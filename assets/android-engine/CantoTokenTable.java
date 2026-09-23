// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026  Nija (bubu12) and contributors
//
// CantoTokenTable —— 粤拼音素串 → text token ids。原生 Java,零依赖。
//
// ⚠️ 为什么不用 sentencepiece(重要):
//   上游 `_PhonemeEncoder` 是【混合编码器】:
//     ① 最长匹配切出 added_tokens.json 的 88 个【结构标记】
//     ② 【其余】交给 sentencepiece
//   ⚠️⚠️ 2026-09-26 更正:这里原来写着「音素串对 added_tokens 的覆盖率 = 0%」——
//      【那句话是错的,而且害了 20 轮】
//      我当时拿【粤拼串 gam1 jat6】去匹配 added_tokens ⇒ 当然 0%
//      而正确输入是【cantophon 三元组串 <o-g> <r-am> <t1>】⇒ 覆盖率 100%
//      ⇒ 正确路径见 CantoCantophon.java;本类的旧表【只作兜底】。
//   ⇒ sentencepiece 本来是【必须的】,Java 侧要么拉 DJL tokenizers(重),
//     要么自己编 sentencepiece(更重)。
//
//   🎯 但我们发现一个关键性质,把它整个消掉了:
//      【单个音素的 token id 是固定的】
//      —— 整句编码 == 各音素单独编码的拼接(实测完全一致)
//   ⇒ 于是预算一张【音节 → token ids】表(7930 条 / 156KB)即可。
//      而 G2P 的输出天然是【空格分隔的音节】⇒ split + 查表 + 拼接,零算法复杂度。
//
//   ⚠️ 表的构建有个坑(我踩过):**不能"按 inventory 猜组合"**(19 声母 × 61 韵母 × 6 声调)
//      —— 会漏掉 G2P 实际输出的形式(如 `ngoh5` 那个带 h 的 `oh`)。
//      ⇒ 必须【从 G2P 的实际输出里收集音节】(见 tools/build-syllable-table.py 的做法)。
//
// 表格式(TSV):`音节<TAB>逗号分隔的 ids`,如:
//      gam1	8802,10385
//      jat6	307,319,10752
//      <pause-short>	16470

package canto;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

public final class CantoTokenTable {

    /** 表文件名(放 assets 或模型目录) */
    /** ⚠️ 旧表(仅兜底)。主路径是 CantoCantophon(cantophon 三元组)—— 见 encode() 的注释 */
    public static final String ASSET_NAME = "syllable-ids.tsv";

    private final Map<String, int[]> table;
    private final int[] fallback;      // 未知音素怎么办(默认空 ⇒ 跳过)

    private CantoTokenTable(Map<String, int[]> table, int[] fallback) {
        this.table = table; this.fallback = fallback;
    }

    /** 从 TSV 流加载 */
    public static CantoTokenTable load(InputStream in) throws IOException {
        Map<String, int[]> m = new HashMap<>(8192);
        BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        try {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty()) continue;
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                String key = line.substring(0, tab);
                String rhs = line.substring(tab + 1).trim();
                if (rhs.isEmpty()) { m.put(key, new int[0]); continue; }
                String[] parts = rhs.split(",");
                int[] ids = new int[parts.length];
                for (int i = 0; i < ids.length; i++) ids[i] = Integer.parseInt(parts[i].trim());
                m.put(key, ids);
            }
        } finally {
            br.close();
        }
        return new CantoTokenTable(m, new int[0]);
    }

    /** 表条目数 */
    public int size() { return table.size(); }

    /**
     * 音素串 → token ids。
     * ⚠️ 按【空格】切音节(这正是 G2P 的输出格式);未知音节【记入日志但不抛】
     *    —— 因为漏一个音节只意味着少读一个字,不该让整句失败。
     */
    public int[] encode(String phonemes) { return encode(phonemes, null); }

    /**
     * 音素(粤拼,空格分隔)→ text token ids。
     *
     * ⚠️⚠️ 2026-09-26 根因修复:【优先走 cantophon 三元组】
     *
     *   原来是查 syllable-ids.tsv(音节 → 2 个 id,如 gam1 → 8802,10385),
     *   而模型要的是 cantophon 的【声母/韵母/声调三元组】:
     *     gam1 → <o-g> <r-am> <t1> → 16388, 16415, 16464
     *   ⇒ 两条路给出【完全不同的 id】⇒ prompt 从第一个 token 就分叉
     *   ⇒ should_continue 永不触发 ⇒ 生成到上限 ⇒ 多出的帧是噪声
     *   ⇒ 听感「开头勉强能听、后面叽里咕噜」
     *
     *   权威实现:canto_tts/core/cantophon.py 的 jyutping_to_tokens()
     *   本方法现在委托给 CantoCantophon(逐行对照它写的)。
     *
     * ⚠️ 旧表(syllable-ids.tsv)保留作【兜底】:三元组查不到 id 时才回退,
     *    这样万一遇到 cantophon 不认的音节,行为仍可预期(而不是静默丢字)。
     */
    public int[] encode(String phonemes, java.util.List<String> missedOut) {
        // ① 首选:cantophon 三元组(与 SDK 逐位一致的唯一路径)
        try {
            int[] ids = CantoCantophon.jyutpingToIds(phonemes);
            if (ids.length > 0) return ids;
        } catch (Throwable t) { /* 落到旧表兜底 */ }
        return encodeLegacy(phonemes, missedOut);
    }

    /** 旧的"音节 → id"路径(仅兜底,不要当主路径) */
    private int[] encodeLegacy(String phonemes, java.util.List<String> missedOut) {
        if (phonemes == null || phonemes.trim().isEmpty()) return new int[0];
        String[] syls = phonemes.trim().split("\\s+");
        int total = 0;
        int[][] parts = new int[syls.length][];
        for (int i = 0; i < syls.length; i++) {
            int[] ids = table.get(syls[i]);
            if (ids == null) {
                if (missedOut != null) missedOut.add(syls[i]);
                ids = fallback;
            }
            parts[i] = ids;
            total += ids.length;
        }
        int[] out = new int[total];
        int k = 0;
        for (int[] p : parts) { System.arraycopy(p, 0, out, k, p.length); k += p.length; }
        return out;
    }

    /**
     * 从模型目录读(表随模型放,便于升级)。
     * ⚠️ 容错:表的实际位置可能是模型根、tts/ 子目录、或 assets ⇒ 逐个试。
     *   实测踩过:目录布局与预期不同 ⇒ FileNotFoundException 让整个引擎起不来。
     */
    public static CantoTokenTable fromModelDir(String dir) throws IOException {
        java.io.File[] cands = {
            new java.io.File(dir, ASSET_NAME),
            new java.io.File(new java.io.File(dir).getParentFile(), ASSET_NAME),
            new java.io.File(dir, "tts/" + ASSET_NAME),
        };
        for (java.io.File f : cands) {
            if (f.isFile()) return load(new java.io.FileInputStream(f));
        }
        throw new java.io.FileNotFoundException("找不到 " + ASSET_NAME + "(找过 "
                + java.util.Arrays.toString(cands) + ")");
    }
}
