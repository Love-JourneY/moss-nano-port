// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026  Nija (bubu12) and contributors
//
// CantoModelSetup —— 模型目录解析 + 首启自拷。原生 Java,零依赖。
//
// ⚠️⚠️ 为什么需要它(2026-09-26 一键 install 实测发现):
//   install.sh 只能把模型推到 /sdcard(零权限);
//   而【内部目录必须由 App 自己拷】—— 因为:
//     · 快:内部建 4 个 session 3403ms,/sdcard(FUSE)要 10985ms ⇒ 【快 3.2 倍】
//     · 关键是 SELinux:adb/su 推的文件【缺 App 的 MLS 类别】
//       ⇒ ORT 报 "system error number 13"(EACCES),而且【父目录也要带类别】
//       ⇒ 而 App 自己拷的文件【自带正确的 uid + SELinux 上下文】
//
//   原来自拷逻辑只在 CantoTtsService.resolveModelDir() 里,
//   而那是【合成时才调用】的 ⇒ 启动 Activity 不触发
//   ⇒ 结果:install.sh 说成功,但内部模型是空的,守护起不来、verify 全 0。
//   ⇒ 抽成静态工具 ⇒ 【Service 与 Activity 都能触发】。
//
// 用法:
//   File dir = CantoModelSetup.ensure(ctx);   // 解析 + 必要时自拷 + 建别名,返回可用目录

package canto;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

public final class CantoModelSetup {

    private static final String TAG = "CantoModel";

    private CantoModelSetup() {}

    /** 内部模型目录(App 私有,首选) */
    public static File internalDir(Context c) {
        return new File(c.getFilesDir(), "models/canto");
    }

    /** 外部暂存目录(install.sh 推到这儿;可能为 null) */
    public static File externalDir(Context c) {
        File ext = c.getExternalFilesDir(null);
        return ext != null ? new File(ext, "models/canto") : null;
    }

    /**
     * 模型"真的在里面"吗 —— 判据不是"目录在",而是【能打开关键 onnx】。
     *
     * ⚠️⚠️ 2026-09-26 修(一键 install 实测发现的关键 bug):
     *   要认【两种布局】!
     *     · 别名布局(内部,App 拷完并改名后):  tts/… · codec/…
     *     · 原始布局(install.sh 刚推到 /sdcard): MOSS-TTS-Nano-cantophon-ONNX/… ·
     *                                            MOSS-Audio-Tokenizer-Nano-ONNX/…
     *   原来只认第一种 ⇒ 外部目录(原始布局)被判为"不完整"
     *   ⇒ 【首启自拷永远不触发】⇒ 内部一直空 ⇒ 守护起不来、verify 全 0。
     */
    public static boolean looksComplete(File dir) {
        if (dir == null) return false;
        // ① 别名布局
        if (new File(dir, "tts/moss_tts_prefill.onnx").isFile()
                && new File(dir, "codec/moss_audio_tokenizer_decode_full.onnx").isFile()) {
            return true;
        }
        // ② 原始布局(install.sh 推过来的样子)
        return new File(dir, "MOSS-TTS-Nano-cantophon-ONNX/moss_tts_prefill.onnx").isFile()
                && new File(dir, "MOSS-Audio-Tokenizer-Nano-ONNX/moss_audio_tokenizer_decode_full.onnx").isFile();
    }

    /**
     * 解析模型目录;内部没有而外部有 ⇒ 【首启自拷】。返回最终可用的目录。
     * ⚠️ 幂等:可以反复调用(内部已就绪时直接返回)。
     */
    public static File ensure(Context c) {
        File internal = internalDir(c);
        File external = externalDir(c);
        boolean okIn = looksComplete(internal);
        boolean okEx = looksComplete(external);
        Log.i(TAG, "模型目录解析: 内部可用=" + okIn + " 外部可用=" + okEx);

        // ⚠️⚠️ 2026-09-26 修(整包 install 实测第二轮):
        //   原来是【一次性】自拷 —— 内部已就绪就 return,不检查别的文件。
        //   ⇒ install.sh 后来补推的 voicebank/ 与 tuning.properties 【永远搬不进去】
        //     ⇒ 守护 READY voices=0 ⇒ 选不了音色(实测)
        //   ⇒ 改成【增量补齐】:即使内部已就绪,也把外部【有而内部没有】的顶层项补进去。
        if (!okIn && okEx) {
            Log.i(TAG, "内部无模型、外部有 ⇒ 【首启全量自拷】(自带正确的 uid 与 SELinux 上下文)");
            if (!copyTree(external, internal)) {
                Log.w(TAG, "自拷失败 ⇒ 退回直接用外部路径(可能 EACCES)");
            }
        } else if (okIn && okEx) {
            // ★ 增量:补齐外部有、内部没有的顶层项(模型目录本身、voicebank/、tuning.properties…)
            // ⚠️ 2026-09-28 加 base-mandarin:多语言支持的第二个模型目录
            //    (基础版:普通话/英文;与粤语版共用同一套图契约)
            // ★ 2026-09-28 改:不再写死 base-mandarin ——
            //   改成【扫暂存区里所有"像个模型"的目录】一起搬(支持任意模型导入)
            java.util.List<String> names = new java.util.ArrayList<>();
            for (String x : new String[]{"voicebank", "tuning.properties", "p2y-rules.tsv",
                    "syllable-ids.tsv", "added_tokens.json", "browser_poc_manifest.json",
                    "tokenizer.model", "data", "model.json"}) names.add(x);
            File extRoot = external.getParentFile();      // <ext>/files/models
            File[] subs = extRoot == null ? null : extRoot.listFiles();
            if (subs != null) for (File sub : subs) {
                if (!sub.isDirectory()) continue;
                if (sub.getName().equals(external.getName())) continue;   // 主模型(canto)另走全量
                if (new File(sub, "browser_poc_manifest.json").isFile()) names.add(sub.getName());
            }
            String[] extra = names.toArray(new String[0]);
            for (String name : extra) {
                // ⚠️⚠️ 2026-09-28 修:base-mandarin 是 models/【下】的【兄弟目录】,
                //   不是 <models/canto>/ 的子项 ⇒ 必须按父目录解析。
                //   (踩过:写成 new File(external, name) ⇒ 找 .../models/canto/base-mandarin
                //    ⇒ 永远找不到 ⇒ 自拷贝静默不触发)
                boolean sibling = !new File(external, name).isDirectory()
                        && new File(external.getParentFile(), name).isDirectory();
                File src = sibling ? new File(external.getParentFile(), name)
                                   : new File(external, name);
                File dst = sibling ? new File(internal.getParentFile(), name)
                                   : new File(internal, name);
                // ⚠️⚠️ 2026-09-28 修:目录"存在"不等于"有内容"!
                //   上次失败的拷贝留下了【空目录】⇒ dst.exists() 为真 ⇒ 被跳过 ⇒ 永远补不上。
                //   ⇒ 目录要【非空】才算已就位。
                boolean dstReady = dst.exists() && (!dst.isDirectory() || nonEmpty(dst));
                if (src.exists() && !dstReady) {
                    Log.i(TAG, "增量补齐: " + name);
                    if (src.isDirectory()) copyTree(src, dst);
                    else copyFile(src, dst);
                }
            }
        }
        okIn = looksComplete(internal);
        if (okIn) ensureAliases(internal);
        File pick = okIn ? internal : (okEx ? external : internal);
        Log.i(TAG, "选 = " + pick);
        return pick;
    }

    /**
     * ★ 按【语言】给模型目录(2026-09-28 加)。
     *
     * 背景:同一个 MOSS 图契约既能跑粤语(微调版)也能跑普通话/英文(基础版),
     *      差别只在【文本→id 那一层】与【音色来源】。
     *   · canto    → models/canto            (粤语微调;汉字→粤拼→三元组→added_tokens)
     *   · mandarin → models/base-mandarin     (基础版;汉字/单词→SentencePiece)
     *
     * ⚠️ 基础版【不自带 audio codec】⇒ codecDir 仍用粤语版里的那份
     *    (同一套 MOSS-Audio-Tokenizer-Nano,音质与解码完全一样)。
     */
    public static File langDir(android.content.Context c, String lang) {
        // ⚠️ internalDir() 已经是 <files>/models/canto ⇒ root 要取它的【父目录】
        //   (踩过:写成 new File(internalDir(c),"models") ⇒ .../models/canto/models ⇒ 拼重了,
        //    表现为"语言切到 mandarin 但模型目录还是 canto")
        File root = internalDir(c).getParentFile();
        File d = "mandarin".equalsIgnoreCase(lang)
                ? new File(root, "base-mandarin")
                : new File(root, "canto");
        return d.isDirectory() ? d : internalDir(c);
    }

    /** 语言可用吗(基础版不存在时不能切过去) */
    public static boolean langAvailable(android.content.Context c, String lang) {
        return "mandarin".equalsIgnoreCase(lang) && langDir(c, "mandarin").isDirectory();
    }

    /** codec 目录:两个语言共用一份(基础版不带 codec) */
    public static File codecDir(android.content.Context c) {
        File canto = internalDir(c);                     // = <files>/models/canto
        File d = new File(canto, "codec");
        if (d.isDirectory()) return d;
        return new File(canto, "MOSS-Audio-Tokenizer-Nano-ONNX");
    }

    /** 目录里有东西吗(空的目录视为"没就位") */
    private static boolean nonEmpty(File d) {
        File[] fs = d.listFiles();
        return fs != null && fs.length > 0;
    }

    /** 供 CantoModel 导入模型用的公开包装(copyTree/copyFile 本身是 package-private 的旧写法) */
    public static boolean copyTreePublic(File src, File dst) { return copyTree(src, dst); }
    public static boolean copyFilePublic(File src, File dst) { return copyFile(src, dst); }

    /** 单文件拷贝(增量补齐用);App 自建 ⇒ uid/上下文正确 */
    public static boolean copyFile(File src, File dst) {
        try {
            FileInputStream in = new FileInputStream(src);
            FileOutputStream out = new FileOutputStream(dst);
            try {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                try { in.close(); } catch (Exception ignored) {}
                try { out.close(); } catch (Exception ignored) {}
            }
            return true;
        } catch (Throwable t) { Log.w(TAG, "copyFile 失败: " + src, t); return false; }
    }

    /**
     * 递归拷贝。⚠️ 用 Java IO 而不是 shell —— 这样文件【由 App 进程创建】,
     * 自动获得正确的 uid 与 SELinux 上下文(这是整个自拷方案的意义)。
     */
    public static boolean copyTree(File src, File dst) {
        try {
            if (src == null || !src.isDirectory()) return false;
            if (!dst.exists() && !dst.mkdirs()) return false;
            File[] kids = src.listFiles();
            if (kids == null) return false;
            byte[] buf = new byte[1 << 16];
            for (File k : kids) {
                File t = new File(dst, k.getName());
                if (k.isDirectory()) {
                    if (!copyTree(k, t)) return false;
                } else {
                    FileInputStream in = new FileInputStream(k);
                    FileOutputStream out = new FileOutputStream(t);
                    try {
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    } finally {
                        try { in.close(); } catch (Exception ignored) {}
                        try { out.close(); } catch (Exception ignored) {}
                    }
                }
            }
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "copyTree 失败: " + t, t);
            return false;
        }
    }

    /** 让 <dir>/tts 与 <dir>/codec 指向真实目录(优先软链,失败则改名) */
    public static void ensureAliases(File dir) {
        alias(dir, "tts", "MOSS-TTS-Nano-cantophon-ONNX");
        alias(dir, "codec", "MOSS-Audio-Tokenizer-Nano-ONNX");
    }

    private static void alias(File dir, String link, String target) {
        File l = new File(dir, link);
        if (l.exists()) return;
        File t = new File(dir, target);
        if (!t.exists()) return;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                java.nio.file.Files.createSymbolicLink(l.toPath(), t.toPath());
                Log.i(TAG, "软链 " + link + " -> " + target);
                return;
            }
        } catch (Throwable e) {
            Log.w(TAG, "建软链失败(" + link + "),改用改名: " + e);
        }
        // 兜底:直接改名(只做一次;模型目录因此变成 tts/ codec/)
        if (t.renameTo(l)) Log.i(TAG, "改名 " + target + " -> " + link);
        else Log.w(TAG, "别名建立失败: " + link);
    }
}
