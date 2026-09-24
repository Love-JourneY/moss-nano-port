#!/bin/bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026  Nija (bubu12) and contributors
#
# moss-nano-port module —— 粤语播报模块(上下配套开源项目)
# 本脚本属【本模块自有代码】,以 GNU AGPL-3.0-or-later 授权;全文见 ./LICENSE
# ⚠️ 本模块打包/调用的第三方组件各有其许可(见 ./NOTICE),不因本文件而改变。
# ---------------------------------------------------------------------------
# install.sh —— moss-nano-port 模块部署(幂等 · 可重复跑 · 带 --dry-run)
#
# 一条命令部署:
#   ./install.sh                     本机(自动识别平台)
#   ./install.sh --dry-run           只打印要做什么,不动手
#   ./install.sh --target tablet     推送到平板 Debian chroot 并安装
#   ./install.sh --target all        本机 + 平板
#
# ⚠️ 设计要点(为什么长这样):
#   1) 【同一份脚本两端跑】—— 平板的安装 = 把整个模块目录推进 chroot,再在 chroot 内跑
#      `install.sh --target local`。逻辑只有一份,不会两边跑偏。
#   2) 【离线优先】—— 轮子在 pkg/<arch>-py<ver>/,模型在 models/。
#      先试离线安装,失败才退回联网(联网只为省事,不是必需)。
#   3) 【幂等】—— 每步都先判"做好了没",重复跑不会重复干活、不会报错。
set -uo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
. "$SELF_DIR/lib/common.sh"

DRY=0
TARGET="local"
ALLOW_NET=1          # 离线装不上时是否允许联网兜底
DO_APT=1             # 是否允许 apt 装 python3-venv(官方仓库包)
# ⚠️ 必须先把原始参数存下来:下面的 while 会把 $@ 吃掉,
#    而提权重入(ct_ensure_root)要用【完整原始参数】重新执行自己。
ORIG_ARGS=("$@")
while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run|-n) DRY=1; shift;;
    --target)     TARGET="${2:-local}"; shift 2;;
    --offline)    ALLOW_NET=0; shift;;
    --no-apt)     DO_APT=0; shift;;
    -h|--help)    sed -n '2,20p' "$0" | sed 's/^# \?//'; exit 0;;
    *) die "未知参数: $1(用 -h 看用法)";;
  esac
done

# ── 执行器:dry-run 时只打印 ──
run() {
  if [ "$DRY" = "1" ]; then printf '  %s[dry-run]%s %s\n' "$C_YEL" "$C_OFF" "$*" >&2; return 0; fi
  "$@"
}
# 需要 root 的写操作:dry-run 时只打印
run_root() {
  if [ "$DRY" = "1" ]; then printf '  %s[dry-run/root]%s %s\n' "$C_YEL" "$C_OFF" "$*" >&2; return 0; fi
  "$@"
}

# =====================================================================
# 核心安装(在【目标机器本机】执行;平板场景由 chroot 内部的自己调用)
# =====================================================================
install_core() {
  local root; root="$(ct_root)"
  local arch; arch="$(ct_arch)"
  local pyv pybin

  log "===== moss-nano-port 模块部署(本机·$arch)====="
  log "模块版本 $CT_VERSION"

  # ---------- ① python 解释器 ----------
  pybin="${CT_PYTHON:-python3}"
  if ! command -v "$pybin" >/dev/null 2>&1; then
    die "找不到 $pybin —— 请先装 python3"
  fi
  pyv="$(ct_pyver "$pybin")"
  log "目标解释器:$pybin(Python $(command -v "$pybin" >/dev/null && "$pybin" -V 2>&1 | awk '{print $2}'),py$pyv)"

  # ---------- ② venv ----------
  if [ -x "$CT_VENV_DIR/bin/moss-nano-port" ]; then
    ok "venv 已在:$CT_VENV_DIR(跳过创建)"
  else
    if [ -d "$CT_VENV_DIR" ] && [ ! -x "$CT_VENV_DIR/bin/python" ]; then
      warn "venv 目录残缺 ⇒ 重建:$CT_VENV_DIR"
      run_root rm -rf "$CT_VENV_DIR"
    fi
    if [ ! -d "$CT_VENV_DIR" ]; then
      # ⚠️ Debian 的 python3 默认【不带】ensurepip ⇒ `python3 -m venv` 会失败
      #    先确认 venv 可用,不行就 apt 装 python3-venv(官方仓库包)
      if ! "$pybin" -c 'import ensurepip' >/dev/null 2>&1; then
        if [ "$DO_APT" = "1" ] && command -v apt-get >/dev/null 2>&1; then
          log "缺 ensurepip ⇒ apt 装 python3-venv(官方仓库包)"
          # 优先用归档在包内的 .deb(完全离线),没有才联网 apt
          local debdir="$root/$CT_PKG_DIR_REL/deb-${arch}"
          if compgen -G "$debdir/*.deb" >/dev/null 2>&1; then
            log "  用包内归档的 .deb 离线安装:$debdir"
            run_root dpkg -i "$debdir"/*.deb || run_root apt-get -f install -y
          else
            run_root apt-get update -qq || true
            run_root env DEBIAN_FRONTEND=noninteractive apt-get install -y python3-venv python3-pip \
              || die "apt 装 python3-venv 失败 —— 请手动装(见 NOTES.md)"
          fi
        else
          die "缺 ensurepip 且不允许 apt ⇒ 请手动装 python3-venv"
        fi
      fi
      log "创建 venv:$CT_VENV_DIR"
      run_root mkdir -p "$(dirname "$CT_VENV_DIR")"
      run_root "$pybin" -m venv "$CT_VENV_DIR" || die "创建 venv 失败"
    fi
  fi

  # ---------- ③ 离线轮子安装 ----------
  local pkgdir="$root/$CT_PKG_DIR_REL/${arch}-py${pyv}"
  if [ "$DRY" = "1" ]; then
    printf '  %s[dry-run]%s pip install --no-index --find-links %s moss-nano-port ...\n' "$C_YEL" "$C_OFF" "$pkgdir" >&2
  elif [ -d "$pkgdir" ] && compgen -G "$pkgdir/*.whl" >/dev/null 2>&1; then
    log "离线安装轮子:$pkgdir($(ls "$pkgdir"/*.whl 2>/dev/null | wc -l) 个)"
    if ! "$CT_VENV_DIR/bin/pip" install --no-index --find-links "$pkgdir" \
           moss-nano-port==0.1.4 2>&1 | tail -3; then
      warn "离线安装不完整,尝试补齐"
      ALLOW_NET=1
    fi
  else
    warn "没有 $pkgdir 的离线轮子"
  fi

  # 联网兜底(仅为省事;不是必需 —— 离线轮子齐全时不走这条)
  if [ "$ALLOW_NET" = "1" ] && ! "$CT_VENV_DIR/bin/python" -c 'import canto_tts' >/dev/null 2>&1; then
    log "联网兜底:pip 从镜像装 moss-nano-port(轮子归档缺失时才走)"
    run "$CT_VENV_DIR/bin/pip" install -i "${CT_PIP_INDEX:-https://pypi.tuna.tsinghua.edu.cn/simple}" \
        moss-nano-port==0.1.4 || die "pip 安装失败"
  fi

  # ---------- ④ 模型实体化(关键!见 NOTES.md「符号链接陷阱」) ----------
  # ⚠️ 绝不直接把 HF 缓存目录当模型用:缓存里是符号链接,ONNX 的 external-data
  #    路径校验会解析到 blobs/<别的 sha>/,报 "escapes model directory"。
  #    必须 cp -rL 解引用成"同一目录下的真文件"。
  if [ -f "$CT_MODEL_DIR/browser_poc_manifest.json" ] && [ ! -L "$CT_MODEL_DIR/browser_poc_manifest.json" ]; then
    ok "模型已实体化:$CT_MODEL_DIR"
  else
    run_root mkdir -p "$CT_DATA_DIR"
    local arc="$root/models/moss-nano-port-nano"
    if [ -d "$arc" ]; then
      log "从包内归档安装模型:$arc"
      run_root rm -rf "$CT_MODEL_DIR"
      run_root cp -rL "$arc" "$CT_MODEL_DIR"
    elif [ "$ALLOW_NET" = "1" ]; then
      log "包内无模型 ⇒ 从镜像下载(HF_ENDPOINT=$CT_HF_MIRROR)"
      if [ "$DRY" = "1" ]; then
        printf '  %s[dry-run]%s snapshot_download %s via %s\n' "$C_YEL" "$C_OFF" "$CT_HF_REPO" "$CT_HF_MIRROR" >&2
      else
        local snap
        snap="$(HF_ENDPOINT="$CT_HF_MIRROR" "$CT_VENV_DIR/bin/python" - "$CT_HF_REPO" <<'PY'
import os, sys
from huggingface_hub import snapshot_download
print(snapshot_download(sys.argv[1]))
PY
        )" || die "下载模型失败"
        # ⚠️ cp -rL 是重点:解引用符号链接,把散在 blobs/ 的文件收拢到同一目录
        run_root rm -rf "$CT_MODEL_DIR"
        run_root cp -rL "$snap" "$CT_MODEL_DIR"
      fi
    else
      die "包内无模型且已 --offline ⇒ 无法继续"
    fi
  fi

  # ---------- ⑤ 用户命令 ----------
  if [ -x "$CT_BIN" ]; then
    ok "命令已存在:$CT_BIN(更新内容)"
  fi
  run_root install -m 0755 "$root/bin/vsay-canto" "$CT_BIN"

  # ---------- ⑥ 模块本体(文档随包走) ----------
  # ⚠️ 为什么要拷进 /opt:换机器后不靠翻旧会话也能接手 —— 说明书必须和代码在一起
  if [ "$root" != "$CT_OPT_DIR" ]; then
    run_root mkdir -p "$CT_OPT_DIR"
    # ⚠️ 只拷代码与文档,【不拷 pkg/ 与 models/】——
    #    那两样是"安装介质"(离线轮子 102MB + 模型 729MB),装完就没用了;
    #    复制进 /opt 等于白占 850MB,也违反 FHS(/opt 是程序位,模型该在 /var/lib)。
    # ⚠️ 2026-09-22 再加两条:docs/samples(A/B 对照 wav 6.3MB)与 backups/ ——
    #    同理,那是"给人听的证据"和"回滚副本",不是运行需要的东西。
    run_root tar -C "$root" \
      --exclude='./pkg' --exclude='./models' --exclude='./dist' \
      --exclude='./.git' --exclude='*.bak' --exclude='*/__pycache__' \
      --exclude='./docs/samples' --exclude='./backups' \
      -cf - . | run_root tar -C "$CT_OPT_DIR" -xf -
    ok "模块已装到:$CT_OPT_DIR(不含 pkg/ models/)"
  else
    ok "已在安装位:$CT_OPT_DIR"
  fi

  ok "本机部署完成"
  return 0
}

# =====================================================================
# 平板部署 = 把模块推进 chroot,再在 chroot 内跑本脚本的 install_core
# =====================================================================
install_tablet() {
  log "===== moss-nano-port 部署到平板(格仔 · Debian chroot)====="
  command -v adb >/dev/null 2>&1 || die "找不到 adb"
  local tgt; tgt="$(ct_pick_adb)" || die "三个 adb 目标全部掉线(Wi-Fi/VPN/USB 都试过)"
  ok "adb 目标:$tgt"

  local root; root="$(ct_root)"
  # 离线物要在【目标架构】下取:aarch64 轮子
  local pkgdir="$root/$CT_PKG_DIR_REL/aarch64-py313"
  local stage="/data/local/tmp/moss-nano-port-stage"

  if [ "$DRY" = "1" ]; then
    printf '  %s[dry-run]%s adb push %s → %s\n' "$C_YEL" "$C_OFF" "$root" "$stage" >&2
    printf '  %s[dry-run]%s 在 chroot 内跑 install.sh --target local\n' "$C_YEL" "$C_OFF" >&2
    return 0
  fi

  # ① 清场 + 推送(⚠️ 用 /data/local/tmp 中转:adb push 以 shell 身份跑,写不进 chroot)
  adb -s "$tgt" shell "rm -rf $stage" >/dev/null 2>&1
  adb -s "$tgt" shell "mkdir -p $stage" >/dev/null 2>&1
  # 只推需要的:aarch64 轮子 + 模型 + 脚本 + 文档(不推 x86_64 轮子,省一半传输)
  log "推送模块骨架 → 平板"
  for item in install.sh verify.sh uninstall.sh pack.sh README.md NOTES.md lib bin assets; do
    adb -s "$tgt" push "$root/$item" "$stage/" >/dev/null 2>&1 || warn "推送 $item 失败"
  done
  adb -s "$tgt" shell "mkdir -p $stage/pkg $stage/models" >/dev/null 2>&1
  # ⚠️ 幂等优化:轮子和模型各才 49M / 729M,每次重推一遍纯属浪费(实测推一次要 ~95 秒)。
  #    先问目标:已经装好了就【不推】。判据用"实体化后的真文件存在",不是"目录存在"。
  local need_wheels=1 need_model=1
  if ct_adbx_cmd "$tgt" "[ -x $CT_VENV_DIR/bin/moss-nano-port ] && echo HAVE=1 || echo HAVE=0" 2>/dev/null | grep -q HAVE=1; then
    need_wheels=0; log "目标已有 venv+引擎 ⇒ 跳过推送轮子"
  fi
  if ct_adbx_cmd "$tgt" "[ -f $CT_MODEL_DIR/browser_poc_manifest.json ] && [ ! -L $CT_MODEL_DIR/browser_poc_manifest.json ] && echo HAVE=1 || echo HAVE=0" 2>/dev/null | grep -q HAVE=1; then
    need_model=0; log "目标已有实体化模型 ⇒ 跳过推送模型"
  fi

  if [ "$need_wheels" = "1" ]; then
    log "推送 aarch64 离线轮子($(du -sh "$pkgdir" 2>/dev/null | cut -f1))"
    adb -s "$tgt" push "$pkgdir" "$stage/pkg/aarch64-py313" >/dev/null 2>&1 || warn "推送轮子失败"
  fi
  if [ "$need_model" = "1" ] && [ -d "$root/models/moss-nano-port-nano" ]; then
    log "推送模型归档($(du -sh "$root/models/moss-nano-port-nano" 2>/dev/null | cut -f1))"
    adb -s "$tgt" push "$root/models/moss-nano-port-nano" "$stage/models/moss-nano-port-nano" >/dev/null 2>&1 || warn "推送模型失败"
  fi
  if [ "$need_wheels" = "1" ] && [ -d "$root/$CT_PKG_DIR_REL/deb-aarch64" ]; then
    log "推送离线 .deb(venv 引导用)"
    adb -s "$tgt" push "$root/$CT_PKG_DIR_REL/deb-aarch64" "$stage/pkg/deb-aarch64" >/dev/null 2>&1 || true
  fi

  # ② 搬进 chroot(需要 root;Android 侧文件属主不同,用 su cp)
  log "搬进 chroot:$CT_CHROOT$CT_OPT_DIR"
  adb -s "$tgt" shell "su -c 'mkdir -p $CT_CHROOT$CT_OPT_DIR && cp -a $stage/. $CT_CHROOT$CT_OPT_DIR/ && rm -rf $stage'" >/dev/null 2>&1 \
    || die "搬进 chroot 失败"

  # ③ 在 chroot 内执行本脚本(逻辑只有一份)
  log "在 chroot 内执行安装(首次约 1~3 分钟)"
  ct_adbx_cmd "$tgt" "export PATH=/usr/local/bin:/usr/bin:/bin" \
    "$CT_OPT_DIR/install.sh --target local ${DO_APT:+} ${ALLOW_NET:+}" \
    "echo INSTALL_RC=\$?"

  # ④ 清掉 chroot 里的"安装介质"(pkg/ models/)
  # ⚠️ 必须清:轮到平板时 $root 就是 /opt/moss-nano-port,install_core 的
  #    「排除 pkg/models 再拷进 /opt」那一步会整段跳过(源=目标),
  #    ⇒ 850MB 的轮子和模型会一直白占在 /opt 里。/opt 是程序位,不是仓库。
  log "清理 chroot 内的安装介质(pkg/ models/)"
  ct_adbx_cmd "$tgt" "rm -rf $CT_OPT_DIR/pkg $CT_OPT_DIR/models && echo cleaned" >/dev/null 2>&1

  ok "平板部署流程结束(详情看上面输出)"
}

case "$TARGET" in
  local)  [ "$DRY" = "1" ] || ct_ensure_root "${ORIG_ARGS[@]}" || true; install_core ;;
  tablet) install_tablet ;;
  all)    [ "$DRY" = "1" ] || ct_ensure_root "${ORIG_ARGS[@]}" || true; install_core; install_tablet ;;
  *)      die "未知 target: $TARGET(可选 local|tablet|all)";;
esac
