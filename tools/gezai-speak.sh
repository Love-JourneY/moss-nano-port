#!/bin/bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026  Nija (bubu12) and contributors
# canto-tts module —— 自有代码,AGPL-3.0-or-later(全文见 ./LICENSE;第三方见 ./NOTICE)
# ---------------------------------------------------------------------------
# ═══════════════════════════════════════════════════════════════════════════
# gezai-speak.sh —— 格仔(一加平板 2 Pro / Android 16 + Debian 13 chroot)
#                   粤语语音播报:合成 + 【唯一能出声的播放路径】
# ═══════════════════════════════════════════════════════════════════════════
# 【为什么需要这个脚本】
#   2026-09-22 实测确证:平板 chroot 里**没有任何**能出声的播放路径 ——
#     · pw-play/pw-cat ⇒ `pw_context_connect() failed: Host is down`(无 PipeWire/Pulse 守护)
#     · aplay hw:0,0   ⇒ `pcm_write: write error: Invalid argument`(Android 音频 HAL 独占声卡)
#     · Android 侧 tinyplay ⇒ `Error playing sample`
#     · App 本地 HTTP 127.0.0.1:8791 ⇒ 进程进后台 cgroup 后不 accept(连接卡在 Recv-Q)
#     · am startservice / start-foreground-service(含 root)⇒ `Error: Not found; no service started.`
#       (logcat 真凶:`OplusHansManager: proxyService ...`,ColorOS 代理后拒绝)
#   ⇒ 唯一系统一定放行、且不创建窗口的通路 = 启动一个
#      `@android:style/Theme.NoDisplay` 的 Activity:
#         com.fcitx5sensevoice/.SpeakTriggerActivity  --es wav_path <路径>
#
# ⚠️⚠️ 使用前须知(重要)
#   1. 本脚本 --consent 时会真的让平板**出声**,并会 startActivity(NoDisplay ⇒ 无窗口;
#      2026-09-22 已用 mCurrentFocus 证明播放前后焦点都不是它)。
#      **必须先征得机器主人(Nija)同意** —— 这正是 --consent 开关存在的理由。
#   2. 不加 --consent:只做「合成 + 落盘 + 取证快照」,**绝不触发播放**。
#   3. 不删别人的任何文件;生产模型 /var/lib/canto-tts/model 只读。
#
# 用法
#   ./gezai-speak.sh "今日天氣幾好"                     # 只合成 + 落盘(安全,不触发)
#   ./gezai-speak.sh --consent "今日天氣幾好"            # 合成 + 真播(需用户同意)
#   ./gezai-speak.sh --consent --play-only <wav路径>    # 只播一个已存在的 wav
#
# 出口码:0 成功 / 1 参数或环境错 / 2 合成失败 / 3 播放触发失败
# ═══════════════════════════════════════════════════════════════════════════
set -uo pipefail

# ── 设备寻址:多目标回退(平板 adb 会掉线) ──
#   ⚠️ 探活 adb 必须加 `</dev/null>` —— 否则 adb shell 会吞掉后面的 stdin(2026-09-22 踩过)
# ⚠️ 2026-09-28:目标不写死 —— 用 GEZAI_TARGETS 环境变量,或靠 device.mac 解析
TARGETS=(${GEZAI_TARGETS:-"localhost:5555"})

# ── App 侧共享目录(同一 inode,两侧路径不同;2026-09-22 实测确认) ──
PKG=com.fcitx5sensevoice
APP_UID=10379          # u0_a379
APP_GID=1078           # ext_data_rw
DIR_ANDROID="/sdcard/Android/data/$PKG/files"                       # Android 侧
DIR_CHROOT="/android/data/media/0/Android/data/$PKG/files"          # chroot 侧(真实后端存储)

VOICE=cv03             # 默认音色(Nija 2026-09-22 试听后选定:cv03)
CONSENT=0
PLAY_ONLY=""
while [ $# -gt 0 ]; do
  case "$1" in
    --consent)   CONSENT=1; shift ;;
    --voice)     VOICE="$2"; shift 2 ;;
    --play-only) PLAY_ONLY="$2"; shift 2 ;;
    -h|--help)   sed -n '2,40p' "$0"; exit 0 ;;
    *)           break ;;
  esac
done
TEXT="${*:-}"

# ── 选一个活着的 adb 目标 ──
ADB=""
for t in "${TARGETS[@]}"; do
  if adb -s "$t" devices </dev/null 2>/dev/null | grep -q "$t[[:space:]]*device"; then ADB="$t"; break; fi
done
[ -n "$ADB" ] || { echo "gezai-speak: 没有可用的 adb 目标(${TARGETS[*]})" >&2; exit 1; }

# ── 在 chroot 里跑一段命令 ──
#   为什么绕这么一圈:命令要穿过 adb shell → Android sh → su -c → chroot bash 四层,
#   任何内嵌引号都会把分组打散(实测:su -c 只吃到第一个词)。
#   ⇒ 把命令**写进脚本文件**再送进 chroot 执行,彻底躲开引号地狱。
#   ⚠️ chroot 必须显式设 PATH —— 平板的 Android env 里没有 /usr/local/bin。
cr() {
  local n="gezai-speak-$$.sh"
  printf '#!/bin/bash\nexport PATH=/usr/local/bin:/usr/bin:/bin\n%s\n' "$1" > "/tmp/$n"
  adb -s "$ADB" push "/tmp/$n" "/data/local/tmp/$n" </dev/null >/dev/null 2>&1
  # ⚠️ 这里单引号必须留着 —— 它让 **远端 sh** 把整串当成 su -c 的单个参数
  adb -s "$ADB" shell su -c "'cp -f /data/local/tmp/$n /data/local/linux/debian/tmp/$n'" </dev/null >/dev/null 2>&1
  adb -s "$ADB" shell su -c "'chroot /data/local/linux/debian /bin/bash /tmp/$n'" </dev/null 2>&1
  local rc=$?
  # 只清理「本脚本自己刚生成的」两个临时脚本(不碰任何既有文件)
  rm -f "/tmp/$n"
  return $rc
}

# ── 取证:audio_flinger 里的活跃音轨快照 ──
#   判据:播放中 `N Tracks of which M are active` 的 M 从 0 变 >0,
#         附近能看到 App uid `10379` / `48000`(采样率)/ `FrmCnt` 增长。
snapshot() {
  echo "--- audio_flinger 活跃音轨计数($1) ---"
  adb -s "$ADB" shell 'dumpsys media.audio_flinger 2>/dev/null | grep -E "of which [0-9]+ are active"' </dev/null 2>&1
  echo "--- 该行附近是否出现 App(10379)的音轨 ---"
  adb -s "$ADB" shell 'dumpsys media.audio_flinger 2>/dev/null | grep -n "10379" | head -5' </dev/null 2>&1
}

if [ -n "$PLAY_ONLY" ]; then
  WAV_ANDROID="$PLAY_ONLY"
  echo "[1/3] 跳过合成(只播已有 wav):$WAV_ANDROID"
else
  [ -n "$TEXT" ] || { echo "gezai-speak: 缺文本(或用 --play-only <wav>)" >&2; exit 1; }
  NAME="gezai-$$.wav"                 # 带 pid,避免并发互相覆盖(不删旧文件)
  OUT="$DIR_CHROOT/$NAME"
  echo "[1/3] 合成中(chroot / vsay-canto-female --voice $VOICE)..."
  # ⚠️ 文本进 shell 前必须安全单引号化(把内部的 ' 变成 '\'' ),
  #    否则文本里的引号会把送进 chroot 的那行命令拆散。
  TEXT_SQ=$(printf '%s' "$TEXT" | sed "s/'/'\\\\''/g")
  # ⚠️ --no-play:合成脚本内那条 pw-play/aplay 探测是死路,不让它白试
  cr "vsay-canto-female --voice $VOICE --no-play --out '$OUT' '$TEXT_SQ'"
  rc=$?
  echo "[1/3] 合成出口码=$rc"
  [ $rc -eq 0 ] || { echo "gezai-speak: 合成失败" >&2; exit 2; }
  # ⚠️ 必须 chown 给 App 的 uid,否则 App 读不到(EACCES)。
  #    2026-09-22 的坑:chroot 侧写进去默认 root 属主 ⇒ 一定要改。
  cr "chown $APP_UID:$APP_GID '$OUT' && chmod 664 '$OUT' && ls -ln '$OUT'"
  WAV_ANDROID="$DIR_ANDROID/$NAME"
  echo "[2/3] wav 就绪:$WAV_ANDROID"
  adb -s "$ADB" shell "ls -la $WAV_ANDROID" </dev/null 2>&1
fi

if [ "$CONSENT" != "1" ]; then
  echo
  echo "[3/3] 【未触发播放】—— 只合成不播(--consent 才播;防误碰用户设备)"
  echo "        确认可以出声后,再执行:"
  echo "        $0 --consent --play-only $WAV_ANDROID"
  exit 0
fi

echo "[3/3] 播放前基线:"
snapshot "播放前"
adb -s "$ADB" shell 'logcat -c' </dev/null 2>/dev/null   # 清缓冲,只抓本次证据

echo "[3/3] 触发:am start -n $PKG/.SpeakTriggerActivity --es wav_path $WAV_ANDROID"
# ⚠️ 四个要点:
#   ① 组件名 = com.fcitx5sensevoice/.SpeakTriggerActivity(installed APK 里已 resolve 验证)
#   ② extra 名 = wav_path(定义在 SpeakService.EXTRA_WAV_PATH)
#   ③ --activity-single-top:重复触发复用实例,不叠加
#   ④ Theme.NoDisplay ⇒ 不创建 window(不是透明);但仍需用户知情
adb -s "$ADB" shell "am start --activity-single-top -n $PKG/.SpeakTriggerActivity --es wav_path $WAV_ANDROID" </dev/null 2>&1
rc=$?
[ $rc -eq 0 ] || { echo "gezai-speak: 触发失败(exit=$rc)" >&2; exit 3; }

echo "--- 等 1.5s 让音轨起来 ---"; sleep 1.5
snapshot "播放中"
echo "--- logcat TTS_PLAYER ---"
adb -s "$ADB" shell 'logcat -d -s TTS_PLAYER:* 2>/dev/null | tail -20' </dev/null 2>&1
echo
echo "判据:出现 TTS_PLAYER_PLAY_START / TTS_PLAYER_PLAY_DONE,"
echo "      且 framesWritten == framesPlayed(尾音没被切)+ audio_flinger 活跃音轨数 >0"
