#!/bin/bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# moss-nano-port for Android —— 验收。判据是【可观测事实】,不是"看着像好了"。
set -uo pipefail
TARGET="${ANDROID_SERIAL:-${2:-}}"
[ -n "$TARGET" ] || { echo "用法: ./verify.sh --target <adb>"; exit 2; }
AND="adb -s $TARGET"
PKG=canto.tts
DAEMON_PORT="${CANTO_DAEMON_PORT:-18790}"
PASS=0; FAIL=0
ok(){  printf '  ✅ %s —— %s\n' "$1" "$2"; PASS=$((PASS+1)); }
bad(){ printf '  ❌ %s —— %s\n' "$1" "$2"; FAIL=$((FAIL+1)); }
chk(){ [ "$2" = "$3" ] && ok "$1" "$2" || bad "$1" "期望 $3,实到 $2"; }
say(){ printf '  %s\n' "$*"; }

echo "===== moss-nano-port for Android 验收 ====="
chk "设备在线" "$($AND get-state 2>/dev/null | tr -d '\r')" "device"
chk "APK 已安装" "$($AND shell pm list packages 2>/dev/null | grep -c "$PKG" | tr -d '\r')" "1"
# ⚠️ 用【是否出现在引擎清单里】判断,而不是数行数(query-services 输出多行)
if $AND shell "cmd package query-services -a android.intent.action.TTS_SERVICE 2>/dev/null | grep -q 'canto.CantoTtsService'" >/dev/null 2>&1; then
  ok "服务被系统列为 TTS 引擎" "canto.CantoTtsService"
else bad "服务被系统列为 TTS 引擎" "没在清单里"; fi
# ⚠️ "是不是默认引擎"【不作硬断言】—— 安装时我们【不擅自改】用户的默认引擎
CURSYNTH=$($AND shell "settings get secure tts_default_synth 2>/dev/null" | tr -d '\r')
if [ "$CURSYNTH" = "$PKG" ]; then ok "是默认引擎" "$PKG"
else say "ℹ️  当前默认引擎是 $CURSYNTH(想设为 $PKG 请跑 install.sh)"; fi
# ⚠️ 模型在【App 私有目录】(不是 /sdcard 暂存区)—— 且 App 首启会自拷过去。
#    ⚠️ 路径也要用 /data/user/0(= /data/data),因为 App 看到的就是这个。
D="/data/user/0/$PKG/files/models/canto"
# ⚠️ G2P 的 .so 不在这里 —— 它们【打进 APK 的 lib/】了(Android 14+ 不许 dlopen 可写目录)
chk "模型 tts/ 在" "$($AND shell "su -c 'ls $D/tts/*.onnx 2>/dev/null | wc -l'" 2>/dev/null | tr -d '\r')" "5"
chk "模型 codec/ 在" "$($AND shell "su -c 'ls $D/codec/*.onnx 2>/dev/null | wc -l'" 2>/dev/null | tr -d '\r')" "3"
chk "G2P .so 在 APK 的 lib/(不是模型目录)" "$($AND shell "su -c 'ls /data/app/*/$PKG-*/lib/arm64/libcanto_g2p*.so 2>/dev/null | wc -l'" 2>/dev/null | tr -d '\r')" "2"
chk "音节表在" "$($AND shell "su -c 'ls $D/syllable-ids.tsv 2>/dev/null | wc -l'" 2>/dev/null | tr -d '\r')" "1"
chk "p2y 表在" "$($AND shell "su -c 'ls $D/p2y-rules.tsv 2>/dev/null | wc -l'" 2>/dev/null | tr -d '\r')" "1"
chk "tuning.properties 在" "$($AND shell "su -c 'ls $D/tuning.properties 2>/dev/null | wc -l'" 2>/dev/null | tr -d '\r')" "1"
chk "音色库在(含 male-default)" "$($AND shell "su -c 'ls $D/voicebank/*.json 2>/dev/null | wc -l'" 2>/dev/null | tr -d '\r')" "7"
echo
echo "  --- 属主(必须 = App uid,否则 EACCES)---"
OWN=$($AND shell "su -c 'stat -c %u $D/tts 2>/dev/null'" 2>/dev/null | tr -d '\r')
APPUID=$($AND shell "su -c 'stat -c %u /data/user/0/$PKG 2>/dev/null'" 2>/dev/null | tr -d '\r')
chk "模型属主 = App uid" "$OWN" "$APPUID"
echo
echo "  --- 🕊️ root 依赖已移除(2026-09-27)---"
echo "     实测:无 root 时 App 自己跑 [S7]1266ms/[S9]1811ms,与 root 守护(1270/1745)几乎一样"
echo "     ⇒ 守护进程 / 开机自启 / root 提示 均已删除。见 VOICE.md §9"
echo "     ⚠️ 不被 ColorOS virtualFreeze 冻,靠的是【常驻 1×1 透明 overlay】"
echo

# ===== 汇总 =====
echo "===== $PASS 通过 / $FAIL 失败 ====="
[ "$FAIL" -eq 0 ]
