package cn.woshiikun_1145.mcmod.choco.cstmm.client.screen;

import cn.woshiikun_1145.mcmod.choco.cstmm.client.cache.ShopDataCache;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.config.GlobalConfig;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.payload.MatchActionPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.List;

/**
 * 商店界面 - 商品按地图配置，打开时向服务端请求，仅竞技模式对局中可购买
 */
@Environment(EnvType.CLIENT)
public class ShopScreen extends Screen {

    public ShopScreen() {
        super(Text.literal("商店"));
        // 打开商店时向服务端请求最新商店数据（仅在构造时请求一次，数据到达后由接收器刷新界面，避免循环请求）
        ClientPlayNetworking.send(new MatchActionPayload(
                MatchActionPayload.ActionType.REQUEST_SHOP, "", 0, ""));
    }

    @Override
    protected void init() {
        super.init();

        int screenWidth = this.width;
        int screenHeight = this.height;

        // 标题
        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("🎒 物品商店"),
                b -> {}
        ).dimensions(20, 10, 160, 20).build());

        // 返回按钮
        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("返回"),
                button -> close()
        ).dimensions(screenWidth - 80, 10, 60, 20).build());

        // 生成商品按钮（服务端下发数据且可购买时才显示）
        List<GlobalConfig.ShopItem> items = ShopDataCache.getInstance().getItems();
        if (ShopDataCache.getInstance().hasData()
                && ShopDataCache.getInstance().isEligible() && !items.isEmpty()) {
            int y = 45;
            for (int i = 0; i < items.size(); i++) {
                GlobalConfig.ShopItem item = items.get(i);
                String label = getItemDisplayName(item) + " §e- 价格: " + item.getPrice();
                if (item.getMaxPurchase() > 0) {
                    label += " §7(限购 " + item.getMaxPurchase() + " 次)";
                }
                final int index = i;
                this.addDrawableChild(ButtonWidget.builder(
                        Text.literal(label),
                        button -> buyItem(index)
                ).dimensions(20, y, 320, 24).build());
                y += 28;
            }
        }

        // 提示信息（底部）
        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("§7点击按钮即可购买物品"),
                b -> {}
        ).dimensions(20, screenHeight - 40, 200, 20).build());
    }

    /**
     * 获取物品显示名称
     * itemId 格式："minecraft:diamond_sword" 或带 NBT："minecraft:diamond_sword{...}"
     */
    private String getItemDisplayName(GlobalConfig.ShopItem item) {
        String raw = item.getItemId();
        if (raw == null || raw.isEmpty()) return "§7未知物品";

        // 拆分物品ID与NBT部分
        String idPart = raw;
        String nbtPart = null;
        int brace = raw.indexOf('{');
        if (brace >= 0) {
            idPart = raw.substring(0, brace).trim();
            nbtPart = raw.substring(brace).trim();
        }
        if (!idPart.contains(":")) idPart = "minecraft:" + idPart;

        try {
            Item baseItem = Registries.ITEM.get(Identifier.of(idPart));
            if (baseItem == Items.AIR) return "§7" + raw;

            ItemStack stack = new ItemStack(baseItem);

            // 尝试应用NBT（兼容 1.21 components 与旧版 tag 格式）
            if (nbtPart != null) {
                try {
                    NbtElement parsed = NbtHelper.fromNbtProviderString(nbtPart);
                    if (parsed instanceof NbtCompound compound) {
                        MinecraftClient client = MinecraftClient.getInstance();
                        if (client.world != null) {
                            NbtCompound full = new NbtCompound();
                            full.putString("id", idPart);
                            full.putByte("count", (byte) 1);
                            if (compound.contains("components")) {
                                full.put("components", compound.get("components"));
                            } else if (!compound.isEmpty()) {
                                full.put("tag", compound);
                            }
                            stack = ItemStack.fromNbt(client.world.getRegistryManager(), full)
                                    .filter(s -> !s.isEmpty())
                                    .orElse(stack);
                        }
                    }
                } catch (Exception ignored) {
                    // NBT 解析失败则只显示基础物品名
                }
            }
            return "§f" + stack.getName().getString();
        } catch (Exception e) {
            return "§7" + raw;
        }
    }

    /**
     * 购买物品（由服务端校验资格、扣费与限购）
     */
    private void buyItem(int index) {
        List<GlobalConfig.ShopItem> items = ShopDataCache.getInstance().getItems();
        if (index < 0 || index >= items.size()) return;

        MatchActionPayload payload = new MatchActionPayload(
                MatchActionPayload.ActionType.BUY_ITEM,
                String.valueOf(index),
                0,
                ""
        );
        ClientPlayNetworking.send(payload);

        if (MinecraftClient.getInstance().player != null) {
            MinecraftClient.getInstance().player.sendMessage(
                    Text.literal("§a购买请求已发送！"), false
            );
        }
    }

    /**
     * 收到服务端商店数据后重载界面（由 ClientNetworkHandler 调用）
     */
    public void refresh() {
        clearChildren();
        init();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);

        ShopDataCache cache = ShopDataCache.getInstance();
        context.drawText(textRenderer, "§6━━━ 物品商店 ━━━", this.width / 2 - 90, 12, 0xFFFFFF, true);
        context.drawText(textRenderer, "§7点击下方按钮即可购买物品", 20, 32, 0xAAAAAA, true);

        // 显示物品数量
        String countInfo = "§7共 " + cache.getItems().size() + " 个物品";
        context.drawText(textRenderer, countInfo, this.width - 150, 12, 0x666666, true);

        // 状态提示
        if (!cache.hasData()) {
            context.drawText(textRenderer, "§7正在获取商品...", 20, 45, 0xAAAAAA, true);
        } else if (!cache.isEligible() || cache.getItems().isEmpty()) {
            context.drawText(textRenderer, "§e当前不在竞技模式对局中，无法购买", 20, 45, 0xFFFF55, true);
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
