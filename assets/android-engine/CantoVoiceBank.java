// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026  Nija (bubu12) and contributors
//
// CantoVoiceBank —— 音色库(零样本克隆的"音色"从哪来)。原生 Java,只用 org.json。
//
// 设计要点:
//   1) 音色的本质是【参照音频经 codec_encode 编出的 codes】(nFrames × 16),
//      不是 embedding ⇒ 我们只需存/读这些 int。
//   2) 只读需要的键:json 里的 ref_audio(原始 wav 路径)在 Android 上没用 ⇒ 不读。
//   3) timbre_ref(74 维音色指纹)要带上 —— 它是"多候选挑最好"的门槛依据
//      (F0 判不出"是不是同一个人",靠它)。
//   4) 对接点与 Python 版同一行语义:backend.defaultVoiceCodes = voice.codes。

package canto;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

public final class CantoVoiceBank {

    /**
     * 默认音色。⚠️ 2026-09-25 改为【原版男声】(male-default):
     *   · 来源:MOSS-TTS-Nano 模型内置的 default_voice(零参照音频)
     *   · 从 browser_poc_manifest.json 的 default_voice.prompt_audio_codes 导出(44 帧 × 16)
     *   · 之前测的是 cv03(Common Voice 女声)—— 那是"可选音色"之一
     *   · Nija 指示:"用 canto-tts 的原版男声,测试实战"
     */
    public static final String DEFAULT_VOICE = "male-default";

    public static final class Voice {
        public final String name;
        public final int[][] codes;      // [nFrames][16]
        public final float[] timbreRef;  // 74 维,可为 null
        public final String source, license, desc;
        public final float refF0;

        Voice(String name, int[][] codes, float[] timbre, String source, String license, String desc, float f0) {
            this.name = name; this.codes = codes; this.timbreRef = timbre;
            this.source = source; this.license = license; this.desc = desc; this.refF0 = f0;
        }
        public int nFrames() { return codes.length; }
        /** 压平成一维(ORT 图吃扁平输入时用) */
        public int[] flatten() {
            int[] out = new int[codes.length * 16];
            int k = 0;
            for (int[] f : codes) for (int c : f) out[k++] = c;
            return out;
        }
    }

    private final Map<String, Voice> voices;
    public final String defaultName;

    private CantoVoiceBank(Map<String, Voice> voices, String defaultName) {
        this.voices = voices; this.defaultName = defaultName;
    }

    /** 从单个 json 流解析 */
    public static Voice parse(InputStream in, String fallbackName) throws Exception {
        String json = readAll(in);
        JSONObject o = new JSONObject(json);
        String name = o.optString("voice", fallbackName);
        JSONArray arr = o.getJSONArray("prompt_audio_codes");
        int[][] codes = new int[arr.length()][];
        for (int i = 0; i < arr.length(); i++) {
            JSONArray row = arr.getJSONArray(i);
            int[] f = new int[row.length()];
            for (int j = 0; j < row.length(); j++) f[j] = row.getInt(j);
            codes[i] = f;
        }
        float[] timbre = null;
        JSONArray ta = o.optJSONArray("timbre_ref");
        if (ta != null) {
            timbre = new float[ta.length()];
            for (int i = 0; i < ta.length(); i++) timbre[i] = (float) ta.optDouble(i, 0);
        }
        return new Voice(name, codes, timbre,
                o.optString("source", ""), o.optString("license", ""),
                o.optString("desc", ""), (float) o.optDouble("ref_f0_median", 0));
    }

    /** 手工装库(调用方自己遍历 assets/voicebank/)—— 保持极简,不在本类里耦合 Android API */
    public static CantoVoiceBank of(List<Voice> list) {
        Map<String, Voice> m = new LinkedHashMap<>();
        for (Voice v : list) m.put(v.name, v);
        String def = m.containsKey(DEFAULT_VOICE) ? DEFAULT_VOICE
                : (m.isEmpty() ? DEFAULT_VOICE : Collections.min(m.keySet()));
        return new CantoVoiceBank(m, def);
    }

    public Voice get(String name) { return voices.get(name); }
    public Voice defaultVoice() { return voices.get(defaultName); }
    public List<Voice> all() { return new ArrayList<>(voices.values()); }
    public List<String> names() { return new ArrayList<>(voices.keySet()); }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(8192);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return new String(bo.toByteArray(), "UTF-8");
    }
}
