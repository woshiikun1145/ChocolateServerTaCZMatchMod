package cn.woshiikun_1145.mcmod.choco.cstmm.client.screen;

import cn.woshiikun_1145.mcmod.choco.cstmm.Cstmm;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.cache.ConfigDataCache;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.ClientHandshakeState;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.network.ClientNetworkHandler;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.config.MapConfig;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.PlayerProfile;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.payload.MatchActionPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@Environment(EnvType.CLIENT)
public class MatchMenuScreen extends Screen {

    private static final int SIDEBAR_WIDTH = 140;
    private static final int HEADER_HEIGHT = 50;
    private static final int CARD_WIDTH = 260;
    private static final int CARD_HEIGHT = 150;
    private static final int CARD_SPACING = 16;
    /** 卡片网格渲染/点击共用的起始坐标（内容区起点向内偏移 10px，保证两处基准一致） */
    private static final int CARDS_START_X = SIDEBAR_WIDTH + 30;
    private static final int CARDS_START_Y = HEADER_HEIGHT + 20;

    /** 快速匹配卡片的本地标识：仅用于客户端卡片识别（内置背景图/走 JOIN_QUICK 专用动作），不作为地图 ID 外发 */
    private static final String QUICK_CARD_ID = "quick";

    private final List<SidebarButton> sidebarButtons = new ArrayList<>();
    private int selectedTab = 0;
    private List<MapCardData> mapCards = new ArrayList<>();

    /** 侧边栏橙色标记当前 Y（像素，浮点做滑动动画；目标为选中按钮实际索引位置） */
    private float markerY;

    private int scrollOffset = 0;

    /** 玩家加入队列时携带的匹配模式：COMPETITIVE（竞技）/ CASUAL（休闲），默认竞技 */
    private String selectedMode = "COMPETITIVE";
    private ButtonWidget casualModeBtn;
    private ButtonWidget competitiveModeBtn;

    // 关于页面按钮（仅 tab == 4 时可见）
    private ButtonWidget repoBtn;
    private ButtonWidget issueBtn;
    private ButtonWidget secretBtn;

    public MatchMenuScreen() {
        super(Text.literal("匹配菜单"));

        sidebarButtons.add(new SidebarButton("🏠 欢迎", 0));
        sidebarButtons.add(new SidebarButton("⚔ 匹配", 1));
        sidebarButtons.add(new SidebarButton("📊 履历", 2));
        // 配置界面设计上只能通过 /cstmm config 打开，匹配菜单不提供入口
        sidebarButtons.add(new SidebarButton("ℹ 关于", 4));

        loadMaps();
    }

    public void refreshMaps() {
        loadMaps();
    }

    private void loadMaps() {
        mapCards.clear();

        // 快速匹配入口：独立队列，服务端自动搜索未占用地图开局，超时后补位
        mapCards.add(new MapCardData(QUICK_CARD_ID, "⚡ 快速匹配", "自动选择地图", ""));

        ConfigDataCache cache = ConfigDataCache.getInstance();
        List<MapConfig> maps = cache.getMaps();

        if (maps != null && !maps.isEmpty()) {
            for (MapConfig config : maps) {
                if (!config.isEnabled()) continue;
                String type = config.getWinCondition() == MapConfig.WinCondition.KILLS ? "按击杀" : "按计时";
                mapCards.add(new MapCardData(
                        config.getId(),
                        config.getDisplayName(),
                        type,
                        config.getBackgroundBase64() != null ? config.getBackgroundBase64() : ""
                ));
            }
        }
        // 移除硬编码假地图卡片：未同步/无地图时由 drawMatchCards 的空提示兜底，避免点击假卡片发出无效加入请求
    }

    @Override
    protected void init() {
        super.init();

        int btnY = HEADER_HEIGHT + 10;
        for (SidebarButton btn : sidebarButtons) {
            final int tabId = btn.id;
            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal(btn.label),
                    button -> {
                        selectedTab = tabId;
                        // 模式切换按钮仅在"匹配"页显示，关于页按钮仅在"关于"页显示
                        casualModeBtn.visible = tabId == 1;
                        competitiveModeBtn.visible = tabId == 1;
                        boolean about = tabId == 4;
                        repoBtn.visible = about;
                        issueBtn.visible = about;
                        secretBtn.visible = about;
                        if (tabId == 2) {
                            requestProfile();
                        }
                    }
            ).dimensions(10, btnY, SIDEBAR_WIDTH - 20, 28).build());
            btnY += 34;
        }

        // 模式切换按钮：置于标题栏右侧（不遮挡标题与卡片区），仅在"匹配"页显示
        casualModeBtn = this.addDrawableChild(ButtonWidget.builder(
                Text.literal(""),
                button -> {
                    selectedMode = "CASUAL";
                    updateModeButtonMessages();
                }
        ).dimensions(this.width - 198, 16, 90, 18).build());
        competitiveModeBtn = this.addDrawableChild(ButtonWidget.builder(
                Text.literal(""),
                button -> {
                    selectedMode = "COMPETITIVE";
                    updateModeButtonMessages();
                }
        ).dimensions(this.width - 102, 16, 90, 18).build());
        updateModeButtonMessages();
        casualModeBtn.visible = selectedTab == 1;
        competitiveModeBtn.visible = selectedTab == 1;

        // 关于页面按钮：位于文字介绍下方（drawAbout 文字止于 contentY + 110 = 170）
        int aboutBtnX = SIDEBAR_WIDTH + 20 + 20;
        repoBtn = this.addDrawableChild(ButtonWidget.builder(
                Text.literal("§9模组仓库"),
                button -> openUrl("https://github.com/woshiikun1145/ChocolateServerTaCZMatchMod")
        ).dimensions(aboutBtnX, 180, 190, 20).build());
        issueBtn = this.addDrawableChild(ButtonWidget.builder(
                Text.literal("§9报告问题"),
                button -> openUrl("https://github.com/woshiikun1145/ChocolateServerTaCZMatchMod/issues")
        ).dimensions(aboutBtnX, 206, 190, 20).build());
        secretBtn = this.addDrawableChild(ButtonWidget.builder(
                Text.literal("§4千万别点"),
                button -> triggerSecretAction()
        ).dimensions(aboutBtnX, 232, 190, 20).build());
        boolean aboutVisible = selectedTab == 4;
        repoBtn.visible = aboutVisible;
        issueBtn.visible = aboutVisible;
        secretBtn.visible = aboutVisible;

        // 橙色标记初始化为当前选中按钮位置，避免打开界面时从顶部滑入
        markerY = markerTargetY();
    }

    /** 侧边栏橙色标记的目标 Y：按选中 tab 对应按钮的实际索引定位（tab id ≠ 按钮索引） */
    private float markerTargetY() {
        return HEADER_HEIGHT + 10 + buttonIndexOf(selectedTab) * 34f;
    }

    /** tab id 对应的按钮索引（找不到按 0 处理） */
    private int buttonIndexOf(int tabId) {
        for (int i = 0; i < sidebarButtons.size(); i++) {
            if (sidebarButtons.get(i).id == tabId) return i;
        }
        return 0;
    }

    /** 刷新模式按钮文案：选中项加 §a✔ 高亮，未选中置灰 */
    private void updateModeButtonMessages() {
        casualModeBtn.setMessage(Text.literal(
                "CASUAL".equals(selectedMode) ? "§a✔ 休闲模式" : "§7休闲模式"));
        competitiveModeBtn.setMessage(Text.literal(
                "COMPETITIVE".equals(selectedMode) ? "§a✔ 竞技模式" : "§7竞技模式"));
    }

    private void requestProfile() {
        // 使用网络包请求，不再使用命令
        MatchActionPayload payload = new MatchActionPayload(
                MatchActionPayload.ActionType.REQUEST_PROFILE, "", 0, ""
        );
        ClientPlayNetworking.send(payload);
    }

    // 重写 renderBackground 阻止模糊
    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        // 留空，不绘制原版背景，避免模糊
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // 绘制自定义纯色背景
        context.fill(0, 0, this.width, this.height, 0xCC000000);

        int screenWidth = this.width;
        int screenHeight = this.height;

        // 标题栏
        context.fill(0, 0, screenWidth, HEADER_HEIGHT, 0xCC222222);
        Text title = Text.literal("🎯 Chocolate Match");
        context.drawText(textRenderer, title, 20, 16, 0xFFAA00, true);

        // 侧边栏
        context.fill(0, HEADER_HEIGHT, SIDEBAR_WIDTH, screenHeight, 0xCC1A1A1A);
        context.fill(SIDEBAR_WIDTH, HEADER_HEIGHT, SIDEBAR_WIDTH + 1, screenHeight, 0x44FFFFFF);

        // 侧边栏橙色标记：按按钮实际索引定位（修复"关于"页黄条错位：tab id 4 ≠ 按钮索引 3），
        // 切换时向目标位置滑动而非直接跳变
        float markerTarget = markerTargetY();
        markerY += (markerTarget - markerY) * Math.min(1f, delta * 0.25f);
        int markerTop = Math.round(markerY);
        context.fill(2, markerTop, 6, markerTop + 28, 0xFFFFAA00);

        int contentX = SIDEBAR_WIDTH + 20;
        int contentY = HEADER_HEIGHT + 10;
        int contentWidth = screenWidth - SIDEBAR_WIDTH - 30;
        int contentHeight = screenHeight - HEADER_HEIGHT - 20;

        drawContent(context, contentX, contentY, contentWidth, contentHeight, mouseX, mouseY, delta);

        // 版本号按文字实际宽度右对齐（贴右边缘留 5px），避免长版本号溢出屏幕
        String versionText = "v" + ClientHandshakeState.getClientVersion();
        context.drawText(textRenderer, versionText, screenWidth - textRenderer.getWidth(versionText) - 5, screenHeight - 15, 0x44FFFFFF, true);

        // 调用 super.render 绘制按钮（不绘制背景）
        super.render(context, mouseX, mouseY, delta);
    }

    private void drawContent(DrawContext context, int x, int y, int width, int height,
                             int mouseX, int mouseY, float delta) {
        switch (selectedTab) {
            case 0 -> drawWelcome(context, x, y, width, height);
            case 1 -> drawMatchCards(context, x, y, width, height, mouseX, mouseY, delta);
            case 2 -> drawProfile(context, x, y, width, height);
            case 4 -> drawAbout(context, x, y, width, height);
            default -> drawWelcome(context, x, y, width, height);
        }
    }

    private void drawWelcome(DrawContext context, int x, int y, int width, int height) {
        context.drawText(textRenderer, "§6欢迎来到 Chocolate Match", x + 20, y + 30, 0xFFFFFF, true);
        context.drawText(textRenderer, "§7这是一个为 Chocolate Server TaCZ 子服设计的 PvP 对战系统", x + 20, y + 60, 0xAAAAAA, true);
        context.drawText(textRenderer, "§7使用 §e; §7打开菜单，§e' §7打开商店", x + 20, y + 80, 0xAAAAAA, true);
        // 快速匹配入口只是队列，不计入地图数量
        int enabledMaps = 0;
        for (MapConfig m : ConfigDataCache.getInstance().getMaps()) {
            if (m.isEnabled()) enabledMaps++;
        }
        context.drawText(textRenderer, "§a当前可用地图: §f" + enabledMaps + " 张", x + 20, y + 110, 0xFFFFFF, true);
    }

    private void drawMatchCards(DrawContext context, int x, int y, int width, int height,
                                int mouseX, int mouseY, float delta) {
        if (mapCards.isEmpty()) {
            String hint = ConfigDataCache.getInstance().isLoaded() ? "§e暂无可用地图" : "§e配置同步中，请稍候...";
            context.drawText(textRenderer, hint, x + 20, y + 20, 0xFFFFAA, true);
            return;
        }

        int cardsPerRow = getCardsPerRow();
        int startX = CARDS_START_X;
        int startY = CARDS_START_Y - scrollOffset;

        // 裁剪到卡片内容区，防止卡片画出内容区底部
        context.enableScissor(x, y, x + width, y + height);
        for (int i = 0; i < mapCards.size(); i++) {
            int row = i / cardsPerRow;
            int col = i % cardsPerRow;
            int cardX = startX + col * (CARD_WIDTH + CARD_SPACING);
            int cardY = startY + row * (CARD_HEIGHT + CARD_SPACING);

            MapCardData card = mapCards.get(i);
            int bgColor = 0xCC2A2A2A;
            context.fill(cardX, cardY, cardX + CARD_WIDTH, cardY + CARD_HEIGHT, bgColor);
            context.drawBorder(cardX, cardY, CARD_WIDTH, CARD_HEIGHT, 0x44FFFFFF);

            // 卡片背景：等比裁剪铺满（cover）；快速匹配用内置 quick.png，其余用配置的 Base64 图，均无则纯色兜底
            int bgX = cardX + 2, bgY = cardY + 2;
            int bgW = CARD_WIDTH - 4, bgH = CARD_HEIGHT - 4;
            CardTexture tex = QUICK_CARD_ID.equals(card.id) ? getQuickCardTexture()
                    : decodeBase64Texture(card.backgroundBase64);
            if (tex != null) {
                // 与外层内容区裁剪求交集后再裁剪，保证滚动出可视区的卡片不会画到内容区外
                int clipX1 = Math.max(bgX, x), clipY1 = Math.max(bgY, y);
                int clipX2 = Math.min(bgX + bgW, x + width), clipY2 = Math.min(bgY + bgH, y + height);
                if (clipX2 > clipX1 && clipY2 > clipY1) {
                    double scale = Math.max((double) bgW / tex.width(), (double) bgH / tex.height());
                    int drawW = (int) Math.round(tex.width() * scale);
                    int drawH = (int) Math.round(tex.height() * scale);
                    int dx = bgX - (drawW - bgW) / 2;
                    int dy = bgY - (drawH - bgH) / 2;
                    context.enableScissor(clipX1, clipY1, clipX2, clipY2);
                    context.drawTexture(tex.id(), dx, dy, drawW, drawH,
                            0f, 0f, tex.width(), tex.height(), tex.width(), tex.height());
                    context.disableScissor();
                }
            } else {
                context.fill(bgX, bgY, bgX + bgW, bgY + bgH, 0xCC1A2A3A);
            }

            // 90% 不透明度灰色遮罩：压暗背景图，保证文字可读（层级：背景图 < 遮罩 < 文字）
            context.fill(cardX, cardY, cardX + CARD_WIDTH, cardY + CARD_HEIGHT, 0xE6808080);

            Text nameText = Text.literal("§6" + card.displayName);
            int nameWidth = textRenderer.getWidth(nameText);
            context.drawText(textRenderer, nameText,
                    cardX + (CARD_WIDTH - nameWidth) / 2,
                    cardY + CARD_HEIGHT / 2 - 20,
                    0xFFFFFF, true);

            Text typeText = Text.literal("§7" + card.mapType);
            int typeWidth = textRenderer.getWidth(typeText);
            context.drawText(textRenderer, typeText,
                    cardX + (CARD_WIDTH - typeWidth) / 2,
                    cardY + CARD_HEIGHT / 2 + 10,
                    0xAAAAAA, true);

            boolean hovered = mouseX >= cardX && mouseX <= cardX + CARD_WIDTH &&
                    mouseY >= cardY && mouseY <= cardY + CARD_HEIGHT;
            if (hovered) {
                context.fill(cardX, cardY, cardX + CARD_WIDTH, cardY + CARD_HEIGHT, 0x22FFFFFF);
                context.drawText(textRenderer, "§e点击加入", cardX + CARD_WIDTH - 60, cardY + 8, 0xFFFFAA, true);
            }
        }
        context.disableScissor();
    }

    /** 卡片每行数量（渲染、点击、滚动共用同一公式，避免三处不一致） */
    private int getCardsPerRow() {
        return Math.max(1, (this.width - SIDEBAR_WIDTH - 50) / (CARD_WIDTH + CARD_SPACING));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (selectedTab == 1) {
            int x = CARDS_START_X;
            int y = CARDS_START_Y - scrollOffset;
            int cardsPerRow = getCardsPerRow();
            // 与渲染裁剪区一致的内容区边界（contentY = HEADER_HEIGHT + 10，bottom = height - 10）
            int clipLeft = SIDEBAR_WIDTH + 20;
            int clipRight = this.width - 10;
            int clipTop = HEADER_HEIGHT + 10;
            int clipBottom = this.height - 10;

            for (int i = 0; i < mapCards.size(); i++) {
                int row = i / cardsPerRow;
                int col = i % cardsPerRow;
                int cardX = x + col * (CARD_WIDTH + CARD_SPACING);
                int cardY = y + row * (CARD_HEIGHT + CARD_SPACING);
                // 只对内容区内可见的部分做命中检测，避免点到已被滚出裁剪区的卡片
                int visLeft = Math.max(cardX, clipLeft);
                int visRight = Math.min(cardX + CARD_WIDTH, clipRight);
                int visTop = Math.max(cardY, clipTop);
                int visBottom = Math.min(cardY + CARD_HEIGHT, clipBottom);
                if (visRight <= visLeft || visBottom <= visTop) continue;
                if (mouseX >= visLeft && mouseX <= visRight &&
                        mouseY >= visTop && mouseY <= visBottom) {
                    joinQueue(mapCards.get(i).id);
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void joinQueue(String mapId) {
        if (MinecraftClient.getInstance().player == null) return;
        // #28 配置缓存未同步（握手未完成）时禁止发起匹配
        if (!ConfigDataCache.getInstance().isLoaded()) {
            MinecraftClient.getInstance().player.sendMessage(Text.literal("§e配置同步中，请稍候..."), false);
            return;
        }
        // 第 4 个字段携带匹配模式（COMPETITIVE/CASUAL），服务端按模式分队列
        boolean quick = QUICK_CARD_ID.equals(mapId);
        // 快速匹配走专用 JOIN_QUICK 动作，不再以 "quick" 伪地图 ID 复用 JOIN_QUEUE，
        // 消除与真实地图 ID "quick" 的冲突
        MatchActionPayload payload = new MatchActionPayload(
                quick ? MatchActionPayload.ActionType.JOIN_QUICK : MatchActionPayload.ActionType.JOIN_QUEUE,
                quick ? "" : mapId,
                0,
                selectedMode
        );
        ClientPlayNetworking.send(payload);
        close();
        if (MinecraftClient.getInstance().player != null) {
            String modeName = "COMPETITIVE".equals(selectedMode) ? "竞技模式" : "休闲模式";
            String message = quick
                    ? "§a已加入快速匹配队列（" + modeName + "），系统将自动分配地图..."
                    : "§a已加入 " + mapId + "（" + modeName + "）队列，等待匹配...";
            MinecraftClient.getInstance().player.sendMessage(Text.literal(message), false);
        }
    }

    private void drawProfile(DrawContext context, int x, int y, int width, int height) {
        context.drawText(textRenderer, "§6📊 玩家履历", x + 20, y + 20, 0xFFFFFF, true);

        PlayerProfile profile = ClientNetworkHandler.PlayerProfileCache.getInstance().getProfile();
        if (profile == null) {
            context.drawText(textRenderer, "§7加载中...", x + 20, y + 50, 0xAAAAAA, true);
            context.drawText(textRenderer, "§7（请稍候）", x + 20, y + 70, 0x666666, true);
            return;
        }

        int lineY = y + 50;
        int spacing = 22;
        context.drawText(textRenderer, "§7玩家: §f" + profile.getPlayerName(), x + 20, lineY, 0xFFFFFF, true);
        lineY += spacing;
        context.drawText(textRenderer, "§7总击杀: §c" + profile.getTotalKills(), x + 20, lineY, 0xFFFFFF, true);
        lineY += spacing;
        context.drawText(textRenderer, "§7总被击杀: §9" + profile.getTotalDeaths(), x + 20, lineY, 0xFFFFFF, true);
        lineY += spacing;
        context.drawText(textRenderer, "§7KD: §e" + profile.getKDString(), x + 20, lineY, 0xFFFFFF, true);
        lineY += spacing;
        context.drawText(textRenderer, "§7参赛场次: §a" + profile.getTotalMatches(), x + 20, lineY, 0xFFFFFF, true);
        lineY += spacing;
        context.drawText(textRenderer, "§7胜利场次: §6" + profile.getTotalWins(), x + 20, lineY, 0xFFFFFF, true);
        lineY += spacing;
        double winRate = profile.getTotalMatches() > 0 ?
                (double) profile.getTotalWins() / profile.getTotalMatches() * 100 : 0;
        context.drawText(textRenderer, "§7胜率: §b" + String.format("%.1f", winRate) + "%", x + 20, lineY, 0xFFFFFF, true);
    }

    private void drawAbout(DrawContext context, int x, int y, int width, int height) {
        context.drawText(textRenderer, "§6ℹ 关于", x + 20, y + 20, 0xFFFFFF, true);
        context.drawText(textRenderer, "§7Chocolate Match Mod", x + 20, y + 50, 0xAAAAAA, true);
        context.drawText(textRenderer, "§7版本: " + ClientHandshakeState.getClientVersion(), x + 20, y + 70, 0xAAAAAA, true);
        context.drawText(textRenderer, "§7作者: woshiikun_1145", x + 20, y + 90, 0xAAAAAA, true);
        context.drawText(textRenderer, "§7为 Chocolate Server TaCZ 子服设计", x + 20, y + 110, 0xAAAAAA, true);
        // 按钮位于文字下方（见 init 中 aboutBtnX/y 布局）
    }

    // ==================== 关于页面按钮行为 ====================

    private void openUrl(String url) {
        Util.getOperatingSystem().open(url);
    }

    /** 播放模组内置音效（assets/cstmm/sound/<name>.ogg，经 sounds.json 注册） */
    private void playModSound(String name) {
        MinecraftClient.getInstance().getSoundManager().play(
                PositionedSoundInstance.master(SoundEvent.of(Identifier.of("cstmm", name)), 1.0f));
    }

    /** 以玩家本人身份发送聊天消息（等效于玩家在聊天栏主动发送） */
    static void sendChatMessage(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null && client.getNetworkHandler() != null) {
            client.getNetworkHandler().sendChatMessage(message);
        }
    }

    /** "千万别点"：五选一随机行为（B 站视频 / otto / 遗言崩溃 / 警报音 / 猫娘发言） */
    private void triggerSecretAction() {
        switch (ThreadLocalRandom.current().nextInt(5)) {
            case 0 -> openUrl("https://www.bilibili.com/video/BV1GJ411x7h7");
            case 1 -> playModSound("otto");
            case 2 -> MinecraftClient.getInstance().setScreen(new LastWordsScreen());
            case 3 -> playModSound("wt_rwr");
            default -> sendChatMessage("我是小猫娘喵~");
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (selectedTab == 1) {
            int contentHeight = this.height - HEADER_HEIGHT - 20;
            int totalHeight = (int) Math.ceil(mapCards.size() / (double) getCardsPerRow()) * (CARD_HEIGHT + CARD_SPACING);
            if (totalHeight > contentHeight) {
                // 滚轮向下（verticalAmount < 0）= 查看下方内容 = 内容上移 = scrollOffset 增加
                scrollOffset = (int) Math.max(0, Math.min(scrollOffset - verticalAmount * 20, totalHeight - contentHeight));
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    // ==================== 卡片背景纹理 ====================

    /** 已解码的卡片背景纹理（含原始图像尺寸，供 cover 等比缩放计算） */
    private record CardTexture(Identifier id, int width, int height) {}

    /** Base64 背景纹理缓存（key 为原始 Base64 字符串；解码失败缓存 null，避免每帧重试刷日志） */
    private static final Map<String, CardTexture> BG_TEXTURE_CACHE = new HashMap<>();
    private static int dynamicTextureIndex = 0;
    private static CardTexture quickCardTexture = null;
    private static boolean quickTextureLoadFailed = false;

    private static CardTexture decodeBase64Texture(String base64) {
        if (base64 == null || base64.isEmpty()) return null;
        if (BG_TEXTURE_CACHE.containsKey(base64)) return BG_TEXTURE_CACHE.get(base64);
        CardTexture tex = null;
        try {
            NativeImage image = NativeImage.read(new ByteArrayInputStream(Base64.getDecoder().decode(base64)));
            tex = registerDynamicTexture(image);
        } catch (Exception e) {
            Cstmm.LOGGER.warn("[CSTMM - MatchMenuScreen] Failed to decode card background image, using placeholder", e);
        }
        BG_TEXTURE_CACHE.put(base64, tex);
        return tex;
    }

    /** 加载内置的快速匹配卡片背景（jar 内 /card_bg/quick.png），失败只警告一次并回退纯色 */
    private static CardTexture getQuickCardTexture() {
        if (quickCardTexture != null || quickTextureLoadFailed) return quickCardTexture;
        try (InputStream in = MatchMenuScreen.class.getResourceAsStream("/card_bg/quick.png")) {
            if (in == null) {
                Cstmm.LOGGER.warn("[CSTMM - MatchMenuScreen] /card_bg/quick.png not found in jar, using placeholder");
            } else {
                quickCardTexture = registerDynamicTexture(NativeImage.read(in));
            }
        } catch (Exception e) {
            Cstmm.LOGGER.warn("[CSTMM - MatchMenuScreen] Failed to load quick card background", e);
        }
        quickTextureLoadFailed = quickCardTexture == null;
        return quickCardTexture;
    }

    private static CardTexture registerDynamicTexture(NativeImage image) {
        NativeImageBackedTexture texture = new NativeImageBackedTexture(image);
        Identifier id = Identifier.of("cstmm", "dynamic/card_bg_" + (dynamicTextureIndex++));
        MinecraftClient.getInstance().getTextureManager().registerTexture(id, texture);
        return new CardTexture(id, image.getWidth(), image.getHeight());
    }

    private static class SidebarButton {
        String label;
        int id;
        SidebarButton(String label, int id) {
            this.label = label;
            this.id = id;
        }
    }

    private static class MapCardData {
        String id;
        String displayName;
        String mapType;
        String backgroundBase64;
        MapCardData(String id, String displayName, String mapType, String backgroundBase64) {
            this.id = id;
            this.displayName = displayName;
            this.mapType = mapType;
            this.backgroundBase64 = backgroundBase64;
        }
    }
}