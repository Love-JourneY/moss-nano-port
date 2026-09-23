# tablet-dsh-tts —— 格仔(平板)DSH 🔊 语音播报对接包

> 2026-09-22 立。目标：**平板上点 DSH 的 🔊 按钮 → 用 canto-tts 粤语男声真的出声。**
> 本目录 = 这一包的**源码**（整包可搬；部署到平板 chroot 的 4 个位置）。

## 链路（一句话）

```
DSH 页面 🔊 按钮(dsh-tts 插件 client.js)
  → POST http://127.0.0.1:8790/speak          【server.js，平板 :8790】
  → /usr/local/bin/vsay                       【本包 vsay —— 平板适配层】
  → vsay-canto  -o <单文件>                    【canto-tts 粤语男声；合成+拼接，不播】
  → /usr/local/bin/vsay-android-play          【本包 —— 把 wav 交给 Android】
  → am start …/.SpeakTriggerActivity          【经 andro FIFO 桥】
  → Android AudioTrack → 扬声器
```

## 为什么必须是这个形状（三条硬约束）

| # | 约束 | 后果 / 对策 |
|---|---|---|
| 1 | 平板 chroot 里**没有任何**能出声的播放器（Android 音频 HAL 独占 `/dev/snd`；pacat/paplay/pw-play/aplay/tinyplay 已逐条实测否掉） | 唯一通路 = Android 侧 `SpeakTriggerActivity`（`Theme.NoDisplay`，不创建窗口）⇒ `vsay-android-play` |
| 2 | `SpeakTriggerActivity` **一次只吃一个 wav**（女声源码 line 1122 明载） | 多段必须**在合成侧拼成单文件**再播一次；逐段 `am start` 会互相打断，用户只会听到最后一段 ⇒ `vsay` 用 `-o/--out` + 只触发一次 |
| 3 | 非登录上下文（DSH / p2p-services 启动）**不吃 `/etc/profile.d`** | `vsay` 与 `init.d-dsh-tts` 各自显式 `export PATH`；并显式 `TMPDIR=/tmp`（Android 会把 `TMPDIR=/data/local/tmp` 隐式带进 chroot，而那个目录在 chroot 里**不存在**） |

## 部署（4 个文件 + 1 处注册）

```sh
R=/data/local/linux/debian
install -m 755 vsay              $R/usr/local/bin/vsay
install -m 755 vsay-android-play $R/usr/local/bin/vsay-android-play
install -m 755 init.d-dsh-tts    $R/etc/init.d/dsh-tts
install -m 755 p2p-services.patched $R/usr/local/bin/p2p-services   # ⚠️ 先备份原件
```

`p2p-services.patched` 相对原版只做两件事：
1. **修复**：原 `start)`/`stop)` 分支里 `# port-gate（…）` 注释把后面的
   `start_sshd; start_dinotty; start_dsh` **整串吞掉** ⇒ `p2p-services start`
   实际只起 port-gate，**重启平板后 sshd/dinotty/DSH 都不会起来**（已修，并把
   注释挪到行尾）。
2. **注册** `dsh-tts`：在 `start)`/`stop)` 里各加一行 `/etc/init.d/dsh-tts {start,stop}`，
   在 `status)` 里加一行 dsh-tts 状态（与 port-gate / dsh-mesh 同构）。

## 验收

```sh
su -c 'chroot /data/local/linux/debian /bin/sh /etc/init.d/dsh-tts status'
su -c 'chroot /data/local/linux/debian /bin/bash -c "export PATH=/usr/local/bin:/usr/bin:/bin;
       /usr/local/bin/vsay -m zhll \"格仔 DSH 播報測試。\""'
```

判据（2026-09-22 实测通过）：
- `dumpsys media.audio_flinger` 活跃音轨 **0 → 1 → 0**
- `logcat -s TTS_PLAYER` 出现 `PLAY_START` / `PLAY_DONE`，且 **`framesWritten == framesPlayed`**（尾音未被切）
- `~/.local/state/vsay-usage.log` 出现 `vsay→canto`

## 边界（**故意**没做的事）

- **没有改 `vsay-canto` / `vsay-canto-female`** —— 它们与黑仔保持**字节一致**
  （`8071d81b…` / `bbd8240c…`），不产生第二份分叉。代价：**直接**调用
  `vsay-canto`（不走 `vsay`）在平板上**仍然不出声**（它只会去试 paplay/aplay）。
  受支持的入口是 `/usr/local/bin/vsay` —— DSH 走的正是它。
- 机器上装了个 `am` shim 就**只能**救女声（`vsay-canto-female` 有 Android 交接代码），
  男声 `vsay-canto` 里**根本没有**交接代码 ⇒ 男声的直调缺口只能靠 fork 它来补，
  那会破坏字节一致。⇒ 选择不开这个口子。
- **没有改 `server.js`**：平板那份本来就 spawn `/usr/local/bin/vsay -m zhll <文本>`，
  正是本包要修的入口，够用。（且当时黑仔的 `~/dev/dsh-tts/server.js` 正被另一个
  agent 改，不宜做基准。）

## 回滚

```sh
su -c 'chroot /data/local/linux/debian /bin/sh /etc/init.d/dsh-tts stop'
su -c 'cp -a /data/local/linux/debian/usr/local/bin/p2p-services.bak-<时间戳> \
              /data/local/linux/debian/usr/local/bin/p2p-services'
su -c 'rm -f /data/local/linux/debian/usr/local/bin/vsay \
              /data/local/linux/debian/usr/local/bin/vsay-android-play \
              /data/local/linux/debian/etc/init.d/dsh-tts'
```
