# CHANGELOG —— moss-nano-port 模块

> 只记**用户可感知**的变化。逐次追加,不改旧条目。


## 2026-09-27 —— GUI:暗黑模式(自动/暗/亮)

**Nija 要求**:「app 的 gui 要有暗黑模式」。

- 新增 `assets/android-engine/CantoTheme.java`(158 行,原生 Java 零依赖)
  —— 不用 androidx 的 DayNight(本 App 用 `-bootclasspath android.jar` 直编,没有 androidx)
- 三种模式:跟随系统 / 强制暗 / 强制亮;GUI 有「🌓 主题」按钮循环切换
- 亮:`#F6F7F9` 底 · 暗:`#121212` 底;按钮 = 暗底卡片 + 蓝字

**修的过程中踩的三个坑(都是"设了颜色却被系统样式盖住")**:
| # | 坑 | 修法 |
|---|---|---|
| ① | Button/EditText 自带 `backgroundTint` 盖过 `setBackground` | `setBackgroundTintList(null)` |
| ② | window/decorView 背景没设 ⇒ 内容不足一屏时下方露亮底 | 两处设色 + `setFillViewport(true)` |
| ③ | **动态创建的控件没套色**(音色按钮异步 new 出来的)⇒ 27% 屏幕亮灰 | 创建后重新 `apply()` |

**验收**:像素级——暗色底 `RGB(16,16,16)` 占 95%,异常亮灰 27% → **0%**。

## 2026-09-26 —— Android 引擎:修完 8 个 bug,与笔记本侧听感一致

**背景**:Nija 实测反馈「只有今日听得清,后面叽里咕噜,不像粤语」。
逐层查到根因并修复;每一环都有客观数据(过零率/时长/帧数/与 SDK 逐位对比)。

| # | 问题 | 修法 | 验证 |
|---|---|---|---|
| 1 | **G2P 用错组件**(最贵) | 新增 `CantoCantophon.java`:粤拼 → 声母/韵母/声调三元组;`CantoTokenTable` 改为 cantophon 优先 | 第一帧 tokens **与 SDK 逐位一致**;prompt 142 行;33 帧 / 2.64s |
| 2 | **RNG 与 SDK 不同** | `java.util.Random` → `CantoRngTable`(从 SDK 导出 4000 个值) | ZCR 从"有尖峰"变均匀 |
| 3 | **音色选择无效** | 协议加音色名(`T <voice>\n<len>\n<text>`),守护按名查 codes | F0 123.7(male)~292.7Hz(cv03) 可切 |
| 4 | **心跳防冻** | 守护每帧发 1 字节,App 持续 read ⇒ 保持活跃 | App 连续等 24 秒不被冻 |
| 5 | **段落粒度太碎** | `ANDROID_GRAIN` 6 → 60(等效不切) | 韵律自然,不再"一个词一个词" |
| 6 | **maxFrames 太小** | 40 → 150(心跳防冻后可以放大) | 整句一次念完 |
| 7 | **回调时机错** | `callback.start()` 挪到【拿到 PCM 之后】 | 框架不再提前 UTTER_DONE |
| 8 | **GUI 显示误导** | 区分「采样点数」与「采样率」 | 不再误读成 96kHz |

**新增文件**:
- `assets/android-engine/CantoCantophon.java`(348 行)—— 权威 `cantophon.py` 的 Java 版
- `assets/android-engine/CantoRngTable.java`(712 行)—— 从 SDK 导出的随机数表
- `tools/gen-rng-table.py` · `assets/android-tok/cantophon-tables.json`

**教训归档**:`NOTES.md` §14(145 行,含现象/根因/方法论/验收/六条开发规则)。
**依据**:「对接外部组件不许凭名字猜」—— 我把 `canto_hk_g2p`(汉字→粤拼)
当成了 `cantophon`(汉字→三元组)。一句我自己写错的注释把错误固化成"事实",带偏 20 轮。

## [0.1.4-module.1] — 2026-09-22

### 新增
- **零样本声音克隆**:接上 `codec_encode` ⇒ 音色可换(模型本支持,原 SDK 写死男声)
- **音色库** `voicebank/`:`cv01~cv04`(Common Voice yue 真人女声 / CC-0)· `qwen_hk` · `qwen_short`
- **常驻守护** `lib/tts_stream.py`:模型只加载一次 ⇒ 平板 2 段 **9240ms → 2277ms(4.06×)**
- **流式原型** `lib/tts_stream_live.py`:**TTFB 288ms**(6318ms → 288ms = 22×)
- **切段策略**:句号优先(旧版按逗号切 ⇒ 听感「一个字一个字吐」)
- **p2y 普→粤**:`1907` 条词表(引擎重写为单遍最长匹配)⇒ CER **23.7% → 4.3%**
- **女声 CLI** `bin/vsay-canto-female`:`--split` · `--max-phonemes` · `--pause` ·
  `--best-of` · 音色身份门槛 · 漂移重试
- **平板支持** `tablet-dsh-tts/`:接力播放(借 `VSAY_CANTO_PLAYER` 钩子)+ 首段限长

### 变更
- **默认切段**:逗号切 → **句号优先**
- **默认音色**(女声):`cv01` → **`cv03`**(用户选定)

### 退役(不是删除,归档可恢复)
- 旧 **VITS**(`vits-cantonese-hf-xiaomaiiwn`,109MB):CER 22.0% · **发行包无 LICENSE** ⇒ 全面退役、零残留
- 旧 **qwen3-tts**(2.2GB):模型太大、生产环境用不起 ⇒ 归档到 `/var/lib/retired/` ·
  `/opt/retired/`(一键恢复见 `voice-tts/retired/qwen3-tts/restore.sh`)
- **moss-nano-port 成为唯一模型**

### 修掉的坑(如实记录)
- `température` 参数运行时**完全无效**(采样逻辑烘进 ONNX 图)
- `decode_step` **不能**替代 `prefill`(是两个图)
- ORT **线程越多越慢**(4 最优,12 慢 4.4 倍)
- 自写 C++ 推理器**数学上限 1%**(ORT 占 99.2% of wall)
- 守护**僵尸 socket** 导致"4.3× 白优化"(判据应为"能 connect"而不是"文件存在")

---

## [0.1.4-module.0] — 2026-09-21

### 初始
- moss-nano-port 部署到本机(Arch)与Android 设备(Android chroot)
- 模块化:`install.sh` / `verify.sh` / `uninstall.sh` / `pack.sh`
- 模型实体化到 `/var/lib/moss-nano-port/model`(729MB)
