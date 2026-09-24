#!/usr/bin/env python3
"""生成 CantoRngTable.java —— 与参考实现(SDK)逐位一致的随机数表。

为什么:(用户反馈"后面叽里咕噜"的根因)
  SDK 用 np.random.default_rng(1234)(NumPy PCG64);
  Android 侧原来用 java.util.Random ⇒ 序列完全不同
  ⇒ 采样走向不同分支 ⇒ 模型的 should_continue 永不为 0
  ⇒ 一直生成到上限 ⇒ 多出的帧是噪声。

用法:CANTO_VENV=/opt/moss-nano-port-venv ./gen-rng-table.py
"""
import os, subprocess, sys, pathlib

N = int(os.environ.get("N", "4000"))
OUT = pathlib.Path(__file__).resolve().parent.parent / "assets/android-engine/CantoRngTable.java"

code = f"""
import numpy as np
rng = np.random.default_rng(1234)
vals = [min(0.99999994, max(0.0, float(rng.random()))) for _ in range({N})]
out = []
per = 6
for i in range(0, len(vals), per):
    out.append("        " + ", ".join(f"{{v:.9f}}f" for v in vals[i:i+per]) + ",")
print("\n".join(out))
"""
venv = os.environ.get("CANTO_VENV", "/opt/moss-nano-port-venv")
py = os.path.join(venv, "bin", "python")
body = subprocess.run([py, "-c", code], capture_output=True, text=True, check=True).stdout.rstrip()
hdr = OUT.read_text(encoding="utf-8").split("    public static final float[] V = {")[0] if OUT.exists() else ""
if not hdr:
    print("先跑一次生成完整文件(本脚本只刷新数值表)", file=sys.stderr); sys.exit(1)
OUT.write_text(hdr + "    public static final float[] V = {\n" + body + "\n    };\n}\n", encoding="utf-8")
print(f"OK 刷新 {OUT}")
