<p align="center">
  <img src="assets/icon/icon-512.png" alt="moss-nano-port" width="180">
</p>

# moss-nano-port

> **让一个小模型在你自己的设备上说话。** 粤语 · 普通话 · 英文 · 日语 —— 全离线、不联网、不需要 GPU。

[![License: AGPL-3.0](https://img.shields.io/badge/License-AGPL--3.0-blue.svg)](LICENSE)
[![Upstream: Apache-2.0](https://img.shields.io/badge/Upstream%20model-Apache--2.0-green.svg)](NOTICE)

---

## 这是什么

**MOSS-TTS-Nano 的下游配套引擎。**

上游 [OpenMOSS Team](https://github.com/OpenMOSS/MOSS-TTS)(复旦大学 NLP / 上海创智学院 / 模思智能)开源了一个
**0.1B 参数的多语言语音生成模型** —— 小到能在 CPU 上实时跑。本项目把它**真正装进你的设备**：

| | 上游给的 | 本项目做的 |
|---|---|---|
| **模型** | 权重(Apache-2.0) | — |
| **推理** | PyTorch + Python 脚本 | **用 Java 重新实现 ONNX 推理**，进程内运行，无 Python |
| **Android** | — | **注册成系统 TTS 引擎** —— 任何 App 的朗读都能用 |
| **部署** | 手工装依赖 | **一条命令 install / verify / uninstall**，整包可离线迁移 |
| **多语言** | 模型支持 | **模型注册表**：扫目录自动识别编码器与音色 |
| **权限** | — | **不需要 root** |

> ⚠️ **非官方 · 社区项目。** 本项目**不是**上游官方发布，也**不使用**其商标暗示官方背书。

---

## 特点

- **全离线** —— 不联网、不上传、不依赖云端 API
- **不需要 root** —— 常驻一个 1×1 透明窗口让系统不冻结进程，仅此而已
- **不需要 GPU** —— 纯 CPU 推理
- **4 种语言 / 25 把音色** —— 粤语(微调) · 普通话 6 · 英文 5 · 日语 7
- **进程内推理** —— 没有独立守护进程，没有跨进程拷贝
- **模型可插拔** —— 把任何兼容模型目录丢进 `models/`，引擎自动识别

---

## 快速开始

### 1. 拿到模型

模型不进 Git(体积太大)。见下面的[模型获取](#模型获取)一节。

### 2. 部署

```bash
git clone https://github.com/Love-JourneY/moss-nano-port.git
cd moss-nano-port
./install.sh          # 幂等，可重复跑
./verify.sh           # 给出通过/失败清单
```

### 3. 用

装好后，模型会出现在系统 TTS 引擎列表里。任何支持 TTS 的 App 都能选它。

```bash
# 命令行试听
echo "今日天气几好" | ./bin/vsay-canto
```

---

## 模型获取

模型是 600MB+ 的权重，所以**不进 Git**。

### ① 粤语(必需)

```bash
huggingface-cli download typangaa/canto-tts-nano --local-dir models/canto-tts-nano
```

### ② 普通话 / 英文 / 日语(可选)

```bash
# 国内网络建议加镜像
HF_ENDPOINT=https://hf-mirror.com huggingface-cli download \
  OpenMOSS-Team/MOSS-TTS-Nano-100M-ONNX --local-dir models/base-mandarin
```

> ⚠️ 基础版**不带 audio codec** —— 引擎会复用粤语版里的那一份，所以**粤语包是前提**。

### ③ 目录约定

引擎扫 `models/` 下的每个子目录，**只要它含 `browser_poc_manifest.json` 就当成一个模型**：

```
models/
  └─ <任意名字>/
       ├─ browser_poc_manifest.json    (必需 —— 有这个才算模型)
       ├─ added_tokens.json            (有 ⇒ 粤语三元组编码；无 ⇒ SentencePiece)
       ├─ voicebank/*.json             (音色来源之一：微调式，多把)
       ├─ builtin_voices(在 manifest)  (音色来源之二：内置，可多把)
       └─ model.json                   (可选：显示名 / 语言 / 默认音色)
```

**放好后打开 App，首页「模型」区就能看到它、点它、用它的音色。**

`model.json` 示例：

```json
{
  "display_name": "MOSS-TTS-Nano 基础版(普/英/日)",
  "languages": ["zh", "en", "ja"],
  "default_voice": "Zhiming"
}
```

### ④ 离线自洽

若你拿到的是**已经装好的整包**，模型在 `models/` 里，`./install.sh` 会优先用包内模型
—— 不联网、不下载。**这是本项目的主推用法。**

---

## 前端怎么接

本引擎注册为标准的 Android `TextToSpeechService`。你的 App 只要用系统 TTS API：

```kotlin
val tts = TextToSpeech(context) { /* 初始化 */ }
tts.setLanguage(Locale.forLanguageTag("yue-HK"))   // 粤语
tts.speak("今日天气几好", TextToSpeech.QUEUE_FLUSH, null, "id")
```

> ⚠️ 服务声明里**必须有 `<category android:name="android.intent.category.DEFAULT" />`**
> —— 少了它，服务会「注册了但从不被调用」。这是实测踩过的坑。

---

## 许可(两层，别混)

| 层 | 内容 | 许可 |
|---|---|---|
| **本项目的代码/脚本/文档** | `install.sh` `verify.sh` `uninstall.sh` `bin/` `lib/` 等 | **AGPL-3.0-or-later** |
| **上游模型** | MOSS-TTS-Nano 权重 · MOSS-Audio-Tokenizer | **Apache-2.0** |
| **第三方运行时** | ONNX Runtime | MIT |
| **音色参照音频** | Common Voice 22 (yue) | CC-0 |

**逐项来源见 [`NOTICE`](NOTICE)。**

> ⚠️ 本项目打包/调用的第三方组件**各有其自己的许可**，不因本文件而改变。

---

## 上游与致谢

- 模型与原始推理：[OpenMOSS Team / MOSS-TTS](https://github.com/OpenMOSS/MOSS-TTS)
  (复旦大学 NLP · 上海创智学院 · 模思智能)
- 粤语微调权重：[`typangaa/canto-tts-nano`](https://huggingface.co/typangaa/canto-tts-nano)
- 运行时：[ONNX Runtime](https://github.com/microsoft/onnxruntime)

没有他们的开源，就没有这个项目。

---

## 常见问题

**Q：为什么不用 Python？**
A：目标设备是手机/平板，装 Python 运行时太重。Java 重实现让引擎能直接注册成系统 TTS 服务。

**Q：需要 root 吗？**
A：不需要。早期版本用过 root 守护进程，已彻底移除，改用常驻透明窗口避免系统冻结。

**Q：能换成别的模型吗？**
A：能。只要模型目录符合上面的约定，引擎会自动识别它的编码器和音色。

**Q：普通话听起来不如粤语？**
A：粤语权重是**专门微调**过的，普通话用的是上游**未微调的基础版**。这不是 bug，是模型本身的差异。
