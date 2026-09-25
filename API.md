# API.md — 对外 API 调用手册

> 本文档面向**其他模组/插件开发者**，详述 Chocolate Server Tacz Match Mod（CSTMM）对外暴露的三组 Java API 的获取方式、调用方法、返回值语义与注意事项。
> 玩家操作见 [README.md](README.md) ｜ 配置见 [CONFIG.md](CONFIG.md) ｜ 运行原理见 [DEVELOPER.md](DEVELOPER.md) ｜ 网络层见 [NETWORK.md](NETWORK.md)

---

## 1. 概述

CSTMM 在 `cn.woshiikun_1145.mcmod.choco.cstmm.api` 包下提供三个纯接口，作为**稳定调用面**：

| 接口 | 功能域 | 默认实现（单例） |
|---|---|---|
| `MatchApi` | 对局状态查询（供 HUD/积分板等使用） | `MatchManager.getInstance()` |
| `QueueApi` | 匹配队列查询与进出队 | `QueueManager.getInstance()` |
| `VoteApi` | 加时/踢人投票的发起、投票与状态查询 | `VoteManager.getInstance()` |

三个接口均由对应 Manager 类实现并通过 `getInstance()` 提供单例。**接口方法签名保持向后兼容**（新方法只追加、record 只加字段不会改变既有访问器），Manager 具体类上的额外方法则按内部需求演进，建议优先依赖 `api` 包。

**适用环境**：仅服务端（`src/main`）。这些 API 直接操作服务端对局状态，**客户端侧不可用**（客户端另有独立的缓存类 `ConfigDataCache`/`ShopDataCache`，不在本文范围）。

---

## 2. 接入方式

### 2.1 依赖引入

模组未发布到远程 Maven 仓库，最简单的方式是把 CSTMM 的 jar 放入依赖声明（loom 环境）：

```gradle
dependencies {
    // 编译期可用 API，运行期由服务器上的 CSTMM 提供（推荐给只调用 API 的模组）
    modCompileOnly files("libs/chocolate-server-tacz-match-mod-<版本>.jar")
    // 或：本地联调时直接作为运行依赖
    // modImplementation files("libs/chocolate-server-tacz-match-mod-<版本>.jar")
}
```

Maven 坐标（若自行 `publishToMavenLocal`）：`org.exampl:chocolate-server-tacz-match-mod:<mod_version>`（见 `gradle.properties`）。

### 2.2 获取 API 实例

```java
import cn.woshiikun_1145.mcmod.choco.cstmm.api.MatchApi;
import cn.woshiikun_1145.mcmod.choco.cstmm.api.QueueApi;
import cn.woshiikun_1145.mcmod.choco.cstmm.api.VoteApi;
import cn.woshiikun_1145.mcmod.choco.cstmm.manager.MatchManager;
import cn.woshiikun_1145.mcmod.choco.cstmm.manager.QueueManager;
import cn.woshiikun_1145.mcmod.choco.cstmm.manager.VoteManager;

MatchApi matchApi = MatchManager.getInstance();
QueueApi queueApi = QueueManager.getInstance();
VoteApi  voteApi  = VoteManager.getInstance();
```

单例为懒加载，首次调用 `getInstance()` 时创建；在你的 `ModInitializer#onInitialize` 或之后任意时机获取均可。

### 2.3 调用线程要求

- 所有方法请在**服务端主线程**调用（服务器 tick 线程，如命令执行、事件回调、`ServerTickEvents`）。
- 内部状态虽多为并发容器，但 `MatchSession`、玩家对象等并非线程安全；跨线程使用可能造成难以排查的错乱。
- API 不校验调用线程，也不会自动切线程。

---

## 3. MatchApi — 对局状态

### 3.1 方法一览

| 方法 | 返回 | 说明 |
|---|---|---|
| `getCurrentMatchStatus(UUID playerUuid)` | `MatchStatus` | 玩家当前对局的汇总状态（供 HUD 使用） |
| `isInGame(UUID playerUuid)` | `boolean` | 玩家是否在任一对局中（含准备阶段） |
| `getKillsData(UUID playerUuid)` | `KillsData` | 玩家所在对局的比分数据 |

### 3.2 返回 record 字段

```java
record MatchStatus(
        String mapName,          // 地图 ID（如 "map_1"）；不在对局时为 ""
        int remainingSeconds,    // TIMER 制剩余秒数（KILLS 制下该值无意义，可能为 0）
        int redKills,            // 红队总击杀
        int blueKills,           // 蓝队总击杀
        boolean isPreparing,     // 准备倒计时阶段
        boolean isFighting       // 战斗阶段
) {}

record KillsData(
        int redKills,            // 红队总击杀
        int blueKills,           // 蓝队总击杀
        int playerKills,         // 玩家所在队伍的击杀数（红队玩家 = redKills）
        int playerDeaths         // 对方队伍的击杀数（即本队死亡数，红队玩家 = blueKills）
) {}
```

> ⚠️ **语义注意**：`playerKills`/`playerDeaths` 是**队伍口径**（随玩家所在队取整队击杀/死亡），不是个人 K/D。个人战绩存储在 `PlayerDataManager`（`config/cstmm/data/players/`），未通过 API 暴露。

### 3.3 空值行为

玩家不在任何对局中（或对局已结束）时：
- `getCurrentMatchStatus` 返回 `("", 0, 0, 0, false, false)`
- `getKillsData` 返回 `(0, 0, 0, 0)`

调用方可用 `mapName.isEmpty()` 判定是否在局内，等价于 `isInGame`。

### 3.4 示例：HUD 文本渲染

```java
// 每 tick 渲染时（client 端不可用此 API；本例为服务端侧的命令示例）
private static int matchStatus(CommandContext<ServerCommandSource> ctx) {
    ServerPlayerEntity player = ctx.getSource().getPlayer();
    if (player == null) return 0;

    MatchApi.MatchStatus st = MatchManager.getInstance().getCurrentMatchStatus(player.getUuid());
    if (st.mapName().isEmpty()) {
        player.sendMessage(Text.literal("§7当前未在对局中"), false);
        return 1;
    }
    String phase = st.isPreparing() ? "准备中" : (st.isFighting() ? "战斗中" : "已结束");
    player.sendMessage(Text.literal(String.format(
            "§6[%s] §c红 %d : %d 蓝§9 ｜ %s", st.mapName(), st.redKills(), st.blueKills(), phase)), false);
    return 1;
}
```

---

## 4. QueueApi — 匹配队列

### 4.1 方法一览

| 方法 | 返回 | 说明 |
|---|---|---|
| `getQueueStatus(String mapName)` | `QueueStatus` | 单张地图的排队人数（**两种模式合计**） |
| `getAllQueueStatus()` | `List<QueueStatus>` | 全部启用地图 + 快速匹配入口的队列状态 |
| `joinQueue(UUID playerUuid, String mapName, int team)` | `void` | 玩家加入地图队列（**固定按竞技模式**；`team` 参数已废弃） |
| `leaveQueue(UUID playerUuid)` | `void` | 玩家离开任意队列（地图队列或快速队列） |
| `isInQueue(UUID playerUuid)` | `boolean` | 是否在任一队列（含快速匹配队列） |

### 4.2 QueueStatus 字段

```java
record QueueStatus(
        String mapName,        // 地图 ID；末尾伪条目为 "quick"（快速匹配队列合计）
        int playerCount,       // 排队总人数（两种模式合计；队伍在开局时才分配，队列无红蓝之分）
        boolean isMapAvailable // 地图是否启用（quick 入口恒为 true）
) {}
```

- `getAllQueueStatus()` 末尾固定追加一条 `mapName="quick"` 的伪条目：`playerCount` = 快速匹配队列总人数（竞技+休闲）。
- 快速匹配队列**按模式独立**（竞技/休闲互不混合），但 API 层不区分模式，只暴露合计值。
- **破坏性变更**：`QueueStatus` 的 `redCount`/`blueCount` 已合并为 `playerCount`——队伍不再入队时分配，改为人数满足开局条件、准备开局时由系统一次性分队，队列层面不存在红蓝人数。

### 4.3 joinQueue 的行为细节

`joinQueue(UUID, String, int team)` 的完整处理流程：

1. 玩家不在线 → 静默返回（无任何效果）。
2. 玩家已在队列或对局中 → 玩家收到弹窗提示 `§c你已在队列或游戏中！...`（POPUP 协议），不入队。
3. 地图不存在或未启用 → 提示 `§c该地图未启用或不存在！`（`"quick"` 不再是特殊值——快速匹配走专用 `JOIN_QUICK` 网络动作或 `QueueManager.joinQuickQueue(player, mode)`，此处一律视为真实地图 ID）。
4. 容量检查：该模式排队人数已达两队最高人数之和（`maxRedPlayers + maxBluePlayers`，0 = 无上限）→ 提示 `§c该地图匹配人数已满！`，不入队。`team` 参数**已废弃**（队伍在开局时自动分配，保留参数仅为兼容旧签名）。
5. 成功入队后无返回值，玩家收到确认提示（不提示队伍——队伍尚未分配）。

> ⚠️ 方法无返回值，调用方无法直接判断是否入队成功；入队结果可随后用 `isInQueue` 复查。提示形式不统一：成功/离队提示走聊天栏，部分拒绝提示（如重复入队）走弹窗（POPUP 协议），调用方不宜依赖提示形式做逻辑判断。

> ⚠️ **接口版 `joinQueue` 固定竞技模式**。如需按休闲模式入队（与客户端匹配菜单行为一致），使用 `QueueManager` 具体类重载：
>
> ```java
> QueueManager.getInstance().joinQueue(
>         serverPlayer,           // ServerPlayerEntity（需在线）
>         "map_1",
>         0,                      // 已废弃：队伍在开局时自动分配
>         QueueManager.MODE_CASUAL // "CASUAL"；非法/空值按 COMPETITIVE 兜底
> );
> ```

### 4.4 示例：每 10 秒广播一次各图排队人数

```java
ServerTickEvents.END_SERVER_TICK.register(server -> {
    if (server.getTicks() % 200 != 0) return; // 200 tick = 10 秒
    for (QueueApi.QueueStatus st : QueueManager.getInstance().getAllQueueStatus()) {
        if (st.playerCount() > 0 && !"quick".equals(st.mapName())) {
            server.getPlayerManager().getPlayerList().forEach(p ->
                    p.sendMessage(Text.literal("§7[" + st.mapName() + "] 排队中: " + st.playerCount() + " 人"), false));
        }
    }
});
```

---

## 5. VoteApi — 投票系统

### 5.1 方法一览

| 方法 | 说明 |
|---|---|
| `startOvertimeVote(UUID matchId)` | 发起加时赛投票（正常流程由对局平局逻辑自动调用） |
| `startKickVote(UUID playerUuid, UUID targetUuid)` | 由 `playerUuid` 发起踢出 `targetUuid` 的投票 |
| `handleVote(UUID playerUuid, boolean agree)` | 玩家投票（同意/反对），等价于客户端按 F7/F8 |
| `getVoteStatus(UUID matchId)` | 查询当前投票状态 |

### 5.2 matchId 的双语义

`startOvertimeVote` / `getVoteStatus` 的 `matchId` 参数接受以下任意一种（内部经 `resolveSession` 解析）：
- **对局会话 UUID**（`MatchSession.getSessionId()`）；
- **对局内任意在线玩家的 UUID**。

解析失败（无此玩家/无此对局）→ 静默返回，无效果。

### 5.3 投票规则（两种投票通用）

| 规则 | 值 |
|---|---|
| 投票时长 | 30 秒 |
| 通过条件 | 同意票 ≥ 在线对局人数 ÷ 2 + 1（过半） |
| 提前结束 | 同意票达标立即通过；剩余未投票人数即使全投同意也无法达标时立即判失败 |
| 并发限制 | 同一对局同时只能有一个投票（进行中时新发起被拒绝并提示） |
| 人数门槛 | 在线对局人数 < 2 时不发起（加时投票场景下直接判平局结束对局） |

**加时投票**通过后：对局剩余时间重置为 30 秒并回到战斗阶段；未通过则对局立即以平局结束。

**踢人投票**附加规则：
- 发起者冷却：按目标所在地图的 `kickCooldownSeconds`（默认 60 秒）限制同一发起者连续发起，冷却中提示剩余秒数；
- 目标必须在对局中，且发起者必须与目标**同处一个对局**；
- 通过后目标被移出对局并恢复原点/背包/游戏模式。

### 5.4 getVoteStatus 返回值

```java
record VoteStatus(
        VoteType type,           // OVERTIME / KICK；无投票时为 null
        UUID target,             // 被踢者 UUID（OVERTIME 时为 null）
        int yesVotes,
        int noVotes,
        int requiredVotes,       // 达标所需同意票
        int remainingSeconds     // 剩余秒数
) {}
```

无对局或无进行中投票时返回 `(null, null, 0, 0, 0, 0)` —— **调用方必须对 `type` 判空**。

### 5.5 handleVote 的行为细节

1. 玩家不在线 → 静默返回。
2. 玩家不在对局中 → 提示 `§c你不在任何对局中！`。
3. 其所在对局无进行中投票 → 提示 `§c当前没有进行中的投票！`。
4. 重复投票 → 提示 `§c你已经投过票了！`（一人一票）。
5. 每次有效投票会向全对局广播进度；触发提前通过/失败时立即结算。

### 5.6 示例：控制台发起踢人投票

```java
// /vote-kick <玩家> <目标> 自定义命令（服务端侧，控制台可执行）
ServerPlayerEntity initiator = server.getPlayerManager().getPlayer(initiatorUuid);
ServerPlayerEntity target = server.getPlayerManager().getPlayer(targetUuid);
if (initiator != null && target != null) {
    // 具体类重载：支持控制台发起（initiator 传 null 时跳过冷却与同局校验提示）
    VoteManager.getInstance().startKickVote(initiator, target);
}
```

---

## 6. Manager 具体类的扩展能力

以下方法仅存在于具体类（非 `api` 接口），供深度集成使用；语义可能随版本演进，使用前核对当版源码。

### 6.1 MatchManager

| 方法 | 说明 |
|---|---|
| `boolean startMatch(String mapId, List<ServerPlayerEntity> red, List<ServerPlayerEntity> blue)` | 旧签名，固定竞技开局（兼容保留） |
| `boolean startMatch(String mapId, boolean competitive, List<ServerPlayerEntity> red, List<ServerPlayerEntity> blue)` | 完整开局：校验地图启用/冷却/占用/玩家未在局；成功返回 `true`，失败返回 `false` 且不改变队列 |
| `void endMatch(MatchSession session, String message, int winnerTeam)` | 强制结束对局；`winnerTeam`：0=平局 1=红胜 2=蓝胜；幂等（已结束的对局直接返回） |
| `MatchSession getPlayerSession(UUID playerUuid)` | 玩家所在对局；不在局内返回 `null`（对局 ENDED 后也返回会话但 phase 为 ENDED） |
| `MatchSession getSession(String sessionId)` | 按会话 ID 精确查找 |
| `List<MatchSession> getAllActiveSessions()` | 全部会话（含已结束待清理的）防御性拷贝 |
| `boolean isMapInCooldown(String mapId)` | 地图是否在对局结束冷却期内 |
| `void registerAndSetupPlayer(MatchSession session, ServerPlayerEntity player)` | **补位入场统一入口**：登记会话、保存原点/游戏模式/背包（竞技）、传送至队伍出生点、发放默认装备 |
| `void restoreKickedPlayer(UUID playerUuid)` | 恢复被投票踢出的玩家（原点/背包/游戏模式） |

`MatchSession` 常用只读方法：`getSessionId()`、`getMapName()`、`getPhase()`（PREPARING/FIGHTING/ENDED）、`getRedKills()/getBlueKills()`、`getRedPlayers()/getBluePlayers()`、`isCompetitive()`、`isPlayerInGame(UUID)`。

### 6.2 QueueManager

| 成员 | 说明 |
|---|---|
| `MODE_COMPETITIVE` / `MODE_CASUAL` | 模式常量 `"COMPETITIVE"` / `"CASUAL"` |
| `void joinQueue(ServerPlayerEntity, String mapId, int team, String mode)` | 模式化入队（见 §4.3） |
| `int getQuickQueueSize()` | 快速匹配两条队列（竞技+休闲）人数合计 |
| `void tryMatch()` | 每秒匹配调度（由模组内 `MatchScheduler` 自动驱动，外部勿重复调用） |

### 6.3 VoteManager（实体重载）

| 方法 | 说明 |
|---|---|
| `void startKickVote(ServerPlayerEntity initiator, ServerPlayerEntity target)` | 实体重载；`initiator` 可为 `null`（控制台发起，跳过冷却记录） |
| `void handleVote(ServerPlayerEntity voter, boolean agree)` | 实体重载投票 |

---

## 7. 典型集成场景

### 7.1 自定义结束条件（外部模组强制结算）

```java
MatchSession session = MatchManager.getInstance().getPlayerSession(player.getUuid());
if (session != null && session.getPhase() != MatchSession.GamePhase.ENDED) {
    // 1=红胜 2=蓝胜 0=平局；message 会广播给全对局玩家
    MatchManager.getInstance().endMatch(session, "§6触发剧情事件，对局结束！", 0);
}
```

### 7.2 组队系统对接（引导整队入队）

```java
for (ServerPlayerEntity member : party.members()) {
    QueueManager.getInstance().joinQueue(member, mapId, 0, QueueManager.MODE_COMPETITIVE);
}
// 逐个复查入队结果（人数已满/已在队列的成员会收到提示）
```

> 组队同图入队无需额外同步：队伍在开局时统一分配（战队聚组 + 平衡分队），**同战队的成员会尽量被分到同一队**（战队系统自动处理，无需外部干预）。

### 7.3 外部记分板/HUD 数据源

在 `ServerTickEvents.END_SERVER_TICK`（建议降频，如每 20 tick）轮询 `getCurrentMatchStatus` / `getKillsData`，写入你自己的记分板。

---

## 8. 注意事项与稳定性承诺

1. **双端同步**：CSTMM 服务端与客户端模组必须同版本部署（握手协议校验），更新时一并替换两端 jar。
2. **接口稳定性**：`api` 包三个接口的既有方法签名与 record 既有字段向后兼容；Manager 具体类的方法不承诺兼容。
3. **枚举 ordinal 协议**：若通过 `MatchStatusPayload.StatusType` 等 payload 层扩展，枚举值只能在**末尾追加**（网络协议按 ordinal 序列化）。
4. **勿模拟内部流程**：请勿直接修改 `MatchSession` 内部集合或调用 tick 类方法模拟对局推进；一切状态变更应经由 `startMatch` / `registerAndSetupPlayer` / `endMatch` 等入口，否则会绕过存包/恢复逻辑导致玩家物品丢失。
5. **快速匹配管线**：直启 → 跨队列合并 → 超时补位的顺序是文档化行为，外部入队请勿绕过队列状态自行拉人（应使用 `registerAndSetupPlayer` 并自行维护队伍平衡）。
6. **误用崩溃**：客户端自检对 `requestConfigSync` 限频 50 次/秒，超限抛 `ConfigSyncRateLimitException` 使客户端崩溃（反滥用设计，详见 NETWORK.md）。
