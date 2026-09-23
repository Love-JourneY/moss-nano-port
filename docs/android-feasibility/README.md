# Android 可行性验证产物(2026-09-22)

> **本目录回答一个问题:能不能在 Android 上进程内跑 canto-tts 的 ONNX 推理?**
> 结论:**能,而且是【逐样本对得上】的那种能。**

## 为什么这些产物值得收进开源仓库

| 产物 | 开源价值 |
|---|---|
| `feasibility-report.md` | **完整证据链**:全部实测数字、踩坑记录、反向结论。后人不必重跑 |
| `python-pipeline.md`(1099 行) | **调用序列说明书**,精确到张量名/形状/dtype ⇒ 移植到任何语言都用得上 |
| `canto-probe.apk`(12.5MB) | **最小可安装验证包**(无 UI)。是"真 App 进程能载 ORT"的实证 |
| `android_canto.wav` + `ref_canto.wav` | **A/B 对照音频** ⇒ 可复听"Android 输出 = 本机输出" |
| `CantoConstants.java` | 烘死常量的生成物(留档;正式实现已参数化进 `assets/android-engine/`) |

## 核心结论(数字)

```
逐样本比对(Android vs 本机 Python 参照):
  帧数 33==33 · 随机数 578==578 · 样本数 126720==126720(【文件字节数完全相同】)
  样本级最大绝对差 = 1(int16 满量程 32767)· 相同样本 99.85% · 相关系数 0.9999999999
  客观指标(时长/F0 150.9Hz/RMS/有声帧 84.6%)【逐项完全相同】
⇒ 残余 ±1 LSB 是 numpy np.round(四舍六入五成双)vs Java Math.round(四舍五入)的舍入差异

性能:Session×4 3403ms → prefill 597ms → 循环 2086ms(33帧)→ codec 380ms
      端到端 6566ms · 热态 ≈2.5~3.0s 合成 2.64s 音频
内存:峰值 RSS 1228MB（vs chroot 1.46GiB ⇒ 【Android 更省】）
线程:1~2 最快,8 差一倍(【推翻 chroot 时代"4 最优"的结论】);1/2/4 输出 md5 相同
```

## ⚠️ 三条必须传下去的硬信息

### ① ColorOS 会压缩前台服务(真机风险)
```
✅ App 进程内 ORT native 库加载成功 · 4 Session 创建 · prefill 完成
❌ 然后被 osense 内存压缩掐停:VmRSS 1157MB → 206MB,推理停在中途
   此时 isForeground=true · oom_score_adj=200 ⇒ 【前台服务真生效也照样被压】
反制【全部无效】:deviceidle 白名单 / am set-inactive false / PARTIAL_WAKE_LOCK /
                每 8s 重取锁 / setThreadPriority(FOREGROUND)
⇒ TextToSpeechService 被系统 TTS 框架【bind】,重要性更高 ⇒ 很可能不受影响,
  【但这是"很可能"不是"已验证"】⇒ P4 必须专测:连续合成 N 句,查中途停滞 / VmRSS 被压缩
```

### ② 两条部署坑
```
(a) 模型子目录若是 adb mkdir 建的 ⇒ 属主 shell ⇒ App EACCES(errno 13)
    ⇒ install.sh 必须 chown -R <app_uid>:ext_data_rw
(b) 模型放【内部存储】比 /sdcard(FUSE)快 3.2 倍:建 4 session 3403ms vs 10985ms
    ⇒ 首启拷进内部存储,别常驻从 /sdcard 读
```

### ③ 输出天然不确定
```
should_continue 由图内采样推出 ⇒ 时长会波动(实测 7.52/7.12/9.76s)
⇒ TTS 引擎默认【固定 seed】换可预测延迟;评测时单次对比不可靠,要多次取统计量
```

## 复现

```bash
# 本目录的 APK 可直接装(无 UI 的 Service,跑完看 logcat)
adb install -r canto-probe.apk
# 完整构建脚本见原工程:~/dev/android-canto-tts/{build.sh,build-apk.sh,deploy.sh}
```
