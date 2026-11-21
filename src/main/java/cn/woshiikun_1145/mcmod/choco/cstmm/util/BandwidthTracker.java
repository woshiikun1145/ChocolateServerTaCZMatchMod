package cn.woshiikun_1145.mcmod.choco.cstmm.util;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 【作用】服务器带宽统计（调试用）：通过 Netty 管线最外侧的字节计数器统计每个玩家连接的
 *         原始出/入站字节（加密与压缩后的线路字节，涵盖原版与模组全部流量）；
 *         内置 1Hz 守护采样线程维护"最近一秒速率"与录制期间的逐秒样本（总量/均值/峰值），
 *         供 /cstmm debug bandwidth start|stop|get|view 查询。
 *         计数器常驻（采样线程 1Hz、开销可忽略），录制与否只影响逐秒样本是否留存。
 * 【被谁使用】NetworkHandler（玩家 JOIN 时向连接管线注入 OutboundCounter/InboundCounter）、
 *           ModCommands（bandwidth 子命令调用 startRecording/stopRecording/getCurrentBandwidth/getViewReport）。
 *           仅服务端。
 */
public final class BandwidthTracker {

    /** 出站字节累计（自服务端启动起，Netty 回调线程高频写入） */
    private static final AtomicLong outBytes = new AtomicLong();
    /** 入站字节累计（自服务端启动起） */
    private static final AtomicLong inBytes = new AtomicLong();

    /** 最近一秒出站/入站速率（采样线程每秒刷新；volatile 保证命令线程读到最新值） */
    private static volatile long lastSecOut = 0;
    private static volatile long lastSecIn = 0;

    /** 采样线程是否已启动（幂等启动） */
    private static final AtomicBoolean samplerStarted = new AtomicBoolean(false);

    // ==================== 录制状态（以下字段均由 synchronized 方法读写） ====================
    /** 是否处于录制中（start 开启、stop 关闭） */
    private static boolean recording = false;
    /** 逐秒样本 [outBytes, inBytes]（录制期间每秒追加一条） */
    private static final List<long[]> samples = new ArrayList<>();
    /** 录制期间的出/入站总量与峰值（字节/秒） */
    private static long totalOut = 0;
    private static long totalIn = 0;
    private static long peakOut = 0;
    private static long peakIn = 0;

    private BandwidthTracker() {
    }

    // ==================== Netty 管线计数器 ====================

    /**
     * 【作用】出站字节计数器：加到管线最首（addFirst），出站消息从管线尾流向头、
     *         最后经过本处理器——此时消息已编码/压缩/加密完毕，统计的是最终线路字节。
     *         兼容 ByteBuf 与打包发送的 ByteBuf 列表（1.20.5+ 分包压缩会产生 List）。
     * 【被谁使用】NetworkHandler（玩家 JOIN 时 addFirst 注入，名称 cstmm_bw_out）。
     */
    public static final class OutboundCounter extends ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            long bytes = countBytes(msg);
            if (bytes > 0) outBytes.addAndGet(bytes);
            ctx.write(msg, promise);
        }
    }

    /**
     * 【作用】入站字节计数器：加到管线最首（addFirst），入站消息从网卡进入后最先经过本处理器，
     *         统计的是解密/解压前的原始线路字节。
     * 【被谁使用】NetworkHandler（玩家 JOIN 时 addFirst 注入，名称 cstmm_bw_in）。
     */
    public static final class InboundCounter extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            long bytes = countBytes(msg);
            if (bytes > 0) inBytes.addAndGet(bytes);
            ctx.fireChannelRead(msg);
        }
    }

    /** 统计单个 Netty 消息的字节数：ByteBuf 直接取可读字节，ByteBuf 列表逐项求和，其余忽略 */
    private static long countBytes(Object msg) {
        if (msg instanceof ByteBuf buf) {
            return buf.readableBytes();
        }
        if (msg instanceof List<?> list) {
            long sum = 0;
            for (Object o : list) {
                if (o instanceof ByteBuf buf) sum += buf.readableBytes();
            }
            return sum;
        }
        return 0;
    }

    // ==================== 采样线程 ====================

    /** 幂等启动 1Hz 守护采样线程（常驻，开销可忽略）：每秒归零累计计数并维护速率/样本 */
    private static void ensureSampler() {
        if (!samplerStarted.compareAndSet(false, true)) return;
        Thread sampler = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return; // 守护线程，随进程退出
                }
                sampleSecond();
            }
        }, "CSTMM-BandwidthSampler");
        sampler.setDaemon(true);
        sampler.start();
    }

    /** 采样一秒：取走累计计数作为本秒速率；录制中则追加样本并累计总量/峰值 */
    private static synchronized void sampleSecond() {
        long o = outBytes.getAndSet(0);
        long i = inBytes.getAndSet(0);
        lastSecOut = o;
        lastSecIn = i;
        if (recording) {
            samples.add(new long[]{o, i});
            totalOut += o;
            totalIn += i;
            if (o > peakOut) peakOut = o;
            if (i > peakIn) peakIn = i;
        }
    }

    // ==================== 命令查询接口 ====================

    /** 开始记录：清空历史样本与总量峰值，从此每秒留存一条样本 */
    public static synchronized void startRecording() {
        ensureSampler();
        recording = true;
        samples.clear();
        totalOut = 0;
        totalIn = 0;
        peakOut = 0;
        peakIn = 0;
    }

    /** 停止记录，返回可直接发给玩家的汇总消息 */
    public static synchronized String stopRecording() {
        recording = false;
        long secs = samples.size();
        return "已停止记录（时长 " + secs + " 秒，出站总量 " + humanBytes(totalOut)
                + "，峰值 " + humanBytes(peakOut) + "/s；入站总量 " + humanBytes(totalIn)
                + "，峰值 " + humanBytes(peakIn) + "/s）";
    }

    /** 获取当前所占带宽（最近 1 秒出/入站速率） */
    public static String getCurrentBandwidth() {
        ensureSampler();
        return "当前带宽：↑出站 " + humanBytes(lastSecOut) + "/s，↓入站 " + humanBytes(lastSecIn) + "/s（最近 1 秒采样）";
    }

    /** 查看带宽记录：总量/均值/峰值摘要 + 最近逐秒样本（最多 15 条，新→旧） */
    public static synchronized String getViewReport() {
        ensureSampler();
        if (samples.isEmpty()) {
            return "暂无带宽记录（先用 bandwidth start 开始记录）";
        }
        long secs = samples.size();
        StringBuilder sb = new StringBuilder();
        sb.append("带宽记录（时长 ").append(secs).append(" 秒）：\n");
        sb.append("§e出站: §f总量 ").append(humanBytes(totalOut))
          .append("，均值 ").append(humanBytes(totalOut / secs)).append("/s")
          .append("，峰值 ").append(humanBytes(peakOut)).append("/s\n");
        sb.append("§e入站: §f总量 ").append(humanBytes(totalIn))
          .append("，均值 ").append(humanBytes(totalIn / secs)).append("/s")
          .append("，峰值 ").append(humanBytes(peakIn)).append("/s\n");
        sb.append("§e最近逐秒样本（新→旧，最多 15 条）§7[↑出站/↓入站]:");
        int shown = 0;
        for (int idx = samples.size() - 1; idx >= 0 && shown < 15; idx--, shown++) {
            long[] s = samples.get(idx);
            sb.append("\n§7- §f").append(humanBytes(s[0])).append("s / ").append(humanBytes(s[1])).append("s");
        }
        return sb.toString();
    }

    /** 字节数人性化格式：B / KB / MB / GB */
    private static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format("%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format("%.2f MB", mb);
        return String.format("%.2f GB", mb / 1024.0);
    }
}
