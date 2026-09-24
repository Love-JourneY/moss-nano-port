#!/bin/bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026  Nija (bubu12) and contributors
#
# moss-nano-port module —— 粤语播报模块(上下配套开源项目)
# 本脚本属【本模块自有代码】,以 GNU AGPL-3.0-or-later 授权;全文见 ./LICENSE
# ⚠️ 本模块打包/调用的第三方组件各有其许可(见 ./NOTICE),不因本文件而改变。
# ---------------------------------------------------------------------------
# uninstall.sh —— moss-nano-port 模块干净卸载(不留残留)
#
#   ./uninstall.sh                     本机卸载
#   ./uninstall.sh --target tablet     平板 chroot 卸载
#   ./uninstall.sh --target all
#   ./uninstall.sh --dry-run
#   ./uninstall.sh --keep-model        保留模型权重(换版本时不重下)
#
# ⚠️ 卸载要"干净":命令、模块、venv、模型、缓存全部列出来逐个处理,
#    不做"删一半留一半"。--keep-model 是唯一的例外,且必须显式给。
set -uo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
. "$SELF_DIR/lib/common.sh"

TARGET="local"; DRY=0; KEEP_MODEL=0
ORIG_ARGS=("$@")   # ⚠️ 先存原始参数 —— 提权重入要用完整参数重跑自己
while [ $# -gt 0 ]; do
  case "$1" in
    --target)     TARGET="${2:-local}"; shift 2;;
    --dry-run|-n) DRY=1; shift;;
    --keep-model) KEEP_MODEL=1; shift;;
    -h|--help)    sed -n '2,12p' "$0" | sed 's/^# \?//'; exit 0;;
    *) die "未知参数: $1";;
  esac
done

run_root() {
  if [ "$DRY" = "1" ]; then printf '  %s[dry-run]%s %s\n' "$C_YEL" "$C_OFF" "$*" >&2; return 0; fi
  "$@"
}

uninstall_core() {
  log "===== 卸载 moss-nano-port(本机)====="
  local n=0
  del() { # $1=路径 $2=说明
    if [ -e "$1" ] || [ -L "$1" ]; then
      log "  移除 $2:$1"
      run_root rm -rf "$1"; n=$((n+1))
    else
      log "  已不存在:$1"
    fi
  }
  del "$CT_BIN"        "用户命令"
  del "$CT_OPT_DIR"    "模块本体+文档"
  del "$CT_VENV_DIR"   "python venv"
  if [ "$KEEP_MODEL" = "1" ]; then
    warn "保留模型(--keep-model):$CT_MODEL_DIR"
    # 只删 HF 缓存(可重建),留模型
    del "$CT_DATA_DIR/cache" "HF 缓存"
  else
    del "$CT_MODEL_DIR" "模型权重"
    del "$CT_DATA_DIR"  "数据目录"
  fi

  # ⚠️ 反向扫描:确认没有残留的 PATH 注入 / profile.d 条目
  #    (本模块【不】写 profile.d —— 靠绝对路径的 /usr/local/bin 命令即可,零全局污染)
  for f in /etc/profile.d/moss-nano-port.sh "$HOME/.bashrc"; do
    if [ -f "$f" ] && grep -q "moss-nano-port" "$f" 2>/dev/null; then
      warn "发现残留引用:$f(本模块本不该写这里,请人工确认)"
    fi
  done

  ok "卸载完成(处理 $n 项)"
}

uninstall_tablet() {
  log "===== 卸载平板 moss-nano-port ====="
  command -v adb >/dev/null 2>&1 || die "找不到 adb"
  local tgt; tgt="$(ct_pick_adb)" || die "三个 adb 目标全部掉线"
  ok "adb 目标:$tgt"
  if [ "$DRY" = "1" ]; then
    printf '  %s[dry-run]%s 在 chroot 内执行卸载\n' "$C_YEL" "$C_OFF" >&2
    return 0
  fi
  # 复用 chroot 内的同一份脚本(逻辑一份)
  if ct_adbx_cmd "$tgt" "[ -x $CT_OPT_DIR/uninstall.sh ] && echo HAS=yes || echo HAS=no" | grep -q HAS=yes; then
    ct_adbx_cmd "$tgt" "$CT_OPT_DIR/uninstall.sh --target local $([ "$KEEP_MODEL" = 1 ] && echo --keep-model)"
  else
    warn "chroot 内没有模块脚本 ⇒ 直接删路径"
    ct_adbx_cmd "$tgt" "rm -rf $CT_BIN $CT_OPT_DIR $CT_VENV_DIR $CT_DATA_DIR && echo removed"
  fi
  ok "平板卸载完成"
}

case "$TARGET" in
  local)  [ "$DRY" = "1" ] || ct_ensure_root "${ORIG_ARGS[@]}" || true; uninstall_core ;;
  tablet) uninstall_tablet ;;
  all)    [ "$DRY" = "1" ] || ct_ensure_root "${ORIG_ARGS[@]}" || true; uninstall_core; uninstall_tablet ;;
  *)      die "未知 target: $TARGET";;
esac
