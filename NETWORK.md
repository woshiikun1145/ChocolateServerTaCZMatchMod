# Chocolate Server Tacz Match Mod（CSTMM）网络层原理详解

# *此文档由GLM-5.3-flash生成*
面向调试人员，最大化说明 CSTMM 网络层的设计动机、线路格式（wire format）、状态机、防护机制与调试方法。

> 配置方法见 [CONFIG.md](CONFIG.md) ｜ 运行架构总览见 [DEVELOPER.md](DEVELOPER.md)（本文是其"网络协议"章节的完全展开）｜ 玩家操作见 [README.md](README.md)

---

## 目录

1. [技术基础与分层架构](#1-技术基础与分层架构)
2. [原版限制——一切设计的出发点](#2-原版限制一切设计的出发点)
3. [注册架构与线程模型](#3-注册架构与线程模型)
4. [线路格式基础（wire format）](#4-线路格式基础wire-format)
5. [数据包目录与逐包编码](#5-数据包目录与逐包编码)
6. [握手协议——完整状态机](#6-握手协议完整状态机)
7. [配置同步——分片管道详解](#7-配置同步分片管道详解)
8. [防护机制汇总](#8-防护机制汇总)
9. [UTF-8 字节工具算法](#9-utf-8-字节工具算法)
10. [版本与兼容性规则](#10-版本与兼容性规则)
11. [错误处理与日志](#11-错误处理与日志)
12. [调试指南](#12-调试指南)
13. [扩展指南——如何新增一个包](#13-扩展指南如何新增一个包)

---

## 1. 技术基础与分层架构

CSTMM 网络层基于 **Fabric Networking API v1**（`fabric-networking-api-v1`，要求 MC 1.20.5+ 的 `CustomPayload` + `PacketCodec` 体系），全部为 **play 阶段**包（`PayloadTypeRegistry.playS2C()/playC2S()`），不涉及登录/配置阶段。

```
┌───────────────────────────── 客户端 jar（与服务端同一 jar）─────────────────────────────┐
│                                                                                       │
│  [客户端侧]                                  [服务端侧]                                │
│  CstmmClient                                 Cstmm                                    │
│    └─ ClientNetworkHandler.register()          └─ NetworkHandler.register()           │
│         │  S2C 接收器 ×11                          │  Codec 注册（S2C×11 + C2S×7）     │
│         │  ClientPlayConnectionEvents              │  C2S 接收器 ×7                   │
│         │  ClientTickEvents（握手超时）             │  ServerPlayConnectionEvents      │
│         └─ 发送：ClientPlayNetworking.send         └─ 发送：ServerPlayNetworking.send  │
│                                                                                       │
│  状态层：ClientHandshakeState（握手门禁）       状态层：pendingHandshakes 等            │
│  缓存层：ConfigDataCache / ShopDataCache /      权威层：ConfigManager / MatchManager    │
│         PlayerProfileCache                             / QueueManager                  │
└───────────────────────────────────────────────────────────────────────────────────────┘
```

设计原则：

- **单一 jar 双端加载**：payload 类（`network/payload/*`）在公共代码中，`NetworkHandler`（服务端逻辑）在 `main` 源集，`ClientNetworkHandler`（客户端逻辑）在 `client` 源集，两端各自 `register()`，互不依赖。
- **服务端权威**：客户端永远不产生配置数据——C2S 的 `config_update` 是"整份配置提交"，服务端解析校验后**用服务端数据重建**再广播（见 7.4）。
- **发包必须经过辅助方法**：`NetworkHandler.sendMatchStatus` 等方法统一做截断防护，禁止绕过直接 `ServerPlayNetworking.send`。

## 2. 原版限制——一切设计的出发点

| 限制 | 数值 | 影响 |
|---|---|---|
| `PacketByteBuf.writeString(max)` 长度校验 | 按写入字符串的 **UTF-8 编码字节数**（不是字符数） | 含中文时按"字符数"估算会超限 → `EncoderException` → 连接被断开。所有字符串字段发送前必须按字节截断/切分（见 9 节） |
| S2C play 包大小 | 单包约 64KB（原版网络栈） | 配置 JSON（含 Base64 背景图，可达数百 KB）无法单包发送 → 引入分片 |
| **C2S play 包大小** | **32768 字节硬限制**（原版网络栈校验，超限直接断开连接） | C2S 配置提交同样必须分片，且每片必须 < 32768B |
| `writeString(n)` 单字段上限 | n（本项目使用 64 / 128 / 256 / 32767 / 65536） | 每个字符串字段在 codec 中声明上限，发送端负责保证不超 |

**分片大小取 30000 字节**的推导：32767（`writeString(32767)` 上限）留出余量 → 32768（C2S 整包硬限制，整包还含 payload ID 等 overhead）→ 取整 30000，同时保证中文（3 字节/字）对齐安全。

## 3. 注册架构与线程模型

### 3.1 注册清单

**Codec 注册**（必须两端都注册，缺一侧 → 原版以"未知 payload"断开连接或解码错位）：

| 侧 | 注册 |
|---|---|
| `playS2C()` ×11 | `hud_data` `match_status` `config_sync` `open_config_screen` `player_profile` `handshake_s2c` `shop_data` `clan_data` `queue_status` `popup` `badge` |
| `playC2S()` ×7 | `match_action` `config_update` `request_config_sync` `handshake_c2s` `clan_action` `request_queue_status` `request_badge` |

**接收器注册**（只需数据流向的接收侧）：

| 包 | 方向 | 接收端 | 处理线程 |
|---|---|---|---|
| `match_action` | C2S | 服务端 `handleMatchAction` | `context.server().execute()` → 主线程 |
| `config_update` | C2S | 服务端 `handleConfigUpdatePart` | 同上 |
| `request_config_sync` | C2S | 服务端（限频后回发） | 同上 |
| `handshake_c2s` | C2S | 服务端（清理重试记录） | 同上 |
| `clan_action` | C2S | 服务端 `handleClanActionMaybeChunked`（徽标分片重组后进 `handleClanAction`） | 同上 |
| `request_queue_status` | C2S | 服务端（订阅集增删 + 立即回发快照） | 同上 |
| `request_badge` | C2S | 服务端（badgeId 反查战队补发全部分片） | 同上 |
| 其余 11 个 S2C 包 | S2C | 客户端各接收器（`ClientNetworkHandler`） | `context.client().execute()` → 客户端主线程 |

### 3.2 线程模型（重要）

Fabric Networking 的接收回调在 **Netty IO 线程**执行，**不在主线程**。CSTMM 所有接收器第一件事就是 `context.server().execute(...)` / `context.client().execute(...)` 把处理体抛回主线程：

- 服务端主线程：QueueManager / MatchManager / ConfigManager 等全部非线程安全，必须串行；
- 客户端主线程：屏幕（Screen）、HUD、缓存更新必须符合 Minecraft 客户端线程约束。

**推论**：同一个玩家的多个分片即使乱序到达 IO 线程，也会被按序抛入主线程队列执行（`execute` 是入队），服务端重组逻辑因此可以假定"同一连接的分片串行到达"。跨玩家之间则完全并发——所以服务端重组缓冲按 `Map<UUID, ...>` 隔离且使用 `ConcurrentHashMap`。

### 3.3 生命周期钩子

| 事件 | 服务端动作 | 客户端动作 |
|---|---|---|
| JOIN | 发首个握手请求，登记 `pendingHandshakes[uuid]=0` | `ClientHandshakeState.onJoin()` 重置握手状态；配置/履历同步由 EventListener 的 JOIN 逻辑触发 |
| DISCONNECT | 清空该玩家的配置重组缓冲 + 限频窗口 + 队列状态订阅 + 徽标上传缓冲/已发集合（`clearPendingConfigUpdate` / `syncRequestWindows.remove` / `queueStatusSubscribers.remove` / `pendingBadgeUploads.remove` / `sentBadges.remove`） | 重置握手状态、`HudOverlay.reset()`、清空 `ConfigDataCache`/`ShopDataCache`/`ClanCache`/`QueueStatusCache`/`BadgeCache`（防跨服残留） |

## 4. 线路格式基础（wire format）

所有 codec 是 `PacketCodec.of(encode, decode)` 双函数，读写顺序**必须严格对称**。

| 操作 | 线路字节 | 说明 |
|---|---|---|
| `writeVarInt(v)` | 1~5 字节变长 | 非负小整数用 varInt（分片索引、数量、价格） |
| `writeInt(v)` | 固定 4 字节 | 击杀数、队伍号、秒数等 |
| `writeBoolean(b)` | 1 字节 | 资格标志、inGame 标志 |
| `writeEnumConstant(e)` | varInt（**ordinal**） | `ActionType`、`MatchStatusPayload.StatusType`——顺序即协议 |
| `writeString(s, max)` | varInt(字节数) + UTF-8 字节 | `max` 为字节上限；超限抛异常，故发送端前置截断 |
| payload 头部 | 由 Fabric 注入 | 包含 payload ID（`cstmm:xxx`），不计入各字段 |

包 ID 全部在 `cstmm` 命名空间下（模组 ID `chocolateservertaczmatchmod` 的缩写命名空间，与 `fabric.mod.json` 的 mod id 是两个概念）。

## 5. 数据包目录与逐包编码

### 5.1 总览

| ID | 方向 | 触发时机 | 频率 |
|---|---|---|---|
| `cstmm:handshake_s2c` | S2C | 玩家加入 + 未应答时每秒重试（≤2 次） | 加入时 1~3 次 |
| `cstmm:handshake_c2s` | C2S | 每次收到 handshake_s2c | 与上成对 |
| `cstmm:config_sync` | S2C | 加入 / OP 保存后全服广播 / 玩家请求 | 每次同步 N 片 |
| `cstmm:config_update` | C2S | OP 在配置界面点保存 | 每次提交 N 片 |
| `cstmm:request_config_sync` | C2S | 打开配置界面 / 点"重新加载" | 限频 2 次/秒/人 |
| `cstmm:match_action` | C2S | 入队/快速匹配/退队/投票/购买/请求履历/请求商店 | 玩家操作驱动 |
| `cstmm:match_status` | S2C | 对局状态消息广播 | 事件驱动 |
| `cstmm:hud_data` | S2C | 对局期间每秒推送（含结束清除包） | 1 次/秒/人 |
| `cstmm:player_profile` | S2C | 加入时自动 + 玩家请求履历 | 低频 |
| `cstmm:open_config_screen` | S2C | 服务端要求打开 OP 配置界面 | 空包 |
| `cstmm:shop_data` | S2C | 响应 REQUEST_SHOP | 低频 |
| `cstmm:clan_action` | C2S | 战队操作（创建/加入/退出/解散/转让/踢人/编辑/查询） | 玩家操作驱动 |
| `cstmm:clan_data` | S2C | 战队数据（MINE/LIST/DETAIL JSON 快照） | 页面打开/搜索 + 战队变化时推送给全体成员 |
| `cstmm:request_queue_status` | C2S | 队列状态订阅开关（打开匹配主菜单订阅、关闭菜单退订） | 菜单打开/关闭时 |
| `cstmm:queue_status` | S2C | 队列状态快照（own/clan/maps JSON，蓝红人数/玩家列表按模式拆分） | 订阅后队列变化时推送 |
| `cstmm:popup` | S2C | 弹窗通知（当前界面内弹对话框，无界面回退聊天：配置已保存/战队加入结果/重复入队拒绝等） | 事件驱动 |
| `cstmm:badge` | S2C | 战队徽标分片下发（badgeId 内容寻址，每片 ≤30000 字符） | MINE/DETAIL 发送前（同一徽标每人只发一次）+ 缺失补发 |
| `cstmm:request_badge` | C2S | 徽标缓存缺失请求（客户端收 MINE/DETAIL 后本地无该 badgeId 时请求补发） | 极低频 |

### 5.2 逐包字段表

**`cstmm:match_action`（C2S）**

| 字段 | 编码 | 上限 | 语义 |
|---|---|---|---|
| `action` | enum ordinal | — | 见下表，**只能在末尾追加** |
| `mapName` | string | 64B | JOIN_QUEUE：地图 ID（**不再接受 `quick` 伪地图 ID**）；BUY_ITEM：**商品下标的十进制字符串**（复用字段省一个包） |
| `team` | int(4B) | — | JOIN_QUEUE：1 红/2 蓝/其他=自动（服务端把非 1/2 归一为 0，防客户端注入任意整数）；BUY_ITEM：忽略 |
| `target` | string | 64B | JOIN_QUEUE / JOIN_QUICK：模式 `COMPETITIVE`/`CASUAL`（空/非法按竞技兜底）；其余动作空闲 |

`ActionType` ordinal 表：`JOIN_QUEUE=0, LEAVE_QUEUE=1, VOTE_YES=2, VOTE_NO=3, VOTE_OVERTIME=4, SELECT_TEAM=5, BUY_ITEM=6, REQUEST_PROFILE=7, REQUEST_SHOP=8, JOIN_QUICK=9`

> 快速匹配走专用 `JOIN_QUICK` 动作，不再以 `"quick"` 伪地图 ID 复用 `JOIN_QUEUE`（否则真实地图 ID 恰为 `quick` 时会被劫持进快速队列）。

**`cstmm:hud_data`（S2C）**：`string mapName(64B，发送前 truncate)` + `int redKills` + `int blueKills` + `int remainingSeconds` + `bool inGame`。结束时发 `inGame=false` 清除包。

**`cstmm:match_status`（S2C）**：`enum StatusType（MATCH_STARTING/MATCH_ENDED/VOTE_STARTED/VOTE_RESULT/COUNTDOWN，ordinal）` + `string message(128B，发送链路统一截断)` + `int redKills` + `int blueKills`。

**`cstmm:config_sync` / `cstmm:config_update`（分片包，两端字段相同）**：`varInt partIndex` + `varInt totalParts` + `string data(32767B)`，`data` 实际每片 ≤ 30000 字节。

**`cstmm:player_profile`（S2C）**：`string jsonData(65536B)`。服务端发送前检查：JSON 的 UTF-8 字节数 **≥ 65536 时跳过发送**并 WARN（不截断——履历截断会造成数据失真，宁可不发）。

**`cstmm:clan_action`（C2S）**：`enum action（REQUEST_LIST/REQUEST_DETAIL/REQUEST_MINE/CREATE/JOIN/LEAVE/DISBAND/TRANSFER/KICK/EDIT，ordinal 末尾追加）` + `string text1(192B)` + `string text2(16B)` + `string badge(30000B)` + `int number` + `varInt badgePartIndex` + `varInt badgeTotalParts`。字段按动作复用：CREATE/EDIT=text1名称/text2缩写/badge徽标分片/number成员上限；JOIN、REQUEST_DETAIL=text1战队名；TRANSFER/KICK=text1目标玩家名；REQUEST_LIST=text1搜索词（空=随机10条）；其余空闲。
**徽标分片上传**：badge ≤30000 字符时单包直发（badgePartIndex=0/totalParts=1）；更大时客户端拆成 N 片逐包发送（每片 ≤30000），服务端重组缓冲集齐后拼接执行动作（**只有最后一片触发**）。服务端防护：totalParts ∈ [1,64]、partIndex 越界拒绝、累计 ≤1.92M 字符、新序列（partIndex=0 或元数据不匹配）覆盖旧缓冲、断线清理。

**`cstmm:badge`（S2C）**：`string badgeId(16B)` + `varInt partIndex` + `varInt totalParts` + `string data(30000B)`。badgeId = 完整徽标 base64 的 SHA-256 前 8 字节 hex（内容寻址，相同徽标跨玩家/战队复用缓存）；totalParts ∈ [1,128]。**触发时机**：发送携带徽标的 MINE/DETAIL JSON **之前**（TCP 保序保证客户端重组完成后再收到引用）；per-player 已发集合去重（同一 badgeId 只发一次），客户端缺失时经 `cstmm:request_badge` 请求全量补发。

**`cstmm:request_badge`（C2S）**：`string badgeId(16B)`——服务端按 badgeId 反查战队（SHA-256 惰性计算，edit 徽标后 id 自动变化），重新下发全部分片；查不到静默忽略。

**`cstmm:clan_data`（S2C）**：`string kind(16B)` + `string json(65536B)`。kind=MINE：`{"inClan":bool,"clan":{name,abbr,badge,leaderName,memberCount,limit,members:[{name,isLeader,online,matchState}]}}`（**badge 字段 = badgeId 内容寻址 id（16 字符 hex），非完整 base64**；matchState=""=空闲，否则"地图显示名-模式名"）；kind=LIST：`{"clans":[{name,abbr,leaderName,memberCount,limit}]}`（行内无徽标）；kind=DETAIL：`{"found":bool,"clan":{...同上}}`。客户端收到 MINE/DETAIL 后本地徽标缓存缺失 → 自动发 `request_badge`。

**`cstmm:request_queue_status`（C2S）**：`bool subscribe`——true=加入队列状态订阅集并立即回发一次快照；false=退订。**事件驱动**：队列变化（加入/退出/开局/解散/补位）时服务端主动向订阅者推送，无轮询、无限频。

**`cstmm:queue_status`（S2C）**：`string json(65536B)` = `{own:{inQueue,map,mapId,mode,matched,needed}, clan:{inClan,name,abbr,count,members:[{map,player}]}, maps:[{id,status,modes:[{mode,red,blue,players}]}]}`。status ∈ AVAILABLE/IN_MATCH/DISABLED/COOLDOWN；蓝红人数与玩家列表按"竞技"/"休闲"两模式分别下发（客户端每 3 秒轮换展示，地图名/类型/图片由客户端按 id 从配置缓存解析）。

**`cstmm:popup`（S2C）**：`string message(256B)`——客户端在当前界面内弹对话框（MatchMenuScreen/ConfigScreen），无界面回退聊天栏。

**`cstmm:shop_data`（S2C）**：`bool eligible` + `varInt itemCount` + 循环 `{string itemId(256B) + varInt price + varInt maxPurchase}`。解码端 `count = max(0, readVarInt)` 防负数，`create` 静态工厂保证 itemCount 与列表长度一致。

**`cstmm:handshake_s2c`**：`string serverVersion`。**`cstmm:handshake_c2s`**：`string clientVersion`。**`request_config_sync` / `open_config_screen`**：空包（codec 仅占位）。

## 6. 握手协议——完整状态机

### 6.1 时序

```
服务端                                       客户端
  │ JOIN 事件                                  │
  ├─ sendHandshakeRequest(serverVersion) ────►│ ClientHandshakeState.onResponse()
  ├─ pendingHandshakes[uuid] = 0              │   ├─ 比对 clientVersion == serverVersion
  │                                           │   ├─ handshaked = true/false
  │◄──────────── handshake_c2s ───────────────┤   ├─ 无论匹配与否回发应答 ──────┐
  │ 收到应答 → pendingHandshakes.remove        │   └─ 结果提示（handshakeMessageShown 去重）
  │ 版本不一致 → 仅 WARN（功能在客户端禁用）    │
  │                                           │
  │ 每秒 tickHandshake()：                     │ （若应答丢失）
  │ 未收到应答且重试 < 2 → 重发 handshake_s2c   │◄── 再次触发 onResponse（提示已去重，不重复）
  │ 重试 ≥ 2 → 放弃（等客户端 8 秒超时提示兜底） │
```

### 6.2 关键设计点

- **服务端发起**：未装模组的服务端不会发请求，客户端以此区分"服务端没装"与"网络丢包"。
- **应答无条件回发**：即使版本不匹配也回发（服务端据此停止重试；不回发会导致服务端重试 2 次浪费时间）。
- **提示去重**：`handshakeMessageShown` 标志保证服务端重试导致的多次 `onResponse` 只提示一次。
- **双保险超时**：服务端重试 2 次后放弃；客户端加入 8 秒（`TIMEOUT_MS`）仍未握手成功 → 本地提示"未与服务端握手成功"。
- **版本号同源**：两端各从 `FabricLoader` 的 mod 容器元数据读取（`getModVersion()` / `getClientVersion()`），即 jar 的 `mod_version`，改 `gradle.properties` 重新构建即自动同步。
- **门禁**：`ClientHandshakeState.isHandshaked()` 是全部客户端功能的总开关——快捷键、商店、匹配菜单在未握手时只弹提示不发任何包。

## 7. 配置同步——分片管道详解

### 7.1 数据流总览

```
[服务端权威状态]  ConfigManager（maps + global）
      │ buildConfigJson()：{maps:[...], global:{...}, inUseMaps:[...]}
      │                    ▲ inUseMaps = 当前非 ENDED 对局的地图 ID（客户端据此阻止删除活跃地图）
      ▼
splitByUtf8Bytes(json, 30000) ──► [片0][片1]...[片N-1]
      │ 逐片 ServerPlayNetworking.send(ConfigSyncPayload(i, N, chunk))
      ▼
[客户端] ConfigSyncPayload 接收器（主线程串行）
      │ partIndex==0 → 重置缓冲；丢首包 → 放弃本次同步
      │ 逐片 append，receivedParts == totalParts → 拼接完成
      ▼
applyConfigSync(json)：GSON 解析 → ConfigDataCache.update* → 刷新 MatchMenuScreen / ConfigScreen
```

### 7.2 S2C 侧（服务端 → 客户端）

- **触发点**：① 玩家加入（EventListener JOIN）② 任意 OP 保存成功后**全服广播** ③ 玩家发 `request_config_sync`（限频 2/s）。
- **客户端重组为信任路径**：S2C 只做计数重组（`receivedParts >= pendingTotalParts`），无位图去重、无大小上限——因为服务端是自己人；防护全部集中在 C2S。

### 7.3 C2S 侧（OP 客户端 → 服务端）

客户端 `sendConfigUpdate`：同一把 `splitByUtf8Bytes(json, 30000)` 逐片发 `ConfigUpdatePayload`。服务端 `handleConfigUpdatePart` 的完整防线（按序执行）：

1. **权限预检**：`hasPermissionLevel(2)`，非 OP 的分片直接拒绝（每片都会检查，不只是拼完之后）。
2. **边界校验**：`totalParts ∈ [1, 64]`、`partIndex ∈ [0, totalParts)`，越界丢弃并 WARN。
3. **首包依赖**：`partIndex != 0` 且无 pending 缓冲 → 判定首包丢失，放弃本次更新（等下次完整同步）。
4. **位图去重**：`receivedConfigUpdateParts` 用 `long` 位掩码（bit i = partIndex i 已收），重复分片直接丢弃——防重复拼接导致的 JSON 损坏。64 片上限与 `long` 的 64 位天然匹配。
5. **累积字节上限**：每片的 UTF-8 字节数累加进 `pendingConfigUpdateBytes`，超过 **2MB** 清空该玩家全部重组状态并 WARN。合法上限 64 片 × 30000B ≈ 1.92MB，不会误伤。
6. **完成判定**：`bitCount(掩码) >= totalParts` → 拼接完成，清空 pending，进入落盘流程。

### 7.4 服务端落盘流程（`handleConfigUpdate`）

```
解析 JSON → maps[] + global（缺失任一 → 拒绝）
  → 活跃对局保护：本次提交中"消失"的地图 ID（服务端有、提交无）逐个检查，
     任一正处于非 ENDED 对局 → 整次保存取消并提示（防对局僵死）
  → updateMap(逐个) + removeMap(消失的) + updateGlobal —— 先全部校验后统一应用
  → 权威重建 syncJson = buildConfigJson()（用服务端数据，不用客户端原始 JSON）
  → 全服广播 config_sync
```

注意：**保存失败是全有或全无**（先解析校验再应用）；**广播数据是服务端重建的**——即使客户端提交了被篡改的字段（如悄悄加进来的地图），广播回所有客户端的都是服务端认可后的状态。

## 8. 防护机制汇总

| # | 位置 | 机制 | 参数 |
|---|---|---|---|
| 1 | 服务端 `request_config_sync` | 滑动窗口限频（每玩家 1s 窗口计数） | 2 次/秒/人，超限拒绝+聊天提示 |
| 2 | 客户端 `requestConfigSync` | **自检限频**：窗口计数超限抛未捕获 RuntimeException `ConfigSyncRateLimitException` → 客户端崩溃（反滥用：客户端逻辑 bug 不会演变成对服务端的打包攻击） | 50 次/秒 |
| 3 | 服务端 `config_update` 重组 | totalParts/partIndex 边界、首包依赖、long 位图去重、断线清理 | [1,64]、bitmask |
| 4 | 服务端 `config_update` 重组 | 累积字节上限（防 OOM/DoS） | 2MB（合法上限 ≈1.92MB） |
| 5 | 所有字符串字段 | 发送前按 UTF-8 字节截断/切分（不切断多字节字符） | 见 9 节 |
| 6 | `player_profile` | 超限跳过发送（不截断） | 65536B |
| 7 | `config_update` / 握手 | 断线即清理全部 per-player 状态 | — |
| 8 | 服务端 `handleConfigUpdate` | OP 权限（每片预检）+ 活跃对局地图禁删 + 先校验后应用 | permission ≥2 |

## 9. UTF-8 字节工具算法

`NetworkHandler.splitByUtf8Bytes(s, maxBytes)` 与 `truncateByUtf8Bytes(s, maxBytes)`：

- 逐字符累计编码字节数：ASCII(0x00-0x7F)=1B、0x80-0x7FF=2B、BMP 其余=3B、代理对=4B（增补字符按两 char 一次消费）。
- **只会在完整字符边界切分**——中文/emoji 永远不会被切成半个字符（半个 UTF-8 序列会导致解析端 `readString` 解码错乱）。
- 极端兜底：单个字符字节数 > maxBytes 时强制消费 1 char，防死循环。
- `truncateByUtf8Bytes` 用于单字段场景（`hud_data.mapName` 64B、`match_status.message` 128B），保证 `writeString` 永不抛异常；未配对代理实际编码为 1 字节 `?`，按 3 字节估算只会更保守。

**为什么不能用 `String.substring` 按字符数切**：`writeString` 校验的是编码后字节数，1 个中文字符 = 3 字节，按字符数切 30000 字符的片含中文时实际字节数会超 30000 → 必超限。这是历史上真实踩过的坑（教训：字符切分导致含中文配置同步失败）。

## 10. 版本与兼容性规则

1. **两端模组版本必须一致**（握手比对字符串相等）。不一致 → 客户端全部功能禁用（门禁在客户端，服务端仅 WARN）。
2. **枚举 ordinal 追加原则**：`ActionType`、`StatusType` 只能在**末尾**追加新值；中间插入/删除/重排会使两端语义错位（wire 上只是整数）。
3. **新增包**：必须两端同步注册 codec（S2C 还需客户端接收器，C2S 需服务端接收器），并同步部署 jar。
4. **字段演进**：在既有包**末尾追加字段**是安全的做法；修改既有字段顺序/类型 = 破坏性变更，必须两端同版本。
5. Fabric API 版本锁定 0.115.6+1.21.1；`fabric.mod.json` 中 `"fabric-api": "*"` 可按需收紧。

## 11. 错误处理与日志

统一前缀 `[CSTMM - Network]`（服务端）/ `[CSTMM - ClientNetwork]`（客户端）。关键日志速查：

| 日志 | 含义 |
|---|---|
| `Handshake version mismatch: client X, server Y` | 两端版本不一致（服务端 WARN） |
| `Config update missed first part from X` | C2S 首片丢失，本次提交作废 |
| `Duplicate config update part N from X` | 位图去重命中（理论上仅恶意/异常客户端触发） |
| `Config update from X exceeded 2000000 UTF-8 bytes` | 触发 2MB 防护，pending 清空 |
| `Invalid config update part (index i, total n)` | 分片索引越界丢弃 |
| `Config sync missed first part, dropped`（客户端） | S2C 首片丢失，等待下次同步 |
| `Failed to parse config sync`（客户端 ERROR，含堆栈） | 拼接后 JSON 解析失败——优先怀疑两端版本不一致或分片丢失后的脏缓冲 |
| `Player profile json too large (n UTF-8 bytes)` | 履历超限跳过 |

## 12. 调试指南

**对称追踪法**：CSTMM 无独立包转储，调试靠两端日志对账。

1. **确认注册**：服务端启动日志找 `Registered network handlers`，客户端找 `Registered client network handlers`。缺任何一个 → 包会静默丢失或断线。
2. **握手问题排查顺序**：
   - 客户端 8 秒提示"未握手成功"且服务端无 `version mismatch` → 服务端没装模组或包被拦截；
   - 服务端有 `version mismatch` → 对比两端 `gradle.properties` 的 `mod_version`，重新构建部署；
   - 时好时坏 → 握手重试（≤2 次）已兜底，检查网络丢包。
3. **配置同步排查**：
   - 客户端界面"等待配置同步..."卡住 → 看客户端有无 `Config sync missed first part` 或 `Failed to parse config sync`；
   - 保存配置后地图消失 → 看服务端 `Invalid config update part` / `exceeded ... bytes` / 权限 WARN，以及 ConfigManager 的校验 WARN；
   - 单侧失败常见根因：**两端版本不同**（codec 字段上限或字段数不一致 → 解码错位 → 断线或乱数据）。
4. **限频触发确认**：服务端 `配置同步请求过于频繁` 提示 = 客户端 1 秒内发了 ≥3 次 `request_config_sync`（正常 UI 操作不应触发；触发说明客户端存在刷新循环——历史上 ShopScreen 曾因在 `init()` 发请求导致循环，已改为构造器发送）。
5. **线程问题排查**：接收器若忘记 `execute()` 包裹，症状是"偶发并发修改异常/界面渲染错乱"——检查所有接收器第一行是否回到主线程。
6. **C2S 32768 限制验证**：若单包直发大配置被踢线（连接断开），即命中原版硬限制——确认走的是 `sendConfigUpdate`（分片）而非裸 `send`。

## 13. 扩展指南——如何新增一个包

以新增 S2C 包 `cstmm:example` 为例：

1. **定义 payload**（公共代码 `network/payload/ExamplePayload.java`）：
   - `record ExamplePayload(...) implements CustomPayload`；
   - `public static final Id<ExamplePayload> ID = new Id<>(Identifier.of("cstmm", "example"))`；
   - `CODEC = PacketCodec.of(encode, decode)`，读写顺序严格对称；字符串字段写明上限并考虑发送端截断。
2. **服务端**（`NetworkHandler.register()`）：`PayloadTypeRegistry.playS2C().register(ID, CODEC)` + 发送辅助方法（截断防护在此做）。
3. **客户端**（`ClientNetworkHandler.register()`）：同注册 + `ClientPlayNetworking.registerGlobalReceiver(ID, (payload, ctx) -> ctx.client().execute(...))`——**务必回主线程**。
4. **兼容性**：枚举字段只能末尾追加；两端 jar 必须同版本部署。
5. **防护自查清单**：字符串是否可能超上限？客户端能否高频触发？高频点是否需要限频？包是否含用户可控大字符串（是否需要分片/上限）？
