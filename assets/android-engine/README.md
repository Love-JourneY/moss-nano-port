# Android 引擎侧 Java 模块(原生 Java,零框架依赖)

> **2026-09-23 实测:平板上 `app_process` 跑通,P2y 与 Python 版【逐字节一致】(14/14)。**
> 按 Nija 指示:**原生 Java 极简,不用 Kotlin,不搞复杂**。

## 三个模块

| 文件 | 行数 | 作用 |
|---|---|---|
| `src/P2y.java` | 187 | 普通话书面文 → 粤语口语(**Python 版逐条机械复刻**,判据=逐字节相同) |
| `src/CantoVoiceBank.java` | 106 | 音色库读 json(零样本克隆的"音色"从哪来) |
| `src/CantoSegmenter.java` | 115 | 切段策略(默认 **NONE 不切**) |

## 构建与验证(纯命令行,不需要 Gradle / Android Studio)

```bash
SDK=/opt/android-sdk
JSON=$(# org.json jar:d8 时需一起 dex 化)
javac -encoding UTF-8 -source 8 -target 8 \
      -bootclasspath $SDK/platforms/android-36/android.jar \
      -cp "$JSON" -d out src/*.java

$SDK/build-tools/36.0.0/d8 --lib $SDK/platforms/android-36/android.jar \
      --min-api 24 --output dex $(find out -name '*.class') "$JSON"

# 推到设备跑(不需要 Activity、不弹 UI)
adb push dex/classes.dex /data/local/tmp/x/
adb shell "cd /data/local/tmp/x && CLASSPATH=./classes.dex \
           app_process /system/bin <主类> /data/local/tmp/x"
```

## P2y 的六步(顺序敏感,别调)

```
⓪ 总开关(VSAY_P2Y=0 / VSAY_CANTO_P2Y=0 ⇒ 原样返回)
① 半角标点归全角 —— 【只在紧邻 CJK 汉字时转】
   为什么不能无脑全局转:会把 `127.0.0.1:8790` 变成 `127.0.0.1：8790`、
   `a,b` 变成 `a，b`;而 DSH 正文夹着端口号/路径/代码片段。
② 进行体「在+V」→「喺度+V」(正则)
③ 数字+元/块 → 蚊(正则,不参与词表)
④ 【带 \u0000 哨兵】的单遍最长匹配
   🔑 哨兵:把"文本末尾"变成普通字符,让"句末 了→喇"能写成普通表条目("了\0"→"喇\0")
      ⚠️ 我第一版漏了哨兵 ⇒ 句末语气词规则全部失效。
⑤ 收尾压掉重复助词((嘅){2,} 等)
```

## 验证(判据是【逐字节】,不是"看起来对")

```bash
# 1) 本机生成权威期望值
while read -r l; do [ -n "$l" ] && p2y "$l"; done < tests/cases.txt > tests/expected-from-python.txt
# 2) 设备上跑比对(见 tests/P2yByteCompare.java)
# 3) 判据:输出必须 0 不同
```

**当前基线:`14 相同 / 0 不同`。**

## ⚠️ 两个我踩过的坑(留给后人)

1. **改文件要改对地方**:我把新版写到了 `assets/`,但 `src/canto/` 还是旧版
   ⇒ 编译出旧行为、比对报 7 处不同。**同步后再跑才对。**
2. **`Killed` 不一定是 OOM**:我的 smoke 测试 `System.exit(1)` ⇒ 终端只显示 `Killed`。
   实测 logcat 明写 `Calling main entry ...` + `VM exiting with result code 1`
   ⇒ **它真的跑了**。看到 Killed 先看 logcat 的退出码。

## 许可

- 本目录 Java 源码:**AGPL-3.0-or-later**(见模块根 `LICENSE`)
- `tests/expected-from-python.txt` 由 Python 版 `p2y` 生成,同为 AGPL-3.0

## root 权限提示:统一模块

本引擎的 root 提示**不用自己写的**,而是复用统一模块:

```
资产目录:  assets/android-engine/rootgate/com/bbsoy/rootgate/RootGate.java
  ⚠️ 原「root 网关」模块(~Documents/repo/android-rootgate/)已于 2026-09-28 【整体退役删除】。
     本 App 现【不依赖 root】—— 进程内推理 + 常驻透明 overlay 防冻,详见 NOTES.md §去 root。
依据:      ~/AGENTS.md §P2(依赖 root 的自研 App 必须弹窗告知,不许静默失败)
```

**⇒ 以后新 App 也用这一份,别再各写各的。**
