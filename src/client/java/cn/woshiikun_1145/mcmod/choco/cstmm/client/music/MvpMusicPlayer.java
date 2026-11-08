package cn.woshiikun_1145.mcmod.choco.cstmm.client.music;

import cn.woshiikun_1145.mcmod.choco.cstmm.Cstmm;
import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.SourceDataLine;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 【作用】MVP 音乐播放器（客户端）：用 JLayer（纯 Java MP3 解码）把文件解码为 PCM，
 *         写入独立 SourceDataLine 播放。与 Minecraft 的 OpenAL 音效相互独立（不受游戏音量影响，
 *         音量由 ClientConfig.mvpMusicVolume 控制），单例互斥（新的播放会停止上一首）。
 *         播放线程为守护线程，异常只记日志不崩溃；停止时立即关闭音频线路。
 * 【被谁使用】MvpMusicClientHandler（收到 MVP 音乐通知时）、PersonalizeTabPanel（试听）。
 */
public final class MvpMusicPlayer {

    // 当前播放线程与音频线路（stop() 时中断/关闭）
    private static volatile Thread playThread;
    private static volatile SourceDataLine line;
    private static volatile boolean stopped = true;
    private static volatile String currentMusic = "";

    private MvpMusicPlayer() {}

    /** 是否正在播放 */
    public static boolean isPlaying() {
        return !stopped;
    }

    /** 当前播放的曲名（未播放为空串） */
    public static String getCurrentMusic() {
        return currentMusic;
    }

    /**
     * 【作用】播放指定 MP3 文件（新播放会先停止旧的）。
     * @param file   已缓存的 MP3 文件
     * @param name   曲名（仅用于日志/状态展示）
     * @param volume 0~4 倍率（0 表示不播放，1=100%，最高 400%）
     * 【被谁使用】MvpMusicClientHandler、PersonalizeTabPanel。
     */
    public static synchronized void play(Path file, String name, float volume) {
        stop();
        if (file == null || !Files.isRegularFile(file) || volume <= 0) return;
        stopped = false;
        currentMusic = name == null ? "" : name;
        Thread t = new Thread(() -> run(file, volume), "cstmm-mvp-music");
        t.setDaemon(true);
        playThread = t;
        t.start();
    }

    /** 停止播放并释放音频线路（可重复调用） */
    public static synchronized void stop() {
        stopped = true;
        currentMusic = "";
        SourceDataLine l = line;
        line = null;
        if (l != null) {
            try {
                l.stop();
                l.flush();
                l.close();
            } catch (Exception ignored) {
            }
        }
        Thread t = playThread;
        playThread = null;
        if (t != null) t.interrupt();
    }

    // ==================== 播放线程 ====================

    private static void run(Path file, float volume) {
        Bitstream bitstream = null;
        SourceDataLine out = null;
        try (InputStream in = Files.newInputStream(file)) {
            bitstream = new Bitstream(in);
            Decoder decoder = new Decoder();
            while (!stopped) {
                Header header = bitstream.readFrame();
                if (header == null) break; // 文件结束
                SampleBuffer samples = (SampleBuffer) decoder.decodeFrame(header, bitstream);
                if (samples != null) {
                    int rate = samples.getSampleFrequency();
                    int channels = samples.getChannelCount();
                    if (out == null) {
                        out = openLine(rate, channels, volume);
                        if (out == null) return;
                        line = out;
                    }
                    byte[] pcm = toLittleEndian(samples.getBuffer(), samples.getBufferLength());
                    if (pcm.length > 0) out.write(pcm, 0, pcm.length);
                }
                bitstream.closeFrame();
            }
        } catch (Throwable t) {
            if (!stopped) {
                Cstmm.LOGGER.warn("[CSTMM - MvpMusicPlayer] Playback failed for {}: {}", file.getFileName(), t.toString());
            }
        } finally {
            try {
                if (bitstream != null) bitstream.close();
            } catch (Exception ignored) {
            }
            if (out != null) {
                try {
                    out.drain();
                    out.stop();
                    out.close();
                } catch (Exception ignored) {
                }
            }
            if (line == out) line = null;
            if (!stopped) currentMusic = "";
            stopped = true;
        }
    }

    /** 打开 16-bit PCM 输出线路，并把 0~4 音量倍率映射为 dB 增益（含静音下限与设备上限） */
    private static SourceDataLine openLine(int sampleRate, int channels, float volume) {
        try {
            AudioFormat format = new AudioFormat(sampleRate, 16, channels, true, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            SourceDataLine l = (SourceDataLine) AudioSystem.getLine(info);
            // 缓冲区约 0.1 秒：过小易爆音，过大则停止不灵敏
            int bufferBytes = Math.max(1024, sampleRate * channels * 2 / 10);
            l.open(format, bufferBytes);
            try {
                FloatControl gain = (FloatControl) l.getControl(FloatControl.Type.MASTER_GAIN);
                float db = (float) (20.0 * Math.log10(Math.max(0.01f, Math.min(4f, volume))));
                gain.setValue(Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), db)));
            } catch (Exception ignored) {
                // 无 MASTER_GAIN 控制的实现：按设备默认音量播放
            }
            l.start();
            return l;
        } catch (Exception e) {
            Cstmm.LOGGER.warn("[CSTMM - MvpMusicPlayer] Failed to open audio line: {}", e.toString());
            return null;
        }
    }

    /** short[]（JLayer 解码输出）→ 16-bit little-endian PCM 字节 */
    private static byte[] toLittleEndian(short[] samples, int length) {
        ByteBuffer buf = ByteBuffer.allocate(length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < length; i++) {
            buf.putShort(samples[i]);
        }
        return buf.array();
    }
}
