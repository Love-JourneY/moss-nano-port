#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026  Nija (bubu12) and contributors
# canto-tts module —— 自有代码,AGPL-3.0-or-later(全文见 ./LICENSE;第三方见 ./NOTICE)
# ---------------------------------------------------------------------------
# -*- coding: utf-8 -*-
"""cer_eval.py —— 用【粤语 ASR】客观量 canto-tts 的 CER,并产出人耳 A/B 音频

为什么需要它(以及它【量不出】什么):
    · "听起来地道"这句话不可证伪 ⇒ 必须有一把客观尺子。
      尺子 = `alvanlii/whisper-small-cantonese`(与 canto-tts 官方评测同一把)。
    · ⚠️ 但 CER 量的是【字对不对】,**量不出"地道不地道"**。
      普→粤转换之后,CER 只可能"不变差"或"变差"——
      因为参考文本也跟着变了。所以:
        CER 是【不能变差的底线】,不是"更地道"的证明。
        "更地道"只能靠人耳 A/B(本脚本把 A/B 音频一并产出)。

用法(必须用带 torch 的 venv):
    /opt/speech-to-speech-venv/bin/python tools/cer_eval.py            # 默认 8 句
    ... --n 12                 # 多跑几句
    ... --outdir /tmp/ab       # 指定输出目录(A/B 音频落这里)
    ... --no-asr               # 只合成、不算 CER(只想拿音频时用)

判据(硬):
    · 同一段文本重复合成 **必须逐字节相同**(已实测:ONNX 贪心解码,无采样)
      ⇒ A/B 无需固定 seed —— 复跑得到的就是同一份音频。
    · 若 CER(p2y 开) > CER(p2y 关) 超过 3 个百分点 ⇒ 报警(转换把字念坏了)。
"""
import argparse
import os
import re
import subprocess
import sys
import tempfile
import wave

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

CANTO = "/opt/canto-tts-venv/bin/canto-tts"
MODEL = "/var/lib/canto-tts/model"
P2Y = os.path.join(os.path.dirname(ROOT), "voice-tts", "backend", "p2y.py")
ASR_MODEL = "alvanlii/whisper-small-cantonese"

# ══════════════════════════════════════════════════════════════════════════
# 测试句 —— 刻意选【DSH 回复】的典型文体:普通话书面文,带虚词/连接词/正式词
# ══════════════════════════════════════════════════════════════════════════
SENTENCES = [
    "谢了,这个功能已经完成了。",
    "我看了一下,发现这里有一个问题。",
    "如果你有时间的话,我们可以一起讨论一下解决方案。",
    "他的目的是什么?我们不知道。",
    "但是我不想去,因为太贵了。",
    "明天早上八点我们在公司门口见面。",
    "他站在那里,看着窗外的风景。",
    "谢谢你的帮助,对不起我迟到了。",
    "这件事很重要,所以我们必须马上开始。",
    "虽然很难,但是可以做到。",
    "我不知道为什么他会这样做。",
    "请问一下,这里是什么地方?",
]


def p2y(text):
    if not os.path.exists(P2Y):
        return text, False
    r = subprocess.run(["python3", P2Y], input=text, capture_output=True, text=True, timeout=30)
    out = (r.stdout or "").rstrip("\n")
    return (out or text), bool(out.strip())


def synth(text, out_wav):
    """用 canto-tts 合成。返回 True/False。"""
    for attempt in (1, 2):
        r = subprocess.run([CANTO, "synthesize", text, "-o", out_wav, "--checkpoint", MODEL],
                           capture_output=True, text=True, timeout=300)
        if r.returncode == 0 and os.path.exists(out_wav) and os.path.getsize(out_wav) > 1000:
            return True
        sys.stderr.write(f"  合成失败(第 {attempt} 次)rc={r.returncode} {r.stderr[-200:]}\n")
    return False


# ── CER:先统一字形,再算编辑距离 ────────────────────────────────────────
# ⚠️ 必须统一简繁,否则「饭≠飯」会被算成错字 —— 那是字形差异,不是读错。
#    这是本脚本最容易出错、也最容易被误读的一处。
_PUNCT = re.compile(r"[\s,。，、.!?！？;:：；'\"“”‘’()（）\[\]【】\-—…]")
_cc = None


def norm_script(s):
    global _cc
    if _cc is None:
        try:
            import opencc
            _cc = opencc.OpenCC("t2s")     # 两边都归到【简体】再比
        except Exception:
            _cc = False
    if _cc:
        s = _cc.convert(s)
    return s


def norm(s):
    return _PUNCT.sub("", norm_script(s or ""))


def cer(ref, hyp):
    ref, hyp = norm(ref), norm(hyp)
    if not ref:
        return 0.0, 0, 0
    # 经典 Levenshtein(字级)
    prev = list(range(len(hyp) + 1))
    for i, rc in enumerate(ref, 1):
        cur = [i] + [0] * len(hyp)
        for j, hc in enumerate(hyp, 1):
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (rc != hc))
        prev = cur
    return prev[-1] / len(ref), prev[-1], len(ref)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=8, help="跑前 N 句")
    ap.add_argument("--outdir", default="/tmp/p2y-ab")
    ap.add_argument("--no-asr", action="store_true")
    a = ap.parse_args()

    outdir = a.outdir
    os.makedirs(outdir, exist_ok=True)
    sents = SENTENCES[:a.n]

    asr = None
    if not a.no_asr:
        os.environ.setdefault("HF_HUB_OFFLINE", "1")
        os.environ.setdefault("TRANSFORMERS_OFFLINE", "1")
        from transformers import pipeline
        print(f"加载 ASR:{ASR_MODEL} …", flush=True)
        asr = pipeline("automatic-speech-recognition", model=ASR_MODEL, device=-1)
        print("ASR 就绪\n", flush=True)

    rows = []
    print(f"{'#':>2}  {'CER关':>7} {'CER开':>7}  参考(p2y 开)")
    print("─" * 90)
    for i, s in enumerate(sents, 1):
        conv, ok = p2y(s)
        wa = os.path.join(outdir, f"s{i:02d}_A_关p2y.wav")
        wb = os.path.join(outdir, f"s{i:02d}_B_开p2y.wav")
        if not synth(s, wa):
            print(f"{i:2d}  (合成失败 A)")
            continue
        if not synth(conv, wb):
            print(f"{i:2d}  (合成失败 B)")
            continue
        if a.no_asr:
            rows.append((i, None, None, conv)); continue
        ha = asr(wa)["text"]
        hb = asr(wb)["text"]
        ca, _, _ = cer(s, ha)
        cb, _, _ = cer(conv, hb)
        rows.append((i, ca, cb, conv, ha, hb))
        print(f"{i:2d}  {ca*100:6.1f}% {cb*100:6.1f}%  {conv}")
        if os.environ.get("VERBOSE"):
            print(f"      ASR-关:{ha}")
            print(f"      ASR-开:{hb}")

    print("─" * 90)
    if not a.no_asr and rows and rows[0][1] is not None:
        ca = sum(r[1] for r in rows) / len(rows)
        cb = sum(r[2] for r in rows) / len(rows)
        print(f"平均 CER  关 p2y = {ca*100:.1f}%   开 p2y = {cb*100:.1f}%   "
              f"Δ = {(cb-ca)*100:+.1f} 个百分点")
        if (cb - ca) > 0.03:
            print("⚠️ 开 p2y 之后 CER 明显变差(>3pt)⇒ 转换把字念坏了,要查过改!")
        else:
            print("✅ CER 未变差(底线通过)—— 注意:这只说明【字没念错】,")
            print("   【不】证明「更地道」;地道与否请看 A/B 音频,用人耳判。")
    print(f"\nA/B 音频(同一文本、同一模型、逐字节可复现):\n  {outdir}/sNN_A_关p2y.wav  vs  sNN_B_开p2y.wav")
    return 0


if __name__ == "__main__":
    sys.exit(main())
