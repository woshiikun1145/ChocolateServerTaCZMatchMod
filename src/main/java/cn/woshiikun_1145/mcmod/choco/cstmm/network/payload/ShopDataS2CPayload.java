package cn.woshiikun_1145.mcmod.choco.cstmm.network.payload;

import cn.woshiikun_1145.mcmod.choco.cstmm.data.config.GlobalConfig;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * 商店数据同步包（S2C）：
 * 玩家请求商店（REQUEST_SHOP）时，服务端返回其购买资格与所在地图的商品列表
 */
public record ShopDataS2CPayload(
        boolean eligible,
        int itemCount,
        List<GlobalConfig.ShopItem> items
) implements CustomPayload {

    public static final Id<ShopDataS2CPayload> ID = new Id<>(
            Identifier.of("cstmm", "shop_data")
    );

    public static final PacketCodec<PacketByteBuf, ShopDataS2CPayload> CODEC = PacketCodec.of(
            (payload, buf) -> {
                buf.writeBoolean(payload.eligible());
                buf.writeVarInt(payload.itemCount());
                for (GlobalConfig.ShopItem item : payload.items()) {
                    buf.writeString(item.getItemId() == null ? "" : item.getItemId(), 256);
                    buf.writeVarInt(item.getPrice());
                    buf.writeVarInt(item.getMaxPurchase());
                }
            },
            buf -> {
                boolean eligible = buf.readBoolean();
                int count = Math.max(0, buf.readVarInt());
                List<GlobalConfig.ShopItem> items = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    String itemId = buf.readString(256);
                    int price = buf.readVarInt();
                    int maxPurchase = buf.readVarInt();
                    items.add(new GlobalConfig.ShopItem(itemId, price, maxPurchase));
                }
                return new ShopDataS2CPayload(eligible, count, List.copyOf(items));
            }
    );

    /** 静态工厂：items 为 null 时按空列表处理，保证 itemCount 与列表长度一致 */
    public static ShopDataS2CPayload create(boolean eligible, List<GlobalConfig.ShopItem> items) {
        List<GlobalConfig.ShopItem> safe = items == null ? List.of() : items;
        return new ShopDataS2CPayload(eligible, safe.size(), safe);
    }

    @Override
    public Id<ShopDataS2CPayload> getId() {
        return ID;
    }
}
