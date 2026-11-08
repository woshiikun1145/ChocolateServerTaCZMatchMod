package cn.woshiikun_1145.mcmod.choco.cstmm.client.screen;

import cn.woshiikun_1145.mcmod.choco.cstmm.client.cache.ClientConfig;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.cache.ConfigDataCache;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.cache.FaceCache;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.music.MvpMusicCache;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.music.MvpMusicIndex;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.music.MvpMusicPlayer;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.network.ClientNetworkHandler;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.util.ToastManager;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.util.Base64ImageDecoder.CardTexture;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.util.FaceImageCache;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.PlayerProfile;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.payload.MatchActionPayload;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.payload.SetFacePayload;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.payload.SetMvpMusicPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleConsumer;

/**
 * 【作用】主菜单"个性化"标签页面板：编辑界面主题色（预设色板 + 自定义 HEX，写入
 *         config/cstmm/client/config.json 即时生效）与绑定自己 QQ/B站账号头像
 *         （选平台 + 输入账号 ID，保存发 SetFacePayload 到服务端写入档案文件
 *         config/cstmm/data/players/<uuid>.json 的 avatarType/avatarId 字段；服务端只存/发绑定，
 *         头像图片由各客户端按绑定自行获取——预览经 FaceImageCache 本地解析）。
 * 【被谁使用】仅 MatchMenuScreen 使用（initWidgets/draw/mouseClicked/hideWidgets/disposeWidgets）。
 * 文本框为 Screen 子控件；按钮/色板为手动绘制 + 手动命中（与 ClanTabPanel 同风格）。
 */
@Environment(EnvType.CLIENT)
public class PersonalizeTabPanel {

    private static final int BORDER = 0x44FFFFFF;
    private static final int BTN = 0xFF3A3A3A;
    private static final int BTN_HOVER = 0xFF4E4E4E;

    /** 预设主题色板（6 位 hex RGB），首项为模组默认橙 */
    private static final String[] PRESET_COLORS = {
            "FFAA00", "FF5555", "FF69B4", "55FF55", "55FFFF", "5555FF", "FFD700", "FFFFFF"
    };

    private final MatchMenuScreen menu;

    // 文本控件（MatchMenuScreen.init 里创建并加入 children）
    TextFieldWidget idField;
    TextFieldWidget hexField;

    // ===== 控制区（音量/下载线程）控件：滑块 + 数值输入框（Screen children 由面板控制可见/位置） =====
    private RangedSlider soundVolumeSlider;
    private TextFieldWidget soundVolumeField;
    private RangedSlider musicVolumeSlider;
    private TextFieldWidget musicVolumeField;
    private RangedSlider threadsSlider;
    private TextFieldWidget threadsField;
    // 最近一次写入配置的整数百分比（拖动/输入去重，避免每帧重复落盘与回写循环）
    private double lastSavedSoundPct = -1;
    private double lastSavedMusicPct = -1;
    private int lastSavedThreads = -1;

    /** 头像绑定平台："qq" / "bili" */
    private String selectedType = "qq";
    /** 已预览待保存的头像绑定（预览框优先展示；保存后清空回退服务端下发的 own 绑定） */
    private String pendingType = "";
    private String pendingId = "";
    /** 状态提示行（含 § 色号），保存/清除结果就地反馈；5 秒后自动消失 */
    private String statusMsg = "§7选择平台并输入账号 ID，预览确认后保存";
    private long statusMsgAt = System.currentTimeMillis();

    /** MVP 音乐区状态提示（与头像区提示分开，避免互相覆盖）；5 秒后自动消失 */
    private String musicStatus = "";
    private long musicStatusAt = 0;

    /** 更新头像区状态提示（记录时间供 5 秒自动消失） */
    private void setStatus(String msg) {
        statusMsg = msg;
        statusMsgAt = System.currentTimeMillis();
    }

    /** 更新音乐区状态提示（记录时间供 5 秒自动消失） */
    private void setMusicStatus(String msg) {
        musicStatus = msg;
        musicStatusAt = System.currentTimeMillis();
    }
    /** MVP 音乐列表页码（每页 5 条） */
    private int musicPage = 0;
    private static final int MUSIC_PAGE_SIZE = 5;

    // 滚动偏移与滚动范围（内容实际高度每帧测量后钳制）；clip 为当前内容区可视边界（控件越界自动隐藏）
    private int scrollOffset = 0;
    private int lastMaxScroll = 0;
    private int clipTop = Integer.MIN_VALUE;
    private int clipBottom = Integer.MAX_VALUE;

    /** 本帧绘制的按钮（id, 区域），供鼠标命中 */
    private final List<Btn> buttons = new ArrayList<>();
    private record Btn(String id, int x, int y, int w, int h) {}

    public PersonalizeTabPanel(MatchMenuScreen menu) {
        this.menu = menu;
    }

    /**
     * 【作用】切到个性化页时调用：请求自己的档案（回显已选 MVP 音乐）并按服务端配置的根 URL
     *         加载曲目清单（同 URL 已加载则不重复请求）。
     * 【被谁使用】MatchMenuScreen 的侧边栏切页回调。
     */
    public void onShow() {
        try {
            ClientPlayNetworking.send(new MatchActionPayload(MatchActionPayload.ActionType.REQUEST_PROFILE, "", 0, ""));
        } catch (Exception ignored) {
            // 未握手/未连接时发送失败：忽略，页面仍可展示本地缓存
        }
        MvpMusicIndex.ensureLoaded(ConfigDataCache.getInstance().getGlobalConfig().getMvpMusicUrl());
    }

    // ==================== 初始化 ====================

    /** 由 MatchMenuScreen.init() 调用（窗口尺寸变化时重建）；先摘除旧控件防重复累积 */
    public void initWidgets() {
        if (idField != null) disposeWidgets();
        idField = makeField(160, "QQ号/UID（纯数字）");
        idField.setMaxLength(PlayerProfile.MAX_AVATAR_ID_LENGTH);
        hexField = makeField(70, "RRGGBB");
        hexField.setMaxLength(6);
        // 回显服务端下发的自己的绑定（档案同步携带；头像绑定不在本地 config 存储）
        if (FaceCache.hasOwn()) {
            selectedType = "bili".equals(FaceCache.getOwnType()) ? "bili" : "qq";
            idField.setText(FaceCache.getOwnId());
        }
        hexField.setText(ClientConfig.getThemeColor());

        // 音量控制（0~400%）与下载线程数（1~32）：滑块拖动与输入框输入双通道，即时生效并落盘
        lastSavedSoundPct = Math.round(ClientConfig.getSoundEffectsVolume() * 100);
        soundVolumeSlider = new RangedSlider(0, 400, "%", lastSavedSoundPct, v -> {
            if (v == lastSavedSoundPct) return;
            lastSavedSoundPct = v;
            ClientConfig.setSoundEffectsVolume((float) (v / 100.0));
            soundVolumeField.setText(String.valueOf((long) v));
        });
        menu.addPanelWidget(soundVolumeSlider);
        soundVolumeField = makeField(44, "0~400");
        soundVolumeField.setMaxLength(4);
        soundVolumeField.setText(String.valueOf((long) lastSavedSoundPct));
        soundVolumeField.setChangedListener(s -> applyVolumeText(s, true));

        lastSavedMusicPct = Math.round(ClientConfig.getMvpMusicVolume() * 100);
        musicVolumeSlider = new RangedSlider(0, 400, "%", lastSavedMusicPct, v -> {
            if (v == lastSavedMusicPct) return;
            lastSavedMusicPct = v;
            ClientConfig.setMvpMusicVolume((float) (v / 100.0));
            musicVolumeField.setText(String.valueOf((long) v));
        });
        menu.addPanelWidget(musicVolumeSlider);
        musicVolumeField = makeField(44, "0~400");
        musicVolumeField.setMaxLength(4);
        musicVolumeField.setText(String.valueOf((long) lastSavedMusicPct));
        musicVolumeField.setChangedListener(s -> applyVolumeText(s, false));

        lastSavedThreads = ClientConfig.getDownloadThreads();
        threadsSlider = new RangedSlider(1, 32, "", lastSavedThreads, v -> {
            int n = (int) v;
            if (n == lastSavedThreads) return;
            lastSavedThreads = n;
            ClientConfig.setDownloadThreads(n);
            MvpMusicCache.setDownloadThreads(n);
            threadsField.setText(String.valueOf(n));
        });
        menu.addPanelWidget(threadsSlider);
        threadsField = makeField(44, "1~32");
        threadsField.setMaxLength(2);
        threadsField.setText(String.valueOf(lastSavedThreads));
        threadsField.setChangedListener(this::applyThreadsText);

        hideWidgets();
    }

    // 创建文本输入框（默认隐藏、由绘制时定位），并经宿主 Screen 加入 children
    private TextFieldWidget makeField(int width, String hint) {
        TextFieldWidget field = new TextFieldWidget(menu.font(), 0, 0, width, 16, Text.literal(hint));
        menu.addTextField(field);
        field.visible = false;
        return field;
    }

    /** 非个性化页渲染帧由 MatchMenuScreen 调用：隐藏文本控件（Screen children 不隐藏会画到其他标签页上） */
    public void hideWidgets() {
        if (idField != null) idField.visible = false;
        if (hexField != null) hexField.visible = false;
        hideVolumeWidgets();
    }

    /** 音量控件默认隐藏，仅控制区实际绘制时显示（避免小窗口被裁剪时残留可点击的隐形控件） */
    private void hideVolumeWidgets() {
        if (soundVolumeSlider != null) soundVolumeSlider.visible = false;
        if (soundVolumeField != null) soundVolumeField.visible = false;
        if (musicVolumeSlider != null) musicVolumeSlider.visible = false;
        if (musicVolumeField != null) musicVolumeField.visible = false;
        if (threadsSlider != null) threadsSlider.visible = false;
        if (threadsField != null) threadsField.visible = false;
    }

    /** 界面退出时从 Screen children 物理摘除文本控件（防"框框"残留在已退出的界面上） */
    public void disposeWidgets() {
        if (idField != null) menu.removeTextField(idField);
        if (hexField != null) menu.removeTextField(hexField);
        if (soundVolumeSlider != null) menu.removePanelWidget(soundVolumeSlider);
        if (soundVolumeField != null) menu.removeTextField(soundVolumeField);
        if (musicVolumeSlider != null) menu.removePanelWidget(musicVolumeSlider);
        if (musicVolumeField != null) menu.removeTextField(musicVolumeField);
        if (threadsSlider != null) menu.removePanelWidget(threadsSlider);
        if (threadsField != null) menu.removeTextField(threadsField);
        idField = null;
        hexField = null;
        soundVolumeSlider = null;
        soundVolumeField = null;
        musicVolumeSlider = null;
        musicVolumeField = null;
        threadsSlider = null;
        threadsField = null;
        lastSavedSoundPct = -1;
        lastSavedMusicPct = -1;
        lastSavedThreads = -1;
        pendingType = "";
        pendingId = "";
    }

    // ==================== 绘制 ====================

    /**
     * 【作用】个性化页主渲染：主题色区（色板 + 自定义 HEX）+ 头像区 + MVP 音乐区 + 控制区。
     *         支持滚轮滚动：内容整体按 scrollOffset 上移绘制并以内容区裁剪，控件（输入框/滑块）
     *         随内容定位、滚出可视区自动隐藏；每帧按内容实际高度钳制偏移并刷新按钮命中表。
     * 【被谁使用】MatchMenuScreen#drawContent（selectedTab == 6 时委托）。
     */
    public void draw(DrawContext context, int x, int y, int width, int height, int mouseX, int mouseY) {
        TextRenderer tr = menu.font();
        if (idField == null) return; // 控件已被摘除（界面退出流程），等待 initWidgets 重建
        buttons.clear();

        // 操作成功/校验不通过/失败类状态提示 5 秒后自动消失
        long now = System.currentTimeMillis();
        if (!statusMsg.isEmpty() && now - statusMsgAt > 5000) statusMsg = "";
        if (!musicStatus.isEmpty() && now - musicStatusAt > 5000) musicStatus = "";

        hideVolumeWidgets(); // 控制区实际绘制时再显示（滚出可视区不留隐形控件）

        // 滚动后的内容基准 Y（内容整体上移 scrollOffset 像素）
        int baseY = y - scrollOffset;
        clipTop = y;
        clipBottom = y + height;

        // 内容裁剪：滚动后的内容只在个性化页可视区域内渲染（不越界画到标题栏/侧边栏）
        context.enableScissor(x, y, x + width, y + height);

        // 页面标题跟随主题色（本页设置）
        context.drawText(tr, "个性化", x + 20, baseY + 14, ClientConfig.getThemeColorArgb(), true);

        // ===== 主题色 =====
        context.drawText(tr, "§7界面主题色", x + 20, baseY + 36, 0xAAAAAA, true);
        int swatchY = baseY + 48;
        int swatchX = x + 20;
        String current = ClientConfig.getThemeColor();
        for (String hex : PRESET_COLORS) {
            int rgb = (int) (0xFF000000L | Long.parseLong(hex, 16));
            context.fill(swatchX, swatchY, swatchX + 22, swatchY + 22, rgb);
            boolean selected = hex.equalsIgnoreCase(current);
            context.drawBorder(swatchX, swatchY, 22, 22, selected ? 0xFFFFFFFF : BORDER);
            if (selected) {
                // 选中项外圈白色双描边增强辨识
                context.drawBorder(swatchX - 1, swatchY - 1, 24, 24, 0x88FFFFFF);
            }
            buttons.add(new Btn("swatch:" + hex, swatchX, swatchY, 22, 22));
            swatchX += 30;
        }
        // 自定义 HEX（应用/恢复默认按钮右对齐内容区，窄窗口不再溢出右边缘）
        int hexY = baseY + 80;
        context.drawText(tr, "§7自定义 HEX:", x + 20, hexY + 4, 0xAAAAAA, true);
        placeField(hexField, x + 100, hexY, 70);
        int hexResetX = x + width - 20 - 70;
        int hexApplyX = !"FFFFFF".equalsIgnoreCase(current) ? hexResetX - 50 - 6 : hexResetX;
        addBtn(context, tr, "hexApply", "§f应用", hexApplyX, hexY - 1, 50, 18, mouseX, mouseY, false);
        if (!"FFFFFF".equalsIgnoreCase(current)) {
            addBtn(context, tr, "hexReset", "§7恢复默认", hexResetX, hexY - 1, 70, 18, mouseX, mouseY, false);
        }

        // ===== 头像 =====
        context.drawText(tr, "§7头像（仅支持 QQ 或 B站 账号）", x + 20, baseY + 110, 0xAAAAAA, true);
        int previewY = baseY + 126;
        // 预览优先展示待保存的绑定，否则展示服务端下发的自己的绑定
        String previewType = !pendingType.isEmpty() ? pendingType : FaceCache.getOwnType();
        String previewId = !pendingType.isEmpty() ? pendingId : FaceCache.getOwnId();
        drawFacePreview(context, tr, x + 20, previewY, 56, previewType, previewId);

        // 平台/预览/保存/清除按钮右对齐内容区（窄窗口不再溢出右边缘）
        int colX = x + 88;
        int clearX = x + width - 20 - 68;
        int saveX = clearX - 68 - 6;
        int previewBtnX = saveX - 46 - 6;
        int biliX = x + width - 20 - 84;
        int qqX = biliX - 64 - 6;
        addBtn(context, tr, "type:qq", selectedType.equals("qq") ? "§a✔ QQ" : "§7QQ", qqX, previewY, 64, 18, mouseX, mouseY, false);
        addBtn(context, tr, "type:bili", selectedType.equals("bili") ? "§a✔ 哔哩哔哩" : "§7哔哩哔哩", biliX, previewY, 84, 18, mouseX, mouseY, false);
        placeField(idField, colX, previewY + 26, 154);
        addBtn(context, tr, "preview", "§e预览", previewBtnX, previewY + 50, 46, 18, mouseX, mouseY, false);
        addBtn(context, tr, "save", "§a保存头像", saveX, previewY + 50, 68, 18, mouseX, mouseY, false);
        addBtn(context, tr, "clear", "§c清除头像", clearX, previewY + 50, 68, 18, mouseX, mouseY, false);

        context.drawText(tr, statusMsg, colX, previewY + 74, 0xFFFFFF, true);
        context.drawText(tr, "§8保存后头像会显示在战队成员列表、队列页与履历页",
                x + 20, previewY + 94, 0x666666, true);

        // ===== MVP 音乐 =====
        int musicEnd = drawMusicSection(context, tr, x, width, previewY + 104, mouseX, mouseY);

        // ===== 控制（音量 / 下载） =====
        int contentBottom = drawControlSection(context, tr, x, width, musicEnd + 14, mouseX, mouseY);

        context.disableScissor();

        // 按本帧内容实际高度（相对未滚动基准测量，避免随偏移自收缩）钳制滚动偏移，并记录供滚轮钳制
        int contentHeight = contentBottom - baseY;
        lastMaxScroll = Math.max(0, contentHeight - height + 6);
        if (scrollOffset > lastMaxScroll) scrollOffset = lastMaxScroll;
        if (scrollOffset < 0) scrollOffset = 0;
    }

    /**
     * 【作用】个性化页滚轮滚动（与匹配页同口径：滚轮向下 = 查看下方内容 = 内容上移 = 偏移增加）。
     * 【被谁使用】MatchMenuScreen#mouseScrolled（selectedTab == 6）。
     */
    public void scroll(double verticalAmount) {
        scrollOffset = (int) Math.max(0, Math.min(scrollOffset - verticalAmount * 20, lastMaxScroll));
    }

    /**
     * 【作用】MVP 音乐区：展示曲目清单（每页 5 条，图标 + 作者 - 曲名）、当前已选高亮，
     *         提供停止试听/刷新/清除/翻页/选择/试听。清单数据来自 MvpMusicIndex（服务端配置的根 URL），
     *         选择结果经 SetMvpMusicPayload 写档。翻页下方显示资源预下载提示。
     *         @return 音乐区结束 Y（供下方"控制"区定位）
     * 【被谁使用】draw（个性化页渲染）。
     */
    private int drawMusicSection(DrawContext context, TextRenderer tr, int x, int width,
                                 int musicY, int mouseX, int mouseY) {
        context.drawText(tr, "§7MVP 音乐（成为 MVP 时播放）", x + 20, musicY, 0xAAAAAA, true);

        // 顶部按钮行（停止试听/刷新列表/清除选择）：内容区足够宽时与标题同行，
        // 窄窗口换到标题下方独立一行（避免与标题/头像提示行重叠）；列表起始 Y 随之调整
        int topBtnX = x + width - 262;
        int leftTextEnd = x + 20 + Math.max(
                tr.getWidth("§7MVP 音乐（成为 MVP 时播放）"),
                tr.getWidth("§8保存后头像会显示在战队成员列表、队列页与履历页"));
        boolean roomOnRow = topBtnX - leftTextEnd >= 10;
        int topBtnY = roomOnRow ? musicY - 2 : musicY + 12;
        int listY = musicY + (roomOnRow ? 16 : 36);
        addBtn(context, tr, "musicStop", "停止试听", topBtnX, topBtnY, 64, 16, mouseX, mouseY,
                !MvpMusicPlayer.isPlaying());
        addBtn(context, tr, "musicReload", "刷新列表", topBtnX + 70, topBtnY, 70, 16, mouseX, mouseY, false);
        addBtn(context, tr, "musicClear", "§c清除选择", topBtnX + 146, topBtnY, 68, 16, mouseX, mouseY, false);

        PlayerProfile profile = ClientNetworkHandler.PlayerProfileCache.getInstance().getProfile();
        String selected = profile == null ? "" : profile.getMvpMusicName();

        List<MvpMusicIndex.Track> tracks = MvpMusicIndex.getTracks();
        int pageCount = Math.max(1, (tracks.size() + MUSIC_PAGE_SIZE - 1) / MUSIC_PAGE_SIZE);
        if (musicPage >= pageCount) musicPage = pageCount - 1;
        if (musicPage < 0) musicPage = 0;

        if (tracks.isEmpty()) {
            String tip = MvpMusicIndex.isLoading()
                    ? "§8正在加载曲目清单..."
                    : MvpMusicIndex.getError().isEmpty()
                    ? "§8暂无曲目（请确认服务端已配置 MVP音乐URL）"
                    : "§c曲目清单加载失败（详情见提示栏）";
            // 状态提示自动换行（窄窗口不截断）
            int tipY = listY + 4;
            for (net.minecraft.text.OrderedText line : tr.wrapLines(Text.literal(tip), Math.max(40, width - 40))) {
                context.drawText(tr, line, x + 20, tipY, 0xFFFFFF, true);
                tipY += 10;
            }
            return tipY + 8;
        }

        int iconSize = 16;
        int rowH = 22;
        int from = musicPage * MUSIC_PAGE_SIZE;
        int to = Math.min(tracks.size(), from + MUSIC_PAGE_SIZE);
        int btnX = x + width - 110;
        for (int i = from; i < to; i++) {
            MvpMusicIndex.Track track = tracks.get(i);
            int rowY = listY + (i - from) * rowH;
            boolean isSelected = track.musicName().equals(selected);
            if (isSelected) {
                context.fill(x + 18, rowY - 1, x + width - 8, rowY + rowH - 3, 0x33FFAA00);
            }
            // 图标（优先读 cover/ 磁盘缓存；未下载完成时留空）
            CardTexture icon = MvpMusicCache.iconTexture(track);
            if (icon != null) {
                context.drawTexture(icon.id(), x + 20, rowY, iconSize, iconSize,
                        0f, 0f, icon.width(), icon.height(), icon.width(), icon.height());
            } else {
                context.fill(x + 20, rowY, x + 20 + iconSize, rowY + iconSize, 0xFF222222);
                context.drawBorder(x + 20, rowY, iconSize, iconSize, BORDER);
            }
            String label = (isSelected ? "§a" : "§f") + track.display();
            int textW = Math.max(40, btnX - (x + 42) - 6);
            int fullW = tr.getWidth(label);
            if (fullW > textW) {
                // 曲名放不下：向左循环滚动显示（marquee，可用区外裁剪）
                int period = fullW + 24;
                int shift = (int) ((System.currentTimeMillis() / 40) % period);
                context.enableScissor(x + 42, rowY - 2, x + 42 + textW, rowY + 14);
                context.drawText(tr, label, x + 42 - shift, rowY + 4, 0xFFFFFF, true);
                context.drawText(tr, label, x + 42 - shift + period, rowY + 4, 0xFFFFFF, true);
                context.disableScissor();
            } else {
                context.drawText(tr, label, x + 42, rowY + 4, 0xFFFFFF, true);
            }

            addBtn(context, tr, "select:" + i, isSelected ? "§a已选" : "§f选择",
                    btnX, rowY, 50, 16, mouseX, mouseY, false);
            addBtn(context, tr, "preview:" + i, "§e试听",
                    btnX + 54, rowY, 44, 16, mouseX, mouseY, false);
        }

        // 翻页（仅在多页时显示）
        int navY = listY + MUSIC_PAGE_SIZE * rowH + 2;
        if (pageCount > 1) {
            addBtn(context, tr, "musicPrev", "§7上一页", x + 20, navY, 60, 16, mouseX, mouseY, musicPage <= 0);
            addBtn(context, tr, "musicNext", "§7下一页", x + 84, navY, 60, 16, mouseX, mouseY, musicPage >= pageCount - 1);
            context.drawText(tr, "§8" + (musicPage + 1) + "/" + pageCount, x + 150, navY + 4, 0x888888, true);
        }

        // 翻页下方状态区：资源预下载提示（索引就绪后后台自动缓存）+ 操作状态提示
        int statusY = navY + 18;
        if (MvpMusicCache.isPrefetching()) {
            context.drawText(tr, "§8正在下载资源，这可能需要一点时间……", x + 20, statusY, 0xAAAAAA, true);
            statusY += 12;
        }
        if (!musicStatus.isEmpty()) {
            context.drawText(tr, musicStatus, x + 20, statusY, 0xFFFFFF, true);
        }
        return statusY + 12;
    }

    // ==================== 控制区（音量） ====================

    /**
     * 【作用】控制区：模组音效音量、MVP 音乐音量（各一行，0~400%）与 MVP 资源下载线程数（1~32），
     *         每行 = 标签 + 滑块 + 数值输入框，拖动/输入即时生效并落盘 config/cstmm/client/config.json。
     *         音效音量经 SoundSystemMixin 缩放全部 cstmm 命名空间 MC 音效；
     *         MVP 音乐音量作用于 JLayer 播放器；下载线程数实时调整 MvpMusicCache 下载池并发。
     *         @return 控制区结束 Y（供滚动范围测量）
     * 【被谁使用】draw（个性化页渲染，位于 MVP 音乐区下方）。
     */
    private int drawControlSection(DrawContext context, TextRenderer tr, int x, int width,
                                   int ctrlY, int mouseX, int mouseY) {
        context.drawText(tr, "§7控制（音量 / 下载）", x + 20, ctrlY, 0xAAAAAA, true);
        int row1Y = ctrlY + 16;
        placeControlRow(context, tr, x, width, row1Y, "模组音效音量:", soundVolumeSlider, soundVolumeField, "%");
        placeControlRow(context, tr, x, width, row1Y + 24, "MVP 音乐音量:", musicVolumeSlider, musicVolumeField, "%");
        placeControlRow(context, tr, x, width, row1Y + 48, "下载线程数:", threadsSlider, threadsField, "");
        context.drawText(tr, "§8音量范围 0~400%（100% 为原始音量）；线程数 1~32，改动即时生效",
                x + 20, row1Y + 74, 0x666666, true);
        return row1Y + 86;
    }

    /** 控制区单行布局：标签 + 滑块（自适应宽度）+ 数值输入框 + 后缀（如 "%"） */
    private void placeControlRow(DrawContext context, TextRenderer tr, int x, int width, int rowY,
                                 String label, RangedSlider slider, TextFieldWidget field, String suffix) {
        if (slider == null || field == null) return;
        context.drawText(tr, "§f" + label, x + 20, rowY + 4, 0xFFFFFF, true);
        int sliderX = x + 132;
        int fieldX = x + width - 86;
        // 滑块与输入框都是 Screen children（渲染不受 scissor 裁剪），滚出可视区必须隐藏
        boolean inView = rowY >= clipTop && rowY + 16 <= clipBottom;
        slider.visible = inView;
        slider.setX(sliderX);
        slider.setY(rowY);
        // 不强制最小 80：窄窗口按剩余空间压缩，避免滑块越过右侧输入框造成重叠
        slider.setWidth(Math.max(24, fieldX - sliderX - 10));
        slider.setHeight(16);
        placeField(field, fieldX, rowY + 1, 44);
        if (inView && suffix != null && !suffix.isEmpty()) {
            context.drawText(tr, "§7" + suffix, fieldX + 47, rowY + 5, 0xAAAAAA, true);
        }
    }

    /** 百分比输入框回调：解析 0~400 整数写入配置并同步滑块（非法/越界输入不落盘） */
    private void applyVolumeText(String s, boolean soundVolume) {
        if (s.isBlank()) return;
        try {
            int pct = Integer.parseInt(s.trim());
            if (pct < 0 || pct > 400) return;
            if (soundVolume) {
                lastSavedSoundPct = pct;
                ClientConfig.setSoundEffectsVolume(pct / 100.0f);
                if (soundVolumeSlider != null) soundVolumeSlider.setValue(pct);
            } else {
                lastSavedMusicPct = pct;
                ClientConfig.setMvpMusicVolume(pct / 100.0f);
                if (musicVolumeSlider != null) musicVolumeSlider.setValue(pct);
            }
        } catch (NumberFormatException ignored) {}
    }

    /** 下载线程数输入框回调：解析 1~32 整数写入配置并实时调整下载池并发 */
    private void applyThreadsText(String s) {
        if (s.isBlank()) return;
        try {
            int n = Integer.parseInt(s.trim());
            if (n < 1 || n > 32) return;
            lastSavedThreads = n;
            ClientConfig.setDownloadThreads(n);
            MvpMusicCache.setDownloadThreads(n);
            if (threadsSlider != null) threadsSlider.setValue(n);
        } catch (NumberFormatException ignored) {}
    }

    /**
     * 区间整数滑块：内部值域 0~1 线性映射 [min, max]，拖动时经回调上报四舍五入后的整数值；
     * 后缀（如 "%"）仅用于滑块文案展示。面板侧按 lastSaved 去重，避免逐像素重复落盘。
     */
    private static final class RangedSlider extends SliderWidget {
        private final double min;
        private final double max;
        private final String suffix;
        private final DoubleConsumer onValue;

        private RangedSlider(double min, double max, String suffix, double initialValue, DoubleConsumer onValue) {
            super(0, 0, 120, 16, Text.literal(""), clamp01((initialValue - min) / (max - min)));
            this.min = min;
            this.max = max;
            this.suffix = suffix == null ? "" : suffix;
            this.onValue = onValue;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Text.literal(displayValue() + suffix));
        }

        @Override
        protected void applyValue() {
            onValue.accept(currentValue());
        }

        private double currentValue() {
            return Math.round(min + this.value * (max - min));
        }

        private String displayValue() {
            return String.valueOf((long) currentValue());
        }

        /** 由输入框反向同步滑块位置（不触发 applyValue，避免回写循环） */
        private void setValue(double v) {
            double nv = clamp01((v - min) / (max - min));
            if (Math.abs(nv - this.value) < 1e-6) return;
            this.value = nv;
            updateMessage();
        }

        private static double clamp01(double v) {
            return Math.max(0.0, Math.min(1.0, v));
        }
    }

    // 头像预览框：深色底 + 边框 + 纹理（按绑定自行获取：QQ 直链立即下载，B站解析后下载，完成前显示占位）
    private void drawFacePreview(DrawContext context, TextRenderer tr, int x, int y, int size, String type, String id) {
        context.fill(x, y, x + size, y + size, 0xFF111111);
        CardTexture tex = FaceImageCache.getTexture(type, id);
        if (tex != null) {
            context.drawTexture(tex.id(), x, y, size, size, 0f, 0f, tex.width(), tex.height(), tex.width(), tex.height());
        } else if (type == null || type.isEmpty()) {
            context.drawCenteredTextWithShadow(tr, "§8无", x + size / 2, y + size / 2 - 4, 0xFFFFFF);
        }
        context.drawBorder(x, y, size, size, BORDER);
    }

    // 在布局中定位并显示一个输入框（面板文本框是 Screen children，位置由绘制时指定；
    // Screen children 渲染不经过裁剪，滚出可视区需隐藏防画到其他区域/残留可点击）
    private void placeField(TextFieldWidget field, int x, int y, int w) {
        field.visible = y >= clipTop && y + 16 <= clipBottom;
        field.setPosition(x, y);
        field.setWidth(w);
    }

    // 手动按钮：底色/边框/居中文案（禁用置灰），hover 高亮；非禁用时登记到命中表
    private void addBtn(DrawContext context, TextRenderer tr, String id, String label,
                        int x, int y, int w, int h, int mouseX, int mouseY, boolean disabled) {
        boolean hover = !disabled && mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h;
        context.fill(x, y, x + w, y + h, disabled ? 0xFF262626 : hover ? BTN_HOVER : BTN);
        context.drawBorder(x, y, w, h, disabled ? 0xFF333333 : 0xFF666666);
        context.drawCenteredTextWithShadow(tr, label, x + w / 2, y + (h - 8) / 2, disabled ? 0x777777 : 0xFFFFFF);
        if (!disabled) buttons.add(new Btn(id, x, y, w, h));
    }

    // ==================== 交互 ====================

    /**
     * 【作用】个性化页按钮/色板点击命中：按本帧 buttons 命中表匹配坐标并处理。
     * 【被谁使用】MatchMenuScreen#mouseClicked（selectedTab == 6 时调用）。
     */
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return false;
        for (Btn b : buttons) {
            if (mouseX >= b.x && mouseX <= b.x + b.w && mouseY >= b.y && mouseY <= b.y + b.h) {
                handleAction(b.id);
                return true;
            }
        }
        return false;
    }

    /** 按钮动作：色板/HEX 即时落盘；平台切换；头像预览（本地按绑定获取图片）与保存/清除（发 SET_FACE 绑定） */
    private void handleAction(String id) {
        if (id.startsWith("swatch:")) {
            ClientConfig.setThemeColor(id.substring(7));
            hexField.setText(ClientConfig.getThemeColor());
            setStatus("§a主题色已更新");
        } else if (id.equals("hexApply")) {
            String hex = hexField.getText().trim();
            if (!hex.matches("[0-9a-fA-F]{6}")) {
                setStatus("§cHEX 格式错误，应为 6 位十六进制（如 FFAA00）");
                return;
            }
            ClientConfig.setThemeColor(hex);
            setStatus("§a主题色已更新");
        } else if (id.equals("hexReset")) {
            ClientConfig.setThemeColor(ClientConfig.DEFAULT_THEME_COLOR);
            hexField.setText(ClientConfig.getThemeColor());
            setStatus("§a已恢复默认主题色");
        } else if (id.startsWith("type:")) {
            selectedType = id.substring(5);
            setStatus("§7已选择 " + (selectedType.equals("qq") ? "QQ" : "哔哩哔哩")
                    + "，输入账号 ID 后预览/保存");
        } else if (id.equals("preview")) {
            String err = PlayerProfile.validateAvatarBinding(selectedType, idField.getText().trim());
            if (err != null) {
                setStatus(err);
                return;
            }
            pendingType = selectedType;
            pendingId = idField.getText().trim();
            setStatus("§7预览加载中...");
        } else if (id.equals("save")) {
            String value = idField.getText().trim();
            // 与服务端同规则的预校验，就地拦截无效输入
            String err = PlayerProfile.validateAvatarBinding(selectedType, value);
            if (err != null) {
                setStatus(err);
                return;
            }
            // 只上报头像绑定（平台 + 账号 ID），服务端广播给其他玩家，图片由各客户端自行获取
            ClientPlayNetworking.send(new SetFacePayload(selectedType, value));
            pendingType = "";
            pendingId = "";
            ToastManager.show("§a已提交保存，服务器确认后生效");
        } else if (id.equals("clear")) {
            ClientPlayNetworking.send(new SetFacePayload("", ""));
            pendingType = "";
            pendingId = "";
            ToastManager.show("§a已请求清除头像，服务器确认后生效");
        } else if (id.startsWith("select:")) {
            handleMusicSelect(id.substring(7));
        } else if (id.startsWith("preview:")) {
            handleMusicPreview(id.substring(8));
        } else if (id.equals("musicStop")) {
            MvpMusicPlayer.stop();
            setMusicStatus("§7已停止播放");
        } else if (id.equals("musicClear")) {
            ClientPlayNetworking.send(new SetMvpMusicPayload(""));
            ToastManager.show("§a已请求清除 MVP 音乐，服务器确认后生效");
        } else if (id.equals("musicReload")) {
            MvpMusicIndex.reload(ConfigDataCache.getInstance().getGlobalConfig().getMvpMusicUrl());
            musicPage = 0;
            ToastManager.show("§e正在刷新曲目清单...");
        } else if (id.equals("musicPrev")) {
            if (musicPage > 0) musicPage--;
        } else if (id.equals("musicNext")) {
            int pageCount = Math.max(1, (MvpMusicIndex.getTracks().size() + MUSIC_PAGE_SIZE - 1) / MUSIC_PAGE_SIZE);
            if (musicPage + 1 < pageCount) musicPage++;
        }
    }

    // 选择曲目：按 music_name 上报服务端写入档案
    private void handleMusicSelect(String indexText) {
        MvpMusicIndex.Track track = trackAt(indexText);
        if (track == null) return;
        ClientPlayNetworking.send(new SetMvpMusicPayload(track.musicName()));
        setMusicStatus("§7已提交选择：" + track.display());
    }

    // 试听：确保资源已缓存后播放（与 MVP 播放共用播放器，单例互斥）
    private void handleMusicPreview(String indexText) {
        MvpMusicIndex.Track track = trackAt(indexText);
        if (track == null) return;
        if (!ClientConfig.isMvpMusicEnabled()) {
            setMusicStatus("§cMVP 音乐已在客户端配置中关闭");
            return;
        }
        setMusicStatus("§7正在试听：" + track.display());
        var cached = MvpMusicCache.cachedMusic(track);
        if (cached != null) {
            MvpMusicPlayer.play(cached, track.display(), ClientConfig.getMvpMusicVolume());
        } else {
            MvpMusicCache.ensureMusic(track, path -> MinecraftClient.getInstance().execute(() ->
                    MvpMusicPlayer.play(path, track.display(), ClientConfig.getMvpMusicVolume())));
        }
    }

    // 按曲目清单下标取曲目（越界返回 null）
    private MvpMusicIndex.Track trackAt(String indexText) {
        try {
            int idx = Integer.parseInt(indexText);
            List<MvpMusicIndex.Track> tracks = MvpMusicIndex.getTracks();
            return (idx >= 0 && idx < tracks.size()) ? tracks.get(idx) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
