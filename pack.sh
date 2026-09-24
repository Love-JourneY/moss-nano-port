#!/bin/bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026  Nija (bubu12) and contributors
#
# moss-nano-port module —— 粤语播报模块(上下配套开源项目)
# 本脚本属【本模块自有代码】,以 GNU AGPL-3.0-or-later 授权;全文见 ./LICENSE
# ⚠️ 本模块打包/调用的第三方组件各有其许可(见 ./NOTICE),不因本文件而改变。
# ---------------------------------------------------------------------------
# pack.sh —— 把整个模块打成【单文件】,便于移植(整包可迁移)
#
#   ./pack.sh                  打当前平台可用的整包(含两端轮子+模型)
#   ./pack.sh --slim           瘦身:只打本机架构的轮子,不含模型
#   ./pack.sh -o /path/out     指定输出路径
#
# 产物:单个 .tar.zst —— 拷到任意机器,`tar --zstd -xf` 后 `./install.sh` 即可。
#
# ⚠️ 为什么用 tar.zst 而不是自解压 shell:
#    自解压包要塞二进制尾巴,审计困难、易被误判为可疑文件;
#    tar.zst 是标准格式,一条命令解开,内容 100% 可读可核。
set -uo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
. "$SELF_DIR/lib/common.sh"

SLIM=0; OUT=""
while [ $# -gt 0 ]; do
  case "$1" in
    --slim) SLIM=1; shift;;
    -o|--out) OUT="${2:-}"; shift 2;;
    -h|--help) sed -n '2,14p' "$0" | sed 's/^# \?//'; exit 0;;
    *) die "未知参数: $1";;
  esac
done

ROOT="$(ct_root)"
ARCH="$(ct_arch)"
[ -n "$OUT" ] || OUT="$ROOT/dist/moss-nano-port-module-${CT_VERSION}-${ARCH}$([ "$SLIM" = 1 ] && echo "-slim").tar.zst"
mkdir -p "$(dirname "$OUT")"

# ---------- 先做自检:缺件就不许打包(否则打出来的是个坏包) ----------
log "===== 打包前自检 ====="
miss=0
[ -f "$ROOT/install.sh" ] || { err "缺 install.sh"; miss=1; }
[ -f "$ROOT/verify.sh" ]  || { err "缺 verify.sh";  miss=1; }
[ -f "$ROOT/uninstall.sh" ] || { err "缺 uninstall.sh"; miss=1; }
[ -f "$ROOT/README.md" ]  || { err "缺 README.md";  miss=1; }
[ -f "$ROOT/NOTES.md" ]   || { err "缺 NOTES.md(维护笔记必须随包走)"; miss=1; }
[ -f "$ROOT/bin/vsay-canto" ] || { err "缺 bin/vsay-canto"; miss=1; }
[ -d "$ROOT/pkg" ] || { err "缺 pkg/(离线轮子仓)"; miss=1; }
[ "$miss" = "0" ] || die "自检未通过,拒绝打包"

n_wheels=0
for d in "$ROOT/$CT_PKG_DIR_REL"/*-py*; do
  [ -d "$d" ] || continue
  c=$(ls "$d"/*.whl 2>/dev/null | wc -l); n_wheels=$((n_wheels+c))
  log "  轮子 $(basename "$d"):$c 个"
done
[ "$n_wheels" -gt 0 ] || die "pkg/ 里没有任何轮子 —— 离线自洽不成立"
ok "轮子合计 $n_wheels 个"

if [ -d "$ROOT/models/moss-nano-port-nano" ]; then
  ok "模型归档:$(du -sh "$ROOT/models/moss-nano-port-nano" | cut -f1)"
else
  warn "包内无模型 ⇒ 目标机首次安装需联网(装完即离线)"
fi

# ---------- 打包 ----------
log "===== 打包 → $OUT ====="
TMPD="$(mktemp -d)"; trap 'rm -rf "$TMPD"' EXIT
STAGE="$TMPD/moss-nano-port"
mkdir -p "$STAGE"
# ⚠️ 用 tar 管道而不是 cp -a:顺带过滤 dist/.git 这类不该进包的
# ⚠️ 2026-09-22 加 --exclude='./docs/samples' —— 那些 A/B 对照 wav 有 6.3MB,
#    是【给人听的证据】,不是运行需要的东西。发行包只需要代码+文档+轮子+模型;
#    把它们打进去,每次打包体积白涨 6MB,换机器的人还得自己分辨哪些能删。
#    ⚠️ 排除的是"介质",不是"知识":证据留在仓库里(开发场),包外可查。
tar -C "$ROOT" \
    --exclude='./dist' --exclude='./.git' --exclude='./.gitignore' \
    --exclude='./docs/samples' --exclude='./backups' \
    --exclude='*.bak' --exclude='*/__pycache__' \
    -cf - . | tar -C "$STAGE" -xf -

if [ "$SLIM" = "1" ]; then
  log "瘦身模式:只保留 ${ARCH} 的轮子"
  for d in "$STAGE/$CT_PKG_DIR_REL"/*-py*; do
    case "$(basename "$d")" in "${ARCH}-py"*) ;; *) rm -rf "$d";; esac
  done
  rm -rf "$STAGE/models"
fi

# 写一份"包内自述"—— 换机器的人第一眼看到的就是它
cat > "$STAGE/PACK-INFO.txt" <<EOF
moss-nano-port 模块包
  版本     : $CT_VERSION
  打包时间 : $(date '+%Y-%m-%d %H:%M:%S %z')
  打包机器 : $(uname -n 2>/dev/null || echo unknown) / $(uname -m)
  模式     : $([ "$SLIM" = 1 ] && echo "瘦身(仅 ${ARCH} 轮子,无模型)" || echo "整包(全架构轮子 + 模型)")
  轮子数   : $n_wheels

怎么用:
  tar --zstd -xf $(basename "$OUT")
  cd moss-nano-port
  ./install.sh            # 一条命令部署(需 root;本机纪律:走 sbrun)
  ./verify.sh --play      # 验收
  ./uninstall.sh          # 卸载

⚠️ 事实澄清:moss-nano-port 是【男声】(F0 中位 102.3Hz),单音色不可选。
   部署它是"多一个男声选择",不是"提升音色"。详见 README.md / NOTES.md。
EOF
ok "已写入 PACK-INFO.txt"

tar -C "$TMPD" --zstd -cf "$OUT" moss-nano-port || die "打包失败"
# ⚠️ 打包后自检:能列目录才算真包(防"打了一半")
tar --zstd -tf "$OUT" >/dev/null 2>&1 || die "产物损坏,无法列出内容"
ok "产物:$OUT($(du -h "$OUT" | cut -f1),$(tar --zstd -tf "$OUT" | wc -l) 项)"
echo "$OUT"
