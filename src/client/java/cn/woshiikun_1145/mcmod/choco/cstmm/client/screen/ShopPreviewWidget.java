package cn.woshiikun_1145.mcmod.choco.cstmm.client.screen;

import cn.woshiikun_1145.mcmod.choco.cstmm.client.util.Base64ImageDecoder;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.util.UrlImageCache;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.config.MapConfig;
import cn.woshiikun_1145.mcmod.choco.cstmm.util.ItemStrings;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Drawable;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;

import java.util.List;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * 【作用】购买页实时预览控件（配置界面 → 商店分类列下方）：
 *         按当前编辑中的地图配置即时绘制 5 列网格——爆破图按队伍分开预览：CT 视角第 1 列显示
 *         拆弹装备购买项，T 视角第 1 列为空列（与购买页一致）；普通图为 5 列分类网格。
 *         列头名称/图标与商品格"名称 + 价格"随编辑实时刷新。
 * 【被谁使用】ConfigScreen（普通图分类列编辑器、爆破图 T/CT 分类列编辑器末尾加入滚动区）。
 */
final class ShopPreviewWidget implements Drawable {
    // 每帧从编辑中的 MapConfig 取配置（闭包引用，保证实时性）
    private final Supplier<MapConfig> mapSupplier;
    // 拆弹装备价格（爆破图 CT 第 1 列固定项展示用；取编辑中的全局经济参数）
    private final IntSupplier defuserPriceSupplier;
    // 是否爆破图预览（决定第 1 列是否为固定装备列）
    private final boolean bomb;
    // 爆破图预览视角：true=T 方（红队，第 1 列空列）；false=CT 方（蓝队，第 1 列拆弹装备）
    private final boolean tTeam;
    private final int x;
    private final int y;
    private final int width;
    private final int height;
    private final int colWidth;

    ShopPreviewWidget(int x, int y, int width, int height,
                      Supplier<MapConfig> mapSupplier, IntSupplier defuserPriceSupplier,
                      boolean bomb, boolean tTeam) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.mapSupplier = mapSupplier;
        this.defuserPriceSupplier = defuserPriceSupplier;
        this.bomb = bomb;
        this.tTeam = tTeam;
        this.colWidth = (width - 4 * 4) / 5;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        MapConfig map = mapSupplier.get();
        if (map == null) return;

        var tr = MinecraftClient.getInstance().textRenderer;
        // 预览底板
        context.fill(x - 4, y - 4, x + width + 4, y + height + 4, 0x33000000);
        String title = bomb ? (tTeam ? "§7购买页预览（T 方/红队视角）:" : "§7购买页预览（CT 方/蓝队视角）:")
                : "§7购买页预览:";
        context.drawText(tr, title, x - 4, y - 14, 0x888888, true);

        int bodyTop = y;
        int bodyBottom = y + height;
        // 爆破图按预览视角的队伍取列（与购买页下发一致）；普通图取通用分类列
        List<MapConfig.ShopColumn> cols = bomb
                ? map.getBombShopColumnsForTeam(tTeam) : map.getShopColumns();

        for (int c = 0; c < 5; c++) {
            int cx = x + c * (colWidth + 4);
            context.fill(cx, bodyTop, cx + colWidth, bodyBottom, 0x66000000);

            // 列头：爆破图第 1 列固定装备列（CT 显示拆弹装备项，T 方空列）；其余取分类配置
            String name;
            String icon = "";
            List<Row> items = List.of();
            if (bomb && c == 0) {
                // T 方空列：列头仅标注提示；CT：列头"装备" + 拆弹装备项（价格取全局经济参数）
                name = tTeam ? "" : "装备";
                if (!tTeam) {
                    items = List.of(new Row("拆弹装备", "$" + defuserPriceSupplier.getAsInt(), null, ItemStack.EMPTY));
                }
            } else {
                int idx = bomb ? c - 1 : c;
                if (idx >= cols.size()) {
                    context.drawText(tr, "§8（未配置）", cx + 4, bodyTop + 3, 0x666666, true);
                    continue;
                }
                name = cols.get(idx).getName();
                icon = cols.get(idx).getIcon();
                items = cols.get(idx).getItems().stream()
                        .map(i -> {
                            ItemStack st = stackOf(i.getItemId());
                            String disp = i.getDisplayName().isEmpty()
                                    ? (st.isEmpty() ? String.valueOf(i.getItemId()) : st.getName().getString())
                                    : i.getDisplayName();
                            return new Row(disp, "$" + Math.max(0, i.getPrice()), i.getIcon(), st);
                        })
                        .toList();
            }

            // 爆破图 T 方第 1 列为空列（与购买页一致），标注提示
            if (bomb && c == 0 && tTeam) {
                context.drawText(tr, "§8（T 方空列）", cx + 4, bodyTop + 3, 0x666666, true);
            }

            if (!icon.isEmpty()) {
                var tex = icon.startsWith("http://") || icon.startsWith("https://")
                        ? UrlImageCache.get(icon) : Base64ImageDecoder.decode(icon);
                if (tex != null) {
                    context.drawTexture(tex.id(), cx + 2, bodyTop + 1, 12, 12,
                            0f, 0f, tex.width(), tex.height(), tex.width(), tex.height());
                }
            }
            context.drawText(tr, tr.trimToWidth(name, colWidth - (icon.isEmpty() ? 8 : 22)),
                    cx + (icon.isEmpty() ? 4 : 17), bodyTop + 3, 0xFFFFFF, true);

            // 商品格（可容纳几行画几行）
            int rowTop = bodyTop + 16;
            int fitRows = Math.max(0, (bodyBottom - rowTop) / 24);
            for (int r = 0; r < items.size() && r < fitRows; r++) {
                Row item = items.get(r);
                int ry = rowTop + r * 24;
                context.fill(cx + 1, ry, cx + colWidth - 1, ry + 21, 0x33FFFFFF);
                // 格内图标（base64/URL/空=物品贴图）
                String ic = item.icon();
                if (ic == null || ic.isEmpty()) {
                    if (!item.stack().isEmpty()) {
                        context.drawItem(item.stack(), cx + 3, ry + 2);
                    }
                } else {
                    var tex = ic.startsWith("http://") || ic.startsWith("https://")
                            ? UrlImageCache.get(ic) : Base64ImageDecoder.decode(ic);
                    if (tex != null) {
                        context.drawTexture(tex.id(), cx + 3, ry + 2, 16, 16,
                                0f, 0f, tex.width(), tex.height(), tex.width(), tex.height());
                    }
                }
                context.drawText(tr, tr.trimToWidth(item.name(), colWidth - 40), cx + 22, ry + 3, 0xFFFFFF, true);
                context.drawText(tr, item.price(), cx + 22, ry + 12, 0xFFFF55, true);
            }
            if (items.size() > fitRows) {
                context.drawText(tr, "§8…", cx + 4, bodyBottom - 10, 0x666666, true);
            }
        }
    }

    // 轻量预览条目（名称/价格/图标/物品栈解耦于配置对象）
    private record Row(String name, String price, String icon, ItemStack stack) {}

    /** 物品串 → ItemStack（/give 格式等，统一走 ItemStrings；仅用于预览贴图，解析失败返回空栈）。 */
    private ItemStack stackOf(String raw) {
        if (raw == null || raw.isEmpty()) return ItemStack.EMPTY;
        MinecraftClient client = MinecraftClient.getInstance();
        var lookup = client.world != null ? client.world.getRegistryManager() : null;
        return ItemStrings.toStack(raw, lookup);
    }
}
