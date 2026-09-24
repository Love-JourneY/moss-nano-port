#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
exp-fp16-memory.py —— 实验:fp16 权重在 ORT CPU EP 上【到底省不省内存】?

⚠️ 为什么有这个脚本(2026-09-24):
   我们想让 683MB 的 moss-nano-port 模型在 Android 上常驻,考虑过 fp16 量化。
   **结论:无效,而且有害** —— 但这个结论必须能被别人【复现】,不能只写"我们试过了"。

结论(实测):
   【fp32】文件 288.0 MB → RssAnon 增量 +73.6 MB
   【fp16】文件 144.0 MB → RssAnon 增量 +165.9 MB   ← 反而多 92 MB

根因:
   ORT 的 CPUExecutionProvider 加载 fp16 权重时【转换成 fp32 计算】
   ⇒ fp16 原始权重 + fp32 转换副本【两套并存】⇒ 内存不降反升

⚠️ 注意:ORT 1.30 的 CPU EP 【确实原生支持 fp16 MatMul】(输出 dtype=float16,能跑),
   所以这不是"不支持 fp16"的问题,而是"**加载时会转 fp32**"的问题。
   两个说法差的正是这个实验的价值。

用法:
   python3 exp-fp16-memory.py            # 需要 onnx + onnxruntime
   # 若 onnx 装不上(权限):pip install --target /tmp/onnxlib onnx
   #                        PYTHONPATH=/tmp/onnxlib python3 exp-fp16-memory.py
"""
import os, sys, tempfile
import numpy as np
import onnx
from onnx import helper, TensorProto, numpy_helper
import onnxruntime as ort

N = 3072          # 权重维度(3072² × 4B ≈ 36MB/层)
LAYERS = 8        # 层数 ⇒ fp32 文件 ~288MB / fp16 ~144MB


def mem():
    """返回 (RssAnon, RssFile, VmRSS) 单位 MB —— 用 RssAnon 才干净(mmap 文件页会污染 RSS)"""
    s = open("/proc/self/status").read()
    g = lambda k: int(s.split(k + ":")[1].split()[0]) / 1024
    return g("RssAnon"), g("RssFile"), g("VmRSS")


def make(path, is16):
    T = TensorProto.FLOAT16 if is16 else TensorProto.FLOAT
    dt = np.float16 if is16 else np.float32
    W = np.random.randn(N, N).astype(dt)
    inits, nodes, cur = [], [], "X"
    X = helper.make_tensor_value_info("X", T, [1, N])
    for i in range(LAYERS):
        inits.append(numpy_helper.from_array(W.T if i == 0 else W, f"W{i}"))
        nodes.append(helper.make_node("MatMul", [cur, f"W{i}"], [f"Y{i}"]))
        cur = f"Y{i}"
    Y = helper.make_tensor_value_info(cur, T, [1, N])
    g = helper.make_graph(nodes, "g", [X], [Y], inits)
    m = helper.make_model(g, opset_imports=[helper.make_opsetid("", 17)])
    m.ir_version = 10          # ⚠️ 不设这个,ORT 会拒绝加载(踩过)
    onnx.checker.check_model(m)
    onnx.save(m, path)
    return os.path.getsize(path)


def main():
    print(f"ORT {ort.__version__} · providers={ort.get_available_providers()}")
    with tempfile.TemporaryDirectory() as d:
        for name, is16, dt in [("fp32", False, np.float32), ("fp16", True, np.float16)]:
            p = os.path.join(d, f"m_{name}.onnx")
            sz = make(p, is16)
            a0, f0, r0 = mem()
            so = ort.SessionOptions()
            so.log_severity_level = 3
            sess = ort.InferenceSession(p, so, providers=["CPUExecutionProvider"])
            a1, f1, r1 = mem()
            y = sess.run(None, {"X": np.random.randn(1, N).astype(dt)})[0]
            a2, f2, r2 = mem()
            print(f"\n【{name}】文件 {sz/1048576:6.1f} MB · 输出 dtype={y.dtype}")
            print(f"   加载: Anon +{a1-a0:6.1f}  File +{f1-f0:6.1f}  合计 +{(a1-a0)+(f1-f0):6.1f} MB")
            print(f"   推理: Anon +{a2-a1:6.1f}  File +{f2-f1:6.1f}")
            print(f"   终态: Anon {a2:6.1f}  File {f2:6.1f}  总 {r2:6.1f} MB")
            del sess
    print("""
判读:
  fp16 的 Anon 增量【≥ fp32】⇒ CPU EP 转 fp32 算 ⇒ 两套并存 ⇒ fp16 不可用于省内存
  fp16 的 Anon 增量 ≈ fp32/2 ⇒ 真省内存 ⇒ 可用
⚠️ 我们实测是第一种。""")
    return 0


if __name__ == "__main__":
    sys.exit(main())
