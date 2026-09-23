// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026  Nija (bubu12) and contributors
//
// CantoSegmenter —— 切段策略(复刻本机 vsay-canto 的语义)。原生 Java,零依赖。
//
// 为什么有这么多策略(历史):
//   ① 最初【按所有标点切】(含逗号)⇒ Nija:「一个字一个字吐」「像在读课文」
//   ② 改成【句号优先】⇒ 好一些
//   ③ Nija 实战反馈:「在 DSH 的助手消息中,切分的效果巨差」「可能还是完全不要切分合并好」
//   ⇒ 【默认 NONE(不切)】
//
// 关键实现坑(我在 bash 版踩过):
//   "把 CHUNK 调大"【不等于】"不切" —— 句号优先策略【总是】按句末标点切,
//   CHUNK 只管"单句超长时是否下沉到逗号"。
//   ⇒ 要真不切,必须让"句末切分"这一步也不做(见 NONE)。
//
// 已知代价:NONE 在超长文本上可能音色漂移(后半段偶发掉男声)
//   ⇒ 保留 2000 字硬上限保护。
//   ⚠️ 另注:DSH 插件侧对 >240 字会自动退回句级切分(早出声),那是【调用方的策略】,
//      不是本类的事 —— 本类只忠实执行拿到的 mode。

package canto;

import java.util.ArrayList;
import java.util.List;

public final class CantoSegmenter {

    public enum Mode {
        NONE,      // 真·不切(默认)
        SENTENCE,  // 句号优先
        CLAUSE     // 旧行为:所有标点后切
    }

    private static final String SENT_END = "。！？!?";
    private static final String SUB = "；;：:、，,";
    private static final String ALL = SENT_END + SUB;

    /** 极端长度保护 */
    public static final int HARD_CAP = 2000;

    /**
     * ⚠️ Android 专用分段粒度(2026-09-25 实测定案)。
     *
     * 为什么需要它(用户实战反馈:"只听见他说一个字"):
     *   · 架构约束:App 侧等太久(>~3 秒)会被 ColorOS 的应用冻结机制冻住,
     *     读不到守护回传的 PCM ⇒ 所以【单段合成必须短】
     *   · 但单段太短(如 10 帧 = 0.8 秒)又只能念 3 个字 ⇒ 长句被截断
     *   ✅ **2026-09-26 第三轮修正:已可【整句合成】!**
     *      实测:加了心跳(守护每帧发一个字节)之后,
     *        App 侧连续等 【24 秒】wchan 仍是 do_epoll_wait(【没被冻!】),
     *        而且成功收到 56 帧(4.48 秒)的整段音频。
     *      ⇒ 所以不再需要"切碎换时间":粒度和 maxFrames 都可以放大,
     *        让韵律自然(切碎会让每段语调独立,听起来"一个词一个词念")
     *      ⇒ 取 60 字(实际等于"不切",标点优先)
     *
     *   ⚠️ 历史(留档,说明为什么一度切成 3 字):
     *       实测帧数 ≈ 20 + 6 × 字数,且【20 帧是最小输出】:
     *         「好」1字 → 20 帧 · 「今天天」3字 → 20 帧 · 「今天天气很好」6字 → 56 帧
     *       ⇒ 【3 字以内都是 20 帧(≈1.6 秒音频 ≈ 4 秒合成)】
     *       ⇒ 取【每段 ≤ 3 个汉字】:合成 ≈4 秒,在冻结窗口(实测 ≈6 秒)内有 2 秒余量
     *    ⚠️ 为什么不是 6 字(第一版):6 字要 56 帧 = 9 秒合成
     *       ⇒ ① 超过冻结窗口 ⇒ App 读不到结果
     *          ② 被 maxFrames=25 截断 ⇒ 【用户实际听到"他只会今天"】(实测反馈)
     *
     * ⚠️ 切法:优先在标点处切;没有标点就【按字数硬切】(会切断词组,但保证不漏字)
     */
    public static final int ANDROID_GRAIN = 60;

    private CantoSegmenter() {}

    public static List<String> split(String text, Mode mode, int maxChars) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        String t = text.trim();
        if (t.isEmpty()) return out;
        List<String> raw;
        switch (mode == null ? Mode.NONE : mode) {
            case NONE:
                if (t.length() <= HARD_CAP) { out.add(t); return out; }
                return hardSplit(t, HARD_CAP);
            case SENTENCE:
                raw = twoLevel(t, maxChars, SENT_END);
                break;
            case CLAUSE:
            default:
                raw = twoLevel(t, maxChars, ALL);
                break;
        }
        for (String s : raw) if (hasContent(s)) out.add(s);
        return out;
    }

    public static List<String> split(String text) { return split(text, Mode.NONE, 50); }

    /**
     * Android 专用切段:按 {@link #ANDROID_GRAIN} 个字一段。
     * ⚠️ 优先在标点后切;超长无标点则硬切(宁可切断词组,也不漏字)。
     */
    public static List<String> splitForAndroid(String text) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (text == null) return out;
        String t = text.trim();
        if (t.isEmpty()) return out;
        // 先按标点切成"自然句"
        java.util.List<String> sents = splitAfter(t, SENT_END);
        for (String sent : sents) {
            if (sent.length() <= ANDROID_GRAIN) { if (hasContent(sent)) out.add(sent); continue; }
            // 再按次级标点
            java.util.List<String> parts = splitAfter(sent, SUB);
            for (String part : parts) {
                if (part.length() <= ANDROID_GRAIN) { if (hasContent(part)) out.add(part); continue; }
                // 最后硬切(按字数)
                for (String piece : hardSplit(part, ANDROID_GRAIN)) {
                    if (hasContent(piece)) out.add(piece);
                }
            }
        }
        return out;
    }

    /** 在指定标点【之后】切(标点归前一段) */
    private static List<String> splitAfter(String t, String punct) {
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            sb.append(c);
            if (punct.indexOf(c) >= 0 && sb.toString().trim().length() > 0) {
                out.add(sb.toString().trim());
                sb.setLength(0);
            }
        }
        if (sb.toString().trim().length() > 0) out.add(sb.toString().trim());
        return out;
    }

    /** 两级:先按 first 切;超预算下沉到次级标点;还超才硬切 */
    private static List<String> twoLevel(String t, int maxChars, String first) {
        List<String> out = new ArrayList<>();
        for (String sent : splitAfter(t, first)) {
            if (sent.length() <= maxChars) { out.add(sent); continue; }
            for (String part : splitAfter(sent, SUB)) {
                if (part.length() <= maxChars) out.add(part);
                else out.addAll(hardSplit(part, maxChars));
            }
        }
        return out;
    }

    /** 硬切(会切断词,只作最后手段) */
    private static List<String> hardSplit(String t, int n) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < t.length(); i += n) {
            String s = t.substring(i, Math.min(t.length(), i + n)).trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    /** 只剩标点/空白的段丢掉 */
    private static boolean hasContent(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isWhitespace(c) && ALL.indexOf(c) < 0) return true;
        }
        return false;
    }
}
