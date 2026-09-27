package cn.woshiikun_1145.mcmod.choco.cstmm.client.hud;

import cn.woshiikun_1145.mcmod.choco.cstmm.client.ClientHandshakeState;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.cache.BadgeCache;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.util.Base64ImageDecoder;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.util.FaceImageCache;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.util.UrlImageCache;
import cn.woshiikun_1145.mcmod.choco.cstmm.manager.ClanManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 【作用】对局 HUD 覆盖层（CS2 风格顶部队伍栏）：屏幕顶部横贯三段式——
 *         己方队伍卡片列（左）+ 中央计分板 + 敌方队伍卡片列（右）。
 *         玩家卡片 = 名字条（[战队缩写][队伍]玩家名）+ 队色边框头像（右下角战队徽标，无则不显示），
 *         准备阶段下挂"个人击杀 + 本局 K/D（1 位小数）"与"上一条命击杀徽章（骷髅+N杀）"，
 *         对局期间下挂红色血条（存活）或白色小骷髅（阵亡）；头像变暗表示阵亡。
 *         中央计分板 = 地图名 + 红色倒计时 + 红蓝比分 + 双方存活人数。
 *         血量/存活/阵亡由客户端本地读取（同服玩家实体），不占网络包。
 *         支持进出对局的淡入淡出动画；未握手成功时不渲染。
 * 【被谁使用】CstmmClient#onInitializeClient 调用 init() 注册到 HudRenderCallback；
 *             ClientNetworkHandler 的 HudDataPayload 接收器调用 updateData、DISCONNECT 回调调用 reset。
 */
@Environment(EnvType.CLIENT)
public class HudOverlay implements HudRenderCallback {

    // 饿汉式单例（HudRenderCallback 事件监听器）
    private static final HudOverlay INSTANCE = new HudOverlay();

    // ==================== 服务端推送数据 ====================
    // 地图显示名；空串表示未在对局
    private static volatile String mapName = "";
    // 红队击杀数
    private static volatile int redKills = 0;
    // 蓝队击杀数
    private static volatile int blueKills = 0;
    // 倒计时秒数（准备阶段=准备倒计时，战斗阶段=对局剩余秒数；0 表示无计时）
    private static volatile int remainingSeconds = 0;
    // 当前是否处于对局中
    private static volatile boolean inGame = false;
    // 对局阶段（MatchSession.GamePhase.ordinal()：1=准备 2=战斗）
    private static volatile int phase = 0;
    // 本局花名册（解析自 hud_data.rosterJson，按服务端顺序：红队在前蓝队在后）
    private static volatile List<RosterEntry> roster = List.of();

    /**
     * 花名册单个玩家条目（解析自服务端 rosterJson）：
     * uuid/名字/队伍(1红2蓝)/本局击杀/本局死亡/上一条命击杀/战队缩写/徽标引用/头像绑定。
     */
    private record RosterEntry(UUID uuid, String name, int team, int kills, int deaths,
                               int lastLifeKills, String clanAbbr, String badge,
                               String avatarType, String avatarId) {}

    // ==================== 布局常量（GUI 缩放坐标） ====================
    // 名字条高度
    private static final int NAME_H = 11;
    // 头像边长
    private static final int AV = 40;
    // 卡片宽（头像两侧各留 2px）
    private static final int CARD_W = AV + 4;
    // 卡片高（名字条 + 头像）
    private static final int CARD_H = NAME_H + AV;
    // 卡片间距
    private static final int GAP = 3;
    // 中央计分板宽度
    private static final int CENTER_W = 76;
    // 卡片顶部起点（贴屏幕顶）
    private static final int TOP_Y = 2;
    // 卡片列与计分板的最小间距
    private static final int SIDE_PAD = 10;

    // 队色：红队边框 / 蓝队边框 / 自己卡片描边
    private static final int RED_FRAME = 0xFFFF5555;
    private static final int BLUE_FRAME = 0xFF5566FF;
    private static final int SELF_FRAME = 0xFFFFD700;

    // 动画相关
    // HUD 整体透明度系数（0~1），进出对局时逐帧增减实现淡入淡出
    private static float fadeAlpha = 1.0f;

    // 单例，禁止外部实例化
    private HudOverlay() {}

    /**
     * 【作用】将本类注册为 HUD 渲染回调，使 onHudRender 每帧被调用。
     * 【被谁使用】仅被 CstmmClient#onInitializeClient 调用一次。
     */
    public static void init() {
        HudRenderCallback.EVENT.register(INSTANCE);
    }

    /**
     * 【作用】每帧渲染 CS2 风格顶部队伍栏：左右两队卡片 + 中央计分板，按淡入淡出系数整体透明。
     * 【被谁使用】Fabric HudRenderCallback 事件（init 注册后由渲染循环每帧回调）。
     */
    @Override
    public void onHudRender(DrawContext context, RenderTickCounter tickCounter) {
        // 未与服务端握手成功时不渲染 HUD
        if (!ClientHandshakeState.isHandshaked()) return;

        MinecraftClient client = MinecraftClient.getInstance();
        // 【作用】按是否在对局中逐帧调整透明度，实现 HUD 淡入/淡出
        if (client.player == null || !inGame) {
            // 不在游戏中，淡出
            if (fadeAlpha > 0) {
                fadeAlpha = Math.max(0, fadeAlpha - 0.02f);
            }
            if (fadeAlpha <= 0) return;
        } else {
            // 在游戏中，淡入
            fadeAlpha = Math.min(1.0f, fadeAlpha + 0.02f);
        }

        if (client.world == null) return;
        TextRenderer tr = client.textRenderer;
        int centerX = client.getWindow().getScaledWidth() / 2;

        List<RosterEntry> own = new ArrayList<>();
        List<RosterEntry> enemy = new ArrayList<>();
        splitTeams(client, own, enemy);

        boolean preparing = phase == 1;

        // ==================== 中央计分板 ====================
        drawScoreboard(context, tr, centerX, preparing);

        // ==================== 左右队伍卡片列 ====================
        // 【作用】按人数自适应卡片宽（超宽队伍收窄防溢出屏幕），己方贴计分板左侧、敌方右侧
        int availHalf = centerX - CENTER_W / 2 - SIDE_PAD;
        drawTeamRow(context, tr, client, own, fitCardWidth(own.size(), availHalf),
                centerX - CENTER_W / 2, preparing, true);
        drawTeamRow(context, tr, client, enemy, fitCardWidth(enemy.size(), availHalf),
                centerX + CENTER_W / 2, preparing, false);
    }

    // ==================== 中央计分板 ====================

    /**
     * 【作用】绘制中央计分板：地图名（金色小字）→ 红色大数字倒计时 → 红蓝比分 → 双方存活人数。
     * 【被谁使用】onHudRender（每帧）。
     */
    private static void drawScoreboard(DrawContext context, TextRenderer tr, int centerX, boolean preparing) {
        // 背景板（半透明黑，与卡片同高，贴顶）
        int bgTop = TOP_Y - 1;
        int bgBottom = TOP_Y + CARD_H + 1;
        context.fill(centerX - CENTER_W / 2, bgTop, centerX + CENTER_W / 2, bgBottom, applyFade(0xA6000000));
        context.drawBorder(centerX - CENTER_W / 2, bgTop, CENTER_W, bgBottom - bgTop, applyFade(0x55FFFFFF));

        // 第一行：地图名（保留原 HUD 元素，金色小字）
        String mapDisplay = mapName.isEmpty() ? "" : "⚔ " + mapName;
        if (!mapDisplay.isEmpty()) {
            drawScaledText(context, tr, mapDisplay, centerX, TOP_Y + 1, 0.75f, 0xFFAA00, true);
        }

        // 第二行：倒计时（红色大数字，准备阶段为准备倒计时，战斗阶段为对局剩余时间）
        if (remainingSeconds > 0 || preparing) {
            drawScaledText(context, tr, formatTime(Math.max(0, remainingSeconds)),
                    centerX, TOP_Y + 10, 1.5f, 0xFFFF5555, true);
        }

        // 第三行：红蓝比分（队色数字 + 白色分隔竖线）
        String scoreText = "§c" + redKills + "§f | §9" + blueKills;
        drawScaledText(context, tr, scoreText, centerX, TOP_Y + 33, 1.0f, 0xFFFFFF, true);

        // 第四行：双方存活人数（人形图标 + 数字，己方左/敌方右）
        drawAliveCount(context, tr, centerX, true);
        drawAliveCount(context, tr, centerX, false);
    }

    /**
     * 【作用】绘制半边存活人数：人形小图标（头+身像素块）+ 数字，按当前视角己方在左、敌方在右。
     * 【被谁使用】drawScoreboard（每帧）。
     */
    private static void drawAliveCount(DrawContext context, TextRenderer tr, int centerX, boolean ownSide) {
        List<RosterEntry> list = teamListOf(ownSide);
        MinecraftClient client = MinecraftClient.getInstance();
        int alive = 0;
        for (RosterEntry e : list) {
            if (isAlive(client, e.uuid())) alive++;
        }
        int color = applyFade(0xFFEEEEEE);
        int numberW = tr.getWidth(Text.literal(String.valueOf(alive)));
        // 己方靠左半、敌方靠右半，图标 + 数字组合居中于各自半区
        int groupW = 7 + numberW;
        int x = ownSide ? centerX - CENTER_W / 4 - groupW / 2 : centerX + CENTER_W / 4 - groupW / 2;
        int y = TOP_Y + 44;
        drawPersonIcon(context, x, y + 1, color);
        context.drawText(tr, Text.literal(String.valueOf(alive)), x + 7, y, color, true);
    }

    // ==================== 队伍卡片列 ====================

    /**
     * 【作用】绘制一列（一排）玩家卡片；ownSide=true 时自己的卡片排在最左（CS2 视角习惯）。
     * 己方从锚点向左生长（贴计分板左侧），敌方从锚点向右生长。
     * 【被谁使用】onHudRender（每帧，左右各一次）。
     */
    private static void drawTeamRow(DrawContext context, TextRenderer tr, MinecraftClient client,
                                    List<RosterEntry> list, int cardW, int startAnchorX,
                                    boolean preparing, boolean growLeft) {
        if (list.isEmpty()) return;
        int totalW = list.size() * cardW + (list.size() - 1) * GAP;
        int x = growLeft ? startAnchorX - totalW : startAnchorX;
        int y = TOP_Y;
        for (RosterEntry e : list) {
            boolean self = client.player != null && e.uuid().equals(client.player.getUuid());
            drawCard(context, tr, client, e, x, y, cardW, self, preparing);
            x += cardW + GAP;
        }
    }

    /**
     * 【作用】按可用宽度与卡片数计算自适应卡片宽（下限 30，上限标准宽），超宽队伍收窄防溢出。
     * 【被谁使用】onHudRender（每帧）。
     */
    private static int fitCardWidth(int count, int availHalf) {
        if (count <= 0) return CARD_W;
        int w = (availHalf - (count - 1) * GAP) / count;
        return Math.max(30, Math.min(CARD_W, w));
    }

    /**
     * 【作用】绘制单张玩家卡片：名字条（[战队缩写][队伍]玩家名）→ 队色边框头像
     *         （右下角战队徽标小图，无徽标不显示）→ 阶段下挂内容：
     *         准备阶段 = 头像左下角血量数字 + 下方"个人击杀 + 本局 K/D"与"上一条命击杀徽章"；
     *         对局期间 = 存活画红色血条、阵亡头像压暗并画白色小骷髅。
     * 【被谁使用】drawTeamRow（每帧每卡片）。
     */
    private static void drawCard(DrawContext context, TextRenderer tr, MinecraftClient client,
                                 RosterEntry e, int x, int y, int cardW, boolean self, boolean preparing) {
        boolean alive = isAlive(client, e.uuid());
        int avX = x + 2;
        int avY = y + NAME_H;

        // 卡片外框（统一暗描边；自己的卡片用金色描边高亮）
        context.fill(x, y, x + cardW, y + CARD_H, applyFade(0x88000000));
        context.drawBorder(x, y, cardW, CARD_H, applyFade(self ? SELF_FRAME : 0x44000000));

        // ---- 名字条：[战队缩写][队伍]{玩家名}，阵亡置灰 ----
        String abbr = e.clanAbbr() == null ? "" : e.clanAbbr();
        String teamTag = e.team() == 1 ? "红" : "蓝";
        String cCode = alive ? "§7" : "§8";
        String tCode = alive ? (e.team() == 1 ? "§c" : "§9") : "§8";
        String nCode = alive ? "§f" : "§8";
        drawNameStrip(context, tr, abbr, cCode, tCode, teamTag, nCode, e.name(), x + 2, y + 1, cardW - 4);

        // ---- 头像：深色底 + 绑定头像（QQ/B站，未设置显示深色占位） ----
        context.fill(avX, avY, avX + AV, avY + AV, applyFade(0xFF161616));
        Base64ImageDecoder.CardTexture face = FaceImageCache.getTexture(e.avatarType(), e.avatarId());
        if (face != null) {
            context.drawTexture(face.id(), avX, avY, AV, AV, 0f, 0f, face.width(), face.height(), face.width(), face.height());
        } else {
            // 头像未就绪/未绑定：显示名字首字占位
            String initial = e.name().isEmpty() ? "?" : e.name().substring(0, 1);
            drawScaledText(context, tr, "§8" + initial, avX + AV / 2f, avY + AV / 2f - 4, 1.0f, 0xFFFFFF, true);
        }
        // 阵亡：头像压暗（半透明黑罩）
        if (!alive) {
            context.fill(avX, avY, avX + AV, avY + AV, applyFade(0x96000000));
        }
        // 队色头像边框（自己的卡片用金色）
        context.drawBorder(avX, avY, AV, AV, applyFade(self ? SELF_FRAME : (e.team() == 1 ? RED_FRAME : BLUE_FRAME)));

        // ---- 头像右下角战队徽标（没有设置徽标则不显示） ----
        drawClanBadge(context, e.badge(), avX + AV - 13, avY + AV - 13, 13);

        // ---- 阶段下挂内容 ----
        if (preparing) {
            // 准备阶段：头像左下角血量数字（白字阴影）
            var ent = getPlayerEntity(client, e.uuid());
            if (ent != null && alive) {
                String hp = String.format("%.0f", Math.max(0, ent.getHealth()));
                context.drawText(tr, Text.literal(hp), avX + 2, avY + AV - 9, applyFade(0xFFFFFFFF), true);
            }
            // 下方第一行：个人击杀 + 本局 K/D（1 位小数）
            double kd = e.deaths() == 0 ? e.kills() : (double) e.kills() / e.deaths();
            String statLine = "§f" + e.kills() + "杀 §7K/D " + String.format("%.1f", kd);
            drawScaledText(context, tr, statLine, x + cardW / 2f, y + CARD_H + 2, 0.75f, 0xFFFFFF, true);
            // 下方第二行：上一条命击杀徽章（白色小骷髅 + 绿色 N杀），无则不显示
            if (e.lastLifeKills() > 0) {
                String badgeText = "§a" + e.lastLifeKills() + "杀";
                int badgeW = 8 + tr.getWidth(Text.literal(badgeText)) * 3 / 4;
                int bx = x + cardW / 2 - badgeW / 2;
                drawSkullIcon(context, bx, y + CARD_H + 11, applyFade(0xFFEEEEEE));
                drawScaledText(context, tr, badgeText, bx + 8, y + CARD_H + 10, 0.75f, 0x55FF55, false);
            }
        } else {
            // 对局期间：存活 = 头像下方红色血条；阵亡 = 白色小骷髅
            if (alive) {
                var ent = getPlayerEntity(client, e.uuid());
                float max = ent != null ? Math.max(1f, ent.getMaxHealth()) : 20f;
                float hp = ent != null ? Math.max(0f, ent.getHealth()) : 0f;
                int barW = cardW - 8;
                int bx = x + (cardW - barW) / 2;
                int by = y + CARD_H + 3;
                context.fill(bx, by, bx + barW, by + 3, applyFade(0x66000000));
                int fillW = (int) (barW * (hp / max));
                if (fillW > 0) {
                    context.fill(bx, by, bx + fillW, by + 3, applyFade(0xFFDD3333));
                }
            } else {
                drawSkullIcon(context, x + cardW / 2 - 3, y + CARD_H + 3, applyFade(0xFFEEEEEE));
            }
        }
    }

    /**
     * 【作用】绘制头像右下角战队徽标小图（URL 徽标客户端自行下载，base64 徽标走分片缓存）；
     * 徽标缺失/未设置时不绘制（徽标分片未就绪时同样不画，到货后下一帧自动出现）。
     * 【被谁使用】drawCard（每帧）。
     */
    private static void drawClanBadge(DrawContext context, String badgeValue, int x, int y, int size) {
        if (badgeValue == null || badgeValue.isEmpty()) return;
        // 【作用】base64 徽标 id 缺失时请求补发（幂等，已请求过直接返回）；URL 徽标由 UrlImageCache 自行下载
        if (!ClanManager.isBadgeUrl(badgeValue)) {
            BadgeCache.requestIfMissing(badgeValue);
        }
        Base64ImageDecoder.CardTexture tex = UrlImageCache.resolveBadgeTexture(badgeValue);
        if (tex == null) return;
        context.drawTexture(tex.id(), x, y, size, size, 0f, 0f, tex.width(), tex.height(), tex.width(), tex.height());
        context.drawBorder(x, y, size, size, applyFade(0xFF222222));
    }

    // ==================== 小部件与工具 ====================

    /**
     * 【作用】绘制名字条文字（0.75 缩放居中）：[战队缩写][队伍]{玩家名}；
     * 超宽时只截断玩家名尾部为 …（保证格式码与队/战队标记完整，不会截断 § 转义对）。
     * 【被谁使用】drawCard（每帧）。
     */
    private static void drawNameStrip(DrawContext context, TextRenderer tr, String abbr, String cCode,
                                      String tCode, String teamTag, String nCode, String name,
                                      int x, int y, int maxW) {
        double scale = 0.75;
        String abbrSeg = abbr.isEmpty() ? "" : cCode + "[" + abbr + "]";
        String head = abbrSeg + tCode + "[" + teamTag + "]" + nCode;
        String shownName = name;
        String text = head + shownName;
        // 【作用】名字超宽时逐字符截断为 …；名字缩到 1 字符仍超宽则整体接受（极少见的超长缩写场景）
        while (tr.getWidth(Text.literal(text)) * scale > maxW && shownName.length() > 1) {
            shownName = shownName.substring(0, shownName.length() - 1) + "…";
            text = head + shownName;
        }
        drawScaledText(context, tr, text, (float) (x + maxW / 2.0), y, (float) scale, 0xFFFFFF, true);
    }

    /** 【作用】按缩放绘制带 § 格式码的文本；centerX=true 时按渲染后宽度水平居中。仅 HUD 内部使用。 */
    private static void drawScaledText(DrawContext context, TextRenderer tr, String text,
                                       float x, float y, float scale, int color, boolean centerX) {
        var matrices = context.getMatrices();
        matrices.push();
        if (centerX) {
            float w = tr.getWidth(Text.literal(text)) * scale;
            x -= w / 2f;
        }
        matrices.translate(x, y, 0);
        matrices.scale(scale, scale, 1f);
        context.drawText(tr, Text.literal(text), 0, 0, applyFade(color), true);
        matrices.pop();
    }

    /** 【作用】绘制白色人形小图标（头 3x2 + 身 5x3 像素块），存活人数用。仅 HUD 内部使用。 */
    private static void drawPersonIcon(DrawContext context, int x, int y, int color) {
        context.fill(x + 1, y, x + 4, y + 2, color);
        context.fill(x, y + 3, x + 5, y + 6, color);
    }

    /** 【作用】绘制白色小骷髅图标（头骨 + 下颚 + 深色眼窝），阵亡标记与击杀徽章用。仅 HUD 内部使用。 */
    private static void drawSkullIcon(DrawContext context, int x, int y, int color) {
        context.fill(x + 1, y, x + 6, y + 4, color);
        context.fill(x + 2, y + 4, x + 5, y + 5, color);
        context.fill(x + 2, y + 2, x + 3, y + 3, 0xFF1A1A1A);
        context.fill(x + 4, y + 2, x + 5, y + 3, 0xFF1A1A1A);
    }

    /** 【作用】将颜色的 alpha 通道按 fadeAlpha 缩放，使背景/边框/文字同步淡入淡出。 */
    private static int applyFade(int argb) {
        int a = (int) (((argb >>> 24) & 0xFF) * fadeAlpha);
        return (argb & 0xFFFFFF) | (a << 24);
    }

    // 将秒数格式化为 "分:秒"（两位补零）
    private static String formatTime(int seconds) {
        int minutes = seconds / 60;
        int secs = seconds % 60;
        return String.format("%02d:%02d", minutes, secs);
    }

    // ==================== 花名册辅助 ====================

    /**
     * 【作用】把花名册按"己方在左/敌方在右"拆成两队；己方队伍内自己的卡片排最前（CS2 视角习惯）。
     * 自己不在花名册（如观战）时默认红队视为己方。
     * 【被谁使用】onHudRender（每帧）。
     */
    private static void splitTeams(MinecraftClient client, List<RosterEntry> own, List<RosterEntry> enemy) {
        UUID self = client.player != null ? client.player.getUuid() : null;
        int ownTeam = 1;
        List<RosterEntry> current = roster;
        for (RosterEntry e : current) {
            if (self != null && e.uuid().equals(self)) {
                ownTeam = e.team();
                break;
            }
        }
        RosterEntry selfEntry = null;
        for (RosterEntry e : current) {
            boolean isOwnTeam = e.team() == ownTeam;
            if (self != null && e.uuid().equals(self)) {
                selfEntry = e;
            } else if (isOwnTeam) {
                own.add(e);
            } else {
                enemy.add(e);
            }
        }
        if (selfEntry != null) {
            own.add(0, selfEntry);
        }
    }

    /** 【作用】取指定视角侧的队伍列表（存活人数统计用）。仅 HUD 内部使用。 */
    private static List<RosterEntry> teamListOf(boolean ownSide) {
        // 与 splitTeams 同口径：按自己所在队拆分（无自己时默认红队为"己方"）
        MinecraftClient client = MinecraftClient.getInstance();
        UUID self = client.player != null ? client.player.getUuid() : null;
        int ownTeam = 1;
        for (RosterEntry e : roster) {
            if (self != null && e.uuid().equals(self)) {
                ownTeam = e.team();
                break;
            }
        }
        List<RosterEntry> list = new ArrayList<>();
        for (RosterEntry e : roster) {
            boolean isOwn = e.team() == ownTeam;
            if (ownSide == isOwn) list.add(e);
        }
        return list;
    }

    /**
     * 【作用】按 UUID 查找客户端世界中的玩家实体（血量/存活读取用）；不在世界（未追踪/已下线）返回 null。
     * 【被谁使用】drawCard/isAlive（每帧）。
     */
    private static net.minecraft.client.network.AbstractClientPlayerEntity getPlayerEntity(MinecraftClient client, UUID uuid) {
        if (client.world == null) return null;
        for (var p : client.world.getPlayers()) {
            if (p.getUuid().equals(uuid)) return p;
        }
        return null;
    }

    /**
     * 【作用】判断玩家当前是否存活（客户端本地读取，不走网络）：
     * 实体不存在（未追踪/已下线/死亡动画结束移除）或生命值 ≤ 0 视为阵亡。
     * 【被谁使用】drawCard/drawAliveCount（每帧）。
     */
    private static boolean isAlive(MinecraftClient client, UUID uuid) {
        var entity = getPlayerEntity(client, uuid);
        return entity != null && entity.isAlive() && entity.getHealth() > 0;
    }

    // ==================== 数据更新方法 ====================

    /**
     * 【作用】整体覆盖写入服务端推送的 HUD 数据（地图名/比分/倒计时/阶段/花名册 JSON），
     *         花名册在此解析为条目列表（单包 ≤64KiB，解析失败保留上一次花名册并告警）。
     * 【被谁使用】仅被 ClientNetworkHandler 的 HudDataPayload 接收器调用。
     */
    public static void updateData(String name, int red, int blue, int time, boolean game, int phaseOrdinal, String rosterJson) {
        mapName = name;
        redKills = red;
        blueKills = blue;
        remainingSeconds = time;
        inGame = game;
        phase = phaseOrdinal;
        roster = parseRoster(rosterJson);
    }

    /**
     * 【作用】解析花名册 JSON（紧凑数组，字段见 MatchManager#buildRosterJson）为条目列表；
     * 空/非法 JSON 返回空列表（HUD 退化为仅显示中央计分板）。
     * 【被谁使用】updateData（每次收到 hud_data 包）。
     */
    private static List<RosterEntry> parseRoster(String rosterJson) {
        List<RosterEntry> list = new ArrayList<>();
        if (rosterJson == null || rosterJson.isEmpty()) return list;
        try {
            JsonArray arr = JsonParser.parseString(rosterJson).getAsJsonArray();
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                String u = optString(o, "u");
                UUID uuid;
                try {
                    uuid = UUID.fromString(u);
                } catch (IllegalArgumentException bad) {
                    continue;
                }
                list.add(new RosterEntry(
                        uuid,
                        optString(o, "n"),
                        optInt(o, "t"),
                        optInt(o, "k"),
                        optInt(o, "d"),
                        optInt(o, "lk"),
                        optString(o, "c"),
                        optString(o, "b"),
                        optString(o, "at"),
                        optString(o, "ai")
                ));
            }
        } catch (Exception e) {
            cn.woshiikun_1145.mcmod.choco.cstmm.Cstmm.LOGGER.warn(
                    "[CSTMM - HudOverlay] Failed to parse roster JSON, keeping previous roster", e);
            return list;
        }
        return list;
    }

    /** 【作用】读取 JSON 对象字符串字段（缺失/null 返回空串）。仅 parseRoster 使用。 */
    private static String optString(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    /** 【作用】读取 JSON 对象整数字段（缺失/null 返回 0）。仅 parseRoster 使用。 */
    private static int optInt(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsInt() : 0;
    }

    /**
     * 【作用】断线时清空 HUD 数据并重置淡出动画状态。
     * 【被谁使用】仅被 ClientNetworkHandler 的 DISCONNECT 回调调用。
     */
    public static void reset() {
        mapName = "";
        redKills = 0;
        blueKills = 0;
        remainingSeconds = 0;
        inGame = false;
        phase = 0;
        roster = List.of();
        fadeAlpha = 0;
    }

    // 是否处于对局中（当前无调用方，预留查询接口）
    public static boolean isInGame() {
        return inGame;
    }
}
