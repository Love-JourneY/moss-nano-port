# moss-nano-port for Android —— 便携包(AGPL-3.0)

> ## ⚠️ 2026-09-27 重大变更:**本项目已不再依赖 root**
>
> 原先的「root 守护 + 开机自启 + 权限弹窗」整条链路**已全部移除**。原因(实测):
>
> | 路径 | S7 循环 | S9 端到端 |
> |---|---|---|
> | 无 root(App 自己跑) | **1266 ms** | **1811 ms** |
> | root 守护 | 1270 ms | 1745 ms |
>
> ⇒ **性能几乎一样** ⇒ 守护失去存在理由。
>
> **不被 ColorOS virtualFreeze 冻的原理**(实测三组对照):
>
> | 场景 | oom_adj | wchan | 30 秒跑完? |
> |---|---|---|---|
> | 无 overlay + 退后台 | 450 | `do_freezer_trap` | ❌ 卡 6 秒 |
> | **有 overlay + 退后台** | **0** | **`do_epoll_wait`** | ✅ **完整** |
>
> ⇒ App 挂一个 **1×1 透明 `TYPE_APPLICATION_OVERLAY`**(常驻)即可保持"前台可见"。
> ⇒ 需要 `SYSTEM_ALERT_WINDOW` 特殊权限(用户授权;按 §P2 必须弹窗告知用途)。
>
> 旧文档里的 `/data/adb/service.d/moss-nano-port-daemon.sh`、`daemon-start.sh`、
> `tuning.properties` 的 `daemonPort` 等**均已不存在** —— 不要再按旧文操作。


> **把 canto-tts 的 ONNX 推理搬进 Android 进程,注册成系统 TTS 引擎。**
> **一个目录 = 全部。`cp -a` 拖到另一台机器,`./install.sh` 一条命令装好。**
> 日期:**2026-09-24**

## 这是什么 · 为什么不是"一个 APK"

| | |
|---|---|
| **APK**(12.0 MB) | 代码 + ORT native 库 + 小资产(p2y 表 25KB · 音节表 156KB · 音色 3KB) |
| **模型**(684.5 MB) | `tts/` + `codec/` + G2P 数据表 —— **塞不进 APK** |

**⚠️ 模型底价砍不动**:`global_shared 420.7 + local_shared 219.6 + codec decode_shared 42.2 = 682.5MB`
(可砍的死代码只有 43.3MB:`codec_encode` 42.4 + `local_decoder`/`local_cached_step`)。
Play 基础包上限 100MB ⇒ **便携包形态是对的**,而且它正好满足我们的「基础设施纪律」八条。


## ⚠️ 设备要求(2026-09-24 实测得出,不是猜的)

**先跑 `./check-device.sh --target <adb>` —— 30 秒内告诉你这台设备能不能跑。**

| 要求 | 判据 | 为什么 |
|---|---|---|
| **ABI** | `arm64-v8a` | 我们只打包了它(ORT + G2P 的 .so) |
| **SDK** | >= 24 | APK 的 minSdk |
| **MemAvailable** | **> 2.5 GB** | 模型常驻需 ~1.2GB;不够则内核换出,随后被冻结 |
| **zram 已用** | **<= 6 GB** | 超过说明系统整体压力大,我们的常驻模型会被当"可回收" |
| **无 virtualFreeze** | ColorOS / OPPO / OnePlus 系要小心 | 实测:它会冻结"不活跃"进程,**即使该进程被 system_server 绑定** |

### 在Android 设备(OnePlus OPD2413 / Android 16)上的实测结论

```
技术链【全部打通】(有日志证据):
  系统注册 OK -> 系统绑定 OK -> 服务启动 OK -> 引擎就绪(voices=6)OK
  -> G2P OK -> ORT 4 Session 加载 OK -> 系统调 onSynthesizeText OK -> 粤语可用 OK

但【出不了声】:
  683MB 模型 -> RSS 冲到 1.14GB
  -> 内核把 941MB 换出到 zram(实测 VmSwap: 941,432 kB)
  -> 进程被判"不活跃"(lastActivityTime=-3m12s)
  -> virtualFreeze: true(ColorOS 的虚拟冻结)
  -> 合成线程卡在 do_freezer_trap => 永远完不成
```

**结论:这是【设备内存策略】问题,不是代码问题。**

### 已排除的解法(都有实测依据,别重试)

| 解法 | 结论 | 依据 |
|---|---|---|
| **fp16 量化** | **内存反而多 92MB** | `tools/exp-fp16-memory.py` **可复跑** |
| ORT 关 memory-pattern / CPU arena | 完全无效(RSS/Swap 曲线一模一样) | 实测 |
| 改 mmap | **本来就是 mmap**(`/proc/PID/maps` 里有 `.data` 映射) | 实测 |
| int8 量化 | 只降 20% + 音色漂移 | 早前实测 |
| 冻结反制 x7 | 全部无效 | deviceidle 白名单 / `am set-inactive false` / PARTIAL_WAKE_LOCK / 每 8s 重取锁 / setThreadPriority(FOREGROUND) / **`startForeground`(mediaPlayback)** —— 实测 `isForeground=true` 仍被冻 / **前台客户端持绑 + 亮屏 + wakelock** —— 照样被冻 |

### 出路

| # | 出路 | 可行性 |
|---|---|---|
| 1 | **换内存更宽裕的设备**(先跑 `check-device.sh`) | 确定可行 |
| 2 | **改架构**:轻量部分(G2P + p2y)在端上,重活回主机 | 确定可行,但**违背"进程内推理"初衷** |
| 3 | 减参数量(重训 / 蒸馏) | 工程量大 |

## 八条硬要求对照

| # | 要求 | 本包怎么满足 |
|---|---|---|
| 1 | **整包可迁移** | `apk/` + `model/` + 三个脚本 + 文档 = 一个目录;`cp -a` 即搬 |
| 2 | **离线自洽** | 模型/资产/G2P .so **全在包内**;`install.sh` 只推文件,不联网 |
| 3 | **不依赖智能体** | 恢复只靠 `install.sh`;无大模型、无网络 |
| 4 | **一条命令部署** | `./install.sh`(**幂等**、可重复跑、带 `--dry-run`) |
| 5 | **能验收** | `./verify.sh` 给通过/失败清单(硬断言,含**属主检查**与**ColorOS 风险检查**) |
| 6 | **能卸载** | `./uninstall.sh` 干净移除(⚠️ 先把默认引擎还回去,否则系统 TTS 会哑) |
| 7 | **真持久** | APK 是系统 TTS 引擎(开机自动挂载);模型在 App 私有目录,重启仍在 |
| 8 | **模块化可插拔** | 拔掉 = 恢复默认引擎 + 卸载包,**不碰** IME/识别/剪贴板/DSH 🔊 |

## 快速开始

```bash
./install.sh --dry-run              # 先看计划(零副作用)
./install.sh                        # 部署
./verify.sh --target <adb>          # 验收
./uninstall.sh --target <adb>       # 移除
```

## 🔴 五条实测教训(全是真金白银踩出来的,别重踩)

### ① 模型子目录的属主必须是 App uid,否则 EACCES(errno 13)
```
/sdcard/Android/data/canto.tts          → u0_aXXX ext_data_rw  ✅ 系统建的
/sdcard/Android/data/canto.tts/files    → u0_aXXX ext_data_rw  ✅ 系统建的
/sdcard/Android/data/canto.tts/files/models/canto → shell ...  ✗ adb mkdir 建的!
⇒ 【不是"别放 Documents"那么简单】:只要子目录是 adb mkdir 出来的,属主就是 shell
⇒ install.sh 里必须 chown -R <app_uid>:ext_data_rw
```

### ② 模型放【内部存储】比 /sdcard 快 3.2 倍
```
从 /sdcard/... (FUSE MediaProvider) 建 4 个 session: 10985 ms
从 /data/data/<pkg>/files/... (真文件系统)        建 4 个 session:  3403 ms
⇒ 首启拷进内部存储;别让引擎常驻从 /sdcard 读
```

### ③ 🔴 ColorOS osense 会压缩前台服务(头号未验证风险)
```
真 App 进程实测:ORT 能跑,但被 osense 掐停
  VmRSS 1157MB → 206MB,推理停在中途
  此时 isForeground=true · oom_score_adj=200 ⇒ 【前台服务真生效也照样被压】
  5 种反制全部无效:deviceidle 白名单 / am set-inactive false / PARTIAL_WAKE_LOCK /
                   每 8s 重取锁 / setThreadPriority(FOREGROUND)

⇒ TextToSpeechService 被系统 TTS 框架【bind】,重要性更高 ⇒ 【很可能】不受影响
⇒ 【但这是"很可能",不是"已验证"】⇒ verify.sh 里专门测:连续合成 N 句,查中途停滞
```

### ④ 线程数:Android 与 chroot 的结论【相反】
```
Android(bionic ORT, 8 核)   loop 耗时
  threads=1  2026ms | 2  2368ms | 4  2706ms | 8  4686ms(差一倍)
⇒ 【1~2 最快】;而 chroot 时代记的是"4 最优" ⇒ 别照抄
⇒ 1/2/4 输出 md5 完全相同 ⇒ 线程数不影响数值,只影响速度
⇒ 已默认 intraOpNumThreads=2
```

### ⑤ 输出天然不确定 ⇒ 引擎默认固定 seed
```
根因:should_continue 是【图内由文本侧采样结果推出来的】,
     而文本侧采样吃【我们喂的 u】⇒ 【终止条件本身是随机的】
     ⇒ 同一句话的时长会波动(实测 7.52/7.12/9.76s)
⇒ 作为系统 TTS 引擎,可预测的延迟比"每次不同"更重要
⇒ 已默认固定 seed(DEFAULT_SEED=20260923L),留 setSeed() 要多样性时用
```

## 架构(数据流)

```
文本
 ├─ P2y            普→粤(1907 条规则表;逐字节与 Python 版一致)
 ├─ CantoSegmenter 切段(默认 NONE 不切;240 字以上由调用方兜底)
 ▼
G2P(libcanto_g2p.so,交叉编译的 Rust;JNI 桥 libcanto_g2p_jni.so)
 ▼
CantoTokenTable   音节→token ids(7930 条表;【不需要 sentencepiece】)
 ▼
CantoOrtEngine    prefill → decode 循环(每帧 17 随机数)→ codec decode
 ▼
int16 PCM → TextToSpeechService 的 callback(⚠️ maxBufferSize 单位是【字节】)
```

## 文件清单

```
android/
├── install.sh / verify.sh / uninstall.sh   ← 三脚本(八条要求)
├── README.md                                ← 本文
├── device.mac                               ← 设备寻址(⚠️ MAC 优先,IP 只当回退)
├── apk/moss-nano-port.apk                 ← 12.0 MB,已签名
├── apk/AndroidManifest.xml                  ← 四件套声明(留档)
└── model/                                   ← 684.5 MB(若随包分发)
```

## 许可

- **本目录脚本与文档**:AGPL-3.0-or-later(见模块根 `LICENSE`)
- **canto-tts 权重 / MOSS-TTS-Nano / pip 包**:Apache-2.0
- **ONNX Runtime**:MIT
- **canto-hk-g2p**(G2P 的 Rust 源):Apache-2.0;其数据含 **CC BY 4.0**(rime-cantonese)
- **音色 cv03**:Common Voice 22 yue,**CC-0**
- 逐项见模块根 `NOTICE`

## 🔑 架构:为什么需要一个 root 守护(2026-09-25 定案)

```
系统 TTS 框架 ──bind──▶ canto.tts App(uid 10326)
                          · TextToSpeechService(四件套声明)
                          · onSynthesizeText:【不立刻 start()】
                          · 线程:ping 守护 → 发文本 → 收 PCM
                                  → start() + audioAvailable() + done()
                          ⚠️ 整段 ≤ ~2 秒(否则 App 会被系统冻结)
                              │ 127.0.0.1:<daemonPort>
                              ▼
                        CantoDaemon(root, uid 0, 常驻)
                          · install.sh 用 su + setsid 起 + 开机自启
                          · 模型加载一次(683MB)常驻
                          · 完整推理(【不被系统冻结】)
```

**为什么必须这样(实测依据)**:
- ColorOS 的 `virtualFreeze` 会把【普通 App 身份】进程内的长时间推理冻死
  (合成期间 CPU 时间在 t≈6s 后完全停止;wchan=do_freezer_trap)
- **10 种 App 侧对策全部实测无效**:deviceidle 白名单 / set-inactive /
  PARTIAL_WAKE_LOCK / 每 8s 重取锁 / threadPriority / startForeground(两种) /
  降帧数 / 前台客户端持绑 + 亮屏 + wakelock
- **6 条减内存路径也全部无效**:fp16(反而多 92MB)/ int8(音色漂移)/
  按图裁剪(只剩 3MB 可砍)/ 砍死代码(仅 42MB)/ ORT 调优(反而多 139MB)/ mmap
- **而 uid=0 的进程连续烧 CPU 70 秒【从不被冻】**(实测)

## ⚠️ 五条部署约束(必须知道)

| # | 约束 | 后果 |
|---|---|---|
| 1 | **需要 root**(KernelSU / Magisk / APatch) | 无 root ⇒ App 回退本地推理(会被冻,可能不出声) |
| 2 | **APK 必须有 `INTERNET` 权限** | 连 127.0.0.1 也要;缺了守护永远"连不上" |
| 3 | **`maxFrames` ≤ ~25**(≈2 秒音频) | 长了 App 在等待窗口里被冻,读不到 PCM |
| 4 | **`callback.start()` 必须在拿到 PCM 之后** | 否则框架提前 UTTER_DONE ⇒ 解绑 ⇒ 服务销毁 |
| 5 | **`daemonPort` 在 `tuning.properties` 与 `install.sh` 必须一致** | 不一致 ⇒ 连不上(默认 18790) |

## ⚠️ root 权限提示:统一模块(不许静默失败)

**依据 `~/AGENTS.md` §P2** —— 「依赖 root 的自研 App 必须弹窗告知,不许静默失败」。

```
本引擎【不自己写】root 提示,而是复用统一模块:
  资产副本: assets/android-engine/rootgate/com/bbsoy/rootgate/RootGate.java
  ⚠️ 原「root 网关」模块(~Documents/repo/android-rootgate/)已于 2026-09-28 【整体退役删除】。
     本 App 现【不依赖 root】—— 进程内推理 + 常驻透明 overlay 防冻,详见 NOTES.md §去 root。
```

**它做三件事**:① 用 root 前弹窗说明(逐条列为什么 + 用途范围)
② 成功/失败都弹窗(失败附"怎么办") ③ 进界面即探测,不可用【主动弹窗】。

**⚠️ 两个实测踩过的坑(已内置解决)**:
1. **`su -c id` 会阻塞等授权框** —— 不批准就**永不返回** ⇒ 弹窗永不出现。
   ⇒ 内置 **8 秒超时**,超时视为未授予。
2. **Android 11+ 包可见性**:App 默认**看不见任何包** ⇒ 必须在 manifest 声明 `<queries>`,
   否则 `getLaunchIntentForPackage("me.weishu.kernelsu")` **永远返回 null**。
   ⇒ 已声明 6 个 root 管理器包名;**且 root 环境隐身时跳不过去是正常的** ——
     此时弹窗会**明确告知**"请手动去 root 管理器授权"(不静默失败)。

