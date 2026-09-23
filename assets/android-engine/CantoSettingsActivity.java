// SPDX-License-Identifier: AGPL-3.0-or-later
// canto-tts 系统 TTS 引擎 —— 设置页(音色 + 全部引擎旋钮)
//
// Nija 2026-09-27:「音质啊、码率啊、采样率啊这些,我希望都可以直接在引擎里面设置。」
//   ⇒ 本页把 tuning.properties 的【全部旋钮】暴露出来,不用再 adb 改文件。
//
// ⚠️ 引擎读的是 <模型目录>/tuning.properties —— 本页直接改那个文件(幂等重写)。
//    音色走 SharedPreferences(与主界面/CantoTtsService 共用)。
package canto;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public class CantoSettingsActivity extends Activity {

    public static final String PREFS = "canto_tts";
    public static final String KEY_VOICE = "voice";
    /** ★ 语言(2026-09-28 加):canto=粤语微调 · mandarin=基础版(普通话/英文) */
    public static final String KEY_LANG = "lang";
    /**
     * ★ 普通话/英文的音色(2026-09-28 加)。
     *   ⚠️ 必须与粤语的 voice 分开存 —— 基础版音色名(Junhao/Zhiming…)
     *      与粤语音色库名(male-default/cv01…)是【两套】,
     *      混在一个键里会查不到 ⇒ 回落第一把 ⇒ 用户感觉"只有一个音色且难听"(实测踩到)。
     */
    public static final String KEY_VOICE_ZH = "voice_zh";

    /** 旋钮定义:key / 中文名 / 说明 / 候选值(空=自由填) */
    private static final String[][] KNOBS = {
        { "outputSampleRate", "输出采样率(Hz)",
          "48k=模型原生 · 24k=推荐 · 16k/8k 更小。\n⚠️ 降采样率【不加速推理】,只省传输与文件大小。",
          "48000,24000,16000,8000" },
        { "maxPauseMs", "内部静音上限(ms)",
          "模型会在词边界插长静音(听起来「两字一顿」)。\n本项把内部静音压到这个上限;0=不处理。首尾静音不动。",
          "0,120,180,260,400" },
        { "maxFrames", "单次最大帧数",
          "1 帧 = 80ms 音频。太小会把话截断(实测 6 字需 56 帧)。\n150 ≈ 12 秒上限。",
          "80,150,300" },
        { "threads", "ORT 线程数",
          "0 = 自动(实测 Android 上 2 最优)。\n改这个不影响输出数值(md5 相同),只影响速度。",
          "0,2,4,6" },
        { "optLevel", "ORT 图优化级别",
          "all=全部优化(默认) · extended · basic · none。\n实测对本模型增益不明显。",
          "all,extended,basic,none" },
        { "ep", "Execution Provider",
          "cpu(实测最快) · xnnpack · nnapi · qnn。\n⚠️ 实测三个非 CPU 的 EP 都【没有提速】,xnnpack 反而慢 16%。",
          "cpu,xnnpack,nnapi,qnn" },
        { "arena", "CPU 内存池",
          "1=开(默认,快) · 0=关(峰值内存更低,慢一点)。",
          "1,0" },
        { "memPattern", "内存模式预分配",
          "1=开(默认,快) · 0=关(峰值内存更低)。",
          "1,0" },
        { "seedVariation", "每次合成有随机变化",
          "0=关(默认):同一句【逐位相同】—— 与参考实现逐位可复现。\n1=开:同一句每次略有不同(更自然,但不可逐位对比)。",
          "0,1" },
    };

    private LinearLayout list;
    private TextView hint;
    private File tuningFile;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sc = new ScrollView(this);
        sc.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);

        TextView title = new TextView(this);
        title.setText("MOSS-TTS-Nano 引擎 · 设置");
        title.setTextSize(20);
        root.addView(title);

        hint = new TextView(this);
        hint.setPadding(0, 16, 0, 8);
        root.addView(hint);

        // ★ 语言选择(粤语 / 普通话·英文)
        TextView lh = new TextView(this);
        lh.setTextSize(15);
        lh.setPadding(0, 0, 0, 4);
        lh.setText("语言 · 用哪个模型\n  canto = 粤语微调(汉字→粤拼→三元组)\n"
                + "  mandarin = 基础版(汉字/单词→SentencePiece;含英文,18 把音色)\n"
                + "  当前:" + currentLang());
        root.addView(lh);
        LinearLayout lrow = new LinearLayout(this);
        lrow.setOrientation(LinearLayout.HORIZONTAL);
        lrow.addView(btn("canto(粤语)", new Runnable() { @Override public void run() {
            setLang("canto"); rebuild();
        }}));
        lrow.addView(btn("mandarin(普通话/英文)", new Runnable() { @Override public void run() {
            if (!CantoModelSetup.langAvailable(CantoSettingsActivity.this, "mandarin")) {
                Toast.makeText(CantoSettingsActivity.this,
                        "⚠️ 基础版模型不在设备上:\nmodels/base-mandarin/\n(见 README 的下载说明)",
                        Toast.LENGTH_LONG).show();
                return;
            }
            setLang("mandarin"); rebuild();
        }}));
        root.addView(lrow);

        // ⚠️ 2026-09-28 去重:音色选择【只在首页】—— 设置页不再重复一套
        //   (Nija 报:"设置里又有套音色选择" ⇒ 两处 UI 会互相打脸)
        TextView vh = new TextView(this);
        vh.setText("音色在【首页】选 —— 本页只管引擎参数");
        vh.setTextSize(13);
        vh.setPadding(0, 8, 0, 8);
        root.addView(vh);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);

        sc.addView(root);
        setContentView(sc);
        CantoTheme.apply(this);
        resolveTuning();
        rebuild();
    }

    /** 定位 tuning.properties(App 内部模型目录) */
    private void resolveTuning() {
        try {
            File d = CantoModelSetup.ensure(this);
            tuningFile = new File(d, "tuning.properties");
            hint.setText("配置文件:" + tuningFile.getAbsolutePath());
        } catch (Throwable t) {
            hint.setText("⚠️ 找不到模型目录:" + t);
        }
    }

    private Properties readTuning() {
        Properties p = new Properties();
        try {
            if (tuningFile != null && tuningFile.isFile()) {
                FileInputStream in = new FileInputStream(tuningFile);
                try { p.load(in); } finally { in.close(); }
            }
        } catch (Throwable ignored) {}
        return p;
    }

    private void writeTuning(Properties p) {
        try {
            if (tuningFile == null) return;
            // 保留注释头(把原文件里以 # 开头的行拼回去)
            StringBuilder head = new StringBuilder();
            if (tuningFile.isFile()) {
                java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(new FileInputStream(tuningFile), "UTF-8"));
                String l;
                while ((l = r.readLine()) != null) if (l.trim().startsWith("#")) head.append(l).append('\n');
                r.close();
            }
            StringBuilder body = new StringBuilder();
            for (String key : p.stringPropertyNames()) {
                body.append(key).append('=').append(p.getProperty(key)).append('\n');
            }
            FileOutputStream out = new FileOutputStream(tuningFile);
            try { out.write((head + body.toString()).getBytes("UTF-8")); } finally { out.close(); }
            Toast.makeText(this, "已保存 · 下次合成生效", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "❌ 写入失败:" + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void rebuild() {
        list.removeAllViews();
        Properties p = readTuning();
        for (final String[] k : KNOBS) {
            list.addView(sectionLabel(k[1] + "  ·  " + k[0]));
            TextView d = new TextView(this);
            d.setText(k[2]);
            d.setTextSize(13);
            d.setPadding(0, 0, 0, 6);
            list.addView(d);
            final String cur = p.getProperty(k[0], "");
            list.addView(sectionLabel("当前:" + (cur.isEmpty() ? "(未设置,用内置默认)" : cur)));
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (final String cand : k[3].split(",")) {
                Button bt = new Button(this);
                bt.setText(cand);
                bt.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        Properties np = readTuning();
                        np.setProperty(k[0], cand);
                        writeTuning(np);
                        rebuild();
                    }
                });
                row.addView(bt);
            }
            list.addView(row);
            // 自由填
            final EditText et = new EditText(this);
            et.setHint("或直接填一个值");
            et.setText(cur);
            Button apply = new Button(this);
            apply.setText("应用自定义值");
            apply.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    String val = et.getText().toString().trim();
                    if (val.isEmpty()) return;
                    Properties np = readTuning();
                    np.setProperty(k[0], val);
                    writeTuning(np);
                    rebuild();
                }
            });
            list.addView(et);
            list.addView(apply);
        }
    }



    private String currentLang() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_LANG, "canto");
    }

    private void setLang(String l) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_LANG, l).apply();
        Toast.makeText(this, "已切到 " + l + " · 下次合成生效", Toast.LENGTH_SHORT).show();
    }

    private String currentVoice() {
        return getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(KEY_VOICE, CantoVoiceBank.DEFAULT_VOICE);
    }

    private TextView sectionLabel(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(15);
        t.setPadding(0, 22, 0, 4);
        return t;
    }

    private Button btn(String s, final Runnable r) {
        Button b = new Button(this);
        b.setText(s);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { r.run(); }
        });
        return b;
    }
}
