#!/bin/bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026  Nija (bubu12) and contributors
#
# canto-tts module —— 粤语播报模块(上下配套开源项目)
# 本脚本属【本模块自有代码】,以 GNU AGPL-3.0-or-later 授权;全文见 ./LICENSE
# ⚠️ 本模块打包/调用的第三方组件各有其许可(见 ./NOTICE),不因本文件而改变。
# ---------------------------------------------------------------------------
# verify.sh —— canto-tts 模块验收(给出【通过/失败清单】,不靠"看着像好了")
#
# 判据是硬的:每一项都实际执行并检查产物,任一项失败 ⇒ 退出码 1。
#
#   ./verify.sh                      本机验收
#   ./verify.sh --target tablet      平板 chroot 验收
#   ./verify.sh --target all
#   ./verify.sh --play               额外真的播一遍(本机)
set -uo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
. "$SELF_DIR/lib/common.sh"

TARGET="local"; DO_PLAY=0
while [ $# -gt 0 ]; do
  case "$1" in
    --target) TARGET="${2:-local}"; shift 2;;
    --play)   DO_PLAY=1; shift;;
    -h|--help) sed -n '2,12p' "$0" | sed 's/^# \?//'; exit 0;;
    *) die "未知参数: $1";;
  esac
done

PASS=0; FAIL=0
# 检查项:$1=名称 $2=0/1(通过?) $3=证据
chk() {
  if [ "$2" = "0" ]; then ok "$1 —— $3"; PASS=$((PASS+1))
  else err "$1 —— $3"; FAIL=$((FAIL+1)); fi
}

# 在"目标"上跑一段 stdin 脚本:本机直接跑,平板走 adb→chroot
# ⚠️ 两个环境必须用【同一个检查清单】,否则平板"看着过了"其实没验
target_run() {
  if [ "$TARGET" = "local" ]; then
    bash -s
  else
    command -v adb >/dev/null 2>&1 || { err "无 adb"; return 1; }
    local tgt; tgt="$(ct_pick_adb)" || { err "adb 目标全掉线"; return 1; }
    ct_adbx "$tgt"
  fi
}

log "===== canto-tts 验收(target=$TARGET)====="

# 一次性把【目标机上】的事实抓回来,再本地判定 —— 避免几十次往返
FACTS="$(target_run <<EOF
echo "ARCH=\$(uname -m)"
echo "PY=\$(command -v python3 || echo none)"
echo "PYV=\$(python3 -c 'import sys;print("%d.%d"%sys.version_info[:2])' 2>/dev/null || echo none)"
echo "VENV_PY=\$([ -x $CT_VENV_DIR/bin/python ] && echo yes || echo no)"
echo "VENV_VER=\$($CT_VENV_DIR/bin/python -c 'import importlib.metadata as m;print(m.version("canto-tts"))' 2>/dev/null || echo none)"
echo "CLI=\$([ -x $CT_VENV_DIR/bin/canto-tts ] && echo yes || echo no)"
echo "MODEL=\$([ -f $CT_MODEL_DIR/browser_poc_manifest.json ] && echo yes || echo no)"
echo "MODEL_SYMLINK=\$(find $CT_MODEL_DIR -type l 2>/dev/null | wc -l)"
echo "BIN=\$([ -x $CT_BIN ] && echo yes || echo no)"
echo "OPT=\$([ -f $CT_OPT_DIR/README.md ] && echo yes || echo no)"
echo "PLAYER=\$(for p in paplay pw-play aplay; do command -v \$p >/dev/null 2>&1 && { echo \$p; break; }; done)"
echo "G2P=\$($CT_VENV_DIR/bin/python -c 'import canto_hk_g2p;print("ok")' 2>/dev/null || echo missing)"
echo "ORT=\$($CT_VENV_DIR/bin/python -c 'import onnxruntime as o;print(o.__version__)' 2>/dev/null || echo missing)"
echo "===SYNTH==="
T=\$(mktemp -d 2>/dev/null || echo /tmp/ct-verify-\$\$); mkdir -p "\$T"
if [ -x $CT_VENV_DIR/bin/canto-tts ] && [ -d $CT_MODEL_DIR ]; then
  if $CT_VENV_DIR/bin/canto-tts synthesize "今日天氣幾好，多謝晒。" -o "\$T/v.wav" --checkpoint $CT_MODEL_DIR >/dev/null 2>&1 && [ -s "\$T/v.wav" ]; then
    echo "SYNTH_OK=yes"
    echo "SYNTH_BYTES=\$(wc -c < "\$T/v.wav")"
    echo "SYNTH_RATE=\$(python3 -c "
import wave,sys
try:
  w=wave.open('\$T/v.wav'); print('%d/%d'%(w.getframerate(),w.getnchannels()))
except Exception as e: print('err')
" 2>/dev/null)"
  else echo "SYNTH_OK=no"; fi
else echo "SYNTH_OK=no"; fi
echo "===E2E==="
if [ -x $CT_BIN ]; then
  $CT_BIN -o "\$T/e2e.wav" "多謝晒，今日天氣幾好。" >/dev/null 2>&1 && [ -s "\$T/e2e.wav" ] && echo "E2E_OK=yes" || echo "E2E_OK=no"
else echo "E2E_OK=no"; fi
echo "===PLAY==="
# ⚠️ 要【逐个播放器真试】而不是"探测到哪个就算哪个":
#    平板 chroot 里装了 pw-play,但没有 PipeWire 守护 ⇒ 探测通过、播放失败。
#    只报"有播放器"会把真问题盖住(2026-09-22 踩过)。
if [ "$DO_PLAY" = "1" ] && [ -s "\$T/v.wav" ]; then
  POK=no; PUSED=""; PERR=""
  for p in paplay pw-play aplay; do
    command -v \$p >/dev/null 2>&1 || continue
    [ -z "\$PUSED" ] && PUSED=\$p
    case "\$p" in aplay) \$p -q "\$T/v.wav" 2>"\$T/pe" ;; *) \$p "\$T/v.wav" 2>"\$T/pe" ;; esac
    if [ \$? -eq 0 ]; then POK=yes; PUSED=\$p; break; fi
  done
  PERR=\$(head -1 "\$T/pe" 2>/dev/null | cut -c1-70)
  echo "PLAY_OK=\$POK"
  echo "PLAYER_USED=\$PUSED"
  echo "PLAY_ERR=\$PERR"
else echo "PLAY_OK=skip"; echo "PLAYER_USED="; echo "PLAY_ERR="; fi
rm -rf "\$T"
EOF
)" || true

g() { printf '%s\n' "$FACTS" | grep -E "^$1=" | head -1 | cut -d= -f2-; }

# ⚠️ 空结果必须【快速失败】而不是逐项报错 —— 否则会掩盖真正原因。
#    2026-09-22 踩过:ct_pick_adb 的探活 adb 把 heredoc 从 stdin 吃掉了,
#    平板侧一行都没收到,却表现成"11 项失败",看不出是"根本没连上"。
if [ -z "$(g ARCH)" ]; then
  err "无法从目标机($TARGET)取到任何事实 —— 大概率没连上,不是模块坏了"
  err "  · 平板:先 `adb devices` 看目标在不在;三个目标会互掉线"
  err "  · 本机:直接跑 ./verify.sh 不带 --target"
  exit 1
fi

# ---------- 逐项判定 ----------
[ -n "$(g ARCH)" ]                                  ; chk "目标机可达" $? "$(g ARCH)"
[ "$(g CLI)" = "yes" ]                              ; chk "CLI 可执行 ($CT_VENV_DIR/bin/canto-tts)" $? "CLI=$(g CLI)"
[ "$(g VENV_VER)" != "none" ] && [ -n "$(g VENV_VER)" ] ; chk "canto-tts 已装" $? "版本=$(g VENV_VER)"
# ⚠️ 注意要同时判"非空" —— 空字符串 != "missing" 会让这条假过(踩过)
[ -n "$(g ORT)" ] && [ "$(g ORT)" != "missing" ]    ; chk "onnxruntime 可 import" $? "版本=$(g ORT)"
[ "$(g G2P)" = "ok" ]                               ; chk "canto-hk-g2p 可 import(粤语 G2P)" $? "$(g G2P)"
[ "$(g MODEL)" = "yes" ]                            ; chk "模型已实体化" $? "$CT_MODEL_DIR"
[ "$(g MODEL_SYMLINK)" = "0" ]                      ; chk "模型目录无符号链接(ONNX 硬要求)" $? "符号链接数=$(g MODEL_SYMLINK)"
[ "$(g BIN)" = "yes" ]                              ; chk "命令已安装" $? "$CT_BIN"
[ "$(g OPT)" = "yes" ]                              ; chk "模块+文档已装到安装位" $? "$CT_OPT_DIR"
[ "$(g SYNTH_OK)" = "yes" ]                         ; chk "合成出声(wav 非空)" $? "字节=$(g SYNTH_BYTES) 采样=$(g SYNTH_RATE)"
[ "$(g E2E_OK)" = "yes" ]                           ; chk "端到端(vsay-canto -o)" $? "$(g E2E_OK)"
if [ "$DO_PLAY" = "1" ]; then
  [ "$(g PLAY_OK)" = "yes" ]                        ; chk "实际播放" $? "播放器=$(g PLAYER_USED)"
  # ⚠️ 失败时把真实报错摆出来,并说清"这不是模块坏了" —— 免得下一个人当成 bug 查半天
  if [ "$(g PLAY_OK)" != "yes" ]; then
    err "   真实报错: $(g PLAY_ERR)"
    if [ "$(g ARCH)" = "aarch64" ]; then
      err "   这是【平板 chroot 的已知限制】,不是部署问题:"
      err "     Android 音频 HAL(ohalservice.qti)独占声卡,SELinux Enforcing,"
      err "     chroot 直接开 /dev/snd 一律 write error: Invalid argument。"
      err "     合成是成功的 —— 见上面「合成出声」那一条。"
      err "     平板要出声必须走 Android 自己的音频框架(AudioTrack),见 NOTES.md §7。"
    fi
  fi
else
  [ -n "$(g PLAYER)" ]                              ; chk "有可用播放器(未实播,加 --play 可实播)" $? "$(g PLAYER)"
fi

log "========== 结果:$PASS 通过 / $FAIL 失败 =========="
[ "$FAIL" = "0" ] || exit 1
