#!/bin/bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026  Nija (bubu12) and contributors
#
# canto-tts for Android —— 一键部署(幂等 · 可重复跑 · 带 --dry-run)
#
# ⚠️ 为什么是"便携包"而不是"一个 APK":
#   模型底价 682.5MB(global_shared 420.7 + local_shared 219.6 + codec 42.2)砍不动,
#   塞 APK 不现实(Play 基础包上限 100MB)。而这个包的形态正好满足我们的
#   「基础设施纪律」八条:一个目录 = 全部,cp -a 拖到另一台机器就能复现。
#
# 用法:
#   ./install.sh                 # 部署到默认目标(用 $ANDROID_SERIAL 或 adb.mac 解析)
#   ./install.sh --dry-run       # 只打印计划,不动任何东西
#   ./install.sh --target <adb>  # 显式指定设备
#   ./install.sh --no-apk        # 跳过 APK(只推模型)
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

DRY=0; TARGET="${ANDROID_SERIAL:-}"; NO_APK=0
while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) DRY=1; shift;;
    --target)  TARGET="${2:-}"; shift 2;;
    --no-apk)  NO_APK=1; shift;;
    -h|--help) sed -n '2,20p' "$0"; exit 0;;
    *) echo "未知参数: $1" >&2; exit 2;;
  esac
done
say(){ printf '  %s\n' "$*"; }
run(){ if [ "$DRY" = 1 ]; then say "[dry-run] $*"; else eval "$@"; fi; }

say "===== canto-tts for Android 部署 ====="

# ① 找设备(⚠️ 设备寻址纪律:优先 MAC 解析,IP 只当回退)
if [ -z "$TARGET" ]; then
  MACFILE="$HERE/device.mac"
  if [ -f "$MACFILE" ]; then
    MAC="$(cat "$MACFILE")"
    IP="$(ip neigh show 2>/dev/null | awk -v m="$MAC" 'tolower($3)==tolower(m){print $1; exit}')"
    [ -n "$IP" ] && TARGET="$IP:5555"
  fi
fi
[ -n "$TARGET" ] || { echo "❌ 找不到设备。请 --target <adb> 或写 device.mac" >&2; exit 1; }
say "目标设备: $TARGET"
AND="adb -s $TARGET"

# ② APK
if [ "$NO_APK" = 0 ]; then
  # ⚠️ APK 位置:打包时可能落在 android/apk/ 或 ../assets/android-engine/apk/
#   (2026-09-26 修:原来只找 $HERE/apk/ ⇒ 便携包里没有 ⇒ install.sh 直接失败)
APK=""
for c in "$HERE/apk/canto-tts-engine.apk" \
         "$HERE/../assets/android-engine/apk/canto-tts-engine.apk" \
         ; do
  [ -f "$c" ] && { APK="$c"; break; }
done
if [ -z "$APK" ]; then
  echo "  ❌ 找不到 canto-tts-engine.apk" >&2
  echo "     找过:$HERE/apk/ · $HERE/../assets/android-engine/apk/" >&2
  echo "     先跑 ./pack.sh 构建 APK,或设 CANTO_APK=<路径>" >&2
  exit 1
fi
APK="${CANTO_APK:-$APK}"
  [ -f "$APK" ] || { echo "❌ 缺 APK: $APK" >&2; exit 1; }
  say "装 APK($(du -h "$APK" | cut -f1))…"
  run "$AND install -r '$APK'"
fi

# ③ 模型(GPU 无关,纯 CPU;684MB)
# ⚠️ 模型源:【包内优先】(整包可迁移的关键)—— 2026-09-26 修
#   原来只认 /var/lib/canto-tts/model(本机安装位)
#   ⇒ 搬到另一台设备时那个路径不存在 ⇒ 迁移失败
#   ⇒ 正解:先找包内的 models/*,再回退到本机安装位
MODEL_SRC="${CANTO_MODEL_SRC:-}"
if [ -z "$MODEL_SRC" ]; then
  # ⚠️ 顺序 = 优先级:【包内 → 本机安装位】
  #   包内实际在 models/canto-tts-nano/(不是 models/ 直接放)
  for c in "$HERE/../models/canto-tts-nano" \
           "$HERE/models/canto-tts-nano" \
           "$HERE/../models" \
           "$HERE/models" \
           "/var/lib/canto-tts/model"; do
    # 判据:这个目录里得有 MOSS-TTS-Nano-cantophon-ONNX
    if [ -d "$c/MOSS-TTS-Nano-cantophon-ONNX" ]; then MODEL_SRC="$(cd "$c" && pwd)"; break; fi
  done
fi
if [ -z "$MODEL_SRC" ] || [ ! -d "$MODEL_SRC/MOSS-TTS-Nano-cantophon-ONNX" ]; then
  echo "  ❌ 找不到模型目录(MOSS-TTS-Nano-cantophon-ONNX)" >&2
  echo "     找过:包内 models/ · /var/lib/canto-tts/model" >&2
  echo "     可用 CANTO_MODEL_SRC=<路径> 指定" >&2
  exit 1
fi
say "推模型($MODEL_SRC)…"
PKG=canto.tts
# ⚠️⚠️ 路径选择的【关键决策】(2026-09-24 实测定案):
#   ① 最终位置必须是【内部存储】:getFilesDir()/models/canto
#      理由:从 /sdcard(FUSE)建 4 个 session 要 10985ms,内部只要 3403ms ⇒ 【快 3.2 倍】
#   ② 但 adb 推不到内部存储(除非 root 且 chown/chcon,而 chcon 的 MLS 类别因设备而异,
#      写死在脚本里【必然有一天失效】)—— 实测踩过:su 建的文件缺 App 的 MLS 类别 ⇒ EACCES
#   ③ ⇒ 【两段式】:adb 推到 /sdcard(零权限),然后让 App 【自己】拷到内部
#      · App 拷的文件【自带正确的 uid + SELinux 上下文】⇒ 不需要 root、不需要 chcon
#      · 这也是 subagent 的建议(首启拷贝)
#   ⇒ 所以本脚本推 /sdcard;CantoTtsService 首启时自动搬到内部。
STAGE="/sdcard/Android/data/$PKG/files/models/canto"
# 内部存储路径(守护要读这里)
STAGE_INTERNAL="/data/data/$PKG/files/models/canto"
run "$AND shell mkdir -p '$STAGE'"
say "  (684MB,按 adb push 速度可能要几分钟)"
for d in MOSS-TTS-Nano-cantophon-ONNX MOSS-Audio-Tokenizer-Nano-ONNX; do
  run "$AND push '$MODEL_SRC/$d' '$STAGE/'"
done
for f in tokenizer.model added_tokens.json browser_poc_manifest.json; do
  [ -f "$MODEL_SRC/$f" ] && run "$AND push '$MODEL_SRC/$f' '$STAGE/'"
done
# 扁平化:代码期望 <modelDir>/tts/*.onnx 与 <modelDir>/codec/*.onnx
# ⚠️ /sdcard 是 FUSE【不支持软链】⇒ 不在这里建;App 搬到内部后才建


# ④ G2P + 小资产
say "推 G2P 与资产…"
ASSETS="$HERE/../assets"
run "$AND push '$ASSETS/android-g2p/libcanto_g2p.so' '$STAGE/'"
run "$AND push '$ASSETS/android-engine/jni/libcanto_g2p_jni.so' '$STAGE/'"
run "$AND push '$ASSETS/android-g2p/g2p-data-runtime.tar.gz' '/data/local/tmp/'"
run "$AND shell tar -xzf /data/local/tmp/g2p-data-runtime.tar.gz -C '$STAGE/'"
run "$AND push '$ASSETS/android-p2y/p2y-rules.tsv' '$STAGE/'"
run "$AND push '$ASSETS/android-tok/syllable-ids.tsv' '$STAGE/'"
# ⚠️⚠️ 2026-09-26 补(整包 install 全周期实测发现漏推):
#   · voicebank/ —— 音色库(7 个 json)。漏了 ⇒ 守护 READY voices=0 ⇒ 选不了音色
#     漏了 ⇒ 守护用内置默认;verify 也会报缺失
if [ -d "$ASSETS/android-engine/voicebank" ]; then
  run "$AND push '$ASSETS/android-engine/voicebank' '$STAGE/'"
else
  say "  ⚠️ 包里没有 voicebank/ ⇒ 音色库会缺失(守护 voices=0)"
fi
# ⚠️ 2026-09-28:多语言支持 —— 把 SentencePiece 词表推进模型目录
#    (基础版模型 base-mandarin/ 是可选的:有它才能切普通话/英文)
if [ -f "$ASSETS/android-tok/sp-vocab-16384.tsv" ]; then
  run "$AND push '$ASSETS/android-tok/sp-vocab-16384.tsv' '$STAGE/'"
fi
if [ -d "$MODELS/base-mandarin" ]; then
  say "发现包内 base-mandarin/ ⇒ 一并推上去(普通话/英文用)"
  run "$AND push '$MODELS/base-mandarin' '$STAGE/'"
else
  say "  (包内没有 base-mandarin/ ⇒ 只有粤语;要普通话见 README 的下载说明)"
fi
if [ -f "$HERE/tuning.properties" ]; then
  run "$AND push '$HERE/tuning.properties' '$STAGE/'"
else
  say "  ⚠️ 包里没有 tuning.properties ⇒ 守护会用内置默认"
fi

# ⑤ 属主/上下文:【不需要手工 chown/chcon】
say "属主与 SELinux 上下文:由 App 首启拷贝时自动获得正确值(不需要 root)"
say "  (实测:su 建的文件缺 App 的 MLS 类别 ⇒ ORT 报 system error number 13 ⇒ EACCES)"
say "  (App 自己拷的文件自带正确 uid + 上下文 ⇒ 这是唯一稳的做法)"

# ⑤.5 让 App 能【读】刚推到 /sdcard 的模型
#     ⚠️⚠️ 2026-09-26 实测发现的关键一环:
#       adb push 创建的文件/目录属主是【shell】,权限 drwxrws---(others 无权限)
#       ⇒ App(u0_aXXXX)读不到 ⇒ CantoModelSetup.looksComplete(外部) = false
#       ⇒ 【首启自拷永远不触发】⇒ 内部一直空 ⇒ 守护起不来、verify 全 0
#     ⇒ 必须放开 others 读权限(a+rX)。实测加了这一条,自拷立刻成功。
#     ⚠️ 用 a+rX 而不是 a+rwX:只读就够(App 只需读),更安全。
say "放开暂存模型的读权限(让 App 能读到,否则自拷不会触发)…"
if [ "$DRY" = 1 ]; then
  say "  [dry-run] 会:chmod -R a+rX $STAGE"
else
  $AND shell "su -c 'chmod -R a+rX $STAGE 2>/dev/null'" >/dev/null 2>&1
  say "  ✅ 已 chmod -R a+rX"
fi

# ⑥ ⚠️ 2026-09-27:守护与开机自启【已彻底移除】——
#   实测无 root 时 App 自己跑推理 [S7]1266ms/[S9]1811ms,与 root 守护(1270/1745)几乎一样
#   ⇒ 不再需要 root。详见 VOICE.md §9 与 android/README.md。
#   (App 侧靠【常驻 overlay】避免被 ColorOS virtualFreeze 冻)

# ⑦ 设为默认 TTS 引擎
say "设为默认 TTS 引擎…"
run "$AND shell settings put secure tts_default_synth $PKG"

say ""
say "===== 部署完成 ====="
say "验证: ./verify.sh --target $TARGET"
