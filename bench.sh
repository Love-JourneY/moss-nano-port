#!/bin/bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026  Nija (bubu12) and contributors
# moss-nano-port module —— 自有代码,AGPL-3.0-or-later(全文见 ./LICENSE;第三方见 ./NOTICE)
# ---------------------------------------------------------------------------
# bench.sh —— vsay-canto 改前/改后耗时对比(可复跑)
#
# 为什么这么写:
#   · 机器负载会漂(实测同一配置 3.0s~8.3s),所以【单次数字没意义】——
#     必须 5 次取中位数,而且【旧版新版在同一时间窗内交替跑】,才可比。
#   · 旧版 = backups/vsay-canto.bak-20260922(改前的原版,绝对路径自洽,可直接跑)。
#   · 统一 -n(不播),只量【合成】;播放耗时另计(见 NOTES)。
set -uo pipefail
export XDG_RUNTIME_DIR=/run/user/1000 PULSE_SERVER=unix:/run/user/1000/pulse/native

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OLD="$HERE/backups/vsay-canto.bak-20260922"
NEW="$HERE/bin/vsay-canto"
PY=/opt/canto-tts-venv/bin/python
TS="$HERE/lib/tts_stream.py"
SOCK=/run/user/1000/moss-nano-port.sock
N=${N:-5}

T2='今日天氣幾好，我哋去食飯。'
T3='今日天氣幾好，我哋去食飯。你覺得點樣？'

killd() { local p; for p in $(pgrep -f "[t]ts_stream.py --serve" 2>/dev/null); do kill "$p" 2>/dev/null; done; sleep 1; rm -f "$SOCK"*; }

ms() { date +%s%3N; }
median() { printf '%s\n' "$@" | sort -n | awk '{a[NR]=$1} END{print a[int((NR+1)/2)]}'; }
minof()  { printf '%s\n' "$@" | sort -n | head -1; }

run_series() {   # $1=标签 $2=文本 $3..=命令
  local label="$1" text="$2"; shift 2
  local -a t=()
  local i a b
  for i in $(seq 1 "$N"); do
    a=$(ms); "$@" -n "$text" >/dev/null 2>&1; b=$(ms)
    t+=( $((b-a)) )
  done
  printf '%-28s 中位 %6d ms   最快 %6d ms   %s\n' "$label" "$(median "${t[@]}")" "$(minof "${t[@]}")" "${t[*]}"
}

echo "======== vsay-canto 改前/改后 耗时对比(每项 ${N} 次,--no-play)========"
echo "文本 A(2 段 11 字):$T2"
echo "文本 B(3 段 17 字):$T3"
echo

echo "───── 文本 A(2 段)─────"
killd
run_series "① 旧版(L3·每段重载)" "$T2" "$OLD"
killd
run_series "② 新版 L1 warm(常驻)" "$T2" "$NEW"
killd
run_series "③ 新版 L2(一次性)"   "$T2" env VSAY_CANTO_DAEMON=0 "$NEW"
echo

echo "───── 文本 B(3 段)─────"
killd
run_series "① 旧版(L3·每段重载)" "$T3" "$OLD"
killd
run_series "② 新版 L1 warm(常驻)" "$T3" "$NEW"
killd
run_series "③ 新版 L2(一次性)"   "$T3" env VSAY_CANTO_DAEMON=0 "$NEW"
echo

echo "───── 新版 L1 冷启(含守护加载,只测 1 次 × 2)─────"
for i in 1 2; do
  killd
  a=$(ms); "$NEW" -n "$T2" >/dev/null 2>&1; b=$(ms)
  echo "  冷启第 $i 次(2 段):$((b-a)) ms"
done
killd
echo
echo "（守护 RSS ≈ 1.6 GiB;--idle-timeout 默认 300s,空闲自动退出还内存）"
