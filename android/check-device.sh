#!/bin/bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026  Nija (bubu12) and contributors
#
# check-device.sh —— 【这台 Android 能不能跑 canto-tts 进程内推理?】
#
# ⚠️ 为什么有这个脚本(2026-09-24 实测教训):
#   我们在格仔(平板)上把整条技术链【全部打通】了:
#     系统注册 → 系统绑定 → 服务启动 → 引擎就绪 → G2P → ORT 加载 → 系统调合成
#   但【出不了声】—— 因为 683MB 模型被换出到 zram,进程被 ColorOS 的
#   virtualFreeze 冻住,合成永远完不成。
#
#   ⇒ 这不是代码问题,是【设备内存策略】问题。
#   ⇒ 所以【换设备前先用这个脚本量一量】,别又白干一遍。
#
# 用法:
#   ./check-device.sh                      # 用 $ANDROID_SERIAL 或 device.mac 解析
#   ./check-device.sh --target <adb>
#
# 判据(全部来自实测,不是拍脑袋):
#   · 模型常驻需要 ~1.2GB 堆(683MB 权重 + ORT 运行时 + KV cache)
#   · 要让内核【不】把它换出:MemAvailable 应 > 2.5GB 且 zram 压力小
#   · zram 已用 > 6GB ⇒ 系统整体在压力下 ⇒ 该模型【大概率被换出】
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET="${ANDROID_SERIAL:-}"; [ "${1:-}" = "--target" ] && TARGET="${2:-}"
# 设备解析:① 显式 --target ② device.mac + ARP ③ adb devices 里唯一一个在线设备
if [ -z "$TARGET" ] && [ -f "$HERE/device.mac" ]; then
  MAC="$(cat "$HERE/device.mac")"
  IP="$(ip neigh show 2>/dev/null | awk -v m="$MAC" 'tolower($3)==tolower(m){print $1; exit}')"
  [ -n "$IP" ] && TARGET="$IP:5555"
fi
if [ -z "$TARGET" ]; then
  # 兜底:adb devices 里【恰好一个】在线设备就用它(多设备时必须显式指定)
  N=$(adb devices 2>/dev/null | awk 'NR>1 && $2=="device"' | wc -l)
  if [ "$N" = "1" ]; then
    TARGET="$(adb devices 2>/dev/null | awk 'NR>1 && $2=="device"{print $1; exit}')"
    echo "  (device.mac 没解析到,从 adb devices 取到唯一设备: $TARGET)"
  fi
fi
[ -n "$TARGET" ] || { echo "用法: $0 --target <adb>   (或写 device.mac / 只连一台设备)"; exit 2; }
ADB="adb -s $TARGET"
say(){ printf '  %s\n' "$*"; }
VERDICT=0; REASONS=()

echo "════ canto-tts 引擎 · 设备能力检测 ════"
echo "  设备: $TARGET"
$ADB get-state >/dev/null 2>&1 || { echo "  ❌ 设备不在线"; exit 1; }

# ── ① 基础信息 ──
ABI=$($ADB shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r')
SDK=$($ADB shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')
REL=$($ADB shell getprop ro.build.version.release 2>/dev/null | tr -d '\r')
BRAND=$($ADB shell getprop ro.product.brand 2>/dev/null | tr -d '\r')
MODEL=$($ADB shell getprop ro.product.model 2>/dev/null | tr -d '\r')
say "设备: $BRAND $MODEL · Android $REL (SDK $SDK) · ABI $ABI"
[ "$ABI" = "arm64-v8a" ] || { VERDICT=1; REASONS+=("ABI 不是 arm64-v8a(我们只打包了 $ABI=arm64-v8a)"); }
[ "${SDK:-0}" -ge 24 ] || { VERDICT=1; REASONS+=("SDK $SDK < 24(APK 要求 ≥24)"); }

# ── ② 内存(这是决定性的一条)──
read -r TOTAL FREE AVAIL <<<"$($ADB shell "grep -E 'MemTotal|MemFree|MemAvailable' /proc/meminfo" 2>/dev/null | tr -d '\r' | awk '{printf "%s ", $2}')"
SWTOT=$($ADB shell "grep SwapTotal /proc/meminfo 2>/dev/null" | tr -d '\r' | awk '{print $2}')
SWFREE=$($ADB shell "grep SwapFree /proc/meminfo 2>/dev/null" | tr -d '\r' | awk '{print $2}')
tom(){ awk -v k="${1:-0}" 'BEGIN{printf "%.2f", k/1048576}'; }
say ""
say "内存: 总 $(tom "$TOTAL") GB · 空闲 $(tom "$FREE") GB · 【可用 $(tom "$AVAIL") GB】"
[ -n "${SWTOT:-}" ] && say "swap: 总 $(tom "$SWTOT") GB · 空闲 $(tom "$SWFREE") GB · 【已用 $(awk -v a="$SWTOT" -v b="$SWFREE" 'BEGIN{printf "%.2f",(a-b)/1048576}') GB】"

# 判据:
AVAIL_GB=$(awk -v k="${AVAIL:-0}" 'BEGIN{printf "%.2f", k/1048576}')
if awk -v a="$AVAIL_GB" 'BEGIN{exit !(a < 2.5)}'; then
  VERDICT=1
  REASONS+=("MemAvailable 只有 ${AVAIL_GB}GB — 模型常驻需 ~1.2GB,这会让内核把它换出 ⇒ 必被冻结")
fi
if [ -n "${SWTOT:-}" ]; then
  SWUSED=$(awk -v a="$SWTOT" -v b="${SWFREE:-0}" 'BEGIN{printf "%.2f",(a-b)/1048576}')
  if awk -v u="$SWUSED" 'BEGIN{exit !(u > 6)}'; then
    VERDICT=1
    REASONS+=("zram 已用 ${SWUSED}GB — 系统整体压力大 ⇒ 我们的常驻模型会被当"可回收"换出")
  fi
fi

# ── ③ 厂商冻结策略(实测最阴的一条)──
say ""
say "厂商冻结相关(2026-09-24 实测:ColorOS 会对【系统 bind 的 TTS 服务】做 virtualFreeze):"
OO=$($ADB shell "dumpsys activity processes 2>/dev/null | grep -c 'virtualFreeze'" 2>/dev/null | tr -d '\r')
say "  dumpsys 里出现 virtualFreeze 的次数: ${OO:-0}"
if [ "${OO:-0}" -gt 0 ]; then
  say "  ⚠️ 这台设备有 virtualFreeze 机制(ColorOS/OPPO 系特征)"
  say "     ⇒ 实测:它会冻结"不活跃"进程,即使该进程被 system_server 绑定"
  say "     ⇒ 我们的合成要 100+ 秒,期间进程被判"不活跃" ⇒ 被冻 ⇒ 永远完不成"
  REASONS+=("有 virtualFreeze(合成期间会被冻结)")
  VERDICT=1
fi

# ── ④ 实测:真装一遍并合成?交给 verify.sh ──
say ""
echo "════ 结论 ════"
if [ "$VERDICT" = 0 ]; then
  echo "  ✅ 这台设备【看起来能跑】"
  echo "     下一步: ./install.sh && ./verify.sh --target $TARGET"
else
  echo "  ❌ 这台设备【大概率跑不动 canto-tts 进程内推理】"
  echo ""
  echo "     原因:"
  for r in "${REASONS[@]}"; do printf '       · %s\n' "$r"; done
  echo ""
  echo "     ⚠️ 这不是代码问题(技术链已在格仔上全通),是【设备内存策略】问题。"
  echo "     ⚠️ 已排除的解法(都有实测依据,别重试):"
  echo "        · fp16 量化        ⇒ 实测【内存反而多 92MB】(tools/exp-fp16-memory.py 可复跑)"
  echo "        · ORT 关 arena     ⇒ 实测无效"
  echo "        · 改 mmap          ⇒ 本来就是 mmap"
  echo "        · int8 量化        ⇒ 只降 20% 且音色漂移"
  echo "        · 冻结反制 ×6      ⇒ 全部无效(含【前台服务】—— 实测 isForeground=true 仍被冻)"
  echo "     ⇒ 出路:换内存更宽裕的设备 / 改架构(轻量部分在端上,重活回主机)"
fi
exit $VERDICT
