package cn.woshiikun_1145.mcmod.choco.cstmm.client.screen;

import cn.woshiikun_1145.mcmod.choco.cstmm.client.cache.ShopDataCache;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.util.Base64ImageDecoder;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.util.UrlImageCache;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.config.GlobalConfig;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.payload.MatchActionPayload;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.payload.ShopDataS2CPayload;
import cn.woshiikun_1145.mcmod.choco.cstmm.util.ItemStrings;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * 【作用】商店界面（一页式网格布局，参考 CS 购买页）：普通图与爆破图统一 5 列网格——
 *         爆破图第 1 列固定"装备"列（仅 CT 显示拆弹装备购买项，T 方空列），第 2~5 列为
 *         配置的分类列；普通图 5 列全部为配置的分类列（免费发放）。列内商品超出列体时
 *         鼠标滚轮逐列上下滚动。商品格显示图标（base64/URL/空=物品栏贴图）、名称与价格；
 *         爆破图顶部信息栏显示余额与购买窗口状态，余额不足或窗口关闭的商品置灰。
 * 【被谁使用】CstmmClient（keyOpenShop 快捷键打开）；ClientNetworkHandler 收到商店数据包后调 refresh()。
 */
@Environment(EnvType.CLIENT)
public class ShopScreen extends Screen {

    // 列布局常量（普通/爆破统一 5 列网格）
    private static final int SHOP_COLS = 5;
    private static final int COL_GAP = 6;
    private static final int CELL_H = 30;
    private static final int HEADER_H = 16;
    private static final int GRID_TOP = 58;

    // 分类列滚动偏移（像素，逐列独立）
    private final int[] colOffsets = new int[SHOP_COLS];
    // 每列商品格数据（init 从缓存构建；render 按滚动偏移绘位、mouseClicked 按同几何命中）
    private final List<List<Cell>> columns = new ArrayList<>();
    // 每列几何缓存（init 计算）
    private int colWidth = 0;
    private int colBodyTop = 0;
    private int colBodyBottom = 0;
    // 列头数据（名称 + 图标），与 columns 一一对应
    private final List<String[]> headers = new ArrayList<>();

    /**
     * 【作用】商品格数据：购买索引（爆破图第 1 列拆弹装备 = -1，其余为分类列拼接索引）、
     *         名称、价格文案、图标串（base64/URL，空 = 物品栏贴图 stack）、物品栈与可购状态。
     */
    private record Cell(int buyIndex, String name, String priceText, String icon, ItemStack stack, boolean affordable) {}

    // 【作用】构造即向服务端请求商店数据（C2S REQUEST_SHOP），显示内容等回包后再刷新
    public ShopScreen() {
        super(Text.literal("商店"));
        ClientPlayNetworking.send(new MatchActionPayload(
                MatchActionPayload.ActionType.REQUEST_SHOP, "", 0, ""));
    }

    @Override
    protected void init() {
        super.init();

        ShopDataCache cache = ShopDataCache.getInstance();

        // 返回按钮
        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("返回"),
                button -> close()
        ).dimensions(this.width - 80, 10, 60, 20).build());

        boolean canShow = cache.hasData() && cache.isEligible() && !cache.getColumns().isEmpty();
        if (canShow) {
            initGrid(cache);
        } else {
            columns.clear();
            headers.clear();
        }
    }

    /** 构建网格数据：列头（名称/图标）与每列商品格（爆破图第 1 列 = 拆弹装备购买项）。 */
    private void initGrid(ShopDataCache cache) {
        columns.clear();
        headers.clear();
        colWidth = (this.width - 40 - (SHOP_COLS - 1) * COL_GAP) / SHOP_COLS;
        colBodyTop = GRID_TOP + HEADER_H + 2;
        colBodyBottom = this.height - 45;

        List<ShopDataS2CPayload.ColumnData> serverCols = cache.getColumns();
        boolean bomb = cache.isBombMode();

        for (int c = 0; c < SHOP_COLS; c++) {
            List<Cell> cells = new ArrayList<>();
            String headerName = "";
            String headerIcon = "";

            if (bomb && c == 0) {
                // 第 1 列：装备列（仅 CT 显示拆弹装备购买项；T 方空列）
                headerName = "装备";
                if (cache.hasDefuser()) {
                    int price = cache.getDefuserPrice();
                    boolean affordable = cache.isBuyWindowOpen() && cache.getMoney() >= price;
                    cells.add(new Cell(-1, "拆弹装备", "$" + price, "", ItemStack.EMPTY, affordable));
                }
            } else {
                // 服务端下发的列：爆破图 [0]=装备列（空商品），1..4=分类列；普通图 0..4=分类列。
                // 两种模式分类列下标都与网格列号一致（爆破图第 1 列在上分支单独处理）
                int idx = c;
                if (idx < serverCols.size()) {
                    ShopDataS2CPayload.ColumnData col = serverCols.get(idx);
                    headerName = col.name();
                    headerIcon = col.icon();
                    int flatBase = flatIndexBase(serverCols, idx);
                    for (int r = 0; r < col.items().size(); r++) {
                        GlobalConfig.ShopItem item = col.items().get(r);
                        int buyIndex = flatBase + r;
                        String name = item.getDisplayName();
                        if (name.isEmpty()) {
                            ItemStack stack = stackOf(item.getItemId());
                            name = stack.isEmpty()
                                    ? (item.getItemId() == null ? "" : item.getItemId())
                                    : stack.getName().getString();
                        }
                        name = textRenderer.trimToWidth(name, colWidth - 46);
                        boolean affordable = !bomb
                                || (cache.isBuyWindowOpen() && cache.getMoney() >= Math.max(0, item.getPrice()));
                        String priceText = bomb || item.getPrice() > 0
                                ? "$" + Math.max(0, item.getPrice()) : "免费";
                        cells.add(new Cell(buyIndex, name, priceText, item.getIcon(),
                                stackOf(item.getItemId()), affordable));
                    }
                }
            }
            headers.add(new String[]{headerName, headerIcon});
            columns.add(cells);
        }

        // 数据变化后钳制各列滚动偏移，避免停留在超出内容高度的位置
        for (int c = 0; c < SHOP_COLS; c++) {
            int content = columns.get(c).size() * CELL_H;
            int maxOffset = Math.max(0, content - (colBodyBottom - colBodyTop));
            colOffsets[c] = Math.min(Math.max(0, colOffsets[c]), maxOffset);
        }
    }

    /**
     * 【作用】计算某分类列首商品的平铺购买索引（与客户端渲染列顺序、服务端平铺列表一致）：
     *         逐列累加前列商品数。爆破图 columns[0] 为空"装备列"（不占索引，拆弹装备走
     *         index=-1），普通图从 columns[0] 起——空装备列贡献 0，两种模式统一按此计算。
     */
    private int flatIndexBase(List<ShopDataS2CPayload.ColumnData> serverCols, int colIdx) {
        int base = 0;
        for (int i = 0; i < colIdx; i++) {
            base += serverCols.get(i).items().size();
        }
        return base;
    }

    /** 鼠标所在列（购买区内返回 0..4，否则 -1）。 */
    private int columnAt(double mx, double my) {
        if (my < colBodyTop || my > colBodyBottom) return -1;
        for (int c = 0; c < SHOP_COLS; c++) {
            int x = 20 + c * (colWidth + COL_GAP);
            if (mx >= x && mx < x + colWidth) return c;
        }
        return -1;
    }

    /**
     * 【作用】购买物品：爆破图 index=-1 = 拆弹装备（第 1 列），其余为分类列拼接索引；
     *         普通图为分类列拼接索引（免费发放）。条件校验、扣费与限购全部在服务端；
     *         购买成功后服务端重发数据刷新余额/状态。
     */
    private void buyItem(int index) {
        ClientPlayNetworking.send(new MatchActionPayload(
                MatchActionPayload.ActionType.BUY_ITEM, String.valueOf(index), 0, ""));
        if (MinecraftClient.getInstance().player != null) {
            MinecraftClient.getInstance().player.sendMessage(Text.literal("§a购买请求已发送！"), false);
        }
    }

    /**
     * 收到服务端商店数据后重载界面（由 ClientNetworkHandler 调用）。
     * 状态同步为订阅制：服务端在购买窗口翻转、结算/奖励改钱、购买成功时主动推送，
     * 客户端仅在打开时发一次 REQUEST_SHOP，不再定时轮询。
     */
    public void refresh() {
        clearChildren();
        init();
    }

    /** 鼠标滚轮：购买区内按所在列滚动商品列表（每列独立）。 */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double hAmount, double vAmount) {
        ShopDataCache cache = ShopDataCache.getInstance();
        if (cache.hasData() && cache.isEligible()) {
            int col = columnAt(mouseX, mouseY);
            if (col >= 0 && columns.size() > col) {
                int content = columns.get(col).size() * CELL_H;
                int maxOffset = Math.max(0, content - (colBodyBottom - colBodyTop));
                colOffsets[col] = Math.max(0, Math.min(maxOffset,
                        colOffsets[col] - (int) Math.round(vAmount * CELL_H)));
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, hAmount, vAmount);
    }

    /** 鼠标点击：购买区内按商品格几何命中（y 含滚动偏移，仅未滚出裁剪区的格可点）。 */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        ShopDataCache cache = ShopDataCache.getInstance();
        if (cache.hasData() && cache.isEligible() && button == 0) {
            int col = columnAt(mouseX, mouseY);
            if (col >= 0 && columns.size() > col) {
                int offset = Math.max(0, colOffsets[col]);
                List<Cell> cells = columns.get(col);
                for (int r = 0; r < cells.size(); r++) {
                    int y = colBodyTop - offset + r * CELL_H;
                    int x = 20 + col * (colWidth + COL_GAP);
                    if (mouseX >= x + 1 && mouseX < x + colWidth - 1
                            && mouseY >= y && mouseY < y + CELL_H - 3) {
                        // 不可购（窗口关闭/余额不足）的格子不发购买请求，提示由服务端校验兜底
                        if (cells.get(r).affordable()) {
                            buyItem(cells.get(r).buyIndex());
                        }
                        return true;
                    }
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /**
     * 【作用】主渲染：顶部标题与（爆破图）余额/购买窗口信息栏；网格区逐列绘制列头
     *         （编号 + 图标 + 名称）与 scissor 裁剪的商品格（图标/名称/价格，置灰不可购）；
     *         无数据/不可购/未配置时显示状态提示。
     */
    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);

        ShopDataCache cache = ShopDataCache.getInstance();
        // 页面标题跟随主题色（个性化页设置）
        context.drawText(textRenderer, "━━━ 物品商店 ━━━", this.width / 2 - 90, 12,
                cn.woshiikun_1145.mcmod.choco.cstmm.client.cache.ClientConfig.getThemeColorArgb(), true);

        int statusY = 45;
        if (cache.hasData() && cache.isEligible() && !cache.getColumns().isEmpty()) {
            if (cache.isBombMode()) {
                // 信息栏：余额 + 购买窗口状态
                context.drawText(textRenderer, "§e余额: $" + cache.getMoney(), 20, 32, 0xFFFF55, true);
                context.drawText(textRenderer,
                        cache.isBuyWindowOpen() ? "§a购买窗口开启" : "§c购买窗口已关闭",
                        120, 32, 0xAAAAAA, true);
            }
            renderGrid(context, mouseX, mouseY, cache);
        } else if (!cache.hasData()) {
            context.drawText(textRenderer, "§7正在获取商品...", 20, statusY, 0xAAAAAA, true);
        } else if (!cache.isEligible()) {
            context.drawText(textRenderer, cache.isBombMode()
                    ? "§e仅爆破图对局中可购买" : "§e当前不在竞技模式对局中，无法购买", 20, statusY, 0xFFFF55, true);
        } else {
            context.drawText(textRenderer, "§e当前地图未配置商店商品（配置界面 → 商店分类列）", 20, statusY, 0xFFFF55, true);
        }
    }

    /** 网格渲染：每列列背景 + 列头（编号/图标/名称）+ scissor 裁剪的商品格。 */
    private void renderGrid(DrawContext context, int mouseX, int mouseY, ShopDataCache cache) {
        boolean bomb = cache.isBombMode();
        for (int c = 0; c < SHOP_COLS; c++) {
            int x = 20 + c * (colWidth + COL_GAP);

            // 列背景
            context.fill(x, GRID_TOP, x + colWidth, colBodyBottom, 0x66000000);

            // 列头：编号（金色）+ 图标 + 名称
            String[] header = c < headers.size() ? headers.get(c) : new String[]{"", ""};
            String headerName = header[0];
            String headerIcon = header[1];
            context.drawText(textRenderer, String.valueOf(c + 1), x + 3, GRID_TOP + 4, 0xFFD700, true);
            int nameX = x + 12;
            if (!headerIcon.isEmpty()) {
                var tex = headerIcon.startsWith("http://") || headerIcon.startsWith("https://")
                        ? UrlImageCache.get(headerIcon)
                        : Base64ImageDecoder.decode(headerIcon);
                if (tex != null) {
                    context.drawTexture(tex.id(), x + 13, GRID_TOP + 2, 12, 12,
                            0f, 0f, tex.width(), tex.height(), tex.width(), tex.height());
                }
                nameX = x + 27;
            }
            context.drawText(textRenderer, textRenderer.trimToWidth(headerName, x + colWidth - nameX - 4),
                    nameX, GRID_TOP + 4, 0xFFFFFF, true);

            // 商品格：scissor 裁剪列体后按滚动偏移绘制
            if (columns.size() > c) {
                int offset = Math.max(0, colOffsets[c]);
                List<Cell> cells = columns.get(c);
                context.enableScissor(x, colBodyTop, x + colWidth, colBodyBottom);
                for (int r = 0; r < cells.size(); r++) {
                    int y = colBodyTop - offset + r * CELL_H;
                    renderCell(context, x, y, colWidth, cells.get(r), mouseX, mouseY);
                }
                context.disableScissor();
            }
        }
    }

    /** 单个商品格：底色 + 悬停高亮 + 图标（物品贴图/解码图）+ 名称 + 价格（不可购置灰）。 */
    private void renderCell(DrawContext context, int x, int y, int w, Cell cell, int mouseX, int mouseY) {
        boolean hovered = mouseX >= x + 1 && mouseX < x + w - 1
                && mouseY >= y && mouseY < y + CELL_H - 3;
        // 底色：可购=深灰，不可购=更暗；悬停提亮
        int bg = cell.affordable() ? (hovered ? 0x50FFFFFF : 0x30FFFFFF) : (hovered ? 0x28FFFFFF : 0x18FFFFFF);
        context.fill(x + 1, y, x + w - 1, y + CELL_H - 3, bg);

        // 图标：icon 串非空走 base64/URL 解码，否则画物品栏贴图
        int textX;
        if (!cell.icon().isEmpty()) {
            var tex = cell.icon().startsWith("http://") || cell.icon().startsWith("https://")
                    ? UrlImageCache.get(cell.icon())
                    : Base64ImageDecoder.decode(cell.icon());
            if (tex != null) {
                context.drawTexture(tex.id(), x + 5, y + 6, 16, 16,
                        0f, 0f, tex.width(), tex.height(), tex.width(), tex.height());
            }
            textX = x + 24;
        } else if (!cell.stack().isEmpty()) {
            context.drawItem(cell.stack(), x + 4, y + 5);
            textX = x + 24;
        } else {
            textX = x + 6;
        }

        // 名称（第 1 行）+ 价格（第 2 行）；不可购时整格灰字
        int nameColor = cell.affordable() ? 0xFFFFFF : 0x888888;
        int priceColor = cell.affordable() ? 0xFFFF55 : 0x666666;
        context.drawText(textRenderer, textRenderer.trimToWidth(cell.name(), x + w - textX - 4),
                textX, y + 3, nameColor, true);
        context.drawText(textRenderer, cell.priceText(), textX, y + 16, priceColor, true);

        // 不可购时叠加半透明遮罩（视觉置灰）
        if (!cell.affordable()) {
            context.fill(x + 1, y, x + w - 1, y + CELL_H - 3, 0x55000000);
        }
    }

    /**
     * 物品串 → ItemStack（/give 格式等，统一走 ItemStrings；仅用于预览贴图与显示名，
     * 解析失败返回空栈）。无世界时跳过组件仅显示基础物品。
     */
    private ItemStack stackOf(String raw) {
        if (raw == null || raw.isEmpty()) return ItemStack.EMPTY;
        MinecraftClient client = MinecraftClient.getInstance();
        var lookup = client.world != null ? client.world.getRegistryManager() : null;
        return ItemStrings.toStack(raw, lookup);
    }

    // 不暂停游戏：服务器界面惯例（单人打开时世界继续运行）
    @Override
    public boolean shouldPause() {
        return false;
    }
}
