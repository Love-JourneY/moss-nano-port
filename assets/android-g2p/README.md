# Android aarch64 粤语 G2P(交叉编译产物 + 复现方法)

> **2026-09-22 交叉编译成功。** 为「Android moss-nano-port 推理引擎 App」提供**进程内**的
> 文本→粤拼音素转换(替代原来只有 chroot 才能用的 Python 版)。

## 产物

| 文件 | 说明 |
|---|---|
| `libcanto_g2p.so` | **312 KB** · ELF 64-bit LSB shared object · ARM aarch64 |
| `Cargo.toml.patched` | 我们对 vendored 副本的 Cargo.toml 改动(留档,便于复现) |

## 为什么能交叉编译(关键发现)

上游 `canto-hk-g2p` 的 `pyo3` **只在 `src/lib.rs` 的 `PyPipeline` 薄绑定层**用(6 处),
**核心 `pipeline::Pipeline` 是纯 Rust** ⇒ 把 pyo3 改成可选 + `#[cfg]` 掉绑定层,Android 就能编。

## 复现步骤

```bash
# 1) 装 Rust(免 root)
curl -sSf https://sh.rustup.rs | sh -s -- -y --no-modify-path --profile minimal
rustup target add aarch64-linux-android

# 2) 装 NDK(免 root)
sdkmanager --sdk_root="$HOME/android-sdk-ndk" "ndk;28.2.13676358"

# 3) 取上游源码(Apache-2.0)
pip download canto-hk-g2p --no-binary :all: --no-deps -d /tmp
tar -xzf /tmp/canto_hk_g2p-*.tar.gz

# 4) 应用我们的两处改动(见 Cargo.toml.patched + lib.rs 的 4 处 cfg)
#    · Cargo.toml:pyo3 加 optional = true;末尾加 [features] default=["python"] / python=["dep:pyo3"]
#    · src/lib.rs:给 pyo3 的 use / #[pyclass] / #[pymethods] / #[pymodule] 各加 #[cfg(feature = "python")]

# 5) 交叉编译(不带 python feature)
NDK=$HOME/android-sdk-ndk/ndk/28.2.13676358
TC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin
PATH="$TC:$PATH" cargo build --release --target aarch64-linux-android --no-default-features
```

## ⚠️ 许可与署名(合规,必须保留)

- **canto-hk-g2p 本身**:Apache-2.0(上游 `LICENSE` / `Cargo.toml` 一致)
- **其捆绑数据**(上游 NOTICE 逐项声明):
  - `rime-cantonese` → **CC BY 4.0**(署名即可,可商用)
  - ⚠️ 上游**明确排除**了 ODbL v1.0 的 `jyut6ping3.maps.dict.yaml`
    (理由:ODbL 与其宽松许可栈不兼容)—— **我们继承这个排除**
- **⇒ 再分发时须带**:上游 `LICENSE` + `NOTICE`(含 rime-cantonese 署名)

## 数据表

G2P 运行时需要 `data/`(上游 sdist 里的 `.bin` / `.tsv`,约 **9.2 MB`)。
⇒ Android 侧要把它打进 assets,并用 `Pipeline::from_dir(<data_dir>)` 指过去。
