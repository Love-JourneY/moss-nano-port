# canto-tts 系统 TTS 引擎 APK

> **2026-09-24 打出:12.0 MB,已签名,含全部代码 + ORT native 库 + 小资产。**
> **⚠️ 不含模型(684MB)—— 模型由 install.sh 推到 App 私有目录。**

## APK 内容

| 条目 | 大小 | 说明 |
|---|---|---|
| `lib/arm64-v8a/libonnxruntime.so` | 31.5 MB | **ORT native 库**(从 AAR 的 jni/ 取) |
| `lib/arm64-v8a/libonnxruntime4j_jni.so` | 0.1 MB | ORT 的 JNI 层 |
| `classes.dex` | 253 KB | 9 个 Java 模块(1079 行)+ org.json + ORT 的 classes.jar |
| `assets/syllable-ids.tsv` | 156 KB | **音节→token ids 表(替代 sentencepiece)** |
| `assets/p2y-rules.tsv` | 25 KB | 普→粤规则表 |
| `assets/voicebank/cv03.json` | 3 KB | 默认音色(Nija 选定) |
| `AndroidManifest.xml` | 2 KB | **四件套**(见下) |
| `res/xml/tts_engine.xml` | — | 引擎配置 |
| `META-INF/*` | — | 签名(debug keystore) |

**⇒ APK 12MB;模型 684MB 在外部。** 这正是它建议的"便携包"形态。

## ⚠️ 构建路径(不用 Gradle)

```bash
BT=/opt/android-sdk/build-tools/36.0.0
AJ=/opt/android-sdk/platforms/android-36/android.jar

# 1) 资源
$BT/aapt2 compile --dir res -o build/res.zip
$BT/aapt2 link -o build/base.apk -I $AJ --manifest AndroidManifest.xml \
     -R build/res.zip --java build/gen --auto-add-overlay

# 2) 编译(⚠️ 注意两个 classpath:org.json + ORT 的 classes.jar)
javac -source 8 -target 8 -bootclasspath $AJ \
      -cp libs-json.jar:libs-ort.jar -d build/classes $(find src build/gen -name '*.java')

# 3) dex(⚠️ ORT 与 org.json 的 jar 必须一起 dex 化,否则 ClassNotFoundException)
$BT/d8 --lib $AJ --min-api 24 --output build/dex \
     $(find build/classes -name '*.class') libs-json.jar libs-ort.jar

# 4) 装 native 库 + 小资产,重打 zip(⚠️ AndroidManifest.xml 要 STORED)
#    见 build-apk.py —— 本机【没有 zip 命令】,用 python zipfile
# 5) zipalign + apksigner
$BT/zipalign -f 4 app-unsigned.apk app-aligned.apk
$BT/apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android \
     --key-pass pass:android --out canto-tts-engine.apk app-aligned.apk
```

## 🔴 targetSdk≥30 的硬要求(装不上时的第一嫌疑)

```
Failure [-124]: Targeting R+ (version 30 and above) requires the resources.arsc
of installed APKs to be stored uncompressed and aligned on a 4-byte boundary

⇒ 这两个文件【必须 ZIP_STORED(不压缩)】:
    · AndroidManifest.xml   —— 系统解析清单要用
    · resources.arsc        —— Android 11+(targetSdk≥30)【强制】
⚠️ zipalign 只能【对齐】,不能【解压】⇒ 必须在打包时就 STORED
⚠️ 还要 `zipalign -p`(页对齐,Android 15+ 要求)
```

## ⚠️ 踩过的坑(7 个,都值得记)

1. **`-bootclasspath android.jar` 下【不能用 lambda】**
   ```
   cannot find symbol: method metafactory(...)
   Fatal Error: Unable to find method metafactory
   ⇒ 根因:android.jar 里没有 LambdaMetafactory
   ⇒ 修法:用匿名类(`new Runnable(){ public void run(){...} }`)
   ```
2. **AAR 里的 `.so` 必须手动搬进 `lib/<abi>/`** —— `d8` 只处理 dex,不搬 native 库。
   ⇒ 缺了它 APK 能装但一调 ORT 就 `UnsatisfiedLinkError`
3. **`org.json` 与 ORT 的 `classes.jar` 都要一起给 `d8`** —— 否则运行期 ClassNotFoundException
4. **本机没有 `zip` 命令** ⇒ 用 python `zipfile` 重打(且 `AndroidManifest.xml` 要 `ZIP_STORED`)
5. **AAR 的 `classes.jar` 要自己抠出来**(`unzip x.aar classes.jar`)当 javac/d8 的 classpath
6. 🔴 **`resources.arsc` 必须 ZIP_STORED** —— 否则 targetSdk≥30 直接装不上(见上节)
7. 🔴 **核验 APK 内容要用 `aapt2 dump xmltree`,不要用 `dumpsys` 的 grep**
   —— dumpsys **不打印 meta-data**,会让人误判"声明缺失"
   (实测:`dumpsys package canto.tts | grep android.speech.tts` 查不到,但 APK 里其实有)

## 四件套(服务声明,缺一不可)

```xml
<service android:name="canto.CantoTtsService"
         android:exported="true"
         android:permission="android.permission.BIND_TEXT_TO_SPEECH_ENGINE">
  <intent-filter>
    <action android:name="android.intent.action.TTS_SERVICE" />
    <category android:name="android.intent.category.DEFAULT" />
  </intent-filter>
  <meta-data android:name="android.speech.tts" android:resource="@xml/tts_engine" />
</service>
```

**来历**(「系统 TTS 悬案」结案):
- 缺 ②(`permission`)时系统可能因安全策略【拒绝绑定】⇒ 表现"注册了但从不被调用"
- 缺 ④(`category DEFAULT`)是我们原缺的
- **⚠️ 握住引擎的【不是调用方 App,而是 system_server**(uid 1000)**
  ⇒ 普通 App 直接 bindService 会被拒(`SecurityException`)——
    **"普通 App 直接 bind 失败"不能当作"系统不调用我们"的证据**

## 模型怎么放(APK 之外)

```
App 私有目录:`getExternalFilesDir(null)/models/canto/`
  ├── tts/     ← 4 个图 + 权重(684.5MB)
  ├── codec/   ← codec 的 2 个图 + 权重
  ├── data/    ← G2P 数据表(9.2MB)
  ├── voicebank/*.json
  ├── p2y-rules.tsv · syllable-ids.tsv · libcanto_g2p.so · libcanto_g2p_jni.so

⚠️ 三条实测教训:
  ① 子目录若是 `adb mkdir` 建的 ⇒ 属主 shell ⇒ App EACCES(errno 13)
     ⇒ install.sh 必须 `chown -R <app_uid>:ext_data_rw`
  ② 模型放【内部存储】比 /sdcard 快 3.2 倍(建 4 session:3403ms vs 10985ms)
     ⇒ 首启拷进内部存储,别常驻从 /sdcard 读
  ③ 可砍死代码 43.3MB(codec_encode 42.4 + local_decoder/cached_step),
     但硬底价 682.5MB(global_shared 420.7 + local_shared 219.6 + codec 42.2)砍不动
```

## 🔴 头号未验证风险(ColorOS 内存压缩)

```
真 App 进程实测:ORT 能跑,但被 ColorOS 的 osense 压缩掐停
  VmRSS 1157MB → 206MB,推理停在中途
  此时 isForeground=true · oom_score_adj=200 ⇒ 前台服务真生效也照样被压
  5 种反制全部无效:deviceidle 白名单 / am set-inactive false / PARTIAL_WAKE_LOCK /
                   每 8s 重取锁 / setThreadPriority(FOREGROUND)

⇒ TextToSpeechService 被系统 TTS 框架【bind】,重要性更高 ⇒ 【很可能】不受影响
⇒ 【但必须实测】:连续合成 N 句,查中途停滞 / VmRSS 被压缩
```
