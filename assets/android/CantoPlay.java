// CantoPlay.java —— 安卓侧 WAV 播放器(命令行,零 UI)
//
// ⚠️ 为什么需要它(2026-09-22 实测结论):
//   平板 chroot 里【播不出声】—— 直接开 /dev/snd 会 write error: Invalid argument。
//   根因:这台 Android 的声卡被系统的音频 HAL(ohalservice.qti)独占,
//   SELinux 又是 Enforcing ⇒ chroot 里无论 aplay / tinyplay 都驱动不了 DSP。
//   ⇒ 正解:把 PCM 交给 Android 自己的音频框架(AudioTrack),由 HAL 去驱动硬件。
//
// 用法(在 Android 侧,不在 chroot 内):
//   CLASSPATH=/data/local/tmp/canto-play.jar app_process /system/bin com.bbsoy.canto.CantoPlay <file.wav>
//
// 退出码:0 成功;1 参数/IO 错;2 音频初始化错。
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

import java.io.FileInputStream;
import java.io.IOException;

public final class CantoPlay {

    /** 极简 RIFF/WAVE 解析结果。只认 PCM(fmt=1)+16bit —— canto-tts 的输出正好是。 */
    private static final class Wav {
        int sampleRate = 48000;
        int channels = 2;
        int bits = 16;
        byte[] pcm;
    }

    /**
     * 解析 WAV。⚠️ 不能假设 data 就在固定偏移 —— 真实文件里 fmt 块和 data 块之间
     * 常有 LIST/fact 等附加块(canto-tts 的输出就带),必须按 chunk 逐个走。
     */
    private static Wav readWav(String path) throws IOException {
        byte[] all;
        try (FileInputStream in = new FileInputStream(path)) {
            all = in.readAllBytes();
        }
        if (all.length < 44) throw new IOException("文件太小,不像 WAV: " + path);

        Wav w = new Wav();
        int pos = 12; // 跳过 "RIFF" + size + "WAVE"
        while (pos + 8 <= all.length) {
            String id = new String(all, pos, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int size = le32(all, pos + 4);
            int body = pos + 8;
            if (size < 0 || body + size > all.length) size = all.length - body; // 容错
            if ("fmt ".equals(id)) {
                int fmt = le16(all, body);
                if (fmt != 1) throw new IOException("只支持 PCM(fmt=1),实际 fmt=" + fmt);
                w.channels = le16(all, body + 2);
                w.sampleRate = le32(all, body + 4);
                w.bits = le16(all, body + 14);
            } else if ("data".equals(id)) {
                w.pcm = new byte[size];
                System.arraycopy(all, body, w.pcm, 0, size);
                break;
            }
            pos = body + size + (size & 1); // chunk 按偶数字节对齐
        }
        if (w.pcm == null) throw new IOException("WAV 里找不到 data 块: " + path);
        return w;
    }

    private static int le16(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8)
             | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("用法: CantoPlay <file.wav>");
            System.exit(1);
        }
        try {
            Wav w = readWav(args[0]);
            int chanMask = (w.channels == 1)
                    ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
            int encoding = (w.bits == 8)
                    ? AudioFormat.ENCODING_PCM_8BIT : AudioFormat.ENCODING_PCM_16BIT;

            int minBuf = AudioTrack.getMinBufferSize(w.sampleRate, chanMask, encoding);
            int bufSize = Math.max(minBuf, 16384);

            AudioTrack track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(encoding)
                            .setSampleRate(w.sampleRate)
                            .setChannelMask(chanMask)
                            .build())
                    .setBufferSizeInBytes(bufSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();

            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                System.err.println("AudioTrack 初始化失败");
                System.exit(2);
            }
            System.err.printf("播放 %s (%d Hz / %d ch / %d bit / %d 字节)%n",
                    args[0], w.sampleRate, w.channels, w.bits, w.pcm.length);
            track.play();
            int off = 0;
            while (off < w.pcm.length) {
                // ⚠️ 分块写:一次 write 太大在部分机型上会被截断
                int n = Math.min(bufSize, w.pcm.length - off);
                int written = track.write(w.pcm, off, n);
                if (written <= 0) break;
                off += written;
            }
            // ⚠️ 必须等排空再 release,否则最后一段被切掉(实测会少 ~0.3s)
            track.stop();
            track.release();
            System.err.println("播放完成");
        } catch (IOException e) {
            System.err.println("读文件失败: " + e.getMessage());
            System.exit(1);
        } catch (Throwable t) {
            System.err.println("播放失败: " + t);
            System.exit(2);
        }
    }
}
