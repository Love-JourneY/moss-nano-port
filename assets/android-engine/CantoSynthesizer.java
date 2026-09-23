// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026  Nija (bubu12) and contributors
//
// CantoSynthesizer —— 把已就绪的模块串成一条合成链。原生 Java。
//
// 数据流(与 Python 版 tts_stream.py 同一语义):
//   文本
//     │
//     ├─(可选) P2y      普→粤        [已有:P2y.java]
//     ├─(可选) Segmenter 切段         [已有:CantoSegmenter.java]
//     │
//     ▼ 逐段
//   G2P(粤拼音素)                    [已有:libcanto_g2p.so,走 JNI 或 app_process 同进程]
//     ▼
//   三元组 → token ids                ⚠️ 2026-09-26 更正:不是"音节级"!
//                                      正确是【cantophon 三元组级】:
//                                        gam1 → <o-g> <r-am> <t1> → 16388,16415,16464
//                                      见 CantoCantophon.java
//                                      (旧 syllable-ids.tsv 只作兜底)
//     ▼
//   【prefill】→ 循环【decode_step】  [ORT Java API]
//     ▼
//   audio token ids(16 通道 × 帧)
//     ▼
//   【audio_tokenizer decode】→ PCM   [ORT Java API]
//     ▼
//   int16 PCM → 交播放(TextToSpeechService 的 callback 或 AudioTrack)
//
// ⚠️ 设计原则(照 Nija 的"极简"指示):
//   1) 【本类不碰 Android API】—— 只接收"数据"与"回调",这样它能在
//      app_process / instrumentation / JVM 三种环境里测(与已验证的冒烟测试同路线)。
//   2) 【依赖注入】—— ONNX Session 与 G2P 都从外面传进来(接口化),
//      这样"用真 ORT"还是"用桩"由调用方决定,本类可独立测。
//   3) 【失败要可见】—— 所有异常都往上抛,不吞(上游踩过"静默失败"的坑)。
//   4) 【音色是参数】—— defaultVoiceCodes 可换 ⇒ 支持音色选择(目标③)。

package canto;

import java.util.List;

public final class CantoSynthesizer {

    /** ONNX 推理后端(由调用方实现:真 ORT / 桩) */
    public interface Backend {
        /**
         * 合成一段文本为 PCM。
         * @param phonemes  粤拼音素串(空格分隔音节,如 "gam1 jat6 tin1 hei3")
         * @param voiceCodes 音色 codes(压平后的 int[];null ⇒ 用后端内置默认)
         * @return int16 单/双声道 PCM(采样率见 sampleRate())
         */
        short[] synthesize(String phonemes, int[] voiceCodes) throws Exception;
        /** 输出采样率(Hz) */
        int sampleRate();
    }

    /** 文本→音素(由 libcanto_g2p 的 JNI 或同进程调用实现) */
    /**
     * ★【原文直通】的 G2p(2026-09-28 加)。
     *   普通话/英文走 SentencePiece,不需要汉字→粤拼这一步 ⇒ 原样返回。
     *   ⚠️ 它让上层的 CantoSynthesizer 逻辑【完全不用改】—— 换语言只是换一个 G2p 实现。
     */
    public static final G2p identityG2p() {
        return new G2p() {
            @Override public String toPhonemes(String t) { return t; }
        };
    }

    public interface G2p {
        /** @return 空格分隔的粤拼音素串 */
        String toPhonemes(String cantoneseText) throws Exception;
    }

    private final Backend backend;
    private final G2p g2p;
    private final P2y p2y;                  // 可为 null(不转)
    private final int[] defaultVoiceCodes;  // 可为 null(用后端内置)

    public CantoSynthesizer(Backend backend, G2p g2p, P2y p2y, int[] defaultVoiceCodes) {
        this.backend = backend;
        this.g2p = g2p;
        this.p2y = p2y;
        this.defaultVoiceCodes = defaultVoiceCodes;
    }

    public int sampleRate() { return backend.sampleRate(); }

    /** 只转文本(普→粤 + 切段),不出声 —— 便于测试与调试 */
    public List<String> prepare(String text, CantoSegmenter.Mode mode, int maxChars, boolean useP2y) {
        String t = text == null ? "" : text;
        if (useP2y && p2y != null) t = p2y.convert(t);
        return CantoSegmenter.split(t, mode, maxChars);
    }

    /**
     * 合成整段文本(逐段合成并拼接)。
     * ⚠️ 逐段拼接与本机 vsay-canto 的"边合成边播"不同 ——
     *    这里【先全合成再返回】,是为了适配 TextToSpeechService 的 callback 模型
     *    (它要求按顺序交音频字节)。要"早出声"由调用方做流式(见设计文档 P4)。
     */
    public short[] synthesize(String text,
                              CantoSegmenter.Mode mode, int maxChars,
                              boolean useP2y, int[] voiceCodes) throws Exception {
        List<String> segs = prepare(text, mode, maxChars, useP2y);
        if (segs.isEmpty()) return new short[0];
        int[] codes = voiceCodes != null ? voiceCodes : defaultVoiceCodes;

        int total = 0;
        short[][] parts = new short[segs.size()][];
        for (int i = 0; i < segs.size(); i++) {
            String ph = g2p.toPhonemes(segs.get(i));
            if (ph == null || ph.trim().isEmpty()) { parts[i] = new short[0]; continue; }
            parts[i] = backend.synthesize(ph, codes);
            total += parts[i].length;
        }
        short[] out = new short[total];
        int k = 0;
        for (short[] p : parts) { System.arraycopy(p, 0, out, k, p.length); k += p.length; }
        return out;
    }

    /** 便捷:默认参数 */
    public short[] synthesize(String text) throws Exception {
        return synthesize(text, CantoSegmenter.Mode.NONE, 240, true, null);
    }

    // ── 音频小工具(TextToSpeechService 的 callback 要 byte[],这里做转换)──

    /** int16 PCM → 小端 byte[](给 SynthesisCallback.audioAvailable 用) */
    public static byte[] toLittleEndianBytes(short[] pcm) {
        byte[] b = new byte[pcm.length * 2];
        for (int i = 0; i < pcm.length; i++) {
            b[i * 2]     = (byte) (pcm[i] & 0xFF);
            b[i * 2 + 1] = (byte) ((pcm[i] >> 8) & 0xFF);
        }
        return b;
    }

    /**
     * 把 PCM 按 maxBytes 上限分块(⚠️ TextToSpeechService 的 maxBufferSize 单位是【字节】,
     * 不是采样点 —— 这个坑我们踩过,表现是"绑定成功、合成成功、永远静音")。
     * @return 每块是 byte[](长度 ≤ maxBytes,且为偶数)
     */
    public static List<byte[]> chunkForCallback(short[] pcm, int maxBytes) {
        int max = maxBytes > 1 ? maxBytes : 4096;
        if ((max & 1) == 1) max -= 1;          // 保证偶数(半个 int16 是废字节)
        byte[] all = toLittleEndianBytes(pcm);
        java.util.ArrayList<byte[]> out = new java.util.ArrayList<>();
        for (int off = 0; off < all.length; off += max) {
            int n = Math.min(max, all.length - off);
            byte[] chunk = new byte[n];
            System.arraycopy(all, off, chunk, 0, n);
            out.add(chunk);
        }
        return out;
    }
}
