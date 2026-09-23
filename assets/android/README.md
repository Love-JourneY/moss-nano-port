# assets/android —— 安卓侧播放器(AudioTrack 路线)

## 这是什么

`CantoPlay.java` + `canto-play.jar`:一个**零 UI 的命令行 WAV 播放器**,
走 Android 自己的音频框架(`AudioTrack`),由系统的音频 HAL 去驱动声卡。

**它解决什么问题**:平板 chroot 里 `aplay`/`tinyplay` 都播不出声
(Android 音频 HAL `ohalservice.qti` 独占 `/dev/snd`,SELinux Enforcing)
⇒ 播放在这台机器上**必须**交给 Android 侧的音频框架。

## ⚠️ 现状:留作参考实现,**未经端到端验证**

本机(一加平板 2 Pro / Android 16)实测:
```
$ CLASSPATH=/data/local/tmp/canto-play.jar app_process /system/bin \
      com.bbsoy.canto.CantoPlay /data/local/tmp/ct-o.wav
Aborted   (RC=134)
```
崩溃点在 `AndroidRuntime::startReg` → `register_com_android_internal_os_ApplicationSharedMemory`
→ `FindClass` → `AssertNoPendingException`。
**试过 6 种环境变体全部同样崩**(显式 `ANDROID_ROOT`/`ANDROID_DATA`、
`env -i`、`--nice-name`、修 `LD_LIBRARY_PATH`、`app_process64`、
`unset BOOTCLASSPATH …`)⇒ 判定为 **Android 16 对 `app_process` 路线的加固**,
不是环境配错。详见 `NOTES.md §7`。

⇒ **正路是做进一个 Android App**(`AudioTrack` 直接在 App 进程里用),
   也就是 `~/Documents/repo/voice-tts/docs/平板粤语TTS-自播放方案.md` 的「方案 C」。
   本目录的 `CantoPlay.java` 可以直接抄过去当参考实现 —— RIFF 解析、
   `AudioTrack.Builder` 参数、分块写入、**播完要 `stop()` 排空否则尾部被切**,
   这些坑都已经写在代码注释里了。

## 怎么重新编译

```bash
cd assets/android
javac -source 11 -target 11 -classpath /opt/android-sdk/platforms/android-36/android.jar \
      -nowarn -d build CantoPlay.java
/opt/android-sdk/build-tools/36.0.0/d8 --min-api 21 --output build build/*.class
python3 -c "import zipfile;z=zipfile.ZipFile('canto-play.jar','w',zipfile.ZIP_STORED);z.write('build/classes.dex','classes.dex');z.close()"
```
