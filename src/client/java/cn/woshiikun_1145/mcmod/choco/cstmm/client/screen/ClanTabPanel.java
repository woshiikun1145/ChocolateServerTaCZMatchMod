package cn.woshiikun_1145.mcmod.choco.cstmm.client.screen;

import cn.woshiikun_1145.mcmod.choco.cstmm.client.cache.BadgeCache;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.cache.ClanCache;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.util.Base64ImageDecoder;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.util.Base64ImageDecoder.CardTexture;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.cache.ClanCache.ClanInfo;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.cache.ClanCache.ListData.ListRow;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.cache.ClanCache.Mine;
import cn.woshiikun_1145.mcmod.choco.cstmm.manager.ClanManager;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.payload.ClanActionPayload;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.payload.ClanActionPayload.ClanAction;
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
 * 主菜单"战队"标签页面板。
 * 未加入战队：左侧 1/3 展示选中战队详情（名称/缩写/徽标/队长/人数上限/加入按钮），
 * 右侧 2/3 为战队列表（随机 10 条，可搜索）+ 底部搜索栏与创建战队按钮（弹出创建对话框）。
 * 已加入战队：顶部战队信息，中部成员列表（可滚动，队长可踢人/转让），底部退出/解散按钮。
 * 文本框为 Screen 子控件（由 MatchMenuScreen.init 创建）；按钮为手动绘制 + 手动命中。
 */
@Environment(EnvType.CLIENT)
public class ClanTabPanel {

    private static final int BORDER = 0x44FFFFFF;
    private static final int BTN = 0xFF3A3A3A;
    private static final int BTN_HOVER = 0xFF4E4E4E;

    private final MatchMenuScreen menu;

    // 文本控件（MatchMenuScreen.init 里创建并加入 children）
    TextFieldWidget searchField;
    TextFieldWidget dlgNameField;
    TextFieldWidget dlgAbbrField;
    TextFieldWidget dlgBadgeField;
    TextFieldWidget dlgLimitField;

    private boolean createDialogOpen = false;
    /** false = 创建战队对话框，true = 编辑战队信息对话框（复用同一界面） */
    private boolean dialogEditMode = false;
    private String selectedClanName = null;
    private String dialogError = "";
    private int listScroll = 0;
    private int memberScroll = 0;
    private String searchQuery = "";

    /** 本帧绘制的按钮（id, arg, 区域），供鼠标命中 */
    private final List<Btn> buttons = new ArrayList<>();
    private record Btn(String id, String arg, int x, int y, int w, int h) {}

    public ClanTabPanel(MatchMenuScreen menu) {
        this.menu = menu;
    }

    // ==================== 初始化 ====================

    /** 由 MatchMenuScreen.init() 调用（窗口尺寸变化时会重建）；先摘除旧控件防止重复累积 */
    public void initWidgets() {
        if (searchField != null) disposeWidgets();
        searchField = makeField(200, "搜索战队名/缩写/队长");
        searchField.setMaxLength(128);
        dlgNameField = makeField(240, "战队名称（≤128字）");
        dlgNameField.setMaxLength(128);
        dlgAbbrField = makeField(240, "战队缩写（≤10字符）");
        dlgAbbrField.setMaxLength(10);
        dlgBadgeField = makeField(240, "徽标 base64（可留空）");
        // TextFieldWidget 默认上限 32 字符——徽标输入框不设长度上限，可任意粘贴超大 base64；
        // 超过分片协议上限时在提交前拦截（send 上限 = 分片 64 片 × 每片 30000 字符）
        dlgBadgeField.setMaxLength(Integer.MAX_VALUE);
        dlgLimitField = makeField(240, "成员数量限制（0=无限制）");
        dlgLimitField.setMaxLength(6);
        hideAllFields();
    }

    private TextFieldWidget makeField(int width, String hint) {
        TextFieldWidget field = new TextFieldWidget(menu.font(), 0, 0, width, 16, Text.literal(hint));
        menu.addTextField(field);
        field.visible = false;
        return field;
    }

    /** 界面退出时从 Screen children 物理摘除全部文本控件（防"框框"残留在已退出的界面上） */
    public void disposeWidgets() {
        if (searchField != null) menu.removeTextField(searchField);
        if (dlgNameField != null) menu.removeTextField(dlgNameField);
        if (dlgAbbrField != null) menu.removeTextField(dlgAbbrField);
        if (dlgBadgeField != null) menu.removeTextField(dlgBadgeField);
        if (dlgLimitField != null) menu.removeTextField(dlgLimitField);
        searchField = null;
        dlgNameField = null;
        dlgAbbrField = null;
        dlgBadgeField = null;
        dlgLimitField = null;
        createDialogOpen = false;
        dialogEditMode = false;
        dialogError = "";
    }

    private void hideAllFields() {
        if (searchField != null) searchField.visible = false;
        if (dlgNameField != null) dlgNameField.visible = false;
        if (dlgAbbrField != null) dlgAbbrField.visible = false;
        if (dlgBadgeField != null) dlgBadgeField.visible = false;
        if (dlgLimitField != null) dlgLimitField.visible = false;
    }

    /** 非战队页渲染帧由 MatchMenuScreen 调用：隐藏全部文本控件（它们是 Screen children，不隐藏会画到其他标签页上） */
    public void hideWidgets() {
        hideAllFields();
    }

    /** 切到本页时请求我的战队 + 随机列表 */
    public void onShow() {
        send(ClanAction.REQUEST_MINE, "", "", "", 0);
        send(ClanAction.REQUEST_LIST, "", "", "", 0);
    }

    /** 界面退出时清理创建对话框状态（防"框框"残留），由 MatchMenuScreen.removed() 调用 */
    public void resetDialogState() {
        createDialogOpen = false;
        dialogEditMode = false;
        dialogError = "";
        if (dlgNameField != null) dlgNameField.setText("");
        if (dlgAbbrField != null) dlgAbbrField.setText("");
        if (dlgBadgeField != null) dlgBadgeField.setText("");
        if (dlgLimitField != null) dlgLimitField.setText("");
        if (searchField != null) searchField.setText("");
        searchQuery = "";
        if (menu.getFocused() != null && isDialogField(menu.getFocused())) {
            menu.setFocused(null);
        }
    }

    /** 创建/编辑对话框是否打开（供 MatchMenuScreen 拦截 ESC：先关对话框再考虑关菜单） */
    public boolean isCreateDialogOpen() {
        return createDialogOpen;
    }

    /** 关闭对话框并丢弃输入（ESC / 取消共用） */
    public void closeDialog() {
        createDialogOpen = false;
        dialogEditMode = false;
        dialogError = "";
        if (dlgNameField != null) dlgNameField.setText("");
        if (dlgAbbrField != null) dlgAbbrField.setText("");
        if (dlgBadgeField != null) dlgBadgeField.setText("");
        if (dlgLimitField != null) dlgLimitField.setText("");
        menu.setFocused(null);
    }

    /** 战队操作发送；徽标超过单包 30000 字符时自动分片上传（服务端集齐后执行动作） */
    private void send(ClanAction action, String t1, String t2, String badge, int number) {
        String b = badge == null ? "" : badge;
        if (b.length() <= ClanActionPayload.MAX_PART_CHARS) {
            ClientPlayNetworking.send(ClanActionPayload.single(action, t1, t2, b, number));
            return;
        }
        int total = (b.length() + ClanActionPayload.MAX_PART_CHARS - 1) / ClanActionPayload.MAX_PART_CHARS;
        for (int i = 0; i < total; i++) {
            int from = i * ClanActionPayload.MAX_PART_CHARS;
            int to = Math.min(from + ClanActionPayload.MAX_PART_CHARS, b.length());
            ClientPlayNetworking.send(new ClanActionPayload(action, t1, t2, b.substring(from, to), number, i, total));
        }
    }

    // ==================== 绘制 ====================

    public void draw(DrawContext context, int x, int y, int width, int height, int mouseX, int mouseY) {
        // 控件已被摘除（界面退出流程）时跳过绘制，等待 initWidgets 重建
        if (searchField == null) return;
        buttons.clear();
        Mine mine = ClanCache.getInstance().getMine();

        if (createDialogOpen) {
            hideAllFields();
            drawCreateDialog(context, x, y, width, height, mouseX, mouseY);
            return;
        }

        if (mine.inClan && mine.clan != null) {
            hideAllFields();
            drawInClan(context, x, y, width, height, mouseX, mouseY, mine);
        } else {
            // 浏览视图：隐藏创建/编辑对话框的输入框（对话框关闭后 visible 仍为 true，会残留悬浮的空框）
            hideAllFields();
            drawBrowse(context, x, y, width, height, mouseX, mouseY);
        }
    }

    // ---------- 未加入战队：详情(1/3) + 列表/搜索(2/3) ----------

    private void drawBrowse(DrawContext context, int x, int y, int width, int height, int mouseX, int mouseY) {
        TextRenderer tr = menu.font();
        int detailW = width / 3;

        // ===== 左侧详情面板 =====
        context.fill(x, y, x + detailW - 6, y + height, 0x66101018);
        context.drawBorder(x, y, detailW - 6, height, BORDER);
        ClanInfo detail = resolveSelectedClan();
        int dx = x + 12;
        int dy = y + 12;
        if (detail == null) {
            context.drawText(tr, "§7点击右侧战队查看详情", dx, y + height / 2 - 4, 0xAAAAAA, true);
        } else {
            // 徽标 64×64
            drawBadgeLarge(context, dx, dy, 64, detail.badge);
            dy += 72;
            context.drawText(tr, "§6" + detail.name, dx, dy, 0xFFFFFF, true); dy += 14;
            context.drawText(tr, "§7缩写: §e" + detail.abbr, dx, dy, 0xFFFFFF, true); dy += 14;
            context.drawText(tr, "§7队长: §f" + detail.leaderName, dx, dy, 0xFFFFFF, true); dy += 14;
            context.drawText(tr, "§7成员: §f" + detail.memberCount + (detail.limit > 0 ? "§7/" + detail.limit : "§7（无上限）"),
                    dx, dy, 0xFFFFFF, true);
            dy += 20;
            boolean full = detail.limit > 0 && detail.memberCount >= detail.limit;
            addBtn(context, tr, buttons, "join:" + detail.name, full ? "§8已满员" : "§a加入战队",
                    dx, y + height - 34, detailW - 30, 20, mouseX, mouseY, full);
        }

        // ===== 右侧列表（表格） =====
        int lx = x + detailW + 6;
        int lw = width - detailW - 6;
        int listH = height - 30;
        context.fill(lx, y, lx + lw, y + listH, 0x66101018);
        context.drawBorder(lx, y, lw, listH, BORDER);

        int rowH = 26;
        int headerH = 20;
        // 表头
        context.fill(lx + 1, y + 1, lx + lw - 1, y + headerH, 0xFF232323);
        int cx1 = lx + 10;
        int cx2 = lx + (int) (lw * 0.42);
        int cx3 = lx + (int) (lw * 0.60);
        int cx4 = lx + (int) (lw * 0.82);
        context.drawText(tr, "§7战队名称", cx1, y + 6, 0xAAAAAA, true);
        context.drawText(tr, "§7缩写", cx2, y + 6, 0xAAAAAA, true);
        context.drawText(tr, "§7队长", cx3, y + 6, 0xAAAAAA, true);
        context.drawText(tr, "§7人数", cx4, y + 6, 0xAAAAAA, true);

        List<ListRow> rows = ClanCache.getInstance().getList().clans;
        int maxVisible = Math.max(0, (listH - headerH - 4) / rowH);
        int start = Math.min(listScroll, Math.max(0, rows.size() - maxVisible));
        context.enableScissor(lx + 1, y + headerH + 1, lx + lw - 1, y + listH - 1);
        for (int i = start; i < rows.size() && i < start + maxVisible + 1; i++) {
            ListRow row = rows.get(i);
            int ry = y + headerH + 2 + (i - start) * rowH;
            if (ry + rowH > y + listH - 1) break;
            boolean sel = row.name.equals(selectedClanName);
            if (sel) context.fill(lx + 1, ry, lx + lw - 1, ry + rowH, 0x33FFD700);
            else if (mouseX >= lx && mouseX <= lx + lw && mouseY >= ry && mouseY < ry + rowH) {
                context.fill(lx + 1, ry, lx + lw - 1, ry + rowH, 0x22FFFFFF);
            }
            context.drawText(tr, "§f" + row.name, cx1, ry + 7, 0xFFFFFF, true);
            context.drawText(tr, "§e" + row.abbr, cx2, ry + 7, 0xFFFFFF, true);
            context.drawText(tr, "§7" + row.leaderName, cx3, ry + 7, 0xFFFFFF, true);
            context.drawText(tr, "§f" + row.memberCount + (row.limit > 0 ? "§7/" + row.limit : ""), cx4, ry + 7, 0xFFFFFF, true);
        }
        context.disableScissor();
        if (rows.isEmpty()) {
            // 搜索态与无战队态区分提示
            String emptyText = (searchQuery == null || searchQuery.isBlank())
                    ? "§7暂无战队，点击下方「创建战队」建立第一个战队吧"
                    : "§7没有搜索到匹配的战队，请尝试更改搜索关键词";
            context.drawText(tr, emptyText, lx + 12, y + headerH + 10, 0xAAAAAA, true);
        }

        // ===== 底部：搜索栏 + 创建战队 =====
        int by = y + height - 24;
        searchField.visible = true;
        searchField.setPosition(lx + 4, by + 2);
        searchField.setWidth(lw - 150);
        addBtn(context, tr, buttons, "search", "§f搜索", lx + lw - 140, by, 64, 20, mouseX, mouseY, false);
        addBtn(context, tr, buttons, "create", "§a创建战队", lx + lw - 70, by, 66, 20, mouseX, mouseY, false);
    }

    /** 详情优先取 DETAIL 缓存，否则回退列表行数据 */
    private ClanInfo resolveSelectedClan() {
        ClanInfo detail = ClanCache.getInstance().getSelectedDetail();
        if (detail != null && detail.name.equals(selectedClanName)) return detail;
        for (ListRow row : ClanCache.getInstance().getList().clans) {
            if (row.name.equals(selectedClanName)) {
                ClanInfo info = new ClanInfo();
                info.name = row.name;
                info.abbr = row.abbr;
                info.leaderName = row.leaderName;
                info.memberCount = row.memberCount;
                info.limit = row.limit;
                info.badge = "";
                return info;
            }
        }
        return null;
    }

    // ---------- 已加入战队 ----------

    private void drawInClan(DrawContext context, int x, int y, int width, int height,
                            int mouseX, int mouseY, Mine mine) {
        TextRenderer tr = menu.font();
        ClanInfo clan = mine.clan;
        boolean isLeader = isMeLeader(clan);

        // ===== 顶部：名称/缩写/徽标 =====
        int topH = 56;
        context.fill(x, y, x + width, y + topH, 0x66101018);
        context.drawBorder(x, y, width, topH, BORDER);
        drawBadgeLarge(context, x + 10, y + 10, 36, clan.badge);
        context.drawText(tr, "§6" + clan.name + " §7[§e" + clan.abbr + "§7]", x + 58, y + 12, 0xFFFFFF, true);
        context.drawText(tr, "§7队长: §f" + clan.leaderName + "  §7成员: §f" + clan.memberCount
                + (clan.limit > 0 ? "§7/" + clan.limit : "§7（无上限）"), x + 58, y + 30, 0xAAAAAA, true);
        if (isLeader) {
            context.drawText(tr, "§a[队长]", x + width - 60, y + 12, 0x55FF55, true);
        }

        // ===== 中部：成员列表（可滚动） =====
        int listY = y + topH + 8;
        int listH = height - topH - 8 - 34;
        context.fill(x, listY, x + width, listY + listH, 0x66101018);
        context.drawBorder(x, listY, width, listH, BORDER);
        int rowH = 24;
        int maxVisible = Math.max(0, (listH - 4) / rowH);
        int start = Math.min(memberScroll, Math.max(0, clan.members.size() - maxVisible));
        context.enableScissor(x + 1, listY + 1, x + width - 1, listY + listH - 1);
        for (int i = start; i < clan.members.size() && i < start + maxVisible + 1; i++) {
            ClanInfo.MemberEntry m = clan.members.get(i);
            int ry = listY + 2 + (i - start) * rowH;
            if (ry + rowH > listY + listH - 1) break;
            // [队长] 标注（名字左侧）
            int nameX = x + 10;
            if (m.isLeader) {
                context.drawText(tr, "§a[队长]", x + 10, ry + 7, 0x55FF55, true);
                nameX = x + 10 + 46;
            }
            // 名字颜色：在线绿色 / 离线灰色
            context.drawText(tr, (m.online ? "§a" : "§7") + m.name, nameX, ry + 7, 0xFFFFFF, true);
            // 匹配状态：离线（灰色）/ 空闲（绿色）/ 正在匹配（"地图-模式"）
            String stateText;
            if (!m.online) {
                stateText = "§7离线";
            } else {
                stateText = (m.matchState == null || m.matchState.isEmpty()) ? "§a空闲" : "§f" + m.matchState;
            }
            int stateX = isLeader ? x + width - 250 : x + width - 110;
            context.drawText(tr, stateText, stateX, ry + 7, 0xFFFFFF, true);
            if (isLeader && !m.isLeader) {
                int bw = 52;
                int bx = x + width - bw * 2 - 18;
                addBtn(context, tr, buttons, "kick:" + m.name, "§c踢出", bx, ry + 3, bw, 17, mouseX, mouseY, false);
                addBtn(context, tr, buttons, "transfer:" + m.name, "§e转让", bx + bw + 8, ry + 3, bw, 17, mouseX, mouseY, false);
            }
        }
        context.disableScissor();
        if (clan.members.isEmpty()) {
            context.drawText(tr, "§7战队没有成员", x + 12, listY + 10, 0xAAAAAA, true);
        }

        // ===== 底部：退出 / 解散（队长）/ 编辑信息（队长） =====
        int by = y + height - 28;
        addBtn(context, tr, buttons, "leave", "§e退出战队", x, by, 100, 22, mouseX, mouseY, false);
        if (isLeader) {
            addBtn(context, tr, buttons, "disband", "§c解散战队", x + 108, by, 100, 22, mouseX, mouseY, false);
            addBtn(context, tr, buttons, "edit", "§9编辑战队信息", x + 216, by, 120, 22, mouseX, mouseY, false);
        }
    }

    private boolean isMeLeader(ClanInfo clan) {
        String me = menu.getPlayerName();
        for (ClanInfo.MemberEntry m : clan.members) {
            if (m.isLeader && m.name.equals(me)) return true;
        }
        return false;
    }

    // ---------- 创建战队对话框 ----------

    private void drawCreateDialog(DrawContext context, int x, int y, int width, int height,
                                  int mouseX, int mouseY) {
        TextRenderer tr = menu.font();
        // 遮罩
        context.fill(x, y, x + width, y + height, 0xC8000000);
        int dw = 300, dh = 200;
        int dx = x + (width - dw) / 2;
        int dy = y + (height - dh) / 2;
        context.fill(dx, dy, dx + dw, dy + dh, 0xFF212121);
        context.drawBorder(dx, dy, dw, dh, 0xFFDAA520);
        context.drawCenteredTextWithShadow(tr, dialogEditMode ? "§6编辑战队信息" : "§6创建战队",
                dx + dw / 2, dy + 8, 0xFFFFFF);

        int lx = dx + 12;
        int ly = dy + 24;
        // 标签独占一行、输入框另起一行等宽铺满：旧版输入框从 lx+80 起与标签同行，
        // 而标签普遍宽于 80px，输入框背景会盖住标签尾部（"徽标Base64"标签被遮掉大半）
        context.drawText(tr, "§7战队名称（≤128字符）", lx, ly, 0xAAAAAA, true);
        placeField(dlgNameField, lx, ly + 11, dw - 24); ly += 30;
        context.drawText(tr, "§7战队缩写（≤10字符）", lx, ly, 0xAAAAAA, true);
        placeField(dlgAbbrField, lx, ly + 11, dw - 24); ly += 30;
        // 徽标输入框已不设长度限制（分片上传），标签不再标注 30000 上限
        context.drawText(tr, "§7徽标Base64:（可留空）", lx, ly, 0xAAAAAA, true);
        placeField(dlgBadgeField, lx, ly + 11, dw - 24); ly += 30;
        context.drawText(tr, "§7成员上限（0=无限制）", lx, ly, 0xAAAAAA, true);
        placeField(dlgLimitField, lx, ly + 11, dw - 24); ly += 30;
        if (!dialogError.isEmpty()) {
            context.drawText(tr, "§c" + dialogError, lx, ly, 0xFFFF55, true);
            ly += 14;
        }
        addBtn(context, tr, buttons, "dlgConfirm", dialogEditMode ? "§a保存" : "§a创建",
                dx + dw / 2 - 104, dy + dh - 30, 100, 20, mouseX, mouseY, false);
        addBtn(context, tr, buttons, "dlgCancel", "§7取消", dx + dw / 2 + 4, dy + dh - 30, 100, 20, mouseX, mouseY, false);

        // 对话框打开时置焦名称框
        if (menu.getFocused() == null || !isDialogField(menu.getFocused())) {
            menu.setFocused(dlgNameField);
        }
    }

    private boolean isDialogField(Object w) {
        return w == dlgNameField || w == dlgAbbrField || w == dlgBadgeField || w == dlgLimitField
                || w == searchField;
    }

    private void placeField(TextFieldWidget field, int x, int y, int w) {
        field.visible = true;
        field.setPosition(x, y);
        field.setWidth(w);
    }

    /** 大徽标（详情/战队页顶部）：badgeId 查分片缓存，未到齐或无徽标 = 黑色实心正方形 */
    private void drawBadgeLarge(DrawContext context, int x, int y, int size, String badgeId) {
        context.fill(x, y, x + size, y + size, 0xFF111111);
        String base64 = BadgeCache.get(badgeId);
        CardTexture tex = (base64 == null || base64.isEmpty())
                ? null : Base64ImageDecoder.decode(base64);
        if (tex != null) {
            context.drawTexture(tex.id(), x, y, size, size, 0f, 0f, tex.width(), tex.height(), tex.width(), tex.height());
        }
        context.drawBorder(x, y, size, size, 0xFFFFD700);
    }

    // ==================== 交互 ====================

    private void addBtn(DrawContext context, TextRenderer tr, List<Btn> list, String id, String label,
                        int x, int y, int w, int h, int mouseX, int mouseY, boolean disabled) {
        boolean hover = !disabled && mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h;
        context.fill(x, y, x + w, y + h, disabled ? 0xFF262626 : hover ? BTN_HOVER : BTN);
        context.drawBorder(x, y, w, h, disabled ? 0xFF333333 : 0xFF666666);
        context.drawCenteredTextWithShadow(tr, label, x + w / 2, y + (h - 8) / 2, disabled ? 0x777777 : 0xFFFFFF);
        if (!disabled) list.add(new Btn(id, "", x, y, w, h));
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return false;
        for (Btn b : buttons) {
            if (mouseX >= b.x && mouseX <= b.x + b.w && mouseY >= b.y && mouseY <= b.y + b.h) {
                handleAction(b.id);
                return true;
            }
        }
        // 列表行点击（选中战队）
        return false;
    }

    private void handleAction(String id) {
        if (id.equals("search")) {
            searchQuery = searchField.getText();
            send(ClanAction.REQUEST_LIST, searchQuery, "", "", 0);
        } else if (id.equals("create")) {
            createDialogOpen = true;
            dialogEditMode = false;
            dialogError = "";
            dlgNameField.setText("");
            dlgAbbrField.setText("");
            dlgBadgeField.setText("");
            dlgLimitField.setText("0");
            menu.setFocused(dlgNameField);
        } else if (id.equals("edit")) {
            // 编辑战队信息：预填当前值（仅队长可见此按钮）
            Mine mineNow = ClanCache.getInstance().getMine();
            if (mineNow.inClan && mineNow.clan != null) {
                createDialogOpen = true;
                dialogEditMode = true;
                dialogError = "";
                dlgNameField.setText(mineNow.clan.name);
                dlgAbbrField.setText(mineNow.clan.abbr);
                // badge 字段是内容寻址 id，编辑预填需要完整 base64（分片未到齐时留空让玩家重贴）
                String curBadge = BadgeCache.get(mineNow.clan.badge);
                dlgBadgeField.setText(curBadge == null ? "" : curBadge);
                dlgLimitField.setText(String.valueOf(mineNow.clan.limit));
                menu.setFocused(dlgNameField);
            }
        } else if (id.equals("dlgCancel")) {
            // 取消 = 丢弃输入：清空对话框全部输入框，避免残留到下次打开
            createDialogOpen = false;
            dialogEditMode = false;
            dialogError = "";
            dlgNameField.setText("");
            dlgAbbrField.setText("");
            dlgBadgeField.setText("");
            dlgLimitField.setText("");
            menu.setFocused(null);
        } else if (id.equals("dlgConfirm")) {
            String name = dlgNameField.getText().trim();
            String abbr = dlgAbbrField.getText().trim();
            String badge = dlgBadgeField.getText().trim();
            int limit;
            try {
                limit = Integer.parseInt(dlgLimitField.getText().trim());
            } catch (NumberFormatException e) {
                dialogError = "成员上限必须是数字";
                return;
            }
            if (name.isEmpty()) { dialogError = "战队名称不能为空"; return; }
            if (abbr.isEmpty()) { dialogError = "战队缩写不能为空"; return; }
            // 输入框不设上限，但分片协议上限（64 片 × 30000 字符）仍生效：
            // 提交前拦截而不是发 65+ 个分片包等服务端拒收（届时对话框已关闭，无法就地修正）
            if (badge.length() > ClanManager.MAX_BADGE_LENGTH) {
                dialogError = "徽标过大（base64 最多 " + ClanManager.MAX_BADGE_LENGTH + " 字符）";
                return;
            }
            send(dialogEditMode ? ClanAction.EDIT : ClanAction.CREATE, name, abbr, badge, limit);
            createDialogOpen = false;
            dialogEditMode = false;
            menu.setFocused(null);
        } else if (id.startsWith("join:")) {
            send(ClanAction.JOIN, id.substring(5), "", "", 0);
        } else if (id.equals("leave")) {
            send(ClanAction.LEAVE, "", "", "", 0);
        } else if (id.equals("disband")) {
            send(ClanAction.DISBAND, "", "", "", 0);
        } else if (id.startsWith("kick:")) {
            send(ClanAction.KICK, id.substring(5), "", "", 0);
        } else if (id.startsWith("transfer:")) {
            send(ClanAction.TRANSFER, id.substring(9), "", "", 0);
        }
    }

    /** 列表行点击选中（由 MatchMenuScreen 在战队页鼠标事件时调用，返回是否命中行） */
    public boolean handleRowClick(double mouseX, double mouseY, int x, int y, int width, int height) {
        Mine mine = ClanCache.getInstance().getMine();
        if (mine.inClan || createDialogOpen) return false;
        int detailW = width / 3;
        int lx = x + detailW + 6;
        int lw = width - detailW - 6;
        int listH = height - 30;
        if (mouseX < lx || mouseX > lx + lw || mouseY < y + 20 || mouseY > y + listH - 26) return false;
        int rowH = 26;
        int idx = listScroll + (int) ((mouseY - y - 22) / rowH);
        List<ListRow> rows = ClanCache.getInstance().getList().clans;
        if (idx < 0 || idx >= rows.size()) return false;
        selectedClanName = rows.get(idx).name;
        send(ClanAction.REQUEST_DETAIL, selectedClanName, "", "", 0);
        return true;
    }

    public void scroll(double verticalAmount) {
        Mine mine = ClanCache.getInstance().getMine();
        if (mine.inClan) {
            memberScroll = Math.max(0, memberScroll - (int) (verticalAmount * 20));
        } else {
            listScroll = Math.max(0, listScroll - (int) (verticalAmount * 20));
        }
    }
}
