// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026  Nija (bubu12) and contributors
//
// CantoMainActivity —— moss-nano-port 引擎的极简主界面。原生 Java,零依赖,不用 Kotlin。
//
// ⚠️ 为什么这么"土"(纯 LinearLayout + Button,没有 Material/RecyclerView/协程):
//   Nija 指示:「引擎的 GUI 用原生 Java 做,极简不用 Kotlin」
//   ⇒ 不引 androidx、不引 Kotlin、不引任何第三方 UI 库
//   ⇒ 好处:APK 小、编译快(只用 aapt2+javac+d8)、可读、可改
//
// 它做四件事:
//   ① 显示状态(进程内推理在不在 / 模型目录 / 当前音色 / 音色数)
//   ② 起/停推理进程内推理(root;需要 KernelSU)
//   ③ 选音色(点一下即生效,存 SharedPreferences)
//   ④ 输入文本 → 试听(直接问进程内推理拿 PCM → AudioTrack 播)

package canto;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;


import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

public class CantoMainActivity extends Activity {

    private static final String TAG = "CantoMain";
    private static final String PREFS = "canto_tts";
    private static final String KEY_VOICE = "voice";

    private TextView status;
    /** 主题切换按钮(Nija 2026-09-26 要求暗黑模式) */
    private Button themeBtn;
    private EditText input;
    private LinearLayout voiceList;
    /** ★ 模型列表(2026-09-28) */
    private LinearLayout modelList;
    private CantoVoiceBank bank;
    /** ★ 进程内推理合成器(2026-09-28 加:删守护后界面也要能自己合成) */
    private volatile CantoSynthesizer synth;
    /** ⚠️ 只在进界面时提示一次 root,别反复弹 */
    private File modelDir;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sc = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(p, p, p, p);

        root.addView(tagLine("MOSS-TTS-NANO · 下游配套引擎"));
        root.addView(title("moss-nano-port"));

        status = new TextView(this);
        status.setTextSize(14);
        status.setPadding(0, p / 2, 0, p / 2);
        root.addView(status);

        // ── 进程内推理控制 ──
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.addView(btn("刷新状态", new Runnable() { public void run() { refresh(); } }));
        row1.addView(btn("⚙️ 设置", new Runnable() { public void run() {
            startActivity(new android.content.Intent(CantoMainActivity.this, CantoSettingsActivity.class));
        }}));
        root.addView(row1);

        // ── 主题(亮/暗/跟随系统)──
        LinearLayout row0 = new LinearLayout(this);
        row0.setOrientation(LinearLayout.HORIZONTAL);
        themeBtn = btn("🌓 主题:自动", new Runnable() { public void run() { cycleTheme(); } });
        row0.addView(themeBtn);
        root.addView(row0);

        // ── 试听 ──
        root.addView(label("试听文本:"));
        input = new EditText(this);
        input.setText("今日天氣幾好。");
        input.setSingleLine(false);
        root.addView(input);
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.addView(btn("🔊 试听", new Runnable() { public void run() { speak(); } }));
        row2.addView(btn("🔬 系统TTS自检", new Runnable() { public void run() { selfTestSystemTts(); } }));
        root.addView(row2);
        TextView hint = new TextView(this);
        hint.setText("「试听」= 进程内推理直接出 PCM(验模型+推理);\n"
                   + "「系统TTS自检」= 走完整系统 TTS 链路(验别的 App 调我们时能不能出声)");
        hint.setTextSize(12);
        root.addView(hint);

        // ── 音色 ──
        root.addView(label("音色(点一下即切换):"));
        modelList = new LinearLayout(this);
        modelList.setOrientation(LinearLayout.VERTICAL);
        root.addView(tagLine("模型 · 可导入"));
        root.addView(modelList);
        voiceList = new LinearLayout(this);
        voiceList.setOrientation(LinearLayout.VERTICAL);
        root.addView(voiceList);

        // ⚠️ 让内容不足一屏时也铺满 ⇒ 底部不露出 window 背景(暗色下会显成亮灰)
        sc.setFillViewport(true);
        sc.setBackgroundColor(CantoTheme.bg(this));
        root.setBackgroundColor(CantoTheme.bg(this));
        sc.addView(root);
        setContentView(sc);
        refresh();
        // ⚠️ 按 <内部路径> §P2:【依赖 root 的自研 App 必须弹窗告知,不许静默失败】
        //     不再在本类里自己写一套(那样每个 App 各写各的,Nija 明确反对)。
        // ⚠️ 2026-09-27 已移除 root 依赖(不再申请/使用 root)

        // ★ 暗黑模式(Nija 2026-09-26 要求):按用户偏好/系统设置整套配色
        CantoTheme.apply(this);

        // ⚠️⚠️ 2026-09-26 修(Nija「一键 install」实测发现):
        //   install.sh 只能把模型推到 /sdcard;内部目录要【App 自己拷】。
        //   原来自拷只在 Service 的合成路径里 ⇒ 启动 Activity 不触发
        //   ⇒ install 说成功,但内部是空的(进程内推理起不来、verify 全 0)。
        //   ⇒ 这里在【进界面时就在后台触发一次】(幂等,已就绪就直接返回)。
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    java.io.File d = CantoModelSetup.ensure(CantoMainActivity.this);
                    Log.i(TAG, "模型目录 = " + d + " 可用=" + CantoModelSetup.looksComplete(d));
                } catch (Throwable t) { Log.w(TAG, "模型目录准备失败", t); }
            }
        }, "canto-model-setup").start();
        if (themeBtn != null) themeBtn.setText("🌓 主题:" + CantoTheme.modeLabel(this));
        // ★ 自动化验收入口:adb shell am start ... --ez autoSelftest true
        //   ⇒ 自动跑一次系统 TTS 自检(便于 verify.sh / CI 用,不必手点)
        // 🔬 冻结论证实验入口
        if (getIntent() != null && getIntent().getBooleanExtra("overlayTest", false)) {
            overlayTest(getIntent().getIntExtra("ioSeconds", 30));
        }
        if (getIntent() != null && getIntent().getBooleanExtra("ioStressTest", false)) {
            String m = getIntent().getStringExtra("ioMode");
            int sec = getIntent().getIntExtra("ioSeconds", 30);
            ioStressTest(m == null ? "file" : m, sec);
        }
        if (getIntent() != null && getIntent().getBooleanExtra("autoSelftest", false)) {
            input.postDelayed(new Runnable() { public void run() { selfTestSystemTts(); } }, 1200);
        }
    }




    /**
     * 🔬 路 A 验证:【不可见 overlay 能否让进程保持"前台可见"⇒ 不被冻】。
     *
     * 已知(实测):
     *   · Activity 在前台 ⇒ 连烧 CPU 30 秒不被冻
     *   · 一按 HOME 退到后台 ⇒ 2 秒内被冻(wchan=do_freezer_trap)
     *
     * 若 overlay 也能达到同样的"前台可见"效果 ⇒
     *   TTS 服务在被系统 bind 时,挂一个不可见 overlay 就能【不依赖 root】。
     *   ⚠️ 需要 SYSTEM_ALERT_WINDOW 权限(adb: appops set <pkg> SYSTEM_ALERT_WINDOW allow)
     *
     * 用法:am start ... --ez overlayTest true --ei ioSeconds 30
     */
    private android.view.View overlayView;

    private void overlayTest(int seconds) {
        try {
            android.view.WindowManager wm = (android.view.WindowManager) getSystemService(WINDOW_SERVICE);
            // TYPE_APPLICATION_OVERLAY = API 26+;旧版用 TYPE_PHONE
            int type = android.os.Build.VERSION.SDK_INT >= 26
                    ? android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : android.view.WindowManager.LayoutParams.TYPE_PHONE;
            android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams(
                    1, 1, type,                      // 1x1 像素
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    android.graphics.PixelFormat.TRANSLUCENT);
            lp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
            lp.x = 0; lp.y = 0;
            lp.alpha = 0.01f;                        // 几乎不可见
            overlayView = new android.view.View(this);
            overlayView.setBackgroundColor(0x00000000);
            wm.addView(overlayView, lp);
            Log.i("CantoOverlay", "OVERLAY_ADDED type=" + type);
        } catch (Throwable t) {
            Log.e("CantoOverlay", "OVERLAY_FAILED", t);
        }
        ioStressTest("cpu", seconds);
    }

    private void removeOverlay() {
        try {
            if (overlayView != null) {
                ((android.view.WindowManager) getSystemService(WINDOW_SERVICE)).removeView(overlayView);
                overlayView = null;
                Log.i("CantoOverlay", "OVERLAY_REMOVED");
            }
        } catch (Throwable ignored) {}
    }

    /**
     * 🔬 冻结论证实验(2026-09-27 加,决定【能否去掉 root 依赖】):
     *
     * 已知:
     *   · App 烧 CPU 做推理 ⇒ t≈6s 被冻(CPU 时间停止增长)
     *   · 但 App 等进程内推理回包(进程内推理每帧发心跳字节)⇒ 连续 24 秒不被冻
     * 🔑 假说:【「有 IO 活动」能防冻,「纯烧 CPU」不能】
     *
     * 用法:am start ... --ez ioStressTest true --ei ioSeconds 30
     *   --ez ioMode cpu  ⇒ 纯烧 CPU(对照组)
     *   --ez ioMode file ⇒ 持续写文件(实验组)
     */
    private void ioStressTest(final String mode, final int seconds) {
        final java.io.File f = new java.io.File(getFilesDir(), "io_stress.bin");
        Log.i("CantoIoTest", "START mode=" + mode + " seconds=" + seconds);
        new Thread(new Runnable() {
            @Override public void run() {
                long t0 = System.currentTimeMillis();
                long n = 0;
                try {
                    while (System.currentTimeMillis() - t0 < seconds * 1000L) {
                        if ("file".equals(mode)) {
                            java.io.FileOutputStream o = new java.io.FileOutputStream(f);
                            o.write(("beat " + n + " " + System.currentTimeMillis() + "\n").getBytes());
                            o.close();
                            Thread.sleep(100);
                        } else {
                            // 纯烧 CPU(对照组):忙 100ms 再睡 100ms
                            long e = System.currentTimeMillis() + 100;
                            while (System.currentTimeMillis() < e) { }
                            Thread.sleep(100);
                        }
                        n++;
                        if (n % 30 == 0) {
                            Log.i("CantoIoTest", "beat " + n + " t=" + (System.currentTimeMillis()-t0) + "ms");
                        }
                    }
                } catch (Throwable t) {
                    Log.e("CantoIoTest", "异常", t);
                }
                long ms = System.currentTimeMillis() - t0;
                Log.i("CantoIoTest", "DONE mode=" + mode + " n=" + n + " 用时=" + ms + "ms");
            }
        }, "canto-io-stress").start();
    }

    /**
     * 循环切换主题:自动 → 暗 → 亮。
     * ⚠️ 切换后【重建 Activity】让整套配色重新应用(纯 Java 界面没有 XML 主题可依赖)。
     */
    private void cycleTheme() {
        String next = CantoTheme.nextMode(this);
        CantoTheme.setMode(this, next);
        // recreate() 是 API 11+;它会重跑 onCreate ⇒ 自动应用新配色
        recreate();
    }

    /**
     * 系统夜间模式变化时(用户在系统设置里切换深色主题)——
     * 若当前是"自动",则重建以跟上。
     * ⚠️ 需要在 manifest 里声明 android:configChanges 才收得到;这里做兜底:
     *   没声明也不影响(用户可手动切)。
     */
    @Override public void onConfigurationChanged(android.content.res.Configuration cfg) {
        super.onConfigurationChanged(cfg);
        if ("auto".equals(CantoTheme.mode(this))) {
            CantoTheme.apply(this);

        // ⚠️⚠️ 2026-09-26 修(Nija「一键 install」实测发现):
        //   install.sh 只能把模型推到 /sdcard;内部目录要【App 自己拷】。
        //   原来自拷只在 Service 的合成路径里 ⇒ 启动 Activity 不触发
        //   ⇒ install 说成功,但内部是空的(进程内推理起不来、verify 全 0)。
        //   ⇒ 这里在【进界面时就在后台触发一次】(幂等,已就绪就直接返回)。
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    java.io.File d = CantoModelSetup.ensure(CantoMainActivity.this);
                    Log.i(TAG, "模型目录 = " + d + " 可用=" + CantoModelSetup.looksComplete(d));
                } catch (Throwable t) { Log.w(TAG, "模型目录准备失败", t); }
            }
        }, "canto-model-setup").start();
            if (themeBtn != null) themeBtn.setText("🌓 主题:" + CantoTheme.modeLabel(this));
        }
    }



    /**
     * 【系统 TTS 自检】—— 走完整系统 TTS 链路。
     *
     * ⚠️ 为什么要做进引擎 App 而不是另开一个"验收客户端"(Nija 2026-09-25 指示):
     *   「我之前的要求是整合在引擎的app里面,而不是你东一个app西一个app。所以如果可以删的话,那就删掉。」
     *   ⇒ 验收能力【保留】,但【收进同一个 App】。
     *
     * 它做的事:用【显式引擎包名】的 TextToSpeech 构造器(不改系统默认引擎),
     *   说一句话,监听 UTTER_START/UTTER_DONE 把结果【弹窗】报出来(§P2 不许静默失败)。
     */
    private void selfTestSystemTts() {
        final String text = input.getText().toString().trim();
        status.setText("系统 TTS 自检中…");
        final android.speech.tts.TextToSpeech[] tts = new android.speech.tts.TextToSpeech[1];
        final StringBuilder log = new StringBuilder();
        // ⚠️ 用 3 参数构造器【显式指定本引擎】⇒ 不动系统默认引擎
        tts[0] = new android.speech.tts.TextToSpeech(this,
                new android.speech.tts.TextToSpeech.OnInitListener() {
            @Override public void onInit(int st) {
                log.append("onInit status=").append(st).append('\n');
                if (st != android.speech.tts.TextToSpeech.SUCCESS) {
                    dialog("❌ 系统 TTS 初始化失败",
                           log + "\n可能:引擎没被系统列为 TTS 引擎(看 verify.sh 那条断言)");
                    return;
                }
                tts[0].setOnUtteranceProgressListener(
                        new android.speech.tts.UtteranceProgressListener() {
                    @Override public void onStart(String id) {
                        log.append("UTTER_START ").append(id).append('\n');
                    }
                    @Override public void onDone(String id) {
                        log.append("UTTER_DONE ").append(id).append('\n');
                        runOnUiThread(new Runnable() { public void run() {
                            status.setText("✅ 系统 TTS 自检完成");
                            dialog("✅ 系统 TTS 链路通了", log.toString());
                        }});
                    }
                    @Override public void onError(String id) {
                        log.append("UTTER_ERROR ").append(id).append('\n');
                        runOnUiThread(new Runnable() { public void run() {
                            dialog("❌ 系统 TTS 报错", log.toString());
                        }});
                    }
                });
                int r = tts[0].speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH,
                        null, "selftest-1");
                log.append("speak ret=").append(r).append('\n');
                if (r != android.speech.tts.TextToSpeech.SUCCESS) {
                    dialog("❌ speak() 返回失败", log.toString());
                }
            }
        }, getPackageName());   // ★ 显式指向本引擎(默认不动系统默认引擎)
    }

    /** 输出采样率 —— 读 <模型目录>/tuning.properties 的 outputSampleRate,默认 24000 */
    private int outSampleRate() {
        try {
            if (modelDir != null) {
                File f = new File(modelDir, "tuning.properties");
                if (f.isFile()) {
                    java.util.Properties p = new java.util.Properties();
                    FileInputStream in = new FileInputStream(f);
                    try { p.load(in); } finally { in.close(); }
                    return Integer.parseInt(p.getProperty("outputSampleRate", "24000"));
                }
            }
        } catch (Throwable ignored) {}
        return 24000;
    }

    // ────────────────────────── 基本控件 ──────────────────────────

    /**
     * Section 头(design-aesthetic 的固定格式):
     *   <标签>  ← 小字号 + 大字距 + 古铜金
     *   <主标题> ← Display 字号
     */
    private TextView title(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        CantoTheme.display(t, this);
        return t;
    }

    /** 标签行(用 accent 色,克制使用) */
    private TextView tagLine(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        CantoTheme.label(t, this);
        return t;
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        CantoTheme.body(t, this);
        return t;
    }

    /**
     * ★ 基础版的音色名(2026-09-28 加)。
     *   基础版【没有 voicebank/ 目录】—— 它的音色在 manifest 的 builtin_voices(18 把)。
     *   ⇒ 界面要按当前语言显示对应的音色来源。
     */

    /** 内置音色明细:{voice, group, display_name} */
    /**
     * 基础版内置音色的【原始数据】:{voice, group, display_name, int[] codes}
     * codes 是压平的 [nFrames][16]。给 currentVoiceCodes() 用。
     */
    private java.util.List<Object[]> builtinVoicesRaw() {
        java.util.List<Object[]> out = new java.util.ArrayList<Object[]>();
        try {
            File mf = new File(CantoModelSetup.langDir(this, "mandarin"), "browser_poc_manifest.json");
            byte[] buf = new byte[(int) mf.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(mf);
            int n;
            try { n = in.read(buf); } finally { in.close(); }
            org.json.JSONArray arr = new org.json.JSONObject(new String(buf, 0, n, "UTF-8"))
                    .getJSONArray("builtin_voices");
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.getJSONObject(i);
                org.json.JSONArray rows = o.getJSONArray("prompt_audio_codes");
                int frames = rows.length();
                int[] codes = new int[frames * 16];
                int k = 0;
                for (int f = 0; f < frames; f++) {
                    org.json.JSONArray row = rows.getJSONArray(f);
                    for (int c = 0; c < 16; c++) codes[k++] = row.optInt(c, 0);
                }
                out.add(new Object[]{ o.optString("voice", "?"), o.optString("group", ""),
                        o.optString("display_name", ""), codes });
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private java.util.List<String[]> builtinVoiceList() {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        try {
            File mf = new File(CantoModelSetup.langDir(this, "mandarin"), "browser_poc_manifest.json");
            byte[] buf = new byte[(int) mf.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(mf);
            int n;
            try { n = in.read(buf); } finally { in.close(); }
            org.json.JSONArray arr = new org.json.JSONObject(new String(buf, 0, n, "UTF-8"))
                    .getJSONArray("builtin_voices");
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.getJSONObject(i);
                out.add(new String[]{ o.optString("voice", "?"),
                        o.optString("group", ""), o.optString("display_name", "") });
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private java.util.List<String> builtinVoiceNames() {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            File mf = new File(CantoModelSetup.langDir(this, "mandarin"), "browser_poc_manifest.json");
            byte[] buf = new byte[(int) mf.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(mf);
            int n;
            try { n = in.read(buf); } finally { in.close(); }
            org.json.JSONArray arr = new org.json.JSONObject(new String(buf, 0, n, "UTF-8"))
                    .getJSONArray("builtin_voices");
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.getJSONObject(i);
                out.add(o.optString("voice", "?") + "(" + o.optString("group", "") + ")");
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private Button btn(String s, final Runnable r) {
        Button b = new Button(this);
        b.setText(s);
        // ★ 设计系统:禁用 Material 默认的蓝底/蓝字,改用卡片底 + 强调色文字
        //   ⚠️ 必须先清 backgroundTint,否则 setBackground 又被覆盖(踩过)
        b.setBackgroundTintList(null);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(CantoTheme.card(this));
        g.setCornerRadius(10 * getResources().getDisplayMetrics().density);
        g.setStroke((int) (1 * getResources().getDisplayMetrics().density),
                CantoTheme.line(this));
        b.setBackground(g);
        b.setTextColor(CantoTheme.text(this));
        b.setAllCaps(false);
        b.setLetterSpacing(0.04f);
        b.setAllCaps(false);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { r.run(); }
        });
        return b;
    }

    /** 统一的弹窗(关键路径【不用 Toast】—— 见 <内部路径> §P2) */
    private void dialog(String title, String msg) {
        CantoTheme.dialogBuilder(this)
                .setTitle(title).setMessage(msg)
                .setPositiveButton("知道了", null).show();
    }

    // ────────────────────────── 状态与音色 ──────────────────────────

    private void refresh() {
        // ⚠️ 2026-09-28:不再 resolveModelDir()/loadBank() ——
        //   模型与音色都【只问 CantoModel】,避免两套来源打架(那是"选了没反应"的根因)
        StringBuilder sb = new StringBuilder();
        sb.append("引擎:【进程内推理】不依赖 root").append('\n');
        // ★ 模型目录按【当前语言】显示(2026-09-28):
        //   以前恒显示 modelDir(粤语),而切到 mandarin 时引擎用的是 base-mandarin
        //   ⇒ 界面与实际不符(踩过:看着是 canto,日志里是 base-mandarin)
        CantoModel cm = CantoModel.current(CantoMainActivity.this);
        File md = cm == null ? CantoModelSetup.langDir(CantoMainActivity.this, "canto") : cm.dir;
        boolean zhM = cm != null && cm.encoding == CantoModel.Encoding.SENTENCEPIECE;
        sb.append("模型:").append(cm == null ? " ❌ 无可用模型"
                : " " + cm.displayName + (cm.usable() ? " ✅" : " ⚠️ " + cm.problem)).append('\n');
        sb.append("编码:").append(zhM ? "SentencePiece" : "粤语三元组")
          .append(" · 音色 ").append(cm == null ? 0 : cm.voices.size()).append(" 把\n");
        sb.append(md.getAbsolutePath()).append('\n');
        sb.append("输出:采样率 ").append(outSampleRate()).append("Hz · 立体声 16bit\n");
        // ★ 音色来源按语言不同(2026-09-28):
        //   粤语   voicebank/ 目录(7 把)   普通话/英文 manifest 的 builtin_voices(18 把)
        boolean zh = false;   // 已废弃的语言分支(仅保留旧代码可编译)
        if (zh) {
            java.util.List<String> bl = builtinVoiceNames();
            sb.append("音色:").append(bl.isEmpty() ? "⚠️ 读不到" : bl.size() + " 把(基础版内置)")
              .append("\n   ").append(bl.isEmpty() ? "" : String.join(" · ", bl.subList(0, Math.min(6, bl.size()))))
              .append(bl.size() > 6 ? " …" : "");
        } else {
            sb.append("音色:").append(bank == null ? 0 : bank.names().size())
              .append(" 把 · 当前 ").append(currentVoice());
        }
        status.setText(sb.toString());
        buildModelButtons();
        buildVoiceButtons(true);
        // ★ 2026-09-28 加【日志断言】:
        //   uiautomator dump 对本 App 读不出、截图又要唤醒用户的平板 ——
        //   所以界面内容【同时打进 logcat】,可 grep 验收,不依赖看屏幕。
        logUiDump();
    }

    /**
     * 把界面上的关键内容打进日志(供 adb logcat 断言)。
     * grep 这个 TAG 就能知道:有哪些模型、选的哪个、列了哪些音色、选的是哪把。
     */
    private void logUiDump() {
        try {
            CantoModel cur = CantoModel.current(this);
            java.util.List<CantoModel> all = CantoModel.scan(this);
            StringBuilder sb = new StringBuilder();
            sb.append("models=").append(all.size()).append(" {");
            for (CantoModel m : all) {
                sb.append(m.name).append(m.usable() ? "(" + m.voices.size() + "音色)" : "(不可用)")
                  .append(m.languages.isEmpty() ? "" : "[" + String.join("/", m.languages) + "]").append(' ');
            }
            sb.append("} current=").append(cur == null ? "(none)" : cur.name);
            if (cur != null && cur.usable()) {
                // ⚠️ 打印【实际选中的音色名】而不是读旧键 ——
                //   踩过:Junhao 与 Zhiming 都是 98 帧,只打帧数根本分不出选的是哪把
                CantoModel.VoiceRef picked = cur.pickVoice(this);
                sb.append(" enc=").append(cur.encoding).append(" voices=").append(cur.voices.size());
                sb.append(" 选中=").append(picked == null ? "(none)" : picked.name)
                  .append("(声明默认=").append(cur.defaultVoice.isEmpty() ? "无" : cur.defaultVoice)
                  .append(" 用户选=").append(CantoModel.preferredVoiceName(this).isEmpty()
                          ? "无" : CantoModel.preferredVoiceName(this)).append(")").append(" {");
                int n = 0;
                for (CantoModel.VoiceRef v : cur.voices) {
                    if (n++ >= 6) { sb.append("…"); break; }
                    sb.append(v.name).append('/');
                }
                sb.append("}");
                int[] c = currentVoiceCodes();
                sb.append(" codes=").append(c == null ? "null" : (c.length / 16) + "帧");
            }
            java.util.List<File> cands = CantoModel.importCandidates(this);
            sb.append(" importable=").append(cands.size());
            Log.i("CantoUiDump", sb.toString());
        } catch (Throwable t) {
            Log.w("CantoUiDump", "dump 失败", t);
        }
    }

    /** 模型区:列全部模型 + 从暂存区导入(2026-09-28 加) */
    private void buildModelButtons() {
        if (modelList == null) return;
        modelList.removeAllViews();
        java.util.List<CantoModel> all = CantoModel.scan(this);
        CantoModel cur = CantoModel.current(this);
        for (final CantoModel m : all) {
            boolean sel = cur != null && cur.name.equals(m.name);
            Button b = new Button(this);
            b.setText((sel ? "\u2705 " : "\u3000 ") + m.displayName
                    + (m.languages.isEmpty() ? "" : "  [" + String.join("/", m.languages) + "]")
                    + (m.usable() ? "  \u00b7 " + m.voices.size() + " \u97f3\u8272" : "  \u26a0\ufe0f " + m.problem));
            b.setAllCaps(false);
            b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View x) {
                    if (!m.usable()) {
                        Toast.makeText(CantoMainActivity.this, "\u4e0d\u53ef\u7528:" + m.problem,
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    CantoModel.select(CantoMainActivity.this, m.name);
                    synth = null; synthLang = null;
                    Toast.makeText(CantoMainActivity.this, "\u5df2\u9009\u6a21\u578b \u2192 " + m.displayName,
                            Toast.LENGTH_SHORT).show();
                    refresh();
                }
            });
            modelList.addView(b);
        }
        java.util.List<File> cands = CantoModel.importCandidates(this);
        if (!cands.isEmpty()) {
            modelList.addView(label("\u2014\u2014 \u6682\u5b58\u533a\u53ef\u5bfc\u5165(" + cands.size() + ") \u2014\u2014"));
            for (final File d : cands) {
                Button b = new Button(this);
                b.setText("\u2b07\ufe0f \u5bfc\u5165: " + d.getName());
                b.setAllCaps(false);
                b.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View x) {
                        String err = CantoModel.importFrom(CantoMainActivity.this, d, null);
                        Toast.makeText(CantoMainActivity.this,
                                err == null ? "\u2705 \u5df2\u5bfc\u5165,\u53ef\u70b9\u5b83\u9009\u7528" : "\u274c " + err,
                                Toast.LENGTH_LONG).show();
                        refresh();
                    }
                });
                modelList.addView(b);
            }
        }
        TextView hint = label("\u628a\u6a21\u578b\u76ee\u5f55\u653e\u5230 "
                + "/sdcard/Android/data/canto.tts/files/models/ \u4e0b,\u91cd\u542f\u672c\u9875\u5373\u53ef\u5bfc\u5165"
                + "\n\uff08\u9700\u542b browser_poc_manifest.json\uff09");
        hint.setTextSize(12);
        modelList.addView(hint);
    }

    private void buildVoiceButtons(boolean daemonUp) {
        voiceList.removeAllViews();
        // ★ 2026-09-28 统一:音色【完全由当前模型决定】,不再有语言分支
        //   ⚠️ 踩过:旧的 "mandarin 分支" 排在前面抢先 return ⇒ 音色列表永远不变
        CantoModel m = CantoModel.current(this);
        if (m == null) { voiceList.addView(label("(models/ 里没有可用模型)")); return; }
        if (!m.usable()) { voiceList.addView(label("⚠️ " + m.name + ":" + m.problem)); return; }
        if (m.voices.isEmpty()) { voiceList.addView(label("(该模型没有声明音色)")); return; }
        CantoModel.VoiceRef cur = m.pickVoice(this);
        for (final CantoModel.VoiceRef v : m.voices) {
            boolean sel = cur != null && v.name.equals(cur.name);
            Button b = new Button(this);
            b.setText((sel ? "✅ " : "　 ") + v.name
                    + (v.group.isEmpty() ? "" : "  —  " + v.group)
                    + (v.display.isEmpty() ? "" : "  ·  " + v.display)
                    + (v.frames > 0 ? "  (" + v.frames + "帧)" : "  (无 codes)"));
            b.setAllCaps(false);
            b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            b.setEnabled(v.codes != null);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View x) {
                    // 按【模型】分键保存;并【清引擎缓存】让下次合成用新音色
                    CantoModel.setPreferredVoice(CantoMainActivity.this, m.name, v.name);
                    synth = null; synthLang = null;
                    Toast.makeText(CantoMainActivity.this, "音色 → " + v.name, Toast.LENGTH_SHORT).show();
                    refresh();
                }
            });
            voiceList.addView(b);
        }
    }

    /**
     * 试听:把文本交给进程内推理(选中的音色),拿回 PCM,用 AudioTrack 播。
     * ⚠️ 这里【不走系统 TTS 框架】—— 那是给别的 App 用的;
     *    本界面是"引擎自检",进程内推理最简单、也最能反映真实链路。
     */

    /**
     * ★ 进程内推理合成器(2026-09-28 加)。
     *
     * ⚠️ 背景:删掉 root 守护后,界面的「试听」也必须【自己合成】。
     *   实现照 CantoTtsService.ensureReady() 做,字段各自持有(两个组件不同生命周期)。
     */
    /** ★ 当前引擎是按哪个语言建的(切语言时要重建) */
    private volatile String synthLang;

    private CantoSynthesizer ensureLocal() throws Exception {
        // ★ 2026-09-28 统一:只问【模型注册表】,不再看 settingsLang()
        //   ⚠️ 踩过:这里留着旧的语言分支 ⇒ 选模型完全没反应、恒走普通话
        CantoModel m = CantoModel.current(this);
        String wantKey = (m == null ? "(none)" : m.name);
        if (synth != null && wantKey.equals(synthLang)) return synth;
        synchronized (this) {
            if (synth != null && wantKey.equals(synthLang)) return synth;
            if (m == null || !m.usable()) {
                throw new IllegalStateException("没有可用模型(models/ 里缺 browser_poc_manifest.json)");
            }
            boolean sp = m.encoding == CantoModel.Encoding.SENTENCEPIECE;
            modelDir = m.dir;
            synth = null;
            bank = null;                       // 切模型/切语言都要清,防串味
            CantoOrtEngine.Log ortLog = new CantoOrtEngine.Log() {
                @Override public void line(String x) { Log.i("CantoOrt", x); }
            };
            File ttsDir = sp ? modelDir : new File(modelDir, "tts");
            File codecDir = sp ? CantoModelSetup.codecDir(this) : new File(modelDir, "codec");
            CantoOrtBackend backend = new CantoOrtBackend(
                    ttsDir.getAbsolutePath(), codecDir.getAbsolutePath(), 0, ortLog);
            backend.load();
            CantoSynthesizer.G2p g2p;
            P2y p2y = null;
            if (sp) {
                backend.useSentencePiece(true);
                g2p = CantoSynthesizer.identityG2p();
            } else {
                g2p = new CantoG2pJni(modelDir);
                try { p2y = P2y.load(new java.io.FileInputStream(new File(modelDir, "p2y-rules.tsv"))); }
                catch (Exception e) { Log.w(TAG, "p2y 表缺失: " + e.getMessage()); }
                CantoTokenTable.fromModelDir(modelDir.getAbsolutePath());
            }
            if (!sp) {
                File vb = new File(modelDir, "voicebank");
                java.util.List<CantoVoiceBank.Voice> vs = new java.util.ArrayList<CantoVoiceBank.Voice>();
                File[] fs = vb.listFiles();
                if (fs != null) for (File f : fs) {
                    if (!f.getName().endsWith(".json")) continue;
                    try { vs.add(CantoVoiceBank.parse(new java.io.FileInputStream(f),
                            f.getName().replace(".json", ""))); }
                    catch (Exception e) { Log.w(TAG, "音色 " + f.getName() + " 解析失败", e); }
                }
                bank = CantoVoiceBank.of(vs);
            }
            int[] codes = currentVoiceCodes();
            synth = new CantoSynthesizer(backend, g2p, p2y, codes);
            synthLang = wantKey;
            Log.i(TAG, "GUI_ENGINE_READY model=" + m.name + " sp=" + sp
                    + " ttsDir=" + ttsDir + " voices=" + m.voices.size());
            return synth;
        }
    }

    /**
     * ★ 当前音色的 codes —— 【问模型】,不问语言(2026-09-28 改)。
     *   模型自己声明它有哪些音色(voicebank / builtin_voices / default_voice),
     *   我们只负责把用户选的【名字】映射成 codes。
     */
    /** ★ 当前音色名 —— 问模型(不再读旧键) */
    private String currentVoice() {
        try {
            CantoModel m = CantoModel.current(this);
            if (m == null) return "-";
            CantoModel.VoiceRef v = m.pickVoice(this);
            return v == null ? "-" : v.name;
        } catch (Throwable t) { return "-"; }
    }

    private int[] currentVoiceCodes() {
        try {
            CantoModel m = CantoModel.current(this);
            if (m == null || m.voices.isEmpty()) return null;
            CantoModel.VoiceRef pick = m.pickVoice(this);   // ★ 统一:model.json > 本模型偏好 > 第一把
            return pick == null ? null : pick.codes;
        } catch (Throwable t) { Log.w(TAG, "取音色失败", t); return null; }
    }

    private void speak() {
        final String text = input.getText().toString().trim();
        if (text.isEmpty()) { Toast.makeText(this, "先输点字", Toast.LENGTH_SHORT).show(); return; }
        status.setText("合成中…");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    long t0 = System.currentTimeMillis();
                    // ★ 进程内推理(不再走守护)
                    int[] vcodes = currentVoiceCodes();   // ★ 统一入口(按语言)
                    short[] pcm = ensureLocal().synthesize(text,
                            CantoSegmenter.Mode.NONE, 240, true, vcodes);
                    if (pcm == null || pcm.length == 0) {
                        throw new IllegalStateException("合成返回空(检查模型与音色)");
                    }
                    // ⚠️⚠️ 2026-09-28 修:必须降采样!
                    //   模型原生 48kHz,而播放按 outSampleRate()(默认 24000)
                    //   少了这一步 ⇒ 半速 + 时长翻倍(用户报「子弹时间」)
                    final int r = outSampleRate();
                    // ★ 剪掉过长内部静音(模型会插长静音 ⇒ "两字一顿")
                    pcm = CantoResampler.trimLongPauses(pcm, 2, CantoResampler.SRC_RATE, 180);
                    if (r > 0 && r != CantoResampler.SRC_RATE) {
                        pcm = CantoResampler.resample(pcm, 2, r);
                    }
                    long ms = System.currentTimeMillis() - t0;
                    Log.i(TAG, "试听 OK 采样=" + pcm.length + " 耗时=" + ms + "ms");
                    play(pcm, r);
                    final String m = "✅ 时长 " + String.format("%.2f", pcm.length / 2.0 / r)
                            + "s · 采样点数 " + pcm.length
                            + " · 采样率 " + r + "Hz · " + ms + "ms · 音色=" + currentVoice();
                    runOnUiThread(new Runnable() { public void run() { status.setText(m); } });
                } catch (Throwable t) {
                    Log.e(TAG, "试听失败", t);
                    final String m = "❌ " + t.getClass().getSimpleName() + ": " + t.getMessage();
                    runOnUiThread(new Runnable() { public void run() {
                        status.setText(m + "\n(请看 logcat 的 CantoMain/CantoOrt)");
                        Toast.makeText(CantoMainActivity.this, m, Toast.LENGTH_LONG).show();
                    }});
                }
            }
        }, "canto-gui-speak").start();
    }

    private void play(short[] pcm, int rate) {
        try {
            int min = AudioTrack.getMinBufferSize(rate,
                    AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            AudioTrack at = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                    .setBufferSizeInBytes(Math.max(min, pcm.length * 2))
                    .setTransferMode(AudioTrack.MODE_STATIC).build();
            at.write(pcm, 0, pcm.length);
            at.play();
            // 播完再释放(简单起见:按时长 sleep)
            long durMs = pcm.length / 2 * 1000L / rate + 300;
            try { Thread.sleep(durMs); } catch (InterruptedException ignored) {}
            at.stop(); at.release();
        } catch (Throwable t) {
            Log.e(TAG, "play 失败", t);
        }
    }
}
