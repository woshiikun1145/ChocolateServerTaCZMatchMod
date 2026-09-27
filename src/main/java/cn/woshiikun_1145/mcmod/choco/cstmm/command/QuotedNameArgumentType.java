package cn.woshiikun_1145.mcmod.choco.cstmm.command;

import cn.woshiikun_1145.mcmod.choco.cstmm.Cstmm;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 【作用】"带引号战队名"参数类型：读取"名字文本 + 闭引号"（开引号由命令树中的字面量 {@code "}
 *         节点消耗），返回去引号后的名字（支持 \\" 与 \\\\ 转义）；未闭合/空名字抛语法异常，
 *         从而在 Brigadier 层强制"战队名必须带引号"。
 *         自带 Tab 建议：候选 = 裸名 + 闭引号（如 {@code Team Kun"}），补全后即为合法的
 *         引号全名——建议必须以已输入内容为前缀才能被客户端展示，而名字建议从引号后开始
 *         恰好满足这一点（这也是不用 string() 的原因：string() 的引号建议无法在无引号输入时出现）。
 * 【被谁使用】ModCommands 的 get/edit/delete clan 命令树（literal " → 本类型 → 字段/值参数）；
 *           Cstmm#onInitialize 经 Fabric ArgumentTypeRegistry 注册（服务端命令树同步到客户端时
 *           需要两端都知道该类型，否则断连）。解析由服务端执行；建议 provider 双端执行
 *           （数据源 hintClanNames：客户端用 clan_hints 缓存，服务端用 ClanManager）。
 */
public final class QuotedNameArgumentType implements ArgumentType<String> {

    /** 默认示例名单（数据源不可用时的兜底，避免建议完全为空无从下手） */
    private static final List<String> FALLBACK_NAMES = List.of("MyClan");

    private QuotedNameArgumentType() {
    }

    /** 【作用】创建本参数类型实例（ConstantArgumentSerializer 的恒等工厂）。 */
    public static QuotedNameArgumentType quotedName() {
        return new QuotedNameArgumentType();
    }

    /**
     * 【作用】解析"名字 + 闭引号"：从当前位置（开引号已被字面量消耗）扫描到未转义闭引号，
     *         返回去引号名字；未闭合时把 reader 回滚到参数起点再抛异常——回滚保证
     *         建议 provider 能拿到"已输入的名字前缀"做过滤。
     */
    @Override
    public String parse(StringReader reader) throws CommandSyntaxException {
        int start = reader.getCursor();
        // 兼容直连场景（未经过字面量节点）：若当前就是引号则先消耗
        if (reader.canRead() && reader.peek() == '"') {
            reader.skip();
        }
        StringBuilder name = new StringBuilder();
        while (reader.canRead()) {
            char c = reader.read();
            if (c == '\\' && reader.canRead()) {
                // 转义：\" → "、\\ → \（取转义符后的原字符）
                name.append(reader.read());
                continue;
            }
            if (c == '"') {
                // 引号内名字 trim 两侧空白：容忍误输入（如 " Team Kun"），避免查不到战队
                return name.toString().trim();
            }
            name.append(c);
        }
        // 未闭合：回滚到参数起点（建议过滤需要名字前缀），抛"缺少闭引号"
        reader.setCursor(start);
        throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.readerExpectedEndOfQuote().createWithContext(reader);
    }

    /**
     * 【作用】名字建议：remaining 为引号后的已输入前缀（未闭合名字/空）。
     *         对每个以该前缀开头的战队名建议 {@code 名字 + 闭引号}——Tab 选中即得
     *         完整合法的引号全名。名字已闭合（含未转义闭引号）时不再建议（后续是字段）。
     * 【被谁使用】命令树 name 参数节点（解析成功与失败路径都会被 Brigadier 调用）。
     */
    @Override
    public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context,
                                                              SuggestionsBuilder builder) {
        try {
            String remaining = builder.getRemaining();
            if (hasUnescapedQuote(remaining)) {
                // 名字已闭合：本参数阶段结束，无名字建议（后续字段/值建议由各自 provider 负责）
                return builder.buildFuture();
            }
            String prefix = remaining.trim().toLowerCase();
            for (String name : hintClanNames()) {
                if (name.toLowerCase().startsWith(prefix)) {
                    // 建议为完整干净的名字+闭引号：Tab 选中后自动纠正引号内多余空白
                    builder.suggest(name + "\"");
                }
            }
            return builder.buildFuture();
        } catch (Exception e) {
            // 补全期异常不影响命令本身：降级为无建议（Brigadier 会静默吞异常，这里留日志便于排查）
            Cstmm.LOGGER.warn("[CSTMM - Commands] quoted name suggestions failed", e);
            return Suggestions.empty();
        }
    }

    /** 【作用】判断文本中是否存在未转义的闭引号（名字是否已闭合）。 */
    private static boolean hasUnescapedQuote(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') { i++; continue; }
            if (c == '"') return true;
        }
        return false;
    }

    /** 【作用】全部战队名建议数据源（CLIENT=clan_hints 缓存；SERVER=ClanManager；不可用给兜底示例）。 */
    private static Collection<String> hintClanNames() {
        try {
            List<String> names = ModCommands.hintClanNames();
            return names.isEmpty() ? FALLBACK_NAMES : names;
        } catch (Exception e) {
            return FALLBACK_NAMES;
        }
    }

    /** 【作用】Brigadier 需要的示例值（getExamples，用于错误消息占位）。 */
    @Override
    public Collection<String> getExamples() {
        return List.of("\"Team Kun\"");
    }
}
