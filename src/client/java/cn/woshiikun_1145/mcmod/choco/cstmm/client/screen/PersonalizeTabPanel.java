package cn.woshiikun_1145.mcmod.choco.cstmm.client.screen;

import cn.woshiikun_1145.mcmod.choco.cstmm.client.cache.ClientConfig;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.cache.FaceCache;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.util.Base64ImageDecoder.CardTexture;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.util.FaceImageCache;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.PlayerProfile;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.payload.SetFacePayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

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

    /** 头像绑定平台："qq" / "bili" */
    private String selectedType = "qq";
    /** 已预览待保存的头像绑定（预览框优先展示；保存后清空回退服务端下发的 own 绑定） */
    private String pendingType = "";
    private String pendingId = "";
    /** 状态提示行（含 § 色号），保存/清除结果就地反馈 */
    private String statusMsg = "§7选择平台并输入账号 ID，预览确认后保存";

    /** 本帧绘制的按钮（id, 区域），供鼠标命中 */
    private final List<Btn> buttons = new ArrayList<>();
    private record Btn(String id, int x, int y, int w, int h) {}

    public PersonalizeTabPanel(MatchMenuScreen menu) {
        this.menu = menu;
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
    }

    /** 界面退出时从 Screen children 物理摘除文本控件（防"框框"残留在已退出的界面上） */
    public void disposeWidgets() {
        if (idField != null) menu.removeTextField(idField);
        if (hexField != null) menu.removeTextField(hexField);
        idField = null;
        hexField = null;
        pendingType = "";
        pendingId = "";
    }

    // ==================== 绘制 ====================

    /**
     * 【作用】个性化页主渲染：主题色区（色板 + 自定义 HEX）+ 头像区（预览/平台/ID/预览按钮/保存/清除）；
     * 每帧先清空按钮命中表再重填。
     * 【被谁使用】MatchMenuScreen#drawContent（selectedTab == 6 时委托）。
     */
    public void draw(DrawContext context, int x, int y, int width, int height, int mouseX, int mouseY) {
        TextRenderer tr = menu.font();
        if (idField == null) return; // 控件已被摘除（界面退出流程），等待 initWidgets 重建
        buttons.clear();

        // 页面标题跟随主题色（本页设置）
        context.drawText(tr, "🎨 个性化", x + 20, y + 14, ClientConfig.getThemeColorArgb(), true);

        // ===== 主题色 =====
        context.drawText(tr, "§7界面主题色", x + 20, y + 36, 0xAAAAAA, true);
        int swatchY = y + 48;
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
        // 自定义 HEX
        int hexY = y + 80;
        context.drawText(tr, "§7自定义 HEX:", x + 20, hexY + 4, 0xAAAAAA, true);
        placeField(hexField, x + 100, hexY, 70);
        addBtn(context, tr, "hexApply", "§f应用", x + 176, hexY - 1, 50, 18, mouseX, mouseY, false);
        if (!"FFFFFF".equalsIgnoreCase(current)) {
            addBtn(context, tr, "hexReset", "§7恢复默认", x + 232, hexY - 1, 70, 18, mouseX, mouseY, false);
        }

        // ===== 头像 =====
        context.drawText(tr, "§7头像（仅支持 QQ 或 B站 账号）", x + 20, y + 110, 0xAAAAAA, true);
        int previewY = y + 126;
        // 预览优先展示待保存的绑定，否则展示服务端下发的自己的绑定
        String previewType = !pendingType.isEmpty() ? pendingType : FaceCache.getOwnType();
        String previewId = !pendingType.isEmpty() ? pendingId : FaceCache.getOwnId();
        drawFacePreview(context, tr, x + 20, previewY, 56, previewType, previewId);

        int colX = x + 88;
        addBtn(context, tr, "type:qq", selectedType.equals("qq") ? "§a✔ QQ" : "§7QQ", colX, previewY, 64, 18, mouseX, mouseY, false);
        addBtn(context, tr, "type:bili", selectedType.equals("bili") ? "§a✔ 哔哩哔哩" : "§7哔哩哔哩", colX + 70, previewY, 84, 18, mouseX, mouseY, false);
        placeField(idField, colX, previewY + 26, 154);
        addBtn(context, tr, "preview", "§e预览", colX, previewY + 50, 46, 18, mouseX, mouseY, false);
        addBtn(context, tr, "save", "§a保存头像", colX + 52, previewY + 50, 68, 18, mouseX, mouseY, false);
        addBtn(context, tr, "clear", "§c清除头像", colX + 126, previewY + 50, 68, 18, mouseX, mouseY, false);

        context.drawText(tr, statusMsg, colX, previewY + 74, 0xFFFFFF, true);
        context.drawText(tr, "§8保存后头像会显示在战队成员列表、队列页与履历页",
                x + 20, Math.min(previewY + 94, y + height - 12), 0x666666, true);
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

    // 在布局中定位并显示一个输入框（面板文本框是 Screen children，位置由绘制时指定）
    private void placeField(TextFieldWidget field, int x, int y, int w) {
        field.visible = true;
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
            statusMsg = "§a主题色已更新";
        } else if (id.equals("hexApply")) {
            String hex = hexField.getText().trim();
            if (!hex.matches("[0-9a-fA-F]{6}")) {
                statusMsg = "§cHEX 格式错误，应为 6 位十六进制（如 FFAA00）";
                return;
            }
            ClientConfig.setThemeColor(hex);
            statusMsg = "§a主题色已更新";
        } else if (id.equals("hexReset")) {
            ClientConfig.setThemeColor(ClientConfig.DEFAULT_THEME_COLOR);
            hexField.setText(ClientConfig.getThemeColor());
            statusMsg = "§a已恢复默认主题色";
        } else if (id.startsWith("type:")) {
            selectedType = id.substring(5);
            statusMsg = "§7已选择 " + (selectedType.equals("qq") ? "QQ" : "哔哩哔哩")
                    + "，输入账号 ID 后预览/保存";
        } else if (id.equals("preview")) {
            String err = PlayerProfile.validateAvatarBinding(selectedType, idField.getText().trim());
            if (err != null) {
                statusMsg = err;
                return;
            }
            pendingType = selectedType;
            pendingId = idField.getText().trim();
            statusMsg = "§7预览加载中（B站需先解析直链，稍候显示）...";
        } else if (id.equals("save")) {
            String value = idField.getText().trim();
            // 与服务端同规则的预校验，就地拦截无效输入
            String err = PlayerProfile.validateAvatarBinding(selectedType, value);
            if (err != null) {
                statusMsg = err;
                return;
            }
            // 只上报头像绑定（平台 + 账号 ID），服务端广播给其他玩家，图片由各客户端自行获取
            ClientPlayNetworking.send(new SetFacePayload(selectedType, value));
            pendingType = "";
            pendingId = "";
            statusMsg = "§7已提交保存，服务器确认后生效";
        } else if (id.equals("clear")) {
            ClientPlayNetworking.send(new SetFacePayload("", ""));
            pendingType = "";
            pendingId = "";
            statusMsg = "§7已请求清除头像，服务器确认后生效";
        }
    }
}
