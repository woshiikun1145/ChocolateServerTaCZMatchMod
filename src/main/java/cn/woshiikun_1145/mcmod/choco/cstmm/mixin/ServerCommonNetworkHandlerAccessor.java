package cn.woshiikun_1145.mcmod.choco.cstmm.mixin;

import net.minecraft.network.ClientConnection;
import net.minecraft.server.network.ServerCommonNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 【作用】ServerCommonNetworkHandler 的 connection 字段访问器（yarn 映射下为 protected）：
 *         带宽统计需要从 ServerPlayNetworkHandler 取底层连接再取 Netty 管线。
 * 【被谁使用】NetworkHandler（玩家 JOIN 时把 ServerPlayNetworkHandler 强转为本访问器取连接）。仅服务端使用。
 */
@Mixin(ServerCommonNetworkHandler.class)
public interface ServerCommonNetworkHandlerAccessor {

    /** 【作用】读取服务端网络处理器的底层连接。 */
    @Accessor("connection")
    ClientConnection cstmm$getConnection();
}
