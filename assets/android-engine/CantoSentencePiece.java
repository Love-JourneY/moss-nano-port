// SPDX-License-Identifier: AGPL-3.0-or-later
// moss-nano-port 引擎 —— 纯 Java 的 SentencePiece 文本编码器(MOSS-TTS-Nano 基础版用)
//
// ⚠️⚠️ 为什么需要它(2026-09-28):
//   【粤语】路径:汉字 →[canto_hk_g2p]→ 粤拼 →[CantoCantophon]→ 三元组 →[added_tokens]→ id
//   【普通话】路径:汉字 →[SentencePiece]→ id      ← Java 侧【没有】SentencePiece
//   ⇒ 本类用【导出的词表 + 贪心最长匹配】近似它。
//
// 🔴 精度声明(必须诚实):
//   实测与官方 SentencePiece 对拍:
//     '今天天气很好。'          ✅ 一致
//     'Hello, this is a test.'  ✅ 一致
//     '欢迎关注模思智能…'       ✅ 一致
//     '今日天氣幾好。'          ✅ 一致
//     '你好，世界。'            ⚠️ 全角逗号处不同( normalize 规则未完全复刻 )
//     'OpenMOSS Team is ...'    ⚠️ 长英文词的分词不同(贪心 vs unigram 概率)
//   ⇒ 命中约 4/6。对 TTS 而言分词偏差 ⇒ 韵律略不同,但句子仍可听懂。
//   ⇒ 正解是把 SentencePiece 编进 NDK 库(见 语音体系文档 §10),这里是【过渡实现】。
//
// 词表来源:assets/android-tok/sp-vocab-16384.tsv(由 tokenizer.model 导出,16384 条)
package canto;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

public final class CantoSentencePiece {

    /** ▁ —— SentencePiece 的空格标记(U+2581) */
    private static final char SPACE_MARK = '\u2581';
    /** 最长 piece 长度(汉字一般 1~4;英文词可到 16) */
    private static final int MAX_PIECE = 24;

    private final Map<String, Integer> vocab = new HashMap<>();
    public final int unkId;
    public final int vocabSize;

    /** 全角 → 半角 的 normalize 映射(经验性测出;不保证覆盖全部) */
    private static final String[][] NORM = {
        { "，", "," }, { "／", "/" }, { "＄", "$" },
        { "０", "0" }, { "１", "1" }, { "２", "2" }, { "３", "3" }, { "４", "4" },
        { "５", "5" }, { "６", "6" }, { "７", "7" }, { "８", "8" }, { "９", "9" },
    };

    private CantoSentencePiece(Map<String, Integer> v, int unk, int size) {
        vocab.putAll(v); this.unkId = unk; this.vocabSize = size;
    }

    /** 从模型目录加载(词表文件与模型放一起,便于模型升级) */
    public static CantoSentencePiece fromModelDir(String modelDir) throws Exception {
        File[] cands = {
            new File(modelDir, "sp-vocab-16384.tsv"),
            new File(modelDir, "sp-vocab.tsv"),
            new File(new File(modelDir).getParentFile(), "sp-vocab-16384.tsv"),
        };
        for (File f : cands) if (f.isFile()) return load(f);
        throw new java.io.FileNotFoundException("找不到 sp-vocab-16384.tsv(在 " + modelDir + ")");
    }

    public static CantoSentencePiece load(File tsv) throws Exception {
        Map<String, Integer> v = new HashMap<>(20000);
        int unk = 0, max = 0;
        BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(tsv), "UTF-8"));
        String line;
        while ((line = r.readLine()) != null) {
            int t = line.indexOf('\t');
            if (t <= 0) continue;
            int id = Integer.parseInt(line.substring(0, t));
            String piece = line.substring(t + 1);
            if (piece.startsWith("<") && piece.endsWith(">") && piece.length() < 12
                    && !piece.startsWith("<0x")) {
                // 控制符(<unk>/<s>/</s>/<pad>/<|im_start|>…):不进匹配表,但记 unk
                if (piece.equals("<unk>")) unk = id;
                continue;
            }
            v.put(piece, id);
            if (piece.length() > max) max = piece.length();
        }
        r.close();
        return new CantoSentencePiece(v, unk, v.size());
    }

    /** 文本 → token ids(近似 SentencePiece) */
    public int[] encode(String text) {
        String norm = normalize(text);
        java.util.List<Integer> out = new java.util.ArrayList<>();
        int i = 0, n = norm.length();
        while (i < n) {
            int best = -1, blen = 0;
            int lim = Math.min(MAX_PIECE, n - i);
            for (int L = lim; L >= 1; L--) {
                Integer id = vocab.get(norm.substring(i, i + L));
                if (id != null) { best = id; blen = L; break; }
            }
            if (best < 0) {
                out.add(unkId);          // ⚠️ 未知字符 ⇒ unk(官方用字节回退,这里简化)
                i += 1;
            } else {
                out.add(best); i += blen;
            }
        }
        int[] a = new int[out.size()];
        for (int k = 0; k < a.length; k++) a[k] = out.get(k);
        return a;
    }

    /** normalize:全角→半角 + 空格→▁ + 句首加 ▁(与 SentencePiece 一致的做法) */
    public static String normalize(String text) {
        String t = text == null ? "" : text;
        for (String[] m : NORM) if (t.indexOf(m[0]) >= 0) t = t.replace(m[0], m[1]);
        t = t.replace(' ', SPACE_MARK);
        return SPACE_MARK + t;
    }
}
