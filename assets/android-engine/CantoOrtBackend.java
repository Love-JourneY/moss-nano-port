// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026  Nija (bubu12) and contributors
//
// CantoOrtBackend —— 真 ONNX Runtime 推理后端(实现 CantoSynthesizer.Backend)。原生 Java。
//
// ⚠️ 来源:本类是从已验证的原型 `~/dev/android-moss-nano-port/probe/CantoEngine.java`(300 行,
//    平板实测【逐样本吻合 Python 参照实现】)【参数化】而来。改动只有三处:
//      ① 文本 token ids 从【烘死的常量】→【方法参数】(由 G2P + 音节表算)
//      ② 音色 codes   从【烘死的常量】→【方法参数】(由音色库给)
//      ③ 随机数       从【录下来的流】→【自产】(每次不同;要复现就固定 seed)
//    ⚠️ 为什么随机数不必与 Python 逐位一致:
//       逐位对比只是【验证手段】,不是产品需求 —— 产品里每次合成本就该不同。
//       图内采样吃的是均匀随机数,clamp 到 [0, 0.99999994](上界开区间)。
//
// ⚠️ 图与 Session(4 个,别多建):
//   ttsDir/moss_tts_prefill.onnx                    ← 文字 → 首帧 + KV cache
//   ttsDir/moss_tts_decode_step.onnx                ← 逐帧解码(KV cache 进出)
//   ttsDir/moss_tts_local_fixed_sampled_frame.onnx  ← ★采样【烘在图里】,只喂随机数
//   codecDir/moss_audio_tokenizer_decode_full.onnx  ← audio tokens → PCM
//   ⚠️ 另 4 个图(encode / decode_step / local_decoder / local_cached_step)【用不到】:
//      encode 只在"从参照音频编 codes"时用(音色库已预编码);其余是降级分支。
//      ⇒ 不加载它们,省内存(但 data/*.shared.data 不能省 —— 见 README)。
//
// ⚠️ 线程数:SDK 默认 4;平板实测【4 最优】(6/8 明显劣化,内存带宽瓶颈)。

package canto;

import ai.onnxruntime.*;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public final class CantoOrtBackend implements CantoSynthesizer.Backend {

    /** 无操作日志(避免 lambda:Android bootclasspath 下无 LambdaMetafactory) */
    public static final CantoOrtEngine.Log NOOP = new CantoOrtEngine.Log() {
        public void line(String s) { }
    };

    // ── 图里烘死的常量(来自 browser_poc_manifest.json,不是我们发明的)──
    static final int N_VQ = 16;             // 每帧 16 个 VQ 通道
    static final int ROW_WIDTH = 17;        // 1 col slot + 16 cols codes
    static final int AUDIO_PAD_TOKEN_ID = 1024;
    static final int AUDIO_START_TOKEN_ID = 6;
    static final int AUDIO_END_TOKEN_ID = 7;
    static final int AUDIO_USER_SLOT_TOKEN_ID = 8;
    static final int AUDIO_ASSISTANT_SLOT_TOKEN_ID = 9;
    /**
     * 单次合成的帧数上限(375 帧 × 80ms ≈ 30s 音频)。
     *
     * ⚠️ 2026-09-25:实测发现【循环跑到一半会被 ColorOS 的 cgroup freezer 冻住】
     *   (prefill 464ms 正常,但循环开始后进程变成 do_freezer_trap)。
     *   ⇒ 假设:冻结按"不活跃时长"触发 ⇒ 【帧数越少越可能在冻结前跑完】。
     *   ⇒ 所以做成可配置,默认降到 40 帧(3.2s 音频),让单次调用短平快。
     *   ⚠️ 副作用:长文本会被截断 ⇒ 调用方要分段合成、多次调用。
     *      (这也正好与我们的"切段策略"配合:一段一段来,每段都在冻结前跑完)
     */
    /**
     * 默认单次帧数上限。⚠️ 实际用的是 {@link #maxFrames} —— 它会从 tuning.properties 读。
     * ⚠️ 2026-09-25 修 bug:我之前把它写成 static final 常量,
     *   注释说"可被 tuning.properties 覆盖"但【根本没连上】
     *   ⇒ 实测:不管 tuning.properties 写 10 还是 40,守护输出都是 291840 采样(40 帧)
     */
    static final int MAX_NEW_FRAMES_DEFAULT = 40;
    /** 运行时生效的帧数上限(load() 时从 tuning.properties 读,缺省用默认值) */
    private int maxFrames = MAX_NEW_FRAMES_DEFAULT;
    /** 随机数上界(开区间;与 numpy 参照实现一致) */
    static final float U_MAX = 0.99999994f;

    private final String ttsDir, codecDir;
    private final int threads;
    private int threads0 = 2;
    private final CantoOrtEngine.Log log;

    private OrtEnvironment env;
    private OrtSession prefill, decode, frame, codec;
    private int[] userPromptPrefix, userPromptAfterRef, assistantPromptPrefix;
    private CantoTokenTable tokenTable;
    /**
     * ★ 普通话/英文模式(2026-09-28 加)。
     *   非 null ⇒ 用 SentencePiece 编码文本(基础版模型)/ null ⇒ 用粤语音素三元组表。
     *   见 CantoSentencePiece 的"精度声明"。
     */
    private CantoSentencePiece sentencePiece;
    /** 随机数种子。0 = 用固定默认值(可预测);显式设非 0 则复现该次合成 */
    private volatile long seed = DEFAULT_SEED;
    /** 默认固定种子 —— 让同一句话的时长/延迟可预测(系统 TTS 需要) */
    public static final long DEFAULT_SEED = 20260923L;

    /** 换种子(要多样性时用;0 = 回到固定默认) */
    public void setSeed(long s) { this.seed = (s == 0 ? DEFAULT_SEED : s); }
    private long currentSeed() { return seed; }

    public CantoOrtBackend(String ttsDir, String codecDir, int threads, CantoOrtEngine.Log log) {
        this.ttsDir = ttsDir; this.codecDir = codecDir;
        // ⚠️ 2026-09-24 改:默认 4 → 2。实测(Android bionic ORT, 8 核):
        //   threads=1 total 7748ms / 2 → 7328ms / 4 → 7285ms / 8 → 9989ms
        //   ⚠️ 关键:loop(占大头)在 1~2 线程最快,4 慢、8 差一倍
        //   ⚠️ 1/2/4 的【输出 md5 完全相同】⇒ 线程数不影响数值,只影响速度
        //   ⚠️ chroot/glibc 时代的"4 最优、8 慢 2.3×"结论【在 Android 上不成立】,别照抄
        //   ⇒ 想更快可分图设置(prefill 4 / loop 1~2),但那要两套 SessionOptions
        this.threads = threads;
        this.threads0 = 2;
        this.log = log != null ? log : NOOP;
    }

    /** 加载模型(幂等)。⚠️ 这是最慢的一步(平板实测 ~3.5s)⇒ 引擎进程里只做一次。 */
    public synchronized void load() throws OrtException {
        if (env != null) return;
        env = OrtEnvironment.getEnvironment();
        // ★ 先读调参(后面 threads / arena / memPattern / optLevel 都用它)
        java.util.Properties tp = loadTuning(ttsDir);
        OrtSession.SessionOptions so = new OrtSession.SessionOptions();
        // ⚠️⚠️ 2026-09-27 加【Execution Provider 可切换】——
        //   这是【框架层】唯一可能"立竿见影提速"而不动模型的手段。
        //   实测:我们的 libonnxruntime.so 内含 Nnapi/Xnnpack/QNN 三个 EP 字符串。
        //   tuning.properties 的 `ep` 键:
        //     cpu(默认) / xnnpack / nnapi / qnn
        //   ⚠️ 顺序:先 add 的优先;CPU 必须【最后】add(它是兜底)
        // ★ seed 开关:0=每次逐位相同(默认,可复现)· 1=每次略有变化(更自然)
        try {
            CantoRngTable.vary = "1".equals(tp.getProperty("seedVariation", "0").trim());
            log.line("[CantoOrtBackend] seedVariation=" + (CantoRngTable.vary ? "开(每次略变)" : "关(逐位可复现)"));
        } catch (Throwable ignored) {}
        String ep = "cpu";
        try { ep = tp.getProperty("ep", "cpu").trim().toLowerCase(); } catch (Throwable ignored) {}
        boolean epOk = false;
        try {
            if ("xnnpack".equals(ep)) {
                so.addXnnpack(new java.util.HashMap<String, String>());
                epOk = true;
                log.line("[CantoOrtBackend] 已请求 XNNPACK EP(ARM CPU 优化)");
            } else if ("nnapi".equals(ep)) {
                // ⚠️ ORT Java 的 NNAPI 签名是 addNnapi(EnumSet<NNAPIFlags>),不是 Map
                // ⚠️ NNAPIFlags 在 ai.onnxruntime.providers 包下(不是 SessionOptions 内部类)
                java.util.EnumSet<ai.onnxruntime.providers.NNAPIFlags> flags =
                        java.util.EnumSet.of(ai.onnxruntime.providers.NNAPIFlags.USE_FP16);
                so.addNnapi(flags);
                epOk = true;
                log.line("[CantoOrtBackend] 已请求 NNAPI EP(Android NPU/GPU, USE_FP16)");
            } else if ("qnn".equals(ep)) {
                so.addQnn(new java.util.HashMap<String, String>());
                epOk = true;
                log.line("[CantoOrtBackend] 已请求 QNN EP(高通 NPU)");
            }
        } catch (Throwable t) {
            log.line("[CantoOrtBackend] ⚠️ EP " + ep + " 添加失败 ⇒ 回落 CPU: " + t);
            epOk = false;
        }
        log.line("[CantoOrtBackend] EP=" + ep + (epOk ? "(已请求)" : "(CPU)"));
        // threads:显式传参优先;否则看 tuning.properties:threads
        int th = threads;
        if (th <= 0) {
            try { th = Integer.parseInt(tp.getProperty("threads", String.valueOf(threads0))); }
            catch (Throwable t) { th = threads0; }
        }
        so.setIntraOpNumThreads(th);
        so.setInterOpNumThreads(1);
        // ⚠️ 2026-09-24 内存调优(平板实测:683MB 模型会被换出到 swap ⇒ 被冻结):
        //   · setMemoryPatternOptimization(false):不做内存模式预分配
        //     (它会给每个输入形状预留一块 arena ⇒ 浪费大量内存)
        //   · setCPUArenaAllocator(false):每算子独立分配,峰值更低(慢一点但省内存)
        //   ⇒ 目的:把常驻内存压到 swap 阈值以下,避免"换出 → 冻结 → 永不完成"
        // ⚠️⚠️ 2026-09-25:这两个也做成可配置 —— 因为我之前"以为关掉省内存"可能是【反了】。
        //   实测:加载后【匿名内存 1.05GB】(vs 文件映射仅 117MB)
        //   ⇒ 若"每算子各自分配"会让所有缓冲同时存活,那关掉 arena【反而更费】。
        //   ⇒ 两种都试:system property `canto.arena` = 1|0,`canto.memPattern` = 1|0
        //   默认【开启】(即 ORT 的默认行为,实测最省:916MB vs 1055MB)。
        // ⚠️ 从 <模型根>/tuning.properties 读(2026-09-25 修正):
        //   之前用 System.getProperty ⇒ 而 Android 的 `setprop` 【不影响】Java 系统属性
        //   ⇒ 我的开关是【死的】(实测白跑一轮)。
        //   改成读文件:换设备/调参只要改一个 properties,不用重编 APK。
        boolean useArena = !"0".equals(tp.getProperty("arena", "1"));
        boolean useMemPat = !"0".equals(tp.getProperty("memPattern", "1"));
        so.setMemoryPatternOptimization(useMemPat);
        so.setCPUArenaAllocator(useArena);
        // ⚠️⚠️ 2026-09-25:优化级别做成可配置,默认【BASIC_OPT】。
        //
        //   为什么(这是本轮的新机制假设,之前没试过):
        //     实测内存构成:RssFile 只有 73MB(权重是 mmap 的,可干净丢弃),
        //     但 【VmSwap 837MB】 ⇒ 有 ~1GB 的【匿名内存】被换出。
        //     模型权重才 683MB 且是文件映射 ⇒ 那 1GB 匿名内存【只能是 ORT 造出来的】:
        //       ALL_OPT 会做算子融合 / 权重转置 / 常量折叠
        //       ⇒ 把权重【复制进匿名内存】⇒ 匿名内存必须走 swap ⇒ 被压 ⇒ 被冻
        //     ⇒ 降低优化级别,让权重尽量留在【文件映射】里(可以直接丢弃,不用 swap)
        //
        //   ⚠️ 实测结果(2026-09-25 平板):
        //     optLevel=basic + arena=off ⇒ Anon 1,055 MB   ← 我原以为这样省,【其实更费】
        //     optLevel=all   + arena=on  ⇒ Anon   916 MB   ← ORT 默认,【最省】
        //   ⇒ 结论:ORT 的默认配置就是最优的,我"手动优化"反而变差 139MB
        //   ⇒ 默认已改回 ORT 默认(all + arena on + memPattern on)
        //   ⚠️ 想覆盖:在 Application 里 System.setProperty(...),
        //      或用 `adb shell am start -D -n ... ` 加 -D(Android 下 setprop 无效)
        String lvl = tp.getProperty("optLevel", "all").toLowerCase();
        OrtSession.SessionOptions.OptLevel ol;
        if ("all".equals(lvl)) ol = OrtSession.SessionOptions.OptLevel.ALL_OPT;
        else if ("extended".equals(lvl)) ol = OrtSession.SessionOptions.OptLevel.EXTENDED_OPT;
        else if ("none".equals(lvl)) ol = OrtSession.SessionOptions.OptLevel.NO_OPT;
        else ol = OrtSession.SessionOptions.OptLevel.BASIC_OPT;
        so.setOptimizationLevel(ol);
        // ★ 读 maxFrames(之前漏了这一步 ⇒ 开关是死的)
        try { maxFrames = Integer.parseInt(tp.getProperty("maxFrames",
                String.valueOf(MAX_NEW_FRAMES_DEFAULT))); }
        catch (Throwable t) { maxFrames = MAX_NEW_FRAMES_DEFAULT; }
        log.line("[CantoOrtBackend] tuning: OptLevel=" + ol + " arena=" + useArena
                + " memPattern=" + useMemPat + " threads=" + threads
                + " maxFrames=" + maxFrames + " (来源 " + tuningSource + ")");
        prefill = env.createSession(ttsDir + "/moss_tts_prefill.onnx", so);
        decode  = env.createSession(ttsDir + "/moss_tts_decode_step.onnx", so);
        frame   = env.createSession(ttsDir + "/moss_tts_local_fixed_sampled_frame.onnx", so);
        codec   = env.createSession(codecDir + "/moss_audio_tokenizer_decode_full.onnx", so);
        // prompt 模板常量:从 manifest 读(不硬编码,便于模型升级)
        try {
            userPromptPrefix      = PromptTemplates.read(ttsDir, "user_prompt_prefix_token_ids");
            userPromptAfterRef    = PromptTemplates.read(ttsDir, "user_prompt_after_reference_token_ids");
            assistantPromptPrefix = PromptTemplates.read(ttsDir, "assistant_prompt_prefix_token_ids");
        } catch (Exception e) {
            throw new OrtException("读 prompt 模板失败: " + e.getMessage());
        }
        log.line("[CantoOrtBackend] 已加载 4 个 Session(threads=" + threads + ")");
    }

    @Override public int sampleRate() { return 48000; }

    /**
     * ★ 切到【普通话/英文】模式:文本用 SentencePiece 编码(而不是粤语音素三元组)。
     * 由调用方按 Locale 决定;详见 CantoSentencePiece 的精度声明。
     */
    public void useSentencePiece(boolean on) {
        if (!on) { sentencePiece = null; return; }
        try {
            sentencePiece = CantoSentencePiece.fromModelDir(ttsDir);
            log.line("[CantoOrtBackend] SentencePiece 就绪 vocab=" + sentencePiece.vocabSize
                    + "(近似实现,见 CantoSentencePiece 精度声明)");
        } catch (Throwable t) {
            log.line("[CantoOrtBackend] ⚠️ SentencePiece 词表加载失败 ⇒ 回落粤语编码:" + t);
            sentencePiece = null;
        }
    }

    public short[] synthesize(String textOrPhonemes, int[] voiceCodes) throws Exception {
        load();
        int[] textIds;
        if (sentencePiece != null) {
            // ★ 普通话/英文:直接对【原文】做 SentencePiece
            textIds = sentencePiece.encode(textOrPhonemes);
        } else {
            // 粤语:音节表查表。表按模型目录放,便于模型升级。
            if (tokenTable == null) tokenTable = CantoTokenTable.fromModelDir(ttsDir);
            textIds = tokenTable.encode(textOrPhonemes);
        }
        // ⚠️ 真正的推理在 CantoOrtEngine(从原型 CantoEngine 搬来,已参数化)
        CantoOrtEngine.Out out = CantoOrtEngine.run(env, prefill, decode, frame, codec,
                textIds, voiceCodes,
                userPromptPrefix, userPromptAfterRef, assistantPromptPrefix,
                // ⚠️ 2026-09-24 改:用【固定 seed】而不是 System.nanoTime()。
                //    理由(实测根因):
                //      should_continue 是【图内由文本侧采样结果推出来的】,
                //      而文本侧采样吃【我们喂的 u】⇒ 【终止条件本身是随机的】
                //      ⇒ 同一句话的音频时长【天生会波动】(实测 7.52 / 7.12 / 9.76s)
                //    ⇒ 作为【系统 TTS 引擎】,可预测的延迟与时长比"每次都不同"更重要
                //      (浏览器的 speechSynthesis 有回调时序、别的 App 可能拼接音频)
                //    ⇒ 要"多样性"时再按句重播种(见 setSeed)
                currentSeed(),
                maxFrames, log);
        return out.pcm;
    }

    /** 释放 Session(引擎进程退出前调) */
    public synchronized void close() {
        try { if (codec != null) codec.close(); } catch (Exception ignored) {}
        try { if (frame != null) frame.close(); } catch (Exception ignored) {}
        try { if (decode != null) decode.close(); } catch (Exception ignored) {}
        try { if (prefill != null) prefill.close(); } catch (Exception ignored) {}
        prefill = decode = frame = codec = null;
    }

    /**
     * 读调参文件 <ttsDir 的父目录>/tuning.properties。
     * 支持的键(全部可选,缺省即 ORT 默认):
     *   optLevel   = all|extended|basic|none   (默认 all —— 【实测最省内存】)
     *   arena      = 1|0                       (默认 1 —— 【实测比 0 少 139MB】)
     *   memPattern = 1|0                       (默认 1)
     *   threads    = N                         (默认 0 ⇒ 用后端的默认 2)
     *   maxFrames  = N                         (默认 40)
     * ⚠️ 为什么用文件而不是 setprop:System.getProperty 不读 Android setprop(实测)。
     */
    private String tuningSource = "默认";
    private java.util.Properties loadTuning(String dir) {
        java.util.Properties p = new java.util.Properties();
        try {
            java.io.File root = new java.io.File(dir).getParentFile();
            java.io.File[] cands = {
                new java.io.File(root, "tuning.properties"),
                new java.io.File(dir, "tuning.properties"),
            };
            for (java.io.File f : cands) {
                if (f.isFile()) {
                    java.io.FileInputStream in = new java.io.FileInputStream(f);
                    try { p.load(in); } finally { in.close(); }
                    tuningSource = f.getAbsolutePath();
                    return p;
                }
            }
        } catch (Throwable t) {
            // 读不到就用默认,不影响功能
        }
        return p;
    }

    /** 读 prompt 模板常量(从 manifest 或独立 json) */
    static final class PromptTemplates {
        static int[] read(String ttsDir, String key) throws IOException, org.json.JSONException {
            // ⚠️ manifest 在【模型根目录】(=<ttsDir> 的父目录),不在 tts/ 里!
            //   实测踩过:把它当 <ttsDir>/browser_poc_manifest.json ⇒ ENOENT
            //   优先级:模型根的独立 prompt-templates.json > 模型根的 browser_poc_manifest.json
            //          > tts/ 里(兜底,防目录布局变化)
            java.io.File root = new java.io.File(ttsDir).getParentFile();
            java.io.File[] cands = {
                new java.io.File(root, "prompt-templates.json"),
                new java.io.File(root, "browser_poc_manifest.json"),
                new java.io.File(ttsDir, "prompt-templates.json"),
                new java.io.File(ttsDir, "browser_poc_manifest.json"),
            };
            String json = null;
            for (java.io.File f : cands) {
                if (f.isFile()) {
                    json = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
                    System.out.println("[CantoOrtBackend] prompt 模板取自 " + f);
                    break;
                }
            }
            if (json == null) throw new IOException("找不到 prompt 模板(找过 " + java.util.Arrays.toString(cands) + ")");
            org.json.JSONObject o = new org.json.JSONObject(json);
            org.json.JSONObject tpl = o.optJSONObject("prompt_templates");
            if (tpl == null) tpl = o;      // 独立文件时直接就是那一层
            org.json.JSONArray a = tpl.getJSONArray(key);
            int[] out = new int[a.length()];
            for (int i = 0; i < out.length; i++) out[i] = a.getInt(i);
            return out;
        }
    }
}
