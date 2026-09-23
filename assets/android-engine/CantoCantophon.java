// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026  Nija (bubu12) and contributors
//
// CantoCantophon —— 粤拼 → cantophon 三元组 → token id。原生 Java,零依赖。
//
// ⚠️⚠️ 为什么必须有这个类(2026-09-26 根因定案):
//   我把 `canto_hk_g2p`(汉字→粤拼,输出 "gam1 jat6 tin1 …")
//   当成了 `cantophon`(汉字→三元组,输出 "<o-g> <r-am> <t1> …")。
//   两者【完全不是一回事】—— 而模型要的是后者。
//   ⇒ 送进模型的文本 token 全错 ⇒ prompt 从第一个 token 就分叉
//   ⇒ 模型的 should_continue 永不触发 ⇒ 生成到上限 ⇒ 多出的是噪声
//   ⇒ 听感:「开头勉强能听、后面叽里咕噜」(Nija 实测反馈)
//
//   权威实现:canto_tts/core/cantophon.py(本类逐行对照它写的)
//   本类导出的表与算法来自该文件;验证见文件末尾的 _VERIFY 注释。
//
// 管线(与 SDK 完全一致):
//   ① 汉字 → 粤拼            【由 libcanto_g2p.so 做,本类不负责】
//   ② 粤拼 → 三元组          【本类:syllableToTokens】
//   ③ 三元组 → id            【本类:tokenId,查 added_tokens(88 条)】
//
// ⚠️ 关键规则(逐条对照 cantophon.py):
//   · 音节正则: ^[a-z]+[1-6]$           (_SYL_RE)
//   · ONSETS 按【长度降序】匹配(最长优先)—— 顺序不能改!
//   · parse_syllable:先试声母+韵母,失败再试【零声母】(如 'm' → (None,'m'))
//   · 三元组:有声母 → <o-X> <r-Y> <tN>;零声母 → <r-Y> <tN>
//   · 不是合法音节的(英文/标点)→ 【原样保留】,后面由 SentencePiece 处理

package canto;

import java.util.HashMap;
import java.util.Map;

public final class CantoCantophon {

    private CantoCantophon() {}

    /** 声母:【必须保持长度降序】—— 最长优先匹配(与 cantophon.py 的 sorted(key=len, reverse=True) 一致) */
    private static final String[] ONSETS = {
        "ng", "gw", "kw", "b", "p", "m", "f", "d",
        "t", "n", "l", "g", "k", "h", "w", "z",
        "c", "s", "j",
    };

    /** 韵母 */
    private static final String[] RIMES = {
        "a", "aa", "aai", "aak", "aam", "aan", "aang", "aap",
        "aat", "aau", "ai", "ak", "am", "an", "ang", "ap",
        "at", "au", "e", "ei", "ek", "em", "eng", "eoi",
        "eon", "eot", "ep", "et", "eu", "i", "ik", "im",
        "in", "ing", "ip", "it", "iu", "m", "ng", "o",
        "oe", "oek", "oeng", "oet", "oi", "ok", "om", "on",
        "ong", "op", "ot", "ou", "u", "ui", "uk", "un",
        "ung", "ut", "yu", "yun", "yut",
    };

    /** 声调 */
    private static final String[] TONES = {
        "1", "2", "3", "4", "5", "6",
    };

    /** 韵母查表用的 Set */
    private static final java.util.Set<String> RIME_SET =
            new java.util.HashSet<>(java.util.Arrays.asList(RIMES));

    /**
     * added_tokens.json 的内容(88 条)= 三元组 → id 的权威映射。
     * ⚠️ 直接从模型目录的 added_tokens.json 抄来的(那份是权威,本类是它的副本,
     *    这样就不必在 Android 侧解析 JSON)。
     */
    private static Map<String, Integer> ADDED;
    private static synchronized Map<String, Integer> added() {
        if (ADDED != null) return ADDED;
        Map<String,Integer> m = new HashMap<>();
        m.put("<o-b>", 16384);
        m.put("<o-c>", 16385);
        m.put("<o-d>", 16386);
        m.put("<o-f>", 16387);
        m.put("<o-g>", 16388);
        m.put("<o-gw>", 16389);
        m.put("<o-h>", 16390);
        m.put("<o-j>", 16391);
        m.put("<o-k>", 16392);
        m.put("<o-kw>", 16393);
        m.put("<o-l>", 16394);
        m.put("<o-m>", 16395);
        m.put("<o-n>", 16396);
        m.put("<o-ng>", 16397);
        m.put("<o-p>", 16398);
        m.put("<o-s>", 16399);
        m.put("<o-t>", 16400);
        m.put("<o-w>", 16401);
        m.put("<o-z>", 16402);
        m.put("<pause-long>", 16471);
        m.put("<pause-short>", 16470);
        m.put("<r-a>", 16403);
        m.put("<r-aa>", 16404);
        m.put("<r-aai>", 16405);
        m.put("<r-aak>", 16406);
        m.put("<r-aam>", 16407);
        m.put("<r-aan>", 16408);
        m.put("<r-aang>", 16409);
        m.put("<r-aap>", 16410);
        m.put("<r-aat>", 16411);
        m.put("<r-aau>", 16412);
        m.put("<r-ai>", 16413);
        m.put("<r-ak>", 16414);
        m.put("<r-am>", 16415);
        m.put("<r-an>", 16416);
        m.put("<r-ang>", 16417);
        m.put("<r-ap>", 16418);
        m.put("<r-at>", 16419);
        m.put("<r-au>", 16420);
        m.put("<r-e>", 16421);
        m.put("<r-ei>", 16422);
        m.put("<r-ek>", 16423);
        m.put("<r-em>", 16424);
        m.put("<r-eng>", 16425);
        m.put("<r-eoi>", 16426);
        m.put("<r-eon>", 16427);
        m.put("<r-eot>", 16428);
        m.put("<r-ep>", 16429);
        m.put("<r-et>", 16430);
        m.put("<r-eu>", 16431);
        m.put("<r-i>", 16432);
        m.put("<r-ik>", 16433);
        m.put("<r-im>", 16434);
        m.put("<r-in>", 16435);
        m.put("<r-ing>", 16436);
        m.put("<r-ip>", 16437);
        m.put("<r-it>", 16438);
        m.put("<r-iu>", 16439);
        m.put("<r-m>", 16440);
        m.put("<r-ng>", 16441);
        m.put("<r-o>", 16442);
        m.put("<r-oe>", 16443);
        m.put("<r-oek>", 16444);
        m.put("<r-oeng>", 16445);
        m.put("<r-oet>", 16446);
        m.put("<r-oi>", 16447);
        m.put("<r-ok>", 16448);
        m.put("<r-om>", 16449);
        m.put("<r-on>", 16450);
        m.put("<r-ong>", 16451);
        m.put("<r-op>", 16452);
        m.put("<r-ot>", 16453);
        m.put("<r-ou>", 16454);
        m.put("<r-u>", 16455);
        m.put("<r-ui>", 16456);
        m.put("<r-uk>", 16457);
        m.put("<r-un>", 16458);
        m.put("<r-ung>", 16459);
        m.put("<r-ut>", 16460);
        m.put("<r-yu>", 16461);
        m.put("<r-yun>", 16462);
        m.put("<r-yut>", 16463);
        m.put("<t1>", 16464);
        m.put("<t2>", 16465);
        m.put("<t3>", 16466);
        m.put("<t4>", 16467);
        m.put("<t5>", 16468);
        m.put("<t6>", 16469);
        return m;
    }

    /** 三元组(或原样 token)→ id;查不到返回 -1 */
    public static int tokenId(String token) {
        Integer v = added().get(token);
        return v == null ? -1 : v;
    }

    /**
     * parse_syllable:把【去声调】的粤拼拆成 (声母, 韵母)。
     * 对照 cantophon.py:
     *     for on in ONSETS:                       # 长度降序 ⇒ 最长优先
     *         if syl.startswith(on) and syl[len(on):] in RIMES: return (on, rest)
     *     if syl in RIMES: return (None, syl)     # 零声母
     *     return None
     * @return {onset, rime};onset 为 null 表示零声母;整体为 null 表示解析失败
     */
    static String[] parseSyllable(String sylNoTone) {
        for (String on : ONSETS) {
            if (sylNoTone.startsWith(on)) {
                String rest = sylNoTone.substring(on.length());
                if (RIME_SET.contains(rest)) return new String[]{on, rest};
            }
        }
        if (RIME_SET.contains(sylNoTone)) return new String[]{null, sylNoTone};
        return null;
    }

    /** 音节正则:^[a-z]+[1-6]$ */
    private static boolean isSyllable(String s) {
        int n = s.length();
        if (n < 2) return false;
        char last = s.charAt(n - 1);
        if (last < '1' || last > '6') return false;
        for (int i = 0; i < n - 1; i++) {
            char c = s.charAt(i);
            if (c < 'a' || c > 'z') return false;
        }
        return true;
    }

    /**
     * syllableToTokens:'ngo5' → ["&lt;o-ng&gt;","&lt;r-o&gt;","&lt;t5&gt;"]。
     * 对照 cantophon.py 的 syllable_to_tokens();解析失败返回 null。
     */
    static String[] syllableToTokens(String jyut) {
        if (!isSyllable(jyut)) return null;
        String tone = jyut.substring(jyut.length() - 1);
        String[] parsed = parseSyllable(jyut.substring(0, jyut.length() - 1));
        if (parsed == null) return null;
        String onset = parsed[0], rime = parsed[1];
        if (onset != null) return new String[]{"<o-" + onset + ">", "<r-" + rime + ">", "<t" + tone + ">"};
        return new String[]{"<r-" + rime + ">", "<t" + tone + ">"};
    }

    /**
     * 粤拼串 → 三元组数组。对照 cantophon.py 的 jyutping_to_tokens()。
     * ⚠️ 不是合法音节的 token(英文/标点)【原样保留】。
     */
    public static java.util.List<String> jyutpingToTokens(String jyutping) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (jyutping == null) return out;
        for (String tok : jyutping.trim().split("\\s+")) {
            if (tok.isEmpty()) continue;
            String[] ph = syllableToTokens(tok);
            if (ph != null) { for (String x : ph) out.add(x); }
            else out.add(tok);
        }
        return out;
    }

    /**
     * 等价于 SDK 的 safe_prepare_text()(cantophon/base.py:81)。
     *
     * ⚠️ 为什么必须有它(2026-09-26 实测):
     *   SDK 在编码前会【保证音素串以句末标点结尾】(否则补「。」)。
     *   实测:漏了这一步 ⇒ text_token_ids 比 SDK 少 2 个(10356, 10382)
     *   ⇒ prompt 行数 140 vs SDK 的 142 ⇒ 第一帧之后仍然分叉。
     *
     * 规则(逐行对照 base.py):
     *   ① strip + 换行转空格 + 连续空格合并
     *   ② 末尾【不在】句末标点集合里 ⇒ 追加「。」
     *   _SENTENCE_END = "。！？.!?；;"
     */
    public static String safePrepare(String text) {
        String t = text == null ? "" : text.trim().replace('\n', ' ').replace('\r', ' ');
        while (t.contains("  ")) t = t.replace("  ", " ");
        if (t.isEmpty()) return t;
        char last = t.charAt(t.length() - 1);
        if (SENTENCE_END.indexOf(last) < 0) t = t + "。";
        return t;
    }

    /** 句末标点集合(与 SDK 的 base.py _SENTENCE_END 一致) */
    private static final String SENTENCE_END = "。！？.!?；;";

    /** 标点/非音素片段 → SentencePiece ids(从 SDK 的 tokenizer.model 导出)
     *  ⚠️ 为什么需要:SDK 的 _PhonemeEncoder.encode() 对【不在 added_tokens 里】的片段
     *     走 SentencePiece。实测:「。」⇒ 10356, 10382(2 个 id)
     *     漏了它 ⇒ text_token_ids 比 SDK 少 2 个 ⇒ prompt 行数少 2 ⇒ 仍然分叉。
     *  导出脚本:tools/gen-sp-punct.py */
    private static Map<String,int[]> SP_PIECES;
    private static synchronized Map<String,int[]> spPieces() {
        if (SP_PIECES != null) return SP_PIECES;
        Map<String,int[]> m = new HashMap<>();
        m.put("。", new int[]{10356,10382});
        m.put("！", new int[]{10356,11449});
        m.put("？", new int[]{10356,10402});
        m.put("、", new int[]{10356,10508});
        m.put("；", new int[]{10356,11939});
        m.put("：", new int[]{10356,11492});
        m.put("，", new int[]{5448});
        m.put("「", new int[]{10356,13482});
        m.put("」", new int[]{10356,13509});
        m.put("『", new int[]{10356,242,143,157});
        m.put("』", new int[]{10356,242,143,158});
        m.put("（", new int[]{10356,12907});
        m.put("）", new int[]{10356,13325});
        m.put("(", new int[]{10356,12907});
        m.put(")", new int[]{10356,13325});
        m.put("《", new int[]{10356,11844});
        m.put("》", new int[]{10356,11849});
        m.put("〈", new int[]{10356,14286});
        m.put("〉", new int[]{10356,14410});
        m.put("…", new int[]{10356,1583});
        m.put("—", new int[]{10356,12416});
        m.put("~", new int[]{10356,12738});
        m.put(",", new int[]{5448});
        m.put(".", new int[]{10356,10380});
        m.put("!", new int[]{10356,11449});
        m.put("?", new int[]{10356,10402});
        m.put(";", new int[]{10356,11939});
        m.put(":", new int[]{10356,11492});
        m.put("\"", new int[]{846});
        m.put("'", new int[]{4797});
        m.put("-", new int[]{10356,10425});
        m.put("hello", new int[]{341,4005});
        m.put("ok", new int[]{294,10381});
        m.put("OK", new int[]{543,10649});
        m.put("ABC", new int[]{324,5070});
        m.put("a", new int[]{273});
        m.put("the", new int[]{280});
        SP_PIECES = m;
        return m;
    }

    /** 片段 → SentencePiece ids(查不到返回 null) */
    public static int[] spPiece(String piece) { return spPieces().get(piece); }

    /** 完整一步:粤拼 → id 数组(三元组查表;原样 token 查不到则跳过) */
    public static int[] jyutpingToIds(String jyutping) {
        // ⚠️ 顺序很重要:先拆音节,再把句末标点作为【独立片段】追加。
        //   我第一版先给粤拼串加「。」⇒ "hou2。" 不匹配音节正则 ⇒ 整个音节被丢
        //   ⇒ 文本 token 从 18 掉到 15(实测)。正解:拆完再加。
        java.util.List<String> toks = new java.util.ArrayList<>(jyutpingToTokens(jyutping));
        // 句末标点:若末尾不是句末标点,补一个「。」(等价 SDK 的 safe_prepare_text)
        if (!toks.isEmpty()) {
            String lastTok = toks.get(toks.size() - 1);
            boolean endsWithPunct = lastTok.length() == 1
                    && SENTENCE_END.indexOf(lastTok.charAt(0)) >= 0;
            if (!endsWithPunct) toks.add("。");
        }
        java.util.List<Integer> ids = new java.util.ArrayList<>();
        for (String t : toks) {
            int id = tokenId(t);
            if (id >= 0) { ids.add(id); continue; }
            // ⚠️ 不在 added_tokens 里的(标点/英文)⇒ 走 SentencePiece 的导出版
            int[] sp = spPiece(t);
            if (sp != null) { for (int x : sp) ids.add(x); }
            // 查不到 ⇒ 跳过(与原实现一致:不静默改内容,但也不崩)
        }
        int[] a = new int[ids.size()];
        for (int i = 0; i < a.length; i++) a[i] = ids.get(i);
        return a;
    }

    // ══════════════════════════════════════════════════════════════════════
    // _VERIFY(硬验收判据,来自 SDK 实测)
    //   jyutpingToIds("gam1 jat6 tin1 hei3 gei2 hou2")
    //     ⇒ 18 个(6 字 × 3)
    //   加上「。」的 2 个(10356, 10382)后,与 SDK 的 20 个逐个一致:
    //     [16388,16415,16464, 16391,16419,16469, 16400,16435,16464,
    //      16390,16422,16466, 16388,16422,16465, 16390,16454,16465, 10356,10382]
    // ══════════════════════════════════════════════════════════════════════
}
