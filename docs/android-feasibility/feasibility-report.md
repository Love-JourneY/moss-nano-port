# Android 进程内 canto-tts(MOSS-TTS-Nano)ONNX 推理 —— 可行性验证报告

> 日期:2026-09-23 · 设备:平板「格仔」OPD2413(arm64-v8a,Android 16 / SDK 36,8 核,15.8GB RAM)
> 本机:黑仔(Arch,`/opt/android-sdk`,ORT 1.30.0)
> 原型工程:`~/dev/android-canto-tts/`

---

## 1. 核心答案:Android 上跑得动吗?

# ✅ 跑得动。而且不只是"跑起来了",是**逐样本对得上**。

在平板**本机进程内**用 `onnxruntime-android` 1.30.0 跑完整链路,合成出粤语音频:

| 证据 | 结果 |
|---|---|
| ORT native 库加载 | ✅ `libonnxruntime.so` + `libonnxruntime4j_jni.so`(NDK r28,arm64-v8a) |
| 外部权重 `.data` 解析 | ✅ 729MB 权重,`.onnx` 与 `.data` 同目录即自动解析 |
| Session 创建 | ✅ 8 个图**全部**能建会话 |
| **完整链路合成** | ✅ 粤语文本 → 16 路音频码 → 48kHz 立体声 PCM → WAV |
| **与 Python 参照实现对比** | 帧数 **33==33** · 随机数 **578==578** · 样本数 **126720==126720** |
| 样本级最大绝对差 | **1**(int16 满量程 32767) |
| 完全相同的样本占比 | **99.85%** |
| 相关系数 | **0.9999999999** |
| 客观音频指标 | 时长/F0/RMS/有声帧占比 **逐项完全相同** |

> **残余的 ±1 LSB 不是逻辑错误**,是 numpy `np.round`(四舍六入五成双)与
> Java `Math.round`(四舍五入)的**舍入模式差异**。移植逻辑本身是正确的。

**产出音频**:`results/android_canto.wav`(与 `results/ref_canto.wav` 参照对照)
内容:「今日天氣幾好。」→ 2.640 s / 48kHz / 立体声 / F0 中位数 150.9 Hz(男声)

---

## 2. 最小原型:在哪、怎么跑、跑出什么

### 2.1 位置与结构

```
~/dev/android-canto-tts/
├── build.sh           所有 Java → 一个 dex(javac + d8,零 gradle)
├── deploy.sh          推 dex + native .so 到平板 /data/local/tmp
├── build-apk.sh       手工打造最小可安装 APK(aapt2 + d8 + zipalign + apksigner)
├── run-graphs.sh      逐个跑 8 个图,取真实 IO 契约 + 单图内存
├── sweep.sh           线程数扫描 + 可重复性
├── host-prepare.py    本机生成常量 + 参照音频(ground truth)
├── probe/
│   ├── CantoEngine.java       ★ 完整链路唯一实现(CLI 与 App 共用)
│   ├── CantoConstants.java    host-prepare.py 自动生成(模型常量)
│   ├── FullChain.java         CLI 包装(app_process 跑)
│   ├── OrtProbe.java          单图 IO 契约探针
│   └── LoadTest.java          分步加载探针(定位崩溃用)
├── app/
│   ├── SynthService.java      ★ 真 App 进程内的无界面 Service
│   └── AndroidManifest.xml
├── docs/python-pipeline.md    1099 行调用序列说明书(精确到张量名/形状/dtype)
├── dist/canto-probe.apk       12.5 MB 可安装 APK(无 UI)
└── results/                   音频 + 实测日志
```

### 2.2 怎么跑(两条路)

**路 A:app_process(工程验证 / 压测,不用装 APK)**

```bash
cd ~/dev/android-canto-tts
./build.sh && ANDROID_SERIAL=<target> ./deploy.sh
# 首次:把模型推到平板
adb push /var/lib/canto-tts/model /data/local/tmp/canto-ort/model/
# 跑
adb shell "CLASSPATH=/data/local/tmp/canto-ort/canto.dex \
           LD_LIBRARY_PATH=/data/local/tmp/canto-ort/lib \
           app_process /system/bin canto.FullChain \
           <ttsDir> <codecDir> /data/local/tmp/canto-ort/out/x.wav 2"
```

**路 B:真 APK(真 App 进程)**

```bash
./build-apk.sh
adb install -r dist/canto-probe.apk
adb shell am start-foreground-service -n canto.probe/canto.SynthService
adb logcat -s CantoSynth          # 固定 TAG,便于自动化验证
```

> ⚠️ **路 B 有两条必须做的部署动作**(见 §4.3):
> ① 模型目录必须 `chown` 成 App 的 uid;② 模型应放**内部存储**而非 `/sdcard`。

### 2.3 跑出什么

```
✅ 4 个 Session 创建   3403 ms   (内部存储;从 /sdcard 读要 10985 ms)
✅ prefill             597 ms   (prompt 142 行 × 17 列)
✅ 自回归循环         2086 ms   (33 帧 = 2640 ms 音频;采样 + decode)
✅ codec 解码          380 ms   → 126720 样本/声道 = 2.640 s
   端到端             6566 ms   (冷启动,含加载;热态约 3.0 s)
   进程峰值 RSS       1228 MB
   输出              android_canto.wav (506924 B,与参照**字节数相同**)
```

---

## 3. 内存与性能实测

### 3.1 内存:不是问题(而且是好消息)

| 项 | 实测 |
|---|---|
| 进程峰值 RSS | **1228 ~ 1260 MB**(1.20 ~ 1.23 GiB) |
| 现有 chroot 常驻路径自记 RSS | 1.46 GiB |
| ⇒ 对比 | **Android 进程内比现有 chroot 路径更省** |
| 平板总内存 / 可用 | 15.8 GB / **4.4 GB** |
| 结论 | **余量充足,内存不是这条路的障碍** |

**为什么比预想省**:ORT 的 external data 是**按需取用**的。
`local_fixed_sampled_frame.onnx` 只有 471KB,却引用 220MB 的 `local_shared.data`,
实际只让 RSS 涨 141MB。

⚠️ **纠正任务书里的一个假设**:"拿最小的图做轻量测试"**不成立** ——
8 个图**全部**依赖外部 `.data`,最小的图也引用 220MB。

### 3.2 单图加载/推理(每个图独立进程)

| 图 | Session 创建 | 推理(零张量) | 进程峰值 RSS |
|---|---|---|---|
| `moss_tts_prefill` | 780 ms | 37 ms | 527 MB |
| `moss_tts_decode_step` | 652 ms | 22 ms | 528 MB |
| `moss_tts_local_cached_step` | 200 ms | 6 ms | 316 MB |
| `moss_tts_local_decoder` | 226 ms | 26 ms | 323 MB |
| `moss_tts_local_fixed_sampled_frame` | 1586 ms | 50 ms | 353 MB |
| `moss_audio_tokenizer_encode` | 908 ms | 15 ms | 244 MB |
| `moss_audio_tokenizer_decode_full` | 574 ms | 19 ms | 242 MB |
| `moss_audio_tokenizer_decode_step` | 534 ms | 62 ms | 300 MB |

### 3.3 性能:端到端 ≈ 1.0~1.2× 实时(热态)

- 冷启动(含 684MB 加载)约 6.6 s;热态(常驻引擎)约 **2.5~3.0 s** 合成 2.64 s 音频。
- 循环内部:采样 ~37 ms/帧 + decode ~54 ms/帧 ≈ 91 ms/帧(帧 = 80 ms 音频)。
- 现有 chroot 一次性路径自记为「3~4 倍实时」(含每次重复加载 3.4 s)。
  ⇒ **Android 进程内(模型只加载一次)在延迟上有结构性优势。**

### 3.4 线程数:Android 上**不要**照抄 chroot 的结论

| intra_op | prefill | **循环** | codec | 端到端 |
|---|---|---|---|---|
| 1 | 867 ms | **2026 ms** | 722 ms | 7748 ms |
| **2** | 505 ms | 2368 ms | **422 ms** | **7328 ms** |
| 4 | **429 ms** | 2706 ms | 467 ms | 7285 ms |
| 8 | 623 ms | 4686 ms | 547 ms | **9989 ms** |

- **循环(占大头)在 1~2 线程最快;8 线程差一倍。**
- **threads=1/2/4 的输出 md5 完全相同** ⇒ 线程数不影响数值,只影响速度。
- 参照实现 NOTES 记「4 最优,8 慢 2.3×」是 **glibc/chroot** 的结论,**在 Android 上不成立**。
- ⇒ **建议 `intraOpNumThreads=2`**;要榨干可 **prefill 用 4 / 循环用 1~2** 分图配置。

### 3.5 可重复性:设备端逐字节确定

同一参数独立跑两次,输出 **md5 完全相同**(`2b13a56e…`)。
⚠️ 但这只在**喂同一串随机数**时成立(见 §6.4)。

---

## 4. 模型怎么放(体积问题的解法)

### 4.1 体积底价

| 类别 | 大小 |
|---|---|
| **推理必需小计** | **684.5 MB** |
| ├ `moss_tts_global_shared.data`(prefill+decode 共用) | 420.7 MB |
| ├ `moss_tts_local_shared.data`(local 帧图用) | 219.6 MB |
| ├ `moss_audio_tokenizer_decode_shared.data` | 42.2 MB |
| └ 4 个 `.onnx` + 分词器 + meta | 2.1 MB |
| **可砍的死代码** | **−43.3 MB** |
| └ `moss_audio_tokenizer_encode.onnx` + `.data` | 43.2 MB(全包**零调用点**) |
| └ `moss_tts_local_decoder.onnx` / `moss_tts_local_cached_step.onnx` | 0.1 MB(降级分支) |
| 完整模型目录 | 728.6 MB |

### 4.2 方案对比与建议

| 方案 | 评估 |
|---|---|
| A. 全部塞进 APK `assets/`,首启解压 | APK ≈ 690MB。侧载可行,但**安装体验差**;Play 上架不可能(基础包上限 100MB)。且首启需 684MB 额外临时空间 |
| B. **便携包 = APK + 模型 + 一键脚本** | ⭐ **推荐**。正好满足「基础设施纪律」八条:一个目录、离线自洽、一条命令部署、能验收、能卸载 |
| C. `adb push` 手工推 | 破坏"一键",不推荐 |
| D. 量化到能进 APK | int8 已知会音色漂移,且只降 ~20%(prefill/decode 权重会复制成两份)—— 这条已实测,**不重复** |
| D'. **fp16 转换** | 理论 684 → **~342 MB**(腰斩),足以让方案 A 变得可行。⚠️ **但 ORT CPU EP 对 fp16 权重的处理(是否会 upcast 导致速度下降)未验证**,是一条值得单独做的事 |

**建议落地形态(方案 B 的具体化)**:

```
android-canto-tts/                  ← 整包 cp -a 到另一台设备即可
├── apk/canto-tts-engine.apk
├── model/                         (684.5 MB,已剔除死代码)
├── install.sh    adb install + push 模型 + **chown 成 App uid**
├── verify.sh     启动无界面 Service + grep logcat 固定 TAG + 校验 wav 时长
├── uninstall.sh  卸载包 + 清私有目录
└── README.md     随包文档
```

### 4.3 ⚠️ 两条**实测踩到**的部署坑(打包时一定会撞上)

**(a) 模型目录必须 `chown` 成 App 的 uid,否则 EACCES**

```
/sdcard/Android/data/<pkg>             → u0_a324 ext_data_rw   ✅ 系统建的
/sdcard/Android/data/<pkg>/files       → u0_a324 ext_data_rw   ✅ 系统建的
/sdcard/Android/data/<pkg>/files/model → shell    ext_data_rw  ✗ ← 用 adb mkdir 建的
```
⇒ **不只是"别放 Documents"**:只要子目录是 `adb mkdir` 出来的,属主就是 `shell`,App 就读不了。
ORT 报的是 `system error number 13`(EACCES)。
**`install.sh` 里必须 `chown -R <app_uid>:ext_data_rw <模型目录>`。**

**(b) 模型放内部存储比放 `/sdcard` 快 3.2 倍**

```
从 /sdcard/Android/data/…(MediaProvider FUSE) 建 4 个 session : 10985 ms
从 /data/data/<pkg>/files/…(真文件系统)        建 4 个 session :  3403 ms
```
⇒ 首次启动把模型拷进**内部存储**,之后从内部读。别让常驻引擎从 `/sdcard` 读模型。

---

## 5. 可行性结论 + 下一步方案

### 5.1 结论

| 问题 | 答案 |
|---|---|
| Android 上跑得动吗? | **✅ 跑得动,逐样本验证正确** |
| 内存够吗? | ✅ 峰值 ~1.23 GB,比现有 chroot 路径还省;平板余量 4.4 GB |
| 性能可接受吗? | ✅ 热态 ~1.0~1.2× 实时;冷启动 6.6 s(引擎常驻后可忽略) |
| 移植工作量大吗? | 🟢 **比预想小得多**(见 §5.2) |
| 能当系统 TTS 引擎吗? | 🟡 **技术上没有拦路虎**,但有一条厂商策略风险待验证(§6.1) |

### 5.2 三个让移植变简单的发现

1. **采样逻辑烘在图里** —— 温度/top-k/top-p/重复惩罚全是图内常量
   (`audio 0.8 / top_p 0.95 / top_k 25 / rep 1.2`,已从 `Constant` 节点逐字读出)。
   **Java 侧不需要重写任何采样算法,只要按顺序喂 17 个均匀随机数/帧。**
2. **只需 4 个图**(非流式):`prefill` / `decode_step` / `local_fixed_sampled_frame` / `codec decode_full`。
   `local_decoder`、`local_cached_step`、`codec_encode` 全是死代码。
3. **不需要参照音频** —— 默认音色就是 44×16 的整数表,硬编码即可。

### 5.3 下一步

```
P1(已具备基础)  CantoEngine → 抽成可复用引擎类(本报告已抽出)
P2(端上自洽)    · sentencepiece 词表预导(CantoTokenTable 已在做)
                · canto_hk_g2p 交叉编译 → arm64-v8a .so + JNI(最硬的一块)
P3(引擎化)      TextToSpeechService 子类 + 四件套 + 常驻引擎(吃掉 3.4s 冷启动)
P4(打包)        便携包:apk + model + install/verify/uninstall + README
P5(可选)        · fp16 转换(体积腰斩,需验证速度影响)
                · codec decode_step 流式(降低 TTFB)
                · 音色克隆(codec_encode + 用户参照音频)
```

---

## 6. 仍存在的问题与风险

### 6.1 🟡 真 App 进程:ORT 能跑,但被**厂商内存压缩**掐停

我做了真 APK + 真 App 进程验证(`dist/canto-probe.apk`,12.5MB,无 UI Service):

```
✅ App 进程内 native 库加载成功
   nativeloader: Load …/lib/arm64/libonnxruntime4j_jni.so … : ok
✅ 4 个 Session 创建成功(3403 ms),RSS 1008 MB
✅ prefill 完成,已生成 16 帧 …
❌ 被 ColorOS 的 osense 内存压缩掐停:
   osense.compress: canto.probe do shrink, target ratio = 90 …
   进程 VmRSS 从 1157 MB 被压到 206 MB,State: S (sleeping)
```

**关键**:此时 `dumpsys activity services` 显示
`isForeground=true foregroundId=1 types=0x00000001`、`oom_score_adj=200`
—— **前台服务是真正生效的,ColorOS 照样压缩它**。

已尝试且**全部无效**的反制:deviceidle 白名单、`am set-inactive false`、
持 `PARTIAL_WAKE_LOCK`、每 8 秒 release+acquire 唤醒锁、`setThreadPriority(FOREGROUND)`。

**为什么这不否定可行性**:`TextToSpeechService` 是被**系统 TTS 框架 bind** 的,
进程重要性远高于 adb 拉起的服务。**但这是"很可能",不是"已验证"** ——
P3 必须专门验一条:**连续合成 N 句,检查是否存在中途停滞 / VmRSS 被压缩**。

### 6.2 其他未验证项

| # | 项 | 说明 |
|---|---|---|
| 1 | 系统 bind 的真实重要性 | 见 §6.1,需在 P3 验证 |
| 2 | fp16 体积腰斩 | 理论 342MB,ORT CPU EP 的速度/内存影响未测 |
| 3 | `decode_full` vs `decode_step` 数值等价 | 参照实现作者自述"未逐样本比对";做流式前需 A/B |
| 4 | 音色克隆的参照音频预处理 | 源码无调用点,预处理(重采样/单双声道/归一化)为推测 |
| 5 | 长文本 | 本次只测单句 2.64s;375 帧上限 = 30s,长文本需前端切段 |

### 6.3 工程踩坑记录(留给后人)

- **本机既没有 NDK 也没有 `gradle` 二进制** —— 但**没用到它们**。
  走 `javac + d8 + app_process`,以及手工 `aapt2/d8/zipalign/apksigner` 打 APK。
  这条路跑的仍是**真 Android 进程 + bionic 链接的 ORT .so**,结论可外推。
- **`Killed` 不一定是 OOM**。第一次运行终端只有一行 `Killed`,极像内存不足;
  实为 d8 时忘了把 ORT 的 `classes.jar` 一起 dex 化 ⇒ `ClassNotFoundException`
  ⇒ Android `RuntimeInit` 的兜底处理器**给自己发 SIGKILL**。
  已在 `build.sh` 写中文注释固化这条教训。
- **16KB 页对齐已通过**:ORT 1.30 的两个 `.so` 的 LOAD 段 `align=0x4000`,
  ⇒ 兼容 Android 15+ 的 16KB 页设备(平板本身是 4KB 页)。
- `OrtProbe` 的第一次运行 `exists=false` 令人困惑:`getExternalFilesDir()` 返回了路径,
  但 App 读不到 —— 根因是**子目录属主**(§4.3a),不是路径错。

### 6.4 一个重要的设计含义:输出**天然不确定**

`should_continue` 是图内**由文本侧采样结果推出来的**,而文本侧采样吃我们喂的随机数
⇒ **终止条件本身是随机的** ⇒ 同一句话的**音频时长会波动**。
(生产代码若 seed 用 `System.nanoTime()`,同一句话连打多次长度必然不同。)

- 本次"逐样本对得上"是因为**两边喂了同一串随机流** —— 那验证的是**移植正确性**,
  不是在说"输出确定"。
- **建议**:作为系统 TTS 引擎,**默认固定 seed** 换取可预测的延迟与时长;要多样性再按句重播种。
- 做性能/音质评测时,**单次对比不可靠,要多次采样取统计量**。

---

## 7. 我在平板上留下的东西(未删任何东西)

| 位置 | 大小 | 说明 |
|---|---|---|
| `/data/local/tmp/canto-ort/` | 765 MB | 探针 dex + 库 + 模型副本 + 输出 wav |
| `/sdcard/Android/data/canto.probe/` | 729 MB | 测试 App 的私有外部目录(模型副本) |
| `/data/data/canto.probe/files/` | 729 MB | 测试 App 的内部存储(模型副本) |
| 包 `canto.probe` | 12.5 MB | 已安装的测试 APK(测试 Service 已 force-stop) |

**全部是本次验证的产物,没有删除任何东西。** 需要清理请用精确路径,不要用通配符。

---

## 8. 参考

- 调用序列说明书:`docs/python-pipeline.md`(1099 行,精确到张量名/形状/dtype)
- 参照实现:`/opt/canto-tts/lib/tts_stream.py`、`/opt/canto-tts-venv/.../canto_tts/`
- 模型:`/var/lib/canto-tts/model`(只读,未改动)
- 产出音频:`results/android_canto.wav` / `results/ref_canto.wav`
