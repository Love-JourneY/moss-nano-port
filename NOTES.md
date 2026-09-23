# NOTES.md —— canto-tts 模块维护笔记(踩坑全在这)

> ⚠️ **本文件的网络地址已匿名化**(2026-09-28 开源准备):
> 原文里是具体 IP,现替换为 `<平板IP>` / `<宿主IP>` 等占位符。
> 逻辑与结论【一字未改】—— 只动地址。

> **这份笔记必须随包走。** 换机器后不靠翻旧会话也能接手 —— 下面是真踩过的坑,
> 每条都写了「现象 → 根因 → 解法」,不是"注意事项"式的空话。
>
> 模块版本:`0.1.4-module.1` · 最后更新:2026-09-22

> 📚 **想搞懂"这东西先不先进 / 为什么好 / 音色怎么换 / 能不能更快 / 开源世界走到哪一步"**
> —— 看 **`~/Documents/system-maintenance/notes/2026-09-22-语音模型技术路线知识库.md`**。
> 那份是**技术路线知识库(带时间戳快照)**,回答的是"为什么"和"往哪走";
> 本文件回答的是"**我们实际踩过哪些坑、数字是多少**"。两份**互补,不是重复**。
> ⚠️ 那份文档里记了 **3 处与本文旧结论冲突的更正**(sherpa 新架构 / MOSS v1.5 原生粤语 /
> 官方 `duration_filter`)—— **看本文 §11.3 的反向结论时请一并对照**。

---

## 0. 一句话现状

| 机器 | 状态 | 说明 |
|---|---|---|
| 笔记本 黑仔(bbSoyArch / x86_64 / Arch) | ✅ **已部署并验收 12/12** | `vsay-canto` 可用,能合成能播 |
| 平板 格仔(OnePlus Pad 2 / aarch64 / Debian 13 chroot) | ⚠️ **合成 11/12,播放受限** | 装得上、合成正常;播放被 Android 音频 HAL 挡住(§7) |
| 机仔(bbStation / Fedora 43) | ❌ **不可达** | 主板/电源疑似故障(无 POST),`peers.json` 标 `suspended`。实测 `ping <机仔IP>` / `ping <机仔IP-备用>` 全丢包,ARP 为 `FAILED` |

### ⭐ 2026-09-22 晚:本机默认朗读已切到 canto-tts(4x 加速同时落位)

| 事项 | 结果 |
|---|---|
| **默认朗读引擎** | 笔记本 `/usr/local/bin/vsay` **默认 → canto-tts(男声·729MB)**。~~旧 qwen3 女声(2.2GB)保留但降级为可选~~ ⇒ ⚠️ **2026-09-22 更晚:qwen3 已【退役归档】**(见 §12.4) |
| **4x 加速落位** | `/opt/canto-tts/lib/tts_stream.py` 已落位;`vsay-canto` 接上**三级回退**(L1 常驻 / L2 一次性 / L3 旧逐段)。实测**笔记本 2 段 10751→5091 ms(2.1x 中位,最快 3.1x)、3 段 15619→4890 ms(3.2x 中位,最快 4.4x)**。详见 §11.4、§11.8 |
| **顺手修的真 bug** | `vsay` 旧版只认 `-n`,而 DSH 的 🔊 服务写死 `vsay -m zhll <正文>` ⇒ **每句话都被念出「m zhll」前缀**(实测取证)。已修,详见 §11.9 |
| **2.2GB 去留** | ✅ **已执行:退役归档(不是删除)** —— Nija 拍板「模型太大了,生产环境用不起」⇒ **canto-tts 成为唯一模型**。详见 **§12.4** |

⚠️ **事实澄清(别误解)**:canto-tts 是**男声**(F0 中位 102.3Hz;男声范围 85~155Hz),
平板现用的 `vits-cantonese-hf-xiaomaiiwn` 是**女声**(234.6Hz)。
canto-tts **只有一个固化音色,不能选**(官方 README:「單一 default voice —— ONNX 路線暫時未支援 voice cloning、冇 voice 揀」)。
⇒ **部署它是"多一个男声选择",不是"提升音色"。**
⇒ 但 Nija 实测后的判断是:canto-tts 的效果**比 2.2GB 那个大模型还好**。

---

## 1. ⚠️ 最大的坑:ONNX external-data 符号链接校验

**现象**
```
Error: [ONNXRuntimeError] : 1 : FAIL : External data path validation failed for
initializer: core.model.transformer.wte.weight. Error: External data path escapes
model directory. External data path: "moss_tts_global_shared.data" resolved path:
"~/.cache/huggingface/hub/blobs/b0/b051...d3b0" allowed directory:
"~/.cache/huggingface/hub/blobs/54"
```

**根因**
HuggingFace 缓存目录 `snapshots/<sha>/` **里面全是符号链接**,指向 `blobs/<sha>/`。
ONNX 载入模型时会做 external-data 路径安全校验:**只允许外部数据文件待在模型文件所在目录**。
模型文件 `*.onnx` 解析后落在 `blobs/54/`,而它引用的 `moss_tts_global_shared.data`
解析后落在 `blobs/b0/` ⇒ **判定为"逃逸出模型目录",直接拒绝加载**。

**解法(必须)**
把模型**实体化**到一个平坦目录 —— `cp -rL` 解引用所有符号链接:

```bash
SNAP=$(ls -d ~/.cache/huggingface/hub/models--typangaa--canto-tts-nano/snapshots/*/ | head -1)
cp -rL "$SNAP" /var/lib/canto-tts/model      # ⚠️ -L 是关键,不能省
```

`install.sh` 的第 ④ 步做的就是这件事。`verify.sh` 有一条硬检查
「模型目录无符号链接(ONNX 硬要求)」防止有人图省事直接软链缓存目录。

**副作用(要知道)**:`ls -la` 看 HF 缓存只有 ~86MB,实体化后是 **729MB**。
不是下多了,是 `du` 不跟随符号链接、**少算了**。真实体积以实体化目录为准。

---

## 2. ⚠️ 平板 chroot:Debian 的 python3 不带 ensurepip

**现象**
```
$ python3 -m venv /opt/x
... No module named ensurepip
$ python3 -m ensurepip --version
/usr/bin/python3: No module named ensurepip
$ python3 -m pip --version
No module named pip
```

**根因**
Debian 把 `ensurepip`/`pip` 拆成独立包(`python3-venv`、`python3-pip`),默认不装。
这不是坏,是 Debian 的既定做法。

**解法**
```bash
apt-get install -y python3-venv python3-pip
```
两个都是**官方 Debian 仓库包** ⇒ 按八条硬要求的第②条,官方包允许走 apt;
但为了**完全离线**也把 arm64 的 `.deb` 归档进了 `pkg/deb-aarch64/`(49 个,24MB),
`install.sh` 会优先用归档的 `.deb`(走 `dpkg -i`),没有再联网 apt。

拉 `.deb` 的办法(在平板 chroot 里):
```bash
PKGS=$(apt-cache depends --recurse --no-recommends --no-suggests --no-conflicts \
       --no-breaks --no-replaces --no-enhances python3-venv python3-pip \
       | grep -E '^\w' | sort -u)
apt-get download $PKGS
```
⚠️ 拉到 chroot 的 `/tmp` 后,`adb pull` **读不到**(chroot 的 `/tmp` 属主是 root,
Android 的 shell 用户没权限)。必须先用 `su -c 'cp ... /data/local/tmp/xxx && chmod 644 ...'`
中转一次,再 `adb pull`。

---

## 3. ⚠️ chroot 里必须显式设 PATH

**现象**:`adb shell "su -c 'chroot /data/local/linux/debian /bin/bash -c \"python3 -V\"'"`
⇒ `python3: command not found`(但 python3 明明装着)。

**根因**:Android 侧 `su` 传进去的环境没有 Debian 的 PATH,chroot 里 `PATH` 是空的/残缺。

**解法**:每次进 chroot 都显式 export:
```bash
export PATH=/usr/local/bin:/usr/bin:/bin
```
本模块的 `ct_adbx()`(`lib/common.sh`)已经把这句焊死在里面。

---

## 4. ⚠️ 多层引号地狱 —— 改用 stdin 送脚本

**现象**:想通过 `adb shell "su -c 'chroot ... bash -c \"...\"'"` 跑一段带引号的脚本,
里面再嵌 `python3 -c "..."`,`$`、`\"`、`'` 互相打架,报
`syntax error: unexpected '\"'` 或 `can't create /dev/tcp/...: No such file or directory`
(后半句说明整个脚本被 Android 的 `sh` 吃掉了,不是 bash 在执行)。

**解法(本模块采用)**:把脚本从 **stdin** 送进去,不塞进引号:
```bash
adb -s "$TGT" shell "su -c 'chroot /data/local/linux/debian /bin/bash -c \"export PATH=...; exec /bin/bash -s\"'" <<'EOF'
...任意复杂的脚本,想怎么写怎么写...
EOF
```

---

## 5. ⚠️ adb 三个目标会互相掉线 —— 必须自动挑

三个目标:`<平板IP-备用>:5555`(VPN)、`<平板IP>:5555`(Wi-Fi)、`939aa62d`(USB)。
实测**同时只有一个活着**,而且 TCP 掉线后 adb 不会自动回来。

**解法**:`ct_pick_adb()` —— 逐个 `adb -s X shell echo ok` 试;全挂就先 `adb connect` 再试一轮。
**写脚本一律用它,不要把某个 IP 硬编码进逻辑。**

⚠️ 该设备还有 `<设备 IP>` 之外的地址;按 MAC `<你的设备 MAC>` 解析当前 IP 用(见 android/device.mac.example)
`~/Documents/repo/voice-tts/backend/macip`(遵循 2026-09-22「设备寻址纪律:MAC 优先」)。

### ⚠️⚠️ 探活的 adb 会【吃掉调用者的 stdin】(本模块最隐蔽的一个坑)

**现象**:本机 `./verify.sh` 12 项全过;**平板 `./verify.sh --target tablet` 突然 11 项全 FAIL**,
而且失败信息全是空的(`CLI=`、`版本=`、`符号链接数=`)—— 看着像"模块全炸了"。

**根因**:verify 是这么取事实的 ——
```bash
FACTS="$(target_run <<EOF
...要送到平板去跑的脚本...
EOF
)"
```
而 `target_run` 内部先调用 `ct_pick_adb()`,它跑
`timeout 8 adb -s "$t" shell echo ok >/dev/null 2>&1`。
**`adb shell` 会读自己的 stdin** —— 虽然 stdout 重定向到 /dev/null 了,
**stdin 没有**。于是这个"探活"的 adb 把 heredoc 整个吞掉;
等真正要送脚本的 `ct_adbx` 拿到 stdin 时,已经是空的 ⇒ 平板一行都没收到。

**解法**:所有探活/辅助的 adb 调用一律加 `</dev/null`;
`ct_adbx` 也只在**确实有参数**时才去读 stdin。两处都已修。

**判据(记住这个)**:凡"本机全过、对端全空" ⇒ **先怀疑 stdin 被谁吃了**,不要怀疑模块。

---

## 5.5 ⚠️ 安装介质别拷进 /opt(pkg/ 和 models/ 是"搬运工具",不是"程序")

**现象**:`/opt/canto-tts` 一度 **853MB** —— 因为 `install.sh` 早期用
`cp -a "$root/." "$CT_OPT_DIR/"` 把整个仓库(含 102MB 轮子 + 729MB 模型)全拷了过去。

**为什么错**:①徒占 ~850MB ②违反 FHS —— `/opt` 是**程序位**,模型属"数据",该在 `/var/lib`。

**解法**:拷进 `/opt` 时用 tar 排除 `pkg/ models/ dist/ .git`。
⚠️ 但**平板要单独处理**:轮到平板时 `$root` 就是 `/opt/canto-tts`(安装脚本自己在那儿),
`[ "$root" != "$CT_OPT_DIR" ]` 成立不了 ⇒ 那步整个跳过 ⇒ 推过去的 `models/` 会永远留着。
所以 `install_tablet` 末尾要**显式 `rm -rf $CT_OPT_DIR/{pkg,models}`**。

**顺带做了幂等优化**:平板重装前先问目标"引擎和模型装好没",装好就**不重推**
(推一次模型要 ~95 秒)。判据用"实体化后的真文件存在",不是"目录存在"。
修完后:平板重装从 **~2 分钟降到 2 秒**。

---

## 5.6 ⚠️ 播放器探测不能只看"二进制在不在"

**现象**:平板 chroot 里装了 `pw-play`,但它**没有 PipeWire 守护** ⇒
探测"通过",播放必失败,而错误信息看不出为什么。

**解法**:`pick_player` 要同时确认**服务在**——
Pulse 看 `$PULSE_SERVER` 或 `/run/user/*/pulse/native` 存在,
PipeWire 看 `$XDG_RUNTIME_DIR/pipewire-0` 存在;都不在才回落到 `aplay`。

`verify.sh` 同理:要**逐个播放器真试一遍**,不能"探测到哪个就报哪个"。


---

## 6. ⚠️ 本机提权:走 sbrun,重入要用绝对路径 + 完整参数

**两个连在一起的坑**:

1. **`$0` 是相对路径** —— `./install.sh` 里 `exec sbrun -- "$0" "$@"`,
   sbrun 换 cwd 后找不到文件。⇒ 重入前先算绝对路径(`lib/common.sh` 已修)。

2. **`while` 循环把 `$@` 吃光了** —— 参数解析完再 `exec ... "$@"`,传过去的是**空**。
   ⇒ 文件开头必须先 `ORIG_ARGS=("$@")` 存下来,提权时用 `"${ORIG_ARGS[@]}"`。
   (2026-09-22 实测:不存就会出现"提权后什么参数都没了,直接跑默认 local"。)

另外:`--dry-run` 时**不要**提权(否则为了"什么都不做"还去弹一次批准,很烦)。

---

## 7. 平板部署记录(aarch64)

### 关键风险点探路结果(先探路,再动手)

| 依赖 | aarch64 有轮子? | 结论 |
|---|---|---|
| **`canto-hk-g2p` 2.6.1** | ✅ `cp38-abi3-manylinux_2_17_aarch64.manylinux2014_aarch64.whl` | **最担心的点,结果没事** |
| `onnxruntime` 1.30.0 | ✅ `cp313-cp313-manylinux_2_28_aarch64.whl` | 平板 Python 3.13 ⇒ 正好有 |
| `sentencepiece` 0.2.2 | ✅ `cp313-cp313-manylinux_2_27_aarch64` | |
| `numpy` 2.5.3 | ✅ `cp313-cp313-manylinux_2_27_aarch64` | |
| `soundfile` / `cffi` / `protobuf` / `pyyaml` | ✅ | |

**关于 `canto-hk-g2p`**:它确实是 **Rust 编译产物**(PyPI 元数据里 dev 依赖 `maturin>=1.4`,
说明用 maturin 建的),**但上游发布了 aarch64 预编译轮子** ⇒ 不用现场编译。
⚠️ **这是运气好,不是理所当然** —— 如果上游哪天撤了这个轮子,就必须在平板上装 Rust 工具链
现场编译(会很慢,且要几百 MB)。**升级前先查 PyPI 有没有 aarch64 轮子。**

**关于 `manylinux_2_28`**:要求 glibc ≥ 2.28。平板是 Debian 13 (trixie),glibc **2.41** ✅。
如果哪天换到老的 Debian(如 10 buster,glibc 2.28 边缘),要重新核。

### 平板环境事实(实测)

```
aarch64 · Debian GNU/Linux 13 (trixie) · glibc 2.41 · Python 3.13.5
chroot: /data/local/linux/debian    磁盘: 402GB 空闲
音频: /dev/snd 里有 controlC0 + pcmC0D0p 等;chroot 里只有 pw-play
```
⇒ ⚠️ **chroot 内没有 PulseAudio/PipeWire 守护**,`pw-play` 装了也没用;
`vsay-canto` 的播放器探测必须能落到 `aplay`(裸 ALSA)。若 `aplay` 也没有,
需要 `apt-get install -y alsa-utils`(实测:平板上已装,`aplay -l` 能看到
`card 0: sunmtpsndcard … CODEC_DMA-LPAIF_RXTX-RX-0 …`)。

### ✅ 部署结果:装得上,合成正常
```
25 个 aarch64 轮子全部离线装好(canto-hk-g2p / onnxruntime / numpy / sentencepiece …)
合成实测:2.40 秒音频 / 48kHz / 立体声 / 460844 字节 / 耗时 6.6 秒
验收:./verify.sh --target tablet  ⇒  11 通过 / 1 失败(失败项只有"实际播放")
足迹:/opt/canto-tts 110K + venv 191M + /var/lib/canto-tts 730M ≈ 921MB
```

### ❌ 播放:chroot 驱动不了声卡(三层证据)

| 尝试 | 结果 |
|---|---|
| `aplay`(默认设备) | `audio open error: Host is down` |
| `aplay -D hw:0,0 / plughw:0,0 / 0,1 / 0,2` | **全部** `pcm_write: write error: Invalid argument` |
| 把 `/dev/snd/*` 放开到 666 再试 | 仍然 `Invalid argument`(⇒ **不是权限问题**) |
| 查内核 avc | **没有 snd 相关 SELinux 拒绝**(⇒ 不是 SELinux 拦的) |
| Android 侧 `tinyplay` | `cannot open device 0 for card 0` |

**根因**:声卡被 Android 的音频 HAL **`ohalservice.qti`** 打开并独占
(`lsof /dev/snd/controlC0` 能看到它握着一堆 fd),DSP 的路由/时钟由 HAL 配置。
chroot 是个 Linux 命名空间,**不是** Android 音频栈的一部分 ⇒ 它开得了设备节点、
但写不进去。**这不是能靠装包/改权限绕过的。**

**Android 侧也试过(同样不通)**:
- `app_process` 跑一个 `AudioTrack` 小程序(`assets/android/CantoPlay.java`):
  ```
  Aborted (RC=134)   # AndroidRuntime::startReg → FindClass → AssertNoPendingException
  ```
  试了 6 种环境变体(显式 `ANDROID_ROOT`/`ANDROID_DATA`、`env -i`、`--nice-name`、
  修 `LD_LIBRARY_PATH`、`app_process64`、`unset BOOTCLASSPATH…`)—— **一律同样崩**,
  ⇒ 判定为 **Android 16 对 `app_process` 路线的加固**,不是环境配错。
- `cmd audio` 里**没有任何播放子命令**(只有 surround / sound-dose 之类)。

**⇒ 平板要出声,唯一的路是「一个 Android App 里用 `AudioTrack`」** ——
正是 `~/Documents/repo/voice-tts/docs/平板粤语TTS-自播放方案.md` 的「方案 C」。
那属于 App 那条线,**本模块不碰**(它只负责把 wav 合成出来)。

`assets/android/CantoPlay.java` 留给那条线当**参考实现**:RIFF 分块解析、
`AudioTrack.Builder` 参数、分块写入、**播完必须 `stop()` 排空否则尾音被切** ——
坑都写在注释里了。`assets/android/README.md` 有编译命令。

### ⚠️ 不要在平板上弹 UI 测试

Nija 明确抱怨过「**老是在弹我平板上的语音输入设置**」——
之前有 agent 反复 `am start` 设置页做自检,打扰了用户。
**验证一律走命令行/日志**(`adb shell` + chroot 内跑 + 看 wav/ffprobe 输出)。
若确实必须弹 UI,**先问上级 agent,由他决定要不要打扰用户**。
本次全程**零 UI**:没跑过一次 `am start`,`vsay-canto` 的失败信息也是打在 stderr。

---

## 8. 其它纪律备忘

- **不要动** `voice-tts` / `android-voice-ime` 的现有代码(那是已验收的其它线)。
- **`.bak` 备份不能放在 Android 的 `res/` 目录** —— AAPT 会 BUILD FAILED(整个 APK 编译失败)。
- **禁止 `pgrep -f` / `pkill -f` 匹配可能出现在自己命令行里的模式** —— 会把自己的 shell 杀掉。
- 模块**不写** `/etc/profile.d/`、**不改** `~/.bashrc`、**不建** systemd 单元 —— 零全局污染。
- 平板的 `/run` 不存在 ⇒ pid/socket 一律用 `/data/local/linux/`(本模块不需要常驻进程,故不涉及)。
- **本机提权一律走 `sbrun`**(不要 sudo/pkexec)。
  ⚠️ 每次 `install.sh` / `uninstall.sh` 都会**弹一次平板批准** —— 这是设计如此,不是 bug。
  做 `--dry-run` 时**不提权**(否则为"什么都不做"还弹一次,很烦;已修)。

---

## 8.5 打包 / 迁移(实测过)

```bash
./pack.sh          # 整包 501MB(tar.zst,含双架构 50 个轮子 + 729MB 模型 + 全部脚本文档)
./pack.sh --slim   # 瘦身 78MB(只本机架构轮子,不含模型;目标机首次装需联网)
```

**实测过的"整包可迁移"闭环**(不是"看着像好了"):
```bash
tar --zstd -xf dist/canto-tts-module-*-x86_64.tar.zst -C /tmp/restore   # 1.0 秒
cd /tmp/restore/canto-tts && ./install.sh --dry-run                      # 全流程自洽
```
`pack.sh` 自带两道自检:**打包前**查五个必带文件(缺一个就拒绝打包)、
**打包后** `tar -tf` 列一遍(防"打了一半")。

⚠️ 注意权限:`README.md`/`NOTES.md` 要 644、脚本要 755。
（生成文件默认可能是 600 —— 那样别人解开包读不到文档。已修。）

---

## 9. 升级 / 排障速查

```bash
# 换模型版本(先保住旧的)
sudo mv /var/lib/canto-tts/model /var/lib/canto-tts/model.old
HF_ENDPOINT=https://hf-mirror.com /opt/canto-tts-venv/bin/python - <<'PY'
from huggingface_hub import snapshot_download
print(snapshot_download("typangaa/canto-tts-nano"))
PY
# ⚠️ 别忘 -L
sudo cp -rL <上一步打印的路径> /var/lib/canto-tts/model

# 只验合成(不播),快
/opt/canto-tts-venv/bin/canto-tts synthesize "測試。" -o /tmp/t.wav \
    --checkpoint /var/lib/canto-tts/model && ffprobe -v error \
    -show_entries format=duration -of default=nw=1 /tmp/t.wav

# 全量验收
cd ~/Documents/repo/canto-tts && ./verify.sh --play
```

**常见故障对照**

| 症状 | 大概率原因 | 处置 |
|---|---|---|
| `External data path escapes model directory` | 模型目录是软链/符号链接 | `cp -rL` 重新实体化(§1) |
| `python3: command not found`(平板) | chroot 没设 PATH | `export PATH=/usr/local/bin:/usr/bin:/bin`(§3) |
| `No module named pip` / `ensurepip` | Debian 拆包 | `apt-get install python3-venv python3-pip`(§2) |
| **本机全过、平板全空**(失败信息也是空的) | **探活 adb 把 heredoc 从 stdin 吃了** | `</dev/null`(§5 的加粗段) |
| 合成成功但没声音(平板) | **Android 音频 HAL 独占声卡**(绕不过) | chroot 无解;走 App 的 `AudioTrack`(§7) |
| adb 全部 `device not found` | 三个目标互掉线 | `ct_pick_adb()` 或手工 `adb connect`(§5) |
| 提权后参数丢了 | `while` 吃掉了 `$@` | 用 `ORIG_ARGS=("$@")`(§6) |
| `/opt/canto-tts` 有 800MB+ | 介质(pkg/models)被拷进程序位 | 装完清掉;tar 里排除(§5.5) |
| 重装平板要等 2 分钟 | 每次重推 729MB 模型 | 幂等跳过(§5.5,已修:降到 2 秒) |
| 探到播放器却播不出 | 只看二进制、没看守护服务 | 检查 Pulse/PipeWire socket(§5.6) |

---

## 10. 已知未做 / 下一步

- [ ] **机仔(bbStation)未部署** —— 机器坏着(无 POST),**实测不可达**:
      `ping <机仔IP>` / `ping <机仔IP-备用>` 全丢包,`ip neigh` 显 `FAILED`,
      `peers.json` 标 `suspended`。**如实报告,没有假装部署。**
      修好后:`scp` 整包过去 → `./install.sh`(Fedora 43,glibc/轮子需重核:见 §7 manylinux 说明)。
- [ ] **平板播放** —— chroot 无解(§7)。要给平板加"能出声的粤语男声",
      必须把 `assets/android/CantoPlay.java` 合进「方案 C」那个 App。**本模块到此为止。**
- [ ] **`--quality best_of_n` 未启用** —— 需要 `faster-whisper`(重依赖),本模块未归档其轮子。
      要启用得单独在 `pkg/` 里补 `faster-whisper` + `ctranslate2` + `av` 的 aarch64 轮子。
- [ ] **未做系统级"开机可用"验证** —— 本模块无常驻进程,`/usr/local/bin` 的绝对路径命令天然持久,
      无需 systemd/service.d(KernelSU 那套不适用)。
- [ ] **`uninstall.sh` 未在平板上实测过** —— 本机跑过 `--dry-run`;
      平板路径写了但**没真卸**(怕把已装好的平板弄坏)。下次需要腾空间时先验它。

---

## 11. 🚀 性能攻关:实测瓶颈与"减繁重 / 超级加速"路线(2026-09-22)

> 背景:Nija 抱怨平板"合成 6000ms",问「能不能打包成 sherpa-onnx 或超级加速、
> 减少繁重的后端」。本节是**实测答案**,含**四条反向结论**(别再去走的路)。

### 11.1 一句话结论

**平板的"慢"几乎全是【每段重启进程、把 729MB 模型重新加载一遍】造成的,不是推理慢。**
⇒ 已在 `lib/tts_stream.py` 做出原型并实测:**平板 2 段 9240ms → 2277ms(4.06x)**,
**零质量风险、零 C++、零量化。**

### 11.2 实测:时间到底花在哪

**① 单次合成内部(笔记本,12 核,7 字 → 2.32s 音频,29 帧,总计 1335ms)**

| 阶段 | 调用 | 累计 | 占比 |
|---|---|---|---|
| `local_fixed_sampled_frame` | 30 | 607 ms | 46% |
| `decode_step`(全局 12 层) | 29 | 477 ms | 36% |
| `prefill` | 1 | 142 ms | 11% |
| `codec_decode` | 1 | 99 ms | 7% |
| **ORT 合计** | | **1324 ms** | **99.2% of wall** |

- **Python 胶水只占 ≈1%(11ms);G2P 13.7ms;tokenizer 0.2ms。**
- ⇒ 「C++ 重写能加速」**不成立**:能省的就是那 1%。

**② 端到端(平板 chroot,8 核)——真正的瓶颈在这**

| 测试 | 耗时 |
|---|---|
| 单段,独立进程 | 4512 ms |
| 紧接着再跑一次(**仍是新进程**) | 4484 ms ← 又付一遍加载 |
| 同进程拆解:加载 3440 / 第1段 950 / 第2段 1085 | 5475 ms |
| `vsay-canto` 11 字(切 **2 段**) | **9481 ms** |
| `vsay-canto` 3 段 | **13939 ms** |
| 13 字(单段) | 5044 ms |

⛔ **纠正 `bin/vsay-canto` 第 107 行的错误注释**:
原文「首次调用要加载 ONNX(数秒);之后每段很快」——**错**。
`"$CT" synthesize` 每段都是**独立进程**,8 个 ONNX session 全部重载。
⇒ **一次单段调用里约 76% 是模型加载,不是推理。**

笔记本同构:独立进程 5087 / 4717 ms;同进程 2 段 6550 ms ⇒ **省 3881 ms/段**。

### 11.3 ⛔ 四条反向结论(已实测,别再去走)

| 路线 | 实测结果 | 判决 |
|---|---|---|
| **打包成 sherpa-onnx** | sherpa 只吃 VITS/Matcha/Kokoro/Piper 等**非自回归**架构;canto-tts 是 **GPT-2 自回归 + 独立 audio tokenizer** | ❌ 架构不兼容(除非上游提 PR 加架构,大工程) |
| **自己写 C++ 推理器** | Python 只占单次推理 **1%**;ORT 占 99.2% | ❌ 速度收益 ~1%,不值。**真正价值只是 footprint**(干掉 453MB venv + 42MiB 无用权重),不是速度 |
| **ORT 线程调优** | 见下表,**越多越慢**;且 CLI 无 `--threads`,SDK 默认 4 **已是最优** | ❌ 死路,别再花时间 |
| **int8 量化** | 每帧 1.36x,体积只降 2.5x,**且音色有漂移** | ⚠️ 见 §11.5,建议先不上 |

**线程扫描(笔记本,ms/帧;batch=1 的 matvec 是内存带宽瓶颈,多线程互相踩):**

| threads | 2 | **4** | 6 | 8 | 12 |
|---|---|---|---|---|---|
| fp32 | 53.4 | **43.4** | 57.9 | 100.3 | 191.3 |
| int8 | 32.8 | **32.0** | 36.9 | 73.5 | 144.1 |

⇒ **线程默认 4 已经最优;调到 12 反而慢 4.4 倍。**

> ⚠️ 另一条被证伪的假设:**`decode_step` 不能替代 `prefill`**(想借此省掉 421MiB 权重副本)。
> 同输入下 max abs diff **13.05(相对 107%)** —— 是两个真正不同的图。**别删 prefill。**

### 11.4 ✅ 真正的杠杆:`lib/tts_stream.py`(已实测)

> ⚠️ 本节是**原型阶段**的实测;落位后的最终数字见 **§11.8**。

一次加载、多段合成;每段一好就 `SEG <n> <path>` 并 flush ⇒ 调用方仍可边合成边播。

| 场景 | 旧 `vsay-canto` | one-shot | **常驻守护(serve+client)** |
|---|---|---|---|
| 平板 2 段 | 9240 ms | 5833 ms (1.58x) | **2277 ms (4.06x)** |
| 平板 3 段 | 13939 ms | 6867 ms (2.03x) | **3546 ms (3.93x)** |
| 笔记本 2 段 | 10723 ms | 6851 ms (1.57x) | **3153 ms (3.40x)** |

- **每多一段:旧路径 +4.5s,one-shot 只 +1.1s,守护只 +1.2s。**
- 守护热态下单段 ≈ **1.27s**(笔记本)。
- **代价**:常驻 RSS 实测 **1.53 GiB**(平板)/ 1.46 GiB(笔记本)。
  平板 15.8GB 内存、可用 5.6GB ⇒ 可行;故默认**不常驻**,用 `--idle-timeout` 空闲自动退出还内存。

**三种模式**(`--outdir` 一次性 / `--serve` 常驻 / `--client` 转发,`--client` 连不上时返回码 2
由调用方**回退到一次性**,不静默失败):

```bash
# 一次性(推荐,零额外内存)
printf '%s\n' "今日天氣幾好，" "我哋去食飯。" | \
  /opt/canto-tts-venv/bin/python /opt/canto-tts/lib/tts_stream.py --outdir /tmp/x

# 常驻(要极致延迟时;空闲 600s 自动退出)
/opt/canto-tts-venv/bin/python /opt/canto-tts/lib/tts_stream.py --serve \
  --sock /run/user/1000/canto-tts.sock --idle-timeout 600 &
```

### 11.5 ⚠️ 量化 A/B:证据与"为什么建议先不上"

**方法**:`onnxruntime.quantization.quantize_dynamic`,仅 `MatMul`、per-channel、QInt8,
写入 **/tmp 影子目录**,**生产模型 `/var/lib/canto-tts/model` 全程未触碰**。
fp32 与 int8 用**同一固定种子**(`np.random.default_rng(1234)`)⇒ 采样序列起点相同。

**体积(不如预期)**:

| 图 | fp32 | int8 |
|---|---|---|
| `moss_tts_prefill` | 421 MiB(**与 decode 共享一个 .data**) | 178.5 MiB |
| `moss_tts_decode_step` | ↑ 同一个文件 | 178.5 MiB |
| `moss_tts_local_fixed_sampled_frame` | 219 MiB | 152 MiB |
| `moss_audio_tokenizer_decode_full` | 42 MiB | 13.4 MiB |

⚠️ **量化后 prefill/decode 不再共享权重 ⇒ 复制成两份**,TTS 目录 640→509 MiB,**只省 20%**。

**速度**:每帧 43.4 → 32.0 ms = **1.36x**(远不如 §11.4 的 1.58~4.06x)。

**质量(客观)**:

| | F0 中位(3 个种子) | 均值 |
|---|---|---|
| fp32 | 112.1 / 125.0 / 129.0 Hz | 122.0 Hz |
| int8 | 123.7 / 133.3 / 141.6 Hz | **132.9 Hz** |

- 都仍在男声区(85~155Hz),但 **int8 系统性偏高约 9% 且方差更大** ⇒ **音色有漂移迹象**。
- 同输入下 `decode_step` hidden **余弦相似度仅 0.966**(平均绝对误差 0.21,幅度均值 0.79)。

> ⚠️ **方法论坑(重要)**:量化后**采样 token 一致率 = 0%**,但这**不是**质量崩了 ——
> 温度 0.9 + top-k 下,微小数值差会让轨迹**完全分岔**,等价于换了个随机种子(**采样混沌**)。
> ⇒ **不能用"帧一致率"判质量**;要判必须走 **ASR-CER / 人耳 A/B**(同上游 README 的 gate 方法)。

**结论**:Nija 对现效果**极度满意** ⇒ **不该为了 1.36x 去冒音色变化的风险**。
A/B 样本在 `docs/samples/`(`A-B对比_先原版后int8量化_seed*.wav` = 原版→1秒静音→量化版)。
量化件仍在 `/tmp/canto-probe/quant`(临时物,重启即失;**未进模块、未上生产**)。

### 11.6 复现命令要点

- 量 ONNX 契约:`onnxruntime.InferenceSession(p).get_inputs()/get_outputs()`(venv 里**没有** `onnx` 包;
  要读算子集合得 `pip install --target /tmp/qv onnx`,**别往 `/opt/canto-tts-venv` 装**(root 所有,pip 会 Permission denied)。
- ⚠️ **量化时外部数据文件不能用软链** —— onnx 校验报
  `ValidationError: ... should be stored in <path>.data, but it is a symbolic link`。必须真拷贝。
- 平板跑测:`su -c 'sh /data/local/linux/p2p-chroot.sh run "bash /tmp/xxx.sh"'`;
  ⚠️ chroot 的 **dash 没有 `time`**,用 `date +%s%N` 自己算。
- 上游给的契约(不用逆向):`browser_poc_manifest.json` + 两个 `*_browser_onnx_meta.json`
  已写明全部输入输出名、KV cache 形状、以及**烘进图里的采样参数**。

### 11.7 仍未做 / 下一步

- [x] ~~**把 `tts_stream.py` 接进 `bin/vsay-canto`**~~ —— ✅ **2026-09-22 晚已做**,三级回退,见 §11.8。
- [x] ~~**守护的生命周期**~~ —— ✅ 已定:**惰性拉起 + `--idle-timeout` 空闲自退**(默认 300s),
      **不做开机自启**(避免熵增;开机常驻 = 白占 1.6GiB)。见 §11.8。
- [ ] **量化的正式质量门** —— 要上 int8 必须先补 **ASR-CER gate**(如 Qwen3-ASR,与上游同法),
      N 次重复取分布,而不是看单条音频。
- [ ] **footprint 路线(低优先)** —— 若将来要省 453MB venv:可考虑 C++/静态链接 ORT,
      但**纯为体积、不为速度**(§11.3)。另一处可白捡:**`moss_audio_tokenizer_encode` 42 MiB 推理时用不到**
      (voice 是烘死的),但 `_create_sessions()` 里**无条件加载**它。
- [ ] **平板的 4x 加速尚未落位** —— 本轮的落位与默认切换都**只在笔记本**做。
      平板要落位必须:①`install.sh --target tablet` 把新的 `lib/tts_stream.py` + `bin/vsay-canto` 推过去,
      ②给 chroot 里的 `VSAY_CANTO_SOCK` 指到 `/data/local/linux/`(Android 没有 `/run`)。
      ⚠️ **平板播放本来就受限**(§7),收益主要体现在"合成更快",不是"能出声"。

### 11.8 ✅ 落位方案:三级回退(2026-09-22 晚实装)

`bin/vsay-canto` 现在按 **L1 → L2 → L3** 逐级降级,**任何一级失败都自动往下走,绝不静默失败**:

| 级别 | 机制 | 实测(笔记本) | 什么时候用 |
|---|---|---|---|
| **L1 常驻守护** | `tts_stream.py --serve` + `--client`,模型只加载一次 | **2 段 5091ms 中位 / 3 段 4890ms 中位** | 守护已在,或能惰性拉起 |
| **L2 一次性** | `tts_stream.py --outdir`,单次加载合成 N 段 | 2 段 6956ms / 3 段 8000ms | 守护连不上(`--client` 返回码 2) |
| **L3 旧逐段 CLI** | 原逻辑,每段一个新进程 | 2 段 10751ms / 3 段 15619ms | tts_stream.py 缺失/不可用 |

**实测对比(2026-09-22 晚,N=7 取中位,同日同时段交替跑,排除机器负载漂移)**

| 文本 | 旧版(L3)中位 | 新版 L1 中位 | 新版 L1 最快 | **提速(中位)** | 提速(最快) |
|---|---|---|---|---|---|
| 2 段(11 字) | 10751 ms | 5091 ms | 3272 ms | **2.11x** | **3.11x** |
| 3 段(17 字) | 15619 ms | 4890 ms | 3500 ms | **3.19x** | **4.35x** |

> ⚠️ **诚实说明两点**:
> ① **首次(冷启)调用不会快** —— 那一刀要付守护的模型加载(实测 3.6~5.0s)。
>    实测冷启 2 段 ≈ **6.9~7.0s**,对旧版 10751ms 仍是 **~1.5x**,但没有 4x。
>    **4x 是"第二次起"的收益**,这也正是 `--idle-timeout` 存在的理由。
> ② 上表是**同一时间窗内旧版/新版交替跑**得到的;绝对秒数会随机器负载漂
>    (实测同一配置在负载高时能到 8.3s)。**别拿不同时刻的数字互比。**
>
> 复跑命令:`cd ~/Documents/repo/canto-tts && N=7 ./bench.sh`
> (旧版就在 `backups/vsay-canto.bak-20260922`,脚本会自动拿它当对照组。)

**守护的生命周期与代价**

- **惰性拉起**:第一次调用时若 socket 不在就后台起守护;**并发双启用 `flock` 串行化**
  (否则两个调用会各起一个守护,后起的会把先起的 socket unlink 掉,先起的变成孤儿占内存)。
- **`--idle-timeout` 默认 300s**:空闲 5 分钟自动退出,把 **1.6 GiB** 还回去。
  可用 `VSAY_CANTO_IDLE` 调;`0` = 永不退出(想钉死热态时可以配合 systemd 单元)。
- **实测 RSS = 1.62 GiB**(笔记本,`/opt/canto-tts/lib/tts_stream.py --serve`)。
- **"边合成边播"仍在**:L1 逐段请求逐段回传(`SEG <n> <path>`),收到就立刻 `paplay`,
  不等整批合成完 —— 旧版修过的坑(3000 字等 20 分钟)没有退回去。

**⚠️ 落位时踩到并修掉的两个坑(别重踩)**

1. **`exec 9>lock` 失败会把"等别人启"的分支走满 30 秒**。
   socket 目录不存在时 `exec 9>"$SOCK.lock"` 报错、`locked` 仍是 0,旧写法接着
   `for … sleep 0.2` 白等 150 轮 ⇒ **一条命令跑了 38.9s 才开始降级**。
   修法:**先判 `[ -d "$(dirname "$SOCK")" ] && [ -w … ]`**,不满足直接 `return 2` 降级,
   既快又不喷 bash 的 redirection 报错(那些报错看着像崩溃,其实只是没这个目录)。
2. **等 socket 时要同时盯子进程死活** —— 否则 socket 路径写错时同样白等到超时。
   现在 `kill -0 "$dpid" || break` 一发现守护已死就立刻降级。

### 11.9 🐞 顺手修掉的真 bug:DSH 🔊 一直在念「m zhll」

**现象**:DSH 的朗读按钮每句话开头都会多念一段怪音。

**根因(实测取证,不是推测)**:`~/dev/dsh-tts/server.js:35` 写死了

```js
const child = spawn(VSAY, ['-m', MODEL, text], ...)     // MODEL = 'zhll'
```

而旧 `/usr/local/bin/vsay` **只认 `-n`**:

```bash
while [ $# -gt 0 ]; do case "$1" in -n) PUNCT=0; shift;; *) break;; esac; done
if [ $# -gt 0 ]; then TXT="$*"; fi      # ← -m zhll 原封不动进了 TXT
```

取证:

```
$ VSAY_YUE_DEADLINE=0 bash -x /usr/local/bin/vsay -m zhll "測試文字"
+ TXT='-m zhll 測試文字'
+ TXT='- m zhll測試文字。'
+ exec /usr/local/bin/vsay-yue '- m zhll測試文字。'      ← 真的念出来了
```

**修法**:新的 `vsay` **吃掉 `-m/--model` 及其参数**(忽略其值,只作兼容),
并容忍"`-m` 是最后一个参数(没有值)"的情况(否则 `shift 2` 会失败退出)。
⇒ 现在 `vsay -m zhll "你好"` 派发到 `canto` 且文本是 `你好。`,前缀没了。

> ⚠️ **这条是硬契约**:任何改写 `vsay` 的人**必须**保留对 `-m/--model` 的吞掉逻辑,
> 否则 DSH 的每一次朗读都会带着「m zhll」前缀。验收:
> `vsay -m zhll -e lite "你好"` ⇒ 引擎收到的文本必须是 `你好。`,不含 `m`。


---

## 12. 🗑️ 2.2GB 旧模型(qwen3-tts HK 粤语)去留评估

> **背景**:Nija 原话「**如果目前 2G 这个没前景,我都怀疑是否可以删了**」。
> ⚠️ **那是"征求意见",不是"命令"** ⇒ 本节只给**评估 + 建议**,**一个字节都没删**。
> 资产现状(实测):
>
> | 路径 | 体积 | 是什么 |
> |---|---|---|
> | `/var/lib/qwen3-tts-hk/` | **2.2 GB** | `qwen-talker-hk-cantonese-cc0-v0.0.1-q8_0.gguf` 1948MB + `qwen-tokenizer-12hz-q8_0.gguf` 278MB |
> | `/opt/qwen3-tts/` | **71 MB** | CUDA 编译的 `qwen3-tts-cli` + `qwen3-tts-serve` + 10 个 ggml `.so` |
> | **合计** | **≈ 2.27 GB** | |
>
> 来源:`jmtl/hk-cantonese-cc0-qwen3-tts`,**LoRA 只有 140MB**,挂在 `Qwen/Qwen3-TTS-12Hz-1.7B-Base` 上。

### 12.1 成本 / 收益对照表

| 维度 | 留(现状:保留但降级为可选) | 删(不可逆) |
|---|---|---|
| **磁盘** | 继续占 2.27 GB | 立刻回收 2.27 GB |
| **默认朗读速度** | 不受影响(默认已是 canto-tts) | 不受影响(本来就不用它) |
| **可选项** | 保留**女声**选择 —— canto-tts 是男声,性别是真实差异 | ❌ **永久失去女声**(女声版 MOSS-TTS-Nano 尚在开发,**未落地**) |
| **语气控制** | 保留 `--instruct`(唯一有情绪/语气/语调控制的引擎)。实测:同一句话 不带=7.28s / 「平稳克制」=5.44s,md5 全不同 | ❌ **永久失去**。canto-tts **没有**等价能力 |
| **版权** | 保留 **CC-0 训练数据**这条线(可商用、可再分发、可当"干净基线") | ❌ 失去这条干净基线。canto-tts 训练数据**私有**,拿不到同等法律确定性 |
| **平板** | 平板侧另有 `vits-cantonese-hf-xiaomaiiwn`(110MB 女声)可顶替一部分 | 平板仍可用那 110MB 女声 ⇒ **对平板影响很小** |
| **运维负担** | ✅ **几乎为零** —— 已不是默认,没人调它就不耗 CPU;两个目录静态躺着 | 少两个目录要登记 |
| **CUDA 版本地狱** | ✅ **只在使用时才有风险**;不用就不痛。⚠️ 但它绑 CUDA 12/13 ABI(踩过:patch 过 torchaudio) | 一并消失 |
| **恢复成本(关键!)** | —— | ⚠️ **不可逆**。要拿回来必须重下 2.2GB(HF 走 `hf-mirror.com`)+ **重新趟一遍 CUDA ABI 坑**(当时 patch 了 torchaudio、补 `.so.0` 符号链接、发现 `--max-tokens 4096` 必需)。**这不是"随时能装回来"的东西** |
| **可复查性** | 留着 ⇒ 任何时候能做"canto vs qwen3"的人耳 A/B | 删了 ⇒ 只剩 `docs/samples/` 里那几条 wav,**无法再生成新对照** |

### 12.2 建议

> ### ✅ 建议:**先留,但改成"归档不参战"状态;3 个月后再复查一次再决定删不删。**
>
> **理由(按分量排序)**:
>
> 1. **它现在的成本几乎只是磁盘,不是 CPU。** 默认已经切到 canto-tts,
>    没人调 `vsay-yue` 它就一点不算。**"占地方"和"拖慢系统"是两件事**,不该混着判。
> 2. **删了丢的是三样不可再生的东西,不是"一个慢模型"**:
>    ① 女声(而女声替代品**还没做出来**);② `--instruct` 语气控制(全屋独一份);
>    ③ CC-0 版权干净这条线。
> 3. **恢复成本高且踩过坑** —— 重下 2.2GB + 重趟 CUDA ABI(当时 patch 了 torchaudio)。
>    **为了 2.27GB 去换一次"可能要重趟 CUDA 地狱"的风险,不划算。**(本机可用空间充足,不缺这 2.27GB)
> 4. **但也不该"原样留着"** —— 它现在是个**没人管的默认依赖幻觉**。真正的动作是
>    **把它明确降级/归档**,让未来的人一眼看出"这不是默认,是备份选项"。
>
> **⚠️ 什么时候该改判为"删"**(三个条件**同时**满足就去删):
> - [ ] 女声版 MOSS-TTS-Nano **做完并验收**(女声这条线有了正式替代)
> - [ ] canto-tts 或其它引擎**补上了等价于 `--instruct` 的语气控制**
> - [ ] 连续 **3 个月**没有任何一次调用 `vsay-yue` / `vsay-yue-qwen3`
>
> **判据怎么取(客观,不靠"我感觉")**:
> `vsay` 与 `vsay-yue` 已内置**用量记账**(2026-09-22 加),写在
> `${XDG_STATE_HOME:-~/.local/state}/vsay-usage.log`,每行 `时间<TAB>引擎<TAB>正文前24字`:
> ```bash
> # 数 qwen3 这条线还剩多少人在用(3 个月内应为 0 行)
> awk -F'\t' '$2 ~ /qwen3/ {n++} END{print n+0}' ~/.local/state/vsay-usage.log
> # 看默认切换是否真的生效(canto 应远多于 qwen3)
> awk -F'\t' '{c[$2]++} END{for(k in c) print c[k], k}' ~/.local/state/vsay-usage.log | sort -rn
> ```
> ⚠️ **不要用 `journalctl --user -u dsh-tts | grep qwen3` 当判据** ——
> dsh-tts 只记它自己的 `model=zhll` 参数,**区分不出实际走了哪个引擎**,那样会得出错误结论。

### 12.3 真要删的话:安全删除方案(⚠️ 未执行,等 Nija 拍板)

> ⚠️ **2026-09-22 更晚 更新:本节方案已被 §12.4 取代执行 —— 但【没有删】,只做了归档。**
> 下面的步骤作为**方法记录**保留(史册),实际执行见 §12.4。

**铁律:不 `rm`。先降级、再观察、最后才归档。**

```bash
# ── 第 0 步:先确认真的没人用(必须先做,别跳)──
grep -rn --exclude='*.bak' 'vsay-yue\|qwen3-tts' \
  /usr/local/bin /opt/canto-tts /etc ~/Documents/repo/voice-tts ~/.config/systemd 2>/dev/null
# 期望:只剩 /usr/local/bin/vsay-yue 自己 + vsay 的 qwen3 分支(那是"可选",不是"依赖")

# ── 第 1 步:改名降级(不删,只让它不再是任何默认)──
#   新 vsay 默认已走 canto;这一步只是把"显式可选入口"也标注清楚
#   (已做:`vsay-yue-qwen3` 别名 + `vsay -e qwen3`)

# ── 第 2 步:观察期(建议 ≥ 2 周)──
#   期间任何一次被调用都能被发现:
journalctl --user -u dsh-tts --since '2 weeks ago' | grep -i qwen3

# ── 第 3 步:先"移走"而不是"删除"(可一步回滚)──
#   ⚠️ 走 sbrun(要 root),且【保留原路径名】,只换根目录
sbrun --why "把 2.2GB qwen3-tts 模型移出生产位,进入观察归档" -- \
  mv /var/lib/qwen3-tts-hk /var/lib/.retired-qwen3-tts-hk
#   CLI 与 .so 也一并(体积小,但和模型是绑定的):
sbrun --why "同上" -- mv /opt/qwen3-tts /opt/.retired-qwen3-tts

# ── 第 4 步:验证"移走后一切照旧"──
vsay "今日天氣幾好"          # 默认:canto,必须正常
vsay -e qwen3 "測試"         # 应当【明确报错】而不是静默无声
tts-backend list             # qwen3-tts-hk 应显示不可用

# ── 第 5 步:又过了 1 个月且确认无回归 ⇒ 才谈真正的删除 ──
#   此时才:① 记账(本文件 + MAINTENANCE.md 登记)② 再 rm -rf
#   ⚠️ 或者更好:压到备份盘再删本地,而不是直接消失
```

**为什么"移走"而不是"删"**:`mv` 是一步可回滚的(`mv` 回来即可);
`rm -rf` 是不可逆的。**在"不确定"面前,先用可逆动作。**

**⚠️ 不要做的事**:
- ❌ 不要删 `/var/lib/qwen3-tts-hk` 却留下 `/opt/qwen3-tts` —— 那会留下一个**能运行但必崩**的 CLI,
  调用方拿到的是"引擎在但模型没了"的怪错误,比干净地报"缺引擎"难查得多。
- ❌ 不要动 `/usr/local/bin/vsay-yue` 脚本本身(那是路标,不占地方)。
- ❌ 不要删 `/opt/qwen3-tts/lib` 里的 ggml `.so` 而留 CLI —— 同 `--max-tokens 4096` 那类坑一样,
  会变成"符号找不到"的运行期崩溃。

### 12.4 ✅ 已执行(2026-09-22 晚):qwen3-tts **退役归档** —— canto-tts 成唯一模型

> **Nija 拍板(原话)**:「vsay-yue(qwen3)完全给它归档退休,我们从此社会面工作面都只有一个模型,
> 就是 canto-tts,其他都不要,vsay-yue(qwen3)归档存好就行大概率打入冷宫且以后开发潜力和价值不高了??
> **模型太大了,生产环境用不起。**除非我们搞技术突破和攻艰」

**⇒ §12.2 的建议("先留,3 个月后复查")被 Nija 提前拍板 —— 但走的是【归档】,不是 §12.3 的删除。**

#### 执行动作(全部是 `mv`/`install`,**零 `rm`**)

| # | 内容 | 从 | 到 |
|---|---|---|---|
| 1 | GGUF 模型 + tokenizer(2.2GB) | `/var/lib/qwen3-tts-hk/` | **`/var/lib/retired/qwen3-tts-hk/`** |
| 2 | CUDA CLI + `lib/*.so`(71MB) | `/opt/qwen3-tts/` | **`/opt/retired/qwen3-tts/`** |
| 3 | 命令 `vsay-yue` / `vsay-yue-qwen3` | `/usr/local/bin/` | **`/opt/retired/qwen3-tts/bin/`** |
| 4 | 引擎注册 `engine.conf` | `backend/engines/qwen3-tts-hk/` | **`voice-tts/retired/qwen3-tts/engines/`** |
| 5 | 退役说明 + 恢复工具 + 标记 | —— | **`voice-tts/retired/qwen3-tts/{README,RETIRED}.md` + `restore.sh`** |

**为什么这样分(FHS)**:模型是**状态/数据** ⇒ 归 `/var/lib`;CLI 与命令是**软件** ⇒ 归 `/opt`。
**只换父命名空间(加 `retired/`)**,不跨类搬家;同分区 ⇒ `mv` 是原子改名(0 字节拷贝、秒回滚)。

#### 🔴 唯一真会断的地方:改之前 `vsay` 的**两条回退都指向 qwen3**

```
旧:canto 缺  ⇒ exec /usr/local/bin/vsay-yue     ← qwen3
旧:female 缺 ⇒ exec /usr/local/bin/vsay-yue     ← qwen3
```
qwen3 一归档,`vsay-yue` 就**不存在** ⇒ 这两条从"保命"变成"**保证失败**"。

**新策略(已改接,黑仔 + 平板两侧一致)**:

| 情形 | 新行为 |
|---|---|
| `canto` 缺失 | 回退 **`vsay-canto-female`**(同 canto-tts 模型、同粤语发音路径,只换音色) |
| `female` 缺失 | 回退 **`vsay-canto`**(同上,性别互换;**先明确告知**再降级) |
| 两条 canto 线都缺 | **明确报错 `exit 1`**(绝不静默哑掉) |
| `vsay -e qwen3` | **明确报错 `exit 3`**(专用码:1=无引擎 / 2=未知引擎 / 3=已退役引擎) |

**为什么回退到 canto 男↔女、而不是直接报错**:同一个模型,只换音色 codes ⇒ 这是**真正的优雅降级**。
**为什么 `-e qwen3` 要硬报错而不是偷偷给 canto**:调用方**显式点名**了引擎,偷偷换音色 = 撒谎,
且会让"qwen3 还能用"的错觉长期存活。**宁可大声报错,不要静默降级。**

#### 顺手修掉的一个潜在坑

旧 `engines/qwen3-tts-hk/engine.conf` 的 `PRIORITY=100` **高于** canto 的 95 ⇒
一旦 `~/.config/voice-tts/engine` 丢失,调度器会**自动选到 qwen3**。
归档后 canto(95)成为最高优先级 ⇒ **这个坑消失**。

#### ⚠️ 派生音色不受影响(重要澄清)

`VSAY_FEMALE_VOICE=qwen_hk` / `qwen_short` 这两个 canto-tts 女声音色,**名字带 qwen 但不依赖 qwen3** ——
它们的 `prompt_audio_codes` 已**固化在 voicebank JSON**(`qwen_hk.json` / `qwen_short.json`)里,
是离散音色码,**运行时不读 qwen3 任何东西**。⇒ 归档 qwen3 **不会**让这两个音色失效。
**故意不改名**:`qwen_hk` 是**已发布的行为契约**(`VSAY_FEMALE_VOICE=qwen_hk`),改名会打断调用方。
**名字里的 qwen 是命名史,不是依赖。**

#### ⛔ 边界:什么**没有**被退役(别误伤)

| 东西 | 为什么留着 |
|---|---|
| **`backend/p2y.py` + `/usr/local/bin/p2y`** | 普→粤**纯规则表**,与 qwen3 无关;被 **Android IME 的 `P2Y.kt`** 依赖(有逐字一致性单测);⚠️ 另有 agent 正在扩词表,**不许动** |
| **`Qwen3-ASR 0.6B`** | ⚠️ **完全不同的模型** —— 那是**语音识别(ASR)**,属 voice-input 模块 |
| **`~/dev/female-canto-tts/refs/ref_qwen3hk_female.wav`** | 女声克隆的**参考音频素材**(1.1MB),是 female-canto-tts 自己的资产 |
| **用量记账 `~/.local/state/vsay-usage.log`** | 退役后仍保留 —— 它现在是**史册**,记录"退役前 qwen3 还有没有人在用"这个客观事实。**不清空、不改写** |
| **`/usr/local/bin/vsay-canto` 第 4 行注释** | 提到 `vsay-yue` 只是设计对称性说明;⚠️ 该文件 md5 被 Nija 冻结(`8071d81b…`),**不许改** |

#### 一键恢复

```bash
~/Documents/repo/voice-tts/retired/qwen3-tts/restore.sh --dry-run   # 先看
~/Documents/repo/voice-tts/retired/qwen3-tts/restore.sh             # 真恢复
```
⚠️ 恢复后 `vsay -e qwen3` 会重新可用,但 `vsay` 的**自动回退仍指向 canto 系**(故意的,见上)。
**完整退役说明(含 CUDA 复活坑表 / 未来重启条件)**:`voice-tts/retired/qwen3-tts/README.md`。

---

## 旧 VITS 粤语女声退役(2026-09-22)—— 零残留

**Nija 令(原话)**：「我宣布!旧VITS女声全部全面清扫,退役;零残留。!!!!!!!!」

### 退役对象

`vits-cantonese-hf-xiaomaiiwn`(109MB,sherpa-onnx VITS 粤语女声,实测 F0 234.6Hz)

### 两条理由(缺一条都不足以退役;许可那条更硬)

| # | 理由 | 数据 |
|---|---|---|
| 1 | **质量** | 客观 CER **22.0%**(官方同一把粤语 ASR 尺子)。对照:新女声 **6.1%**、男声 13.9% ⇒ **2~3 倍差距**。逐句有严重错读:s01 14.3% / s02 30.8% / **s04 54.5%**。与 Nija 主观评价「一个字一个字吐」「太听不清,冇感情」一致 |
| 2 | **许可** | 发行包里**没有 LICENSE、没有 MODEL_CARD**,HuggingFace 上**无许可标签** ⇒ 版权未声明 ⇒ **仅自用,不许分发**。⚠️ 这推翻了 `tts-models/MANIFEST.json` 原先「模型卡随目录」的记载(那是**未经核实**的错误) |

### 补位(先建后拆 —— 没有"两个都不能用"的中间态)

`canto-tts` 零样本克隆女声,模块 `~/dev/female-canto-tts/`:
- **同一个模型权重**(`/var/lib/canto-tts/model`,只读)+ **同一套粤语发音路径**,只换 voice codes
- 默认音色 **cv01**(评测最好:CER 0%、偏差 0Hz);可换 cv02~cv05 / qwen_hk / qwen_short
- 参照音色来源 **Common Voice 22 yue 真人女声 / CC-0** ⇒ 许可干净
- 实测 F0:笔记本 200.8Hz;平板 204.8Hz(均判为女声)

### 删除实体(逐路径)

| 位置 | 路径 | 体积 |
|---|---|---|
| 笔记本·运行时 | `~/.local/share/sherpa-onnx-tts/vits-cantonese-hf-xiaomaiiwn/` | 110M |
| 笔记本·归档 | `~/Documents/repo/tts-models/payload/vits-cantonese-hf-xiaomaiiwn.tar.bz2` | 103M |
| 笔记本·模型库 | `~/Documents/repo/tts-models/models/yue/vits-cantonese-hf-xiaomaiiwn/` | 110M |
| 笔记本·APK | `~/Documents/repo/tts-models/apk/…-yue-tts-engine-vits-cantonese-hf-xiaomaiiwn.apk` | 118M |
| 笔记本·样本 | `~/Documents/repo/voice-tts/docs/samples/A_平板现用_…_F0-235Hz.wav` | 208K |
| **平板·旧位置** | `/storage/emulated/0/Documents/models/tts/yue/vits-cantonese-hf-xiaomaiiwn/` | 109M |
| **平板·App 私有** | `/storage/emulated/0/Android/data/com.fcitx5sensevoice/files/models/tts/vits-cantonese-hf-xiaomaiiwn/` | 109M |

**合计约 658MB。** tar.bz2 的 sha256 留档:`bf3013cd4be34f531b7e514e708d835584dd60c9ad6eaf467ac1402005c04e46`

### 改动的引用

- 本模块:移除 VITS 引擎插件 → 新增 `canto-female`;`vsay` 的 `lite` 引擎 → `female`;`bin/vsay-lite` 删除(留档 `backups/`)
- `~/dev/dsh-tts/`:注释订正(它**从来不依赖**该模型)+ 新增 `DSH_TTS_VOICE` 音色开关(默认不变)
- `~/dev/android-voice-ime/`:女声路径改走 chroot `canto-tts`;`VitsTtsEngine` → `SherpaTtsEngine` 且**去模型绑定**
- `~/Documents/repo/tts-models/`:`MANIFEST.json` 移入 `retired[]`;`SHA256SUMS` 删行;布局文档改例

### 保留(有意为之)

- **A/B 证据**:`~/dev/female-canto-tts/out/DEMO/对照_旧VITS女声.wav` + `RETIRED-旧VITS-证据.md`(带许可警告:仅自用)
  —— 判据是**可再造性**:分数留(数据),逐句音频删(那是能再造音色的载体)
- `vits_clean` 音色:**删除**。它的 codes 克隆自这个无许可模型 ⇒ 许可继承不干净

---

# §L1 · 2026-09-22 晚:常驻守护"没生效"的真凶 = 僵尸 socket 文件

> 现象:`vsay-canto "…"` 实测 6.5~8.2s,而设计上 L1 常驻应该 2~3s。
> 日志里没有走 L1 的迹象,`ss -x` 却显示 socket 在 LISTEN。**看起来像"守护在跑但没收益"。**

## 真凶

**判据错了。** 旧代码用 `[ -S "$SOCK" ]`(socket **文件存在**)判断"守护活着"。

守护被 kill(或并发双启时被后起的守护 `unlink` 摘掉插座)之后,会留下**僵尸 socket 文件**:
文件还在 ⇒ 旧判据认为守护活着 ⇒

1. **不再惰性拉起**(以为已经有了);
2. 每次调用都去 `connect` ⇒ **ECONNREFUSED** ⇒ `try_daemon` 返回 2 ⇒ 降级 L2(整模型重载 3.5s)。

⇒ 端到端 **5.1s**,而 L1 本该 2~3s。**这就是"4.3x 白优化了"的真凶 —— 不是守护慢。**

**为什么 `ss -x` 也误报**:`/proc/net/unix` 保留的是 socket **bind 时**的名字,
文件被 `unlink` 之后名字仍留在表里 ⇒ `ss` 显示 LISTEN、`[ -S 文件 ]` 也成立,**实际连不上**。
⇒ 判据必须是「**能 connect 上**」(listen(2) 是可探测的),不是 `stat(2)`。

## 修法(六个点)

| # | 修法 | 为什么 |
|---|---|---|
| 1 | 探活改成**真 connect**(`sock_alive()`) | 文件存在 ≠ 可服务 |
| 2 | `flock -n` → **阻塞式 `flock -w 90`** | 并发调用应当**排队**,不是各起一个守护 |
| 3 | 锁内**双重检查**(double-checked locking) | 等锁期间别人可能已把守护起好 ⇒ 必须复用 |
| 4 | **僵尸自愈**:连接失败 ⇒ 清僵尸 socket → 重起 → **重试一次** | 自愈,而不是直接掉进 L2 付 3.5s |
| 5 | **孤儿守护回收**:启动时把 pid 写 `$SOCK.pid`,清理时按 pid 收 | 孤儿每个占 ~1.5GiB(纯浪费) |
| 6 | daemon 侧 `run_serve` **bind 前先探活**,有人在服务就退出 | 调用方**绝不 unlink 别人的 socket** —— 从源头消灭孤儿 |

⚠️ 回收孤儿时**不用 `pgrep -f` / `pkill -f`** —— 那种模式可能匹配到调用者自己的命令行而杀错。
正确做法:直接读 `/proc/<pid>/cmdline` 判断。

## 实测(安装到 `/usr/local/bin` 的那份)

| 项 | 结果 |
|---|---|
| L1 合成(5 连打) | **1.90 / 1.93 / 1.99 / 2.22 / 2.33 s** |
| L2 对照(`--no-daemon`) | **5.67 / 5.76 s** ⇒ **≈2.7x** |
| **TTFB(第一个 ▶ 出声)** | **1696 ms** |
| 6 个并发冷启 | 守护数 = **1**(修前会起多个) |
| SIGKILL 守护留僵尸 socket | **自动清理并重起** ✅ |
| 手动再起一个守护 | **直接退出、不抢插座** ✅ |

## 女声也接上了同一个守护(2026-09-22 晚 顺带修)

**女声此前完全没有守护** —— `vsay-canto-female` 每次调用重载模型(`[load] 3.4s`)。
修法:给守护协议**加 `voice_codes` / `seed` 两个字段** ⇒ **一个守护同时服务男声与女声**,
不必为女声再起一个守护(那要多占 ~1.5GiB,平板可用内存只有 5.6GB,受不了)。

- `_default_voice_codes` 在每次 `synthesize()` 里都会重新读取(`onnx_backend.py:181`)⇒ 每请求换音色是安全的。
- **启动**复用 `vsay-canto --ensure-daemon`(flock/双重检查/僵尸自愈的**唯一实现**)⇒ 女声不抄第二份,消除漂移。
- **质量管线(打分/重试/多候选/停顿)全部留在客户端** —— 守护只做"把一段文本合成成一个 wav"。
- **失败自动回落**本进程 `OnnxBackend` ⇒ 守护坏了不会导致播不出声。

### ⚠️ 踩坑记录(自己引入、自己抓住):跨请求串味

第一版**忘了在未指定 `voice_codes` 时复位成内置男声**。守护是长生命周期进程,
于是女声请求之后再发一次男声请求(`vsay-canto` 不带 `voice_codes`),**出来的竟是女声**(F0 218Hz)。
⇒ 教训:**长生命周期守护里,任何"上一次留下的状态"都是跨请求污染源**,每个请求都要显式复位。

### 严格验证(两条)

1. **同音色同 seed,守护产出与女声 CLI 直跑 md5 完全相同**(`16eea9b831fbde97914c3a3440a49cde`)。
2. **串味回归**:男声在女声请求**前后**md5 一致(`d1bb042d…`),且 ≠ 女声。

| 项 | 结果 |
|---|---|
| 女声 L1 合成(3 次) | **1.42 / 1.55 / 1.51 s** |
| 女声 `--no-daemon` 对照 | **5.89 s** ⇒ **≈3.9x** |

## 坑 2:能力漂移 —— 女声 `play()` 漏了"服务探测"

男声 `vsay-canto` 早就修过「**光有二进制不算数**」(`_pa_ok`/`_pw_ok` 探**服务**),
女声 `vsay-canto-female` 的 `play()` **一直只用 `shutil.which()` 判二进制在不在**,
并把 stderr 丢进 DEVNULL、异常一律 `continue` ⇒ 用户只看到一句
「`[warn] 没有可用的播放器,只生成了 wav`」,**真实报错全被吞掉**。

平板实测被这条坑死:
- `pw-play` 在,但连不上 ⇒ `pw_context_connect() failed: Host is down`
- `aplay` 在,但 `/dev/snd` 被 Android 的 `ohalservice.qti` 独占 ⇒ `pcm_write: write error: Invalid argument`

⇒ **同能力两处实现漂移**(这是本项目第二次栽在"漂移"上,前一次见 `common.sh` 的路径常量)。
修法:女声补上与男声对齐的三段式服务探测 + **如实报错**(不再 DEVNULL 吞掉)+ 平板落盘交 App 播。

## 坑 3(邻域,会反复踩):App 的 `127.0.0.1:8791/play_wav` 坏死

`ensureStarted()` 里写着 `if (started) return` ⇒ accept 循环一旦 `break`,**同一进程生命周期内永不重启**该线程。
表现:`ss` 显示 8791 仍 `LISTEN`,但 `Recv-Q` 越积越多、连接全部超时。
⇒ **设计坑**:后台线程的自愈要落在"循环内重试",不是"启动时判一次"。
本轮未能修(属 App 侧),记录在此免得下次再趟。

---

# §L1b · 2026-09-22 晚:格仔(平板 chroot)部署记录与坑

## 部署了什么(三件,md5 已核对)

| 文件 | md5 | 作用 |
|---|---|---|
| `/usr/local/bin/vsay-canto` | `8071d81b…` | L1 守护修复 + `--ensure-daemon`(女声复用入口) |
| `/usr/local/bin/vsay-canto-female` | `bbd8240c…` | 接入常驻守护 + play() 补服务探测 + 平板落盘交 App 播 |
| `/opt/canto-tts/lib/tts_stream.py` | `ed1ec2dc…` | 协议加 `voice_codes`/`seed` + daemon 侧双启守卫 |

## 实测收益(格仔)

| 路径 | 修前 | 修后 |
|---|---|---|
| 女声合成 | ~4.6–4.9 s(`[load] 3.4s` 每次) | **1.13 / 1.17 / 1.15 / 1.19 s** ⇒ **≈4x** |
| 男声合成 | ~5–7 s | **0.94 / 1.11 / 1.27 s** |

## 坑:chroot 里必须用【chroot 内部】有效的 socket 路径

第一版把 Android 侧路径 `/data/local/linux/canto-tts.sock` 写进 `/etc/profile.d/canto-tts.sh` —— **错的**:
chroot 根是 `/data/local/linux/debian`,所以那个路径在 chroot 里**根本不存在** ⇒
`ensure_daemon` 的"目录可写吗"判据失败 ⇒ 守护起不来、调用方**静默降级 L2**(表现为"修了但没效果")。

**正确**:`/run` 在 chroot 内存在且可写 ⇒ `VSAY_CANTO_SOCK=/run/canto-tts.sock`。
`/etc/profile.d/canto-tts.sh` 另设 `TMPDIR=/tmp` —— 因为 Android 的 env 会把
`TMPDIR=/data/local/tmp` **隐式带进** chroot,而那个路径在 chroot 里也不存在(隐式依赖,显式消除)。

## 好消息:守护能跨 adb 会话存活

`nohup ... --serve &` 之后 adb 会话结束,**守护仍在**(`ps` 可见)。
⇒ 平板上"惰性拉起 + 空闲自退"的模型成立,不必做开机常驻。

## play() 的平板兜底实测

`vsay-canto-female "…"`(不给 `--out`)在平板上现在会:

```
[warn] aplay 播不了(aplay: main:850: audio open error: Host is down)   ← 如实报错,不再吞掉
[play] 音频已就绪(默认不自动出声): /sdcard/Android/data/com.fcitx5sensevoice/files/vsay-female-13048.wav
[play] 要出声请执行: am start --activity-single-top -n com.fcitx5sensevoice/.SpeakTriggerActivity --es wav_path …
```
落盘文件属主已自动修好 **10379:1078**、`chmod 664`(App 读得到)。
真出声需显式 `VSAY_ANDROID_PLAY=1`(会真的响 + 短暂提到前台 ⇒ 必须由人先同意)。

## 平板出声的第一手证据(2026-09-22 20:19,用户已批准一次)

```
TTS_PLAYER_PLAY_START gen=5 rate=48000 channels=2 usage=assistant firstAudioMs=29
TTS_PLAYER_PLAY_DONE  gen=5 cancelled=false framesWritten=192000 framesPlayed=192000
                      headBeforeCancel=192000 audioMs=4000 totalMs=4051
```
- `framesWritten == framesPlayed` ⇒ **尾音没被切**
- audio_flinger 活跃音轨 10 → **11**(播放中)→ 10(播完)⇒ 那条音轨就是我们的
- `mCurrentFocus` 播放前后都是 `…einkbro/…BrowserActivity` ⇒ **NoDisplay 确认:没创建窗口、没抢焦点**

⇒ **平板的语音播报链路:合成(1.1s)→ 落盘 → `SpeakTriggerActivity --es wav_path` → Android AudioTrack 出声。全链路打通。**

---

# §13 普 → 粤(p2y)接入 canto-tts —— 2026-09-22 晚

## 13.1 缺口(实测)

```
grep -c "p2y\|P2Y" /usr/local/bin/vsay-canto        ⇒ 0    ★ 完全没接
grep -c "p2y\|P2Y" /usr/local/bin/vsay-yue          ⇒ 7    (它接了,可参照)
```

后果:DSH 的回复是**普通话书面文**,被 canto-tts 直接念 ⇒ **半咸淡**。
依据(Nija 实测判定):「粤语模型 + 粤语文本」才地道;
「粤语模型 + 普通话文本」= 半咸淡。

词表与工具在 **voice-tts 模块**:`~/Documents/repo/voice-tts/backend/p2y.py`
(设计/规模/过改检测见 `voice-tts/backend/README-p2y.md`)。

## 13.2 为什么**不能**改 vsay-canto,而必须挂 dispatcher

`vsay-canto` 与格仔**字节一致**(md5 `8071d81b02518ab8115b6e4e3d91d22e`)是硬约束 ——
改它就产生两台机的实现分叉。而 dispatcher(`vsay`)本来就是"决定怎么念"的那一层。

**⚠️ 但有两条出声路径,只有一条经过 vsay:**

```
本机 DSH 🔊   → POST /speak       → server.js → vsay → vsay-canto       ← dispatcher 能兜
平板浏览器 🔊 → POST /speak-audio → server.js → **直调 vsay-canto**      ← 绕开 dispatcher!
```

⇒ 只在 dispatcher 注入会漏掉【平板浏览器】那条,**"平板也要能享受"就落空**。
所以实际落点是 **3 处**:

| # | 文件 | 位置 | 覆盖 |
|---|---|---|---|
| 1 | 黑仔 `/usr/local/bin/vsay`(源码 `voice-tts/bin/vsay`) | 补标点之后、切段之前 | 本机 CLI + DSH /speak |
| 2 | 格仔 chroot `/usr/local/bin/vsay`(源码 `canto-tts/tablet-dsh-tts/vsay`) | 同上 | 平板的 DSH /speak |
| 3 | `~/dev/dsh-tts/server.js` | `speak()` 与 `synthesizeToFile()` | **两条路都兜**(含 /speak-audio) |

**不重复转换**:`server.js` 转完给子进程带 `VSAY_P2Y=0`;装了转换的 `vsay` 看到 0 就跳过。
⇒ 不管哪一层先装晚装,链路上永远只转一次。

## 13.3 为什么放在「补标点之后、切段之前」

- **补标点之后**:p2y 的「句末『了』→『喇』」要靠标点判句末。vpunct 先补上才判得准。
- **切段之前**:切段在 `vsay-canto` / `vsay-android-relay` 内部,本步在它之前完成 ⇒
  「喇/啦/咩/呀」这些语气词能参与断句。
- **转换后长度会变**:切段策略是"句号优先 + 按字数定长",对长度变化天然适应 ——
  实测 50 字上限下长句照常切,首段优先那条路也没受影响。

## 13.4 开关

| 变量 | 默认 | 作用 |
|---|---|---|
| `VSAY_P2Y` | `1` | `0` = 关闭(文本本来就是粤语时用) |
| `VSAY_CANTO_P2Y` | `1` | 同上(别名) |
| `P2Y_TRAD` | `0` | `1` = 打开简→繁阶段 |

`VSAY_P2Y=0` 在 **p2y 本体里也生效**(不只是 vsay 那一层)——
一个开关要在所有入口都有效才叫开关(实测踩过:验收脚本假失败)。

## 13.5 客观验收(2026-09-22 实测)

用 `tools/cer_eval.py`(`alvanlii/whisper-small-cantonese`,与官方同一把尺子):

| # | CER 关 p2y | CER 开 p2y | 参考(p2y 开) |
|---|---|---|---|
| 1 | 36.4% | **0.0%** | 多謝，呢個功能已经搞掂喇。 |
| 2 | 57.1% | **7.1%** | 我睇咗一陣，發現呢度有一個問題。 |
| 3 | 31.8% | **0.0%** | 如果你有時間嘅話，我哋可以一齊討論一陣解決方案。 |
| 4 | 8.3% | 27.3% ⚠️ | 佢嘅目的係乜嘢？我哋唔知。 |
| 5 | 27.3% | **0.0%** | 但係我唔想去，因為太貴喇。 |
| 6 | 20.0% | **0.0%** | 聽日朝早八点我哋喺公司门口見面。 |
| 7 | 8.3% | **0.0%** | 佢企喺嗰度，睇住窗外嘅风景。 |
| 8 | 0.0% | 0.0% | 多謝你嘅幫手，對唔住我遲到喇。 |
| | **平均 23.7%** | **平均 4.3%** | Δ = **−19.4 个百分点** |

**⚠️ 怎么读这张表(别过度解读)**:
- 两列的**参考文本不同**(关 = 普通话原文;开 = p2y 输出),所以这不是"同一把尺子量同一个东西"。
- 有意义的读法:关 p2y 时,独立粤语 ASR 只能还原 76% 的字 ⇒
  **引擎在念普通话时会明显走样(半咸淡)**;开 p2y 后还原率 96% ⇒ 字念对了。
- **CER 量不出"地道不地道"**。地道只能靠人耳 A/B。

**⚠️ 唯一变差的一条(第 4 句)**:ASR 把「佢嘅目的係乜嘢」听成「佢咳低，低係乜嘢」——
`嘅` + `目的` 这个组合在 nano 模型上容易糊。已如实记录,未修。

**A/B 音频**(同文本、同模型、**逐字节可复现** —— ONNX 贪心解码无采样,不需要固定 seed):
`docs/samples/p2y-ab/sNN_A_关p2y.wav` vs `sNN_B_开p2y.wav`

## 13.6 回归(全绿)

`/tmp` 的临时脚本已跑过一轮 **23/23**:

- dsh-tts 单元 active · `/health` 报 `p2y.on=true` · `POST /speak` 成功
- 男声合成 · L1 常驻守护 ping · 守护 socket 活着
- 女声合成并产出 wav · 派发 `-e female` · `-m zhll` 兼容契约仍被吃掉
- p2y 单元测试 157 条全过 · 保护表完整性 174 条 · 用户给的例子「谢了→多謝」· 一键关闭
- **平板 `vsay-canto` md5 仍是 `8071d81b…`(没动)** · 平板 p2y 已装 · 平板 vsay 已接 · 平板 dsh-tts 仍在跑

## 13.7 未完成 / 待办(如实写)

1. **黑仔 `/usr/local/bin/vsay` 的 p2y 段尚未落盘** —— 需要 root,`sbrun` 两次都
   "120s 内无人批准"。**但 DSH 的 🔊 已经完全生效**(server.js 那条路覆盖了两条出声路径)。
   落盘方式(二选一):`sbrun` 批准一次;或用 root 跑
   `~/Documents/repo/voice-tts/install.sh`(它已包含 p2y)。
   `/usr/local/bin/p2y` 同样还是 v1 —— 但 `vsay` 与 `vsay-yue` 都优先读**开发场**那份,
   所以实际生效的已经是 v2。
2. 简→繁(`P2Y_TRAD=1`)未做 CER 对比,默认关。
3. 「多谢 / 唔該」的语境区分靠规则表做不到,只能按搭配钉(见 README-p2y §9)。

## 13.8 变更涟漪(反向 grep)

- 新标识符 `VSAY_P2Y` / `VSAY_CANTO_P2Y` / `P2Y_ON` 的落点:
  `voice-tts/bin/vsay` · `canto-tts/tablet-dsh-tts/vsay` · `dev/dsh-tts/server.js` ·
  平板 `/usr/local/bin/vsay` ✓ 全部命中。
- `vsay-canto` / `vsay-canto-female` / `vsay-android-relay` / `vsay-android-play`
  **一个字节都没动**(md5 可查)。
- 旧引用只剩 `voice-tts/backups/vsay-yue.bak-20260922`(史册/`.bak`,按纪律不改)。

## 14. ⚠️⚠️⚠️ 最贵的一课:把 `canto_hk_g2p` 当成了 `cantophon`(带偏 20 轮)

> **2026-09-26 定案。这一课的代价:Android 侧合成"开头勉强能听、后面叽里咕噜",
> 查了 20 轮才挖到根。Nija 的原话:「叽里咕噜,不知道说了什么…感觉都不像粤语了,
> 都像什么乱七八糟的别的方言」—— 他的直觉方向(框架对接层)是对的。**

### 14.1 现象(用户听到的)

```
「今日天氣幾好」⇒ 只有开头「今日」勉强能听,后面全是噪声
⇒ 客观指标(不是靠耳朵):
   · 时长:Android 4.88~5.36s  vs  笔记本 2.64s   ⇒ 【多生成一倍】
   · 过零率:第 4 段 ZCR 0.149   vs  正常 0.05~0.06 ⇒ 【是噪声,不是语音】
```

### 14.2 根因(一句话)

```
我把 `canto_hk_g2p`(汉字 → 粤拼,输出 "gam1 jat6 tin1 …")
当成了 `cantophon`   (汉字 → 三元组,输出 "<o-g> <r-am> <t1> …")

⇒ 两者【完全不是一回事】;而模型要的是【后者】。
⇒ 送进模型的文本 token 全错 ⇒ prompt 从第一个 token 就分叉
⇒ 模型的 should_continue 永不触发(它不会说"我说完了")
⇒ 一直生成到 maxFrames 上限 ⇒ 多出来的帧是噪声
⇒ 听感:「开头(高置信段)勉强能听、后面叽里咕噜」
```

### 14.3 正确的管线(权威实现:`canto_tts/core/cantophon.py`)

```python
def text_to_tokens(text):
    return jyutping_to_tokens(_g2p().convert(text))
    #      ↑ ② 粤拼→三元组      ↑ ① 汉字→粤拼(这一步我的 .so 是对的)
```

| 步 | 做什么 | 谁做 |
|---|---|---|
| ① | 汉字 → 粤拼 `gam1 jat6 tin1 hei3 gei2 hou2` | `canto_hk_g2p` = **我的 .so ✅** |
| ② | 粤拼 → 三元组 `<o-g> <r-am> <t1> …` | `cantophon.jyutping_to_tokens` = **我缺的** |
| ③ | 三元组 → id `16388,16415,16464,…` | 查 `added_tokens.json`(88 条) |
| ④ | 句末补「。」 | `safe_prepare_text`(「。」不走 added_tokens,走 SentencePiece ⇒ 2 个 id) |

**关键规则(逐条对照 cantophon.py):**
```
· 音节正则 ^[a-z]+[1-6]$
· ONSETS(19)必须【按长度降序】匹配(最长优先)—— 顺序不能改
· parse_syllable:先试"声母+韵母",失败再试【零声母】(如 'm' → (None,'m'))
· 三元组:有声母 → <o-X> <r-Y> <tN>;零声母 → <r-Y> <tN>
· 「唔」是零声母 ⇒ 只 2 个标记(实测:'<r-m> <t4>')
```

### 14.4 🔴 为什么这么难查 —— 一句【我自己写错的注释】

```java
// CantoTokenTable.java 里我写着:
//   「我们实测:音素串对 added_tokens 的覆盖率 = **0%**(那些不是音素!)」
```

**这句话本身是错的,而且它【把错误固化成"事实"】,后来我每次都信它:**

```
我当时拿【粤拼串 gam1 jat6】去匹配 added_tokens ⇒ 当然 0%!
而正确输入是【三元组串 <o-g> <r-am> <t1>】⇒ 覆盖率 100%
⇒ 于是我"有理由地"放弃了正确路径,改走 syllable-ids.tsv(音节 → 2 个 id)
⇒ 那是一条错路 ⇒ 20 轮都在错路上打转
```

**⇒ 规则(血的教训):**
```
① 注释里的"实测结论"必须写清【测的是什么输入】——
   否则后人(包括未来的我)会把"输入不对导致的 0%"误读成"这条路不通"
② 说"这条路不通"之前,必须先把【权威实现的输入输出】打印出来对照,
   不能只凭自己的一次实验就下结论
```

### 14.5 ✅ 怎么查出来的(方法论 —— 全程靠数据,零猜测)

```
① 用【客观指标】替代不可测的"听感"
     · 过零率(ZCR)⇒ 区分语音 vs 噪声(正常 0.05~0.06,噪声 >0.12)
     · 时长对比 ⇒ 一眼看出"多生成一倍"(4.88s vs 2.64s)
② monkey-patch 参考实现(SDK)打点 ⇒ 拿到【参考序列】
     · should_continue 序列:SDK = 1×33 然后 0;我的 = 全 1
     · text_token_ids:SDK = 【20 个】;我的 = 14 个
③ 对比【第一帧的 frame_token_ids】⇒ 【决定性的一刀】
     · Android F0 = 576,818,942,…
     · SDK     F0 = 467,875,675,…    ⇒ 完全不同
     ⇒ 这一刀把原因切开:差异在【prompt 层(S5/S6)】,不在递推层(S7)
④ 逐段打印 SDK 的管线(to_phoneme → safe_prepare → encode)各步的实际输出
     ⇒ 第一次看到真正要的三元组格式
⑤ 对照自己的代码注释 ⇒ 发现"用错组件"
⑥ 修完再对比【第一帧 token】⇒ 【逐位一致才算完】
```

### 14.6 ✅ 修法与验收(已通过)

```
新增 assets/android-engine/CantoCantophon.java(348 行):
  · ONSETS(19,长度降序)· RIMES(61)· TONES(6)
  · parseSyllable / syllableToTokens / jyutpingToTokens / jyutpingToIds
  · added_tokens.json 的 88 条【内嵌】(Android 侧不必解析 JSON)
  · SP_PIECES:标点/英文的 SentencePiece ids(导出 37 条)
    ⚠️ 「。」不在 added_tokens ⇒ 走 SP ⇒ 2 个 id(10356, 10382)
CantoTokenTable.encode() 改为【cantophon 优先】,旧表只作兜底
句末标点:【先拆音节,再把「。」作为独立片段追加】
  ⚠️ 第一版粘到音节上("hou2。")⇒ 不匹配音节正则 ⇒ 整个音节被丢
     ⇒ 文本 token 从 18 掉到 15(实测发现)⇒ 正解是拆完再加
```

**硬验收判据(必须逐位一致):**
```
「今日天氣幾好」⇒ 142 行 prompt(文本 20 · 音色 44)
第一帧 tokens = 467,875,675,777,292,970,963,124,395,689,657,248,799,224,151,294
             = 与 SDK 【逐位一致】✅
生成 33 帧(2640 ms)≈ SDK 的 34 帧(2.64s)✅
ZCR: 0.071/0.158/0.039/0.068/0.054 = 与笔记本侧【完全相同】✅
```

### 14.7 📌 以后开发注意(本节的核心)

```
【一】对接外部组件时,【必须打印它的实际输出】与权威实现逐字对照。
      不能凭名字猜("g2p 嘛,当然是干这个的"),也不能只看自己的实验。

【二】注释里的"实测结论"必须写清【测的是什么输入】。
      一句写错的注释,能把后来的人(和未来的自己)带偏几十轮。

【三】判断"这条路不通"之前,先问:我的输入格式对吗?
      对照权威实现的输入 —— 而不是拿自己臆想的输入去试。

【四】"听感"不可测 ⇒ 用客观指标定位:
      过零率 / 时长 / 帧数 / 与参考实现的逐位对比。
      其中【逐位对比】最硬:差一个 token 都能看出来。

【五】修完必须有【硬验收判据】,而且要写进代码注释。
      本项目的判据 = "与 SDK 逐位一致的那一串 id",见 CantoCantophon.java 末尾。

【六】症状与原因之间,先【分层】再往下查:
      "第一帧就不同" ⇒ 原因在 prompt 层,不在递推层。
      这一刀能省掉一半的排查范围。
```

---

## 15. ⚠️ Android 自定义主题:三个"设了颜色却没生效"的坑

> 2026-09-27。本模块的 GUI 是**纯 Java 创建控件**(没有 XML layout、没有 androidx),
> 所以主题要自己套色。Nija 报「暗模式有点问题,显示,背景文字」——
> 下面是查出来的三个原因,以及**怎么定位**的。

### 15.1 三个坑

| # | 现象 | 根因 | 修法 |
|---|---|---|---|
| ① | 按钮/输入框仍是**系统亮灰** | Android 5+ 的 `Button`/`EditText` 自带 **`backgroundTint`**(ColorStateList),会**盖过** `setBackground` | `v.setBackgroundTintList(null)`(API 21+) |
| ② | 内容**下方露出亮底** | `window` / `decorView` 的背景没设 ⇒ 内容不足一屏时露出系统默认底 | `root.setBackgroundColor(bg)` + `window.setBackgroundDrawable(ColorDrawable(bg))` + `ScrollView.setFillViewport(true)` |
| ③ | **音色按钮全亮灰,占 27% 屏幕** | 它们是 `refresh()` 的**异步回调**里 `buildVoiceButtons()` 动态 `new` 的 ⇒ **不在 `onCreate` 的遍历范围** | 动态创建完**必须重新套一次**:`voiceList.post(() -> apply(act))` |

### 15.2 🔑 怎么定位的(方法可复用)

```
① 截图 + 自写 stdlib PNG 解码器(PIL 不可用 ⇒ zlib+struct,含 5 种 filter)
② 颜色直方图 ⇒ 发现 RGB(208,208,208) 占 21~27%(明显异常;我的暗底是 18,18,18)
③ 【按行/列分段统计】⇒ 定位到"底部 25% 全宽"
④ ★ 关键判据:【异常色块会不会随内容移动?】
     会移动  ⇒ 【我的控件】(没套色)
     不移动  ⇒ window / 系统区域
⑤ 顺着"会移动"去查动态创建点 ⇒ 找到 buildVoiceButtons()
```

**⇒ 第 ④ 条是这一轮最有用的一条:一个简单的二分判据,把"我的控件"和"系统区域"一刀切开。**

### 15.3 按钮样式的三轮调整(供后来人参考)

```
第一版:浅底 + 彩字        → 暗色下按钮仍是系统亮灰(坑① 没修)
第二版:实心 accent 底      → 能用,但 Nija 说「按钮蓝色太重」(占 31% 屏幕)
第三版:暗底卡片 + 蓝字     → 【最终】clearTint + cardBg + accent 文字色
```

### 15.4 以后开发注意

```
【一】Android 上「设了颜色却没生效」,第一嫌疑是 backgroundTint
       Button / EditText / 部分 Material 控件都自带 ⇒ 先 setBackgroundTintList(null)
【二】window / decorView 的背景要显式设
       否则内容不足一屏时,露出的那块不是你的布局,而是系统默认底
【三】动态创建的控件必须重新套主题
       异步回调 / Adapter / 运行时 new 出来的 View,都不在 onCreate 的遍历范围
【四】排查"颜色不对"用【空间定位】而不是肉眼:
       直方图 + 按行/列分段 + "会不会随内容移动"
【五】纯 Java 界面(无 XML layout)没有 XML 主题可用 ⇒ 套色必须自己写,
       而且要写"幂等"的套色函数(可以反复调用,便于动态控件复用)
```

---

