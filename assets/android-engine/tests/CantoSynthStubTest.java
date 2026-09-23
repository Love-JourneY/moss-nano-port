// 用【桩后端】验证 CantoSynthesizer 的链路逻辑(不需要 ONNX / G2P .so)
package smoke;
import canto.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class SmokeSynth {
    static int pass = 0, fail = 0;
    static void check(String what, boolean ok, String detail) {
        System.out.println((ok ? "  [OK]   " : "  [FAIL] ") + what + (detail.isEmpty() ? "" : "  → " + detail));
        if (ok) pass++; else fail++;
    }

    /** 桩后端:不做真推理,只把音素数变成可预期的 PCM */
    static class StubBackend implements CantoSynthesizer.Backend {
        final List<String> seen = new ArrayList<>();
        public short[] synthesize(String phonemes, int[] voiceCodes) {
            seen.add(phonemes + "|voice=" + (voiceCodes == null ? "null" : voiceCodes.length));
            // 每个音素产 10 个采样(便于算长度)
            int n = phonemes.trim().isEmpty() ? 0 : phonemes.trim().split("\\s+").length * 10;
            short[] p = new short[n];
            for (int i = 0; i < n; i++) p[i] = (short) (i % 1000);
            return p;
        }
        public int sampleRate() { return 48000; }
    }

    /** 桩 G2P:输入粤语文本,产出可预期的音素串 */
    static class StubG2p implements CantoSynthesizer.G2p {
        public String toPhonemes(String t) {
            int n = t.replaceAll("[\\p{P}\\s]", "").length();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < n; i++) sb.append(i == 0 ? "" : " ").append("syl").append(i).append("1");
            return sb.toString();
        }
    }

    public static void main(String[] args) throws Exception {
        String base = args[0];
        P2y p2y = P2y.load(new FileInputStream(base + "/p2y-rules.tsv"));
        StubBackend be = new StubBackend();
        CantoSynthesizer syn = new CantoSynthesizer(be, new StubG2p(), p2y, new int[]{1,2,3});

        // ① 准备(普→粤 + 不切)
        String src = "我在等你,谢了。";
        List<String> segs = syn.prepare(src, CantoSegmenter.Mode.NONE, 240, true);
        System.out.println("[1] prepare ⇒ " + segs);
        check("NONE 不切 ⇒ 1 段", segs.size() == 1, "" + segs.size());
        check("p2y 已生效(喺度)", segs.get(0).contains("喺度"), segs.get(0));
        check("p2y 已生效(多謝)", segs.get(0).contains("多謝"), segs.get(0));

        // ② 切段模式
        List<String> segs2 = syn.prepare("今天天气好。我们出去走走。", CantoSegmenter.Mode.SENTENCE, 50, false);
        check("SENTENCE ⇒ 2 段", segs2.size() == 2, "" + segs2.size());

        // ③ 合成(桩)→ 长度可预期
        short[] pcm = syn.synthesize(src, CantoSegmenter.Mode.NONE, 240, true, null);
        System.out.println("[3] pcm 长度 = " + pcm.length + " 采样 · 后端看到 " + be.seen.size() + " 次调用");
        check("合成有输出", pcm.length > 0, "" + pcm.length);
        check("采样率 = 48000", syn.sampleRate() == 48000, "" + syn.sampleRate());
        check("默认音色传给了后端", be.seen.get(0).endsWith("voice=3"), be.seen.get(0));

        // ④ 音色可换(目标③:支持音色选择)
        be.seen.clear();
        syn.synthesize(src, CantoSegmenter.Mode.NONE, 240, false, new int[]{9,9,9,9,9});
        check("音色可换(长度 5)", be.seen.get(0).endsWith("voice=5"), be.seen.get(0));

        // ⑤ 🔑 maxBufferSize 单位是【字节】—— 这是踩过的坑
        short[] p = new short[1000];               // 1000 采样 = 2000 字节
        List<byte[]> chunks = CantoSynthesizer.chunkForCallback(p, 8192);
        int tot = 0; for (byte[] c : chunks) tot += c.length;
        System.out.println("[5] 2000 字节 / maxBufferSize=8192 ⇒ " + chunks.size() + " 块,共 " + tot + " 字节");
        check("字节数守恒(2 字节/采样)", tot == 2000, "" + tot);
        check("单块 ≤ maxBufferSize", chunks.get(0).length <= 8192, "" + chunks.get(0).length);

        // 小块上限(模拟真实 maxBufferSize=8192 太小、我们给它 100 字节)
        List<byte[]> c2 = CantoSynthesizer.chunkForCallback(p, 100);
        int tot2 = 0; boolean allEven = true;
        for (byte[] c : c2) { tot2 += c.length; if ((c.length & 1) != 0) allEven = false; }
        check("小块也守恒", tot2 == 2000, "" + tot2);
        check("每块长度是偶数", allEven, "");
        check("块数正确(2000/100=20)", c2.size() == 20, "" + c2.size());

        // ⑥ 小端序正确性(第一个采样 = 0x1234 ⇒ 字节 0x34 0x12)
        short[] x = new short[]{0x1234};
        byte[] b = CantoSynthesizer.toLittleEndianBytes(x);
        check("小端序", (b[0] & 0xFF) == 0x34 && (b[1] & 0xFF) == 0x12,
              String.format("%02X %02X", b[0] & 0xFF, b[1] & 0xFF));

        System.out.println("\n===== " + pass + " 通过 / " + fail + " 失败 =====");
        if (fail > 0) System.exit(1);
    }
}
