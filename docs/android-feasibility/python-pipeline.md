# canto-tts Python 参考实现 → Android 推理调用序列说明书

> **用途**:把现有 Python 实现(`canto-tts 0.1.4` + 厂商 vendored 的 `ort_cpu_runtime.py`)逆向成
> **精确到张量名 / 形状 / dtype** 的调用契约,供后续用 Java/Kotlin + `onnxruntime-android` 重写。
>
> **阅读约定**
> - 所有源码引用格式为 `文件:行号`,路径以仓库根为基准:
>   - `PKG/` = `/opt/canto-tts-venv/lib/python3.14/site-packages/canto_tts/`
>   - `LIB/` = `/opt/canto-tts/lib/`
>   - `MODEL/` = `/var/lib/canto-tts/model/`
> - 形如 `int32[1,N,17]` 的形状,**标 `(meta)` 的来源是厂商随包的 `*_browser_onnx_meta.json`;
>   标 `(onnx)` 的是本次**静态解析 `.onnx` protobuf 图头**实测得到(未加载权重);
>   标 `(源码)` 的是从 Python 代码推出。
> - **本文档只做静态阅读 + 静态解析,未加载任何 729MB 权重,未跑推理。**
> - 凡源码里查不到的,一律标 **「源码未见,推测:…」**,不编造。
>
> 生成时间:2026-09-23 · 阅读对象:`PKG/_vendor/openmoss/ort_cpu_runtime.py`(844 行,推理主循环)

---

## 0. 全局速览

| 项 | 值 | 来源 |
|---|---|---|
| 采样率 | **48000 Hz** | `MODEL/MOSS-Audio-Tokenizer-Nano-ONNX/codec_browser_onnx_meta.json` `codec_config.sample_rate` |
| 声道数 | **2**(输出立体声) | 同上 `codec_config.channels` |
| 编解码帧率 | **12.5 Hz**(每帧 3840 样本 = **80 ms**) | `downsample_rate: 3840` ÷ 48000 |
| 码本 | **n_vq = 16**,每本 1024 项 | `tts_config.n_vq`;`model_config.audio_codebook_sizes=[1024]×16` |
| 行宽 | **row_width = 17 = n_vq + 1** | 见 §2.4 |
| 词表 | **16472**(基础 SP 词表 16384 + 88 个音素特殊 token) | `model_config.vocab_size`;`MODEL/added_tokens.json` |
| 模型总体积 | 729 MB(TTS 640 MiB + codec 86 MiB) | `NOTES.md:53`、`ls -l` |
| ORT 版本 | onnxruntime **1.30.0** | `/opt/canto-tts-venv/.../onnxruntime-1.30.0.dist-info` |
| 默认线程 | **4**(实测最优;8/12 反而慢数倍) | `PKG/backends/onnx_backend.py:124`、`NOTES.md:433-440` |
| 默认最大帧数 | **375 帧 = 30 s** | `browser_poc_manifest.json` `generation_defaults.max_new_frames` |

### 0.1 涉及的全部 ONNX 图(8 个)

**TTS 侧**(`MODEL/MOSS-TTS-Nano-cantophon-ONNX/`,共享权重 `moss_tts_global_shared.data` 441MB / `moss_tts_local_shared.data` 230MB):

| 逻辑名 | 文件 | 大小 | 用途 |
|---|---|---|---|
| `prefill` | `moss_tts_prefill.onnx` | 283 KB | 2 输入 / **25 输出**,prompt 全序列前向 |
| `decode` | `moss_tts_decode_step.onnx` | 291 KB | **26 输入 / 25 输出**,全局 12 层单步 + KV cache |
| `local_decoder` | `moss_tts_local_decoder.onnx` | 50 KB | 3 输入 / 2 输出,**降级路径**,生产不走 |
| `local_cached_step` | `moss_tts_local_cached_step.onnx` | 54 KB | 8 输入 / 4 输出,**降级路径**,生产不走 |
| `local_fixed_sampled_frame` | `moss_tts_local_fixed_sampled_frame.onnx` | **471 KB** | **4 输入 / 2 输出,★生产实际走这条** |

> ⚠️ `tts_browser_onnx_meta.json` 里**没有** `local_greedy_frame` 条目,
> 所以 `sessions` 里不存在 `"local_greedy_frame"` 键(见 `PKG/_vendor/openmoss/ort_cpu_runtime.py:406-415` 的条件式构建)。

**Codec 侧**(`MODEL/MOSS-Audio-Tokenizer-Nano-ONNX/`):

| 逻辑名 | 文件 | 大小 | 用途 |
|---|---|---|---|
| `codec_encode` | `moss_audio_tokenizer_encode.onnx` | 816 KB | 2 输入 / 2 输出。**被无条件建会话,但全包零调用点**(见 §5) |
| `codec_decode` | `moss_audio_tokenizer_decode_full.onnx` | 682 KB | 2 输入 / 2 输出,全序列一次性解码(生产默认) |
| `codec_decode_step` | `moss_audio_tokenizer_decode_step.onnx` | 351 KB | **54 输入 / 54 输出**,带 KV cache 的流式解码 |

---

## 1. 会话创建(ORT SessionOptions)逐图列出

### 1.1 唯一的会话构造点

**所有 8 个 session 都走同一个私有工厂 `_session()`**:

```python
# PKG/_vendor/openmoss/ort_cpu_runtime.py:386-397
def _session(self, path_value: Path) -> ort.InferenceSession:
    options = ort.SessionOptions()
    options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    options.intra_op_num_threads = self.thread_count
    options.inter_op_num_threads = 1
    session = ort.InferenceSession(str(path_value), sess_options=options, providers=self.ort_providers)
    ...
```

| ORT SessionOption | 值 | 源码行 | Android 对应 |
|---|---|---|---|
| `graph_optimization_level` | `ORT_ENABLE_ALL` | `ort_cpu_runtime.py:388` | `sessionOptions.setOptimizationLevel(OptLevel.ALL_OPT)` |
| `intra_op_num_threads` | **4**(`thread_count` 默认,可由 `--threads` / `CantoTTS(thread_count=)` 覆盖) | `:389`,`PKG/backends/onnx_backend.py:124` | `sessionOptions.setIntraOpNumThreads(4)` |
| `inter_op_num_threads` | **1**(显式写死) | `:390` | `setInterOpNumThreads(1)` |
| `enable_mem_pattern` | **未设置 ⇒ ORT 默认 `true`** | — | `setMemoryPatternOptimization(true)`(默认即 true) |
| `enable_cpu_mem_arena` | **未设置 ⇒ ORT 默认 `true`** | — | `setCPUArenaAllocator(true)`(默认即 true) |
| `execution_mode` | **未设置 ⇒ 默认 `ORT_SEQUENTIAL`** | — | 默认 |
| `providers` (EP) | **`["CPUExecutionProvider"]`** | `:43-46` | `new OrtSession.SessionOptions()` 不 add CUDA 即可 |
| `log_severity_level` / 日志 | 未设置 | — | 默认 |
| `optimized_model_filepath` | 未设置(不落盘) | — | 不设置 |
| 每图差异化设置 | **无**。8 个图**完全同一套 options**,只有 `path` 不同 | `:399-424` | — |

**关于 execution provider 的选择逻辑**(`:34-58`):
- 归一化后只支持 `cpu` / `cuda` 两种;`canto_tts` 的 ONNX backend **永远传默认值 `cpu`**
  (`PKG/backends/onnx_backend.py:127` 只传 `model_dir` 和 `thread_count`,`execution_provider` 走默认)。
- ⇒ **实际生产路径 100% 是纯 CPU**,CUDA 分支是死代码。
- Android 上直接不 add 任何 EP(仅 CPU)。

### 1.2 external data 怎么配置

**代码里没有任何显式 external-data 配置。** 全部依赖 ONNX Runtime 的隐式机制:

1. `.onnx` 文件内部每个 `TensorProto` 带 `external_data` 键值对(键 `location` = `moss_tts_global_shared.data` 等)。
   本次静态解析确认:`moss_tts_prefill.onnx` 有 **139 个 initializer,139 个全部 external**;
   `local_fixed_sampled_frame.onnx` 有 **45 个 initializer,45 个全部 external**。
2. ORT 在 `InferenceSession(...)` 构造时按 `location` **相对 `.onnx` 所在目录**去打开 `.data`。
3. **硬约束(踩过坑)**:ORT 会做「external data path escapes model directory」安全校验,
   `.onnx` 与其 `.data` 解析后必须落在**同一个目录**。

> ⚠️ **原始事故**(`NOTES.md:25-54`):HF 缓存 `snapshots/<sha>/` 里全是符号链接指向 `blobs/<sha>/`,
> `.onnx` 落到 `blobs/54/`、`.data` 落到 `blobs/b0/` ⇒ ORT 判定"逃逸出模型目录",**直接拒绝加载**。
> 解法是 `cp -rL` 实体化到平坦目录(`/var/lib/canto-tts/model`),`NOTES.md:53` 记「HF 缓存看着 86MB,
> 实体化后 729MB —— 不是下多了,是 `du` 不跟随符号链接少算了」。

**Android 移植要点**:
- 打包进 APK 的 `assets/` 会被压缩、且**没有真实文件路径**,ORT 需要按路径打开 `.data` ⇒
  **必须把模型解压到 App 私有外部目录**(`context.getExternalFilesDir()`),再传绝对路径建会话。
- **绝对不能建符号链接**,`.data` 必须与 `.onnx` 同目录同名。

### 1.3 会话构建顺序与键名

```python
# PKG/_vendor/openmoss/ort_cpu_runtime.py:399-424
"prefill"                → tts_dir / tts_meta["files"]["prefill"]
"decode"                 → tts_dir / tts_meta["files"]["decode_step"]
"local_decoder"          → tts_dir / tts_meta["files"]["local_decoder"]
"local_greedy_frame"     → 仅当 tts_meta["files"]["local_greedy_frame"] 存在(本包不存在)
"local_fixed_sampled_frame" → tts_dir / tts_meta["files"]["local_fixed_sampled_frame"]   ← 存在
"local_cached_step"      → 仅当存在(本包存在)
"codec_encode"           → codec_dir / codec_meta["files"]["encode"]     ← 建了但永不调用
"codec_decode"           → codec_dir / codec_meta["files"]["decode_full"]
"codec_decode_step"      → codec_dir / codec_meta["files"]["decode_step"]
```

⇒ **实际需要移植的图 = 6 个**:`prefill`、`decode`、`local_fixed_sampled_frame`、
`codec_decode`(decode_full)、`codec_decode_step`。
`local_decoder` / `local_cached_step` / `codec_encode` **可以不移植**(见 §2.7 与 §5)。

`local_fixed_sampled_frame` 与 `local_cached_step` 都还会包一个
`CodecStreamingDecodeSession(codec_meta, sessions["codec_decode_step"])`(`:347-350`)。

### 1.4 Warmup 路径(可选)

`OrtCpuRuntime.warmup()`(`:432-476`)会依次跑:prefill → local 帧图 → `decode_full_audio([0]*16)` →
`codec_streaming_session.run_frames([0]*16)` → `reset()`。
**生产路径(`OnnxBackend.synthesize` / `tts_stream.py`)不调 warmup**;只有 `LIB/tts_stream_live.py:237-238`
的 `--warmup` 显式触发。Android 上建议在第 4 节流程前做一次等价的预热,以吃掉 ORT 的首次分配抖动。

---

## 2. 完整调用序列:粤语文本 → PCM float32

### 2.0 端到端主干(伪代码)

```
输入 text (粤语繁体字符串,可夹英文)
  │
  ├─[S1] G2P:  text --canto_hk_g2p.Pipeline.convert--> jyutping 字符串
  ├─[S2] 音素化: jyutping --cantophon--> token 字符串(空格分隔)
  ├─[S3] 清理:  safe_prepare_text(剥空白/压空格/补句末标点)
  ├─[S4] 分词:  _PhonemeEncoder.encode() --> text_token_ids: List[int]  (N 个)
  ├─[S5] 组 prompt: build_voice_clone_request_rows(default_voice_codes, text_token_ids)
  │              --> input_ids int32[1, 122+N, 17], attention_mask int32[1, 122+N]
  │
  ├─[S6] prefill  ← input_ids, attention_mask
  │        └→ global_hidden float32[1,122+N,768]  (取最后一帧 → [1,768])
  │           present_key_i / present_value_i  float32[1,122+N,12,64]  ×12  → 重命名为 past_*
  │
  ├─[S7] 自回归循环 (最多 375 次):
  │        ┌─ local_fixed_sampled_frame(global_hidden, repetition_seen_mask,
  │        │                            assistant_random_u, audio_random_u)
  │        │     └→ should_continue int32[1,1];  frame_token_ids int32[1,16]
  │        │  若 should_continue == 0  ⇒ break
  │        ├─ 组 next_row  int32[1,1,17]  ([9, code_0..code_15])
  │        ├─ decode ← next_row, past_valid_lengths, past_key_i/past_value_i
  │        │     └→ global_hidden [1,1,768] (取该帧) ; present_* → past_*(轮换)
  │        └─ past_valid_length += 1
  │
  ├─[S8] codec_decode (decode_full) ← audio_codes int32[1,T,16], audio_code_lengths int32[1]
  │        └→ audio float32[1, 2, audio_length] ; audio_lengths int32[1]
  │
  └─[S9] 后处理 → PCM16 / 或直接 float32 双声道交织
```

### 2.1 逐步精确定义

#### S1–S4 文本前端
见 §3。产出 `text_token_ids: List[int]`,长度记作 **N**。

#### S5 组 prompt 行

```python
# PKG/_vendor/openmoss/ort_cpu_runtime.py:501-521
prefix_text_token_ids = [ *user_prompt_prefix_token_ids(13 个), audio_start_token_id(6) ]      # 14
suffix_text_token_ids = [
    audio_end_token_id(7),
    *user_prompt_after_reference_token_ids(56 个),
    *text_token_ids(N 个),
    *assistant_prompt_prefix_token_ids(6 个),
    audio_start_token_id(6),
]                                                                                              # 64 + N
rows = [ *build_text_rows(prefix_text_token_ids)(14 行),
         *build_audio_prefix_rows(prompt_audio_codes)(44 行),
         *build_text_rows(suffix_text_token_ids)(64+N 行) ]
return {"inputIds": rows, "attentionMask": [[1] * len(rows)]}
```

**⇒ 总行数 = 14 + 44 + 64 + N = 122 + N**

| 张量 | 形状 | dtype | 来源 |
|---|---|---|---|
| `input_ids` | `int32[1, 122+N, 17]` | int32 | `(源码)` `:673-680`;维度 `17` 来自 `row_width` |
| `attention_mask` | `int32[1, 122+N]` | int32 | `(源码)` `:674`,`:520` |

> ⚠️ 注意命名陷阱:`request_rows` 里的键叫 `inputIds`/`attentionMask`(驼峰,`List[List[int]]`),
> 而喂给 ORT 的 feeds 键叫 `input_ids`/`attention_mask`(下划线)。两者在 `:675-680` 转换。

**行宽是真·17**(不是 16):

```python
# PKG/_vendor/openmoss/ort_cpu_runtime.py:478-485
def build_text_rows(self, token_ids):
    row_width = int(self.manifest["tts_config"]["n_vq"]) + 1        # 16 + 1 = 17
    for token_id in token_ids:
        row = [audio_pad_token_id(1024)] * row_width
        row[0] = int(token_id)                                      # 只有第 0 列是文本 token
        rows.append(row)
```

```python
# PKG/_vendor/openmoss/ort_cpu_runtime.py:487-499  音色/参照前缀行
row_width = n_vq + 1                                                 # 17
row[0] = audio_user_slot_token_id                                    # 默认 8
row[index + 1] = code_row[index]   for index in range(min(len(code_row), 16))
```

⇒ **第 0 列 = "文本/槽位"通道,第 1..16 列 = 16 个 VQ 码本通道**;空位一律填 `audio_pad_token_id = 1024`。

**prompt_templates 全文**(读自 `MODEL/browser_poc_manifest.json`,非推测):

```
user_prompt_prefix_token_ids (13):
  [4, 600, 289, 10356, 13, 10356, 10425, 1860, 4546, 12907, 10363, 13325, 11492]

user_prompt_after_reference_token_ids (56):
  [10356, 10425, 3965, 7738, 11492, 505, 587,
   10356, 10425, 352, 500, 856, 11492, 505, 587,
   10356, 10425, 2128, 1247, 594, 11492, 505, 587,
   10356, 10425, 348, 909, 561, 3648, 11492, 505, 587,
   10356, 10425, 2818, 1305, 355, 348, 909, 11492, 505, 587,
   10356, 10425, 484, 339, 10367, 783, 11492, 505, 587,
   10356, 10425, 2427, 980, 11492]

assistant_prompt_prefix_token_ids (6):
  [10356, 14, 5, 4, 8165, 430]
```

> 这 3 段是**导出时烘死的常量**,Android 侧直接硬编码即可,不需要理解语义。
> (`10356`/`10425`/`11492`/`505`/`587` 反复出现,推测是 `- Instruction:` / `- Text:` 之类的模板子词;
> **源码未见确切语义**。)

#### S6 prefill

| 方向 | 名字 | 形状 | dtype | 来源 |
|---|---|---|---|---|
| IN | `input_ids` | `int32[batch, prefill_seq, 17]` | int32 | `(onnx)` 图头符号名 |
| IN | `attention_mask` | `int32[batch, prefill_seq]` | int32 | `(onnx)` |
| OUT | `global_hidden` | `float32[batch, prefill_seq, 768]` | float32 | `(onnx)` |
| OUT | `present_key_0..11` | `float32[batch, prefill_seq, 12, 64]` | float32 | `(onnx)` |
| OUT | `present_value_0..11` | `float32[batch, prefill_seq, 12, 64]` | float32 | `(onnx)` |

共 **25 个输出** = 1 + 12×2。实际 batch=1、prefill_seq=122+N。

**取隐状态**:`_extract_last_hidden()`(`:97-102`)对 3 维输入取 `[:, -1, :]`
⇒ `global_hidden: float32[1, 768]`。

**KV cache 命名转换**(`:686-689`):
```python
past_by_name = { out.replace("present_", "past_"): value
                 for out in tts_meta["onnx"]["prefill_output_names"][1:] }   # 跳过 global_hidden
```
⇒ `present_key_0` → `past_key_0`,依此类推(**24 个**)。

**past_valid_length**:`:685` `sum(attention_mask[0])` = **122 + N**。

#### S7 自回归循环(生产实际走 `local_fixed_sampled_frame` 分支)

`generate_audio_frames()` 的 `for step_index in range(max_new_frames)`(`:694`)。
分支判定顺序(`:696` / `:707`):

1. `if "local_greedy_frame" in sessions and not do_sample` → **假**(该 session 不存在)
2. `elif "local_fixed_sampled_frame" in sessions and sample_mode == "fixed"` → **真 ★**
3. `elif "local_cached_step" in sessions` → 不达
4. `else: run_local_decoder` → 不达

> `sample_mode` 来自 manifest `generation_defaults.sample_mode = "fixed"`,
> 经 `_normalize_sample_mode()`(`:228-234`)归一化后仍是 `"fixed"`;此时 `do_sample` 被重算为 `True`
> (`:338-340`,因为 `sample_mode != "greedy"`)。⇒ **生产恒定走 fixed 分支。**

**每帧第 1 步:`local_fixed_sampled_frame`**(`:610-641`)

| 方向 | 名字 | 形状 | dtype | 来源 |
|---|---|---|---|---|
| IN | `global_hidden` | `float32[1, 768]` | float32 | `(onnx)` |
| IN | `repetition_seen_mask` | `int32[1, 16, 1024]` | int32 | `(onnx)`;`(源码)` `:618-622` |
| IN | `assistant_random_u` | `float32[1]` | float32 | `(onnx)`;`(源码)` `:623` |
| IN | `audio_random_u` | `float32[1, 16]` | float32 | `(onnx)`;`(源码)` `:624-627` |
| OUT | `should_continue` | `int32[1, 1]` | int32 | `(onnx)` |
| OUT | `frame_token_ids` | `int32[1, 16]` | int32 | `(onnx)` |

`repetition_seen_mask` 构造(`:616-622`):对该帧之前**每个通道**已采过的 token id 置 1,
码本大小 `audio_codebook_sizes[0] = 1024`。**注意这是"历史集",不是"上一帧"。**

随机数(`:623-627`):**每帧消耗 17 个 `numpy.random.Generator.random()` 抽样**,
顺序是**先 1 个 `assistant_random_u`,再 16 个 `audio_random_u`**,都 clamp 到 `[0, 0.99999994]`。

**终止条件**(`:712-713`):
```python
if not should_continue:   # int(np.asarray(...).reshape(-1)[0]) == 0
    break
```
结束当帧**不**进入 `generated_frames`。

**每帧第 2 步:组 `next_row`**(`:810-813`)

```python
next_row = np.full((1, 1, 17), audio_pad_token_id, dtype=np.int32)   # 全填 1024
next_row[0, 0, 0] = audio_assistant_slot_token_id                     # 第 0 列 = 9
for index, token in enumerate(frame):
    next_row[0, 0, index + 1] = int(token)                            # 第 1..16 列 = 帧码
```
⇒ `input_ids: int32[1, 1, 17]`

**每帧第 3 步:`decode`(全局 12 层单步)**(`:814-828`)

| 方向 | 名字 | 形状 | dtype |
|---|---|---|---|
| IN | `input_ids` | `int32[1, 1, 17]` | int32 |
| IN | `past_valid_lengths` | `int32[1]` | int32 |
| IN | `past_key_0..11` | `float32[1, L, 12, 64]` | float32 |
| IN | `past_value_0..11` | `float32[1, L, 12, 64]` | float32 |
| OUT | `global_hidden` | `float32[1, 1, 768]` | float32 |
| OUT | `present_key_0..11` | `float32[1, L+1, 12, 64]` | float32 |
| OUT | `present_value_0..11` | `float32[1, L+1, 12, 64]` | float32 |

- `L` 从 `122+N` 起,每帧 +1。
- **KV cache 传递 = 纯 rename 轮换**:`:825-828` 把 `present_*` 重新映射成 `past_*`,
  下一轮 `:818-819` 用 `tts_meta["onnx"]["decode_input_names"][2:]`(跳过 `input_ids`、`past_valid_lengths`)
  作为键去 `past_by_name` 取值。**没有 InPlace 复用,没有裁剪,没有滑动窗口 —— cache 单调增长。**
- `past_valid_length += 1`(`:824`),它同时决定 mask 有效长度。
- `global_hidden = _extract_last_hidden(...)`(`:823`)取该步最后位置 → `[1, 768]`,喂给下一帧的帧图。

**循环上限**:`max_new_frames = 375`(`browser_poc_manifest.json`;`onnx_backend.py:138` 默认参数同值)
⇒ 375 × 80 ms = **30 s**。`OnnxBackend.synthesize` **不使用** `adaptive_max_new_frames`
(grep 确认只有 `torch_backend.py` 调用它)⇒ **ONNX 路径恒定 375 帧上限,不做长度自适应**。

#### S8 codec 全序列解码

```python
# PKG/_vendor/openmoss/ort_cpu_runtime.py:650-664
audio_codes, dims = _flatten3d_int32([generated_frames])
feeds = {"audio_codes": audio_codes.reshape(dims),
         "audio_code_lengths": np.asarray([len(generated_frames)], dtype=np.int32)}
```

| 方向 | 名字 | 形状 | dtype | 来源 |
|---|---|---|---|---|
| IN | `audio_codes` | `int32[1, T, 16]` | int32 | `(onnx)` |
| IN | `audio_code_lengths` | `int32[1]` | int32 | `(onnx)` |
| OUT | `audio` | `float32[1, 2, audio_length]` | float32 | `(onnx)` 记为 `[batch, Castaudio_dim_1, audio_length]` |
| OUT | `audio_lengths` | `int32[1]` | int32 | `(onnx)` |

- `T = len(generated_frames)`(有效帧数,不含终止帧)。
- `audio_length` 从输出张量读回,`_slice_channel_major_audio(audio, 0, audio_length)`(`:87-94`)
  **只切前 `audio_length` 个样本**(图里 `audio` 的最后一维可能按 3840×T 分配,`audio_length` 给出真实有效长度)。
- 返回 **channel-major 的 float32 数组列表**:`[ch0: float32[audio_length], ch1: float32[audio_length]]`。
  断言 `audio.ndim == 3 and audio.shape[0] == 1`。

### 2.2 `n_vq = 16` 与 `row_width = 17` 在代码里的**具体**用法

| 数字 | 出处 | 在代码里怎么用 |
|---|---|---|
| `n_vq = 16` | `manifest.tts_config.n_vq`(也被 `tts_meta.model_config.n_vq` 冗余记录) | ① `row_width = n_vq + 1`(`:480`,`:489`,`:672`)② `build_audio_prefix_rows` 里 `range(min(len(code_row), n_vq))`(`:496`)③ 帧图 `repetition_seen_mask` 的第 2 维 `(1, n_vq, 1024)`(`:591`,`:618`)④ `audio_random_u` 的第 2 维 `(1, n_vq)`(`:624-627`)⑤ 循环里 `previous_tokens_by_channel = [[] for _ in range(n_vq)]`(`:691`,含降级路径 `:762`,`:795`)⑥ `create_empty_local_cached_past` 无关;但 `run_local_decoder` 里 `padded_prefix = np.full((1, n_vq - 1))`(`:526`) |
| `row_width = 17` | **代码里不读 `model_config.row_width`**,而是每次现算 `n_vq + 1`(`:480`,`:489`,`:672`) | 造 `[pad]*17` 的行;喂 prefill 的 `input_ids` 最后一维 = 17;喂 decode 的 `next_row` 也是 `(1,1,17)` |

> ⚠️ **重要**:meta 里 `model_config.row_width: 17` 是**给读者看的冗余字段**,
> Python 代码**从不读它**(grep 全文无 `["row_width"]` 命中)。Android 侧硬编码 17 即可,
> 但语义上要理解为 `n_vq + 1`。

**特殊 token id 全表**(`tts_browser_onnx_meta.json` `model_config`):

| 名称 | id |
|---|---|
| `audio_pad_token_id` | **1024** |
| `pad_token_id` | 3 |
| `im_start_token_id` | 4 |
| `im_end_token_id` | 5 |
| `audio_start_token_id` | 6 |
| `audio_end_token_id` | 7 |
| `audio_user_slot_token_id` | 8 |
| `audio_assistant_slot_token_id` | **9** |
| `vocab_size` | 16472 |

### 2.3 最终 PCM float32

```python
# PKG/backends/onnx_backend.py:184-190
merged = (np.stack([np.asarray(c, np.float32) for c in channel_arrays], axis=1)
          if len(channel_arrays) > 1
          else np.asarray(channel_arrays[0], np.float32).reshape(-1, 1))
sample_rate = int(self._runtime.codec_meta["codec_config"]["sample_rate"])   # 48000
_write_wav(Path(out_path), merged, sample_rate)
```

⇒ `merged: float32[T, 2]`(**样本主序、通道是第 1 维**),再写 WAV。
若将来要做流式,则 `LIB/tts_stream_live.py:74-77` 的 `_pcm16` 给出交织方式:
```python
arr = np.stack([...], axis=1)                 # [T, C]
return (np.clip(arr,-1,1) * 32767.0).astype("<i2").tobytes()   # 行主序 ⇒ 已交织
```

---

## 3. 文本前端:粤语文本 → token id

### 3.1 完整链路(5 步)

```
原始粤语文本
  │
  │ [1] G2P —— canto_hk_g2p.Pipeline().convert(text) -> "gam1 jat6 tin1 hei3 gei2 hou2 ，"
  │      PKG/core/cantophon.py:128-131  (@lru_cache(maxsize=1) 单例)  :157
  ▼
jyutping 串(空格分隔,含原样保留的标点/英文)
  │
  │ [2] 音节拆解 —— jyutping_to_tokens()  :134-152
  │      每个 token 走 syllable_to_tokens():
  │        "gam1" -> ["<o-g>", "<r-am>", "<t1>"]      :109-123
  │      拆不开的原样保留(out.append(tok))          :151
  ▼
token 列表 ['<o-g>','<r-am>','<t1>', ..., '，', ...]
  │
  │ [3] " ".join(...)  ⇒ 音素字符串              :165-167
  ▼
"<o-g> <r-am> <t1> <o-j> <r-at> <t6> ... ，"
  │
  │ [4] safe_prepare_text()  —— 去换行/压空格/末尾补句末标点   PKG/backends/base.py:81-94
  ▼
同上(末尾若无 。！？.!?；; 之一则补 "。")
  │
  │ [5] _PhonemeEncoder.encode()  —— 先按 added_tokens 最长优先切分,再对剩余跑 sentencepiece
  ▼
tok    text_token_ids: List[int]   (N 个)
```

调用点:`PKG/backends/onnx_backend.py:165-167`
```python
phoneme = self.to_phoneme(text)                  # [1]+[2]+[3]
prepared = safe_prepare_text(phoneme)            # [4]
text_token_ids = self._encoder.encode(prepared)  # [5]
```

> ⚠️ **注意 `safe_prepare_text` 作用在"音素串"上,不是原始中文文本**。
> 所以补的句末标点是 `。` —— 而 `cantophon._PUNCT`(`PKG/core/cantophon.py:68`)本身也把 `。` 当普通
> piece 透传,最终由 SentencePiece 编码成基础词表里的 id。

### 3.2 分词器:不是"纯 sentencepiece"

**两个东西叠加**(`PKG/backends/onnx_backend.py:72-100`):

1. **`sentencepiece.SentencePieceProcessor`** —— `MODEL/tokenizer.model`(470897 字节,SP unigram/BPE 模型)。
   **是 sentencepiece**,基础词表 **16384**。
2. **`MODEL/added_tokens.json`** —— **88 个额外特殊 token**,id 落在 **16384..16471**,
   **在 SP 词表之外**(所以裸用 sentencepiece 会「silently produce the WRONG token ids」,
   见 `onnx_backend.py:26-34` 的 deviation #3)。

`added_tokens.json` 内容分三类(共 88 条):

| 类别 | 数量 | id 区间 | 例 |
|---|---|---|---|
| onset 声母 | 19 | 16384–16402 | `<o-b>`,`<o-ng>`,`<o-gw>`,`<o-kw>`,`<o-z>`,`<o-j>` |
| rime 韵母 | **61** | 16403–16463 | `<r-aa>`,`<r-aai>`,`<r-am>`,`<r-m>`,`<r-ng>`,`<r-yut>` |
| tone 声调 | 6 | 16464–16469 | `<t1>` … `<t6>` |
| **句间停顿** | **2** | **16470–16471** | `<pause-short>`(16470)、`<pause-long>`(16471) |
| **合计** | **88** | 16384–16471 | 实测统计(非估算) |

> ⚠️ `<pause-short>` / `<pause-long>` **在 `added_tokens.json` 里注册了,
> 但推理链路从不生成它们**。证据:
> ① `cantophon.all_phoneme_tokens()` 只吐 onset/rime/tone(`PKG/core/cantophon.py:85-90`);
> ② `PAUSE_TOKENS` 常量**只在定义处出现一次**、全包无第二处引用
> (`grep -rn PAUSE_TOKENS PKG/` 唯一命中 = `PKG/core/control_schema.py:25` 的定义行);
> ③ `cantophon.py` 与 `onnx_backend.py` 里 `pause` 仅出现在 `onnx_backend.py:29` 的**注释**中。
> ⇒ **源码未见**任何代码路径产出 pause token —— 推测是训练侧/上游 prompt 用的,当前推理链路不产生。

### 3.3 `_PhonemeEncoder.encode()` 的精确算法

```python
# PKG/backends/onnx_backend.py:80-100
added = json.loads((model_dir/"added_tokens.json").read_text())      # {token: id}
self._sp = spm.SentencePieceProcessor(model_file=.../"tokenizer.model")
added_sorted = sorted(self._added, key=len, reverse=True)            # ★ 最长优先
self._pattern = re.compile("(" + "|".join(re.escape(t) for t in added_sorted) + ")")

def encode(self, text):
    for part in self._pattern.split(text):        # 带捕获组 ⇒ 分隔符本身也在结果里
        if not part: continue
        if part in self._added:
            ids.append(self._added[part])         # ★ 精确匹配 ⇒ 直接取追加 id
        else:
            ids.extend(self._sp.encode(part, out_type=int))   # 其余交给 SP
```

**Android 复刻要点**:
- 正则必须**最长优先**排序(否则 `<o-g>` 会被 `<o-g`… 之类的短前缀抢走;实际影响的是
  `<o-g>` vs `<o-gw>`、`<r-a>` vs `<r-aa>` 这类前缀冲突)。
- `re.split` **带一个捕获组**时,分隔符会出现在结果数组里。Java 的 `Pattern.split` **不保留**分隔符
  ⇒ 必须改用 `Matcher.find()` + `appendReplacement` 或手工扫描。
- 分隔符匹配的是**字面量**(`re.escape`),不是正则。
- 音素 token 之间原本有空格(`" ".join`),切分后空格作为非匹配片段被 SP 编码 —— SP 会把
  空格当 word boundary(▁)。

### 3.4 有没有特殊前缀/后缀 token?BOS/EOS 是多少?

**没有 BOS/EOS。** prompt 序列的两端是(读自 manifest,非推测):

```
[input_ids 第 0 行 = 122+N 行 × 17 列]
行 0            : im_start_token_id = 4            ← 序列开头
行 1..12        : user_prompt_prefix_token_ids[1:] (600,289,10356,13,10356,10425,1860,4546,12907,10363,13325,11492)
行 13           : audio_start_token_id = 6
行 14 .. 57     : 音色参照的 44 行(user_slot=8 + 16 个音频码)
行 58           : audio_end_token_id = 7
行 59 .. 114    : user_prompt_after_reference_token_ids(56 个)
行 115 .. 114+N : text_token_ids(N 个)              ← ★ 真正的音素 token 在这里
行 115+N .. 120+N : assistant_prompt_prefix_token_ids(6 个) = [10356,14,5,4,8165,430]
最后一行         : audio_start_token_id = 6          ← 交给模型"开始生成音频"
```

- 没有 `<s>`/`</s>`(SP 的 BOS=1/EOS=2 在本链路**未被使用**)。
- `im_end_token_id = 5` 出现在 `assistant_prompt_prefix_token_ids` 的**第 3 位**
  (即 `[10356, 14, 5, 4, 8165, 430]` 里的 `5`)—— 是模板的一部分,不是序列终止符。
- **序列终止靠帧图的 `should_continue`,不靠 EOS token。**

---

## 4. 音频后处理:audio_tokenizer 输出 → PCM

### 4.1 从张量到 PCM 的每一步

| 步骤 | 做什么 | 源码 | 说明 |
|---|---|---|---|
| 1 | 取 `audio` 输出 | `:663-664` | `float32[1, 2, audio_length]`(channel-major) |
| 2 | 读有效长度 | `:663` `audio_length = audio_lengths[0]` | 丢弃尾部 padding |
| 3 | 按通道切片 | `:87-94` `_slice_channel_major_audio` | 返回 `[float32[L], float32[L]]`,**不做任何缩放** |
| 4 | 堆叠成 `[T, C]` | `PKG/backends/onnx_backend.py:184-188` | `np.stack(..., axis=1)` |
| 5 | clip 到 `[-1, 1]` | `:105` `np.clip(..., -1.0, 1.0)` | **唯一的幅度处理** |
| 6 | 转 int16 | `:108` `np.round(audio * 32767.0).astype(np.int16)` | 乘 **32767**(不是 32768),四舍五入 |
| 7 | 写 RIFF/WAVE | `:109-113` | `nchannels=2, sampwidth=2, framerate=48000`,行主序 ⇒ 自动交织 |

**⇒ 采样率 48000 Hz,通道 2,归一化 = 波形已在 `[-1,1]` 内 + 硬 clip,无额外增益/响度归一化。**

### 4.2 有没有 overlap-add / 窗口 / 淡入淡出?

**没有。** 代码里:
- 没有任何窗函数。实测命令
  `grep -rnE '\b(window|fade|overlap|hann|hamming|blackman)\b' --include=*.py canto_tts/`
  **零命中(exit 1)**。
  > ⚠️ 顺带一个坑:第一次用 `grep -i hann` 会**误命中 `channel`**(`c-h-a-n-n-e-l` 里含 `hann`),
  > 必须加 `\b` 词边界才是可信结论。
- 全序列路径(`decode_full`)是**一次性卷积解码**,不存在帧拼接。
- 流式路径(`decode_step`)的输出也是**连续无重叠**的:状态在 KV cache 里,
  每帧直接接着上一帧的时间轴,`run_frames` 返回的 `audio` 是**新增片段**,
  直接 append 到 PCM 流(`LIB/tts_stream_live.py:170-177` 的 `sink(...)` 顺序写出)。
- 唯一的"边界处理"是:每帧 `audio_length` 可能小于 `3840 × frame_count`,只取有效长度。

> ⚠️ **`tts_stream_live.py` 的作者自己在文档字符串里标注了诚实边界**
> (`LIB/tts_stream_live.py:50-55`):逐帧 `decode_step` 与一次性 `decode_full` 在数学上应等价
> (同一个 causal codec),但**未逐样本比对**。Android 侧若两路都实现,建议做一次 A/B 对拍。

### 4.3 `decode_full` vs `decode_step`

#### `moss_audio_tokenizer_decode_full`(2 输入 / 2 输出)

| 方向 | 名字 | 形状 | dtype |
|---|---|---|---|
| IN | `audio_codes` | `int32[1, T, 16]` | int32 |
| IN | `audio_code_lengths` | `int32[1]` | int32(= T) |
| OUT | `audio` | `float32[1, 2, 3840*T]`(有效 `audio_length`) | float32 |
| OUT | `audio_lengths` | `int32[1]` | int32 |

**用法**:拿到**全部** `generated_frames` 后**一次调用**(`:650-664`)。无状态、可重入。
生产默认走这条(`OnnxBackend.synthesize:183`)。

#### `moss_audio_tokenizer_decode_step`(54 输入 / 54 输出)

**用法**:包在 `CodecStreamingDecodeSession` 里(`:260-309`),支持**逐帧/分块增量解码**。

```python
def run_frames(self, frame_rows):
    audio_codes = np.zeros((1, frame_count, 16), dtype=np.int32)   # 不足 16 列补 0
    feeds = {"audio_codes": audio_codes,
             "audio_code_lengths": np.asarray([frame_count], dtype=np.int32)}
    feeds.update(self.state_feeds)          # ★ 48 个状态张量
    outputs = self.session.run(None, feeds)
    # 24 个状态输出 → 按 spec 写回 self.state_feeds(见下)
    return named_outputs["audio"], int(named_outputs["audio_lengths"].reshape(-1)[0])
```

**每次调用喂几帧?** `LIB/tts_stream_live.py:100-109` 与 `:159-160` 给出两种:
- `decode_chunk = 0`(自适应):`_resolve_stream_decode_frame_budget()`(`:245-257`)按
  「已解码音频领先实时秒数」决定 1 / 2 / 4 / 8 帧 —— 领先 <0.20s 解 1 帧,<0.55s 解 2 帧,
  <1.10s 解 4 帧,再多解 8 帧。**防饿死/防积压**。
- `decode_chunk = N > 0`(固定):每次解 N 帧。`N` 小 ⇒ TTFB 低但调用次数多;`N` 大 ⇒ 反之。

**`audio_lengths` 输出 = 本 chunk 新增的样本数**(不是累计)。

### 4.4 54 个输入逐个列出 + 状态怎么传递

**结构 = 2(码) + 4(transformer offset) + 48(12 组 attention cache × 4 张量) = 54。**

#### 前 2 个(每次新填)

| # | 名字 | 形状 | dtype |
|---|---|---|---|
| 1 | `audio_codes` | `int32[1, code_length, 16]` | int32 |
| 2 | `audio_code_lengths` | `int32[1]` | int32 |

#### 3–6:transformer 位置偏移(4 个)

| # | 名字 | 形状 | dtype | 关联 decoder |
|---|---|---|---|---|
| 3 | `transformer_offset_0` | `int32[1]` | int32 | decoder_index 1 |
| 4 | `transformer_offset_1` | `int32[1]` | int32 | decoder_index 3 |
| 5 | `transformer_offset_2` | `int32[1]` | int32 | decoder_index 5 |
| 6 | `transformer_offset_3` | `int32[1]` | int32 | decoder_index 7 |

#### 7–54:12 组 attention KV cache(每组 4 张量,顺序固定为 offset → keys → values → positions)

| # | offset | keys | values | positions | decoder_index | layer_index | context |
|---|---|---|---|---|---|---|---|
| 7–10 | `attn_offset_0` | `attn_cached_keys_0` | `attn_cached_values_0` | `attn_cached_positions_0` | 1 | 0 | **500** |
| 11–14 | `attn_offset_1` | `attn_cached_keys_1` | `attn_cached_values_1` | `attn_cached_positions_1` | 1 | 1 | 500 |
| 15–18 | `attn_offset_2` | `attn_cached_keys_2` | `attn_cached_values_2` | `attn_cached_positions_2` | 1 | 2 | 500 |
| 19–22 | `attn_offset_3` | `attn_cached_keys_3` | `attn_cached_values_3` | `attn_cached_positions_3` | 1 | 3 | 500 |
| 23–26 | `attn_offset_4` | `attn_cached_keys_4` | `attn_cached_values_4` | `attn_cached_positions_4` | 3 | 0 | **800** |
| 27–30 | `attn_offset_5` | `attn_cached_keys_5` | `attn_cached_values_5` | `attn_cached_positions_5` | 3 | 1 | 800 |
| 31–34 | `attn_offset_6` | `attn_cached_keys_6` | `attn_cached_values_6` | `attn_cached_positions_6` | 5 | 0 | **1200** |
| 35–38 | `attn_offset_7` | `attn_cached_keys_7` | `attn_cached_values_7` | `attn_cached_positions_7` | 5 | 1 | 1200 |
| 39–42 | `attn_offset_8` | `attn_cached_keys_8` | `attn_cached_values_8` | `attn_cached_positions_8` | 7 | 0 | **1600** |
| 43–46 | `attn_offset_9` | `attn_cached_keys_9` | `attn_cached_values_9` | `attn_cached_positions_9` | 7 | 1 | 1600 |
| 47–50 | `attn_offset_10` | `attn_cached_keys_10` | `attn_cached_values_10` | `attn_cached_positions_10` | 7 | 2 | 1600 |
| 51–54 | `attn_offset_11` | `attn_cached_keys_11` | `attn_cached_values_11` | `attn_cached_positions_11` | 7 | 3 | 1600 |

**逐张形状/dtype**(`(meta)` `streaming_decode.attention_caches` + `(onnx)` 图头双重确认):

| 张量族 | 形状 | dtype |
|---|---|---|
| `attn_offset_i` | `int32[1]` | int32 |
| `attn_cached_keys_i` | `float32[1, 4, context, 64]` | float32 |
| `attn_cached_values_i` | `float32[1, 4, context, 64]` | float32 |
| `attn_cached_positions_i` | `int32[1, context]` | int32 |

> `num_heads = 4`,`head_dim = 64`,`context` ∈ {500, 800, 1200, 1600}
> ⇒ 单张 key(或 value)= `4 × context × 64 × 4 B`,最大那组(context=1600)= **1.5625 MiB**。
> 全部 12 组的 KV 总量 = `Σ 2 × 4 × context × 64 × 4 B`
> = `2 × 4 × 64 × 4 × (4×500 + 2×800 + 2×1200 + 4×1600)` = **25,395,200 B ≈ 24.2 MiB**。

**输出 54 个**(顺序与输入一一对应,名字加 `_out`):

```
audio, audio_lengths,
transformer_offset_out_0..3,
attn_offset_out_i, attn_cached_keys_out_i, attn_cached_values_out_i, attn_cached_positions_out_i  (i=0..11)
```

**状态怎么在 step 之间传递**(`:265-309`):

```python
# 初始化(构造时 + 每次 reset)
reset():
  transformer_offset_i           = np.zeros((1,), dtype=np.int32)              # 全 0
  attn_offset_i                  = np.zeros((1,), dtype=np.int32)              # 全 0
  attn_cached_keys_i             = np.zeros((1,4,context,64), dtype=np.float32) # 全 0
  attn_cached_values_i           = np.zeros((1,4,context,64), dtype=np.float32) # 全 0
  attn_cached_positions_i        = np.full((1,context), -1, dtype=np.int32)     # ★ 全 -1(不是 0!)

# 每次 run_frames 之后(★ 直接回灌,不做任何 shape 变换 / 重命名)
  state_feeds[spec["input_name"]]              = named_outputs[spec["output_name"]]
  state_feeds[spec["offset_input_name"]]       = named_outputs[spec["offset_output_name"]]
  state_feeds[spec["cached_keys_input_name"]]  = named_outputs[spec["cached_keys_output_name"]]
  state_feeds[spec["cached_values_input_name"]]= named_outputs[spec["cached_values_output_name"]]
  state_feeds[spec["cached_positions_input_name"]] = named_outputs[spec["cached_positions_output_name"]]
```

**关键差异(与 TTS 全局 KV 对比)**:

| | TTS `decode_step` KV | codec `decode_step` KV |
|---|---|---|
| 命名 | 输出 `present_*` → 输入 `past_*`(**需 rename**) | 输出 `*_out_i` → 输入 `*_i`(**名字不同,需查表**) |
| 映射方式 | 字符串前缀替换 | `codec_meta.streaming_decode` 的 spec 表 |
| 位置张量 | 无(只有 `past_valid_lengths` 标量) | 有 `attn_cached_positions_i`(初始化 **-1**) |
| 形状变化 | `L → L+1` 单调增长 | 固定 `context` 环形/滑窗缓冲 |

> ⚠️ **codec `attn_cached_positions` 初始化成 `-1`** 是一个容易踩的坑 —— 不是 0。Android 复刻时照抄。

---

## 5. 参照音频 / 音色克隆(可选)

### 5.1 `moss_audio_tokenizer_encode.onnx` 在哪里被调用?

**答:在本 Python 实现里,一次也没有被调用。**

证据(全包 grep,排除 `__pycache__`):

| 位置 | 内容 | 性质 |
|---|---|---|
| `PKG/_vendor/openmoss/ort_cpu_runtime.py:421` | `"codec_encode": self._session(codec_dir / self.codec_meta["files"]["encode"])` | **只建会话** |
| `PKG/backends/onnx_backend.py:19-20` | 「the shipped runtime **never needs the codec "encode" ONNX session** at inference time, only "decode"/"decode_step"」 | 作者明说 |
| `PKG/backends/onnx_backend.py:16-18` | 「**no `ref_audio` parameter here** (unlike backends/torch_backend.py)」 | 作者明说 |
| `PKG/backends/torch_backend.py:91,124,137` | `ref_audio` 参数 → `mode="voice_clone"` → `reference_audio_path=ref_audio` | **只有 torch 后端支持** |

**⇒ 结论**:`codec_encode` 是一个「**装好了但从不用的 42 MiB 权重**」
(`NOTES.md:530-531` 也把「`moss_audio_tokenizer_encode` 42 MiB 推理时用不到…但 `_create_sessions()`
里无条件加载它」列为已知浪费)。**Android 移植时可以直接不打包 `moss_audio_tokenizer_encode.onnx`
+ `.data`(44.5 MB),省掉一块体积。**

### 5.2 参照 wav 怎么预处理?

**源码未见**(因为 ONNX 路径根本没有调用点)。可**从图签名反推**输入契约:

| 方向 | 名字 | 形状 | dtype | 来源 |
|---|---|---|---|---|
| IN | `waveform` | `float32[batch, 2, waveform_length]` | float32 | `(onnx)` 图头实测 |
| IN | `input_lengths` | `int32[batch]` | int32 | `(onnx)` |
| OUT | `audio_codes` | `int32[batch, code_length, 16]` | int32 | `(onnx)` |
| OUT | `audio_code_lengths` | `int32[batch]` | int32 | `(onnx)` |

**从形状可以确定的事实**:
- **必须是 2 声道**(第 2 维硬编码 2,不是 `channels` 符号)⇒ 单声道参照需复制成双声道。
- **必须是 float32**(不是 int16)。
- 第 3 维是**样本数**(非定长)。
- **采样率必然是 48000** —— codec 与 TTS 共用同一个 tokenizer,`codec_config.sample_rate = 48000`;
  帧率 12.5 Hz 与 TTS 的 80 ms/帧一致。

**源码未见,推测**:重采样到 48k、单声道→双声道复制、幅度归一化到 `[-1,1]`(与 decode 输出同域),
这些步骤应由调用方(oracle 脚本)完成;`input_lengths[batch] = waveform_length`。

> ⚠️ 另注:`onnx_backend.synthesize(..., **_unused)` **末尾有 `**_unused`**
> ⇒ 即使调用方传 `ref_audio=...`,ONNX 后端也**静默丢弃、不报错**。
> Android 侧若要支持音色克隆,必须**自己**实现 encode 链路。

### 5.3 编码出的 codes 怎么进入 TTS 流程?

**没有任何 Python 代码把 encode 输出接进 TTS 流程。** 但**契约是明确的**,因为它就是「内置音色」用的同一条路:

```python
# PKG/_vendor/openmoss/ort_cpu_runtime.py:487-499, 501-521
build_audio_prefix_rows(prompt_audio_codes):        # prompt_audio_codes = List[List[int]], 每行 16 个码
    row = [audio_pad_token_id]*17
    row[0] = audio_user_slot_token_id               # = 8
    row[1..16] = code_row[0..15]
```

⇒ **参照音频的 codes 进入 TTS 的方式 = 作为 `audio_user_slot_token_id(8)` 前缀行插在 prompt 中间**
(位置见 §3.4 的行 14..57)。**不是 spk embedding,不是单独输入张量** ——
它就是 44 行 `[8, c0..c15]` 的 `input_ids` 行。

**音色克隆的等价操作**:把 `default_voice.prompt_audio_codes` 换成
`codec_encode(reference_wav)` 输出的 `audio_codes[0]`(`int32[code_length, 16]` → `List[List[int]]`),
**其余流程完全不变**。行数从 44 变成 `code_length`(48kHz 下约 12.5 帧/秒)。

### 5.4 有没有默认内置音色?

**有,且是唯一音色。**

```python
# PKG/backends/onnx_backend.py:129
self._default_voice_codes = self._runtime.manifest["default_voice"]["prompt_audio_codes"]
```

读自 `MODEL/browser_poc_manifest.json`:

| 字段 | 值 |
|---|---|
| `default_voice.voice` | `"male_default"` |
| `default_voice.prompt_audio_codes` | **44 行 × 16 列**,码值范围 0..1020 |

**⇒ Android 侧最省事的做法:把 44×16 个整数直接硬编码成一个 `short[44][16]` 常量表,
完全不打包 `codec_encode`,也不需要任何参照音频。** 这就是「零参照音频的默认男声」。

另外 `lib/tts_stream.py` 与 `lib/tts_stream_live.py` **在部署层**支持外部音色档案
(非 SDK 能力,靠改私有属性实现):

```python
# LIB/tts_stream_live.py:92-97
prof = json.loads((bank/"<voice>.json").read_text())
self.backend._default_voice_codes = prof["prompt_audio_codes"]
# LIB/tts_stream.py:162-171(守护模式,必须每请求复位,否则跨请求串味)
_be = getattr(tts, "_backend", tts)
_be._default_voice_codes = req.get("voice_codes") or default_voice_codes
_be._runtime.rng = np.random.default_rng(int(req["seed"]))
```

`/var/lib/canto-tts/voicebank/` 下有 6 个档案:`cv01`(48 帧)、`cv02`、`cv03`、`cv04`、
`qwen_hk`、`qwen_short`。档案结构:
```json
{"voice":"cv01", "prompt_audio_codes": [[…16…] × 48], "n_frames":48,
 "ref_f0_median":184.6, "source":"Common Voice 22 yue (真人女声)", "license":"CC-0",
 "ref_audio":"<开发场>/<项目>/refs/cv_yue/cv01.wav",
 "timbre_ref":[74 floats], "timbre_ref_dim":74, "timbre_ref_src":"…/cv01.wav"}
```
> `timbre_ref`(74 维)**在本模块代码里没有任何消费点**(grep 零命中)⇒ 是部署期/评测期的旁路元数据,
> 与 ONNX 推理无关。**Android 侧只需要 `prompt_audio_codes`。**

### 5.5 `/opt/canto-tts/assets/` 里有什么?

```
/opt/canto-tts/assets/
└── android/
    ├── CantoPlay.java      (130 行,零 UI 的 AudioTrack WAV 播放器)
    ├── canto-play.jar      (d8 编译产物)
    └── README.md           (41 行,含重新编译命令)
```

**没有**参照 wav、没有默认音色 wav、没有词典。`CantoPlay.java` 是**播放**侧的参考实现
(不是推理),价值点:
- `:34-63` RIFF **按 chunk 遍历**解析(不能假设 `data` 在固定偏移 —— canto-tts 输出的 wav 带 `LIST`/`fact` 块);
- `:89-101` `AudioTrack.Builder` 参数(USAGE_MEDIA / CONTENT_TYPE_SPEECH / MODE_STREAM);
- `:111-117` 必须**分块 write**(一次 write 太大在部分机型被截断);
- `:118-119` **播完必须 `stop()` 排空,否则尾音被切**(实测少 ~0.3s)。
- `:12-26` README 记:该文件在 Android 16 上走 `app_process` **会 `Aborted (RC=134)`**
  (试过 6 种环境变体),⇒ 正路是**做进一个 App**,`CantoPlay.java` 只能当抄写模板。

---

## 6. `moss_tts_local_fixed_sampled_frame.onnx`(4 输入 / 2 输出)

### 6.1 四个输入 / 两个输出

| 方向 | 名字 | 形状 | dtype | 语义 | 来源 |
|---|---|---|---|---|---|
| IN | `global_hidden` | `float32[1, 768]` | float32 | 全局 12 层 Transformer **上一步/上一帧**的最后隐状态 | `(onnx)` + `(源码)` `:631` |
| IN | `repetition_seen_mask` | `int32[1, 16, 1024]` | int32 | 每个通道「**历史已出现过的 token id**」的 one-hot 掩码(累加集合) | `(onnx)` + `(源码)` `:616-622` |
| IN | `assistant_random_u` | `float32[1]` | float32 | 文本侧(1 个)均匀随机数,clamp 到 `[0, 0.99999994]` | `(onnx)` + `(源码)` `:623` |
| IN | `audio_random_u` | `float32[1, 16]` | float32 | 16 个通道各一个均匀随机数,同样 clamp | `(onnx)` + `(源码)` `:624-627` |
| OUT | `should_continue` | `int32[1, 1]` | int32 | 1 = 继续生成下一帧;0 = 停止 | `(onnx)` |
| OUT | `frame_token_ids` | `int32[1, 16]` | int32 | 本帧 16 个通道的码本索引 | `(onnx)` |

### 6.2 “采样逻辑烘在图里”是什么意思 —— 是,而且已逐项验证

**这句是字面意思**:温度 / top-k / top-p / 重复惩罚 / 逆变换采样
**全部作为图内常量 + 图内算子实现**,**运行时只喂两个均匀随机数**。

本次**静态解析 `.onnx` 图头**得到的确证(未加载权重):

**(a) 图内确实有采样算子**(`moss_tts_local_fixed_sampled_frame.onnx` 节点直方图):

| 算子 | 数量 | 在采样里的角色 |
|---|---|---|
| `TopK` | **16** | 每通道 top-k 过滤(音频 16 通道) |
| `Softmax` | 50 | 打分→概率(含文本侧 + 音频侧 + top-p 归一化) |
| `CumSum` | **32** | 累积分布(**16 通道 × 2 处**:top-p 判定 + 逆变换采样) |
| `GatherElements` | **16** | 按采样下标从候选 id 里取回真 token id |
| `Clip` | 33 | 概率下限裁剪(0.9999999)+ 随机数上界 |
| `Div` | 32 | 温度除法 + **重复惩罚的除法分支** |
| `Mul` | 169 | **重复惩罚的乘法分支** + 其它 |
| `Sub` | 32 | 逆变换采样的 `u - cdf` |
| `Where` | 66 | **重复惩罚正负号选择** `Where(logits<0, logits*p, logits/p)` |
| `ReduceSum` | 16 | 逆变换采样的 `sum(cdf <= u)` |

**(b) 采样超参数确实是图内常量**(从 `Constant` / `ConstantOfShape` 节点的张量属性里读出):

| 参数 | 值 | 出现次数 | 节点类型 / 例 |
|---|---|---|---|
| `audio_temperature` | **0.8** | **16**(每通道一个) | `Constant` `/Constant_133`, `/Constant_207`, `/Constant_281` … |
| `audio_top_k` | **25** | **16** | `Constant` `/Constant_134`, `/Constant_208`, … |
| `audio_top_p` | **0.95** | **16** | `Constant` `/Constant_136`, `/Constant_210`, … |
| `audio_repetition_penalty` | **1.2** | **1** | **`ConstantOfShape`** `/ConstantOfShape_2`(作为 `Reshape_19` 的填充值) |
| `text_temperature` | 1.0 | 多(≥1) | `Constant`(1.0 共出现 522 次,含大量其它用途) |
| `text_top_p` | 1.0 | 同上 | — |
| 随机数上界 | `0.9999999` | **17**(16 音频 + 1 文本) | `Constant` `/Constant_70`, `/Constant_141`, … |
| mask 填充值 | `-3.4028234663852886e+38`(fp32 最小值) | **17** | `Constant` |
| top-p 后置填充 | `-inf` | **16** | `Constant` |

**重复惩罚的实际实现**(反向追踪 `/Where_4` 的输入确认):
```
/Where_3 = Where( Less(logits, 0),  logits * penalty,  logits / penalty )
             ↑ /Less_2      ↑ /Mul_10 (in: logits, penalty)   ↑ /Div (in: logits, penalty)
   penalty = /Reshape_19  ← /Cast_20 ← /ConstantOfShape_2  (填充值 1.2)
```
⇒ 与 Python 侧 `_apply_repetition_penalty()`(`:105-113`)的公式
`result*penalty if result<0 else result/penalty` **逐字一致**。**确认 1.2 真的烘在图里。**

**采样方式 = 逆变换采样(inverse-CDF)**,不是 Gumbel-max:
```
probs  = Softmax( top-k/top-p 过滤后 / temperature )
cdf    = CumSum(probs)
index  = ReduceSum( cdf <= u )          # u 由外部喂入
token  = GatherElements(candidate_ids, index)
```
⇒ **运行时唯一需要提供的是 `u`**,`u` 与 `cdf` 比较即完成采样。
`should_continue` 侧的推导(`:610-641` + 反向追踪):
```
should_continue = ( Clip(resampled_u, ·, 0.9999999) <= Softmax_1[0] )
```
即「**文本侧采样到的 token 是否仍是 `audio_assistant_slot_token_id(9)`**」的图内等价形式
(对应 Python 侧 `:730-737` 的 `if next_text_token != audio_assistant_slot_token_id: break`)。

### 6.3 温度等参数怎么传进去 —— **不能传,是图内常量**

**关键结论(移植必读)**:

> **`OnnxBackend.synthesize()` 的 `text_temperature` / `text_top_p` / `text_top_k` /
> `audio_temperature` / `audio_top_p` / `audio_top_k` / `audio_repetition_penalty`
> 参数,在 fixed 分支下被【静默忽略】。**

依据:
- `PKG/backends/onnx_backend.py:169-179` 把这些值写进 `manifest["generation_defaults"]`;
- `generate_audio_frames()` 的 fixed 分支(`:707-716`)**只调用**
  `run_local_fixed_sampled_frame(global_hidden, previous_token_sets_by_channel=...)`(`:708-711`),
  **不传任何采样参数**;
- `run_local_fixed_sampled_frame()`(`:610-641`)构造的 feeds **只有 4 个键**,
  与图签名完全一致 —— **没有任何温度/top-k/top-p 入口**;
- `generation_defaults` 里唯一被 fixed 分支读取的是 **`max_new_frames`**(`:694`)。

⇒ **想改温度/top-p,只能重新导出 ONNX(改图内常量),不能在运行时改。**

**同时注意** `manifest` 里的 `generation_defaults`(运行时用的)与
`tts_browser_onnx_meta.json` 里的 `onnx.fixed_sampled_frame_constants`(图内烘死的)**是两套值**:

| 参数 | `generation_defaults`(manifest,运行时) | `fixed_sampled_frame_constants`(图内,实测) | 实际生效 |
|---|---|---|---|
| `audio_temperature` | **0.9** | **0.8** | **0.8(图内)** |
| `audio_top_p` | **0.9** | **0.95** | **0.95(图内)** |
| `audio_top_k` | 25 | 25 | 25(一致) |
| `audio_repetition_penalty` | **1.0** | **1.2** | **1.2(图内)** |
| `text_temperature` | 0.9 | 1.0 | 1.0(图内) |
| `text_top_p` | 1.0 | 1.0 | 一致 |
| `text_top_k` | 50 | **未找到 50 这个字面量** | 见下 |
| `max_new_frames` | 375 | —(不在图内,由宿主循环控制) | **375(宿主)** |

> **`text_top_k = 50` 源码未见**:图内**搜不到 50.0 这个常量**(已全量扫描所有
> `Constant`/`ConstantOfShape` 的张量属性)。**推测**:文本侧候选集只有 2 个 token
> (`audio_assistant_slot_token_id=9` 与 `audio_end_token_id=7`,见 `:188-194`),
> Python 侧 `top_k=min(text_top_k, 2) = 2` 本就是 no-op ⇒ 导出器把它**折叠优化掉了**。
> 这解释了为什么没有 50 这个常量。

**Android 移植建议**:把这 4 个值当成**图的属性**(硬编码),不要暴露成 App 的设置项。
真要暴露,只能靠导出多个 `.onnx` 变体。

---

## 7. 移植到 Android 的难点清单

### 7.1 依赖盘点(默认推理路径 `quality=None` 实际用到的)

| # | Python 依赖 | 用在哪 | 代码引用 | Android 替代方案 | 难度 |
|---|---|---|---|---|---|
| 1 | **`onnxruntime` 1.30.0** | 全部 8 个 session | `ort_cpu_runtime.py:11` | `com.microsoft.onnxruntime:onnxruntime-android`(Java API `OrtEnvironment`/`OrtSession`) | **低** |
| 2 | **`numpy`** | 张量构造 / 切片 / reshape / clip / stack / round | `ort_cpu_runtime.py` 全文 | Java 原生数组 + `java.nio.ByteBuffer`(`OnnxTensor.createTensor` 直接吃 `FloatBuffer`/`IntBuffer`)。**没有自动广播,所有 shape 手工算** | **中** |
| 3 | **`numpy.random.Generator`(PCG64)** | 每帧 17 个均匀随机数 | `:345` `default_rng(1234)`;`:623-627` | `java.util.Random` / `SplittableRandom`。**⚠️ 数值序列不同 ⇒ 不可能 bit-exact 复现原音频**;但音质无影响(采样混沌,`NOTES.md:502-504`) | **低(但不可复现)** |
| 4 | **`sentencepiece` 0.2.2** | `tokenizer.model` → 非音素片段的 token id | `onnx_backend.py:81-85` | 三选一:① **JNI 编译 sentencepiece C++**(官方有 CMake,NDK 可编,~1 天)② **DJL `ai.djl.sentencepiece`**(现成 Android 可用)③ **把 SP 词表预导成 Java 友好的 trie/数组**(需自己实现 unigram Viterbi) | **中高** |
| 5 | **`canto_hk_g2p` 2.6.1**(**Rust `abi3.so`**) | 粤语文本 → jyutping | `cantophon.py:128-131` | ⚠️ **最硬的一块**。给的是 `manylinux_2_17_aarch64`(glibc),**Android 是 bionic,不能直接用**。三选一:① **NDK 交叉编译 Rust crate 成 `arm64-v8a/libcanto_hk_g2p.so` + JNI 包装**(需 Rust 源码)② **把 `data/*.bin` 词典 + 分段算法用 Kotlin 重写**(需逆向 `.bin` 格式)③ **把 G2P 留在服务端/前置步骤**,App 只接收音素串 | **高** |
| 6 | **G2P 二进制词典 9.4 MB** | `canto_hk_g2p/data/` | `ls` | `char.bin`(631K)、`word.bin`(1.79M)、`cmudict.dict`(3.62M)、`char_candidates.bin`(267K)、`word_candidates.bin`(334K)、`*_source.bin`(805K+1.69M)、`*_confidence.bin`(203K+236K)、`classifier_words.bin`、`romanized_slang.bin`、`separable.bin` | **高(格式未公开)** |
| 7 | **`re`(正则)** | `_PhonemeEncoder` 的音素 token 切分 | `onnx_backend.py:86-87,93` | `java.util.regex`。⚠️ **语义差异**:Python `re.split` 带捕获组**保留分隔符**,Java `Pattern.split` **丢弃** ⇒ 必须换 `Matcher.find()`/`appendReplacement` 或手工扫描 | **低** |
| 8 | **`json`** | 读 manifest / meta / added_tokens | `:328,343,344`,`onnx_backend.py:83` | `org.json`(Android 内置) | **低** |
| 9 | **`wave`(标准库)** | 写 16-bit PCM WAV | `onnx_backend.py:109-113` | 手写 44 字节 RIFF 头(`CantoPlay.java:34-63` 已有反向的解析器可抄) | **低** |
| 10 | **`pathlib`** | 路径拼接 | 多处 | `java.io.File` | **低** |
| 11 | **`soundfile` 0.14.0** | **只**用在 `tts_stream_live.py` 的 baseline 对比 | `tts_stream_live.py:199-201,261-264` | 不需要(非生产路径) | — |
| 12 | **`tokenizers` 0.23.2** | **未被使用**(ONNX 路径用自研 `_PhonemeEncoder`) | — | 不需要 | — |
| 13 | **`torch`** | 不安装(未在 dist-info 里) | — | 不需要 | — |
| 14 | `faster_whisper` / `ctranslate2` / `av` | 只服务 `quality="best_of_n"` | `PKG/quality.py` | 不需要(默认 `quality=None`) | — |

### 7.2 非依赖类难点

| # | 难点 | 细节 | 建议 |
|---|---|---|---|
| A | **模型体积 729 MB** | `prefill`+`decode` 共享 441 MB `moss_tts_global_shared.data`;`local_*` 共享 230 MB;codec 86 MB | ① **不打包 `codec_encode`(−44.5 MB)**;② **不打包 `local_decoder`(−50 KB)+ `local_cached_step`(−54 KB)**;③ 走 **Play Asset Delivery** 或首启下载到 `getExternalFilesDir()` |
| B | **external data 路径校验** | ORT 要求 `.onnx` 与 `.data` 同目录;**符号链接会被拒绝** | 解压到真实目录,禁止 symlink;解压后校验 `File.getCanonicalPath()` 的父目录一致 |
| C | **KV cache 内存** | TTS 全局 12 层 × (K+V),每层 `[1, L, 12, 64]` float32。`L = 122+N+k`,`k` 最多 375;取 N≈50 ⇒ `L≈547` ⇒ `12×2×547×12×64×4 B ≈ 38.5 MiB`(单序列)。codec 侧固定缓冲 ≈ **24.2 MiB**(12 组,context 500/800/1200/1600,见 §4.4) | Java 侧预分配 `[1, maxL, 12, 64]` 的 `FloatBuffer` 复用,避免每帧分配 |
| D | **每帧 2 次 session.run,最多 375 帧** | 每帧 = 1× `local_fixed_sampled_frame` + 1× `decode` ⇒ **最多 750 次 run** | `NOTES.md:398-401` 实测:`local_fixed_sampled_frame` 占 46%、`decode_step` 占 36%、`prefill` 11%、`codec_decode` 7%。**ORT 占 99.2%,Python 胶水仅 1%** ⇒ **JNI/Java 重写不会更快**,收益只在 footprint |
| E | **线程数** | `intra_op=4` 实测最优;8 → 慢 2.3×,12 → 慢 4.4× | Android 上**不要**按 CPU 核数设线程,直接 `setIntraOpNumThreads(4)`。`NOTES.md:433-440` |
| F | **WAV 尾部截断** | `CantoPlay.java:118-119` 实测:播完不 `stop()` 排空会**少 ~0.3 s** | 播放侧必须 `track.stop()` 再 `release()` |
| G | **`app_process` 路线不可用** | Android 16 上 `app_process` 跑 AudioTrack **必崩 RC=134**(6 种环境变体全试过) | 必须做进真正的 App(`AudioTrack` 在 App 进程内) |
| H | **`decode_full` vs `decode_step` 等价性未验证** | `tts_stream_live.py:50-55` 作者自述「未逐样本比对」 | 若 Android 要实现流式,先做一次两路对拍 |
| I | **`text_token_ids` 依赖 SP + added_tokens 的精确切分** | 切错 = 每个音素 token 都错(`onnx_backend.py:26-34`) | 移植后**必须**用同一批文本与 Python 侧对拍 token id 序列(这是最便宜的回归测试) |
| J | **音素字符串里的标点** | `_PUNCT` 里的全角标点**原样进 SP**;`safe_prepare_text` 只在末尾补 `。` | 别在 Android 侧"顺手清理"标点 —— 会改变 token 序列 |
| K | **G2P `punc_norm` 默认 True** | 会把 `《》——` 等归一化成粤语标点 | 若要 byte-exact,必须复刻归一化规则 |

### 7.3 建议的移植优先级

```
阶段 1(能出声,最小可用)
  ├─ 硬编码 44×16 默认音色 codes(不打包 codec_encode)
  ├─ 硬编码 3 段 prompt template + 全部 token id 常量
  ├─ 移植 4 个图:prefill / decode / local_fixed_sampled_frame / codec_decode(decode_full)
  ├─ Java 复刻 _flatten3d_int32 / _flatten2d_int32 / _slice_channel_major_audio / _extract_last_hidden
  ├─ 手写 RIFF WAV 输出
  └─ G2P:先用**预生成的音素串**打通(把 canto_hk_g2p 留在开发机),验证推理链路

阶段 2(端上自洽)
  ├─ sentencepiece:JNI 编 libsentencepiece 或 DJL
  └─ canto_hk_g2p:NDK 交叉编译 Rust → arm64-v8a .so + JNI

阶段 3(可选)
  ├─ codec_decode_step 流式(降低 TTFB)
  └─ codec_encode + 用户参照音频(音色克隆)
```

---

## 8. 附:关键常量速查表(可直接抄进 Java)

### 8.1 模型结构

```
n_vq                  = 16
row_width             = 17          ( = n_vq + 1 )
hidden_size           = 768
global_layers         = 12
global_heads          = 12
head_dim              = 64
local_layers          = 1
local_heads           = 12
local_head_dim        = 64
vocab_size            = 16472
audio_codebook_size   = 1024        (16 个码本,每个 1024)
```

### 8.2 特殊 token

```
audio_pad_token_id             = 1024
pad_token_id                   = 3
im_start_token_id              = 4
im_end_token_id                = 5
audio_start_token_id           = 6
audio_end_token_id             = 7
audio_user_slot_token_id       = 8
audio_assistant_slot_token_id  = 9
```

### 8.3 codec

```
sample_rate      = 48000
channels         = 2
downsample_rate  = 3840      ⇒ 12.5 帧/秒 ⇒ 80 ms/帧
num_quantizers   = 16
```

### 8.4 生成

```
max_new_frames            = 375          (宿主循环上限,30 s)
do_sample                 = true
sample_mode               = "fixed"

# ★ 实际生效的采样参数(烘在图里,不可运行时修改)
audio_temperature         = 0.8
audio_top_p               = 0.95
audio_top_k               = 25
audio_repetition_penalty  = 1.2
text_temperature          = 1.0
text_top_p                = 1.0

random_u clamp 上界        = 0.99999994   (源码 :623-626)
default rng seed          = 1234         (:345)
每帧消耗随机数            = 17           (1 × assistant_random_u + 16 × audio_random_u)
```

### 8.5 文件 → 逻辑名 映射

```
prefill                     ← tts_meta.files.prefill                  = moss_tts_prefill.onnx
decode                      ← tts_meta.files.decode_step              = moss_tts_decode_step.onnx
local_fixed_sampled_frame   ← tts_meta.files.local_fixed_sampled_frame = moss_tts_local_fixed_sampled_frame.onnx
codec_decode                ← codec_meta.files.decode_full            = moss_audio_tokenizer_decode_full.onnx
codec_decode_step           ← codec_meta.files.decode_step            = moss_audio_tokenizer_decode_step.onnx
(可不移植) local_decoder     ← tts_meta.files.local_decoder
(可不移植) local_cached_step ← tts_meta.files.local_cached_step
(可不移植) codec_encode      ← codec_meta.files.encode
```

---

## 9. 本次工作的边界与方法论声明

| 项 | 说明 |
|---|---|
| **只读** | 未修改 `/opt/canto-tts/**`、`/var/lib/canto-tts/**`、`/opt/canto-tts-venv/**` 任何文件 |
| **未安装任何包** | venv 里**没有** `onnx` 包(实测 `ModuleNotFoundError`)。本次用的 `.onnx` 图头解析器是**现场手写的 protobuf wire-format 解析器**(`/tmp/onnxprobe.py`、`/tmp/constprobe.py`),只读 `.onnx` 容器(几十~几百 KB),**不触碰 `.data` 权重文件** |
| **未加载模型** | 全程无 `InferenceSession` 构造。`NOTES.md:512-515` 明确提示「venv 里没有 onnx 包;要读算子集合得 `pip install --target /tmp/qv onnx`,**别往 venv 装**」—— 本次**没有装**,改用自写解析器 |
| **形状/dtype 来源** | 标 `(onnx)` 的 = 图头 `graph.input`/`graph.output` 的 `TypeProto` 实测;标 `(meta)` 的 = `*_browser_onnx_meta.json`;标 `(源码)` 的 = Python 代码 |
| **未验证项** | ① 所有张量的**运行时实际数值**未验证(未跑推理);② `decode_full` 与 `decode_step` 的数值等价性未验证;③ `codec_encode` 的参照音频预处理(重采样/单声道/归一化)**源码未见**;④ `text_top_k=50` 在图内**未找到字面量**(标注了推测);⑤ `<pause-short>`/`<pause-long>` 的**生成路径源码未见** |
| **未做** | 没有跑 `verify.sh`、没有跑 `tts_stream.py`、没有起任何服务、没有碰平板 |

---

*本文件由静态源码阅读 + `.onnx` 图头静态解析生成。文档只增不删。*
