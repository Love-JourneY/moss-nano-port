# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026  Nija (bubu12) and contributors
# moss-nano-port module —— 自有代码,AGPL-3.0-or-later(全文见 ./LICENSE;第三方见 ./NOTICE)
# ---------------------------------------------------------------------------
#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
tts_stream.py —— canto-tts 的【一次加载、多段合成】流式器 / 常驻守护(攻关原型)

## 为什么需要它(实测数据,2026-09-22)

现有的 `moss-nano-port synthesize` CLI 是【一次调用只合成一段】。而 `vsay-canto`
会按标点把文本切成多段,逐段各起一个**新进程**:

    while IFS= read -r line; do
      "$CT" synthesize "$line" -o "$seg" --checkpoint "$MODEL"   # ← 每段一个新进程
    done

`vsay-canto` 第 107 行的注释写着「首次调用要加载 ONNX(数秒);之后每段很快」——
**这条注释是错的**。每次 `moss-nano-port` 调用都是一个全新进程,必须把 8 个 ONNX
session 从磁盘重新加载一遍。实测(平板 chroot,8 核):

    单段独立进程          4512 ms
    紧接着再跑一次        4484 ms   ← 又付了一遍加载成本
    同进程内 2 段 拆解:   加载 3440ms + 第1段 950ms + 第2段 1085ms = 5475 ms
    vsay-canto(11 字,2 段)  9481 ms   ← 其中约 3.4s 是【纯冗余的重复加载】
    vsay-canto(3 段)     13939 ms

即:**一次单段调用里约 76% 的时间是在加载模型,不是在做推理。**
笔记本同样:独立进程 5087/4717 ms,同进程 2 段 6550 ms,省 3881 ms/段。

⇒ 所以「减繁重」的第一杠杆**不是** C++ 重写(实测 Python 胶水只占端到端
   推理耗时的 ~1%,ORT 占 99.2%),也**不是**量化(每帧仅 1.36x),
   也**不是**线程调优(SDK 默认 4 已是最优;线程越多越慢)——
   而是【别把已经加载好的模型丢掉】。

## 三种模式

| 模式 | 命令 | 适用 | 实测(平板) |
|---|---|---|---|
| **one-shot(默认,推荐)** | `tts_stream.py --outdir D` | 一次性播报;零额外内存 | 2 段 5833ms(旧 9240) |
| **serve(常驻)** | `tts_stream.py --serve --sock S` | 频繁播报;省掉冷启动 | 见下 |
| **client** | `tts_stream.py --client --sock S --outdir D` | 配合 serve | — |

常驻的代价:实测 RSS ≈ **1.46 GiB**(模型 729MB + ORT 工作区)。
平板 15.8GB 内存/可用 5.6GB ⇒ 可行,但不是免费。所以 **默认不常驻**;
需要极致延迟时再开 serve,并用 `--idle-timeout` 让它空闲自动退出、把内存还回去。

## 协议(stdout,逐行,均立即 flush)

    READY             模型已加载完毕
    SEG <n> <path>    第 n 段合成完成
    ERR <n> <msg>     第 n 段失败
    DONE              全部处理完

## 用法

    # 一次性(推荐)
    printf '%s\\n' "今日天氣幾好，" "我哋去食飯。" | tts_stream.py --outdir /tmp/x

    # 常驻(首次调用会慢,之后每段只花合成时间)
    tts_stream.py --serve --sock /run/user/1000/moss-nano-port.sock --idle-timeout 600 &
    printf '%s\\n' "..." | tts_stream.py --client --sock /run/user/1000/moss-nano-port.sock --outdir /tmp/x

## ⚠️ 线程
实测 ORT intra_op 线程数 **2~4 最优**;8/12 线程反而慢数倍
(batch=1 的 matvec 是内存带宽瓶颈,多线程互相踩)。SDK 默认 4 已经是最优,
`--threads` 只在你确认有更好的值时才用。
"""
from __future__ import annotations

import argparse
import json
import os
import socket
import sys
import time
from pathlib import Path


# ──────────────────────────────────────────────────────────────────────
# 模型装载(唯一需要"加载一次"的东西)
# ──────────────────────────────────────────────────────────────────────
def load_tts(checkpoint: str, threads: int | None):
    import canto_tts  # noqa: PLC0415

    kwargs = {}
    if threads is not None:
        kwargs["thread_count"] = int(threads)
    return canto_tts.CantoTTS(checkpoint=checkpoint, **kwargs)


# ──────────────────────────────────────────────────────────────────────
# 输出降采样(可配置)—— 2026-09-26 Nija 指示
# ──────────────────────────────────────────────────────────────────────
# Nija 原话:
#   「如果我们所有设备的TTS,全部把生成合成的音质稍微降一降。
#     比如说七六八零采样。这个感觉是个很高的音质,我们可以砍一半,ok?
#     因为…很多音乐平台上那种无损音质和普通MP3音质,我都听不太出来区别。」
#
# ⚠️ 它【不能】提速/省内存 —— canto-tts 模型【内部就是 48kHz 生成】的,
#   该算的还是算。它只省:① 磁盘/传输的字节数 ② 播放缓冲 ③ 音频文件大小。
#
# ⚠️ 降采样【不能】"每两个取一个"(直接抽取):
#   24kHz 的 Nyquist 是 12kHz,而原信号含到 24kHz 的成分
#   ⇒ 不滤波会【混叠失真】(高频折回,听起来发闷/沙)
#   ⇒ 正解:【先低通 FIR,再抽取】(scipy 有就用 scipy,没有就用自带的朴素 FIR)
#
# 环境变量:`CANTO_OUTPUT_RATE`(默认 24000 = 砍一半;48000 = 不降)

DEFAULT_OUT_RATE = 24000


def output_rate() -> int:
    """目标输出采样率。`CANTO_OUTPUT_RATE=0` 或 `48000` ⇒ 不降。"""
    try:
        v = int(os.environ.get("CANTO_OUTPUT_RATE", str(DEFAULT_OUT_RATE)))
    except Exception:
        return DEFAULT_OUT_RATE
    return v if v > 0 else DEFAULT_OUT_RATE


def downsample_wav(path: str, dst_rate: int) -> None:
    """把 wav 就地降采样到 dst_rate(先低通再抽取,避免混叠)。"""
    import wave
    import numpy as np  # noqa: PLC0415

    with wave.open(path, "rb") as w:
        nch, sw, src_rate, nframes = w.getnchannels(), w.getsampwidth(), w.getframerate(), w.getnframes()
        raw = w.readframes(nframes)
    if src_rate == dst_rate or dst_rate <= 0:
        return
    if sw != 2:
        return  # 只处理 16-bit
    a = np.frombuffer(raw, dtype="<i2").astype(np.float32)
    if nch > 1:
        a = a.reshape(-1, nch)

    factor = src_rate / dst_rate
    if abs(factor - round(factor)) < 1e-9 and round(factor) >= 2:
        # 整数倍:先低通(FIR)再抽取
        f = int(round(factor))
        # 31 抽头汉明窗低通,截止 = 0.45 × 目标 Nyquist(归一化到源 Nyquist)
        cutoff = 0.45 / f
        half = 15
        n = np.arange(-half, half + 1)
        with np.errstate(divide="ignore", invalid="ignore"):
            sinc = np.where(n == 0, 2 * cutoff, np.sin(2 * np.pi * cutoff * n) / (np.pi * n))
        win = 0.54 - 0.46 * np.cos(2 * np.pi * (n + half) / (2.0 * half))
        h = (sinc * win)
        h = h / h.sum()
        if nch > 1:
            flt = np.stack([np.convolve(a[:, c], h, mode="same") for c in range(nch)], axis=1)
        else:
            flt = np.convolve(a, h, mode="same")
        out = flt[::f]
    else:
        # 非整数倍:线性插值(比直接抽取好)
        n_out = int(len(a) * dst_rate / src_rate)
        idx = np.arange(n_out) * (src_rate / dst_rate)
        i0 = np.floor(idx).astype(np.int64)
        i1 = np.minimum(i0 + 1, len(a) - 1)
        frac = (idx - i0)[:, None] if nch > 1 else (idx - i0)
        out = a[i0] * (1 - frac) + a[i1] * frac

    out = np.clip(np.round(out), -32768, 32767).astype("<i2")
    with wave.open(path, "wb") as w:
        w.setnchannels(nch)
        w.setsampwidth(2)
        w.setframerate(dst_rate)
        w.writeframes(out.tobytes())


def synth_one(tts, text: str, out: str) -> None:
    tts.synthesize(text, out)
    if not os.path.exists(out) or os.path.getsize(out) == 0:
        raise RuntimeError("输出为空")
    # ★ 输出降采样(见文件上方 downsample_wav 的注释;CANTO_OUTPUT_RATE 控制)
    try:
        r = output_rate()
        if r and r != 48000:
            downsample_wav(out, r)
    except Exception as e:  # 降采样失败【不许静默】—— 打日志但保留原音频
        print(f"[tts_stream] 降采样失败(保留 48kHz): {e}", file=sys.stderr, flush=True)


# ──────────────────────────────────────────────────────────────────────
# 模式 1:一次性(默认)—— 加载一次,合成 N 段,退出
# ──────────────────────────────────────────────────────────────────────
def run_oneshot(args, segments: list[str]) -> int:
    os.makedirs(args.outdir, exist_ok=True)
    t0 = time.perf_counter()
    tts = load_tts(args.checkpoint, args.threads)
    if args.timing:
        print(f"[tts_stream] 模型加载 {(time.perf_counter()-t0)*1000:.0f} ms", file=sys.stderr, flush=True)
    print("READY", flush=True)
    rc = 0
    for i, seg in enumerate(segments, 1):
        out = os.path.join(args.outdir, f"seg-{i}.wav")
        t1 = time.perf_counter()
        try:
            synth_one(tts, seg, out)
        except Exception as exc:
            print(f"ERR {i} {type(exc).__name__}: {exc}", flush=True)
            rc = 1
            continue
        if args.timing:
            print(f"[tts_stream] 第{i}段 {seg[:12]!r} 合成 {(time.perf_counter()-t1)*1000:.0f} ms",
                  file=sys.stderr, flush=True)
        print(f"SEG {i} {out}", flush=True)
    print("DONE", flush=True)
    return rc


# ──────────────────────────────────────────────────────────────────────
# 模式 2:常驻守护 —— 模型永不卸载,空闲超时自动退出还内存
# ──────────────────────────────────────────────────────────────────────
def handle_conn(conn: socket.socket, tts, default_voice_codes=None) -> None:
    """一个连接 = 一批段。协议:每行一个 JSON 请求,每请求回一行 JSON。

    `default_voice_codes` = 进程启动时的内置音色(男声)。每个"没带 voice_codes"
    的请求都要复位到它 —— 否则上一个女声请求的音色会串到后面的男声请求上
    (2026-09-22 实测踩过,见下方注释)。
    """
    f = conn.makefile("rwb")
    n = 0
    for raw in f:
        raw = raw.strip()
        if not raw:
            continue
        try:
            req = json.loads(raw)
        except Exception as exc:
            f.write((json.dumps({"ok": False, "err": f"bad json: {exc}"}) + "\n").encode())
            f.flush()
            continue
        n += 1
        idx = req.get("n", n)
        outdir = req.get("outdir") or "/tmp"
        os.makedirs(outdir, exist_ok=True)
        out = os.path.join(outdir, f"seg-{idx}.wav")
        try:
            # ★ 2026-09-22 晚 加:每请求可换【音色】与【随机种子】。
            #   为什么:_default_voice_codes 在每次 synthesize() 里都会被重新读取
            #   (onnx_backend.py:181),所以**一个守护能同时服务男声与女声** ——
            #   女声(vsay-canto-female)因此不必再起第二个守护(那要多占 ~1.5GiB,
            #   平板可用内存只有 5.6GB,受不了)。
            #   seed 是给女声那条路复现用的(它按段重置 rng 以便 A/B 对比)。
            #
            #   ⚠️⚠️ 关键:未指定 voice_codes 时必须【复位成内置默认(男声)】!
            #   第一版忘了复位,实测立刻串味:女声请求之后再发一次男声请求
            #   (vsay-canto 不带 voice_codes),出来的竟是**女声**(F0 218Hz)。
            #   守护是长生命周期进程,任何"上一次留下的状态"都是跨请求污染源。
            _be = getattr(tts, "_backend", tts)
            if req.get("voice_codes") is not None:
                # ⚠️ CantoTTS 是外壳,真正持有音色的是它内部的 OnnxBackend
                #    (tts._backend)。用 getattr 兜底,免得将来 SDK 改结构就炸。
                _be._default_voice_codes = req["voice_codes"]
            else:
                _be._default_voice_codes = default_voice_codes
            if req.get("seed") is not None:
                import numpy as np  # noqa: PLC0415
                _be._runtime.rng = np.random.default_rng(int(req["seed"]))
            synth_one(tts, req["text"], out)
            f.write((json.dumps({"ok": True, "n": idx, "path": out}) + "\n").encode())
        except Exception as exc:
            f.write((json.dumps({"ok": False, "n": idx,
                                 "err": f"{type(exc).__name__}: {exc}"}) + "\n").encode())
        f.flush()
    f.close()


def _someone_listening(path: str) -> bool:
    """探测 <path> 上是否【已有守护在 accept】—— 真连一次,不是看文件存不存在。

    ⚠️ 2026-09-22 晚 加(并发双启 + L1 静默失效的根因):
    旧版 run_serve 直接 `unlink()` 再 `bind()`。两个守护并发启动时,后起的会把
    先起的**插座摘掉**:先起的变成占着 ~1.5GiB 的孤儿,而它的 socket 名字仍留在
    /proc/net/unix 里 ⇒ `ss -x` 看着像在监听、`[ -S 文件 ]` 也成立,**实际连不上**
    (ECONNREFUSED)。调用方因此每次都掉进 L2 整模型重载(实测 5.1s,而 L1 只要 2~3s)。
    修法:bind 之前先真的连一次 —— 连得上就说明已有人在服务,本进程直接退出:
    不抢插座、不制造孤儿、不白烧 1.5GiB。
    """
    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    s.settimeout(0.5)
    try:
        s.connect(path)
        return True
    except OSError:
        return False
    finally:
        try:
            s.close()
        except Exception:
            pass


def run_serve(args) -> int:
    sock_path = Path(args.sock)
    sock_path.parent.mkdir(parents=True, exist_ok=True)
    # ★ 双启防护第一道:还没加载模型就先探一次 —— 已有守护就【立刻】退出,
    #   省掉 3.5s 加载 和 1.5GiB 内存(这是并发双启最常见的浪费)。
    if _someone_listening(str(sock_path)):
        print(f"[tts_stream] {sock_path} 已有守护在服务 ⇒ 本进程退出(不抢插座)",
              file=sys.stderr, flush=True)
        return 0

    t0 = time.perf_counter()
    tts = load_tts(args.checkpoint, args.threads)
    # ★ 记下内置音色(男声)—— 每请求复位用,防跨请求串味(见 handle_conn 注释)
    _be0 = getattr(tts, "_backend", tts)
    default_voice_codes = getattr(_be0, "_default_voice_codes", None)
    if args.timing:
        print(f"[tts_stream] 模型加载 {(time.perf_counter()-t0)*1000:.0f} ms", file=sys.stderr, flush=True)
    # ★ 双启防护第二道:加载期间可能有人先 bind 上了(缩小 TOCTOU 窗口)。
    if _someone_listening(str(sock_path)):
        print(f"[tts_stream] {sock_path} 已有守护在服务 ⇒ 本进程退出(不抢插座)",
              file=sys.stderr, flush=True)
        return 0
    if sock_path.exists():
        # 到这儿说明文件是【僵尸】—— 连不上,清掉再 bind。
        # (正常并发由调用方 vsay-canto 的 flock 串行化;这里是直连启动的兜底。)
        try:
            sock_path.unlink()
        except FileNotFoundError:
            pass
    srv = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    srv.bind(str(sock_path))
    os.chmod(sock_path, 0o600)      # 只给本人用
    srv.listen(4)
    srv.settimeout(float(args.idle_timeout) if args.idle_timeout else None)
    print("READY", flush=True)
    try:
        while True:
            try:
                conn, _ = srv.accept()
            except socket.timeout:
                if args.timing:
                    print(f"[tts_stream] 空闲 {args.idle_timeout}s,退出并释放内存",
                          file=sys.stderr, flush=True)
                break
            with conn:
                handle_conn(conn, tts, default_voice_codes)
    finally:
        srv.close()
        try:
            sock_path.unlink()
        except FileNotFoundError:
            pass
    return 0


# ──────────────────────────────────────────────────────────────────────
# 模式 3:客户端 —— 把 stdin 的段转发给常驻守护
# ──────────────────────────────────────────────────────────────────────
def run_client(args) -> int:
    segments = [ln.strip() for ln in sys.stdin if ln.strip()]
    if not segments:
        print("DONE", flush=True)
        return 0
    os.makedirs(args.outdir, exist_ok=True)
    # ★ 2026-09-22 晚 加:--voice-json 让客户端指定音色档案(女声那条路用)。
    #   档案格式即 voicebank/<名>.json,取其中的 prompt_audio_codes。
    voice_codes = None
    if getattr(args, "voice_json", None):
        with open(args.voice_json, encoding="utf-8") as fh:
            voice_codes = json.load(fh).get("prompt_audio_codes")
    seed_base = getattr(args, "seed_base", None)
    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    try:
        s.connect(args.sock)
    except OSError as exc:
        # 守护不在 ⇒ 如实报错,由调用方回退到一次性模式(不要静默失败)
        print(f"ERR 0 无法连接常驻守护 {args.sock}: {exc}", flush=True)
        return 2
    print("READY", flush=True)
    f = s.makefile("rwb")
    rc = 0
    for i, seg in enumerate(segments, 1):
        req = {"n": i, "text": seg, "outdir": args.outdir}
        if voice_codes is not None:
            req["voice_codes"] = voice_codes
        if seed_base is not None:
            req["seed"] = int(seed_base) + i
        f.write((json.dumps(req) + "\n").encode())
        f.flush()
        line = f.readline()
        if not line:
            print(f"ERR {i} 守护断开", flush=True)
            return 2
        r = json.loads(line)
        if r.get("ok"):
            print(f"SEG {r['n']} {r['path']}", flush=True)
        else:
            print(f"ERR {r.get('n', i)} {r.get('err', '?')}", flush=True)
            rc = 1
    f.close()
    s.close()
    print("DONE", flush=True)
    return rc


# ──────────────────────────────────────────────────────────────────────
def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="moss-nano-port 一次加载多段合成(流式输出路径)")
    ap.add_argument("--checkpoint", default=os.environ.get("VSAY_CANTO_MODEL", "/var/lib/moss-nano-port/model"),
                    help="模型目录(默认 $VSAY_CANTO_MODEL 或 /var/lib/moss-nano-port/model)")
    ap.add_argument("--outdir", help="输出目录(每段一个 seg-<n>.wav)")
    ap.add_argument("--threads", type=int, default=None,
                    help="ORT intra_op 线程数。⚠️实测 2~4 最优,越多越慢。不传则用 SDK 默认 4。")
    ap.add_argument("--timing", action="store_true", help="每段耗时打到 stderr")
    ap.add_argument("--serve", action="store_true", help="常驻守护模式")
    ap.add_argument("--client", action="store_true", help="客户端模式(转发给常驻守护)")
    ap.add_argument("--sock", default="/run/user/%d/moss-nano-port.sock" % os.getuid(),
                    help="Unix socket 路径(serve/client 用)")
    ap.add_argument("--idle-timeout", type=float, default=0.0,
                    help="守护空闲多少秒后自动退出(0=永不退出);退出会把 1.4GiB 内存还回去")
    # ★ 2026-09-22 晚 加(女声复用同一守护用):
    ap.add_argument("--voice-json", default=None,
                    help="音色档案 JSON(voicebank/<名>.json);取 prompt_audio_codes 作为音色。"
                         "不传 = 内置男声。⚠️ 只影响 --client。")
    ap.add_argument("--seed-base", type=int, default=None,
                    help="每段随机种子 = seed_base + 段号(供 A/B 复现);不传 = 引擎默认")
    ap.add_argument("segments", nargs="*", help="段文本;留空则从 stdin 读(每行一段)")
    args = ap.parse_args(argv)

    if args.serve:
        return run_serve(args)
    if args.client:
        if not args.outdir:
            ap.error("--client 需要 --outdir")
        return run_client(args)
    if not args.outdir:
        ap.error("需要 --outdir")
    segments = list(args.segments) if args.segments else [ln.strip() for ln in sys.stdin if ln.strip()]
    if not segments:
        print("DONE", flush=True)
        return 0
    return run_oneshot(args, segments)


if __name__ == "__main__":
    sys.exit(main())
