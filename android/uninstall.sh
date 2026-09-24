#!/bin/bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# moss-nano-port for Android —— 干净移除,不留残留
set -uo pipefail
TARGET="${ANDROID_SERIAL:-${2:-}}"
[ -n "$TARGET" ] || { echo "用法: ./uninstall.sh --target <adb>"; exit 2; }
AND="adb -s $TARGET"; PKG=canto.tts
echo "===== 移除 moss-nano-port for Android ====="
# 先把默认引擎还回去(否则系统 TTS 会哑)
CUR=$($AND shell settings get secure tts_default_synth 2>/dev/null | tr -d '\r')
if [ "$CUR" = "$PKG" ]; then
  echo "  默认引擎是我们的 ⇒ 先还回 OPPO 自带,避免系统 TTS 断掉"
  $AND shell settings put secure tts_default_synth com.oplus.ttsaccessibilityengine 2>/dev/null
fi
$AND shell "rm -rf /sdcard/Android/data/$PKG" 2>/dev/null && echo "  ✗ 已删模型与资产(外部暂存)"
$AND uninstall $PKG 2>/dev/null && echo "  ✗ 已卸载 APK"
echo "  --- 核对 ---"
$AND shell pm list packages 2>/dev/null | grep -q "$PKG" && echo "  ⚠️ 包还在" || echo "  ✅ 包已卸载"
$AND shell "ls /sdcard/Android/data/$PKG 2>/dev/null | wc -l" | tr -d '\r' | sed 's/^/  残留文件数: /'
