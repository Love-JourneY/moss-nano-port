// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026  Nija (bubu12) and contributors
//
// P2y —— 普通话书面文 → 粤语口语(供 TTS 朗读用)。原生 Java,零依赖。
//
// ⚠️ 本文件是 Python 版 `/usr/local/bin/p2y` 的【逐条机械复刻】,不是重新设计。
//    判据:同一批语料下,Java 输出与 Python 输出【逐字节相同】。
//    (Python 版已过 157 单元用例 + CER 实测 23.7%→4.3%;复刻一致即继承那些结论)
//
// ⚠️ 六步处理顺序(顺序敏感,别调):
//   ⓪ 总开关(VSAY_P2Y=0 / VSAY_CANTO_P2Y=0 ⇒ 原样返回)
//   ① 半角标点归全角 —— 只在【紧邻 CJK 汉字】时转
//      为什么不能无脑全局转:会把 `127.0.0.1:8790` 变成 `127.0.0.1：8790`,
//      把 `a,b` 变成 `a，b`;而 DSH 正文里夹着端口号/路径/代码片段。
//   ② 进行体「在+V」→「喺度+V」(正则)
//   ③ 数字+元/块 → 蚊(正则,不参与词表)
//   ④ 【带 \x00 哨兵】的单遍最长匹配
//      🔑 哨兵的用意:把"文本末尾"变成普通字符,这样"句末 了→喇"就能写成
//         普通表条目("了\x00" → "喇\x00")⇒ 与"长词优先"共用同一套机制。
//         ⚠️ 我第一版漏了哨兵 ⇒ 句末语气词规则【全部失效】。
//   ⑤ 收尾压掉重复助词(单遍匹配后基本不会出现,留作兜底)
//
// 用法:
//   P2y p = P2y.load(inputStream);   // assets/p2y-rules.tsv
//   String yue = p.convert("谢了,这个功能已经完成了。");

package canto;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class P2y {

    /** 句末哨兵(中英文里绝不可能出现 ⇒ 零误伤) */
    private static final char SENTINEL = '\u0000';

    /** 半角 → 全角(只这五种) */
    private static final char[][] PUNCT = {
        {',', '，'}, {'?', '？'}, {'!', '！'}, {';', '；'}, {':', '：'}
    };
    private static final Map<Character, Character> PUNCT_MAP = new HashMap<>();
    static {
        for (char[] p : PUNCT) PUNCT_MAP.put(p[0], p[1]);
    }

    /** 进行体:「在」后面紧跟这些动词之一 ⇒ 换成「喺度」 */
    private static final Pattern IN_PROGRESS =
        Pattern.compile("在(?=[等看聽听說说講讲想找做寫写食吃喝跑忙玩睡])");
    /** 数字 + 元 / 块 ⇒ 蚊 */
    private static final Pattern DIGIT_MONEY  = Pattern.compile("(\\d)\\s*元");
    private static final Pattern DIGIT_MONEY2 = Pattern.compile("(\\d)\\s*[块塊]");
    /** 收尾压重复助词 */
    private static final Pattern DUP_GE  = Pattern.compile("(嘅){2,}");
    private static final Pattern DUP_ZO  = Pattern.compile("(咗){2,}");
    private static final Pattern DUP_HAI = Pattern.compile("(係){2,}");

    private final Map<String, String> rules;   // src -> dst(已含带哨兵的条目)
    private final int maxSrcLen;

    private P2y(Map<String, String> rules, int maxSrcLen) {
        this.rules = rules;
        this.maxSrcLen = maxSrcLen;
    }

    /** 从 TSV 流加载(一行一条:`源词\t目标词`;源词里可能含 \u0000 哨兵) */
    public static P2y load(InputStream in) throws IOException {
        Map<String, String> m = new HashMap<>(4096);
        int max = 1;
        BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        try {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty()) continue;
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                String src = line.substring(0, tab);
                String dst = line.substring(tab + 1);
                if (!m.containsKey(src)) m.put(src, dst);   // 先出现者赢(PROTECT 在 LEX 前)
                if (src.length() > max) max = src.length();
            }
        } finally {
            br.close();
        }
        return new P2y(m, max);
    }

    // ────────────────────────── ① 半角标点归全角 ──────────────────────────

    /** 是否 CJK 汉字(与 Python 版 _is_cjk 同判据) */
    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)     // CJK 统一表意
            || (c >= 0x3400 && c <= 0x4DBF)     // 扩展 A
            || (c >= 0xF900 && c <= 0xFAFF)     // 兼容表意
            || (c >= 0x3040 && c <= 0x30FF);    // 日文假名(粤语文本里也可能混)
    }

    /** 只在标点【左边或右边紧邻 CJK】时才转全角 */
    static String normPunct(String t) {
        boolean any = false;
        for (int i = 0; i < t.length(); i++) {
            if (PUNCT_MAP.containsKey(t.charAt(i))) { any = true; break; }
        }
        if (!any) return t;
        char[] out = t.toCharArray();
        int n = t.length();
        for (int i = 0; i < n; i++) {
            Character to = PUNCT_MAP.get(t.charAt(i));
            if (to == null) continue;
            char prev = i > 0 ? t.charAt(i - 1) : 0;
            char next = i + 1 < n ? t.charAt(i + 1) : 0;
            if (isCjk(prev) || isCjk(next)) out[i] = to;
        }
        return new String(out);
    }

    // ────────────────────── ④ 带哨兵的单遍最长匹配 ──────────────────────

    private String onePass(String text) {
        int n = text.length();
        StringBuilder sb = new StringBuilder(n + 16);
        int i = 0;
        while (i < n) {
            int remain = n - i;
            int tryMax = remain < maxSrcLen ? remain : maxSrcLen;
            boolean matched = false;
            for (int len = tryMax; len >= 1; len--) {
                String cand = text.substring(i, i + len);
                String hit = rules.get(cand);
                if (hit != null) {
                    sb.append(hit);
                    i += len;
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                sb.append(text.charAt(i));
                i++;
            }
        }
        return sb.toString();
    }

    // ────────────────────────────── 主流程 ──────────────────────────────

    /** 六步转换。enabled=false 时原样返回(对应 VSAY_P2Y=0) */
    public String convert(String text, boolean enabled) {
        if (text == null || text.isEmpty()) return text;
        if (!enabled) return text;

        String t = text;
        t = normPunct(t);                                        // ①
        t = IN_PROGRESS.matcher(t).replaceAll("喺度");            // ②
        t = DIGIT_MONEY.matcher(t).replaceAll("$1蚊");            // ③
        t = DIGIT_MONEY2.matcher(t).replaceAll("$1蚊");
        t = onePass(t + SENTINEL);                               // ④ 带哨兵
        t = t.replace(String.valueOf(SENTINEL), "");
        t = DUP_GE.matcher(t).replaceAll("嘅");                   // ⑤ 兜底
        t = DUP_ZO.matcher(t).replaceAll("咗");
        t = DUP_HAI.matcher(t).replaceAll("係");
        return t;
    }

    public String convert(String text) { return convert(text, true); }

    public int ruleCount() { return rules.size(); }

    /** 调试用:看某一步的输出 */
    public String debugStep(String text, int step) {
        String t = text;
        if (step >= 1) t = normPunct(t);
        if (step >= 2) t = IN_PROGRESS.matcher(t).replaceAll("喺度");
        if (step >= 3) { t = DIGIT_MONEY.matcher(t).replaceAll("$1蚊");
                         t = DIGIT_MONEY2.matcher(t).replaceAll("$1蚊"); }
        if (step >= 4) { t = onePass(t + SENTINEL).replace(String.valueOf(SENTINEL), ""); }
        if (step >= 5) { t = DUP_GE.matcher(t).replaceAll("嘅");
                         t = DUP_ZO.matcher(t).replaceAll("咗");
                         t = DUP_HAI.matcher(t).replaceAll("係"); }
        return t;
    }
}
