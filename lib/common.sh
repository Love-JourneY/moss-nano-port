#!/bin/bash
# common.sh —— moss-nano-port 模块的共用常量与函数(被 install/verify/uninstall/pack 共同 source)
#
# ⚠️ 为什么要单独一个库:四个脚本都要用同一套路径常量。
#    散着写四遍 = 改一处漏三处(变更涟漪);集中一处 = 改一次全对。
#
# ⚠️ 陷阱(2026-09-22 踩过):被 source 的库里**绝不能用 `return` 提前结束主逻辑**
#    —— 在 source 场景下它会把【调用者脚本】也一并结束。
#    正确做法:主逻辑包进 `if [ "${BASH_SOURCE[0]}" = "$0" ]`。本文件没有主逻辑,故安全。

# ---------- 模块版本(打包/验收用,改这里就够) ----------
CT_VERSION="0.1.4-module.1"

# ---------- 路径契约(FHS 硬底线:程序→/opt,状态/数据→/var/lib,命令→/usr/local/bin) ----------
CT_OPT_DIR="/opt/moss-nano-port"           # 模块本体(脚本+文档)安装位 —— add-on application software
CT_VENV_DIR="/opt/moss-nano-port-venv"     # python 虚拟环境(笔记本既有,平板新建)
CT_DATA_DIR="/var/lib/moss-nano-port"      # 状态/数据(模型权重、HF 缓存)—— 变量数据归 /var/lib
CT_MODEL_DIR="$CT_DATA_DIR/model"     # 实体化后的模型目录(见 NOTES.md「符号链接陷阱」)
CT_BIN="/usr/local/bin/vsay-canto"    # 面向用户的命令行包装

# ---------- 离线仓库(非官方仓库包一律归档进包内,满足「离线自洽」) ----------
CT_PKG_DIR_REL="pkg"

# ---------- 网络镜像(国内直连 HF 会卡;仅首次下载模型时用) ----------
CT_HF_MIRROR="${CT_HF_MIRROR:-https://hf-mirror.com}"
CT_HF_REPO="typangaa/moss-nano-port-nano"

# ---------- 日志 ----------
if [ -t 1 ]; then
  C_RED=$'\033[31m'; C_GRN=$'\033[32m'; C_YEL=$'\033[33m'; C_CYA=$'\033[36m'; C_OFF=$'\033[0m'
else
  C_RED=; C_GRN=; C_YEL=; C_CYA=; C_OFF=
fi
# ⚠️ 日志一律走 stderr:否则被 $(...) 捕获时会污染返回值(2026-09-22 踩过)
log()  { printf '%s[%s]%s %s\n' "$C_CYA" "$(date +%H:%M:%S)" "$C_OFF" "$*" >&2; }
ok()   { printf '%s  ✅ %s%s\n' "$C_GRN" "$*" "$C_OFF" >&2; }
warn() { printf '%s  ⚠️  %s%s\n' "$C_YEL" "$*" "$C_OFF" >&2; }
err()  { printf '%s  ❌ %s%s\n' "$C_RED" "$*" "$C_OFF" >&2; }
die()  { err "$*"; exit 1; }

# ---------- 模块根目录(本文件在 <root>/lib/ 下) ----------
# ⚠️ 用 BASH_SOURCE 而不是 $0:source 进来的脚本 $0 是调用者
ct_root() {
  local self="${BASH_SOURCE[0]}"
  ( cd "$(dirname "$self")/.." && pwd )
}

# ---------- 平台识别 ----------
# 返回 <arch> 供选择 pkg/<arch>-py<ver> 离线轮子目录
ct_arch() {
  case "$(uname -m)" in
    x86_64|amd64)   echo "x86_64" ;;
    aarch64|arm64)  echo "aarch64" ;;
    *)              echo "unknown" ;;
  esac
}

# 目标 python 版本(用【目标解释器】问,不猜)
# $1 = python 可执行文件;输出如 314
ct_pyver() {
  local py="${1:-python3}"
  "$py" -c 'import sys; print("%d%d" % sys.version_info[:2])' 2>/dev/null
}

# 离线轮子目录:<root>/pkg/<arch>-py<ver>
ct_pkg_dir() {
  local root arch pyv
  root="$(ct_root)"; arch="$(ct_arch)"
  pyv="$(ct_pyver "${CT_PYTHON:-python3}")"
  echo "$root/$CT_PKG_DIR_REL/${arch}-py${pyv}"
}

# ---------- root 提权(本机纪律:要 root 一律走 sbrun,不用 sudo/pkexec) ----------
# ⚠️ 为什么不用 sudo:agent 沙箱有 NoNewPrivs=1,setuid 直接失效;
#    pkexec 在独立 PID namespace 里 polkit 认不出,表现为"卡死且不弹窗"。
ct_ensure_root() {
  [ "$(id -u)" = "0" ] && return 0
  command -v sbrun >/dev/null 2>&1 || return 1
  log "非 root ⇒ 通过 sudo-bridge 重入(需在平板上点批准)"
  # ⚠️ 用绝对路径重入:$0 可能是 ./install.sh,而 sbrun 换 cwd 后会找不到文件
  local self; self="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"
  exec sbrun --why "moss-nano-port 模块部署:需要写 /opt、/var/lib、/usr/local/bin" -- "$self" "$@"
}

# ---------- 目标选择:本机 or 平板 chroot ----------
# 平板侧一切路径都在 Debian chroot 内部(/data/local/linux/debian 之下)
CT_CHROOT="/data/local/linux/debian"

# adbpick:自动挑一个"活的" adb 目标 —— 三个目标(Wi-Fi/VPN/USB)会互相掉线
# 用法: CT_ADB_TARGET=$(ct_pick_adb) || die "..."
#
# ⚠️⚠️ 2026-09-22 血坑:这里的每次 adb 调用必须 **</dev/null**。
#    原因:`adb shell` 会读取自己的 stdin。当调用者写成
#        FACTS="$(target_run <<EOF ... EOF)"     # heredoc 从 stdin 喂脚本
#    时,`ct_pick_adb` 里这些"探活"的 adb 会【先把 heredoc 吃掉】,
#    等真正的 `ct_adbx` 拿到 stdin 时已经是空的 ⇒ 平板侧一行都没收到、
#    输出全空,表现成"11 项失败"的假象(本机却是全过)。
#    修法:探活全部 </dev/null,绝不碰调用者的 stdin。
ct_pick_adb() {
  local t
  # ⚠️ 2026-09-28 开源准备:不再写死本机 IP —— 优先用 CANTO_ADB_TARGETS 环境变量,
  #    其次读 device.mac 解析(设备寻址纪律 §P1:用 MAC 不用 IP)
  for t in ${CANTO_ADB_TARGETS:-localhost:5555 127.0.0.1:5555}; do
    if timeout 8 adb -s "$t" shell echo ok >/dev/null 2>&1 </dev/null; then echo "$t"; return 0; fi
  done
  # ⚠️ 都掉线 ⇒ 主动 connect 一次再试(adb over TCP 掉线后不会自动回来)
  for t in ${CANTO_ADB_TARGETS:-localhost:5555 127.0.0.1:5555}; do
    timeout 10 adb connect "$t" >/dev/null 2>&1 </dev/null
    if timeout 8 adb -s "$t" shell echo ok >/dev/null 2>&1 </dev/null; then echo "$t"; return 0; fi
  done
  return 1
}

# 在平板 chroot 内跑脚本。两种用法:
#   ct_adbx <tgt> <<'EOF' ...脚本... EOF      # 脚本走 stdin(推荐,免疫引号地狱)
#   ct_adbx <tgt> "命令1" "命令2"             # 参数形式(内部转成 stdin)
# ⚠️ 必须显式 export PATH,否则 chroot 里 python3: command not found
# ⚠️ 用 stdin 而不是把脚本塞进多层引号 —— 引号地狱会把脚本撕碎(2026-09-22 踩过)
# ⚠️ 有参数时【才】把参数喂进去;没参数时把调用者的 stdin 原样透传
#    (透传错了就会把 heredoc 吃掉 —— 2026-09-22 的 ct_pick_adb 坑同源)
ct_adbx() {
  local tgt="$1"; shift
  # ⚠️ 用数组拼命令,不用 eval(eval 会把命令里的内容再解释一遍,危险)
  local -a remote=(adb -s "$tgt" shell "su -c 'chroot $CT_CHROOT /bin/bash -c \"export PATH=/usr/local/bin:/usr/bin:/bin; exec /bin/bash -s\"'")
  if [ "$#" -gt 0 ]; then
    printf '%s\n' "$@" | "${remote[@]}" 2>&1 | tr -d '\r'
  else
    "${remote[@]}" 2>&1 | tr -d '\r'
  fi
}

# 在平板 chroot 内跑若干条命令(参数形式,语义更清楚;内部即 ct_adbx <tgt> "$@")
ct_adbx_cmd() {
  local tgt="$1"; shift
  ct_adbx "$tgt" "$@"
}
