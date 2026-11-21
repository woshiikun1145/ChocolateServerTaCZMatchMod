package cn.woshiikun_1145.mcmod.choco.cstmm.network.payload;

import cn.woshiikun_1145.mcmod.choco.cstmm.network.NetworkHandler;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 【作用】调试诊断通道（S2C）：/cstmm debug get_logs 的日志内容下发——服务端读取日志文件
 *         按块分包，客户端集齐后写盘到 config/cstmm/download/<服务器地址>/<时间戳>/<文件名>
 *         （客户端落盘，玩家无需到服务器文件系统取件）。
 * 【被谁使用】服务端 NetworkHandler#sendLogDownload（ModCommands debug get_logs 玩家执行时调用）；
 *           客户端 ClientNetworkHandler 注册接收器按 partIndex 重组 → 写盘 + Toast 提示。
 */
public final class DebugPayloads {

    private DebugPayloads() {}

    /**
     * 日志下载分片（S2C）：分包结构与 ConfigSyncPayload 相同（partIndex 从 0 开始，
     * totalParts 为总分片数）。timestamp 为下载批次时间戳（yyyyMMdd-HHmmss，
     * 同批次多文件共用一个目标目录）；fileName 为目标文件名（fatalerr.log / latest.log）。
     */
    public record LogDownloadPayload(
            String timestamp,
            String fileName,
            int partIndex,
            int totalParts,
            String content
    ) implements CustomPayload {

        // 【作用】包类型标识（cstmm:log_download），由 NetworkHandler 注册并由网络层路由
        public static final Id<LogDownloadPayload> ID = new Id<>(
                Identifier.of("cstmm", "log_download")
        );

        // 【作用】编解码器：批次时间戳（上限 20）/文件名（上限 64）/分片序号与总数（VarInt）+
        //         分片内容（单片 ≤30000 字符，与 CONFIG_CHUNK_BYTES 同档）
        public static final PacketCodec<PacketByteBuf, LogDownloadPayload> CODEC = PacketCodec.of(
                (payload, buf) -> {
                    buf.writeString(payload.timestamp(), 20);
                    buf.writeString(payload.fileName(), 64);
                    buf.writeVarInt(payload.partIndex());
                    buf.writeVarInt(payload.totalParts());
                    buf.writeString(NetworkHandler.truncateByUtf8Bytes(payload.content(), 30000), 30000);
                },
                buf -> new LogDownloadPayload(
                        buf.readString(20),
                        buf.readString(64),
                        buf.readVarInt(),
                        buf.readVarInt(),
                        buf.readString(30000)
                )
        );

        @Override
        public Id<LogDownloadPayload> getId() {
            return ID;
        }
    }
}
