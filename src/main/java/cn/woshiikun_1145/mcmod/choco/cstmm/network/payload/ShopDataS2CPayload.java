package cn.woshiikun_1145.mcmod.choco.cstmm.network.payload;

import cn.woshiikun_1145.mcmod.choco.cstmm.data.config.GlobalConfig;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.NetworkHandler;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * 商店数据同步包（S2C）：
 * 玩家请求商店（REQUEST_SHOP）时，服务端返回购买资格与购买页的完整网格布局：
 * 爆破图 = 1+4 列（第 1 列固定"装备"列，仅 CT 携带拆弹装备购买项，T 方空列），
 * 死亡竞赛图 = 5 列分类网格；每列携带列头名称与列内商品列表（商品含价格/显示名/图标）。
 * 爆破图额外携带经济信息（余额、购买窗口、拆弹装备价格）；死亡竞赛图免费发放。
 *
 * 【被谁使用】S2C（服务端→客户端）。服务端 NetworkHandler#sendShopData（REQUEST_SHOP/BUY_ITEM
 * 成功后与订阅制推送共用）经 create 构造发送；客户端 ClientNetworkHandler 注册接收器
 * → ShopDataCache#update 缓存并刷新已打开的 ShopScreen。
 */
public record ShopDataS2CPayload(
        boolean eligible,
        boolean bombMode,
        // 爆破图第 1 列是否显示拆弹装备购买项（服务端按阵营判定：CT=true，T=false 空列）
        boolean hasDefuser,
        int money,
        boolean buyWindowOpen,
        int defuserPrice,
        // 玩家是否已持有拆弹装备（tag 态，2026-11-12 限购配套）：true 时客户端把购买项置灰显示"已持有"
        boolean ownedDefuser,
        List<ColumnData> columns
) implements CustomPayload {

    /**
     * 【作用】购买页单列数据：列头名称 + 列内商品列表（爆破图第 1 列固定为"装备"列，
     *         items 恒为空列表，拆弹装备购买项由客户端按 hasDefuser 单独渲染）。
     */
    public record ColumnData(String name, List<GlobalConfig.ShopItem> items) {}

    // 【作用】包类型标识（cstmm:shop_data），由 NetworkHandler 注册并由网络层路由
    public static final Id<ShopDataS2CPayload> ID = new Id<>(
            Identifier.of("cstmm", "shop_data")
    );

    // 【作用】编解码器：资格/模式/装备列位 + 爆破经济三字段 + 逐列（列名/列内商品逐项）
    public static final PacketCodec<PacketByteBuf, ShopDataS2CPayload> CODEC = PacketCodec.of(
            (payload, buf) -> {
                buf.writeBoolean(payload.eligible());
                buf.writeBoolean(payload.bombMode());
                buf.writeBoolean(payload.hasDefuser());
                buf.writeVarInt(Math.max(0, payload.money()));
                buf.writeBoolean(payload.buyWindowOpen());
                buf.writeVarInt(Math.max(0, payload.defuserPrice()));
                buf.writeBoolean(payload.ownedDefuser());
                List<ColumnData> cols = payload.columns() == null ? List.of() : payload.columns();
                buf.writeVarInt(cols.size());
                for (ColumnData col : cols) {
                    buf.writeString(truncUtf8(col.name(), 64), 64);
                    List<GlobalConfig.ShopItem> items = col.items() == null ? List.of() : col.items();
                    // 先数非空条目再写（null 元素跳过，计数与实际写入一致防客户端读包错位）
                    int count = 0;
                    for (GlobalConfig.ShopItem item : items) {
                        if (item != null) count++;
                    }
                    buf.writeVarInt(count);
                    for (GlobalConfig.ShopItem item : items) {
                        if (item == null) continue;
                        writeItem(buf, item);
                    }
                }
            },
            buf -> {
                boolean eligible = buf.readBoolean();
                boolean bombMode = buf.readBoolean();
                boolean hasDefuser = buf.readBoolean();
                int money = Math.max(0, buf.readVarInt());
                boolean buyWindowOpen = buf.readBoolean();
                int defuserPrice = Math.max(0, buf.readVarInt());
                boolean ownedDefuser = buf.readBoolean();
                int colCount = Math.max(0, buf.readVarInt());
                List<ColumnData> columns = new ArrayList<>(colCount);
                for (int c = 0; c < colCount; c++) {
                    String name = buf.readString(64);
                    int itemCount = Math.max(0, buf.readVarInt());
                    List<GlobalConfig.ShopItem> items = new ArrayList<>(itemCount);
                    for (int i = 0; i < itemCount; i++) {
                        items.add(readItem(buf));
                    }
                    columns.add(new ColumnData(name, List.copyOf(items)));
                }
                return new ShopDataS2CPayload(eligible, bombMode, hasDefuser, money, buyWindowOpen,
                        defuserPrice, ownedDefuser, List.copyOf(columns));
            }
    );

    // 【作用】物品串线路上限（UTF-16 字符数，两端 codec 共用此常量）：
    //        /give 格式带组件的物品串（custom_name/lore/block_entity_data 等）可远超 256 字符，
    //        1.21.1 writeString 第一道校验是"字符数 > 上限即抛 EncoderException"，
    //        未截断会直接炸掉服务端编码器导致玩家断连（2026-11-12 shop_data EncoderException 根因）。
    //        线路副本仅供客户端预览图标/名称，服务端发放仍用配置原件，截断只影响预览不影响购买。
    public static final int ITEM_ID_MAX_CHARS = 2048;

    // 【作用】写单个商品条目（物品串/价格/限购 + 队伍/显示名/图标，全部经 UTF-8 安全截断防断连）
    private static void writeItem(PacketByteBuf buf, GlobalConfig.ShopItem item) {
        buf.writeString(NetworkHandler.truncateByUtf8Bytes(
                item.getItemId() == null ? "" : item.getItemId(), ITEM_ID_MAX_CHARS), ITEM_ID_MAX_CHARS);
        buf.writeVarInt(Math.max(0, item.getPrice()));
        buf.writeVarInt(Math.max(0, item.getMaxPurchase()));
        buf.writeString(NetworkHandler.truncateByUtf8Bytes(item.getTeam(), 8), 8);
        buf.writeString(NetworkHandler.truncateByUtf8Bytes(item.getDisplayName(), 256), 256);
        buf.writeString(NetworkHandler.truncateByUtf8Bytes(item.getIcon(), 32000), 32000);
    }

    // 【作用】读单个商品条目（与 writeItem 一一对应）
    private static GlobalConfig.ShopItem readItem(PacketByteBuf buf) {
        String itemId = buf.readString(ITEM_ID_MAX_CHARS);
        int price = buf.readVarInt();
        int maxPurchase = buf.readVarInt();
        String team = buf.readString(8);
        String displayName = buf.readString(256);
        String icon = buf.readString(32000);
        return new GlobalConfig.ShopItem(itemId, price, maxPurchase, team, displayName, icon);
    }

    /** 按 UTF-8 字节数截断（防 writeString 超上限抛异常断连；null 一律按空串写，切断多字节产生的残字符仅影响显示） */
    private static String truncUtf8(String s, int maxBytes) {
        // 【作用】统一委托 NetworkHandler.truncateByUtf8Bytes（不切断多字节字符的实现，
        //        本类原字节硬切实现会在多字节边界回涨出替换字符，已被替换）
        return NetworkHandler.truncateByUtf8Bytes(s, maxBytes);
    }

    /**
     * 【作用】完整构造（columns 为 null 时按空列表处理）。
     * 【被谁使用】NetworkHandler#sendShopData（服务端下发）与 REQUEST_SHOP 无对局分支（不可购空数据）。
     */
    public static ShopDataS2CPayload create(boolean eligible, boolean bombMode, boolean hasDefuser,
                                            int money, boolean buyWindowOpen, int defuserPrice,
                                            boolean ownedDefuser, List<ColumnData> columns) {
        List<ColumnData> safeCols = columns == null ? List.of() : columns;
        return new ShopDataS2CPayload(eligible, bombMode, hasDefuser, money, buyWindowOpen,
                defuserPrice, ownedDefuser, safeCols);
    }

    @Override
    public Id<ShopDataS2CPayload> getId() {
        return ID;
    }
}
