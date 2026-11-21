package cn.woshiikun_1145.mcmod.choco.cstmm.network.payload;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.NetworkHandler;

/**
 * 【作用】配置同步域 payload 容器：集中存放核心配置哈希握手/全量同步/配置更新/打开配置界面相关包。
 *         由原 ConfigMetaPayload.java、ConfigSyncPayload.java、RequestConfigSyncPayload.java、
 *         ConfigUpdatePayload.java、OpenConfigScreenPayload.java 合并而来，
 *         各嵌套类型行为与网络字节格式与拆分前完全一致（ID/CODEC 未改动）。
 */
public final class ConfigPayloads {

    // 【作用】工具容器，禁止实例化
    private ConfigPayloads() {
    }

    /**
     * 【作用】配置元数据包（S2C，小包）：携带当前核心配置（maps+global）的 SHA-256 哈希与
     *         inUseMaps（对局使用中地图列表）JSON。哈希握手省带宽的核心：
     *         服务端先发哈希，客户端与自己磁盘缓存比对，一致则只回哈希不再重复接收全量配置。
     *         inUseMaps 从 ConfigSyncPayload 中剥离、随本包单独下发，避免对局开始/结束
     *         频繁改变配置 JSON 哈希导致缓存频繁失效。
     * 【被谁使用】S2C（服务端→客户端）。服务端 NetworkHandler 在玩家 JOIN（哈希握手首包）、
     *         哈希匹配回应、全量同步随行、配置保存广播时发送；客户端 ClientNetworkHandler
     *         注册接收器 → 比对磁盘缓存哈希后决定回报哈希或请求全量，并更新 inUseMaps 缓存。
     */
    public record ConfigMetaPayload(
            String configHash,
            String inUseMapsJson
    ) implements CustomPayload {

        // 【作用】包类型标识（cstmm:config_meta），由 NetworkHandler 注册并由网络层路由
        public static final Id<ConfigMetaPayload> ID = new Id<>(
                Identifier.of("cstmm", "config_meta")
        );

        // 【作用】编解码器：哈希（≤128 字符，SHA-256 hex 为 64）+ inUseMaps JSON（≤32767 字节）与 PacketByteBuf 互转
        public static final PacketCodec<PacketByteBuf, ConfigMetaPayload> CODEC = PacketCodec.of(
                (payload, buf) -> {
                    buf.writeString(NetworkHandler.truncateByUtf8Bytes(payload.configHash(), 128), 128);
                    buf.writeString(NetworkHandler.truncateByUtf8Bytes(payload.inUseMapsJson(), 32767), 32767);
                },
                buf -> new ConfigMetaPayload(
                        buf.readString(128),
                        buf.readString(32767)
                )
        );

        @Override
        public Id<ConfigMetaPayload> getId() {
            return ID;
        }
    }

    /**
     * 配置同步（S2C）：核心配置 JSON（{"hash":..,"maps":[..],"global":{..}}，不含 inUseMaps）
     * 过大时分包发送。partIndex 从 0 开始，totalParts 为总分包数；客户端按序拼接后解析。
     * hash 字段用于客户端磁盘缓存比对（哈希握手省带宽）；inUseMaps 由 ConfigMetaPayload 单独下发。
     *
     * 【被谁使用】S2C（服务端→客户端）。服务端 NetworkHandler#sendConfigSync 在玩家 JOIN 哈希握手
     * 不匹配/超时兜底、收到 RequestConfigSyncPayload（哈希不匹配）请求、配置保存后全服广播时分包发送；
     * 客户端 ClientNetworkHandler 注册接收器按 partIndex 重组 → applyConfigSync 解析进 ConfigDataCache
     * 并持久化到磁盘缓存。
     */
    public record ConfigSyncPayload(
            int partIndex,
            int totalParts,
            String data
    ) implements CustomPayload {

        // 【作用】包类型标识（cstmm:config_sync），由 NetworkHandler 注册并由网络层路由
        public static final Id<ConfigSyncPayload> ID = new Id<>(
                Identifier.of("cstmm", "config_sync")
        );

        // 【作用】编解码器：分包序号/总数（VarInt）+ 分片数据（上限 32767 字节）与 PacketByteBuf 互转
        public static final PacketCodec<PacketByteBuf, ConfigSyncPayload> CODEC = PacketCodec.of(
                (payload, buf) -> {
                    buf.writeVarInt(payload.partIndex());
                    buf.writeVarInt(payload.totalParts());
                    buf.writeString(NetworkHandler.truncateByUtf8Bytes(payload.data(), 32767), 32767);
                },
                buf -> new ConfigSyncPayload(
                        buf.readVarInt(),
                        buf.readVarInt(),
                        buf.readString(32767)
                )
        );

        @Override
        public Id<ConfigSyncPayload> getId() {
            return ID;
        }
    }

    /**
     * 【作用】配置同步请求（C2S）：携带客户端本地缓存的核心配置哈希（无缓存/哈希不适用时为空串）。
     *         服务端收到后与自己当前配置哈希比对：一致 → 仅回 ConfigMetaPayload（更新 inUseMaps，
     *         省掉全量 JSON 下发）；不一致 → 全量 ConfigSyncPayload + ConfigMetaPayload。
     * 【被谁使用】C2S（客户端→服务端）。客户端 ClientNetworkHandler 在 JOIN 哈希握手（收到
     *         ConfigMetaPayload 后）与打开/刷新配置界面（ConfigScreen，带每秒 50 次客户端自限）时发送；
     *         服务端 NetworkHandler 注册接收器 → 哈希比对后回发。编解码对空载荷做防御
     *         （旧版客户端发的是无字段空包，读到 0 字节时按空串处理，避免解码越界断连）。
     */
    public record RequestConfigSyncPayload(
            String clientHash
    ) implements CustomPayload {
        // 【作用】包类型标识（cstmm:request_config_sync），由 NetworkHandler 注册并由网络层路由
        public static final Id<RequestConfigSyncPayload> ID = new Id<>(
                Identifier.of("cstmm", "request_config_sync")
        );

        // 【作用】编解码器：客户端缓存哈希（≤128 字符）与 PacketByteBuf 互转；读取端对空载荷防御（兼容旧版客户端空包）
        public static final PacketCodec<PacketByteBuf, RequestConfigSyncPayload> CODEC = PacketCodec.of(
                (payload, buf) -> buf.writeString(payload.clientHash(), 128),
                buf -> new RequestConfigSyncPayload(
                        buf.readableBytes() > 0 ? buf.readString(128) : ""
                )
        );

        @Override
        public Id<RequestConfigSyncPayload> getId() {
            return ID;
        }
    }

    /**
     * C2S 配置更新包（分包）。
     * 原版对 C2S payload 有 32768 字节硬限制，含 base64 背景图的配置 JSON 必须分包发送，
     * 由服务端按玩家重组。分包大小与 ConfigSyncPayload 一致（30000 字符）。
     *
     * 【被谁使用】C2S（客户端→服务端）。客户端 ClientNetworkHandler#sendConfigUpdate 在配置界面
     * 保存时按 UTF-8 字节边界分包发送（ClientPlayNetworking.send）；服务端 NetworkHandler 注册
     * 接收器 → handleConfigUpdatePart 按玩家重组并保存配置（成功后回发 PopupPayload 提示）。
     */
    public record ConfigUpdatePayload(
            int partIndex,
            int totalParts,
            String data
    ) implements CustomPayload {

        // 【作用】包类型标识（cstmm:config_update），由 NetworkHandler 注册并由网络层路由
        public static final Id<ConfigUpdatePayload> ID = new Id<>(
                Identifier.of("cstmm", "config_update")
        );

        // 【作用】编解码器：分包序号/总数（VarInt）+ 分片数据（上限 32767 字节）与 PacketByteBuf 互转
        public static final PacketCodec<PacketByteBuf, ConfigUpdatePayload> CODEC = PacketCodec.of(
                (payload, buf) -> {
                    buf.writeVarInt(payload.partIndex());
                    buf.writeVarInt(payload.totalParts());
                    buf.writeString(NetworkHandler.truncateByUtf8Bytes(payload.data(), 32767), 32767);
                },
                buf -> new ConfigUpdatePayload(
                        buf.readVarInt(),
                        buf.readVarInt(),
                        buf.readString(32767)
                )
        );

        @Override
        public Id<ConfigUpdatePayload> getId() {
            return ID;
        }
    }

    /**
     * 服务端 → 客户端：通知客户端打开配置界面
     *
     * 【被谁使用】S2C（服务端→客户端）。服务端 ModCommands 的 /cstmm config 子命令（权限等级 2）
     * 与 NetworkHandler#sendOpenConfigScreen 发送；客户端 ClientNetworkHandler 注册接收器
     * → 当前不在 ConfigScreen 时打开之。
     */
    public record OpenConfigScreenPayload() implements CustomPayload {

        // 【作用】包类型标识（cstmm:open_config_screen），由 NetworkHandler 注册并由网络层路由
        public static final Id<OpenConfigScreenPayload> ID = new Id<>(
                Identifier.of("cstmm", "open_config_screen")
        );

        public static final PacketCodec<PacketByteBuf, OpenConfigScreenPayload> CODEC = PacketCodec.of(
                (payload, buf) -> {
                    // 无数据
                },
                buf -> new OpenConfigScreenPayload()
        );

        @Override
        public Id<OpenConfigScreenPayload> getId() {
            return ID;
        }
    }
}
