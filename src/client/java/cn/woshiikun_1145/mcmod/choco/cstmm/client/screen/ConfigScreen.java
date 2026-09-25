package cn.woshiikun_1145.mcmod.choco.cstmm.client.screen;

import cn.woshiikun_1145.mcmod.choco.cstmm.client.ClientHandshakeState;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.cache.ConfigDataCache;
import cn.woshiikun_1145.mcmod.choco.cstmm.client.network.ClientNetworkHandler;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.config.GlobalConfig;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.config.MapConfig;
import cn.woshiikun_1145.mcmod.choco.cstmm.util.BlockPosAdapter;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Drawable;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.registry.Registries;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * 【作用】服务端配置编辑界面（管理员用）：左侧地图列表（增删/切换），右侧地图编辑器
 * （ID/名称/结算/人数/边界/出生点/商店物品等）与全局配置区（默认装备/快速超时），
 * 保存时经 Gson 序列化并分包发 C2S；内置未保存修改确认对话框。
 * 【被谁使用】ClientNetworkHandler（收到 S2C OpenConfigScreenPayload 时打开，/cstmm config 命令触发）；
 * 辅助类 ConfigScreenSupport/ConfirmDialog/NumberTextField/LabelWidget 仅被本类使用。
 */
@Environment(EnvType.CLIENT)
public class ConfigScreen extends Screen {

    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(BlockPos.class, new BlockPosAdapter())
            .setPrettyPrinting()
            .create();

    // ---- 可调整的右侧内容布局偏移 ----
    /** 标签后第一列输入框的统一偏移（地图ID/显示名称/目标击杀数/最低与最高人数/条件参数/平局规则按钮/全局参数） */
    private static final int FIELD_OFFSET = 80;
    /** 边界坐标行：每组“标签+输入框”的水平间距（X/Y/Z 三组） */
    private static final int BOUNDARY_COL_SPACING = 90;
    /** 边界坐标行：输入框相对组起点的偏移 */
    private static final int BOUNDARY_FIELD_OFFSET = 33;
    /** 右侧编辑区行距（地图编辑器与全局配置区共用） */
    private static final int ROW_GAP = 26;

    // 布局尺寸
    private int sidebarWidth;
    private int headerHeight;
    private int footerHeight;
    private int contentLeft;
    private int contentTop;
    private int contentWidth;
    private int contentHeight;

    // 滚动偏移
    private int rightScrollOffset = 0;
    private int leftScrollOffset = 0;
    private int rightTotalHeight = 0;
    private int leftTotalHeight = 0;

    // 数据
    private int selectedMapIndex = 0;
    private List<MapConfig> maps = new ArrayList<>();
    private GlobalConfig globalConfig = new GlobalConfig();
    private MapConfig editingMap = null;
    private int globalTab = 0; // 0:装备, 1:参数

    // 区分固定控件和滚动控件
    private final List<ClickableWidget> fixedWidgets = new ArrayList<>();
    private final List<Drawable> rightScrollables = new ArrayList<>();
    private final List<ClickableWidget> leftButtons = new ArrayList<>();

    private boolean hasUnsavedChanges = false;
    /** #6 用户主动重载后放行一次被动同步刷新；被动同步路径在 hasUnsavedChanges 时跳过刷新 */
    private boolean allowNextSyncRefresh = false;

    // 内置确认对话框（在当前界面内弹出，不暂停游戏）
    // textRenderer 懒获取：构造期 Screen.textRenderer 尚未赋值（init() 才赋值），直接传入会是 null（曾致 NPE 崩溃）
    private final ConfirmDialog confirmDialog = new ConfirmDialog(this, this::font);

    /** 面板/对话框桥接：Screen.textRenderer 是 protected */
    public TextRenderer font() {
        return textRenderer;
    }

    /** 服务端弹窗通知（如"配置已保存！"）：复用确认对话框的提示模式 */
    public void showPopup(String message) {
        confirmDialog.show(Text.literal("提示"), Text.literal(message), null);
    }

    // 【作用】构造：先从本地缓存装载一份数据用于编辑，再向服务端请求最新配置同步
    public ConfigScreen() {
        super(Text.literal("配置"));
        loadData();
        ClientNetworkHandler.requestConfigSync();
    }

    /**
     * 【作用】从 ConfigDataCache 深拷贝全部地图与全局配置到本地编辑副本
     * （编辑过程不污染缓存，保存时才回写），并选定当前编辑地图。
     */
    private void loadData() {
        ConfigDataCache cache = ConfigDataCache.getInstance();
        this.maps = new ArrayList<>();
        for (MapConfig src : cache.getMaps()) {
            this.maps.add(ConfigScreenSupport.deepCopyMap(src));
        }
        this.globalConfig = ConfigScreenSupport.deepCopyGlobal(cache.getGlobalConfig());

        if (maps.isEmpty()) {
            editingMap = null;
            selectedMapIndex = -1;
            return;
        }
        if (selectedMapIndex >= maps.size() || selectedMapIndex < 0) {
            selectedMapIndex = 0;
        }
        editingMap = ConfigScreenSupport.deepCopyMap(maps.get(selectedMapIndex));
        hasUnsavedChanges = false;
    }

    /**
     * 【作用】构建全部控件：先清空重建滚动状态，依次计算布局、顶栏按钮、左侧地图列表、
     * 右侧地图编辑器与全局配置区；窗口尺寸变化/切图/增删行时都会整体重走本方法。
     */
    @Override
    protected void init() {
        super.init();
        fixedWidgets.clear();
        rightScrollables.clear();
        leftButtons.clear();
        rightScrollOffset = 0;
        leftScrollOffset = 0;
        rightTotalHeight = 0;
        leftTotalHeight = 0;

        computeLayout();
        buildTopBar();
        buildMapList();

        // ----- 右侧可滚动内容（editY 基线：editingMap == null 时保持 contentTop，与原实现一致） -----
        int editY = contentTop;
        editY = buildMapEditor(editY);
        int globalRowY = buildGlobalSection(editY);

        rightTotalHeight = Math.max(globalRowY + 20, editY + 20) - contentTop;
        if (leftTotalHeight < headerHeight + 40) leftTotalHeight = headerHeight + 40;
    }

    /** 布局尺寸计算 */
    private void computeLayout() {
        sidebarWidth = Math.max(120, width / 5);
        headerHeight = Math.max(40, height / 16);
        footerHeight = Math.max(50, height / 14);
        contentLeft = sidebarWidth + 8;
        contentTop = headerHeight + 6;
        contentWidth = width - sidebarWidth - 16;
        contentHeight = height - headerHeight - footerHeight - 12;
    }

    // 【作用】构建顶栏与底部的固定控件：保存配置 / 重新加载 / 返回按钮
    private void buildTopBar() {
        int screenWidth = this.width;
        int screenHeight = this.height;

        // ----- 固定控件：顶部按钮 -----
        int topBtnY = 6;
        int btnWidth = 90;
        int btnHeight = 20;
        fixedWidgets.add(ButtonWidget.builder(
                Text.literal("保存配置"),
                button -> saveConfig()
        ).dimensions(screenWidth - btnWidth - 8, topBtnY, btnWidth, btnHeight).build());

        fixedWidgets.add(ButtonWidget.builder(
                Text.literal("重新加载"),
                button -> reloadConfig()
        ).dimensions(screenWidth - btnWidth * 2 - 16, topBtnY, btnWidth, btnHeight).build());

        // ----- 固定控件：底部返回 -----
        int returnBtnY = screenHeight - footerHeight + 8;
        fixedWidgets.add(ButtonWidget.builder(
                Text.literal("返回"),
                button -> close()
        ).dimensions(screenWidth - 80, returnBtnY, 60, 20).build());
    }

    /**
     * 【作用】构建左侧地图列表按钮：每张地图一个"启用/禁用 + 名称"按钮
     * （有未保存修改时切换先弹确认框），末尾追加"添加地图/删除选中"按钮，并回写 leftTotalHeight。
     */
    private void buildMapList() {
        // ----- 左侧地图列表（可滚动） -----
        int listBtnX = 6;
        int listBtnY = headerHeight + 4;
        int listBtnWidth = sidebarWidth - 12;
        int listBtnHeight = 22;
        int listSpacing = 26;

        if (maps.isEmpty()) {
            ButtonWidget emptyBtn = ButtonWidget.builder(
                    Text.literal("§e等待配置同步..."),
                    b -> {}
            ).dimensions(listBtnX, listBtnY, listBtnWidth, listBtnHeight).build();
            leftButtons.add(emptyBtn);
            leftTotalHeight = listBtnY + listBtnHeight + 4;
        } else {
            for (int i = 0; i < maps.size(); i++) {
                MapConfig map = maps.get(i);
                String label = (map.isEnabled() ? "启用 " : "禁用 ") + map.getDisplayName();
                final int index = i;
                ButtonWidget btn = ButtonWidget.builder(
                        Text.literal(label),
                        button -> {
                            if (hasUnsavedChanges) {
                                confirmDialog.show(
                                        Text.literal("有未保存的修改！！！"),
                                        Text.literal("切换地图将丢失当前更改，确定继续吗？"),
                                        () -> {
                                            selectedMapIndex = index;
                                            editingMap = ConfigScreenSupport.deepCopyMap(maps.get(index));
                                            hasUnsavedChanges = false;
                                            clearChildren();
                                            init();
                                        }
                                );
                            } else {
                                selectedMapIndex = index;
                                editingMap = ConfigScreenSupport.deepCopyMap(maps.get(index));
                                clearChildren();
                                init();
                            }
                        }
                ).dimensions(listBtnX, listBtnY + i * listSpacing, listBtnWidth, listBtnHeight).build();
                leftButtons.add(btn);
            }
            leftTotalHeight = listBtnY + maps.size() * listSpacing + 4;
        }

        ButtonWidget addMapBtn = ButtonWidget.builder(
                Text.literal("+ 添加地图"),
                button -> addNewMap()
        ).dimensions(listBtnX, leftTotalHeight, listBtnWidth, listBtnHeight).build();
        leftButtons.add(addMapBtn);
        leftTotalHeight += listSpacing;

        ButtonWidget delMapBtn = ButtonWidget.builder(
                Text.literal("删除选中"),
                button -> deleteSelectedMap()
        ).dimensions(listBtnX, leftTotalHeight, listBtnWidth, listBtnHeight).build();
        leftButtons.add(delMapBtn);
        leftTotalHeight += listSpacing + 8;
    }

    /**
     * 右侧地图编辑器（editingMap == null 时直接返回传入的 editY，保持原基线）。
     * @param editY 起始 y
     * @return 推进后的 editY（编辑器最后一行的 y）
     */
    private int buildMapEditor(int editY) {
        int editX = contentLeft;
        int fieldWidth = Math.max(100, contentWidth - 140);

        if (editingMap != null) {
            // ---- 地图ID ----
            rightScrollables.add(new LabelWidget(editX, editY, Text.literal("地图ID:")));
            TextFieldWidget idField = new TextFieldWidget(textRenderer, editX + FIELD_OFFSET, editY, 120, 18, Text.literal(""));
            idField.setText(editingMap.getId());
            idField.setTextPredicate(s -> s.matches("[a-zA-Z0-9_-]*"));
            idField.setChangedListener(s -> {
                editingMap.setId(s);
                hasUnsavedChanges = true;
            });
            rightScrollables.add(idField);
            editY += ROW_GAP;

            // ---- 显示名称 ----
            rightScrollables.add(new LabelWidget(editX, editY, Text.literal("显示名称:")));
            TextFieldWidget nameField = new TextFieldWidget(textRenderer, editX + FIELD_OFFSET, editY, fieldWidth - FIELD_OFFSET, 18, Text.literal(""));
            nameField.setText(editingMap.getDisplayName());
            nameField.setChangedListener(s -> {
                editingMap.setDisplayName(s);
                hasUnsavedChanges = true;
            });
            rightScrollables.add(nameField);
            editY += ROW_GAP;

            // ---- 启用/禁用 ----
            ButtonWidget enableBtn = ButtonWidget.builder(
                    Text.literal(editingMap.isEnabled() ? "禁用" : "启用"),
                    button -> {
                        editingMap.setEnabled(!editingMap.isEnabled());
                        button.setMessage(Text.literal(editingMap.isEnabled() ? "禁用" : "启用"));
                        hasUnsavedChanges = true;
                    }
            ).dimensions(editX, editY, 90, 20).build();
            rightScrollables.add(enableBtn);
            editY += ROW_GAP;

            // ---- 结算方式 ----
            ButtonWidget winCondBtn = ButtonWidget.builder(
                    Text.literal("结算方式: " + editingMap.getWinCondition()),
                    button -> {
                        if (editingMap.getWinCondition() == MapConfig.WinCondition.KILLS) {
                            editingMap.setWinCondition(MapConfig.WinCondition.TIMER);
                        } else {
                            editingMap.setWinCondition(MapConfig.WinCondition.KILLS);
                        }
                        button.setMessage(Text.literal("结算方式: " + editingMap.getWinCondition()));
                        hasUnsavedChanges = true;
                        clearChildren();
                        init();
                    }
            ).dimensions(editX, editY, 150, 20).build();
            rightScrollables.add(winCondBtn);
            editY += ROW_GAP;
            addHint(editX, editY, "结算方式：KILLS=先达到目标击杀数的队伍获胜；TIMER=时限结束时击杀数多者获胜");
            editY += 11;

            // ---- 条件参数 ----
            if (editingMap.getWinCondition() == MapConfig.WinCondition.KILLS) {
                rightScrollables.add(new LabelWidget(editX, editY, Text.literal("目标击杀数:")));
                TextFieldWidget targetKillsField = new NumberTextField(editX + FIELD_OFFSET, editY, 60, 18, Text.literal(""));
                targetKillsField.setText(String.valueOf(editingMap.getTargetKills()));
                targetKillsField.setChangedListener(s -> {
                    if (!s.isEmpty()) {
                        try {
                            editingMap.setTargetKills(Integer.parseInt(s));
                            hasUnsavedChanges = true;
                        } catch (NumberFormatException ignored) {}
                    }
                });
                rightScrollables.add(targetKillsField);
                editY += ROW_GAP;
            } else {
                rightScrollables.add(new LabelWidget(editX, editY, Text.literal("最大时长(秒):")));
                TextFieldWidget maxDurationField = new NumberTextField(editX + FIELD_OFFSET, editY, 60, 18, Text.literal(""));
                maxDurationField.setText(String.valueOf(editingMap.getMaxDuration()));
                maxDurationField.setChangedListener(s -> {
                    if (!s.isEmpty()) {
                        try {
                            editingMap.setMaxDuration(Integer.parseInt(s));
                            hasUnsavedChanges = true;
                        } catch (NumberFormatException ignored) {}
                    }
                });
                rightScrollables.add(maxDurationField);
                editY += ROW_GAP;

                rightScrollables.add(new LabelWidget(editX, editY, Text.literal("平局规则:"), 20));
                ButtonWidget tieRuleBtn = ButtonWidget.builder(
                        Text.literal("平局规则: " + editingMap.getTieRule()),
                        button -> {
                            if (editingMap.getTieRule() == MapConfig.TieRule.OVERTIME) {
                                editingMap.setTieRule(MapConfig.TieRule.DRAW);
                            } else {
                                editingMap.setTieRule(MapConfig.TieRule.OVERTIME);
                            }
                            button.setMessage(Text.literal("平局规则: " + editingMap.getTieRule()));
                            hasUnsavedChanges = true;
                        }
                ).dimensions(editX + FIELD_OFFSET, editY, 160, 20).build();
                rightScrollables.add(tieRuleBtn);
                editY += ROW_GAP;
                addHint(editX, editY, "平局规则：OVERTIME=平局时发起加时投票（全票通过则延长对局）；DRAW=直接平局结束");
                editY += 11;
            }

            // ---- 红队/蓝队最低人数 ----
            rightScrollables.add(new LabelWidget(editX, editY, Text.literal("红队最低人数:"), 18));
            TextFieldWidget minRedField = new NumberTextField(editX + FIELD_OFFSET, editY, 50, 18, Text.literal(""));
            minRedField.setText(String.valueOf(editingMap.getMinRedPlayers()));
            minRedField.setChangedListener(s -> {
                if (!s.isEmpty()) {
                    try {
                        editingMap.setMinRedPlayers(Integer.parseInt(s));
                        hasUnsavedChanges = true;
                    } catch (NumberFormatException ignored) {}
                }
            });
            rightScrollables.add(minRedField);

            rightScrollables.add(new LabelWidget(editX + FIELD_OFFSET + 60, editY, Text.literal("蓝队最低人数:"), 18));
            TextFieldWidget minBlueField = new NumberTextField(editX + FIELD_OFFSET + 160, editY, 50, 18, Text.literal(""));
            minBlueField.setText(String.valueOf(editingMap.getMinBluePlayers()));
            minBlueField.setChangedListener(s -> {
                if (!s.isEmpty()) {
                    try {
                        editingMap.setMinBluePlayers(Integer.parseInt(s));
                        hasUnsavedChanges = true;
                    } catch (NumberFormatException ignored) {}
                }
            });
            rightScrollables.add(minBlueField);
            editY += ROW_GAP;
            addHint(editX, editY, "最低人数 = 开局门槛（两队各达下限才可开局），也是 CONDITIONAL 补位模式的补位阈值");
            editY += 11;

            // ---- 红队/蓝队最高人数（0 = 无上限） ----
            rightScrollables.add(new LabelWidget(editX, editY, Text.literal("红队最高人数:"), 18));
            TextFieldWidget maxRedField = new NumberTextField(editX + FIELD_OFFSET, editY, 50, 18, Text.literal(""));
            maxRedField.setText(String.valueOf(editingMap.getMaxRedPlayers()));
            maxRedField.setChangedListener(s -> {
                if (!s.isEmpty()) {
                    try {
                        editingMap.setMaxRedPlayers(Integer.parseInt(s));
                        hasUnsavedChanges = true;
                    } catch (NumberFormatException ignored) {}
                }
            });
            rightScrollables.add(maxRedField);

            rightScrollables.add(new LabelWidget(editX + FIELD_OFFSET + 60, editY, Text.literal("蓝队最高人数:"), 18));
            TextFieldWidget maxBlueField = new NumberTextField(editX + FIELD_OFFSET + 160, editY, 50, 18, Text.literal(""));
            maxBlueField.setText(String.valueOf(editingMap.getMaxBluePlayers()));
            maxBlueField.setChangedListener(s -> {
                if (!s.isEmpty()) {
                    try {
                        editingMap.setMaxBluePlayers(Integer.parseInt(s));
                        hasUnsavedChanges = true;
                    } catch (NumberFormatException ignored) {}
                }
            });
            rightScrollables.add(maxBlueField);

            rightScrollables.add(new LabelWidget(editX + FIELD_OFFSET + 220, editY, Text.literal("(0=无上限)"), 18));
            editY += ROW_GAP;

            // ---- 准备时间 ----
            rightScrollables.add(new LabelWidget(editX, editY, Text.literal("准备时间(秒):")));
            TextFieldWidget prepareTimeField = new NumberTextField(editX + FIELD_OFFSET, editY, 50, 18, Text.literal(""));
            prepareTimeField.setText(String.valueOf(editingMap.getPrepareTime()));
            prepareTimeField.setChangedListener(s -> {
                if (!s.isEmpty()) {
                    try {
                        editingMap.setPrepareTime(Integer.parseInt(s));
                        hasUnsavedChanges = true;
                    } catch (NumberFormatException ignored) {}
                }
            });
            rightScrollables.add(prepareTimeField);
            editY += ROW_GAP;

            // ---- 边界警告 ----
            rightScrollables.add(new LabelWidget(editX, editY, Text.literal("边界警告(秒):")));
            TextFieldWidget boundaryWarningField = new NumberTextField(editX + FIELD_OFFSET, editY, 50, 18, Text.literal(""));
            boundaryWarningField.setText(String.valueOf(editingMap.getBoundaryWarningTime()));
            boundaryWarningField.setChangedListener(s -> {
                if (!s.isEmpty()) {
                    try {
                        editingMap.setBoundaryWarningTime(Integer.parseInt(s));
                        hasUnsavedChanges = true;
                    } catch (NumberFormatException ignored) {}
                }
            });
            rightScrollables.add(boundaryWarningField);
            editY += ROW_GAP;

            // ---- 边界惩罚 ----
            rightScrollables.add(new LabelWidget(editX, editY, Text.literal("边界惩罚(击杀):")));
            TextFieldWidget boundaryPenaltyField = new NumberTextField(editX + FIELD_OFFSET, editY, 50, 18, Text.literal(""));
            boundaryPenaltyField.setText(String.valueOf(editingMap.getBoundaryPenaltyKills()));
            boundaryPenaltyField.setChangedListener(s -> {
                if (!s.isEmpty()) {
                    try {
                        editingMap.setBoundaryPenaltyKills(Integer.parseInt(s));
                        hasUnsavedChanges = true;
                    } catch (NumberFormatException ignored) {}
                }
            });
            rightScrollables.add(boundaryPenaltyField);
            editY += ROW_GAP;

            // ---- 踢人冷却 ----
            rightScrollables.add(new LabelWidget(editX, editY, Text.literal("踢人冷却(秒):")));
            TextFieldWidget kickCooldownField = new NumberTextField(editX + FIELD_OFFSET, editY, 50, 18, Text.literal(""));
            kickCooldownField.setText(String.valueOf(editingMap.getKickCooldownSeconds()));
            kickCooldownField.setChangedListener(s -> {
                if (!s.isEmpty()) {
                    try {
                        editingMap.setKickCooldownSeconds(Integer.parseInt(s));
                        hasUnsavedChanges = true;
                    } catch (NumberFormatException ignored) {}
                }
            });
            rightScrollables.add(kickCooldownField);
            editY += ROW_GAP;

            // ---- 冷却时间 ----
            rightScrollables.add(new LabelWidget(editX, editY, Text.literal("冷却时间(秒):")));
            TextFieldWidget cooldownField = new NumberTextField(editX + FIELD_OFFSET, editY, 50, 18, Text.literal(""));
            cooldownField.setText(String.valueOf(editingMap.getCooldownSeconds()));
            cooldownField.setChangedListener(s -> {
                if (!s.isEmpty()) {
                    try {
                        editingMap.setCooldownSeconds(Integer.parseInt(s));
                        hasUnsavedChanges = true;
                    } catch (NumberFormatException ignored) {}
                }
            });
            rightScrollables.add(cooldownField);
            editY += ROW_GAP;
            addHint(editX, editY, "对局结束后该地图进入冷却的秒数，冷却期内无法在该地图开新局");
            editY += 11;

            // ---- 是否允许补位 ----
            ButtonWidget reinforceableBtn = ButtonWidget.builder(
                    Text.literal(editingMap.isReinforceable() ? "允许补位: 是" : "允许补位: 否"),
                    button -> {
                        editingMap.setReinforceable(!editingMap.isReinforceable());
                        button.setMessage(Text.literal(editingMap.isReinforceable() ? "允许补位: 是" : "允许补位: 否"));
                        hasUnsavedChanges = true;
                    }
            ).dimensions(editX, editY, 120, 20).build();
            rightScrollables.add(reinforceableBtn);
            editY += ROW_GAP;
            addHint(editX, editY, "允许补位=快速匹配超时的玩家可中途加入该图对局；关闭后补位模式不生效");
            editY += 11;

            // ---- 补位模式 ----
            rightScrollables.add(new LabelWidget(editX, editY, Text.literal("补位模式:"), 20));
            ButtonWidget reinforceModeBtn = ButtonWidget.builder(
                    Text.literal("补位模式: " + editingMap.getReinforcementMode()),
                    button -> {
                        if (editingMap.isAlwaysReinforce()) {
                            editingMap.setReinforcementMode("CONDITIONAL");
                        } else {
                            editingMap.setReinforcementMode("ALWAYS");
                        }
                        button.setMessage(Text.literal("补位模式: " + editingMap.getReinforcementMode()));
                        hasUnsavedChanges = true;
                    }
            ).dimensions(editX + FIELD_OFFSET, editY, 160, 20).build();
            rightScrollables.add(reinforceModeBtn);
            editY += ROW_GAP;
            addHint(editX, editY, "补位模式：CONDITIONAL=各队只补到最低人数；ALWAYS=补到满员（最高人数 0=无上限时全部补入）。需地图允许补位");
            editY += 11;

            // ---- 对局维度 ----
            rightScrollables.add(new LabelWidget(editX, editY, Text.literal("对局维度:")));
            TextFieldWidget dimensionField = new TextFieldWidget(textRenderer, editX + FIELD_OFFSET, editY, fieldWidth - FIELD_OFFSET, 18, Text.literal(""));
            dimensionField.setText(editingMap.getDimension());
            dimensionField.setChangedListener(s -> {
                editingMap.setDimension(s);
                hasUnsavedChanges = true;
            });
            rightScrollables.add(dimensionField);
            editY += ROW_GAP;
            addHint(editX, editY, "对局所在维度 ID（如 minecraft:overworld），非法值回退主世界");
            editY += 11;

            // ---- 商店物品（按地图配置） ----
            rightScrollables.add(new LabelWidget(editX, editY, Text.literal("商店物品:"), 9));
            editY += 18;

            // 列布局：物品ID（可带NBT） | 价格 | 限购次数 | 删除
            int shopItemFieldWidth = Math.max(100, Math.min(200, contentWidth - 280));
            int shopPriceFieldX = editX + shopItemFieldWidth + 10;
            int shopMaxFieldX = shopPriceFieldX + 60;
            int shopDelX = shopMaxFieldX + 60;

            // 表头
            rightScrollables.add(new LabelWidget(editX, editY, Text.literal("物品ID（可带NBT）"), 9));
            rightScrollables.add(new LabelWidget(shopPriceFieldX, editY, Text.literal("价格"), 9));
            rightScrollables.add(new LabelWidget(shopMaxFieldX, editY, Text.literal("限购次数"), 9));
            rightScrollables.add(new LabelWidget(shopDelX, editY, Text.literal("删除"), 9));
            editY += 18;

            List<GlobalConfig.ShopItem> shopItems = editingMap.getShopItems();
            for (int i = 0; i < shopItems.size(); i++) {
                GlobalConfig.ShopItem item = shopItems.get(i);
                final int idx = i;

                // 输入框1：物品ID（可带NBT）
                TextFieldWidget shopItemField = new TextFieldWidget(textRenderer, editX, editY + i * 22, shopItemFieldWidth, 18, Text.literal(""));
                shopItemField.setMaxLength(256);
                shopItemField.setText(item.getItemId() != null ? item.getItemId() : "");
                shopItemField.setChangedListener(s -> {
                    shopItems.get(idx).setItemId(s);
                    hasUnsavedChanges = true;
                });
                rightScrollables.add(shopItemField);

                // 输入框2：价格
                NumberTextField priceField = new NumberTextField(shopPriceFieldX, editY + i * 22, 50, 18, Text.literal(""));
                priceField.setText(String.valueOf(item.getPrice()));
                priceField.setChangedListener(s -> {
                    if (!s.isEmpty()) {
                        try {
                            shopItems.get(idx).setPrice(Integer.parseInt(s));
                            hasUnsavedChanges = true;
                        } catch (NumberFormatException ignored) {}
                    }
                });
                rightScrollables.add(priceField);

                // 输入框3：限购次数
                NumberTextField maxPurchaseField = new NumberTextField(shopMaxFieldX, editY + i * 22, 50, 18, Text.literal(""));
                maxPurchaseField.setText(String.valueOf(item.getMaxPurchase()));
                maxPurchaseField.setChangedListener(s -> {
                    if (!s.isEmpty()) {
                        try {
                            shopItems.get(idx).setMaxPurchase(Integer.parseInt(s));
                            hasUnsavedChanges = true;
                        } catch (NumberFormatException ignored) {}
                    }
                });
                rightScrollables.add(maxPurchaseField);

                ButtonWidget delShopItemBtn = ButtonWidget.builder(
                        Text.literal("×"),
                        button -> {
                            editingMap.getShopItems().remove(idx);
                            hasUnsavedChanges = true;
                            clearChildren();
                            init();
                        }
                ).dimensions(shopDelX, editY + i * 22, 16, 18).build();
                rightScrollables.add(delShopItemBtn);
            }
            editY += shopItems.size() * 22 + 4;

            // 手动添加 + 手持添加
            ButtonWidget addShopItemBtn = ButtonWidget.builder(
                    Text.literal("+ 添加商品"),
                    button -> {
                        editingMap.getShopItems().add(new GlobalConfig.ShopItem("", 0, 1));
                        hasUnsavedChanges = true;
                        clearChildren();
                        init();
                    }
            ).dimensions(editX, editY, 110, 20).build();
            rightScrollables.add(addShopItemBtn);

            ButtonWidget addShopFromHandBtn = ButtonWidget.builder(
                    Text.literal("添加武器 (手持物品)"),
                    button -> addWeaponFromHand()
            ).dimensions(editX + 120, editY, 160, 20).build();
            rightScrollables.add(addShopFromHandBtn);
            editY += ROW_GAP;

            // ---- 边界坐标 ----
            // 位置调整：BOUNDARY_COL_SPACING（组间距）/ BOUNDARY_FIELD_OFFSET（输入框偏移）
            int bY = editY;
            var boundary = editingMap.getBoundary();
            addBoundaryField(editX, bY, "Min X:", boundary::setMinX, boundary.getMinX());
            addBoundaryField(editX + BOUNDARY_COL_SPACING, bY, "Min Y:", boundary::setMinY, boundary.getMinY());
            addBoundaryField(editX + 2 * BOUNDARY_COL_SPACING, bY, "Min Z:", boundary::setMinZ, boundary.getMinZ());

            bY += ROW_GAP;
            addBoundaryField(editX, bY, "Max X:", boundary::setMaxX, boundary.getMaxX());
            addBoundaryField(editX + BOUNDARY_COL_SPACING, bY, "Max Y:", boundary::setMaxY, boundary.getMaxY());
            addBoundaryField(editX + 2 * BOUNDARY_COL_SPACING, bY, "Max Z:", boundary::setMaxZ, boundary.getMaxZ());
            editY = bY + ROW_GAP;
            addHint(editX, editY, "按所站方块判定，站在边界方块上不算出界；某维 Min 大于 Max 时自动交换，六个值须全部正确填写（含 Y）");
            editY += 11;

            // ---- 红队出生点（X/Y/Z 三个输入框，数字输入框不支持空格） ----
            rightScrollables.add(new LabelWidget(editX, editY, Text.literal("红队出生点 (X / Y / Z)"), 9));
            editY += 18;
            int coordFieldWidth = Math.min(80, (contentWidth - 70) / 3);
            for (int i = 0; i < editingMap.getRedSpawns().size(); i++) {
                addSpawnCoordFields(editingMap.getRedSpawns(), i, editX, editY + i * 20, coordFieldWidth);
            }
            editY += Math.max(1, editingMap.getRedSpawns().size()) * 20 + 4;
            ButtonWidget addRedBtn = ButtonWidget.builder(
                    Text.literal("+ 红队出生点"),
                    button -> {
                        editingMap.getRedSpawns().add(BlockPos.ORIGIN);
                        hasUnsavedChanges = true;
                        clearChildren();
                        init();
                    }
            ).dimensions(editX, editY, 120, 20).build();
            rightScrollables.add(addRedBtn);
            editY += ROW_GAP;

            // ---- 蓝队出生点（X/Y/Z 三个输入框） ----
            rightScrollables.add(new LabelWidget(editX, editY, Text.literal("蓝队出生点 (X / Y / Z)"), 9));
            editY += 18;
            for (int i = 0; i < editingMap.getBlueSpawns().size(); i++) {
                addSpawnCoordFields(editingMap.getBlueSpawns(), i, editX, editY + i * 20, coordFieldWidth);
            }
            editY += Math.max(1, editingMap.getBlueSpawns().size()) * 20 + 4;
            ButtonWidget addBlueBtn = ButtonWidget.builder(
                    Text.literal("+ 蓝队出生点"),
                    button -> {
                        editingMap.getBlueSpawns().add(BlockPos.ORIGIN);
                        hasUnsavedChanges = true;
                        clearChildren();
                        init();
                    }
            ).dimensions(editX, editY, 120, 20).build();
            rightScrollables.add(addBlueBtn);
            editY += ROW_GAP;

            // ---- 背景图 Base64 ----
            rightScrollables.add(new LabelWidget(editX, editY, Text.literal("背景图 Base64:")));
            TextFieldWidget backgroundField = new TextFieldWidget(textRenderer, editX + 120, editY, contentWidth - 140, 18, Text.literal(""));
            backgroundField.setText(editingMap.getBackgroundBase64() != null ? editingMap.getBackgroundBase64() : "");
            backgroundField.setChangedListener(s -> {
                editingMap.setBackgroundBase64(s);
                hasUnsavedChanges = true;
            });
            rightScrollables.add(backgroundField);
            editY += ROW_GAP;
        }

        return editY;
    }

    /**
     * 全局配置区域（装备/参数两个标签页）。
     * @param editY 地图编辑器推进后的 y（全局区起始不低于其下）
     * @return 全局区最后一行的 y（globalRowY）
     */
    private int buildGlobalSection(int editY) {
        // ----- 全局配置区域 -----
        int globalStartY = Math.max(editY + 10, contentTop + 10);
        rightScrollables.add(new LabelWidget(contentLeft, globalStartY, Text.literal("⚙ 全局配置"), 9));
        int tabX = contentLeft;
        int globalRowY = globalStartY + 18;

        ButtonWidget gearTab = ButtonWidget.builder(
                Text.literal(globalTab == 0 ? "✅ 装备" : "装备"),
                button -> { globalTab = 0; clearChildren(); init(); }
        ).dimensions(tabX + 120, globalRowY, 70, 20).build();
        rightScrollables.add(gearTab);

        ButtonWidget paramTab = ButtonWidget.builder(
                Text.literal(globalTab == 1 ? "✅ 参数" : "参数"),
                button -> { globalTab = 1; clearChildren(); init(); }
        ).dimensions(tabX + 200, globalRowY, 70, 20).build();
        rightScrollables.add(paramTab);

        globalRowY += 26;

        if (globalTab == 0) {
            rightScrollables.add(new LabelWidget(tabX, globalRowY, Text.literal("当前装备:"), 9));
            globalRowY += 18;

            // 列布局：槽位ID | 物品（可带NBT） | 删除
            int gearSlotFieldWidth = 100;
            int gearItemFieldX = tabX + gearSlotFieldWidth + 10;
            int gearItemFieldWidth = Math.max(100, Math.min(200, contentWidth - 170));
            int gearDelX = gearItemFieldX + gearItemFieldWidth + 10;

            // 表头
            rightScrollables.add(new LabelWidget(tabX, globalRowY, Text.literal("槽位ID"), 9));
            rightScrollables.add(new LabelWidget(gearItemFieldX, globalRowY, Text.literal("物品（可带NBT）"), 9));
            rightScrollables.add(new LabelWidget(gearDelX, globalRowY, Text.literal("删除"), 9));
            globalRowY += 18;

            for (int i = 0; i < globalConfig.getDefaultGear().size(); i++) {
                GlobalConfig.EquipSlot slot = globalConfig.getDefaultGear().get(i);
                final int idx = i;

                // 输入框1：槽位ID（参考 /item replace entity 的 <slot> 参数）
                TextFieldWidget slotField = new TextFieldWidget(textRenderer, tabX, globalRowY + i * 22, gearSlotFieldWidth, 18, Text.literal(""));
                slotField.setText(slot.getSlot());
                slotField.setChangedListener(s -> {
                    globalConfig.getDefaultGear().get(idx).setSlot(s);
                    hasUnsavedChanges = true;
                });
                rightScrollables.add(slotField);

                // 输入框2：物品ID（可带NBT）
                TextFieldWidget itemField = new TextFieldWidget(textRenderer, gearItemFieldX, globalRowY + i * 22, gearItemFieldWidth, 18, Text.literal(""));
                itemField.setMaxLength(256);
                itemField.setText(slot.getItemId());
                itemField.setChangedListener(s -> {
                    globalConfig.getDefaultGear().get(idx).setItemId(s);
                    hasUnsavedChanges = true;
                });
                rightScrollables.add(itemField);

                ButtonWidget delGearBtn = ButtonWidget.builder(
                        Text.literal("×"),
                        button -> {
                            globalConfig.getDefaultGear().remove(idx);
                            hasUnsavedChanges = true;
                            clearChildren();
                            init();
                        }
                ).dimensions(gearDelX, globalRowY + i * 22, 16, 18).build();
                rightScrollables.add(delGearBtn);
            }
            globalRowY += globalConfig.getDefaultGear().size() * 22 + 4;

            ButtonWidget addGearBtn = ButtonWidget.builder(
                    Text.literal("+ 添加装备"),
                    button -> {
                        globalConfig.getDefaultGear().add(new GlobalConfig.EquipSlot(nextFreeContainerSlot(), ""));
                        hasUnsavedChanges = true;
                        clearChildren();
                        init();
                    }
            ).dimensions(tabX, globalRowY, 120, 20).build();
            rightScrollables.add(addGearBtn);
            globalRowY += ROW_GAP;

            rightScrollables.add(new LabelWidget(tabX, globalRowY,
                    Text.literal("槽位ID参考 /item replace entity 的 <slot>，可用 armor.head/chest/legs/feet/body 或 container.0~35"), 9));
            globalRowY += ROW_GAP;

        } else if (globalTab == 1) {
            rightScrollables.add(new LabelWidget(tabX, globalRowY, Text.literal("快速超时(秒):")));
            TextFieldWidget quickTimeoutField = new NumberTextField(tabX + FIELD_OFFSET, globalRowY, 50, 18, Text.literal(""));
            quickTimeoutField.setText(String.valueOf(globalConfig.getQuickTimeout()));
            quickTimeoutField.setChangedListener(s -> {
                if (!s.isEmpty()) {
                    try {
                        globalConfig.setQuickTimeout(Integer.parseInt(s));
                        hasUnsavedChanges = true;
                    } catch (NumberFormatException ignored) {}
                }
            });
            rightScrollables.add(quickTimeoutField);
            globalRowY += ROW_GAP;
            addHint(tabX, globalRowY, "快速匹配超过该秒数无法开局时，进入补位流程（仅加入开启\"允许补位\"的同模式对局）");
            globalRowY += 11;
        }

        return globalRowY;
    }

    // ---------- 内部辅助：字段说明小字（灰色，行高 11px 需调用方计入 editY） ----------
    private void addHint(int x, int y, String text) {
        rightScrollables.add(new LabelWidget(x, y, Text.literal("§8" + text), 9));
    }

    // ---------- 内部辅助：边界坐标输入框（"标签 + 数字输入框"一组） ----------
    private void addBoundaryField(int groupX, int y, String label, IntConsumer setter, int initialValue) {
        rightScrollables.add(new LabelWidget(groupX, y, Text.literal(label)));
        NumberTextField field = new NumberTextField(groupX + BOUNDARY_FIELD_OFFSET, y, 50, 18, Text.literal(""));
        field.setText(String.valueOf(initialValue));
        field.setChangedListener(s -> {
            if (!s.isEmpty()) {
                try {
                    setter.accept(Integer.parseInt(s));
                    hasUnsavedChanges = true;
                } catch (NumberFormatException ignored) {}
            }
        });
        rightScrollables.add(field);
    }

    /**
     * 为单个出生点添加 X/Y/Z 三个数字输入框和删除按钮。
     * 任一输入框变化时，按三个框的当前文本重建 BlockPos 写回列表。
     */
    private void addSpawnCoordFields(List<BlockPos> spawns, int idx, int groupX, int y, int fieldWidth) {
        BlockPos pos = spawns.get(idx);
        int gap = 4;
        NumberTextField xField = new NumberTextField(groupX + 10, y, fieldWidth, 18, Text.literal(""));
        NumberTextField yField = new NumberTextField(groupX + 10 + (fieldWidth + gap), y, fieldWidth, 18, Text.literal(""));
        NumberTextField zField = new NumberTextField(groupX + 10 + (fieldWidth + gap) * 2, y, fieldWidth, 18, Text.literal(""));
        xField.setText(String.valueOf(pos.getX()));
        yField.setText(String.valueOf(pos.getY()));
        zField.setText(String.valueOf(pos.getZ()));

        Runnable update = () -> {
            try {
                int x = Integer.parseInt(xField.getText().trim());
                int yy = Integer.parseInt(yField.getText().trim());
                int z = Integer.parseInt(zField.getText().trim());
                spawns.set(idx, new BlockPos(x, yy, z));
                hasUnsavedChanges = true;
            } catch (Exception ignored) {}
        };
        xField.setChangedListener(s -> update.run());
        yField.setChangedListener(s -> update.run());
        zField.setChangedListener(s -> update.run());
        rightScrollables.add(xField);
        rightScrollables.add(yField);
        rightScrollables.add(zField);

        ButtonWidget delBtn = ButtonWidget.builder(
                Text.literal("×"),
                button -> {
                    spawns.remove(idx);
                    hasUnsavedChanges = true;
                    clearChildren();
                    init();
                }
        ).dimensions(groupX + 10 + (fieldWidth + gap) * 3 + 2, y, 16, 18).build();
        rightScrollables.add(delBtn);
    }

    // ---------- 操作方法 ----------

    /**
     * 【作用】新建一张默认地图：生成唯一 ID（map_N 递增避开已有 ID）、设默认参数，
     * 选中并立即进入编辑态。
     */
    private void addNewMap() {
        MapConfig newMap = new MapConfig();
        // #27 删除中间地图后 "map_N" 可能已存在，递增后缀直到唯一
        int suffix = maps.size() + 1;
        String newId = "map_" + suffix;
        while (isMapIdTaken(newId)) {
            suffix++;
            newId = "map_" + suffix;
        }
        newMap.setId(newId);
        newMap.setDisplayName("新地图");
        newMap.setEnabled(true);
        newMap.setWinCondition(MapConfig.WinCondition.KILLS);
        newMap.setTargetKills(10);
        maps.add(newMap);
        selectedMapIndex = maps.size() - 1;
        editingMap = ConfigScreenSupport.deepCopyMap(newMap);
        hasUnsavedChanges = true;
        clearChildren();
        init();
    }

    /** #27 判断地图 ID 是否已被占用 */
    private boolean isMapIdTaken(String id) {
        return maps.stream().anyMatch(m -> id.equals(m.getId()));
    }

    /**
     * 【作用】删除当前选中的地图：对局中使用的地图先拦截弹窗提示（服务端亦会拒绝），
     * 删除后修正选中索引并重载编辑器。
     */
    private void deleteSelectedMap() {
        if (maps.isEmpty() || selectedMapIndex < 0 || selectedMapIndex >= maps.size()) return;
        MapConfig target = maps.get(selectedMapIndex);
        // 正在对局中使用的地图禁止删除（服务端也会拒绝），弹窗提醒
        if (target != null && ConfigDataCache.getInstance().isMapInUse(target.getId())) {
            confirmDialog.show(
                    Text.literal("§c无法删除"),
                    Text.literal("地图 \"" + target.getDisplayName() + "\" 正在对局中使用，禁止删除！"),
                    null
            );
            return;
        }
        maps.remove(selectedMapIndex);
        if (selectedMapIndex >= maps.size()) {
            selectedMapIndex = maps.size() - 1;
        }
        if (!maps.isEmpty()) {
            editingMap = ConfigScreenSupport.deepCopyMap(maps.get(selectedMapIndex));
        } else {
            editingMap = null;
        }
        hasUnsavedChanges = true;
        clearChildren();
        init();
    }

    /**
     * 【作用】保存配置：先校验装备槽位与地图 ID（错误弹窗拦截），把编辑态回写到选中地图，
     * 再 Gson 序列化为 JSON 分包发送到服务端，成功后清除未保存标记。
     */
    private void saveConfig() {
        // 校验装备槽位ID，错误时弹窗提醒（不进聊天框）
        String slotError = ConfigScreenSupport.validateGearSlots(globalConfig.getDefaultGear());
        if (slotError != null) {
            confirmDialog.show(Text.literal("§c槽位ID错误"), Text.literal(slotError), null);
            return;
        }

        if (editingMap != null && selectedMapIndex >= 0 && selectedMapIndex < maps.size()) {
            maps.set(selectedMapIndex, editingMap);
        }

        // #27 地图 ID 重复检查：发现重复时阻止本次保存
        String idError = ConfigScreenSupport.validateMapIds(maps);
        if (idError != null) {
            confirmDialog.show(Text.literal("§c地图ID重复"), Text.literal(idError), null);
            return;
        }

        try {
            com.google.gson.JsonObject obj = new com.google.gson.JsonObject();
            obj.add("maps", GSON.toJsonTree(maps));
            obj.add("global", GSON.toJsonTree(globalConfig));
            String json = GSON.toJson(obj);
            // 分包发送：C2S payload 有 32768 字节硬限制，大配置直发会被服务端踢线
            ClientNetworkHandler.sendConfigUpdate(json);
            hasUnsavedChanges = false;
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null) {
                client.player.sendMessage(Text.literal("§a配置已发送到服务端！"), false);
            }
        } catch (Exception e) {
            if (MinecraftClient.getInstance().player != null) {
                MinecraftClient.getInstance().player.sendMessage(
                        Text.literal("§c保存失败: " + e.getMessage()), false
                );
            }
        }
    }

    /**
     * 获取一个未被占用的 container.N 槽位（0~35）
     */
    private String nextFreeContainerSlot() {
        for (int i = 0; i <= 35; i++) {
            String slot = "container." + i;
            boolean used = globalConfig.getDefaultGear().stream()
                    .anyMatch(g -> slot.equals(g.getSlot()));
            if (!used) return slot;
        }
        return "container.0";
    }

    /**
     * 【作用】重新加载配置：放行一次被动同步刷新后向服务端请求同步，并立即重建界面。
     */
    private void reloadConfig() {
        // 用户主动重载：放行随后的被动同步刷新，不受未保存修改保护影响
        allowNextSyncRefresh = true;
        ClientNetworkHandler.requestConfigSync();
        refresh();
    }

    /**
     * 【作用】被动刷新入口：有未保存修改时跳过（防覆盖用户编辑），否则重载数据并重建全部控件。
     * 【被谁使用】ClientNetworkHandler（收到服务端配置同步包时回调）；本类 reloadConfig 也调用。
     */
    public void refresh() {
        // #6 被动刷新保护：有未保存修改时跳过被动同步（applyConfigSync）触发的刷新，避免覆盖用户编辑
        if (hasUnsavedChanges && !allowNextSyncRefresh) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null) {
                client.player.sendMessage(Text.literal("§e收到新配置，但你有未保存的修改未应用，已跳过自动刷新"), false);
            }
            return;
        }
        allowNextSyncRefresh = false;
        loadData();
        clearChildren();
        init();
    }

    // ---------- 添加武器到当前编辑地图的商店物品（手持物品，使用物品ID） ----------
    private void addWeaponFromHand() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        var stack = client.player.getMainHandStack();
        if (stack.isEmpty()) {
            client.player.sendMessage(Text.literal("§c请手持一个物品"), false);
            return;
        }
        String itemId = Registries.ITEM.getId(stack.getItem()).toString();
        GlobalConfig.ShopItem item = new GlobalConfig.ShopItem(itemId, 0, 1);
        editingMap.getShopItems().add(item);
        hasUnsavedChanges = true;
        client.player.sendMessage(Text.literal("§a已添加武器: " + stack.getName().getString()), false);
        clearChildren();
        init();
    }

    // ---------- 渲染 ----------

    /**
     * 【作用】主渲染：固定控件直绘；左/右可滚动区各用裁剪区 + 平移实现滚动
     * （控件渲染时传入滚动校正后的鼠标坐标保证 hover 一致）；确认框打开时独占渲染。
     */
    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);

        // 对话框打开时不渲染下层任何内容，彻底避免文字穿透
        if (confirmDialog.isOpen()) {
            confirmDialog.render(context, mouseX, mouseY, delta);
            return;
        }

        // 页面标题跟随主题色（个性化页设置）
        context.drawText(textRenderer, "⚙ 配置界面", 10, 6,
                cn.woshiikun_1145.mcmod.choco.cstmm.client.cache.ClientConfig.getThemeColorArgb(), true);
        context.drawText(textRenderer, "§7地图列表", sidebarWidth / 2 - 25, headerHeight - 4, 0xAAAAAA, true);

        if (editingMap != null) {
            String info = "§7ID: " + editingMap.getId() + " | 出生点: " +
                    editingMap.getRedSpawns().size() + "R / " +
                    editingMap.getBlueSpawns().size() + "B";
            context.drawText(textRenderer, info, contentLeft + 10, headerHeight - 4, 0x888888, true);
        }

        // ---- 固定控件 ----
        for (ClickableWidget widget : fixedWidgets) {
            widget.render(context, mouseX, mouseY, delta);
        }

        // ---- 左侧可滚动区域 ----
        int leftClipX = 0;
        int leftClipY = headerHeight;
        int leftClipWidth = sidebarWidth;
        int leftClipHeight = height - headerHeight - footerHeight;
        context.enableScissor(leftClipX, leftClipY, leftClipX + leftClipWidth, leftClipY + leftClipHeight);

        MatrixStack matrices = context.getMatrices();
        matrices.push();
        matrices.translate(0, -leftScrollOffset, 0);
        for (ClickableWidget btn : leftButtons) {
            // 传入滚动调整后的鼠标坐标，保证 hover 高亮与实际渲染位置一致
            btn.render(context, mouseX, mouseY + leftScrollOffset, delta);
        }
        matrices.pop();
        context.disableScissor();

        // ---- 右侧可滚动区域 ----
        int clipX = contentLeft;
        int clipY = contentTop;
        int clipWidth = contentWidth;
        int clipHeight = contentHeight;
        context.enableScissor(clipX, clipY, clipX + clipWidth, clipY + clipHeight);

        matrices.push();
        matrices.translate(0, -rightScrollOffset, 0);
        for (Drawable drawable : rightScrollables) {
            if (drawable instanceof ClickableWidget widget) {
                // 传入滚动调整后的鼠标坐标，保证 hover 高亮与实际渲染位置一致
                widget.render(context, mouseX, mouseY + rightScrollOffset, delta);
            } else if (drawable instanceof LabelWidget label) {
                label.render(context, mouseX, mouseY, delta);
            }
        }
        matrices.pop();
        context.disableScissor();

        // 版本号按文字实际宽度右对齐（贴右边缘留 5px），避免长版本号溢出屏幕
        String versionText = "v" + ClientHandshakeState.getClientVersion();
        context.drawText(textRenderer, versionText, this.width - textRenderer.getWidth(versionText) - 5, this.height - 15, 0x44FFFFFF, true);
    }

    // ---------- 鼠标事件 ----------

    // 【作用】鼠标 X 是否落在右侧可滚动编辑区内（决定点击/滚轮事件的分区处理）
    private boolean isInRightPanel(double mouseX) {
        return mouseX > contentLeft && mouseX < contentLeft + contentWidth;
    }

    // 【作用】鼠标 X 是否落在左侧地图列表面板内
    private boolean isInLeftPanel(double mouseX) {
        return mouseX < sidebarWidth;
    }

    /**
     * 【作用】鼠标点击分发：确认框打开时独占；其余按固定控件/左面板/右面板顺序
     * 命中处理，滚动区坐标先加回 scrollOffset 再做控件命中。
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (confirmDialog.isOpen()) {
            confirmDialog.handleMouseClicked(mouseX, mouseY, button);
            return true; // 对话框打开时阻断点击穿透
        }

        for (ClickableWidget widget : fixedWidgets) {
            if (widget.mouseClicked(mouseX, mouseY, button)) {
                this.setFocused(widget);
                if (button == 0) this.setDragging(true);
                return true;
            }
        }

        if (isInLeftPanel(mouseX) && mouseY >= headerHeight && mouseY < height - footerHeight) {
            double adjustedY = mouseY + leftScrollOffset;
            for (ClickableWidget btn : leftButtons) {
                if (btn.mouseClicked(mouseX, adjustedY, button)) {
                    this.setFocused(btn);
                    if (button == 0) this.setDragging(true);
                    return true;
                }
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }

        if (isInRightPanel(mouseX) && mouseY >= contentTop && mouseY < contentTop + contentHeight) {
            double adjustedY = mouseY + rightScrollOffset;
            for (Drawable drawable : rightScrollables) {
                if (drawable instanceof ClickableWidget widget) {
                    if (widget.mouseClicked(mouseX, adjustedY, button)) {
                        // 关键：将焦点设置到被点击的控件，否则输入框无法获得键盘输入
                        this.setFocused(widget);
                        if (button == 0) this.setDragging(true);
                        return true;
                    }
                }
            }
            // 点击空白处时取消输入框焦点
            this.setFocused(null);
            return super.mouseClicked(mouseX, mouseY, button);
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (confirmDialog.isOpen()) {
            confirmDialog.handleMouseReleased(mouseX, mouseY, button);
            return true;
        }
        if (isInLeftPanel(mouseX)) {
            double adjustedY = mouseY + leftScrollOffset;
            for (ClickableWidget btn : leftButtons) {
                if (btn.mouseReleased(mouseX, adjustedY, button)) return true;
            }
        }
        if (isInRightPanel(mouseX)) {
            double adjustedY = mouseY + rightScrollOffset;
            for (Drawable drawable : rightScrollables) {
                if (drawable instanceof ClickableWidget widget) {
                    if (widget.mouseReleased(mouseX, adjustedY, button)) return true;
                }
            }
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (confirmDialog.isOpen()) return true;
        if (isInLeftPanel(mouseX)) {
            double adjustedY = mouseY + leftScrollOffset;
            for (ClickableWidget btn : leftButtons) {
                if (btn.mouseDragged(mouseX, adjustedY, button, deltaX, deltaY)) return true;
            }
        }
        if (isInRightPanel(mouseX)) {
            double adjustedY = mouseY + rightScrollOffset;
            for (Drawable drawable : rightScrollables) {
                if (drawable instanceof ClickableWidget widget) {
                    if (widget.mouseDragged(mouseX, adjustedY, button, deltaX, deltaY)) return true;
                }
            }
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (confirmDialog.isOpen()) return true;
        if (isInLeftPanel(mouseX)) {
            int maxScroll = Math.max(0, leftTotalHeight - (height - headerHeight - footerHeight));
            leftScrollOffset = MathHelper.clamp((int)(leftScrollOffset - verticalAmount * 20), 0, maxScroll);
            return true;
        }
        if (isInRightPanel(mouseX)) {
            int maxScroll = Math.max(0, rightTotalHeight - contentHeight);
            rightScrollOffset = MathHelper.clamp((int)(rightScrollOffset - verticalAmount * 20), 0, maxScroll);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (confirmDialog.isOpen()) {
            // 对话框打开时屏蔽其他按键（如关闭界面的 ESC）
            return confirmDialog.handleKeyPressed(keyCode, scanCode, modifiers);
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    // 有未保存修改时退出先弹确认框，确认后才真正关闭
    @Override
    public void close() {
        if (hasUnsavedChanges) {
            confirmDialog.show(
                    Text.literal("未保存的修改"),
                    Text.literal("退出将丢失当前更改，确定退出吗？"),
                    () -> ConfigScreen.super.close()
            );
        } else {
            super.close();
        }
    }

    // 窗口尺寸变化：更新宽高并整体重建控件（init 重建，编辑数据保留在字段中）
    @Override
    public void resize(MinecraftClient client, int width, int height) {
        this.width = width;
        this.height = height;
        clearChildren();
        init();
    }
}