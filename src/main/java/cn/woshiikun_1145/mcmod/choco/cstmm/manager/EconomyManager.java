package cn.woshiikun_1145.mcmod.choco.cstmm.manager;

import cn.woshiikun_1145.mcmod.choco.cstmm.data.MatchSession;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.config.GlobalConfig;
import cn.woshiikun_1145.mcmod.choco.cstmm.data.config.MapConfig;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.NetworkHandler;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.List;
import java.util.UUID;

/**
 * 【作用】爆破图经济系统（仅 gameType=BOMB 的地图对局，价格=匹配内货币非服务器货币）：
 *         CS 标准金额规则（全局可配 GlobalConfig.BombEconomy）——起始金/上限、击杀奖励
 *         （休闲减半）、回合胜负金（全歼/超时 3250，爆炸/拆除 3500）、连败递增败金
 *         （1400 起 +500/回合，封顶 3400，胜方清零）、T 方存活且从未下包战败 0 败金、
 *         安放/拆除个人奖励、购买窗口（冻结时间 + 开局后 N 秒，安放后禁买）与商店扣费购买。
 * 【被谁使用】BombRoundManager（开局/半场/加时重置、回合结算）、NetworkHandler（BUY_ITEM
 *           爆破分支与 REQUEST_SHOP 余额下发）、阶段③（安放/拆除奖励回调）。仅服务端。
 */
public class EconomyManager {

    private static EconomyManager instance;

    private EconomyManager() {}

    public static EconomyManager getInstance() {
        if (instance == null) {
            instance = new EconomyManager();
        }
        return instance;
    }

    /** 回合胜负类型（决定胜利金档位）：全歼/超时 → 淘汰金；爆炸/拆除 → 目标金。 */
    public enum WinKind { ELIMINATION, OBJECTIVE }

    /** 取全局经济参数（ConfigManager 缺配置时 BombEconomy 自带 CS 默认值兜底）。 */
    public GlobalConfig.BombEconomy economy() {
        GlobalConfig config = ConfigManager.getInstance().getGlobalConfig();
        return config != null ? config.getBombEconomy() : new GlobalConfig.BombEconomy();
    }

    // ==================== 初始化 / 重置 ====================

    /**
     * 【作用】爆破对局开局初始化：全员发起始金额、双方连败清零。
     * 【被谁使用】BombRoundManager#startFirstRound。仅服务端。
     */
    public void initSession(MatchSession session) {
        GlobalConfig.BombEconomy econ = economy();
        session.resetLoseStreaks();
        for (UUID uuid : session.getAllPlayers()) {
            session.setPlayerMoney(uuid, econ.getStartMoney());
        }
    }

    /**
     * 【作用】半场/加时重置：全员金额回起始值、连败清零
     *         （竞技第 13 回合攻防转换、进入加时、加时继续投票通过时调用）。
     * 【被谁使用】BombRoundManager#startRound / decideSeriesState / onOvertimeVoteResult。仅服务端。
     */
    public void resetForPhaseChange(MatchSession session) {
        GlobalConfig.BombEconomy econ = economy();
        session.resetLoseStreaks();
        for (UUID uuid : session.getAllPlayers()) {
            session.setPlayerMoney(uuid, econ.getStartMoney());
        }
        broadcastMoney(session, "§6经济已重置（$" + econ.getStartMoney() + "）");
    }

    // ==================== 奖励 ====================

    /**
     * 【作用】击杀奖励：击杀者 +killReward（休闲模式按 casualMoneyMultiplier 缩放）。
     * 【被谁使用】EventListener 有效击杀路径（爆破对局分支，阶段②接入）。仅服务端。
     */
    public void awardKill(MatchSession session, UUID killerUuid) {
        ensureMoney(session, killerUuid);
        GlobalConfig.BombEconomy econ = economy();
        int amount = session.isCompetitive()
                ? econ.getKillReward()
                : (int) Math.round(econ.getKillReward() * econ.getCasualMoneyMultiplier());
        addMoney(session, killerUuid, amount);
    }

    /**
     * 【作用】安放 C4 个人奖励：安放者 +plantReward（战败也保留；阶段③安放成功时回调）。
     * 【被谁使用】阶段③安放交互成功路径。仅服务端。
     */
    public void awardPlant(MatchSession session, UUID planterUuid) {
        addMoney(session, planterUuid, economy().getPlantReward());
    }

    /**
     * 【作用】拆除成功个人奖励：拆除者 +defuseReward（与回合胜利金叠加；阶段③拆除成功时回调）。
     * 【被谁使用】阶段③拆除交互成功路径。仅服务端。
     */
    public void awardDefuse(MatchSession session, UUID defuserUuid) {
        addMoney(session, defuserUuid, economy().getDefuseReward());
    }

    // ==================== 回合结算 ====================

    /**
     * 【作用】回合经济结算（endRound 内广播回合结果后调用）：
     *         胜方全队胜利金（淘汰金/目标金，休闲缩放）；败方连败金（base + streak × step，
     *         封顶 max，休闲缩放；T 方战败时存活且从未下包者 0）；胜方连败清零、败方 +1。
     * 【被谁使用】BombRoundManager#endRound。仅服务端。
     */
    public void settleRound(MatchSession session, int winnerTeam, WinKind winKind) {
        GlobalConfig.BombEconomy econ = economy();
        double mult = session.isCompetitive() ? 1.0 : econ.getCasualMoneyMultiplier();
        int winBonus = winKind == WinKind.OBJECTIVE
                ? econ.getWinBonusObjective() : econ.getWinBonusElimination();
        int loserTeam = session.getOppositeTeam(winnerTeam);

        // 败金先按当前连败数计算再递增（首败拿 base）
        int streak = loserTeam == 1 ? session.getTeam1LoseStreak() : session.getTeam2LoseStreak();
        int loseBonus = (int) Math.min(
                Math.round((econ.getLoseBonusBase() + (long) streak * econ.getLoseBonusStep()) * mult),
                Math.round(econ.getLoseBonusMax() * mult));

        for (UUID uuid : session.getAllPlayers()) {
            ensureMoney(session, uuid);
            int team = session.getPlayerTeam(uuid);
            boolean isWinner = team == winnerTeam;
            int bonus;
            if (isWinner) {
                bonus = (int) Math.round(winBonus * mult);
            } else {
                // CS 规则：T 方战败且存活、本回合从未安放 C4 → 该玩家拿 0 败金
                boolean tSurvivorNoPlant = session.isTTeam(loserTeam)
                        && !session.isBombEverPlantedThisRound()
                        && !session.isDeadThisRound(uuid);
                bonus = tSurvivorNoPlant ? 0 : loseBonus;
            }
            addMoney(session, uuid, bonus);
        }

        session.addLoseStreak(loserTeam);
        // 胜方连败清零（连败金按败方旧 streak 计算后再清零）
        session.resetLoseStreak(winnerTeam);
    }

    // ==================== 购买 ====================

    /**
     * 【作用】购买窗口判定：冻结阶段可买；战斗阶段开局前 N 秒可买（N=buyWindowSeconds）；
     *         炸弹安放后与回合间歇一律禁买。CS 规则同款。
     * 【被谁使用】tryPurchase、ShopDataS2CPayload 窗口状态下发。仅服务端。
     */
    public boolean isBuyWindowOpen(MatchSession session, MapConfig map) {
        if (session == null || map == null || !map.isBombMap()) return false;
        if (session.isBombPlanted() || session.isBombEverPlantedThisRound()) return false;
        if (session.getPhase() == MatchSession.GamePhase.PREPARING) return true;
        if (session.getPhase() == MatchSession.GamePhase.FIGHTING && session.getRoundEndCounter() <= 0) {
            int elapsed = map.getBombSettings().getRoundTimeSeconds() - session.getRemainingSeconds();
            return elapsed <= economy().getBuyWindowSeconds();
        }
        return false;
    }

    /**
     * 【作用】爆破图商店扣费购买：校验购买窗口与余额 → 扣钱 → 发放；
     *         cstmm:bomb_defuser（拆弹装备）仅 CT 可购，购买后打玩家 tag（不是物品，不入背包）；
     *         cstmm:c4 不可通过商店购买（由回合开始系统指派）。其余商品解析发放（失败退款）。
     *         购买成功后返回 true（NetworkHandler 据此重发商店数据）。
     * 【被谁使用】NetworkHandler BUY_ITEM 爆破分支。仅服务端。
     */
    public boolean tryPurchase(ServerPlayerEntity player, MatchSession session, MapConfig map, int itemIndex) {
        ensureMoney(session, player.getUuid());
        if (!isBuyWindowOpen(session, map)) {
            player.sendMessage(Text.literal("§c当前不在购买窗口内（冻结时间 + 开局 "
                    + economy().getBuyWindowSeconds() + " 秒）"), false);
            return false;
        }
        UUID uuid = player.getUuid();
        boolean playerIsT = session.isTTeam(session.getPlayerTeam(uuid));

        // 购买页第 1 列固定项（itemIndex=-1）：拆弹装备（仅 CT，tag 化不入背包）
        if (itemIndex < 0) {
            int price = economy().getDefuserPrice();
            if (playerIsT) {
                player.sendMessage(Text.literal("§c拆弹装备仅 CT 方可购买"), false);
                return false;
            }
            int money = session.getPlayerMoney(uuid);
            if (money < price) {
                player.sendMessage(Text.literal("§c余额不足：需要 $" + price + "，当前 $" + money), false);
                return false;
            }
            session.setPlayerMoney(uuid, money - price);
            player.addCommandTag(BombInteractionManager.DEFUSE_KIT_TAG);
            player.sendMessage(Text.literal("§a购买成功：已获得拆弹装备（-$" + price
                    + "，余额 $" + session.getPlayerMoney(uuid) + "）"), false);
            return true;
        }

        // 分类列平铺列表（与该队伍购买页下发顺序一致：T/CT 独立配置的列顺序拼接；
        // 未配置队伍列时回退通用列/旧版平铺 shopItems）
        List<GlobalConfig.ShopItem> items = map.getBombFlatShopItems(playerIsT);
        if (itemIndex >= items.size()) return false;

        GlobalConfig.ShopItem shopItem = items.get(itemIndex);
        String itemNbt = shopItem.getItemId();
        if (itemNbt == null || itemNbt.isEmpty()) return false;

        // C4 系统指派不可购买
        if (itemNbt.startsWith("cstmm:c4")) {
            player.sendMessage(Text.literal("§cC4 由系统在回合开始时指派，无法购买"), false);
            return false;
        }
        if (itemNbt.startsWith("cstmm:bomb_defuser")) {
            player.sendMessage(Text.literal("§c拆弹装备请在购买页第 1 列购买"), false);
            return false;
        }

        // 适用队伍校验（BOTH/T/CT，配置字段；不限购，钱够就能买）
        String teamRule = shopItem.getTeam();
        if (teamRule.equals("T") && !playerIsT) {
            player.sendMessage(Text.literal("§c该商品仅 T 方可购买"), false);
            return false;
        }
        if (teamRule.equals("CT") && playerIsT) {
            player.sendMessage(Text.literal("§c该商品仅 CT 方可购买"), false);
            return false;
        }

        int price = Math.max(0, shopItem.getPrice());
        int money = session.getPlayerMoney(uuid);
        if (money < price) {
            player.sendMessage(Text.literal("§c余额不足：需要 $" + price + "，当前 $" + money), false);
            return false;
        }

        session.setPlayerMoney(uuid, money - price);

        // 解析并发放物品（复用 EquipmentManager 的解析与入包逻辑；失败退款）
        ItemStack stack = EquipmentManager.getInstance().parseAndBuild(player, itemNbt);
        if (stack == null || stack.isEmpty()) {
            session.setPlayerMoney(uuid, money);
            player.sendMessage(Text.literal("§c物品配置无效，无法发放（已退款）"), false);
            return false;
        }
        player.getInventory().offerOrDrop(stack);

        player.sendMessage(Text.literal("§a购买成功：-" + "$" + price + "，余额 $" + session.getPlayerMoney(uuid)), false);
        return true;
    }

    // 各对局购买窗口状态缓存（sessionId → 是否开启），状态翻转瞬间推送（订阅制）
    private final java.util.Map<String, Boolean> windowStateCache = new java.util.HashMap<>();

    /**
     * 【作用】购买窗口状态翻转检测（订阅制推送核心，替代客户端轮询）：
     *         爆破对局每 tick 调用，窗口开启/关闭状态变化的瞬间向全体在线成员
     *         推送一次商店数据（余额/窗口/装备列随包刷新）。
     * 【被谁使用】MatchManager.tick（爆破对局分支，每 tick）。
     */
    public void checkWindowFlip(MatchSession session, MapConfig map) {
        if (session.getPhase() == MatchSession.GamePhase.ENDED) {
            windowStateCache.remove(session.getSessionId());
            return;
        }
        boolean open = isBuyWindowOpen(session, map);
        Boolean prev = windowStateCache.get(session.getSessionId());
        if (prev == null || prev != open) {
            windowStateCache.put(session.getSessionId(), open);
            cn.woshiikun_1145.mcmod.choco.cstmm.network.NetworkHandler.sendShopDataToTeam(session);
        }
    }

    // ==================== 工具 ====================

    /**
     * 懒初始化金钱记录：中途补位/新加入玩家无记录时补发起始金额
     * （开局由 initSession 全量发放，此处兜底覆盖遗漏路径）。
     */
    private void ensureMoney(MatchSession session, UUID uuid) {
        if (!session.hasMoneyRecord(uuid)) {
            session.setPlayerMoney(uuid, economy().getStartMoney());
        }
    }

    /** 余额增加并封顶 maxMoney。 */
    public void addMoney(MatchSession session, UUID uuid, int amount) {
        if (amount == 0) return;
        int capped = Math.min(session.getPlayerMoney(uuid) + amount, economy().getMaxMoney());
        session.setPlayerMoney(uuid, capped);
    }

    /**
     * 【作用】向对局全员广播消息（经济播报：购买窗口开启/关闭、经济重置等），
     *         复用 NetworkHandler 的对局广播通道。
     * 【被谁使用】resetForPhaseChange（经济重置播报）、阶段③（购买窗口提示）。仅服务端。
     */
    public void broadcastMoney(MatchSession session, String message) {
        NetworkHandler.broadcastToMatch(session, message);
    }
}
