# moss-nano-port

> MOSS-TTS-Nano 的下游配套引擎 —— 让这个小模型在你自己的设备上说话(粤语 / 普通话 / 英文 / 日语)
>
> ⚠️ 实现内部标识:`applicationId = canto.tts`、Java 包名 `canto`
> (刻意保持不动 —— 改了会让平板上已安装的 App 变成孤儿)
>
> ⚠️ **非官方 · 社区项目** —— 本项目是 [OpenMOSS Team](https://github.com/OpenMOSS/MOSS-TTS)
> (复旦大学 NLP / 上海创智学院 / 模思智能)的 **MOSS-TTS-Nano** 的【下游配套】,
> **不是**上游官方发布,也**不使用**其商标暗示官方背书。
>
> **我们做了什么**(上游和我们的边界):
> | 上游给的 | 我们做的 |
> |---|---|
> | 模型权重(Apache-2.0) | **用 Java 重实现 ONNX 推理**(官方只有 Python) |
> | ONNX 图导出 | Android 进程内引擎 + 系统 TTS 引擎注册 |
> | Python 推理代码 | 一键 install/verify/uninstall + 整包可迁移 |
> | 18 把内置音色 | 粤语音色库(voicebank)+ 多语言切换 |
>
> **支持语言**:粤语 · 普通话 · 英文 · 日语(共 4 种,25 把音色)

> **📌 语音体系总入口:[`~/Documents/repo/VOICE.md`](../VOICE.md)**
> 所有语音相关事务(输入 ASR + 输出 TTS + 命令/服务/契约)统一登记在那里。
> Nija 2026-09-27:「所有语音开发…都整理到同一个板块里面,不要太分散。」



> 一个目录 = 全部。`cp -a` 拖到别的机器,`./install.sh` 一条命令装好。
> 不依赖网络、不依赖大模型、不依赖我。

---



## ⚠️ Android 侧的三张关键表(改之前先读 `NOTES.md` §14)

**Android 引擎要"念对粤语",必须走 cantophon 的三元组管线,不能走音节表。**

| 表 | 在哪 | 干什么 |
|---|---|---|
| `added_tokens.json` | 模型目录(88 条) | 三元组 → id(权威映射) |
| `cantophon-tables.json` | `assets/android-tok/` | ONSETS 19 · RIMES 61 · TONES 6 |
| `CantoRngTable.java` | `assets/android-engine/` | 采样随机数(**必须与 SDK 逐位一致**) |

**管线(与权威 `canto_tts/core/cantophon.py` 完全一致):**
```
汉字 →(libcanto_g2p.so)→ 粤拼 "gam1 jat6"
     →(CantoCantophon)→ 三元组 "<o-g> <r-am> <t1>"
     →(查 added_tokens)→ id 16388,16415,16464
     →句末补「。」(走 SentencePiece,不在 added_tokens 里)
```

**⚠️ 硬验收判据**(改完必须验):
```
「今日天氣幾好」⇒ prompt 142 行(文本 20 · 音色 44)
第一帧 tokens = 467,875,675,777,292,970,963,124,395,689,657,248,799,224,151,294
             与 SDK 逐位一致;生成 33 帧(2.64s)
```

**⚠️ 最容易踩的坑**:`canto_hk_g2p`(汉字→粤拼)≠ `cantophon`(汉字→三元组)。
详情与六条开发规则见 `NOTES.md` §14。

## moss-nano-port for Android(2026-09-24 新增)

**把同一套 moss-nano-port 搬进 Android 进程,注册成系统 TTS 引擎。**
一个目录 = 全部;`./install.sh` 一条命令;不依赖网络、不依赖大模型。

```
android/                        便携包(八条硬要求)
├── install.sh / verify.sh / uninstall.sh
├── README.md                   八条对照 + 五条实测教训
├── device.mac                  ⚠️ 设备寻址纪律:MAC 优先,IP 只当回退
└── apk/moss-nano-port.apk    12.0 MB,已签名(代码 + ORT native + 小资产)

assets/android-engine/          引擎源码(10 个 Java 模块 · 1500 行)
├── CantoOrtEngine.java         推理主循环(4 图:prefill/decode_step/采样/codec)
├── CantoOrtBackend.java        ORT Session 管理(线程默认 2 · 固定 seed)
├── CantoTokenTable.java        音节→token(7930 条表;【不需要 sentencepiece】)
├── CantoSynthesizer.java       串链路(不碰 Android API ⇒ 三种环境可测)
├── CantoSegmenter.java         切段(默认 NONE 不切)
├── CantoVoiceBank.java         音色库(零样本克隆的 codes)
├── CantoSettingsActivity.java  音色选择页
├── CantoTtsService.java        TextToSpeechService(系统 TTS 引擎本体)
├── CantoG2pJni.java + jni/     G2P 的 JNI 桥(交叉编译)
└── voicebank/                  6 把音色(许可都干净)

assets/android-g2p/             G2P 交叉编译产物(Android aarch64 .so + data)
assets/android-p2y/             p2y 规则表 + Java 实现 + 比对基准
assets/android-tok/             音节表(替代 sentencepiece)
docs/android-feasibility/       可行性证据链(报告 + 1099 行管线说明 + APK + A/B 音频)
docs/2026-09-24-两端语音一致性.md  chroot ↔ Android 的一致性论证
```

**验证状况**:
| 项 | 结果 |
|---|---|
| 推理数值 | 逐样本吻合(相关系数 **0.9999999999**) |
| 音节→token | **逐位一致** |
| p2y | **逐字节一致(14/14)** |
| G2P | 平板上 `ALL_PASS`(`gam1 jat6 tin1 hei3 gei2 hou2`) |
| p2y 在引擎内 | **9/9 通过**(开关可切) |
| 系统 TTS 注册 | APK 已打出并验签;**真机装 + 出声待验** |

**⚠️ 头号未验证风险**:ColorOS 的 `osense` 会压缩前台服务
(VmRSS 1157MB→206MB,推理停在中途;5 种反制全无效)。
`TextToSpeechService` 被系统 bind,**很可能**不受影响 —— **但必须实测**。

## 许可(两个层次,别混)

| 层次 | 内容 | 许可 |
|---|---|---|
| **本模块自有代码** | `install.sh` · `verify.sh` · `uninstall.sh` · `pack.sh` · `bin/` · `lib/` · `tools/` · `docs/` · 本 README | **AGPL-3.0-or-later** |
| **上游模型/运行时** | canto-tts 权重(`canto-tts-nano-v1`)· MOSS-TTS-Nano · canto-tts pip 包 | **Apache-2.0** |
| | ONNX Runtime | **MIT** |
| **音色库参照音频** | `cv01~cv04`(Common Voice 22 yue) | **CC-0** |
| | `qwen_hk` / `qwen_short` | **Apache-2.0** + **CC-0** 数据 |
| 不得再分发 | `vits_clean`(旧 VITS,无 LICENSE) | **无许可** ⇒ 已删 |

- **完整许可全文**:[`LICENSE`](./LICENSE)(AGPL-3.0)· **逐项第三方清单**:[`NOTICE`](./NOTICE)
- **注意**:moss-nano-port 的**训练数据**作者自述「私有来源、因版权不公开」
  ⇒ 我们只按**模型卡声明的 Apache-2.0** 使用权重,**不能替上游担保训练数据的合规性**。
- **AGPL 的含义**:衍生作品也须 AGPL;**若你把它做成网络服务,也必须提供源码**。


## ⚠️ 先看清一个事实:这是「多一个男声选择」,不是「提升音色」

| | moss-nano-port 男声(本模块) | moss-nano-port 女声(female-moss-nano-port 模块) |
|---|---|---|
| **性别** | **男声** | **女声** |
| 实测 F0 中位 | **102.3 Hz**(男声范围 85~155Hz) | **约 190~205 Hz** |
| 音色可选? | ❌ 只有一个固化音色 | ✅ **可换**(cv01~cv05 / qwen_hk …) |
| 授权 | 模型 **Apache-2.0** · 本模块代码 **AGPL-3.0** | 模型同源 + 参照音色 **CC-0** · 本模块代码 **AGPL-3.0** |

> ⚠️ 2026-09-22:右列原来是平板那个 110MB sherpa-onnx VITS。
> 它已按 Nija 令退役删除(CER 22.0%、发行包无许可声明)⇒ 女声改由**本模型 + 零样本克隆**提供
> (同一个权重、同一套粤语发音路径,只换音色 codes)。见 `~/dev/female-moss-nano-port/`。
> 具体型号与证据见 `NOTES.md` §旧 VITS 退役(史册)。

moss-nano-port 官方 README 原话:

> 「單一 default voice —— ONNX 路線暫時未支援 voice cloning、冇 voice 揀」

**⇒ 部署它是给粤语播报加一个男声选项,不是把原来那个女声"换得更好"。**
A/B 对照样本在 `~/Documents/repo/voice-tts/docs/samples/`(A=女声 / B=男声),自己听。

---

## 部署状态(2026-09-22 实测)

| 机器 | 架构 / 系统 | 合成 | 播放 | 验收 |
|---|---|---|---|---|
| **笔记本 黑仔** | x86_64 / Arch Linux | ✅ | ✅ `paplay` | **12 通过 / 0 失败** |
| **平板 格仔** | aarch64 / Debian 13 chroot | ✅ | ❌ **受限** | **11 通过 / 1 失败** |
| **机仔 bbStation** | — | — | — | ❌ **机器不可达**(主板/电源疑似故障,无 POST) |

### ⭐ 2026-09-22 晚更新:本机默认朗读已切到 moss-nano-port + 4x 加速落位

| 事项 | 结果 |
|---|---|
| **默认朗读** | 笔记本 `/usr/local/bin/vsay` **默认 → moss-nano-port(男声)**。~~旧 qwen3 女声(2.2GB)保留但降级为可选~~ ⇒ ⚠️ **2026-09-22 更晚:qwen3 已【退役归档】,moss-nano-port 成为唯一模型**(见 `NOTES.md` §12.4) |
| **4x 加速** | `/opt/moss-nano-port/lib/tts_stream.py` 已落位;`vsay-canto` 接上**三级回退**(L1 常驻 / L2 一次性 / L3 旧逐段) |
| **实测提速** | 笔记本 **2 段 10751→5091 ms(2.1x 中位,最快 3.1x)**、**3 段 15619→4890 ms(3.2x 中位,最快 4.4x)** |
| **代价** | 守护常驻 **1.62 GiB**;默认 `--idle-timeout 300s` 空闲自退,不做开机自启 |

> ⚠️ **诚实说明**:4x 是**第二次起**的收益。第一次(冷启)要付守护的模型加载
> (实测 3.6~5.0s),冷启 2 段约 **6.9~7.0s**,对旧版 10751ms 仍有 ~1.5x。
> 完整数据、复跑命令、两个踩到的坑:见 `NOTES.md` **§11.8**。

### ⚠️ 平板的播放为什么不行(不是没装好)

平板 chroot 里**合成完全正常**(实测出 460844 字节 / 48kHz 立体声 / 2.40 秒的 wav),
但**放不出声**。三层原因,都已实测确认:

1. 声卡被 Android 的音频 HAL(`ohalservice.qti`)**独占**;
2. `pcmC0D0p` 是 `system:audio` 0660 且 SELinux **Enforcing**;
3. 就算把权限放开成 666,**每个 PCM(hw/plughw 0,0 / 0,1 / 0,2)一律 `write error: Invalid argument`**
   —— DSP 的路由由 HAL 掌管,chroot 驱动不了。

Android 侧的 `tinyplay` 也打不开设备;`app_process` 在 Android 16 上直接 abort(加固)。
⇒ **平板要出声,必须走 Android 自己的音频框架(`AudioTrack`)+ 一个 App**,
   也就是 `~/Documents/repo/voice-tts/docs/平板粤语TTS-自播放方案.md` 的「方案 C」。
   本模块把参考实现留在 `assets/android/`(含踩坑注释),但那属于 App 那条线,**不在本模块范围**。

**⇒ 平板上本模块的定位是「合成器」:出 wav,交给 App 播。**

---

## 这是什么(本质)

粤语(香港)文字转语音。给它中文文本,它吐一个 `.wav`。

- **引擎**:`moss-nano-port` 0.1.4(pip 包,Apache-2.0)+ ONNX Runtime,**纯 CPU**
- **模型**:`typangaa/canto-tts-nano`(约 729MB,已归档进本包)
- **G2P**:`canto-hk-g2p` 2.6.1(Rust 编译的粤语字→粤拼转换,**有 aarch64 轮子**)
- **音色**:单一男声,不可选
- **速度**:笔记本上 2 段约 **5.1 秒**(常驻热态;旧版逐段重载是 10.8 秒)⇒ **2.1x**;
  ⚠️ 冷启第一次约 6.9 秒(要加载模型),第二次起才快 —— 见 NOTES §11.8
- **文本**:繁简都能吃(实测「今天天气很好」和「今日天氣幾好」都出音),夹英文也认

它和家里的语音体系怎么联动:

```
                          ┌──► vsay-canto ──► moss-nano-port(ONNX 男声)──┐
文本 ──► vsay(默认)──────┤                                          ├──► .wav ──► paplay ──► 喇叭
         │  默认引擎=canto └──► vsay-canto-female(克隆女声)─────────┘
         │                     (canto 男↔女 互退:缺一个就退另一个;
         │                      两条都缺 ⇒ 明确报错 exit 1,不哑掉)
         └──► 回退链【不再指向 qwen3】—— 它已于 2026-09-22 退役归档
```

> ⚠️ **2026-09-22 晚:qwen3-tts(2.2GB)已退役归档** ⇒ **moss-nano-port 成为唯一模型**。
> `vsay -e qwen3` 现在**明确报错 exit 3**;归档件 + 一键恢复见
> `../voice-tts/retired/qwen3-tts/`(本 README 下方旧图/旧命令是**当时的事实**,保留不改)。

**不碰** `voice-tts` / `android-voice-ime` 的任何代码 —— 那是已验收的其它线。
本模块只做"加一个男声",插上就赋能、拔掉就干净。

---

## 怎么用(操作)

### 装机

```bash
cd ~/Documents/repo/moss-nano-port
./install.sh              # 本机(需 root;本机纪律:自动走 sbrun 提权)
./install.sh --target tablet   # 装到平板格仔的 Debian chroot
./install.sh --dry-run    # 只看要做什么,不动手
./install.sh --offline    # 强制离线(不许联网兜底)
```

### 出声

```bash
vsay-canto "今日天氣幾好，多謝晒。"        # 直接播
echo "多謝晒。" | vsay-canto               # 从管道来
vsay-canto -o /tmp/a.wav "..."             # 只存文件不播
vsay-canto -n "..."                        # 合成但不播
vsay-canto --no-daemon "..."               # 诊断:跳过常驻守护,只走 L2/L3
```

### ⭐ 默认朗读(本机)

```bash
vsay "今日天氣幾好"                # ← 默认 = moss-nano-port 男声(走本模块)
vsay -e female "今日天氣幾好"       # 女声 = moss-nano-port 克隆(音色可换 VSAY_FEMALE_VOICE)
```

> ⚠️ **2026-09-22 晚:qwen3 女声与轻量 VITS 均已退役** —— 下面这几条是**当时的命令**,保留作史册:
> ```bash
> vsay -e qwen3 "今日天氣幾好"        # 🔴 已退役 ⇒ 现在【明确报错 exit 3】
> vsay-yue-qwen3 "今日天氣幾好"       # 🔴 命令已移入 retired/ 归档
> vsay -e lite "今日天氣幾好"         # 🗑️ 轻量 VITS 已于 2026-09-22 退役删除
> ```
> 恢复 qwen3 女声:`../voice-tts/retired/qwen3-tts/restore.sh`

DSH 的 🔊 按钮经 `~/dev/dsh-tts/server.js`(`:8790`)**调用 `vsay`** ⇒ 现在默认就是男声。
它历史上的调用写法是 `vsay -m zhll <正文>`;`-m` 已被**吞掉**不再念出来(见 NOTES §11.9)。

### 验收 / 卸载 / 打包

```bash
./verify.sh --play          # 通过/失败清单 + 真播一遍
./verify.sh --target tablet # 验平板
./uninstall.sh              # 干净移除
./uninstall.sh --keep-model # 卸了但留住模型权重
./pack.sh                   # 打成单文件(含模型+双架构轮子)
./pack.sh --slim            # 瘦身:只本机架构轮子,不含模型
N=7 ./bench.sh              # 改前/改后耗时对比(自动拿 backups/ 里的旧版当对照)
```

---

## 装到哪儿了(路径契约 · FHS)

| 放什么 | 位置 | 为什么 |
|---|---|---|
| python venv | `/opt/canto-tts-venv` | 程序 = add-on software |
| 模块本体+文档 | `/opt/moss-nano-port` | 同上;**文档随包走**,换机器不靠翻会话 |
| 安卓侧播放器参考实现 | `/opt/moss-nano-port/assets/android/` | 给「方案 C」App 抄的 `AudioTrack` 代码(见 NOTES §7) |
| 模型权重 | `/var/lib/moss-nano-port/model` | 状态/数据归 `/var/lib` |
| 用户命令 | `/usr/local/bin/vsay-canto` | 自装命令的标准位 |
| 离线轮子仓 | `pkg/<arch>-py<ver>/*.whl` | 非官方仓库包必须归档进包内 |
| venv 引导 deb | `pkg/deb-aarch64/*.deb` | 平板的 `python3-venv`(官方仓库包,为完全离线也归档) |

⚠️ **`pkg/` 和 `models/` 是"安装介质",不装进 `/opt`** ——
装完就没用了,复制过去等于白占 ~850MB(踩过:`/opt/moss-nano-port` 一度 853MB)。
`install.sh` 拷进 `/opt` 时会排除它们,平板装完也会清掉 chroot 里的副本。

平板侧全部装**在 chroot 内部** `/data/local/linux/debian` 之下,路径与笔记本同名同层(同构)。
实测足迹:`/opt/moss-nano-port` 110K + `/opt/canto-tts-venv` 191M + `/var/lib/moss-nano-port` 730M ≈ **921MB**。

**没有** `/etc/profile.d/` 注入、**没有** `.bashrc` 改动、**没有** systemd 单元、
**没有**常驻守护 —— 靠绝对路径的命令即可,零全局污染(熵减)。

---

## 环境变量(全部可选)

| 变量 | 默认 | 作用 |
|---|---|---|
| `VSAY_CANTO_MODEL` | `/var/lib/moss-nano-port/model` | 模型目录 |
| `VSAY_CANTO_VENV` | `/opt/canto-tts-venv` | venv 目录 |
| `VSAY_CANTO_CHUNK` | `50` | 每段最大字数(nano 模型对长文本会漂,切段更稳) |
| `VSAY_CANTO_MAXTOTAL` | `0` | 限制总字数(0=不限) |
| `VSAY_CANTO_DEADLINE` | `180` | 总时长上限秒数 |
| `VSAY_CANTO_PLAYER` | 自动探测 | 强制播放器 |
| `VSAY_CANTO_KEEP` | 空 | 非空则保留临时目录(调试) |
| `VSAY_CANTO_DAEMON` | `1` | `0` = 禁用常驻守护,只用 L2/L3 |
| `VSAY_CANTO_STREAM` | `/opt/moss-nano-port/lib/tts_stream.py` | 加速器路径(缺失则退回开发场那份) |
| `VSAY_CANTO_SOCK` | `$XDG_RUNTIME_DIR/moss-nano-port.sock` | 守护 socket。⚠️ Android 没有 `/run`,平板要显式指到 `/data/local/linux/` |
| `VSAY_CANTO_IDLE` | `300` | 守护空闲多少秒自动退出还内存(0=永不退出) |

播放器自动探测顺序:`paplay`(Pulse)→ `pw-play`(PipeWire)→ `aplay`(裸 ALSA)。
⚠️ **不光看二进制在不在,还要看服务在不在**(Pulse 的 socket / PipeWire 的 `pipewire-0`);
平板 chroot 里装了 `pw-play` 却没有守护 ⇒ 只看二进制会"探测通过、播放必失败"。

---

## 命令行速查

```bash
# 引擎直调(绕过包装,排障用)
/opt/canto-tts-venv/bin/moss-nano-port synthesize "多謝晒。" -o /tmp/o.wav \
    --checkpoint /var/lib/moss-nano-port/model

# 量一下时长/采样率
ffprobe -v error -show_entries format=duration -of default=nw=1 /tmp/o.wav

# 手测平板(不进 UI,全命令行)
adb devices
adb -s <目标> shell "su -c 'chroot /data/local/linux/debian /bin/bash -c \"export PATH=/usr/local/bin:/usr/bin:/bin; vsay-canto -o /tmp/x.wav 多謝晒\"'"
```

---

## 相关

- 维护笔记(踩坑全在里面):`NOTES.md`
  - **§11.8** 4x 加速落位方案(三级回退)+ 实测对比
  - **§11.9** 顺手修掉的真 bug:DSH 🔊 一直在念「m zhll」
  - **§12** 🗑️ **2.2GB 旧模型(qwen3-tts)去留评估** —— §12.4 = ✅ **已执行:退役归档**
- **qwen3 退役归档(说明书 + 一键恢复)**:`~/Documents/repo/voice-tts/retired/qwen3-tts/`
  - 冷存储位:`/var/lib/retired/qwen3-tts-hk/` + `/opt/retired/qwen3-tts/`
  - ⚠️ `vsay -e qwen3` 现已**明确报错 exit 3**;`vsay` 的**回退链不再指向 qwen3**
- 语音体系总维护笔记:`~/Documents/repo/voice-tts/`(本模块的 `vsay` 入口在那边装)
- A/B 对照样本:`~/Documents/repo/voice-tts/docs/samples/`
- 验收脚本:`./verify.sh`(12 项,全绿才算数)


## 普 → 粤(p2y)

DSH 的回复是普通话书面文,直接喂粤语模型 = **半咸淡**。
⇒ 合成前先过一层普→粤词表转换(纯规则,零模型零延迟)。

- 词表与工具在 **voice-tts 模块**:`voice-tts/backend/p2y.py`(+ `README-p2y.md`)
- 接入点 3 处:`vsay`(黑仔/平板)· `dev/dsh-tts/server.js`(含绕过 vsay 的 `/speak-audio`)
- 一键关闭:`VSAY_P2Y=0`(或 `DSH_TTS_P2Y=0`)
- 验收:`tools/cer_eval.py`(粤语 ASR 量 CER + 产人耳 A/B)
- 完整设计/过改检测/实测数字:**见 `NOTES.md` §13**

---

## 📦 模型不进 Git —— 怎么拿到它们

> **为什么**:模型是 600MB+ 的权重,塞进 Git 会让仓库无法使用。
> 本仓库只含**代码 + 音色库 + 工具**;模型请按下面两步获取。

### ① 粤语(必需)

```bash
# 微调权重(683MB,Apache-2.0)
huggingface-cli download typangaa/canto-tts-nano --local-dir models/canto-tts-nano
```

### ② 普通话 / 英文 / 日语(可选)

```bash
# 基础版权重(642MB,Apache-2.0)。⚠️ 组织名是 OpenMOSS-Team
HF_ENDPOINT=https://hf-mirror.com huggingface-cli download \
  OpenMOSS-Team/MOSS-TTS-Nano-100M-ONNX --local-dir models/base-mandarin
```

⚠️ **基础版不带 audio codec** —— 引擎会复用粤语版里的那一份
(`MOSS-Audio-Tokenizer-Nano-ONNX`),所以**粤语包是前提**。

### ③ 目录约定

```
models/
  ├─ <任意名字>/                 ← 引擎扫这个目录,自动注册成"一个模型"
  │    ├─ browser_poc_manifest.json    (必需 —— 有这个才算模型)
  │    ├─ added_tokens.json            (有 ⇒ 粤语三元组;无 ⇒ SentencePiece)
  │    ├─ voicebank/*.json             (音色来源之一)
  │    └─ model.json                   (可选:display_name / languages / default_voice)
  └─ …
```

**`model.json` 示例**(推荐写上,引擎会用它显示模型名、声明语言、挑默认音色):

```json
{
  "display_name": "MOSS-TTS-Nano 基础版(普/英/日)",
  "languages": ["zh", "en", "ja"],
  "default_voice": "Zhiming"
}
```

⇒ 放好后打开 App,首页「模型」区就能看到它、点它、用它的音色。

### ④ 离线自洽(整包迁移)

若你拿到的是**已经装好的整包**,模型在 `models/` 里,`./install.sh` 会优先用包内模型
(不联网、不下载)。**这才是本项目的主推用法** —— 见 `android/README.md`。

