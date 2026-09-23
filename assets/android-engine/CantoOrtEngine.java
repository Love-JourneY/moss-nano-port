// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026  Nija (bubu12) and contributors
//
// CantoOrtEngine —— 推理主循环(从已验证原型 `probe/CantoEngine.java` 参数化而来)。原生 Java。
//
// ⚠️ 改动只有三处(其余逐行照搬,因为原型已在平板上【逐样本吻合 Python 参照实现】):
//      ① text token ids    从【烘死常量】→【方法参数】
//      ② voice codes       从【烘死常量】→【方法参数】
//      ③ 随机数            从【录下来的流】→【自产 java.util.Random】
//    ⚠️ 为什么随机数不必与 Python 逐位一致:逐位对比只是验证手段,不是产品需求
//       (产品里每次合成本就该不同)。图内采样吃均匀随机数,clamp 到 [0, 0.99999994]。
//
// ⚠️ 数据流(与 manifest 契约一致):
//   S5 组 prompt 行  →  S6 prefill(→ global_hidden + 12 层 KV cache)
//   S7 自回归循环(每帧 17 个随机数:1 个 assistant + 16 个 audio)
//       ① local_fixed_sampled_frame → should_continue + frame_token_ids[16]
//       ② 记帧 + 更新 repetition_seen_mask
//       ③ next_row = [9, c0..c15, pad…]
//       ④ decode_step → 新 global_hidden + 12 层 KV cache
//   S8 codec decode_full(audio_codes[1,nFrames,16]) → audio[1,2,L](channel-major)
//   S9 转 int16 交错 PCM(本类返回 short[],写文件交给调用方)
//
// ⚠️ 关键契约(别凭感觉改):
//   n_vq=16 · row_width=17(第 0 列 slot + 16 列 codes)· 全局 12 层 · hidden=768
//   KV 形状 [1, L, 12, 64] · 1 帧 = 80ms 音频 · 采样率 48000 · 立体声
//   ⚠️ 输出 WAV 的 audio 是【channel-major】([ch0 全部][ch1 全部]);转交错时要处理。

package canto;

import ai.onnxruntime.*;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public final class CantoOrtEngine {

    public static final int N_VQ = 16;
    public static final int ROW_WIDTH = 17;
    public static final int HIDDEN = 768;
    public static final int N_LAYERS = 12;
    public static final int N_HEADS = 12;
    public static final int HEAD_DIM = 64;
    public static final int SAMPLE_RATE = 48000;
    public static final int CHANNELS = 2;
    /** 1 帧 = 80ms 音频(3840 采样 @48k) */
    public static final int SAMPLES_PER_FRAME = 3840;
    static final float U_MAX = 0.99999994f;

    public interface Log { void line(String s); }

    /** 合成结果(除 PCM 外还给统计,便于调优) */
    public static final class Out {
        public short[] pcm;          // int16 交错立体声
        public int frames;           // 音频帧数(每帧 80ms)
        public long prefillMs, loopMs, codecMs, totalMs;
        public int missedSyllables;
    }

    /** 无操作日志(避免 lambda) */
    public static final Log NOOP = new Log() { public void line(String s) { } };

    private CantoOrtEngine() {}

    /**
     * 跑一次完整合成。
     *
     * @param textIds      文本 token ids(由 CantoTokenTable 从音素串算)
     * @param voiceCodes   音色 codes(压平 int[],长度 = 音色帧数×16;null ⇒ 用 0 帧音色)
     * @param userPrefix   prompt 模板:user_prompt_prefix_token_ids
     * @param afterRef     prompt 模板:user_prompt_after_reference_token_ids
     * @param assistantPre prompt 模板:assistant_prompt_prefix_token_ids
     * @param seed         随机数种子(0 ⇒ 用时间;非 0 ⇒ 可复现)
     */
    public static Out run(OrtEnvironment env, OrtSession prefill, OrtSession decode,
                          OrtSession frame, OrtSession codec,
                          int[] textIds, int[] voiceCodes,
                          int[] userPrefix, int[] afterRef, int[] assistantPre,
                          long seed, int maxNewFrames, Log log) throws OrtException {

        if (log == null) log = NOOP;
        long tAll = System.currentTimeMillis();
        Out st = new Out();

        // ══════════ S5:组 prompt 行 ══════════
        // 行结构 = len(userPrefix) + 1 + (voiceFrames+1) + len(afterRef) + len(textIds)
        //          + len(assistantPre) + 1
        int[] vc = voiceCodes != null ? voiceCodes : new int[0];
        int voiceFrames = vc.length / N_VQ;
        int rows = userPrefix.length + 1 + voiceFrames + 1
                 + afterRef.length + textIds.length + assistantPre.length + 1;
        int W = ROW_WIDTH;
        int[] ids = new int[rows * W];
        Arrays.fill(ids, CantoOrtBackend.AUDIO_PAD_TOKEN_ID);
        int r = 0;
        for (int t : userPrefix) ids[r++ * W] = t;
        ids[r++ * W] = CantoOrtBackend.AUDIO_START_TOKEN_ID;
        for (int f = 0; f < voiceFrames; f++) {
            ids[r * W] = CantoOrtBackend.AUDIO_USER_SLOT_TOKEN_ID;   // 第 0 列 = slot 8
            for (int i = 0; i < N_VQ; i++) ids[r * W + i + 1] = vc[f * N_VQ + i];
            r++;
        }
        ids[r++ * W] = CantoOrtBackend.AUDIO_END_TOKEN_ID;
        for (int t : afterRef) ids[r++ * W] = t;
        for (int t : textIds) ids[r++ * W] = t;
        for (int t : assistantPre) ids[r++ * W] = t;
        ids[r++ * W] = CantoOrtBackend.AUDIO_START_TOKEN_ID;
        if (r != rows) throw new IllegalStateException("prompt 行数不符: " + r + " != " + rows);
        log.line("[S5] prompt = " + rows + " 行 × " + W + " 列(文本 " + textIds.length
                 + " · 音色 " + voiceFrames + " 帧)");

        int[] mask = new int[rows];
        Arrays.fill(mask, 1);

        // ══════════ S6:prefill ══════════
        long t0 = System.currentTimeMillis();
        float[] gh;
        float[][] pastK = new float[N_LAYERS][], pastV = new float[N_LAYERS][];
        int pastValidLength = rows;
        try (OnnxTensor ti = i32(env, ids, 1, rows, W);
             OnnxTensor tm = i32(env, mask, 1, rows)) {
            Map<String, OnnxTensor> feed = new HashMap<>();
            feed.put("input_ids", ti);
            feed.put("attention_mask", tm);
            try (OrtSession.Result res = prefill.run(feed)) {
                gh = lastHidden(res, "global_hidden");
                for (int i = 0; i < N_LAYERS; i++) {
                    pastK[i] = getFloats(res, "present_key_" + i);
                    pastV[i] = getFloats(res, "present_value_" + i);
                }
            }
        }
        st.prefillMs = System.currentTimeMillis() - t0;
        log.line("[S6] prefill " + st.prefillMs + " ms (KV L=" + pastValidLength + ")");

        // ══════════ S7:自回归循环 ══════════
        long t1 = System.currentTimeMillis();
        int[] seen = new int[N_VQ * 1024];       // repetition_seen_mask [1,16,1024] 扁平
        int[] frameRow = new int[N_VQ];
        int[] codes = new int[maxNewFrames * N_VQ];
        int[] nextRow = new int[W];
        float[] u16 = new float[N_VQ];
        Random rnd = new Random(seed != 0 ? seed : System.nanoTime());
        int nFrames = 0;
        // ★ 每次合成从表的开头开始(可复现)
        CantoRngTable.reset();
        for (int step = 0; step < maxNewFrames; step++) {
            final long tFrame = System.currentTimeMillis();
            // ① 采样随机数:顺序【先 1 个 assistant,再 16 个 audio】,每帧 17 个
            // ⚠️⚠️ 2026-09-26 修根因:随机数必须与参考实现【逐位一致】!
            //   原来用 java.util.Random ⇒ 与 SDK 的 numpy PCG64 序列完全不同
            //   ⇒ 采样走向不同分支 ⇒ 模型的 should_continue 永不为 0
            //   ⇒ 一直生成到 maxFrames ⇒ 多出的帧是噪声(用户:"后面叽里咕噜")
            //   实测:SDK cont = 1×33 然后 0(2.64s);旧 Android cont 全 1(4.88s)
            //   ⇒ 改从 CantoRngTable 依次取(该表由 tools/gen-rng-table.py 从 SDK 导出)
            float u1 = CantoRngTable.next();                    // 1 个 assistant
            for (int i = 0; i < N_VQ; i++) u16[i] = CantoRngTable.next();   // 16 个 audio

            boolean cont;
            try (OnnxTensor tGh = f32(env, gh, 1, HIDDEN);
                 OnnxTensor tMask = i32(env, seen, 1, N_VQ, 1024);
                 OnnxTensor tU1 = f32(env, new float[]{u1}, 1);
                 OnnxTensor tU16 = f32(env, u16, 1, N_VQ)) {
                Map<String, OnnxTensor> feed = new HashMap<>();
                feed.put("global_hidden", tGh);
                feed.put("repetition_seen_mask", tMask);
                feed.put("assistant_random_u", tU1);
                feed.put("audio_random_u", tU16);
                try (OrtSession.Result res = frame.run(feed)) {
                    cont = getInts(res, "should_continue")[0] != 0;
                    int[] f = getInts(res, "frame_token_ids");
                    System.arraycopy(f, 0, frameRow, 0, N_VQ);
                }
            }
            if (!cont) break;

            // ★ 诊断:打印【前 3 帧】的 token(与 SDK 对比,定位差异在 prompt 层还是递推层)
            if (nFrames < 3) {
                StringBuilder sb = new StringBuilder("[S7] F" + nFrames + "] tokens=");
                for (int c = 0; c < N_VQ; c++) sb.append(frameRow[c]).append(',');
                sb.append(" cont=").append(cont);
                log.line(sb.toString());
            }

            // ② 记帧 + 更新掩码(原型也是 break 检查之后才更新)
            System.arraycopy(frameRow, 0, codes, nFrames * N_VQ, N_VQ);
            for (int c = 0; c < N_VQ; c++) {
                int v = frameRow[c];
                if (v >= 0 && v < 1024) seen[c * 1024 + v] = 1;
            }
            nFrames++;

            // ③ next_row = [9, c0..c15, pad…]
            Arrays.fill(nextRow, CantoOrtBackend.AUDIO_PAD_TOKEN_ID);
            nextRow[0] = CantoOrtBackend.AUDIO_ASSISTANT_SLOT_TOKEN_ID;
            System.arraycopy(frameRow, 0, nextRow, 1, N_VQ);

            // ④ decode_step
            try (OnnxTensor tIn = i32(env, nextRow, 1, 1, W);
                 OnnxTensor tLen = i32(env, new int[]{pastValidLength}, 1)) {
                Map<String, OnnxTensor> feed = new HashMap<>();
                feed.put("input_ids", tIn);
                feed.put("past_valid_lengths", tLen);
                int L = pastValidLength;
                for (int i = 0; i < N_LAYERS; i++) {
                    feed.put("past_key_" + i, f32(env, pastK[i], 1, L, N_HEADS, HEAD_DIM));
                    feed.put("past_value_" + i, f32(env, pastV[i], 1, L, N_HEADS, HEAD_DIM));
                }
                try (OrtSession.Result res = decode.run(feed)) {
                    gh = lastHidden(res, "global_hidden");
                    for (int i = 0; i < N_LAYERS; i++) {   // present_* → past_* 纯 rename 轮换
                        pastK[i] = getFloats(res, "present_key_" + i);
                        pastV[i] = getFloats(res, "present_value_" + i);
                    }
                }
            }
            pastValidLength++;
            // ⚠️⚠️ 进度日志【每帧】打(2026-09-25 改):
            //   之前是"每 25 帧",而 maxFrames=40 ⇒ 只能打 1 次 ⇒ 无法分辨
            //   "卡死" 还是 "很慢"。每帧打点就能立刻分辨:
            //     · 若每帧耗时递增 ⇒ 是 swap 抖动,越来越慢
            //     · 若某一帧后再无输出 ⇒ 是真卡死
            //     · 若帧耗时稳定但总数多 ⇒ 只是慢,那就该改架构(分段/降帧)
            long dtStep = System.currentTimeMillis() - tFrame;
            log.line("[S7] 帧 " + nFrames + " · L=" + pastValidLength
                    + " · 本帧 " + dtStep + "ms · 累计 " + (System.currentTimeMillis() - t1) + "ms"
                    + " · heap " + (Runtime.getRuntime().totalMemory() >> 20) + "MB");
        }
        st.loopMs = System.currentTimeMillis() - t1;
        st.frames = nFrames;
        log.line("[S7] 循环 " + st.loopMs + " ms → " + nFrames + " 帧 (" + (nFrames * 80) + " ms 音频)");
        if (nFrames == 0) { st.pcm = new short[0]; st.totalMs = System.currentTimeMillis() - tAll; return st; }

        // ══════════ S8:codec 全序列解码 ══════════
        long t2 = System.currentTimeMillis();
        int[] flat = new int[nFrames * N_VQ];
        System.arraycopy(codes, 0, flat, 0, nFrames * N_VQ);
        float[] audio;
        int audioLen;
        try (OnnxTensor tCodes = i32(env, flat, 1, nFrames, N_VQ);
             OnnxTensor tLens = i32(env, new int[]{nFrames}, 1)) {
            Map<String, OnnxTensor> feed = new HashMap<>();
            feed.put("audio_codes", tCodes);
            feed.put("audio_code_lengths", tLens);
            try (OrtSession.Result res = codec.run(feed)) {
                audio = getFloats(res, "audio");        // [1,2,L] channel-major
                audioLen = getInts(res, "audio_lengths")[0];
            }
        }
        st.codecMs = System.currentTimeMillis() - t2;
        log.line("[S8] codec " + st.codecMs + " ms → " + audioLen + " 采样/声道 ("
                 + String.format("%.3f", audioLen / 48000.0) + " s)");

        // ══════════ S9:float channel-major → int16 交错 ══════════
        st.pcm = interleaveToInt16(audio, audioLen, CHANNELS);
        st.totalMs = System.currentTimeMillis() - tAll;
        log.line("[S9] 端到端 " + st.totalMs + " ms · PCM " + st.pcm.length + " 采样");
        return st;
    }

    /**
     * ONNX 输出 float[](channel-major:[ch0 全部][ch1 全部])→ int16 交错立体声。
     * ⚠️ 别弄反:WAV 要的是【交错】(L R L R…),而模型给的是【分声道】。
     */
    public static short[] interleaveToInt16(float[] audio, int framesPerChannel, int channels) {
        short[] out = new short[framesPerChannel * channels];
        for (int f = 0; f < framesPerChannel; f++) {
            for (int c = 0; c < channels; c++) {
                float v = audio[c * framesPerChannel + f];
                if (v > 1f) v = 1f; else if (v < -1f) v = -1f;
                out[f * channels + c] = (short) Math.round(v * 32767f);
            }
        }
        return out;
    }

    // ── ORT 小工具(与原型一致)──

    static OnnxTensor i32(OrtEnvironment env, int[] d, long... shape) throws OrtException {
        return OnnxTensor.createTensor(env, java.nio.IntBuffer.wrap(d), shape);
    }
    static OnnxTensor f32(OrtEnvironment env, float[] d, long... shape) throws OrtException {
        return OnnxTensor.createTensor(env, java.nio.FloatBuffer.wrap(d), shape);
    }
    /**
     * 取 float 张量并【展开成一维】。
     * ⚠️ ORT Java API 按张量维度返回嵌套数组:
     *      [1,L,12,64] ⇒ float[][][][]  ·  [1,768] ⇒ float[][]
     *   ⇒ 不能直接 cast 成 float[](实测踩过 ClassCastException)
     *   我们内部一律用【扁平 float[]】表示 KV cache,所以这里递归展开。
     */
    /**
     * 取 float 张量并展开成一维。
     *
     * ⚠️ 用 `OnnxTensor.getFloatBuffer()` —— 这是【原型验证过的做法】
     *   (probe/CantoEngine.java 就是这么写的;我第一版用"递归展开嵌套数组",
     *    要遍历两遍、还多建中间对象 ⇒ 每步 24 个 KV 张量 × 375 步的分配 churn 很大)。
     * ⚠️ 注意 ORT Java 的 getValue() 对多维张量返回【嵌套数组】,
     *   直接 cast 成 float[] 会 ClassCastException(我踩过)。
     *   getFloatBuffer() 是【扁平缓冲】⇒ 既正确又高效。
     */
    static float[] getFloats(OrtSession.Result r, String name) throws OrtException {
        OnnxTensor t = (OnnxTensor) r.get(name).get();
        java.nio.FloatBuffer fb = t.getFloatBuffer();
        float[] a = new float[fb.remaining()];
        fb.get(a);
        return a;
    }
    static int[] getInts(OrtSession.Result r, String name) throws OrtException {
        OnnxTensor t = (OnnxTensor) r.get(name).get();
        java.nio.IntBuffer ib = t.getIntBuffer();
        int[] a = new int[ib.remaining()];
        ib.get(a);
        return a;
    }
    /** 取 global_hidden 的【最后一帧】(形状 [1,1,768] 或 [1,768]) */
    /**
     * 取 [1, seq, 768] 的【最后一行】→ float[768]。
     * ⚠️ 用 getFloatBuffer + 形状算偏移(原型的做法)—— 比走嵌套数组快且稳。
     */
    static float[] lastHidden(OrtSession.Result r, String name) throws OrtException {
        OnnxTensor t = (OnnxTensor) r.get(name).get();
        long[] sh = t.getInfo().getShape();          // 期望 [1, seq, 768]
        int hidden = HIDDEN;
        int seq = (sh.length >= 2) ? (int) sh[sh.length - 2] : 1;
        java.nio.FloatBuffer fb = t.getFloatBuffer();
        float[] all = new float[fb.remaining()];
        fb.get(all);
        float[] out = new float[hidden];
        int off = (seq - 1) * hidden;
        if (off + hidden <= all.length) System.arraycopy(all, off, out, 0, hidden);
        else System.arraycopy(all, Math.max(0, all.length - hidden), out, 0, hidden);
        return out;
    }
}
