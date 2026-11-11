package cn.woshiikun_1145.mcmod.choco.cstmm.mixin;

import io.netty.channel.Channel;
import net.minecraft.network.ClientConnection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 【作用】ClientConnection 的 channel 字段访问器（yarn 映射下为 private）：
 *         带宽统计需要向连接的 Netty 管线注入字节计数器。
 * 【被谁使用】NetworkHandler（玩家 JOIN 时经 Mixin 访问器取管线注入计数器）。仅服务端使用。
 */
@Mixin(ClientConnection.class)
public interface ClientConnectionAccessor {

    /** 【作用】读取连接的 Netty 通道（管线注入入口）。 */
    @Accessor("channel")
    Channel cstmm$getChannel();
}
