package cn.woshiikun_1145.mcmod.choco.cstmm.client.screen;

import cn.woshiikun_1145.mcmod.choco.cstmm.client.cache.FaceCache;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.cache.LeaderboardCache;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.cache.LeaderboardCache.Row;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.network.ClientNetworkHandler;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.cache.ClientConfig;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.PlayerProfile;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.payload.MatchActionPayload;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.payload.MatchActionPayload.ActionType;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 【作用】主菜单"履历"标签页面板。上约 1/3 为"我的履历"（战绩 + 头像），下约 2/3 为"全服履历"
 * 排行列表（排名/玩家/击杀/死亡/K/D/场次/胜率，空间不足可滚动），右上角下拉式菜单选择排序方式
 * （K/D、胜率、总击杀，客户端本地排序）。自己的行在排行中用主题色底高亮。
 * 【被谁使用】仅 MatchMenuScreen 使用（drawContent 委托 draw、mouseScrolled 委托 scroll、
 * mouseClicked 委托下拉框交互）；切到本页时 onShow 请求自己的档案与全服排行。
 * 数据来源：自己的战绩 ← ClientNetworkHandler.PlayerProfileCache（REQUEST_PROFILE 回包）；
 * 全服排行 ← LeaderboardCache（REQUEST_LEADERBOARD 分片回包）。
 */
@Environment(EnvType.CLIENT)
public class ProfileTabPanel {

    private static final int BORDER = 0x44FFFFFF;
    private static final int BTN = 0xFF3A3A3A;
    private static final int BTN_HOVER = 0xFF4E4E4E;
    private static final int ROW_HEIGHT = 16;
    /** 排序下拉按钮/选项统一宽度 */
    private static final int DROPDOWN_W = 110;
    private static final int DROPDOWN_H = 16;

    private final MatchMenuScreen menu;

    /** 排序方式（默认 K/D）与下拉框展开状态 */
    private SortMode sortMode = SortMode.KD;
    private boolean dropdownOpen = false;

    /** 排行列表滚动偏移（像素）；contentHeight/listTop/listViewportH 由每帧 draw 回写供 scroll 与命中使用 */
    private int scrollOffset = 0;
    private int contentHeight = 0;
    private int listTop = 0;
    private int listViewportH = 0;

    /** 本帧绘制的下拉按钮区域（供鼠标命中；坐标系与渲染一致） */
    private int[] dropdownBtnRect = null;
    /** 本帧履历页内容区（x, y, w, h，供下拉框展开时"点外部仅收起不吞点击"判定） */
    private int[] panelRect = null;

    // 【作用】构造面板并持有宿主 Screen（借用其 font / getPlayerName 桥接方法）
    public ProfileTabPanel(MatchMenuScreen menu) {
        this.menu = menu;
    }

    /** 排序方式：按 K/D、胜率或总击杀降序（标签同时用作下拉框文案） */
    private enum SortMode {
        KD("K/D"), WIN_RATE("胜率"), KILLS("总击杀");

        final String label;

        SortMode(String label) { this.label = label; }

        Comparator<Row> comparator() {
            return switch (this) {
                case KD -> Comparator.comparingDouble(Row::kd).reversed();
                case WIN_RATE -> Comparator.<Row>comparingDouble(Row::winRate).reversed()
                        .thenComparing(Comparator.comparingInt(Row::kills).reversed());
                case KILLS -> Comparator.comparingInt(Row::kills).reversed();
            };
        }
    }

    /**
     * 【作用】切到履历页时请求自己的档案与全服排行（服务端立即回包/分片回包）。
     * 【被谁使用】MatchMenuScreen 侧边栏点击 case 4。
     */
    public void onShow() {
        ClientPlayNetworking.send(new MatchActionPayload(ActionType.REQUEST_PROFILE, "", 0, ""));
        ClientPlayNetworking.send(new MatchActionPayload(ActionType.REQUEST_LEADERBOARD, "", 0, ""));
    }

    /**
     * 【作用】滚动全服排行列表（以列表内容总高为上限，下拉框展开时同样生效）。
     * 【被谁使用】MatchMenuScreen#mouseScrolled（selectedTab == 4 时转发）。
     */
    public void scroll(double verticalAmount) {
        scrollOffset = Math.max(0, scrollOffset - (int) (verticalAmount * 20));
        int max = Math.max(0, contentHeight - listViewportH);
        scrollOffset = Math.min(scrollOffset, max);
    }

    /**
     * 【作用】履历页鼠标点击处理：排序下拉框的展开/选择/收起。
     * @return true 表示点击已被本面板消费（宿主不再向下分发）
     * 【被谁使用】MatchMenuScreen#mouseClicked（selectedTab == 4 时转发）。
     */
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int[] r = dropdownBtnRect;
        if (dropdownOpen) {
            dropdownOpen = false;
            if (r != null) {
                // 点在选项上：切换排序方式（选项列表位于按钮正下方）
                int optIndex = (int) ((mouseY - (r[1] + r[3] + 2)) / DROPDOWN_H);
                boolean inColumn = mouseX >= r[0] && mouseX <= r[0] + r[2]
                        && optIndex >= 0 && optIndex < SortMode.values().length
                        && mouseY >= r[1] + r[3] + 2 + optIndex * DROPDOWN_H
                        && mouseY < r[1] + r[3] + 2 + (optIndex + 1) * DROPDOWN_H;
                if (inColumn) {
                    sortMode = SortMode.values()[optIndex];
                    return true;
                }
            }
            // 点击下拉框外：仅收起；点在履历页内容区外不吞掉点击（侧边栏等仍可正常交互）
            int[] p = panelRect;
            if (p == null) return false;
            return mouseX >= p[0] && mouseX <= p[0] + p[2] && mouseY >= p[1] && mouseY <= p[1] + p[3];
        }
        if (r != null && mouseX >= r[0] && mouseX <= r[0] + r[2]
                && mouseY >= r[1] && mouseY <= r[1] + r[3]) {
            dropdownOpen = true;
            return true;
        }
        return false;
    }

    /**
     * 【作用】履历页主渲染：上约 1/3 画"我的履历"块（头像 + 双列战绩），下约 2/3 画"全服履历"
     * 排行（表头 + 可滚动行 + 右侧滚动条 + 右上角排序下拉框），行按所选排序方式本地排序。
     * 【被谁使用】MatchMenuScreen#drawContent（selectedTab == 4 时委托）。
     */
    public void draw(DrawContext context, int x, int y, int width, int height, int mouseX, int mouseY) {
        TextRenderer tr = menu.font();
        panelRect = new int[]{x, y, width, height};

        // 窄内容区（右列放不下）时战绩退化为单列堆叠，上块需更高
        int textX = FaceCache.hasOwn() ? x + 62 : x + 12;
        boolean twoCols = x + width / 2 + 20 > textX + 130;

        // ===== 上约 1/3：我的履历 =====
        int ownH = Math.min(190, Math.max(twoCols ? 96 : 128, height / 3));
        drawOwnBlock(context, tr, x, y, width, ownH, twoCols);

        // ===== 下约 2/3：全服履历 =====
        int lbY = y + ownH + 12;
        int lbH = Math.max(40, y + height - lbY);
        drawLeaderboard(context, tr, x, lbY, width, lbH, mouseX, mouseY);
    }

    // ===== 上块：我的履历 =====

    /**
     * 绘制"我的履历"块：标题 + 头像（无绑定不占位）+ 战绩。
     * 双列（twoCols）：左列 玩家/总击杀/总被击杀/K/D，右列 参赛场次/胜利场次/胜率；
     * 单列（窄内容区）：7 行战绩纵向堆叠（调用方保证块高 ≥128）。
     */
    private void drawOwnBlock(DrawContext context, TextRenderer tr, int x, int y, int width, int height,
                              boolean twoCols) {
        context.fill(x, y, x + width, y + height, 0x66101018);
        context.drawBorder(x, y, width, height, BORDER);
        context.drawText(tr, "我的履历", x + 12, y + 8, ClientConfig.getThemeColorArgb(), true);

        PlayerProfile profile = ClientNetworkHandler.PlayerProfileCache.getInstance().getProfile();
        if (profile == null) {
            context.drawText(tr, "§7加载中...", x + 12, y + 28, 0xAAAAAA, true);
            return;
        }

        // 头像（个性化绑定，客户端按绑定自行获取图片）；未设置时文本保持原位
        int textX = x + 12;
        if (FaceCache.hasOwn()) {
            QueueTabPanel.drawAvatar(context, x + 12, y + 26, 40, FaceCache.getOwnType(), FaceCache.getOwnId());
            textX = x + 62;
        }

        int colA = textX;
        int colB = twoCols ? x + width / 2 + 20 : colA;
        double winRate = profile.getTotalMatches() > 0
                ? (double) profile.getTotalWins() / profile.getTotalMatches() * 100 : 0;

        // 左列固定 4 行：玩家 / 总击杀 / 总被击杀 / K/D
        int rowY = y + 26;
        context.drawText(tr, "§7玩家: §f" + profile.getPlayerName(), colA, rowY, 0xFFFFFF, true);
        rowY += 16;
        context.drawText(tr, "§7总击杀: §c" + profile.getTotalKills(), colA, rowY, 0xFFFFFF, true);
        rowY += 16;
        context.drawText(tr, "§7总被击杀: §9" + profile.getTotalDeaths(), colA, rowY, 0xFFFFFF, true);
        rowY += 16;
        context.drawText(tr, "§7K/D: §e" + profile.getKDString(), colA, rowY, 0xFFFFFF, true);

        // 右列 3 行：参赛场次 / 胜利场次 / 胜率（双列与左列首行对齐；单列接在左列下方）
        rowY = twoCols ? y + 26 : rowY + 16;
        context.drawText(tr, "§7参赛场次: §a" + profile.getTotalMatches(), colB, rowY, 0xFFFFFF, true);
        rowY += 16;
        context.drawText(tr, "§7胜利场次: §6" + profile.getTotalWins(), colB, rowY, 0xFFFFFF, true);
        rowY += 16;
        context.drawText(tr, "§7胜率: §b" + String.format("%.1f", winRate) + "%", colB, rowY, 0xFFFFFF, true);
    }

    // ===== 下块：全服履历 =====

    /** 绘制"全服履历"排行块：标题行（含排序下拉框）+ 表头 + 可滚动行列表 + 滚动条 */
    private void drawLeaderboard(DrawContext context, TextRenderer tr, int x, int y, int width, int height,
                                 int mouseX, int mouseY) {
        context.fill(x, y, x + width, y + height, 0x66101018);
        context.drawBorder(x, y, width, height, BORDER);
        context.drawText(tr, "全服履历", x + 12, y + 8, ClientConfig.getThemeColorArgb(), true);

        // ===== 排序下拉框（右上角，渲染推迟到行列表之后保证选项浮于其上）=====
        int btnX = x + width - DROPDOWN_W - 8;
        int btnY = y + 6;
        dropdownBtnRect = new int[]{btnX, btnY, DROPDOWN_W, DROPDOWN_H};

        // ===== 表头 =====
        int innerL = x + 10;
        int innerR = x + width - 12;
        Cols c = new Cols(innerL, innerR);
        int headY = y + 26;
        context.drawText(tr, "§7#", innerL, headY, 0xFFFFFF, true);
        context.drawText(tr, "§7玩家", c.nameStart, headY, 0xFFFFFF, true);
        context.drawText(tr, "§7击杀", c.kilX - tr.getWidth("击杀"), headY, 0xFFFFFF, true);
        context.drawText(tr, "§7死亡", c.dthX - tr.getWidth("死亡"), headY, 0xFFFFFF, true);
        context.drawText(tr, "§7K/D", c.kdX - tr.getWidth("K/D"), headY, 0xFFFFFF, true);
        context.drawText(tr, "§7场次", c.matX - tr.getWidth("场次"), headY, 0xFFFFFF, true);
        context.drawText(tr, "§7胜率", c.winX - tr.getWidth("胜率"), headY, 0xFFFFFF, true);
        context.fill(innerL, headY + 12, innerR, headY + 13, BORDER);

        // ===== 行列表（滚动 + 本地排序 + 自己高亮）=====
        listTop = headY + 16;
        listViewportH = Math.max(0, y + height - 4 - listTop);

        List<Row> rows = LeaderboardCache.getInstance().get();
        if (rows == null) {
            contentHeight = 0;
            context.drawText(tr, "§7加载中...", x + 12, listTop + 4, 0xAAAAAA, true);
            return;
        }
        if (rows.isEmpty()) {
            contentHeight = 0;
            context.drawText(tr, "§7暂无全服履历数据", x + 12, listTop + 4, 0xAAAAAA, true);
            return;
        }

        List<Row> sorted = new ArrayList<>(rows);
        sorted.sort(sortMode.comparator());
        contentHeight = sorted.size() * ROW_HEIGHT;
        scrollOffset = Math.min(scrollOffset, Math.max(0, contentHeight - listViewportH));

        String myName = menu.getPlayerName();
        context.enableScissor(x + 2, listTop, x + width - 2, listTop + listViewportH);
        for (int i = 0; i < sorted.size(); i++) {
            int rowY = listTop + i * ROW_HEIGHT - scrollOffset;
            if (rowY + ROW_HEIGHT < listTop || rowY > listTop + listViewportH) continue;
            Row row = sorted.get(i);
            boolean mine = row.name().equals(myName);
            boolean hover = mouseX >= x + 2 && mouseX <= x + width - 2
                    && mouseY >= Math.max(listTop, rowY) && mouseY < rowY + ROW_HEIGHT;

            if (mine) {
                // 自己的行：主题色半透明底（常显）
                int accent = ClientConfig.getThemeColorArgb();
                context.fill(x + 2, rowY, x + width - 2, rowY + ROW_HEIGHT, (0x30 << 24) | (accent & 0xFFFFFF));
            } else if (hover) {
                context.fill(x + 2, rowY, x + width - 2, rowY + ROW_HEIGHT, 0x22FFFFFF);
            }

            // 名次配色：#1 金 / #2 银 / #3 铜，其余灰
            String rank = switch (i) {
                case 0 -> "§6#1";
                case 1 -> "§f#2";
                case 2 -> "§e#3";
                default -> "§7#" + (i + 1);
            };
            context.drawText(tr, rank, innerL, rowY + 4, 0xFFFFFF, true);
            String nameText = (mine ? "§a" : "§f") + row.name();
            context.drawText(tr, QueueTabPanel.truncate(tr, nameText, c.nameEnd - c.nameStart),
                    c.nameStart, rowY + 4, 0xFFFFFF, true);
            context.drawText(tr, "§f" + row.kills(), c.kilX - tr.getWidth(String.valueOf(row.kills())),
                    rowY + 4, 0xFFFFFF, true);
            context.drawText(tr, "§f" + row.deaths(), c.dthX - tr.getWidth(String.valueOf(row.deaths())),
                    rowY + 4, 0xFFFFFF, true);
            context.drawText(tr, "§e" + String.format("%.2f", row.kd()),
                    c.kdX - tr.getWidth(String.format("%.2f", row.kd())), rowY + 4, 0xFFFFFF, true);
            context.drawText(tr, "§f" + row.matches(), c.matX - tr.getWidth(String.valueOf(row.matches())),
                    rowY + 4, 0xFFFFFF, true);
            context.drawText(tr, "§b" + String.format("%.1f", row.winRate()) + "%",
                    c.winX - tr.getWidth(String.format("%.1f", row.winRate()) + "%"),
                    rowY + 4, 0xFFFFFF, true);
        }
        context.disableScissor();

        // ===== 滚动条（内容超出可视区时）=====
        if (contentHeight > listViewportH && listViewportH > 0) {
            int trackX = x + width - 4;
            float ratio = (float) listViewportH / contentHeight;
            int thumbH = Math.max(12, (int) (listViewportH * ratio));
            int thumbY = listTop + (int) ((listViewportH - thumbH)
                    * (scrollOffset / (float) (contentHeight - listViewportH)));
            context.fill(trackX, listTop, trackX + 2, listTop + listViewportH, 0x22FFFFFF);
            context.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, 0x66FFFFFF);
        }

        // ===== 排序下拉框（最后绘制，保证按钮/选项浮于行列表之上）=====
        boolean btnHover = mouseX >= btnX && mouseX <= btnX + DROPDOWN_W
                && mouseY >= btnY && mouseY <= btnY + DROPDOWN_H;
        context.fill(btnX, btnY, btnX + DROPDOWN_W, btnY + DROPDOWN_H,
                btnHover || dropdownOpen ? BTN_HOVER : BTN);
        context.drawBorder(btnX, btnY, DROPDOWN_W, DROPDOWN_H, BORDER);
        context.drawText(tr, "排序: " + sortMode.label + (dropdownOpen ? " ▴" : " ▾"),
                btnX + 8, btnY + 4, 0xFFFFFF, true);
        if (dropdownOpen) {
            SortMode[] modes = SortMode.values();
            for (int i = 0; i < modes.length; i++) {
                int oy = btnY + DROPDOWN_H + 2 + i * DROPDOWN_H;
                boolean optHover = mouseX >= btnX && mouseX <= btnX + DROPDOWN_W
                        && mouseY >= oy && mouseY < oy + DROPDOWN_H;
                context.fill(btnX, oy, btnX + DROPDOWN_W, oy + DROPDOWN_H,
                        optHover ? BTN_HOVER : 0xEE212121);
                boolean selected = modes[i] == sortMode;
                context.drawText(tr, (selected ? "§a✔ " : "   ") + modes[i].label,
                        btnX + 8, oy + 4, 0xFFFFFF, true);
            }
        }
    }

    /** 排行列右对齐锚点：由内容区右缘向左依次为 胜率/场次/K/D/死亡/击杀，玩家名占剩余宽度 */
    private static final class Cols {
        final int winX;
        final int matX;
        final int kdX;
        final int dthX;
        final int kilX;
        final int nameStart;
        final int nameEnd;

        Cols(int innerL, int innerR) {
            this.winX = innerR;
            this.matX = winX - 60;
            this.kdX = matX - 58;
            this.dthX = kdX - 62;
            this.kilX = dthX - 60;
            this.nameStart = innerL + 34;
            this.nameEnd = Math.max(nameStart + 40, kilX - 66);
        }
    }
}
