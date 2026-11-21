package cn.woshiikun_1145.mcmod.choco.cstmm.util;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 【作用】商店/装备物品串的统一解析与导出工具（服务端发放与客户端预览共用同一套规则）：
 *         主格式为 /give 命令格式 "物品ID[组件SNBT] [数量]"（如
 *         minecraft:netherite_sword[minecraft:enchantments={levels:{"minecraft:sharpness":5}}] 1），
 *         同时兼容旧格式：完整 SNBT（{id:...,components:{...}}，含 1.20 tag 自动迁移）、
 *         "物品ID 数量"、纯物品ID、"物品ID{组件}"。
 * 【被谁使用】EquipmentManager（服务端购买/默认装备解析发放）、ConfigScreen（手持导出与保存预校验）、
 *           ShopScreen / ShopPreviewWidget（客户端购买页图标与名称预览）。
 */
public final class ItemStrings {

    // 解析失败诊断日志（ItemStack 解码失败时记录原始串；服务端/客户端共用）
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(ItemStrings.class);

    private ItemStrings() {}

    // unquoted SNBT 键允许的字符（与 /give 输出风格一致；组件键含冒号 minecraft:xxx 也按裸键输出，
    // /give 与本类解析器均接受；解析侧由本类自带的顶层切分器处理键，不依赖 StringNbtReader 的键解析）
    private static final String KEY_ALLOWED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._+-:";

    /**
     * 【作用】物品串 → ItemStack（解析失败返回空栈）。
     *         lookup 为 null 时（极端场景下的纯文本预览）跳过组件，仅按物品ID返回基础物品。
     */
    public static ItemStack toStack(String raw, @Nullable RegistryWrapper.WrapperLookup lookup) {
        if (raw == null || raw.isBlank()) return ItemStack.EMPTY;
        String s = raw.trim();

        // ---- 旧格式①：完整 SNBT（{ 开头）----
        if (s.startsWith("{")) {
            if (lookup == null) return ItemStack.EMPTY;
            try {
                NbtElement parsed = NbtHelper.fromNbtProviderString(s);
                if (!(parsed instanceof NbtCompound compound)) return ItemStack.EMPTY;
                // 1.20 及更早的 tag NBT 先转 components 再解析（迁移失败回退原样）
                if (compound.contains("tag") && !compound.contains("components")) {
                    return ItemStack.fromNbt(lookup, migrateLegacyToComponents(compound)).orElse(ItemStack.EMPTY);
                }
                return ItemStack.fromNbt(lookup, compound).orElse(ItemStack.EMPTY);
            } catch (Exception e) {
                return ItemStack.EMPTY;
            }
        }

        // ---- /give 格式：id[components] [count]；兼容旧 "id{components}" 写法 ----
        String idPart = s;
        String compsPart = null;
        String rest = "";
        int bracket = indexOfTopLevel(s, '[');
        if (bracket >= 0) {
            int close = matchBracket(s, bracket);
            if (close < 0) return ItemStack.EMPTY;
            idPart = s.substring(0, bracket).trim();
            compsPart = s.substring(bracket + 1, close).trim();
            rest = s.substring(close + 1).trim();
        } else {
            int brace = s.indexOf('{');
            if (brace >= 0) {
                int close = matchBracket(s, brace);
                if (close < 0) return ItemStack.EMPTY;
                idPart = s.substring(0, brace).trim();
                compsPart = s.substring(brace + 1, close).trim();
                rest = s.substring(close + 1).trim();
            }
        }

        // ---- 数量：尾随纯数字（"/give 的 count" 或旧 "id 数量" 后缀）----
        int count = 1;
        if (!rest.isEmpty()) {
            Integer parsed = tryParseInt(rest);
            if (parsed == null) return ItemStack.EMPTY;
            count = parsed;
        } else {
            int space = idPart.lastIndexOf(' ');
            if (space > 0) {
                Integer parsed = tryParseInt(idPart.substring(space + 1).trim());
                if (parsed != null) {
                    count = parsed;
                    idPart = idPart.substring(0, space).trim();
                }
            }
        }
        if (count < 1) count = 1;

        // ---- 物品ID ----
        if (idPart.isEmpty()) return ItemStack.EMPTY;
        if (!idPart.contains(":")) idPart = "minecraft:" + idPart;
        Identifier id = Identifier.tryParse(idPart);
        if (id == null) return ItemStack.EMPTY;
        Item item = Registries.ITEM.get(id);
        if (item == Items.AIR) return ItemStack.EMPTY;

        // ---- 无组件：直接按基础物品 + 数量返回 ----
        if (compsPart == null || compsPart.isEmpty()) {
            ItemStack plain = new ItemStack(item);
            plain.setCount(Math.min(count, plain.getMaxCount()));
            return plain;
        }

        // ---- 组件解析（顶层逗号切分 → key=value，值按 SNBT 解析）----
        if (lookup == null) {
            // 无注册表可用时退化为基础物品（仅影响纯文本预览场景）
            ItemStack plain = new ItemStack(item);
            plain.setCount(Math.min(count, plain.getMaxCount()));
            return plain;
        }
        NbtCompound components = new NbtCompound();
        for (String pair : splitTopLevel(compsPart, ',')) {
            if (pair.isBlank()) return ItemStack.EMPTY;
            int eq = indexOfTopLevel(pair, '=');
            if (eq <= 0) return ItemStack.EMPTY;
            String key = unquote(pair.substring(0, eq).trim());
            String value = pair.substring(eq + 1).trim();
            if (key.isEmpty() || value.isEmpty()) return ItemStack.EMPTY;
            try {
                NbtElement parsed = parseSnbtElement(value);
                if (parsed == null) return ItemStack.EMPTY;
                components.put(key, parsed);
            } catch (Exception e) {
                return ItemStack.EMPTY;
            }
        }

        NbtCompound root = new NbtCompound();
        root.putString("id", Registries.ITEM.getId(item).toString());
        root.putInt("count", 1);
        root.put("components", components);
        ItemStack result = ItemStack.fromNbt(lookup, root).orElse(ItemStack.EMPTY);
        if (result.isEmpty()) {
            // 组件串结构合法但 ItemStack 解码失败（如未注册的组件类型/值不合法）：记录原始串便于排查
            LOGGER.warn("[CSTMM - ItemStrings] ItemStack decode failed for: {}", raw);
        }
        if (!result.isEmpty()) {
            result.setCount(Math.min(count, result.getMaxCount()));
        }
        return result;
    }

    /**
     * 【作用】物品串结构预校验（不构造 ItemStack）：物品ID 存在 + 可选组件每对键值可按 SNBT 解析。
     *         供配置界面保存前警告"发放时会静默失败"的条目。
     */
    public static boolean isPlausible(String raw) {
        if (raw == null || raw.isBlank()) return false;
        String s = raw.trim();

        if (s.startsWith("{")) {
            try {
                return NbtHelper.fromNbtProviderString(s) instanceof NbtCompound;
            } catch (Exception e) {
                return false;
            }
        }

        String idPart = s;
        String compsPart = null;
        int bracket = indexOfTopLevel(s, '[');
        if (bracket >= 0) {
            int close = matchBracket(s, bracket);
            if (close < 0) return false;
            idPart = s.substring(0, bracket).trim();
            compsPart = s.substring(bracket + 1, close).trim();
        } else {
            int brace = s.indexOf('{');
            if (brace >= 0) {
                int close = matchBracket(s, brace);
                if (close < 0) return false;
                idPart = s.substring(0, brace).trim();
                compsPart = s.substring(brace + 1, close).trim();
            }
        }

        // 尾随数量剥离（复用与 toStack 相同的规则）
        int space = idPart.lastIndexOf(' ');
        if (space > 0 && tryParseInt(idPart.substring(space + 1).trim()) != null) {
            idPart = idPart.substring(0, space).trim();
        }
        if (idPart.isEmpty()) return false;
        if (!idPart.contains(":")) idPart = "minecraft:" + idPart;
        Identifier id = Identifier.tryParse(idPart);
        if (id == null || Registries.ITEM.get(id) == Items.AIR) return false;

        if (compsPart == null || compsPart.isEmpty()) return true;
        for (String pair : splitTopLevel(compsPart, ',')) {
            if (pair.isBlank()) return false;
            int eq = indexOfTopLevel(pair, '=');
            if (eq <= 0) return false;
            String key = unquote(pair.substring(0, eq).trim());
            String value = pair.substring(eq + 1).trim();
            if (key.isEmpty() || value.isEmpty()) return false;
            try {
                if (parseSnbtElement(value) == null) return false;
            } catch (Exception e) {
                return false;
            }
        }
        return true;
    }

    /**
     * 【作用】ItemStack → /give 格式物品串（不含 "/give @s " 前缀）：
     *         "id[组件1=值,组件2=值] 数量"；无组件时省略方括号，数量为 1 时省略尾数。
     *         组件值取自 stack.encode 的 components 标签（附魔/自定义名/耐久等完整保留）。
     */
    public static String fromStack(ItemStack stack, RegistryWrapper.WrapperLookup lookup) {
        NbtElement encoded = stack.encode(lookup);
        if (!(encoded instanceof NbtCompound full)) return stack.getItem().toString();
        String id = full.getString("id");
        StringBuilder sb = new StringBuilder(id);
        NbtCompound comps = full.getCompound("components");
        if (comps != null && !comps.isEmpty()) {
            sb.append('[');
            boolean first = true;
            for (String key : comps.getKeys()) {
                if (!first) sb.append(',');
                first = false;
                // 组件值经 {"v":值} 复合包装后取 SNBT（toString 对顶层字符串/列表等
                // 非复合元素可能不带 SNBT 引号，包装保证任意类型值都输出合法 SNBT）
                NbtCompound wrapper = new NbtCompound();
                wrapper.put("v", comps.get(key));
                String wrapped = wrapper.toString();
                String valueSnbt = wrapped.startsWith("{\"v\":") && wrapped.endsWith("}")
                        ? wrapped.substring(5, wrapped.length() - 1)
                        : comps.get(key).toString();
                sb.append(quoteKeyIfNeeded(key)).append('=').append(valueSnbt);
            }
            sb.append(']');
        }
        if (stack.getCount() > 1) {
            sb.append(' ').append(stack.getCount());
        }
        return sb.toString();
    }

    // ==================== 内部工具 ====================

    /**
     * 【作用】解析任意 SNBT 值（组件 value 可能是字符串/列表/数字/复合等任意类型）：
     *         NbtHelper.fromNbtProviderString 只接受复合根（此前组件值为字符串/列表时直接抛
     *         CommandSyntaxException，导致"格式正确仍无法解析"），这里把值包进 {"v":值}
     *         再取 v，用同一已验证可用的解析器兼容全部值类型。
     * @return 解析出的 NbtElement；输入非法时抛 CommandSyntaxException
     */
    private static NbtElement parseSnbtElement(String snbt) throws CommandSyntaxException {
        return NbtHelper.fromNbtProviderString("{v:" + snbt + "}").get("v");
    }

    /** 键含 unquoted 允许字符之外的字符（空格等异常键，组件键的冒号已放行）时加双引号兜底。 */
    private static String quoteKeyIfNeeded(String key) {
        for (int i = 0; i < key.length(); i++) {
            if (KEY_ALLOWED.indexOf(key.charAt(i)) < 0) {
                return "\"" + key.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
            }
        }
        return key;
    }

    /** 去除键两侧成对双引号（解析容错：接受 "minecraft:xxx"=v 与 minecraft:xxx=v 两种写法）。 */
    private static String unquote(String key) {
        if (key.length() >= 2 && key.startsWith("\"") && key.endsWith("\"")) {
            return key.substring(1, key.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return key;
    }

    /** 跳过引号与括号嵌套，找目标字符的顶层出现位置；找不到返回 -1。
     *  目标字符优先于深度计数判定（否则搜索 '[' / '{' 时开括号会先走计数分支、永不命中）。 */
    private static int indexOfTopLevel(String s, char target) {
        int depthSquare = 0, depthCurly = 0;
        boolean inQuote = false;
        char quoteChar = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inQuote) {
                if (c == '\\') {
                    i++;
                } else if (c == quoteChar) {
                    inQuote = false;
                }
                continue;
            }
            // 顶层目标字符直接命中（含 '[' / '{' 自身）
            if (c == target && depthSquare == 0 && depthCurly == 0) {
                return i;
            }
            if (c == '\'' || c == '"') {
                inQuote = true;
                quoteChar = c;
            } else if (c == '[') {
                depthSquare++;
            } else if (c == ']') {
                depthSquare--;
            } else if (c == '{') {
                depthCurly++;
            } else if (c == '}') {
                depthCurly--;
            }
        }
        return -1;
    }

    /**
     * 从 open 位置的 '[' 或 '{' 出发找配对闭合字符的下标；找不到返回 -1。
     * 同时跟踪 [] 与 {} 两类嵌套深度（组件值内部可能含方括号列表如 SpawnPotentials:[]、
     * 属性修饰符 [...]，只数同型括号会把值内的 ']' 误判为组件串结尾）。
     */
    private static int matchBracket(String s, int open) {
        char openChar = s.charAt(open);
        char closeChar = openChar == '[' ? ']' : '}';
        int depthSquare = 0, depthCurly = 0;
        boolean inQuote = false;
        char quoteChar = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inQuote) {
                if (c == '\\') {
                    i++;
                } else if (c == quoteChar) {
                    inQuote = false;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                inQuote = true;
                quoteChar = c;
            } else if (c == '[') {
                depthSquare++;
            } else if (c == ']') {
                depthSquare--;
            } else if (c == '{') {
                depthCurly++;
            } else if (c == '}') {
                depthCurly--;
            }
            // open 型深度归零即配对闭合（open 字符自身已计入深度）
            boolean closed = openChar == '[' ? depthSquare == 0 : depthCurly == 0;
            if (closed && c == closeChar) {
                return i;
            }
        }
        return -1;
    }

    /** 按顶层分隔符切分（跳过引号与 [] {} 嵌套内部），用于组件对 key=value 的拆分。 */
    private static List<String> splitTopLevel(String s, char separator) {
        List<String> parts = new ArrayList<>();
        int depthSquare = 0, depthCurly = 0;
        boolean inQuote = false;
        char quoteChar = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inQuote) {
                if (c == '\\') {
                    i++;
                } else if (c == quoteChar) {
                    inQuote = false;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                inQuote = true;
                quoteChar = c;
            } else if (c == '[') {
                depthSquare++;
            } else if (c == ']') {
                depthSquare--;
            } else if (c == '{') {
                depthCurly++;
            } else if (c == '}') {
                depthCurly--;
            } else if (c == separator && depthSquare == 0 && depthCurly == 0) {
                parts.add(s.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(s.substring(start));
        return parts;
    }

    private static Integer tryParseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 旧版（1.20 及更早）tag NBT → 1.21 components 数据组件迁移（自 EquipmentManager 迁入）：
     * 常用键自动转换（附魔数组→levels 映射、Unbreakable→unbreakable 空组件、
     * display.Name/Lore→custom_name/lore）；其余未知键按小写原样搬入。
     */
    private static NbtCompound migrateLegacyToComponents(NbtCompound legacy) {
        NbtCompound tag = legacy.getCompound("tag");
        NbtCompound components = new NbtCompound();

        for (String key : tag.getKeys()) {
            switch (key) {
                case "Enchantments" -> components.put("enchantments", legacyEnchToLevels(tag, key));
                case "StoredEnchantments" -> components.put("stored_enchantments", legacyEnchToLevels(tag, key));
                case "Unbreakable" -> components.put("unbreakable", new NbtCompound());
                case "display" -> {
                    NbtCompound display = tag.getCompound("display");
                    if (display.contains("Name")) {
                        components.put("custom_name", display.get("Name"));
                    }
                    if (display.contains("Lore")) {
                        components.put("lore", display.get("Lore"));
                    }
                }
                default -> components.put(key.toLowerCase(java.util.Locale.ROOT), tag.get(key));
            }
        }

        NbtCompound out = new NbtCompound();
        out.putString("id", legacy.getString("id"));
        if (legacy.contains("count")) {
            out.put("count", legacy.get("count"));
        } else {
            out.putByte("count", (byte) 1);
        }
        out.put("components", components);
        return out;
    }

    /** 旧附魔数组 [{id:"x",lvl:5}] → 1.21 组件格式 {levels:{"x":5}}（StoredEnchantments 同构）。 */
    private static NbtCompound legacyEnchToLevels(NbtCompound tag, String listKey) {
        NbtCompound levels = new NbtCompound();
        for (NbtElement e : tag.getList(listKey, 10)) {
            if (e instanceof NbtCompound ench && ench.contains("id") && ench.contains("lvl")) {
                levels.putShort(ench.getString("id"), ench.getShort("lvl"));
            }
        }
        NbtCompound out = new NbtCompound();
        out.put("levels", levels);
        return out;
    }
}
