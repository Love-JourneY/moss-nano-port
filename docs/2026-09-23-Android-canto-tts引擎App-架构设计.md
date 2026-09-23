# Android canto-tts 推理引擎 App —— 架构设计

> ⚠️ **本文是【设计稿】,不是已完成的事实。** 撰写:**2026-09-22**
> 目标:自研 Android 端的 canto-tts **进程内推理引擎**,注册成系统 TTS 引擎。
> 依据:可行性已验证(见 §1)+ 知识库 `~/Documents/system-maintenance/notes/2026-09-22-语音模型技术路线知识库.md`

---

## §1 为什么要做这件事(动机,别忘)

```
现状:粤语 canto-tts 跑在【chroot(Linux 容器)】里
问题:TextToSpeechService 必须在【引擎进程内】合成
     ⇒ 【chroot 那条路当不了系统 TTS 引擎】
⇒ 所以浏览器/任意 App 调系统 TTS 时,听不到 canto-tts 粤语

要实现的效果:
  浏览器 / 任意 App → 系统 TTS(TextToSpeechService)
                    → 【我们的 App 进程内跑 ONNX 推理】
                    → 粤语 canto-tts 音色(而且音色可选)
```

### 可行性已实测(2026-09-22,平板 arm64-v8a · SDK 36)
```
✅ libonnxruntime.so (33MB, NDK r28, for Android 24) 加载成功
✅ OrtEnvironment / OrtSession 创建成功 —— 用的是【带外部权重】的图
   (moss_tts_local_fixed_sampled_frame.onnx + moss_tts_local_shared.data 220MB)
   RSS 180MB → 321MB(+141MB)
✅ 外部 .data 只要与 .onnx 同目录就【自动解析】,Java API 无需额外参数
✅ Android ORT 版本 = 1.30.0(与本机同版本 ⇒ 算子支持一致)
   POM 原文:"all types and operators" + "All standard ONNX models"
```

---

## §2 推理图与数据流(这是核心)

### 2.1 八个图(本机 ORT 1.30.0 实测)
```
┌─ MOSS-TTS-Nano-cantophon-ONNX ────────────────────────────────┐
│  moss_tts_prefill.onnx                    2 入 → 25 出        │  文字 → 首帧 + KV cache
│  moss_tts_decode_step.onnx               26 入 → 25 出        │  逐帧解码(带 KV cache)
│  moss_tts_local_cached_step.onnx          8 入 →  4 出        │  局部(depth)解码
│  moss_tts_local_decoder.onnx              3 入 →  2 出        │  局部解码(无 cache 版)
│  moss_tts_local_fixed_sampled_frame.onnx  4 入 →  2 出        │  ★采样【烘在图里】
└───────────────────────────────────────────────────────────────┘
┌─ MOSS-Audio-Tokenizer-Nano-ONNX ──────────────────────────────┐
│  moss_audio_tokenizer_encode.onnx         2 入 →  2 出        │  参照音频 → codes(克隆用)
│  moss_audio_tokenizer_decode_full.onnx    2 入 →  2 出        │  codes → PCM(一次性)
│  moss_audio_tokenizer_decode_step.onnx   54 入 → 54 出        │  ★流式解码(KV cache)
└───────────────────────────────────────────────────────────────┘
```

**关键契约**:
```
n_vq=16 · row_width=17 · 全局 12 层 / 768 hidden / 12 head · 局部 1 层
vocab 16472 · 采样率 48kHz · 【1 帧 = 3840/48000 = 80ms 音频】
opset 17 · 全标准算子 · 【采样逻辑(top-k/top-p/temperature)烘在图里】
⇒ 唯一有效的随机性控制是 seed(喂 assistant_random_u / audio_random_u)
```

### 2.2 合成数据流(文字 → PCM)
```
文本(粤语)
  │
  ├─(1) G2P:粤拼音素        ← canto-hk-g2p(0.6MB Rust)⇒ 【需确认 Android 能否交叉编译/或移植】
  ├─(2) 音色 codes          ← voicebank/<voice>.json(预编码,零 ffmpeg 依赖)
  │
  ▼
【prefill】(input_ids + attention_mask)→ 25 个输出(首帧 + 全局 KV cache)
  │
  ▼ 循环(每帧 80ms)
【decode_step】(input_ids + past_key/value)→ 下一帧 + 新 KV cache
  │
  ▼
【local_cached_step】× n_vq(16 通道逐通道解)→ 音频 token
  │
  ▼
【local_fixed_sampled_frame】(global_hidden + repetition_seen_mask
                             + assistant_random_u + audio_random_u)
  │  ★采样已烘进图 ⇒ 只喂随机数
  ▼
audio token ids(16 通道 × 帧数)
  │
  ▼
【audio_tokenizer decode】(decode_full 或 decode_step 流式)
  │
  ▼
PCM(float32,48kHz)→ 转 int16 → AudioTrack
```

### 2.3 播放链路(两种场景)
```
场景 A:系统 TTS 引擎被调用
  App(TextToSpeechService)
    → onSynthesizeText(text, callback)
    → 引擎内合成 PCM
    → callback.audioAvailable(byte[], offset, length)   ← ⚠️ 单位见 §5 坑
    → callback.done()

场景 B:我们自己触发(SpeakTriggerActivity,零窗口)
  am start -n <pkg>/.SpeakTriggerActivity --es wav_path <file>
    → VoiceTtsPlayer(AudioTrack MODE_STREAM)播放

⚠️ 已知约束:SpeakTriggerActivity 【一次只吃一个 wav】
   ⇒ 多段必须【先拼成单文件】再播(否则后段打断前段)
```

---

## §3 组件划分(模块化可插拔)

```
com.fcitx5sensevoice(现有 IME 宿主 App,AGPL-3.0)
│
├─ [保持] 语音识别线(IME · VoiceCaptureService · 历史 · 剪贴板)
│
├─ [新增] CantoTtsEngine 包(可独立成模块)
│   ├─ CantoOnnxSession      ONNX Runtime 封装(8 图的加载/复用/生命周期)
│   ├─ CantoG2p              粤拼音素(canto-hk-g2p 的 Android 实现或 JNI)
│   ├─ CantoVoiceBank        音色库读写(voicebank/*.json,含 74 维音色指纹)
│   ├─ CantoSynthesizer      合成编排(prefill→decode 循环→tokenizer decode)
│   ├─ CantoSegmenter        切段策略(句号优先 / 不切 / 按音素数定长)
│   ├─ CantoP2y              普→粤转换(1907 条词表;纯规则,可移植)
│   └─ CantoPlayer           播放(AudioTrack;或交 SpeakService)
│
├─ [改造] VoiceTtsService : TextToSpeechService
│   ├─ onSynthesizeText  → 调 CantoSynthesizer
│   ├─ onGetLanguage / onIsLanguageAvailable → 按【模型真实支持】作答(zh/yue)
│   └─ ⚠️ 【不要在 onIsLanguageAvailable 里阻塞】(见 §5 坑 2)
│
└─ [保留] SherpaTtsEngine(旧路径)—— 可选:作为"非粤语"回退或直接退役
```

**⚠️ 设计原则(照 `~/.dsh/AGENTS.md`)**:
```
· 上游源码保持 clean(本项目是自研,不涉及上游 patch)
· 模块化可插拔:CantoTtsEngine 能【单独拿走】(一个包 + 资源)
· 文档随包走:NOTES/README 与代码同目录
· 变更涟漪双向:改 `onGetLanguage` 这类契约时,正反两向都要扫
```

---

## §4 三个必须解决的工程问题(这是设计的主体)

### 4.1 模型怎么进设备(729MB,且必须【离线】)
```
模型分项:
  MOSS-TTS-Nano-cantophon-ONNX    642 MB
    └ moss_tts_global_shared.data  421 MB  ← 最大单体
    └ moss_tts_local_shared.data   220 MB
  MOSS-Audio-Tokenizer-Nano-ONNX   87 MB
    └ encode.data / decode_shared.data 各 42 MB

⇒ 【729MB 不可能合理塞进 APK】(虽未超 2GB 上限,但安装体验极差)
```

**候选方案(我给的建议排序)**:

| # | 方案 | 优点 | 缺点 | 我的评价 |
|---|---|---|---|---|
| **A** | **APK 内放压缩包,首次启动解压到 App 私有目录** | 一键、离线、可整包搬(**另一个 APK 也能装**) | APK 体积大(压缩后约 400~600MB?) | ⭐ **推荐**(符合"离线复现") |
| **B** | **模型独立成"数据包 APK"**(APK Expansion 风格) | 主 APK 小;数据包可单独分发 | 要装两个;Android 对多 APK 有约束 | 可选 |
| **C** | **adb push / 用户手动放** | 主 APK 极小 | ❌ **破坏"一键"** | 不推荐(只作开发期手段) |
| **D** | **量化/裁剪到能进 APK** | 体积小 | ⚠️ 已知 **int8 会音色漂移**(F0 偏高 ~9%);且**体积只降 20%**(权重原本共享,量化后复制成两份) | ❌ 不推荐 |

**⇒ 建议 A**;并在 `install.sh`/文档里写清"数据包从哪来、怎么校验 md5"。

### 4.2 内存(平板峰值是关口)
```
已知:
  · 平板 MemTotal 15.8GB / MemAvailable 4.4GB(常态)
    ⚠️ 但曾观测到【瞬时只剩 254MB】(swap 用了 6GB)
  · 单图加载 RSS 180→321MB(+141MB)——【ORT 按需取用权重】
  · ⚠️ 全链路要 421MB(global)+ 220MB(local)【同时常驻】+ 推理工作区
⇒ 【峰值 RSS 是真正的关口,必须实测】

缓解方向:
  · 图【按需加载 / 用完释放】(ORT Session 可 close)
  · 不用的图不加载(如克隆时才加载 encode)
  · ⚠️ 量化只在"过 gate"后考虑(音色指纹 + ASR-CER 双 gate)
```

### 4.3 粤语 G2P 怎么上 Android
```
现状:canto-hk-g2p 是 0.6MB 的 Rust 库(随 canto-tts pip 包)
问题:Android 上跑 Rust 需要【交叉编译 .so + JNI】
候选:
  A. 交叉编译 Rust → libcanto_hk_g2p.so + JNI 封装      ← 干净,但要 NDK 工具链
  B. 把 G2P 规则【移植成 Kotlin/Java】(查它是不是纯查表)  ← 若无模型,可行
  C. 预编码:文本→音素在【服务端】做(❌ 破坏"进程内/离线")
⇒ 【先看它是不是纯查表(是则 B 最省);否则 A】
```

---

## §5 已知的坑(从历史踩坑里带过来的,别重踩)

### 坑 1:`maxBufferSize` 单位是【字节】不是【采样点】
```
旧代码:val maxBuf = callback.maxBufferSize (实测 8192) 当采样点用
        ⇒ ByteArray(n*2) = 16384 字节 = 2× 上限
        ⇒ audioAvailable 抛 IllegalArgumentException
⇒ 表现:绑定成功、语言对、speak() 返回 SUCCESS、native 合成也成功
        【只有最后交字节那步炸】⇒ 【永远静音】,极难定位
✅ 正确:maxBytes = maxBufferSize(字节) ⇒ maxSamples = maxBytes / 2
```

### 坑 2:`onIsLanguageAvailable` 阻塞 binder 线程
```
旧实现:模型加载期 t.join(60_000)
实测:系统一次探 ~305 个语言 ⇒ 会占住一批 binder 线程
⇒ 模型变大/变慢时可能拖到客户端超时或 ANR
✅ 正确:不阻塞(按模型真实支持语言直接答),把"等就绪"只留在 onSynthesizeText
```

### 坑 3:握住引擎的是 `system_server`,不是调用方 App
```
Android 15+ 由 TextToSpeechManagerPerUserService 代绑
⇒ 普通 App【直接】bindService 会被拒(SecurityException)
⇒ 【"普通 App 直接 bind 失败"不能当作"系统不调用我们"的证据】
   ← 我们在这上面踩过,浪费了大量排查时间
```

### 坑 4:`SpeakTriggerActivity` 一次只吃一个 wav
```
按段逐个 am start ⇒ 后段打断前段 ⇒ 只听到最后一段
✅ 正确:先拼成单文件,只播一次
```

### 坑 5:App 的 `127.0.0.1:8791/play_wav` 坏死
```
根因:ensureStarted() 里 if(started) return
     ⇒ accept 循环 break 后【同进程生命周期内永不重启】
⇒ 可监听但无人 accept(Recv-Q 堆积)
✅ 正确:accept 失败要【循环内重试】,不是启动时判一次
```

### 坑 6:ONNX 图【全部】依赖外部权重 .data
```
"拿最小的图做轻量测试"不成立:
  local_fixed_sampled_frame.onnx 只有 471KB,却引用 220MB 的 local_shared.data
⇒ 好处:ORT 【按需取用】⇒ 引用 220MB 只让 RSS 涨 141MB
```

### 坑 7:`Killed` 不一定 OOM
```
实测:d8 时忘了把 ORT 的 classes.jar 一起 dex 化
     ⇒ ClassNotFoundException
     ⇒ Android RuntimeInit 的兜底处理器【给自己发 SIGKILL】
⇒ 表现:终端只显示一行 Killed,极像 OOM
✅ 正确:任何"Killed"都先排除这一类,别急着归因 OOM
```

---

## §6 与现有链路的一致性(目标 ④)

```
必须保持一致的四种调用方:
┌─ DSH 🔊 本机(/speak)       → vsay → vsay-canto(黑仔本机扬声器)
├─ DSH 🔊 远端(/speak-audio) → 回 audio/wav 字节 → 浏览器播
├─ DSH 🔊 平板内(/speak)     → vsay(平板)→ vsay-android-relay → SpeakTriggerActivity
└─ 系统 TTS(新)              → VoiceTtsService → CantoSynthesizer(本设计)

⚠️ p2y 在引擎内也要生效(普→粤,1907 条)
⚠️ 音色选择:默认 cv03(用户选定);系统 TTS 也要能换音色
   (TextToSpeechService 没有"音色"概念 ⇒ 可走【语音包/engine 配置】或
    Voice 的 name 编码音色 id —— 【这是个设计点,待定】)
```

---

## §7 NOT in scope(明确不做,别被拉进来)

| 不做 | 理由 |
|---|---|
| **全双工(GPT Live)** | **Nija 2026-09-22 明确延后**(「以后看看有什么办法再实验设计」) |
| **重训/微调模型** | 现有 canto-tts 权重够用;重训要数据 + 算力,另有路线 |
| **改上游 canto-tts 包** | 项目铁律:上游保持 clean |
| **量化(先不做)** | 未过"音色指纹 + ASR-CER"双 gate 前不上 |
| **上 4B/8B MOSS 大模型** | 8~16GB,平板无望(知识库 §11.2 已判) |
| **替换现有 IME/识别线** | 那条线已验收,别动 |

---

## §8 What already exists(别重建)

| 已有 | 复用方式 |
|---|---|
| `/opt/canto-tts/lib/tts_stream.py` | **调用顺序的参照实现**(读它把 prefill→decode→tokenizer 摸清) |
| `canto_tts` Python SDK(`/opt/canto-tts-venv`) | 同上;不是直接复用(要移植到 Java/Kotlin) |
| `voicebank/*.json` | **直接复用**(音色 codes + 74 维音色指纹) |
| `p2y.py`(1907 条) | **移植成 Kotlin**(纯规则表,无依赖) |
| `SherpaTtsEngine` / `VoiceTtsPlayer` / `SpeakService` | **复用播放链路**(AudioTrack 那套已验证) |
| `tools/tts-probe/`(12KB 诊断探针) | **复用**(把"系统认不认我们"变成可观测事实) |
| Android 可行性原型 `~/dev/android-canto-tts/` | **继续用**(app_process 路线,不弹 UI) |

---

## §9 分阶段实施计划(每阶段可验收)

```
P0 【分水岭】可行性 ✅ 已完成
   平板进程内 OrtSession 创建 + 外部权重加载

P1 单图推理正确性
   在 Android 上跑 moss_tts_local_fixed_sampled_frame,输入造数据,验证输出形状/数值
   ⇒ 与【本机 ORT 1.30.0 同输入输出】逐元素比对(允许 float 误差)

P2 完整链路合成(★关键里程碑)
   Android 上真的合成一段粤语(哪怕一句)
   ⇒ 产出 wav + 客观指标(F0/时长)+ 与本机 canto-tts 输出比对

P3 引擎封装
   CantoSynthesizer / Segmenter / VoiceBank 抽成独立包

P4 注册系统 TTS 引擎
   VoiceTtsService 接 CantoSynthesizer
   ⇒ 实测:TTS_INIT / TTS_SET_LANGUAGE / TTS_SPEAK / TTS_SYNTH_DONE
   ⇒ 浏览器调系统 TTS 能听到粤语

P5 一致性 + 收口
   p2y 生效 · 音色选择 · 与 DSH 🔊 三条路行为一致
   ⇒ 端到端验收 + 文档 + 打包(模块可整包搬到另一台 Android)
```

---

## §10 待定决策(需要人或实测才能定)

| # | 问题 | 谁能定 |
|---|---|---|
| 1 | **模型怎么进设备**(方案 A/B/C/D) | 建议 A;若 APK 太大则 B |
| 2 | **G2P 怎么上 Android** | ✅ **已收窄**:pyo3 只在 `PyPipeline` 薄绑定层(6 处),**核心 `pipeline::Pipeline` 是纯 Rust** ⇒ **方案 A:vendored 副本 + 两行改动(pyo3 改 optional + cfg 掉绑定层)**,不改上游仓库。⚠️ 前提:Rust 工具链 + NDK(本机都没有)。许可:Apache-2.0 + 数据 CC BY 4.0,需在 NOTICE 署名 |
| 3 | **系统 TTS 怎么选音色**(Voice.name 编码?配置?) | 需看 Android TTS API 约束 |
| 4 | **SherpaTtsEngine 留不留**(作非粤语回退?) | 用户/产品决策 |
| 5 | **峰值 RSS 到底多少**(全链路同时常驻) | 实测 |

---

## §11 更新记录

- **2026-09-22** 初稿。依据:可行性实测(5527819b)+ 历史踩坑(F1–F7)+ 知识库 §7/§11。
