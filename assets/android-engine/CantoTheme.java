// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026  Nija (bubu12) and contributors
//
// CantoTheme —— 极简配色方案(亮 / 暗),原生 Java,零依赖。
//
// ⚠️ 为什么不用 androidx 的 DayNight / AppCompatDelegate:
//   本 App 用 `javac -bootclasspath android.jar` 直接编译,【没有 androidx】
//   ⇒ 自己实现:检测系统 uiMode + 手动配色 + 用户可覆盖。
//
// 三种模式(存 SharedPreferences,键 `canto_tts.theme`):
//   "auto"(默认)  跟随系统(Android 10+ 的深色主题开关)
//   "dark"        强制暗
//   "light"       强制亮
//
// Nija 2026-09-26 要求:「app 的 gui 要有暗黑模式」

package canto;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

public final class CantoTheme {

    public static final String PREFS = "canto_tts";
    public static final String KEY = "theme";

    // ── 亮色 ──
    // ─────────────────────────────────────────────────────────────
    // ★ 设计语言表(2026-09-28,按 design-aesthetic skill 立)
    //   原则:3+1 配色 · 点缀色【只】用于交互/强调 · 不用纯黑纯白 · 暖色系
    //   ⚠️ 改配色只改这 6 个值 × 2 套,不要在各处硬编码颜色
    // ─────────────────────────────────────────────────────────────
    private static final int L_BG      = 0xFFFAF8F5;   // 页面底(暖白,非纯白)
    private static final int L_CARD    = 0xFFFFFFFF;   // 卡片/按钮底
    private static final int L_TEXT    = 0xFF1A1714;   // 主文字(暖黑,非纯黑)
    private static final int L_SUB     = 0xFF6B6259;   // 次要文字(暖灰)
    private static final int L_ACCENT  = 0xFF8B7140;   // 强调:古铜金(亮底加深才够对比)
    private static final int L_BORDER  = 0xFFE5DFD5;   // 分隔线(暖)

    // ── 暗色 ──
    private static final int D_BG      = 0xFF0E0D0B;   // 页面底:深暖黑(非 #121212 中性灰)
    private static final int D_CARD    = 0xFF141210;   // 卡片/按钮底(比底微亮)
    private static final int D_TEXT    = 0xFFF2EADC;   // 主文字:暖白(非纯白)
    private static final int D_SUB     = 0xFF8A8175;   // 次要文字(暖灰)
    private static final int D_ACCENT  = 0xFFBC9B6A;   // 强调:古铜金【唯一强调色】
    private static final int D_BORDER  = 0x1FF2EADC;   // 分隔线:暖白 12% 透明

    private CantoTheme() {}

    /** 用户选的模式:"auto" / "dark" / "light" */
    public static String mode(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "auto");
    }

    public static void setMode(Context c, String m) {
        SharedPreferences.Editor e = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        e.putString(KEY, m).apply();
    }

    /** 系统现在是深色吗(Android 10+ 的"深色主题") */
    public static boolean systemDark(Context c) {
        int m = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return m == Configuration.UI_MODE_NIGHT_YES;
    }

    /** 最终该用暗色吗 */
    public static boolean isDark(Context c) {
        String m = mode(c);
        if ("dark".equals(m)) return true;
        if ("light".equals(m)) return false;
        return systemDark(c);          // auto
    }

    /** 模式的中文名(给按钮显示) */
    public static String modeLabel(Context c) {
        String m = mode(c);
        if ("dark".equals(m)) return "暗";
        if ("light".equals(m)) return "亮";
        return "自动";
    }

    /** 循环切换:自动 → 暗 → 亮 → 自动 */
    public static String nextMode(Context c) {
        String m = mode(c);
        if ("auto".equals(m)) return "dark";
        if ("dark".equals(m)) return "light";
        return "auto";
    }

    // ── 取色 ──
    public static int bg(Context c)     { return isDark(c) ? D_BG     : L_BG; }
    public static int card(Context c)   { return isDark(c) ? D_CARD   : L_CARD; }
    public static int text(Context c)   { return isDark(c) ? D_TEXT   : L_TEXT; }
    public static int sub(Context c)    { return isDark(c) ? D_SUB    : L_SUB; }
    public static int accent(Context c) { return isDark(c) ? D_ACCENT : L_ACCENT; }
    public static int border(Context c) { return isDark(c) ? D_BORDER : L_BORDER; }

    /** 圆角卡片背景(暗色下用比页面稍亮的面,形成层次) */
    public static GradientDrawable cardBg(Context c, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(card(c));
        d.setCornerRadius(radiusDp * c.getResources().getDisplayMetrics().density);
        d.setStroke((int) Math.max(1, c.getResources().getDisplayMetrics().density), border(c));
        return d;
    }

    /**
     * 造一个【跟主题同步】的 AlertDialog.Builder。
     *
     * ⚠️⚠️ 2026-09-27 修(Nija 报「弹窗还是底色的弹窗,没有跟主题同步」):
     *   `new AlertDialog.Builder(act)` 用的是【Activity 的主题】,
     *   而 Activity 主题来自 manifest(跟随【系统】),不是我们的三模式。
     *   ⇒ 症状:App 强制暗、系统是亮 ⇒ 【弹窗还是亮的】。
     *   ⇒ 正解:按 CantoTheme.isDark() 显式指定 dialog 主题。
     *
     * ⚠️ 这是"统一接口"的意思:凡是要弹窗的地方,【一律走这个方法】,
     *   不要在各处自己 new AlertDialog.Builder —— 那样迟早又漏一个。
     */
    public static android.app.AlertDialog.Builder dialogBuilder(android.app.Activity a) {
        int theme = isDark(a)
                ? android.R.style.Theme_DeviceDefault_Dialog_Alert          // 暗
                : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;   // 亮
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(a, theme);
        return b;
    }

    /**
     * 把整套配色【应用到 Activity 的视图树】。
     * ⚠️ 因为本 App 的界面是【纯 Java 创建控件】的(没有 XML layout),
     *   所以递归遍历一次视图树最省事,不必逐个控件设色。
     */
    public static void apply(Activity a) {
        View root = a.getWindow().getDecorView();
        // ⚠️⚠️ 2026-09-26 修(Nija 报「暗模式有点问题,背景文字」):
        //   实测暗色截图:屏幕【底部 25%(y 1800~2400)全宽】是一大块亮灰
        //   RGB(208,208,208)⇒ 那是【window 的背景】—— 我的 ScrollView 内容不够高,
        //   下方露出的是 window 默认背景(亮色),递归遍历【覆盖不到它】。
        //   ⇒ 必须显式给 decorView 与 window 都设底色。
        root.setBackgroundColor(bg(a));
        a.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(bg(a)));
        applyTo(a, root);
        a.getWindow().setStatusBarColor(bg(a));
        a.getWindow().setNavigationBarColor(bg(a));
        // 状态栏图标明暗(API 23+):暗色底 ⇒ 用亮图标
        View sv = a.getWindow().getDecorView();
        sv.setSystemUiVisibility(isDark(a)
                ? 0                                        // 亮图标
                : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);    // 暗图标
    }

    /**
     * 清掉控件的【backgroundTint】。
     *
     * ⚠️⚠️ 2026-09-26 修(Nija 报「暗模式有点问题,背景文字」):
     *   Android 5+ 的 Button / EditText 自带 backgroundTint(一个 ColorStateList),
     *   它会把我们 setBackground 设的颜色【盖掉】⇒ 暗色下按钮/输入框仍是
     *   【系统默认的亮灰 RGB(208,208,208)】。
     *   实测:暗色截图里 21% 的屏幕是那块亮灰 ⇒ 就是这些控件。
     *   ⇒ 必须显式清 tint,我们的配色才真正生效。
     */
    private static void clearTint(View v) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 21) {
                v.setBackgroundTintList(null);        // API 21+
            }
        } catch (Throwable ignored) {}
    }

    private static void applyTo(Context c, View v) {
        if (v instanceof TextView) {
            TextView t = (TextView) v;
            t.setTextColor(text(c));
            if (v instanceof EditText) {
                clearTint(v);
                ((EditText) v).setHintTextColor(sub(c));
                v.setBackground(cardBg(c, 8));
                int p = (int) (10 * c.getResources().getDisplayMetrics().density);
                v.setPadding(p, p, p, p);
            } else if (v instanceof Button) {
                clearTint(v);
                // ⚠️ 2026-09-26 按 Nija 反馈调整:「按钮蓝色太重」⇒ 改成【暗底 + 蓝字】
                //   (实心 accent 底占了 31% 屏幕,太抢眼)
                //   ⚠️ 但 clearTint 必须保留 —— 否则按钮仍是系统亮灰
                t.setTextColor(accent(c));       // 蓝字
                v.setBackground(cardBg(c, 10));  // 卡片底(=暗色下比页面稍亮)+ 描边
            } else {
                // 标题(字号 >= 18)用主色,其余用正文色
                if (t.getTextSize() >= 18 * c.getResources().getDisplayMetrics().scaledDensity) {
                    t.setTextColor(accent(c));
                }
            }
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            // ⚠️ 2026-09-26 修:原来只改文字/按钮色,忘了改【容器背景】
            //   ⇒ 实测暗色下平均亮度 187(还是亮的)⇒ 页面底色仍是系统默认
            //   ⇒ 这里给所有容器铺页面底色(ScrollView / LinearLayout 等)
            v.setBackgroundColor(bg(c));
            for (int i = 0; i < g.getChildCount(); i++) applyTo(c, g.getChildAt(i));
        } else if (v instanceof TextView && !(v instanceof Button) && !(v instanceof EditText)) {
            // 纯文本控件:透明底(让容器底色透出来)
            v.setBackgroundColor(Color.TRANSPARENT);
        }
    }
    // ─────────────────────────────────────────────────────────────
    // ★ 三层字体系统(design-aesthetic 第二原则)
    //   Display:大标题/关键值 —— 【绝不用来写长段落】
    //   Label  :标签/元信息   —— 小字号 + 大字距,像 mono
    //   Body   :正文/说明     —— light,行长要收窄
    //   ⚠️ 互斥:Label 不做正文,Display 不写长段
    // ─────────────────────────────────────────────────────────────

    /** 分隔线色(新增;sub/accent 已有) */
    public static int line(Context c) { return isDark(c) ? D_BORDER : L_BORDER; }

    /** Display —— 大标题 / 关键数值 */
    public static void display(android.widget.TextView t, Context c) {
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 24);
        t.setTextColor(text(c));
        t.setLetterSpacing(-0.02f);
        t.setTypeface(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.NORMAL);
        t.setPadding(0, 0, 0, dp(c, 6));
    }

    /** Label —— 标签 / 元信息(小 + 大字距,近似 mono 的观感) */
    public static void label(android.widget.TextView t, Context c) {
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
        t.setTextColor(accent(c));
        t.setLetterSpacing(0.18f);
        t.setAllCaps(false);
        t.setPadding(0, dp(c, 14), 0, dp(c, 2));
    }

    /** Body —— 说明文字(次级色,收窄行长) */
    public static void body(android.widget.TextView t, Context c) {
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13.5f);
        t.setTextColor(sub(c));
        t.setLineSpacing(dp(c, 3), 1.0f);
        t.setPadding(0, 0, 0, dp(c, 6));
    }

    private static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

}