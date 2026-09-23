// SPDX-License-Identifier: AGPL-3.0-or-later
// canto-tts 引擎 —— 【模型注册表】(2026-09-28)
//
// Nija 要求:「还有选模型的,import 模型的能力要有哦,自动依据模型支持的音色选等」
//
// 设计:不再把语言/音色【写死】在代码里,而是【扫目录 + 读模型自己的声明】:
//
//   models/<任意名字>/
//     ├─ browser_poc_manifest.json    ← 有它才算一个可用模型(硬判据)
//     ├─ voicebank/*.json             ← 有 ⇒ 音色来源 = voicebank(粤语微调式)
//     ├─ manifest.builtin_voices      ← 有 ⇒ 音色来源 = 内置(基础版式,可多个)
//     ├─ added_tokens.json            ← 有 ⇒ 编码器 = 粤语三元组;无 ⇒ SentencePiece
//     ├─ tuning.properties            ← 可选:本模型自己的旋钮
//     └─ model.json                   ← 可选:我们自己的元数据(显示名/语言/默认音色)
//
// ⇒ 界面上的「音色列表」= 当前模型能提供的全部音色(自动发现,不是按语言分支)
// ⇒ 「导入模型」= 把暂存区(或用户选的目录)里像个模型的目录搬进 models/ 并注册
package canto;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class CantoModel {

    /** 文本 → token 的编码方式(由模型目录里有没有 added_tokens.json 决定) */
    public enum Encoding {
        /** 汉字 → 粤拼 → 三元组 → added_tokens(粤语微调版) */
        CANTONESE_TRIPLES,
        /** 文本 → SentencePiece(基础版/多语言版) */
        SENTENCEPIECE
    }

    /** 音色的来源 */
    public enum VoiceSource { VOICEBANK, BUILTIN, NONE }

    /** 一个音色的引用(name 唯一;codes 可能为 null = 只展示不可选) */
    public static final class VoiceRef {
        public final String name, group, display;
        public final int[] codes;          // 压平的 [frames][16];null ⇒ 无 codes
        public final int frames;
        VoiceRef(String name, String group, String display, int[] codes, int frames) {
            this.name = name; this.group = group; this.display = display;
            this.codes = codes; this.frames = frames;
        }
        @Override public String toString() { return name; }
    }

    public final File dir;
    public final String name;              // 目录名
    public final String displayName;       // model.json 的 display_name,否则目录名
    public final List<String> languages;   // model.json 的 languages;空 = 未声明
    public final Encoding encoding;
    public final VoiceSource voiceSource;
    public final List<VoiceRef> voices = new ArrayList<VoiceRef>();
    public final String problem;           // 非 null ⇒ 这个目录不可用(界面要显示原因)
    /** model.json 声明的默认音色名(空 = 用 voices 的第一个) */
    public final String defaultVoice;

    private CantoModel(File dir, String problem) {
        this(dir, problem, dir.getName(), Collections.<String>emptyList(),
                Encoding.SENTENCEPIECE, null, "");
    }

    /** 能否真正拿来合成 */
    public boolean usable() { return problem == null; }

    @Override public String toString() {
        return displayName + (usable() ? "" : "(" + problem + ")");
    }

    // ─────────────────────────── 扫描 ───────────────────────────

    /** models/ 的根 */
    public static File modelsRoot(Context c) {
        return CantoModelSetup.internalDir(c).getParentFile();
    }

    /**
     * 扫 models/*,把每个"像个模型"的目录变成 CantoModel。
     * ⚠️ 判据是【能读到 manifest】而不是目录名 —— 这样用户随便起名也能用。
     */
    public static List<CantoModel> scan(Context c) {
        List<CantoModel> out = new ArrayList<CantoModel>();
        File root = modelsRoot(c);
        File[] ds = root == null ? null : root.listFiles();
        if (ds == null) return out;
        for (File d : ds) {
            if (!d.isDirectory()) continue;
            out.add(load(d));
        }
        Collections.sort(out, new java.util.Comparator<CantoModel>() {
            @Override public int compare(CantoModel a, CantoModel b) {
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        return out;
    }

    /** 读一个模型目录(永不抛异常;失败时 problem 里带原因) */
    public static CantoModel load(File dir) {
        File mf = new File(dir, "browser_poc_manifest.json");
        if (!mf.isFile()) return new CantoModel(dir, "缺少 browser_poc_manifest.json");
        JSONObject man;
        try {
            man = new JSONObject(readUtf8(mf));
        } catch (Throwable t) {
            return new CantoModel(dir, "manifest 读不了: " + trim(t));
        }

        // 我们自己的元数据(可选)
        String disp = dir.getName();
        String defVoice = "";
        List<String> langs = new ArrayList<String>();
        File mj = new File(dir, "model.json");
        if (mj.isFile()) {
            try {
                JSONObject m = new JSONObject(readUtf8(mj));
                disp = m.optString("display_name", disp);
                defVoice = m.optString("default_voice", "");
                JSONArray la = m.optJSONArray("languages");
                if (la != null) for (int i = 0; i < la.length(); i++) langs.add(la.optString(i, ""));
            } catch (Throwable ignored) {}
        }

        Encoding enc = new File(dir, "added_tokens.json").isFile()
                ? Encoding.CANTONESE_TRIPLES : Encoding.SENTENCEPIECE;

        CantoModel m = new CantoModel(dir, null, disp, langs, enc, man, defVoice);
        if (m.voices.isEmpty()) return new CantoModel(dir, "没有可用音色(default_voice/builtin_voices/voicebank 都空)");
        return m;
    }

    private CantoModel(File dir, String problem, String disp, List<String> langs,
                       Encoding enc, JSONObject man, String defVoice) {
        this.defaultVoice = defVoice == null ? "" : defVoice;
        this.dir = dir; this.name = dir.getName(); this.displayName = disp;
        this.languages = langs; this.encoding = enc;
        this.problem = problem;

        // ① 音色来源 1:voicebank/
        File vb = new File(dir, "voicebank");
        File[] js = vb.listFiles();
        if (js != null) {
            for (File f : js) {
                if (!f.getName().endsWith(".json")) continue;
                try {
                    CantoVoiceBank.Voice v = CantoVoiceBank.parse(
                            new FileInputStream(f), f.getName().replace(".json", ""));
                    voices.add(new VoiceRef(v.name, "voicebank", v.desc, v.flatten(), v.nFrames()));
                } catch (Throwable ignored) {}
            }
        }
        // ② 音色来源 2:manifest 的 builtin_voices(可多把)
        try {
            JSONArray arr = man.optJSONArray("builtin_voices");
            if (arr != null) for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                JSONArray rows = o.optJSONArray("prompt_audio_codes");
                int frames = rows == null ? 0 : rows.length();
                int[] codes = frames > 0 ? new int[frames * 16] : null;
                if (codes != null) {
                    int k = 0;
                    for (int f = 0; f < frames; f++) {
                        JSONArray row = rows.optJSONArray(f);
                        for (int c = 0; c < 16; c++) codes[k++] = row == null ? 0 : row.optInt(c, 0);
                    }
                }
                voices.add(new VoiceRef(o.optString("voice", "?"), o.optString("group", ""),
                        o.optString("display_name", ""), codes, frames));
            }
            // ③ 音色来源 3:manifest 的 default_voice(单把,粤语式的备用)
            JSONObject dv = man.optJSONObject("default_voice");
            if (dv != null && voices.isEmpty()) {
                JSONArray rows = dv.optJSONArray("prompt_audio_codes");
                int frames = rows == null ? 0 : rows.length();
                int[] codes = frames > 0 ? new int[frames * 16] : null;
                if (codes != null) {
                    int k = 0;
                    for (int f = 0; f < frames; f++) {
                        JSONArray row = rows.optJSONArray(f);
                        for (int c = 0; c < 16; c++) codes[k++] = row == null ? 0 : row.optInt(c, 0);
                    }
                }
                voices.add(new VoiceRef(dv.optString("voice", "default"),
                        "default_voice", "", codes, frames));
            }
        } catch (Throwable ignored) {}

        this.voiceSource = !new File(dir, "voicebank").isDirectory() || voices.isEmpty()
                ? (voices.isEmpty() ? VoiceSource.NONE : VoiceSource.BUILTIN)
                : VoiceSource.VOICEBANK;
    }

    // ─────────────────────────── 选中 / 导入 ───────────────────────────

    public static final String PREFS = "canto_tts";
    public static final String KEY_MODEL = "model_dir";

    /** 当前选中的模型(没选过/选的不在了 ⇒ 取第一个可用的) */
    public static CantoModel current(Context c) {
        List<CantoModel> all = scan(c);
        String want = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_MODEL, null);
        if (want != null) for (CantoModel m : all) if (m.name.equals(want)) return m;
        for (CantoModel m : all) if (m.usable()) return m;
        return all.isEmpty() ? null : all.get(0);
    }

    /** ★ 音色偏好【按模型分键】—— 否则切模型后旧名字失效 ⇒ 回落第一把(实测踩过) */
    public static String voicePrefKey(String modelName) { return "voice::" + modelName; }

    public static String preferredVoiceName(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(voicePrefKey(name(c)), "");
    }

    public static void setPreferredVoice(Context c, String modelName, String voice) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(voicePrefKey(modelName), voice).apply();
    }

    /** 当前模型名(prefs) */
    private static String name(Context c) {
        CantoModel m = current(c);
        return m == null ? "" : m.name;
    }

    /**
     * ★ 这个模型的"默认该用哪把音色"(2026-09-28 加)。
     *   优先级:① model.json 的 default_voice ② 用户上次给【本模型】选的
     *           ③ 第一把【有 codes 且不是纯宣传音】的。
     *   ⚠️ 为什么需要 ①:基础版第一把是 Junhao「CN 欢迎关注模思智能」——
     *      那是【宣传音】,不该当默认(Nija 实测嫌难听)。
     */
    public CantoModel.VoiceRef pickVoice(Context c) {
        if (voices.isEmpty()) return null;
        String want = preferredVoiceName(c);
        if (want != null && !want.isEmpty()) {
            for (CantoModel.VoiceRef v : voices) if (want.equals(v.name) && v.codes != null) return v;
        }
        if (defaultVoice != null && !defaultVoice.isEmpty()) {
            for (CantoModel.VoiceRef v : voices) if (defaultVoice.equals(v.name) && v.codes != null) return v;
        }
        for (CantoModel.VoiceRef v : voices) if (v.codes != null) return v;
        return voices.get(0);
    }

    public static void select(Context c, String name) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_MODEL, name).apply();
    }

    /**
     * ★ 导入:把 src 目录搬进 models/<name>。
     *
     * ⚠️ 只做"搬运 + 校验",不联网、不解压(整包可迁移 / 离线自洽原则)。
     *    来源可以是:暂存区(App 私有外部目录)里的目录,或用户用文件管理器放进去的任何目录。
     *
     * @return null 成功;否则是失败原因(界面必须显示,不能静默失败)
     */
    public static String importFrom(Context c, File src, String asName) {
        if (src == null || !src.isDirectory()) return "来源不是一个目录";
        if (!new File(src, "browser_poc_manifest.json").isFile())
            return "目录里没有 browser_poc_manifest.json —— 这不像一个 MOSS-TTS 模型";
        String nm = (asName == null || asName.trim().isEmpty()) ? src.getName() : asName.trim();
        File dst = new File(modelsRoot(c), nm);
        if (dst.exists()) return "已存在同名模型:" + nm + "(先删掉或换个名字)";
        if (!CantoModelSetup.copyTreePublic(src, dst)) return "拷贝失败(看日志)";
        // 补一份 tuning(没有的话),让本模型也能被旋钮控制
        try {
            File tk = new File(dst, "tuning.properties");
            if (!tk.isFile()) {
                File from = new File(CantoModelSetup.internalDir(c), "tuning.properties");
                if (from.isFile()) CantoModelSetup.copyFilePublic(from, tk);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** 暂存区里【还没导入】的候选模型(App 私有外部目录) */
    public static List<File> importCandidates(Context c) {
        List<File> out = new ArrayList<File>();
        File ext = CantoModelSetup.externalDir(c);          // <ext>/files/models/canto
        File extRoot = ext == null ? null : ext.getParentFile();
        File[] ds = extRoot == null ? null : extRoot.listFiles();
        if (ds != null) for (File d : ds) {
            if ("canto".equals(d.getName())) continue;      // 粤语是引擎自带的主模型
            if (new File(d, "browser_poc_manifest.json").isFile()) out.add(d);
        }
        return out;
    }

    // ─────────────────────────── 工具 ───────────────────────────

    private static String readUtf8(File f) throws Exception {
        byte[] b = new byte[(int) f.length()];
        FileInputStream in = new FileInputStream(f);
        try { int n = in.read(b); return new String(b, 0, Math.max(0, n), "UTF-8"); }
        finally { in.close(); }
    }

    private static String trim(Throwable t) {
        String m = t.getMessage(); return m == null ? t.getClass().getSimpleName() : m;
    }
}
