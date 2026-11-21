package cn.woshiikun_1145.mcmod.choco.cstmm.client.screen;

import cn.woshiikun_1145.mcmod.choco.cstmm.client.util.Base64ImageDecoder;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.util.UrlImageCache;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.config.GlobalConfig;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.config.MapConfig;
import cn.woshiikun_1145.mcmod.choco.cstmm.util.ItemStrings;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Drawable;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * 【作用】购买页实时预览控件（配置界面 → 商店分类列下方）：
 *         按当前编辑中的地图配置即时绘制 5 列网格——爆破图按队伍分开预览：CT 视角第 1 列显示
 *         拆弹装备购买项，T 视角第 1 列为空列（与购买页一致）；死亡竞赛图为 5 列分类网格。
 *         列头名称/图标与商品格"名称 + 价格"随编辑实时刷新；列内商品超出可视行时
 *         鼠标滚轮逐列滚动（与购买页一致）。
 * 【被谁使用】ConfigScreen（死亡竞赛图分类列编辑器、爆破图 T/CT 分类列编辑器末尾加入滚动区）。
 */
final class ShopPreviewWidget implements Drawable {
    // 每帧从编辑中的 MapConfig 取配置（闭包引用，保证实时性）
    private final Supplier<MapConfig> mapSupplier;
    // 拆弹装备价格（爆破图 CT 第 1 列固定项展示用；取编辑中的全局经济参数）
    private final IntSupplier defuserPriceSupplier;
    // 是否爆破图预览（决定第 1 列是否为固定装备列）
    private final boolean bomb;
    // 爆破图预览视角：true=T 方（第 1 列空列）；false=CT 方（第 1 列拆弹装备）
    private final boolean tTeam;
    private final int x;
    private final int y;
    private final int width;
    private final int height;
    private final int colWidth;
    // 各列滚动行偏移（由 ConfigScreen 持有，跨 rebuild 保持）
    private final int[] rowOffsets;
    // 各列当前商品行数（render 时更新，供滚轮计算上限）
    private final int[] rowCounts = new int[5];

    ShopPreviewWidget(int x, int y, int width, int height,
                      Supplier<MapConfig> mapSupplier, IntSupplier defuserPriceSupplier,
                      boolean bomb, boolean tTeam, int[] rowOffsets) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.mapSupplier = mapSupplier;
        this.defuserPriceSupplier = defuserPriceSupplier;
        this.bomb = bomb;
        this.tTeam = tTeam;
        this.colWidth = (width - 4 * 4) / 5;
        this.rowOffsets = rowOffsets;
    }

    /**
     * 【作用】鼠标滚轮：悬停在预览上时逐列滚动商品；仅当该列内容超出可视行时消费事件，
     *         否则返回 false 交回 ConfigScreen 滚动整页。
     * @param mouseY 已加上页面滚动偏移后的控件坐标（screen Y + scrollOffset）
     */
    boolean mouseScrolled(double mouseX, double mouseY, double verticalAmount) {
        if (mouseX < x || mouseX >= x + width || mouseY < y || mouseY >= y + height) return false;
        int c = (int) ((mouseX - x) / (colWidth + 4));
        if (c < 0 || c >= 5) return false;
        int visibleRows = Math.max(0, (height - 16) / 24);
        int maxOffset = Math.max(0, rowCounts[c] - visibleRows);
        if (maxOffset <= 0) return false;
        int delta = verticalAmount > 0 ? -1 : 1;
        rowOffsets[c] = Math.max(0, Math.min(maxOffset, rowOffsets[c] + delta));
        return true;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        MapConfig map = mapSupplier.get();
        if (map == null) return;

        var tr = MinecraftClient.getInstance().textRenderer;
        // 预览底板
        context.fill(x - 4, y - 4, x + width + 4, y + height + 4, 0x33000000);
        String title = bomb ? (tTeam ? "§7购买页预览（T 方视角）:" : "§7购买页预览（CT 方视角）:")
                : "§7购买页预览:";
        context.drawText(tr, title, x - 4, y - 14, 0x888888, true);

        int bodyTop = y;
        int bodyBottom = y + height;
        // 爆破图按预览视角的队伍取列（与购买页下发一致）；死亡竞赛图取通用分类列
        List<MapConfig.ShopColumn> cols = bomb
                ? map.getBombShopColumnsForTeam(tTeam) : map.getShopColumns();

        for (int c = 0; c < 5; c++) {
            int cx = x + c * (colWidth + 4);
            context.fill(cx, bodyTop, cx + colWidth, bodyBottom, 0x66000000);

            // 列头：爆破图第 1 列固定装备列（CT 显示拆弹装备项，T 方空列）；其余取分类配置
            String name;
            List<Row> items = List.of();
            if (bomb && c == 0) {
                // 第 1 列：拆弹装备为固定首项（仅 CT），其后为地图配置的附加商品（按队伍过滤）
                List<GlobalConfig.ShopItem> extra = map.getShopColumn1ItemsForTeam(tTeam);
                name = (tTeam && extra.isEmpty()) ? "" : "装备";
                List<Row> rows = new ArrayList<>();
                if (!tTeam) {
                    rows.add(new Row("拆弹装备", "$" + defuserPriceSupplier.getAsInt(), null, ItemStack.EMPTY));
                }
                for (GlobalConfig.ShopItem i : extra) {
                    ItemStack st = stackOf(i.getItemId());
                    String disp = i.getDisplayName() == null || i.getDisplayName().isEmpty()
                            ? (st.isEmpty() ? String.valueOf(i.getItemId()) : st.getName().getString())
                            : i.getDisplayName();
                    rows.add(new Row(disp, "$" + Math.max(0, i.getPrice()), i.getIcon(), st));
                }
                items = rows;
            } else {
                int idx = bomb ? c - 1 : c;
                if (idx >= cols.size()) {
                    rowCounts[c] = 0;
                    rowOffsets[c] = 0;
                    context.drawText(tr, "§8（未配置）", cx + 4, bodyTop + 3, 0x666666, true);
                    continue;
                }
                name = cols.get(idx).getName();
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

            // 爆破图 T 方第 1 列无附加商品时为空列（与购买页一致），标注提示
            if (bomb && c == 0 && tTeam && items.isEmpty()) {
                context.drawText(tr, "§8（T 方空列）", cx + 4, bodyTop + 3, 0x666666, true);
            }

            context.drawText(tr, tr.trimToWidth(name, colWidth - 8),
                    cx + 4, bodyTop + 3, 0xFFFFFF, true);

            // 商品格：按该列滚动行偏移绘制整行（不画半行，避免溢出列体）
            int rowTop = bodyTop + 16;
            int fitRows = Math.max(0, (bodyBottom - rowTop) / 24);
            rowCounts[c] = items.size();
            int maxOffset = Math.max(0, items.size() - fitRows);
            rowOffsets[c] = Math.max(0, Math.min(maxOffset, rowOffsets[c]));
            int start = rowOffsets[c];
            for (int r = start; r < items.size() && r - start < fitRows; r++) {
                Row item = items.get(r);
                int ry = rowTop + (r - start) * 24;
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
            if (items.size() > start + fitRows) {
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
