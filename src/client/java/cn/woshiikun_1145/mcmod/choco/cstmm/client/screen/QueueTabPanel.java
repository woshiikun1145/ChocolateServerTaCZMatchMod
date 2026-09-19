package cn.woshiikun_1145.mcmod.choco.cstmm.client.screen;

import cn.woshiikun_1145.mcmod.choco.cstmm.client.cache.ConfigDataCache;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.cache.QueueStatusCache;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.cache.QueueStatusCache.Snapshot;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.util.Base64ImageDecoder;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.config.MapConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.util.List;

/**
 * 主菜单"队列"标签页面板（只读展示，无交互按钮）。
 * 三类行：
 * 1. 自己的匹配状态：玩家名 + 战队徽标（黄框）/ 战队名 缩写；当前正在匹配的地图与模式；
 *    已匹配到/还需要人数；右侧地图图片（从左到右不透明度渐变：左 0% → 右 100%）。
 * 2. 自己战队的匹配状态：战队名/缩写/正在匹配人数 + 每个在队列成员的 map | player 列表。
 * 3. 每张地图的状态行：红字（对局中/被禁用/冷却中）或绿字（可用 + 蓝红队人数 + 正在匹配的玩家）。
 * 整页可滚动；地图显示名/类型/图片按 id 从 ConfigDataCache 解析。
 */
@Environment(EnvType.CLIENT)
public class QueueTabPanel {

    private static final int CELL_BORDER = 0x44FFFFFF;
    private static final int IMAGE_CELL_WIDTH = 380;
    private static final int IMAGE_STRIP_COUNT = 24;
    private static final int MAX_PLAYERS_LINES = 6;

    private final MatchMenuScreen menu;
    private int scrollOffset = 0;
    private int contentHeight = 0;

    /** 地图行分模式展示：每 3 秒在竞技/休闲之间轮换（全局同步切换） */
    private boolean showingCasual = false;
    private long lastFlipMs = 0;

    public QueueTabPanel(MatchMenuScreen menu) {
        this.menu = menu;
    }

    /** 距上次轮换满 3 秒则切换展示模式 */
    private void tickModeRotation() {
        long now = System.currentTimeMillis();
        if (now - lastFlipMs >= 3000) {
            lastFlipMs = now;
            showingCasual = !showingCasual;
        }
    }

    private String currentModeLabel() {
        return showingCasual ? "休闲" : "竞技";
    }

    /** 取地图当前轮换模式的队列数据 */
    private Snapshot.ModeRow currentModeData(Snapshot.MapRow map) {
        String label = currentModeLabel();
        for (Snapshot.ModeRow mr : map.modes) {
            if (label.equals(mr.mode)) return mr;
        }
        return null;
    }

    public void scroll(double verticalAmount) {
        scrollOffset = Math.max(0, scrollOffset - (int) (verticalAmount * 20));
        scrollOffset = Math.min(scrollOffset, Math.max(0, contentHeight));
    }

    public void draw(DrawContext context, int x, int y, int width, int height, int mouseX, int mouseY) {
        TextRenderer tr = menu.font();
        tickModeRotation();
        Snapshot snap = QueueStatusCache.getInstance().get();

        int rowGap = 12;
        int rowY = y - scrollOffset;
        int inner = x + 10;
        int innerW = width - 20;

        context.enableScissor(x, y, x + width, y + height);

        // ===== 行1：自己的匹配状态 =====
        int ownH = 84;
        drawOwnRow(context, tr, snap.own, inner, rowY, innerW, ownH);
        rowY += ownH + rowGap;

        // ===== 行2：自己战队的匹配状态 =====
        int clanLines = snap.clan.inClan ? snap.clan.members.size() : 0;
        int clanH = Math.max(70, 34 + Math.min(clanLines, MAX_PLAYERS_LINES) * 13 + (clanLines > MAX_PLAYERS_LINES ? 12 : 0));
        drawClanRow(context, tr, snap.clan, inner, rowY, innerW, clanH);
        rowY += clanH + rowGap;

        // ===== 地图状态行 =====
        for (Snapshot.MapRow map : snap.maps) {
            Snapshot.ModeRow modeDataForHeight = currentModeData(map);
            int lines = Math.min((modeDataForHeight != null ? modeDataForHeight.players.size() : 0), MAX_PLAYERS_LINES);
            int h = Math.max(84, 40 + lines * 12 + ((modeDataForHeight != null ? modeDataForHeight.players.size() : 0) > MAX_PLAYERS_LINES ? 12 : 0));
            drawMapRow(context, tr, map, inner, rowY, innerW, h);
            rowY += h + rowGap;
        }
        contentHeight = (rowY - y) + 20;
        context.disableScissor();

        if (snap.maps.isEmpty()) {
            context.drawText(tr, "§7暂无地图数据（配置同步中或服务器未配置地图）", x + 20, y + 4, 0xAAAAAA, true);
        }
    }

    // ===== 行1：自己的匹配状态 =====

    private void drawOwnRow(DrawContext context, TextRenderer tr, Snapshot.Own own,
                            int x, int y, int width, int height) {
        drawRowFrame(context, x, y, width, height);
        int cellA = 250;
        int imgW = Math.min(IMAGE_CELL_WIDTH, width - cellA - 20);
        int imgX = x + width - imgW - 8;

        // 竖直分隔线
        context.fill(x + cellA, y + 4, x + cellA + 1, y + height - 4, CELL_BORDER);
        if (width - imgW - 24 > cellA) {
            context.fill(imgX - 6, y + 4, imgX - 5, y + height - 4, CELL_BORDER);
        }

        // 玩家名 + 战队徽标（黄框）
        String playerName = menu.getPlayerName();
        context.drawText(tr, playerName, x + 12, y + 12, 0xFFFFFF, true);
        int badgeX = x + 12 + tr.getWidth(playerName) + 8;
        drawBadge(context, badgeX, y + 8, 18);
        // 战队名 缩写
        var mineClan = cn.woshiikun_1145.mcmod.choco.cstmm.client.cache.ClanCache.getInstance().getMine();
        if (mineClan.inClan && mineClan.clan != null) {
            context.drawText(tr, "§6" + mineClan.clan.name + " §7[§e" + mineClan.clan.abbr + "§7]", x + 12, y + 34, 0xFFFFFF, true);
        } else {
            context.drawText(tr, "§7未加入战队", x + 12, y + 34, 0xAAAAAA, true);
        }

        // 中列：匹配状态
        int midX = x + cellA + 12;
        if (own.inQueue) {
            context.drawText(tr, "§f当前正在匹配 §6" + own.map + " §f" + orDefault(own.mode, ""), midX, y + 14, 0xFFFFFF, true);
            String progress;
            if (own.needed < 0) {
                progress = "§7已匹配到: §f" + own.matched + "位玩家   §7等待系统自动分配地图";
            } else {
                progress = "§7已匹配到: §f" + own.matched + "位玩家   §7还需要 §f" + own.needed + "位玩家";
            }
            context.drawText(tr, progress, midX, y + 40, 0xFFFFFF, true);
        } else {
            context.drawText(tr, "§7当前未在匹配", midX, y + 14, 0xAAAAAA, true);
            context.drawText(tr, "§7前往「匹配」页加入队列", midX, y + 40, 0x666666, true);
        }

        // 右列：地图图片 + 渐变
        if (own.inQueue) {
            drawGradientImage(context, own.mapId, imgX, y + 6, imgW, height - 12);
        }
    }

    // ===== 行2：自己战队的匹配状态 =====

    private void drawClanRow(DrawContext context, TextRenderer tr, Snapshot.ClanRow clan,
                             int x, int y, int width, int height) {
        drawRowFrame(context, x, y, width, height);
        int cellA = 250;
        context.fill(x + cellA, y + 4, x + cellA + 1, y + height - 4, CELL_BORDER);

        if (clan.inClan) {
            context.drawText(tr, "§6" + clan.name, x + 12, y + 10, 0xFFFFFF, true);
            context.drawText(tr, "§7[§e" + clan.abbr + "§7]", x + 12, y + 24, 0xAAAAAA, true);
            context.drawText(tr, "§f正在匹配 §e" + clan.count + "人", x + 12, y + height - 20, 0xFFFFFF, true);

            int lineX = x + cellA + 12;
            int lineY = y + 10;
            if (clan.members.isEmpty()) {
                context.drawText(tr, "§7本战队暂无成员在匹配队列中", lineX, lineY, 0xAAAAAA, true);
            } else {
                int shown = 0;
                for (Snapshot.ClanRow.MemberRow m : clan.members) {
                    if (shown >= MAX_PLAYERS_LINES) {
                        context.drawText(tr, "§7…… 共 " + clan.members.size() + " 名成员在队列中", lineX, lineY, 0x666666, true);
                        break;
                    }
                    context.drawText(tr, "§6" + m.map + " §7| §f" + m.player, lineX, lineY, 0xFFFFFF, true);
                    lineY += 13;
                    shown++;
                }
            }
        } else {
            context.drawText(tr, "§7你还没有加入战队", x + 12, y + 14, 0xAAAAAA, true);
            context.drawText(tr, "§7在「战队」页可创建或加入战队，匹配时将优先与战队成员同队", x + 12, y + 32, 0x666666, true);
        }
    }

    // ===== 地图状态行 =====

    private void drawMapRow(DrawContext context, TextRenderer tr, Snapshot.MapRow map,
                            int x, int y, int width, int height) {
        drawRowFrame(context, x, y, width, height);

        MapConfig cfg = findMap(map.id);
        int cellA = 200;
        int imgW = Math.min(IMAGE_CELL_WIDTH, width - cellA - 20);
        int imgX = x + width - imgW - 8;
        context.fill(x + cellA, y + 4, x + cellA + 1, y + height - 4, CELL_BORDER);

        // 左列：地图名 + ID + 结算方式；右下角标识当前展示的模式（每 3 秒竞技/休闲轮换）
        context.drawText(tr, cfg != null ? "§f" + cfg.getDisplayName() : "§f" + map.id, x + 12, y + 12, 0xFFFFFF, true);
        String idText = "ID:" + map.id;
        context.drawText(tr, "§7" + idText, x + cellA - 10 - tr.getWidth(idText), y + 14, 0xAAAAAA, true);
        context.drawText(tr, "§e" + (cfg != null && cfg.getWinCondition() == MapConfig.WinCondition.KILLS ? "按击杀" : "按计时"),
                x + 12, y + height - 22, 0xFFFFFF, true);
        String modeTag = "§7[§e" + currentModeLabel() + "§7]";
        context.drawText(tr, modeTag, x + cellA - 10 - tr.getWidth(modeTag), y + height - 22, 0xFFFFFF, true);

        // 当前轮换到的模式数据
        Snapshot.ModeRow modeData = currentModeData(map);
        int red = modeData != null ? modeData.red : 0;
        int blue = modeData != null ? modeData.blue : 0;
        List<String> players = modeData != null ? modeData.players : List.of();

        // 中列：状态文本
        int midX = x + cellA + 12;
        int midW = imgX - 12 - midX;
        switch (map.status) {
            case "DISABLED" -> {
                context.drawText(tr, "§c此地图已被管理员禁用!", midX, y + height / 2 - 5, 0xFFFF55, true);
            }
            case "IN_MATCH" -> {
                context.drawText(tr, "§c此地图目前不可匹配，因为此地图正在进行一场对局!", midX, y + height / 2 - 5, 0xFFFF55, true);
            }
            case "COOLDOWN" -> {
                context.drawText(tr, "§c此地图正在冷却中，暂时无法匹配!", midX, y + height / 2 - 5, 0xFFFF55, true);
            }
            default -> {
                context.drawText(tr, "§a此地图目前可用", midX, y + 10, 0x55FF55, true);
                context.drawText(tr, "§7正在匹配：", midX, y + 28, 0xAAAAAA, true);
                context.drawText(tr, "§9蓝队: §f" + blue + "人", midX + 60, y + 28, 0xFFFFFF, true);
                context.drawText(tr, "§c红队: §f" + red + "人", midX + 160, y + 28, 0xFFFFFF, true);
                int lineY = y + 44;
                context.drawText(tr, "§7当前正在匹配的玩家:", midX, lineY, 0xAAAAAA, true);
                lineY += 12;
                if (players.isEmpty()) {
                    context.drawText(tr, "§8（暂无）", midX, lineY, 0x666666, true);
                } else {
                    int shown = 0;
                    StringBuilder line = new StringBuilder();
                    for (String name : players) {
                        String piece = (line.length() == 0 ? "" : "  ") + name;
                        if (tr.getWidth(line.toString() + piece) > midW - 8 && line.length() > 0) {
                            context.drawText(tr, line.toString(), midX, lineY, 0xFFFFFF, true);
                            lineY += 12;
                            line.setLength(0);
                            shown++;
                            if (shown >= MAX_PLAYERS_LINES - 2) {
                                context.drawText(tr, "§8…… 共 " + players.size() + " 人", midX, lineY, 0x666666, true);
                                line.setLength(0);
                                break;
                            }
                        }
                        line.append(piece);
                    }
                    if (line.length() > 0) {
                        context.drawText(tr, line.toString(), midX, lineY, 0xFFFFFF, true);
                    }
                }
            }
        }

        // 右列：地图图片 + 渐变
        drawGradientImage(context, map.id, imgX, y + 6, imgW, height - 12);
    }

    // ===== 公共绘制 =====

    private void drawRowFrame(DrawContext context, int x, int y, int width, int height) {
        context.fill(x, y, x + width, y + height, 0x66101018);
        context.drawBorder(x, y, width, height, CELL_BORDER);
    }

    /** 徽标框：黄色边框 + 徽标图（badge 字段为内容寻址 id，查分片缓存；未到齐/无徽标 = 深色实心块） */
    static void drawBadge(DrawContext context, int x, int y, int size) {
        context.fill(x, y, x + size, y + size, 0xFF111111);
        Base64ImageDecoder.CardTexture tex = null;
        var mine = cn.woshiikun_1145.mcmod.choco.cstmm.client.cache.ClanCache.getInstance().getMine();
        if (mine.inClan && mine.clan != null && mine.clan.badge != null && !mine.clan.badge.isEmpty()) {
            String base64 = cn.woshiikun_1145.mcmod.choco.cstmm.client.cache.BadgeCache.get(mine.clan.badge);
            if (base64 != null) tex = Base64ImageDecoder.decode(base64);
        }
        if (tex != null) {
            context.drawTexture(tex.id(), x, y, size, size, 0f, 0f, tex.width(), tex.height(), tex.width(), tex.height());
        }
        context.drawBorder(x, y, size, size, 0xFFFFD700);
    }

    /** 地图图片：先铺图，再从左到右覆盖渐隐的背景色条（左遮罩最重=图最透明，右侧全露出=100% 不透明） */
    static void drawGradientImage(DrawContext context, String mapId, int x, int y, int width, int height) {
        if (width <= 0 || height <= 0) return;
        context.fill(x, y, x + width, y + height, 0xFF0D0D15);
        Base64ImageDecoder.CardTexture tex = "quick".equals(mapId)
                ? MatchMenuScreen.getQuickCardTexture()
                : Base64ImageDecoder.decode(backgroundOf(mapId));
        if (tex != null) {
            context.drawTexture(tex.id(), x, y, width, height, 0f, 0f, tex.width(), tex.height(), tex.width(), tex.height());
        }
        // 不透明度渐变遮罩：左侧不透明度 0% → 右侧 100%（用背景色遮罩反向模拟）
        int stripW = Math.max(1, width / IMAGE_STRIP_COUNT);
        for (int i = 0; i < IMAGE_STRIP_COUNT; i++) {
            float ratio = (float) i / (IMAGE_STRIP_COUNT - 1); // 0 左 → 1 右
            int alpha = (int) (0xCC * ratio);
            int color = (alpha << 24) | 0x0D0D15;
            context.fill(x + i * stripW, y, x + (i + 1) * stripW, y + height, color);
        }
    }

    private static String backgroundOf(String mapId) {
        for (MapConfig cfg : ConfigDataCache.getInstance().getMaps()) {
            if (cfg.getId().equals(mapId)) {
                return cfg.getBackgroundBase64() != null ? cfg.getBackgroundBase64() : "";
            }
        }
        return "";
    }

    private static MapConfig findMap(String mapId) {
        for (MapConfig cfg : ConfigDataCache.getInstance().getMaps()) {
            if (cfg.getId().equals(mapId)) return cfg;
        }
        return null;
    }

    private static String orDefault(String s, String def) {
        return s == null || s.isEmpty() ? def : s;
    }
}
