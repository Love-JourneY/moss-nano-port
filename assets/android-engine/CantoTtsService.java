// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026  Nija (bubu12) and contributors
//
// CantoTtsService —— 把 canto-tts 注册成【系统 TTS 引擎】。原生 Java。
//
// ⚠️ 本类的每个"奇怪写法"都有来历(都踩过,别"优化"掉):
//   ① onIsLanguageAvailable 里【绝不阻塞】
//      旧实现每问一个语言就 t.join(60_000) 等模型加载 —— 实测系统一次探 ~305 个语言,
//      会占住一批 binder 线程 ⇒ 模型变慢时客户端超时或 ANR。
//      ⇒ 本类只按【模型真实支持的语言】直接作答;"等就绪"只留在 onSynthesizeText。
//   ② onSynthesizeText 里 maxBufferSize 单位是【字节】不是采样点
//      旧代码把它当采样点用 ⇒ ByteArray(n*2) = 2× 上限 ⇒ audioAvailable 抛异常。
//      表现:绑定成功、语言对、speak() 返回 SUCCESS、native 合成也成功,
//      但【永远静音】—— 极难定位。⇒ CantoSynthesizer.chunkForCallback 已处理。
//   ③ onGetLanguage 恒返回 ["zho","CHN",""];换模型时这里要跟着走(见注释)。
//   ④ 合成在【后台线程】做,不阻塞 onSynthesizeText 的调用线程。
//      (旧实现同步加载 114MB 模型 ⇒ 主线程卡住、服务被反复重启。)
//
// 服务声明(四件套,缺一不可)见同目录 AndroidManifest.fragment.xml。
//
// ⚠️ 参数来源(避免硬编码路径):
//   · 模型目录:`getExternalFilesDir(null)/models/canto`(App 私有外部目录,零权限)
//     ⚠️ 不要用 /sdcard/Documents/ —— Android 11+ scoped storage 下会 EACCES,
//        而 sherpa/JNI 打不开模型是直接 SIGABRT(不是抛异常),表现为"莫名 native crash"。
//   · 音色库:`<模型目录>/voicebank/*.json`
//   · G2P .so:`<模型目录>/libcanto_g2p.so`(System.load)
//   · 音素→token:【CantoCantophon(内置三元组表 + added_tokens 88 条)】
//     ⚠️ 2026-09-26 更正:主路径【不是】音节表;旧 syllable-ids.tsv 只作兜底
//     (`<模型目录>/syllable-ids.tsv`)

package canto;

import android.app.Activity;
import android.content.Context;
import android.speech.tts.SynthesisCallback;
import android.speech.tts.SynthesisRequest;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeechService;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class CantoTtsService extends TextToSpeechService {

    private static final String TAG = "CantoTts";
    /** 支持的语言:粤语 + 中文(普通话文本会经 p2y 转成粤语再念) */
    private static final String[] LANGS = { "zho", "yue" };
    private static final String[] COUNTRIES = { "CHN", "HKG" };
    private static final String[] VARIANTS = { "", "" };

    /**
     * ⚠️ 前台服务开关(2026-09-24 加入)。
     *
     * 为什么:实测我们的合成线程会被 ColorOS 的 cgroup freezer 冻在 do_freezer_trap
     *   ⇒ 合成永远完不成(VmSwap 836MB,进程 state=S,wchan=do_freezer_trap)。
     * 已知【5 种反制全部无效】:deviceidle 白名单 / am set-inactive false /
     *   PARTIAL_WAKE_LOCK / 每 8s 重取锁 / setThreadPriority(FOREGROUND)。
     *   ⚠️ 但那 5 种里【没有前台服务】—— 而前台服务正是 Android 官方唯一承诺
     *      "不会被当缓存进程回收/冻结"的机制。
     *
     * 策略:【只在合成期间】进前台(不是常驻)⇒ 不留常驻通知,拔掉即干净。
     *   · 通知用 IMPORTANCE_MIN + 无声音 + 无图标闪烁,尽量不打扰
     *   · 合成结束(onDone/onError/超时)立刻 stopForeground(REMOVE)
     */
    private static final String FGS_CHANNEL = "canto_tts_synth";
    private static final int FGS_NOTIF_ID = 0x63616E74;   // "cant"
    private volatile boolean inForeground = false;

    private volatile CantoSynthesizer synth;
    private volatile CantoVoiceBank bank;
    private volatile File modelDir;
    /** 当前音色名(系统 TTS 没有"音色"概念 ⇒ 我们存 SharedPreferences,见 CantoSettingsActivity) */
    private volatile String voiceName = CantoVoiceBank.DEFAULT_VOICE;  // 原版男声


    /**
     * 输出采样率 —— 必须与【守护侧降采样后的采样率】一致!
     * ⚠️⚠️ 2026-09-26 修真 bug:守护已按 tuning 的 outputSampleRate 降采样,
     *   但 App 侧 callback.start 还硬编码 48000 ⇒ 系统按 48k 播 24k 数据
     *   ⇒ 【声音变慢一倍(像牛叫)】。采样率两端【必须同源】。
     */
    private int outSampleRate() {
        try {
            File f = new File(modelDir, "tuning.properties");
            if (f.isFile()) {
                java.util.Properties p = new java.util.Properties();
                FileInputStream in = new FileInputStream(f);
                try { p.load(in); } finally { in.close(); }
                return Integer.parseInt(p.getProperty("outputSampleRate", "48000"));
            }
        } catch (Throwable ignored) {}
        return 48000;
    }

    /**
     * 🔬 路 A 的【真实场景】验证(2026-09-27):
     *   系统 TTS 引擎被 bind 时【没有 Activity】——
     *   实测 Activity 在前台不被冻、挂 overlay 后后台也不被冻。
     *   ⇒ 那【纯 Service 挂 overlay】行不行?这是能否去 root 的关键。
     *
     * 做法:进前台时顺带挂一个 1x1 透明 overlay(若已授予权限)。
     * ⚠️ 没权限就【静默跳过】—— 不影响主流程,但会打日志(便于诊断)。
     */
    private android.view.View visibilityOverlay;

    private void ensureVisibilityOverlay() {
        // Looper fix: View 操作必须在有 Looper 的线程(主线程)
        new android.os.Handler(getMainLooper()).post(new Runnable() {
            @Override public void run() { ensureVisibilityOverlayOnMain(); }
        });
    }

    private void ensureVisibilityOverlayOnMain() {
        if (visibilityOverlay != null) return;
        try {
            android.view.WindowManager wm =
                    (android.view.WindowManager) getSystemService(WINDOW_SERVICE);
            int type = android.os.Build.VERSION.SDK_INT >= 26
                    ? android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : android.view.WindowManager.LayoutParams.TYPE_PHONE;
            android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams(
                    1, 1, type,
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    android.graphics.PixelFormat.TRANSLUCENT);
            lp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
            lp.alpha = 0.01f;
            visibilityOverlay = new android.view.View(this);
            wm.addView(visibilityOverlay, lp);
            Log.i(TAG, "VISIBILITY_OVERLAY_ADDED type=" + type);
        } catch (Throwable t) {
            Log.w(TAG, "VISIBILITY_OVERLAY_FAILED(没权限?): " + t);
        }
    }

    private void removeVisibilityOverlay() {
        new android.os.Handler(getMainLooper()).post(new Runnable() {
            @Override public void run() {
                try {
                    if (visibilityOverlay != null) {
                        ((android.view.WindowManager) getSystemService(WINDOW_SERVICE))
                                .removeView(visibilityOverlay);
                        visibilityOverlay = null;
                        Log.i(TAG, "VISIBILITY_OVERLAY_REMOVED");
                    }
                } catch (Throwable ignored) {}
            }
        });
    }



    /**
     * ★ 从【模型自己声明的音色列表】里取当前选中的那把(2026-09-28 加)。
     *   优先级:用户上次选的(KEY_VOICE_ZH 存名字)> 第一个有 codes 的音色。
     *   ⚠️ 以前把粤语名(male-default)丢进基础版的 18 把里找 ⇒ 找不到 ⇒
     *      静默回落第一把 ⇒ 用户感觉"只有一个音色且难听"。
     */
    private int[] codesFromModel(CantoModel m) {
        if (m == null || m.voices.isEmpty()) return null;
        String want = "";
        try {
            want = getSharedPreferences(CantoSettingsActivity.PREFS, MODE_PRIVATE)
                    .getString(CantoSettingsActivity.KEY_VOICE_ZH, "");
        } catch (Throwable ignored) {}
        CantoModel.VoiceRef pick = m.pickVoice(this);        // ★ 统一入口
        if (pick == null) return null;
        Log.i(TAG, "音色=" + pick.name + "(" + pick.group + ")");
        return pick.codes;
    }


    /**
     * ★ 基础版的音色 codes(2026-09-28 加)。
     *   基础版 manifest 用 builtin_voices(18 把,每把 prompt_audio_codes 是 [nFrames][16] 的嵌套数组),
     *   不是粤语版的 default_voice(单把)。按名字找;找不到就用第一把。
     */
    private int[] builtinVoiceCodes(File dir, String want) {
        try {
            java.io.File mf = new java.io.File(dir, "browser_poc_manifest.json");
            java.io.FileInputStream in = new java.io.FileInputStream(mf);
            String json;
            try {
                byte[] buf = new byte[(int) mf.length()];
                int n = in.read(buf);
                json = new String(buf, 0, Math.max(0, n), "UTF-8");
            } finally { in.close(); }
            org.json.JSONArray arr = new org.json.JSONObject(json).getJSONArray("builtin_voices");
            org.json.JSONArray pick = null;
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.getJSONObject(i);
                String nm = o.optString("voice", "");
                if (pick == null) pick = o.getJSONArray("prompt_audio_codes");
                if (nm.equalsIgnoreCase(want)) { pick = o.getJSONArray("prompt_audio_codes"); break; }
            }
            if (pick == null) return null;
            // 嵌套 [frames][16] → 压平
            int frames = pick.length();
            int[] out = new int[frames * 16];
            int k = 0;
            for (int f = 0; f < frames; f++) {
                org.json.JSONArray row = pick.getJSONArray(f);
                for (int c = 0; c < 16; c++) out[k++] = row.optInt(c, 0);
            }
            Log.i(TAG, "builtin_voice=" + want + " frames=" + frames);
            return out;
        } catch (Throwable t) {
            Log.w(TAG, "读 builtin_voices 失败 ⇒ 用模型内置先验(无音色)" + t);
            return null;
        }
    }

    /** 读用户选的音色(设置页写的);没选过就用默认 */
    private String preferredVoice() {
        try {
            String v = getSharedPreferences(CantoSettingsActivity.PREFS, MODE_PRIVATE)
                    .getString(CantoSettingsActivity.KEY_VOICE, null);
            return (v != null && !v.isEmpty()) ? v : CantoVoiceBank.DEFAULT_VOICE;
        } catch (Throwable t) {
            return CantoVoiceBank.DEFAULT_VOICE;
        }
    }
    private final Object lock = new Object();

    // ────────────────────────── 生命周期 ──────────────────────────

    @Override public void onCreate() {
        // ★ 路 A:Service 一起来就挂 overlay ⇒ 让本进程【前台可见】⇒ 不被冻
        //   (效果:oom_adj 450→200 · wchan do_freezer_trap→do_epoll_wait)
        ensureVisibilityOverlay();
        super.onCreate();
        Log.i(TAG, "TTS_SERVICE_CREATE");

        // ⚠️ 不在这里加载模型(会阻塞 binder)⇒ 放到后台线程,onSynthesizeText 时才等
        // ⚠️ 不用 lambda:Android bootclasspath 下没有 LambdaMetafactory(实测编译失败)
        new Thread(new Runnable() {
            @Override public void run() { ensureReadySafe(); }
        }, "canto-tts-init").start();
    }

    /**
     * 后台初始化(【只做一次】)。
     * ⚠️ 实测踩过:onCreate 建一个 init 线程 + onSynthesizeText 又建
     *    ⇒ 累积出 12 个 canto-tts-init 线程(每个都加载一遍模型!)
     * ⇒ 用 AtomicBoolean 把"初始化"收敛成一次;失败也【不重试】
     *    (模型缺失是配置问题,重试只会重复报错并吃内存)
     */
    private final java.util.concurrent.atomic.AtomicBoolean initStarted =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private void ensureReadySafe() {
        if (!initStarted.compareAndSet(false, true)) return;   // ★ 只第一个线程干活
        try { ensureReady(); } catch (Throwable t) { Log.e(TAG, "初始化失败", t); }
    }

    private CantoSynthesizer ensureReady() throws Exception {
        CantoSynthesizer s = synth;
        if (s != null) return s;
        synchronized (lock) {
            if (synth != null) return synth;
            // ══════════ ★ 按【语言】选模型与编码器(2026-09-28 加)══════════
            // 同一套 MOSS 图契约,差别只在【文本→id】与【音色来源】:
            //   canto    → models/canto          汉字→粤拼→三元组→added_tokens(音色来自 voicebank)
            //   mandarin → models/base-mandarin  汉字/单词→SentencePiece(音色来自 builtin_voices)
            // ★ 2026-09-28 改【模型驱动】:不再按语言写分支,而是问注册表
            //   注册表会告诉我们:模型目录 / 编码方式 / 该模型能提供哪些音色
            CantoModel model = CantoModel.current(this);
            if (model != null && !model.usable()) {
                Log.w(TAG, "⚠️ 选中的模型不可用(" + model.problem + ")⇒ 试着用别的");
                model = null;
            }
            boolean zhMode = model != null && model.encoding == CantoModel.Encoding.SENTENCEPIECE;
            modelDir = model != null ? model.dir : CantoModelSetup.langDir(this, "canto");
            Log.i(TAG, "模型=" + (model == null ? "(回落粤语)" : model.displayName)
                    + " 编码=" + (zhMode ? "SentencePiece" : "粤语三元组")
                    + " 音色源=" + (model == null ? "voicebank" : model.voiceSource)
                    + " 目录=" + modelDir);

            CantoSynthesizer.G2p g2p;
            int[] codes;
            if (zhMode) {
                bank = null;
                codes = codesFromModel(model);            // ★ 音色【由模型自己声明】
                g2p = CantoSynthesizer.identityG2p();     // 原文直通(SentencePiece 自己编码)
            } else {
                bank = loadVoiceBank(modelDir);
                voiceName = preferredVoice();             // ⚠️ 读用户选择(设置页写的)
                CantoVoiceBank.Voice v = bank.get(voiceName);
                if (v == null) v = bank.defaultVoice();
                codes = v != null ? v.flatten() : null;
                g2p = new CantoG2pJni(modelDir);           // G2P(交叉编译的 .so)⇒ JNI 桥
            }
            // ORT 后端
            // ⚠️⚠️ 日志【必须】接上(2026-09-25 修正):
            //   我原先传 CantoOrtEngine.NOOP ⇒ 把引擎内部所有 [S5]~[S9] 进度日志【全丢了】
            //   ⇒ 表现是"合成卡住但连一行错误都没有" ⇒ 【我一直在盲飞】
            //   现在转发到 logcat,这样能看到它到底卡在 prefill / 循环 / codec 哪一步。
            CantoOrtEngine.Log ortLog = new CantoOrtEngine.Log() {
                @Override public void line(String s) { Log.i("CantoOrt", s); }
            };
            // ⚠️ 基础版【不自带 codec】⇒ 两个语言共用粤语版那份 MOSS-Audio-Tokenizer-Nano
            File codecDir = zhMode ? CantoModelSetup.codecDir(this)
                                   : new File(modelDir, "codec");
            // ⚠️ 目录布局不同:
            //   粤语   <models/canto>/tts/*.onnx   (tts 是指向 MOSS-TTS-Nano-cantophon-ONNX 的软链)
            //   普通话 <models/base-mandarin>/*.onnx (onnx 就在根目录)
            File ttsDir = zhMode ? modelDir : new File(modelDir, "tts");
            Log.i(TAG, "ttsDir=" + ttsDir + " codecDir=" + codecDir);
            CantoOrtBackend backend = new CantoOrtBackend(
                    ttsDir.getAbsolutePath(),
                    codecDir.getAbsolutePath(),
                    0, ortLog);   // 0 ⇒ 用后端默认线程数(实测 Android 上 2 最优)
            backend.load();
            if (zhMode) backend.useSentencePiece(true);   // ★ 切到 SentencePiece 编码
            // p2y(普→粤)与音节表:【普通话模式都不需要】
            P2y p2y = null;
            if (!zhMode) {
                try { p2y = P2y.load(new java.io.FileInputStream(new File(modelDir, "p2y-rules.tsv"))); }
                catch (Exception e) { Log.w(TAG, "p2y 表缺失,跳过普→粤: " + e.getMessage()); }
                // 音节表 —— ⚠️ 表在【模型根目录】,不在 tts/ 子目录(实测踩过 ENOENT)
                CantoTokenTable.fromModelDir(modelDir.getAbsolutePath());
            }

            synth = new CantoSynthesizer(backend, g2p, p2y, codes);
            Log.i(TAG, "TTS_SERVICE_READY lang=" + (zhMode ? "mandarin" : "canto")
                    + " sampleRate=" + synth.sampleRate()
                    + " voices=" + (bank != null ? bank.names().size() : 0));
            return synth;
        }
    }

    /**
     * 模型目录。【优先内部存储】。
     *
     * ⚠️ 为什么优先内部(2026-09-24 平板实测):
     *   · 从 /sdcard(FUSE MediaProvider)建 4 个 session:10985 ms
     *   · 从 /data/data/PKG/files(真文件系统)建 4 个 session: 3403 ms
     *   ⇒ 快 3.2 倍
     *   · 而且 /sdcard 是 FUSE,不支持软链 —— 我们的 tts/ codec/ 就是软链
     *     (实测:ln -s 在 /sdcard 上 Permission denied)
     *
     * ⚠️ 判据是"模型真的在里面"(能打开 tts/moss_tts_prefill.onnx),
     *   不是"目录存在" —— 目录在但模型没推完是最坑的中间状态。
     * ⚠️ 兜底:内部没有就用外部(install.sh 可能只推了外部)。
     */
    /**
     * 解析模型目录 —— 【委托给 CantoModelSetup.ensure()】。
     * ⚠️ 2026-09-26:原来这段逻辑在本类里(private),导致【Activity 没法触发自拷】,
     *   一键 install 之后内部模型是空的。抽成静态工具后两处共用。
     */
    private File resolveModelDir() {
        return CantoModelSetup.ensure(this);
    }

    private CantoVoiceBank loadVoiceBank(File dir) {
        File vb = new File(dir, "voicebank");
        java.util.List<CantoVoiceBank.Voice> list = new java.util.ArrayList<>();
        File[] fs = vb.listFiles();
        if (fs != null) for (File f : fs) {
            if (!f.getName().endsWith(".json")) continue;
            try { list.add(CantoVoiceBank.parse(new java.io.FileInputStream(f),
                                               f.getName().replace(".json", ""))); }
            catch (Exception e) { Log.w(TAG, "音色 " + f.getName() + " 解析失败", e); }
        }
        return CantoVoiceBank.of(list);
    }

    // ─────────────────── 语言能力(⚠️ 绝不阻塞!) ───────────────────

    /**
     * ★ 当前语言(2026-09-28 改成【按所选模型回答】)。
     *   canto    → yue/HKG(粤语微调)
     *   mandarin → zho/CHN(基础版;它同时支持英文 ⇒ onIsLanguageAvailable 里也放 eng)
     * ⚠️【绝不阻塞】:只读 SharedPreferences,不查模型、不等加载。
     */
    @Override protected String[] onGetLanguage() {
        // ★ 2026-09-28 改成问注册表:模型声明它支持哪些语言,我们照答
        CantoModel m;
        try { m = CantoModel.current(this); } catch (Throwable t) { m = null; }
        java.util.List<String> langs = m == null ? null : m.languages;
        if (langs != null && !langs.isEmpty()) {
            String l0 = langs.get(0).toLowerCase(Locale.ROOT);
            if (l0.startsWith("yue") || l0.startsWith("zh-hk")) return new String[]{"yue","HKG",""};
            if (l0.startsWith("en")) return new String[]{"eng","USA",""};
            if (l0.startsWith("ja")) return new String[]{"jpn","JPN",""};
            return new String[]{"zho","CHN",""};
        }
        // 模型没声明语言 ⇒ 按编码方式推断
        boolean zh = m != null && m.encoding == CantoModel.Encoding.SENTENCEPIECE;
        return zh ? new String[]{ "zho", "CHN", "" } : new String[]{ "yue", "HKG", "" };
    }

    /**
     * ⚠️【不阻塞】:不查模型、不等加载 —— 只按"我们支持的集合"直接答。
     *   (旧实现每问一个语言就等模型加载;系统一次探 ~305 个语言 ⇒ 占住 binder 线程 ⇒ 客户端超时)
     */
    @Override protected int onIsLanguageAvailable(String lang, String country, String variant) {
        if (lang == null) return TextToSpeech.LANG_NOT_SUPPORTED;
        String l = lang.toLowerCase(Locale.ROOT);
        // 粤语:微调版天然支持
        if ("yue".equals(l)) return TextToSpeech.LANG_COUNTRY_AVAILABLE;
        // 中文/英文:要【基础版模型在位】才支持(否则说了却合成不出来 = 静默失败)
        boolean zh = CantoModelSetup.langAvailable(this, "mandarin");
        if ("zho".equals(l) || "cmn".equals(l)) {
            return zh ? TextToSpeech.LANG_COUNTRY_AVAILABLE : TextToSpeech.LANG_NOT_SUPPORTED;
        }
        if ("eng".equals(l)) {
            return zh ? TextToSpeech.LANG_COUNTRY_AVAILABLE : TextToSpeech.LANG_NOT_SUPPORTED;
        }
        for (String x : LANGS) if (x.equals(l)) return TextToSpeech.LANG_COUNTRY_AVAILABLE;
        return TextToSpeech.LANG_NOT_SUPPORTED;
    }

    @Override protected int onLoadLanguage(String lang, String country, String variant) {
        return onIsLanguageAvailable(lang, country, variant);
    }

    // ────────────────────────── 合成 ──────────────────────────

    @Override protected void onSynthesizeText(SynthesisRequest request, SynthesisCallback callback) {
        final String text = request.getCharSequenceText() != null
                ? request.getCharSequenceText().toString() : "";
        Log.i(TAG, "TTS_SYNTH_START chars=" + text.length());

        // ⚠️ 关键:maxBufferSize 单位是【字节】,不是采样点!
        //    旧代码把它当采样点 ⇒ ByteArray(n*2) = 2× 上限 ⇒ audioAvailable 抛异常
        //    ⇒ 表现"绑定成功、合成成功、永远静音"。
        final int maxBytes = callback.getMaxBufferSize();
        // ⚠️⚠️ 2026-09-25 关键修正:【不在这里 start()】!
        //   实测踩过:一开头 start() 然后去合成(守护要 3.9 秒)
        //   ⇒ 系统 TTS 框架等不到音频 ⇒ 客户端 27ms 就 UTTER_DONE
        //   ⇒ 解绑 ⇒ 我们的服务被销毁(TTS_SERVICE_DESTROY)
        //   ⇒ 等合成完(已收到 PCM 583680 字节)时【callback 已经死了】,音频无处可送
        //   ⇒ 正解:【合成完成后】再 start() + audioAvailable() + done()

        // ⚠️ 不用 lambda(同上)
        new Thread(new Runnable() {
          @Override public void run() {
            enterForeground();                            // ★ 防 freezer(实测必需)
            try {
                // ★★ 2026-09-27 架构定案:【只用进程内推理,彻底不依赖 root】。
                //   为什么能去掉 root(实测证据):
                //     ① 无 root 时 App 自己跑: [S7] 1266ms / [S9] 1811ms
                //        而 root 守护路径:       [S7] 1270ms / [S9] 1745ms
                //        ⇒ 【几乎一样】⇒ 守护失去存在理由
                //     ② 不被 ColorOS virtualFreeze 冻,靠的是【常驻 overlay】:
                //        无 overlay + 退后台 ⇒ oom_adj 450 · do_freezer_trap · 卡 6 秒
                //        有 overlay + 退后台 ⇒ oom_adj   0 · do_epoll_wait · 跑完
                //        (见 ensureVisibilityOverlay / onCreate 那次调用)
                // ★★ 2026-09-25 定案:【分段 + 逐段流式交音频】
                //
                //   为什么必须分段(两个约束叠加):
                //     ① 守护侧单次帧数上限(maxFrames,默认 10 ≈ 0.8 秒音频)
                //        —— 因为 App 侧等太久会被 ColorOS 冻住,读不到回传的 PCM
                //     ② 长文本必须能念完
                //   ⇒ 正解:按【切段策略】把文本切成段,【每段单独问守护】,
                //      并且【每段一拿到就 audioAvailable】⇒
                //        · 首段 1.3 秒内就出声(用户听得到,不用干等)
                //        · App 在持续"写音频",不容易被判 idle 冻掉
                //        · 单段窗口 ≤ ~1.3 秒 ⇒ 全程都在冻的窗口内
                // ⚠️ 用 Android 专用粒度:每段 ≤ 6 字
                //   (用户反馈"只听见一个字"的修正 —— 见 CantoSegmenter.ANDROID_GRAIN 的注释)
                java.util.List<String> segs = CantoSegmenter.splitForAndroid(text);
                final int outRate = outSampleRate();
                if (segs.isEmpty()) { callback.start(outRate,
                        android.media.AudioFormat.ENCODING_PCM_16BIT, 2); callback.done(); return; }
                Log.i(TAG, "分段数=" + segs.size() + " 文本长=" + text.length());


                CantoSynthesizer localSyn = null;
                boolean started = false;
                int totalSamples = 0;
                String via = "local";

                for (int si = 0; si < segs.size(); si++) {
                    String seg = segs.get(si);
                    short[] pcm = null;
                    // ★ 只用本地(进程内推理)
                    if (localSyn == null) localSyn = ensureReady();
                    // ⚠️⚠️ 2026-09-28 修两个 bug(删守护时把这两步一起删掉了):
                    //   ① 【音色】必须传进去 —— 传 null 就用默认音色,【选音色失效】
                    //   ② 【降采样】必须做 —— 模型原生是 48kHz,而 callback 声明的是
                    //      tuning 的 outputSampleRate(默认 24000)。
                    //      少了这一步 ⇒ 系统按 24k 播 48k 数据 ⇒
                    //      【半速 + 时长翻倍】= 用户报的「子弹时间 / 合成好慢」。
                    //      (这一步原来在守护里做,守护删了必须搬回来)
                    int[] vcodes = null;
                    if (bank != null) {
                        CantoVoiceBank.Voice vv = bank.get(voiceName);
                        if (vv != null) vcodes = vv.flatten();
                    }
                    if (pcm == null) pcm = localSyn.synthesize(seg,
                            CantoSegmenter.Mode.NONE, 240, true, vcodes);
                    // ★ 剪掉过长内部静音(模型会插长静音 ⇒ 听起来"两字一顿")
                    int maxPause = 180;
                    try {
                        File tf = new File(modelDir, "tuning.properties");
                        if (tf.isFile()) {
                            java.util.Properties pp = new java.util.Properties();
                            FileInputStream in2 = new FileInputStream(tf);
                            try { pp.load(in2); } finally { in2.close(); }
                            maxPause = Integer.parseInt(pp.getProperty("maxPauseMs", "180"));
                        }
                    } catch (Throwable ignored) {}
                    if (pcm != null && maxPause > 0) {
                        int b0 = pcm.length;
                        pcm = CantoResampler.trimLongPauses(pcm, 2, CantoResampler.SRC_RATE, maxPause);
                        if (si == 0 && b0 != pcm.length)
                            Log.i(TAG, "剪静音(上限 " + maxPause + "ms) · 采样 " + b0 + "→" + pcm.length);
                    }
                    // ★ 降采样:48k → outRate(0/48000 = 不降)
                    if (pcm != null && outRate > 0 && outRate != CantoResampler.SRC_RATE) {
                        int before = pcm.length;
                        pcm = CantoResampler.resample(pcm, 2, outRate);
                        if (si == 0) Log.i(TAG, "降采样 " + CantoResampler.SRC_RATE + "→" + outRate
                                + " · 采样 " + before + "→" + pcm.length);
                    }
                    if (!started) {
                        // ★ 首段到位才 start():框架一直在等 ⇒ 不会提前 UTTER_DONE
                        callback.start(outRate, android.media.AudioFormat.ENCODING_PCM_16BIT, 2);
                        started = true;
                    }
                    java.util.List<byte[]> chunks = CantoSynthesizer.chunkForCallback(pcm, maxBytes);
                    for (byte[] c : chunks) {
                        if (callback.audioAvailable(c, 0, c.length) != TextToSpeech.SUCCESS) {
                            Log.e(TAG, "audioAvailable 失败(可能被 stop)");
                            return;
                        }
                    }
                    totalSamples += pcm.length;
                    Log.i(TAG, "第 " + (si+1) + "/" + segs.size() + " 段已交 采样=" + pcm.length);
                }
                if (!started) {
                    callback.start(outRate, android.media.AudioFormat.ENCODING_PCM_16BIT, 2);
                }
                callback.done();
                Log.i(TAG, "合成来源=" + via + " 段数=" + segs.size() + " 总采样=" + totalSamples);
                Log.i(TAG, "TTS_OK chars=" + text.length() + " samples=" + totalSamples);
                Log.i(TAG, "TTS_SYNTH_DONE chars=" + text.length() + " rate=" + outRate);
            } catch (Throwable t) {
                Log.e(TAG, "TTS_SYNTH_FAIL " + t.getClass().getSimpleName() + ": " + t.getMessage(), t);
                try { callback.error(); } catch (Throwable ignored) {}
            } finally {
                // ⚠️ 实测(2026-09-25):把 FGS 做成【常驻】也【不能】免冻 ——
                //   isFreezeExempt 仍为 false,wchan 仍是 do_freezer_trap。
                //   而 AOSP 的 ProcessList 规则里"有前台服务"本是 freeze-exempt 条件之一
                //   ⇒ ColorOS 的 virtualFreeze 无视该判定。
                //   ⇒ 所以维持"仅合成期间进前台"(不为一个无效的保护留常驻通知)。
                exitForeground();
            }
          }
        }, "canto-tts-synth").start();
    }

    /**
     * 进前台 —— 防 ColorOS 的 cgroup freezer。
     * ⚠️ 只在合成期间用,结束立刻退;通知静音、最低优先级、不可点击。
     */
    private void enterForeground() {
        if (inForeground) return;
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (android.os.Build.VERSION.SDK_INT >= 26 && nm != null) {
                android.app.NotificationChannel ch = new android.app.NotificationChannel(
                        FGS_CHANNEL, "粤语合成中", android.app.NotificationManager.IMPORTANCE_MIN);
                ch.setSound(null, null);
                ch.enableVibration(false);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
            android.app.Notification.Builder b = (android.os.Build.VERSION.SDK_INT >= 26)
                    ? new android.app.Notification.Builder(this, FGS_CHANNEL)
                    : new android.app.Notification.Builder(this);
            b.setContentTitle("canto-tts")
             .setContentText("粤语合成中…")
             .setSmallIcon(android.R.drawable.ic_btn_speak_now)
             .setOngoing(true)
             .setPriority(android.app.Notification.PRIORITY_MIN);
            android.app.Notification n = b.build();
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                // ⚠️ Android 10+ 需要显式传类型;14+ 还要求 manifest 里声明过
                startForeground(FGS_NOTIF_ID, n,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(FGS_NOTIF_ID, n);
            }
            inForeground = true;
            Log.i(TAG, "FGS_ENTER(防 freezer, type=mediaPlayback)");
            ensureVisibilityOverlay();   // 🔬 路 A:让进程保持"前台可见"
        } catch (Throwable t) {
            // ⚠️ 失败不致命:退回到普通服务,继续合成(只是可能被冻)
            Log.w(TAG, "startForeground 失败(继续尝试合成): " + t);
        }
    }

    private void exitForeground() {
        if (!inForeground) return;
        try {
            stopForeground(true);
            inForeground = false;
            Log.i(TAG, "FGS_EXIT");
            // ⚠️⚠️ 2026-09-27 修:原来这里【摘掉 overlay】⇒ 合成完就被冻!
            //   实测:VISIBILITY_OVERLAY_ADDED 后 3.7 秒即 REMOVED ⇒ 随即被冻
            //   ⇒ 正确做法:overlay 应该【Service 活着就一直挂】——
            //     它是"让本进程前台可见"的手段,不是"合成期间临时用一下"。
            //   （overlay 在 onDestroy 时才摘)
        } catch (Throwable t) {
            Log.w(TAG, "stopForeground 失败: " + t);
        }
    }

    @Override protected void onStop() {
        Log.i(TAG, "TTS_SYNTH_STOP(被打断)");
        exitForeground();
    }

    @Override public void onDestroy() {
        removeVisibilityOverlay();
        Log.i(TAG, "TTS_SERVICE_DESTROY");
        exitForeground();
        synth = null;
        super.onDestroy();
    }
}
