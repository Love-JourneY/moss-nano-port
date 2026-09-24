// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026  Nija (bubu12) and contributors
//
// CantoResampler —— 输出降采样(48kHz → 可配置)。原生 Java,零依赖。
//
// ⚠️ 为什么要它(Nija 2026-09-26 指示):
//   「如果我们所有设备的TTS,全部把生成合成的音质稍微降一降。
//     比如说七六八零采样。这个感觉是个很高的音质,我们可以砍一半,ok?
//     因为…很多音乐平台上那种无损音质和普通MP3音质,我都听不太出来区别。」
//   ⇒ 对【语音】而言,48kHz 确实远超人耳需要:
//       · 人声基频 80~300Hz,共振峰到 4~8kHz
//       · 电话质量 8kHz 采样(4kHz 带宽)就高度可懂
//       · 宽带语音 16kHz 采样(8kHz 带宽)已很清晰
//       · 24kHz 采样(12kHz 带宽)比人声需要的【多得多】
//     ⇒ 砍一半(24kHz)对粤语语音**听感几乎无差**
//
// ⚠️⚠️ 但它【不能】提速/省内存 —— 这点必须说清楚:
//   moss-nano-port 模型【内部就是 48kHz 生成的】⇒ 推理该算的还是算。
//   降采样只省:① socket 传输量 ② 播放缓冲 ③ 音频文件大小 ④ 系统 TTS 回调数据量
//
// ⚠️ 降采样【不能】"每两个取一个"(直接抽取)——
//   24kHz 的 Nyquist 是 12kHz,而原信号含到 24kHz 的成分
//   ⇒ 不滤波会【混叠失真】(高频折回到低频,听起来发闷/沙)
//   ⇒ 正确做法:【先低通滤波,再抽取】(本类用窗函数 FIR)

package canto;

public final class CantoResampler {

    /** 源采样率(moss-nano-port 模型固定 48kHz) */
    public static final int SRC_RATE = 48000;

    private CantoResampler() {}

    /**
     * 把 48kHz 交错立体声 int16 PCM 降采样到 dstRate。
     *
     * @param pcm     交错立体声 int16(48kHz)
     * @param channels 声道数(2)
     * @param dstRate  目标采样率(如 24000)
     * @return 降采样后的交错立体声 int16;dstRate==48000 时原样返回
     */
    public static short[] resample(short[] pcm, int channels, int dstRate) {
        if (dstRate <= 0 || dstRate == SRC_RATE) return pcm;
        int factor = SRC_RATE / dstRate;         // 支持整数倍(2 ⇒ 24k, 3 ⇒ 16k, 6 ⇒ 8k)
        if (factor < 2 || SRC_RATE % dstRate != 0) {
            // 不支持的比例:退到线性插值(仍先做简单平滑,避免最严重的混叠)
            return resampleLinear(pcm, channels, dstRate);
        }
        // ① 设计低通 FIR:截止频率 = 0.45 × 目标 Nyquist(留过渡带)
        //    归一到源采样率 ⇒ cutoff = 0.45 × (dstRate/2) / (SRC_RATE/2)
        double cutoff = 0.45 / factor;           // 归一化到 Nyquist
        int half = 15;                            // 31 抽头
        float[] h = new float[half * 2 + 1];
        double sum = 0;
        for (int n = -half; n <= half; n++) {
            double x = n;
            // 理想低通 sinc × 汉明窗
            double sinc = (n == 0) ? 2 * cutoff : Math.sin(2 * Math.PI * cutoff * x) / (Math.PI * x);
            double win = 0.54 - 0.46 * Math.cos(2 * Math.PI * (n + half) / (2.0 * half));
            h[n + half] = (float) (sinc * win);
            sum += h[n + half];
        }
        for (int i = 0; i < h.length; i++) h[i] /= (float) sum;   // 归一化(保直流增益 1)

        // ② 逐声道:滤波 + 抽取
        int frames = pcm.length / channels;
        int outFrames = frames / factor;
        short[] out = new short[outFrames * channels];

        for (int c = 0; c < channels; c++) {
            // 先把该声道解出来(便于滤波)
            float[] ch = new float[frames];
            for (int i = 0; i < frames; i++) ch[i] = pcm[i * channels + c];

            for (int o = 0; o < outFrames; o++) {
                int center = o * factor;
                double acc = 0;
                for (int k = -half; k <= half; k++) {
                    int idx = center + k;
                    if (idx < 0) idx = 0;                       // 边界:复用首样本
                    else if (idx >= frames) idx = frames - 1;   // 边界:复用末样本
                    acc += ch[idx] * h[k + half];
                }
                int v = (int) Math.round(acc);
                if (v > 32767) v = 32767; else if (v < -32768) v = -32768;
                out[o * channels + c] = (short) v;
            }
        }
        return out;
    }

    /** 非整数倍比例的兜底:线性插值(比直接抽取好,但不如 FIR) */
    private static short[] resampleLinear(short[] pcm, int channels, int dstRate) {
        int frames = pcm.length / channels;
        int outFrames = (int) ((long) frames * dstRate / SRC_RATE);
        short[] out = new short[outFrames * channels];
        for (int o = 0; o < outFrames; o++) {
            double src = (double) o * SRC_RATE / dstRate;
            int i0 = (int) src, i1 = Math.min(i0 + 1, frames - 1);
            double f = src - i0;
            for (int c = 0; c < channels; c++) {
                double v = pcm[i0 * channels + c] * (1 - f) + pcm[i1 * channels + c] * f;
                out[o * channels + c] = (short) Math.round(v);
            }
        }
        return out;
    }
    /**
     * ★ 剪掉【过长的内部静音】(2026-09-28 加)。
     *
     * ⚠️ 为什么要它(实测,Nija 报「今日天氣幾好」听起来是"今日 天氣 幾好")：
     *   模型自己会在某些粤拼序列后插入很长的静音 —— 实测同一句:
     *     今日天氣幾好。  ⇒ 内部静音 [70, 370, 220]ms
     *     你好。          ⇒ 内部静音 [570, 70]ms
     *   而【笔记本用同一个模型也有同样的停顿】⇒ 这是【模型韵律】,不是本实现的 bug。
     *
     * ⇒ 后处理:把内部静音压到不超过 maxMs(默认 180ms)。
     *   · 不动模型、不动权重
     *   · 更连贯(不再"两字一顿")
     *   · 总时长变短(用户也会觉得"快了")
     *   ⚠️ 首尾静音【不动】(那是模型的自然收尾)
     *
     * @param pcm     立体声交错 PCM(16bit)
     * @param channels 声道数(2)
     * @param rate     采样率
     * @param maxMs    内部静音上限(毫秒);<=0 表示不处理
     */
    public static short[] trimLongPauses(short[] pcm, int channels, int rate, int maxMs) {
        if (pcm == null || pcm.length < channels * 4 || maxMs <= 0) return pcm;
        int frames = pcm.length / channels;
        // ① 10ms 窗的包络(取各声道绝对值的均值)
        int win = Math.max(1, rate / 100);
        int nWin = frames / win;
        if (nWin < 3) return pcm;
        float[] env = new float[nWin];
        float peak = 0f;
        for (int w = 0; w < nWin; w++) {
            long sum = 0;
            for (int i = w * win; i < (w + 1) * win; i++) {
                for (int c = 0; c < channels; c++) {
                    int v = pcm[i * channels + c];
                    sum += (v < 0 ? -v : v);
                }
            }
            env[w] = (float) sum / (win * channels);
            if (env[w] > peak) peak = env[w];
        }
        if (peak <= 1f) return pcm;                      // 全静音,不动
        float thr = peak * 0.03f;
        int maxWin = Math.max(1, maxMs / 10);            // 允许的最大静音窗数

        // ② 找出要删掉的窗(内部静音中超出上限的部分)
        java.util.List<int[]> cuts = new java.util.ArrayList<>();
        int w = 0;
        while (w < nWin) {
            if (env[w] < thr) {
                int s = w;
                while (w < nWin && env[w] < thr) w++;
                int len = w - s;
                boolean head = (s == 0);
                boolean tail = (w >= nWin);
                if (!head && !tail && len > maxWin) {
                    // 保留前半 + 后半各 maxWin/2,砍掉中间
                    int keep = Math.max(1, maxWin);
                    int cutFrom = s + keep / 2;
                    int cutTo = w - (keep - keep / 2);
                    if (cutTo > cutFrom) cuts.add(new int[]{cutFrom, cutTo});
                }
            } else w++;
        }
        if (cuts.isEmpty()) return pcm;

        // ③ 拼接(跳过被砍的窗)
        int totalCut = 0;
        for (int[] c : cuts) totalCut += (c[1] - c[0]);
        short[] out = new short[pcm.length - totalCut * win * channels];
        int oi = 0, ci = 0, wi = 0;
        while (wi < nWin) {
            if (ci < cuts.size() && wi == cuts.get(ci)[0]) { wi = cuts.get(ci)[1]; ci++; continue; }
            int from = wi * win * channels, to = Math.min((wi + 1) * win * channels, pcm.length);
            int n = to - from;
            System.arraycopy(pcm, from, out, oi, n);
            oi += n; wi++;
        }
        return out;
    }

}
