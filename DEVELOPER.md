# Chocolate Server Tacz Match Mod（CSTMM）调试与技术文档

面向调试人员与维护者，说明模组的运行原理、架构与网络协议细节。

> 玩家操作说明请查阅 [README.md](README.md)（玩家手册），地图/商店/全局参数的配置方法请查阅 [CONFIG.md](CONFIG.md)（配置手册）。
> 网络层的完全展开（线路格式、线程模型、分片管道、防护机制、调试方法）请查阅 [NETWORK.md](NETWORK.md)。
> 对外 API（MatchApi/QueueApi/VoteApi）的调用方法与语义请查阅 [API.md](API.md)（面向二次开发）。

---

## 目录

1. [基本信息](#1-基本信息)
2. [运行架构](#2-运行架构)
3. [网络协议（详细）](#3-网络协议详细)
4. [配置同步与分片机制](#4-配置同步与分片机制)
5. [对局生命周期](#5-对局生命周期)
6. [数据持久化与保护](#6-数据持久化与保护)
7. [管理员与调试命令](#7-管理员与调试命令)
8. [配置文件字段参考](#8-配置文件字段参考)
9. [日志规范](#9-日志规范)
10. [常见问题排查](#10-常见问题排查)

---

## 1. 基本信息

- **模组 ID**：`chocolateservertaczmatchmod`（网络通道命名空间 `cstmm`）
- **MC 版本**：1.21.1 ｜ **Fabric Loader**：0.16.0+（`gradle.properties` 构建目标即最低要求，fabric.mod.json 自动展开 `>=0.16.0`）｜ **Fabric API**：0.115.6+1.21.1
- **形态**：服务端 + 客户端双端模组，**必须同版本同步部署**
- **版本号来源**：`gradle.properties` 的 `mod_version` → Gradle processResources 展开到 `fabric.mod.json` → 运行时 FabricLoader 元数据读取（无硬编码版本常量），改版本只需改 `mod_version` 重新构建

## 2. 运行架构

### 2.1 服务端模块

| 模块 | 职责 |
|---|---|
| `Cstmm` | 主入口，注册网络包、命令、事件 |
| `NetworkHandler` | 服务端网络分发，C2S 包处理与限频 |
| `MatchManager` | 统一对局引擎：开局、记分、边界检测、结束、补位注册（`registerAndSetupPlayer`） |
| `QueueManager` | 匹配队列（按 `mapId@mode` 复合键），开局条件判定、跨队列合并、补位流程；战队偏好多队分配；快速匹配引擎已提取为 `QuickMatchEngine`（持有快速队列与等待计时，经包私有回引用访问 QueueManager，公共 API 零变化） |
| `ClanManager` | 战队系统（config/cstmm/data/clans.json 原子写盘）：创建/加入/退出/解散/转让/踢人/编辑；只影响分队偏好（同战队尽量同队），不影响 KD/击杀；徽标 base64 内容寻址 id（SHA-256 前 8 字节 hex，`getBadgeId`） |
| `VoteManager` | 踢人投票、加时投票（含冷却与超时静默清理） |
| `ConfigManager` | 配置加载/校验/落盘（原子写盘）、保存后全服广播 |
| `InventoryManager` | 竞技模式存包/清包/恢复快照 |
| `EquipmentManager` | 竞技模式默认装备发放、购买物品发放（双校验） |
| `PlayerDataManager` | 战绩记录（仅对局结束时记录一次）与档案持久化 |
| `MatchScheduler` | 全局调度 tick，各 manager tick 用独立 try-catch 隔离异常 |
| `EventListener` | 玩家加入（握手触发）、死亡重生（回出生点）、伤害（友伤判定）、断线清理 |
| `BandwidthTracker`（util） | `/cstmm debug bandwidth` 服务器带宽统计：玩家 JOIN 时向连接 Netty 管线最外侧注入出/入站字节计数器（`mixin` 包两个 Accessor 打通 protected/private 字段），1Hz 守护线程采样速率与逐秒记录 |
| `mixin` 包 | Mixin 访问器（`ClientConnectionAccessor`/`ServerCommonNetworkHandlerAccessor`），仅供带宽统计读取连接管线；无注入型 Mixin |
| 命令参数说明 | 战队名参数用原生 `StringArgumentType.string()`（引号包裹空格名由 Brigadier 原生解析）——**不使用自定义 ArgumentType**（服务器环境限制）；战队名建议数据源为 `clan_hints` 同步缓存（服务端执行时用 ClanManager） |

### 2.2 客户端模块

| 模块 | 职责 |
|---|---|
| `CstmmClient` | 客户端入口，快捷键注册（; 菜单 / ' 商店 / F7 F8 投票） |
| `ClientNetworkHandler` | S2C 包解码与分发 |
| `ClientHandshakeState` | 握手状态机，全部功能的门禁 |
| `ConfigDataCache` | 配置同步结果的客户端缓存（供界面渲染） |
| `ConfigDiskCache` | 配置磁盘持久缓存（按服务器标识隔离；重连哈希一致免收全量配置） |
| `MatchMenuScreen` | 匹配菜单（欢迎/匹配/队列/战队/履历/个性化/关于页，卡片背景 cover 裁剪渲染，关于页含仓库/问题反馈链接与"千万别点"彩蛋入口）；队列/战队/履历/个性化页由 QueueTabPanel / ClanTabPanel / ProfileTabPanel / PersonalizeTabPanel 渲染（履历页：上 1/3 自己履历 + 下 2/3 全服排行，可滚动，右上角下拉选择 K/D/胜率/总击杀排序） |
| `QueueTabPanel` / `ClanTabPanel` / `ProfileTabPanel` / `PersonalizeTabPanel` | 队列状态页（own/clan/每图状态 + 渐变地图图）、战队页（列表/搜索/创建/详情/成员管理）、履历页（自己履历 + 全服排行）与个性化页（主题色 + QQ/B站头像绑定） |
| `ClanCache` / `QueueStatusCache` / `LeaderboardCache` | 战队/队列状态/全服排行 JSON 缓存（clan_data / queue_status / leaderboard 包） |
| `FaceCache` / `FaceImageCache` | 自己的头像绑定缓存（档案同步携带 avatarType/avatarId）与头像图片异步获取（QQ 拼接 qlogo 直链 / B站经 uapis 解析 face 直链，内存缓存 + 失败冷却；断线清空） |
| `ClientConfig` | 客户端个性化配置 `config/cstmm/client/config.json`（当前仅界面主题色，懒加载 + 原子落盘） |
| `BadgeCache` | 战队徽标两级缓存（badge 包按 badgeId 重组，落盘 `config/cstmm/client/cache/badges/<id>.b64`；进服上报磁盘已有 id 服务端免重发；内存+磁盘均缺失时自动发 request_badge 补发；断线仅清内存，磁盘保留跨重连复用） |
| `Base64ImageDecoder` | base64 图片 → GPU 纹理的健壮解码工具（data URI 前缀剥离/空白清理/MIME 降级/非 PNG 经 ImageIO 转换；地图卡片背景与战队徽标共用，`CardTexture` record 在此类中） |
| `LastWordsScreen` + `WhyYouClickThis` | "千万别点"的遗言流程（不可取消：发送/算了/回车/ESC 均发送遗言；崩溃挂起为静态状态，由 CstmmClient 每 tick 抛出；屏幕被其他模组顶号/断线顶掉时 `removed()` 兜底同样触发；被反崩溃模组吞掉异常则改用 `scheduleStop()` 强制退出） |
| `ConfigScreen` | OP 配置界面（未保存修改时阻断被动同步刷新） |
| `ShopScreen` + `ShopDataCache` | 商店界面与商品数据缓存 |
| `HudOverlay` | CS2 风格对局 HUD 渲染（顶部队伍栏：己方/敌方玩家卡片 + 中央计分板；淡入淡出动画，断线重置） |

### 2.3 关键设计约束

- **握手门禁**：客户端未握手成功时禁用全部功能（快捷键点击仅提示，不发包）。
- **匹配模式**：竞技/休闲由玩家入队时选择（`MatchActionPayload` 的 `target` 字段），不在 MapConfig 中配置。
- **队伍开局时才分配**：地图队列是单一无队伍名单（入队仅做容量检查 = maxRed + maxBlue 之和，超出发"该地图匹配人数已满"）；人数达到两队最低之和、准备开局时才由 `QuickMatchEngine.splitIntoTeams`（与快速匹配共用）一次性分队。`joinQueue` 的 `preferredTeam`/API `team` 参数已废弃。
- **状态推送事件驱动**：队列/战队状态不轮询——客户端打开匹配主菜单时发 `request_queue_status{subscribe:true}` 订阅（关闭菜单在 `removed()` 发 false 退订），队列变化（加入/退出/开局/解散/补位）时服务端向订阅者推送 `queue_status` 快照（"匹配"页取消匹配按钮与"队列"页共用同一份快照）；战队任何变更（创建/加入/退出/解散/转让/踢人/编辑）时向全体在线成员推送 MINE 快照，**并重推队列快照**（队列快照的 clan 行依赖战队数据，否则"先匹配后入队"会显示旧状态）。
- **战队成员字段**：clan_data 的成员含 `online`（在线状态）、`matchState`（"空闲"/"地图显示名-模式名"）与 `avatarType`/`avatarId`（头像绑定，服务端只存/发绑定，图片由各客户端自行获取），由服务端实时计算下发。
- **原子写盘**：所有 JSON 保存均为临时文件 + `Files.move(ATOMIC_MOVE)`；加载失败（`JsonParseException`）绝不回写默认值覆盖用户文件。
- **活跃对局保护**：活跃对局的地图禁止删除/修改（服务端 `handleConfigUpdate` 与 `/cstmm data delete map` 校验 + 客户端弹窗提示）。
- **快照保护**：`InventoryManager.saveInventory` 绝不覆盖未恢复的旧快照；恢复流程为"清背包 → 恢复 → 成功才删快照"。

## 3. 网络协议（详细）

所有自定义包基于 Fabric Networking v1 的 `CustomPayload`，命名空间 `cstmm`。

**字符串长度限制均为 UTF-8 字节数**（`PacketByteBuf.writeString` 的校验口径），发送端在编码前按字节截断且不切断多字节字符（中文安全）。

### 3.1 包一览

| ID | 方向 | 用途 |
|---|---|---|
| `cstmm:handshake_s2c` | S2C | 握手请求（携带服务端版本） |
| `cstmm:handshake_c2s` | C2S | 握手应答（携带客户端版本） |
| `cstmm:config_sync` | S2C | 配置同步（核心配置 JSON 分片，含哈希） |
| `cstmm:config_meta` | S2C | 配置元数据（核心配置 SHA-256 + inUseMaps，哈希握手省带宽） |
| `cstmm:config_update` | C2S | 配置保存（JSON 分片） |
| `cstmm:request_config_sync` | C2S | 请求配置同步（携带客户端缓存哈希，限频 2 次/秒/人） |
| `cstmm:match_action` | C2S | 匹配/投票/购买等动作 |
| `cstmm:match_status` | S2C | 对局状态消息广播 |
| `cstmm:hud_data` | S2C | HUD 数据（每秒推送） |
| `cstmm:player_profile` | S2C | 玩家履历（JSON） |
| `cstmm:leaderboard` | S2C | 全服履历排行（紧凑 JSON 分片；履历页打开时请求，K/D/胜率/总击杀排序由客户端本地完成） |
| `cstmm:open_config_screen` | S2C | 服务端请求打开 OP 配置界面（空包） |
| `cstmm:shop_data` | S2C | 商店数据（购买资格 + 商品列表） |
| `cstmm:clan_action` | C2S | 战队操作（创建/加入/退出/解散/转让/踢人/编辑/查询；大徽标自动分片上传） |
| `cstmm:clan_data` | S2C | 战队数据（MINE/LIST/DETAIL JSON 快照；badge 字段 = 内容寻址 id） |
| `cstmm:badge` | S2C | 战队徽标分片下发（badgeId 内容寻址，每片 ≤30000 字符） |
| `cstmm:request_badge` | C2S | 徽标缓存缺失请求（客户端内存+磁盘均无该 badgeId 时请求补发） |
| `cstmm:badge_known` | C2S | 客户端进服上报磁盘缓存已有的徽标 id（服务端跳过重发） |
| `cstmm:queue_status` | S2C | 队列状态快照（订阅制，变化时推送） |
| `cstmm:request_queue_status` | C2S | 队列状态订阅开关（打开匹配主菜单订阅、关闭菜单退订） |
| `cstmm:popup` | S2C | 弹窗通知（匹配菜单/配置界面内嵌弹窗，其他情况用全局弹窗界面 PopupScreen 承载、确定后返回原界面） |
| `cstmm:set_face` | C2S | 设置/清除自己的头像绑定（avatarType + avatarId；服务端校验后写入玩家档案并同步档案/战队/队列三处下发） |
| `cstmm:clan_hints` | S2C | 战队名+成员名提示快照（`cstmm data get\|edit\|delete clan` 的 Tab 补全数据源；JOIN 与战队变更时推送） |

> 逐包字段定义与分片防护参数详见 NETWORK.md §5.2（单包上限：C2S 32768B / S2C JSON 65536B；徽标分片上传 totalParts ∈ [1,64]、累计 ≤1.92M 字符；下发 totalParts ∈ [1,3]）。

### 3.2 握手流程

```
玩家加入 ──► 服务端 JOIN 事件：发 handshake_s2c{serverVersion}，登记待应答
客户端收到 ──► 本地比对版本 ──► 回发 handshake_c2s{clientVersion}（无论匹配与否都回发）
              └─► 首次结果提示聊天消息（handshakeMessageShown 去重，服务端重试不会重复提示）
服务端收到应答 ──► 移除待应答记录；版本不一致仅 WARN（功能在客户端侧禁用）
未收到应答 ──► 服务端每秒重试，最多 2 次（防丢包）；客户端 8 秒未握手成功显示超时提示
```

- 版本号双方各自从 FabricLoader mod 元数据读取（与握手同源，也用于界面右下角版本显示）。
- **版本不一致时客户端禁用全部功能**，因此两端版本必须一致，服务端与客户端需同步更新部署。

### 3.3 各包字段与编码

**match_action**（C2S）：`enum action` + `string mapName(64B)` + `int team` + `string target(64B)`

- `ActionType` 枚举（ordinal 序列化，**只能在末尾追加**）：
  `JOIN_QUEUE, LEAVE_QUEUE, VOTE_YES, VOTE_NO, VOTE_OVERTIME, SELECT_TEAM, BUY_ITEM, REQUEST_PROFILE, REQUEST_SHOP, JOIN_QUICK, REQUEST_LEADERBOARD`
- `JOIN_QUEUE`：mapName=地图 ID（**不再接受 `quick` 伪地图 ID**，与真实地图名冲突已修复）；team=队伍偏好（1 红 / 2 蓝 / 其他=自动）；**target=模式**（`COMPETITIVE`/`CASUAL`，空或非法按竞技兜底）
- `JOIN_QUICK`：快速匹配专用动作，mapName/team 空闲，**target=模式**（同上规则）；进入按模式独立的快速匹配队列
- `BUY_ITEM`：mapName 空闲，team=商品下标
- `REQUEST_SHOP`：请求当前可购买的商品数据
- `REQUEST_LEADERBOARD`：请求全服履历排行（履历页打开时发送；服务端以 `cstmm:leaderboard` 分片回发）

**hud_data**（S2C）：`string mapName(64B)` + `int redKills` + `int blueKills` + `int remainingSeconds` + `bool inGame` + `varInt phase`（GamePhase ordinal）+ `string rosterJson(65536B)`。对局期间每秒广播（订阅式去重：内容不变跳过），结束时发 `inGame=false` 清除包。`remainingSeconds` 准备阶段=准备倒计时、战斗阶段=对局剩余秒数。`rosterJson` 为花名册 JSON（字段 u/n/t/k/d/lk/c/b/at/ai，详见 NETWORK.md），血量/存活由客户端本地读取。相关服务端新增：MatchSession 个人击杀/死亡/本条命击杀/上一条命击杀统计（EventListener 击杀与死亡事件写入）与玩家名缓存（setupPlayer 写入）。

**match_status**（S2C）：`enum type（MATCH_STARTING / MATCH_ENDED / VOTE_STARTED / VOTE_RESULT / COUNTDOWN）` + `string message(128B)` + `int redKills` + `int blueKills`。

**player_profile**（S2C）：履历 JSON 字符串；**UTF-8 字节数 ≥ 65536 时跳过发送**并 WARN（按字节校验，不是字符数）。

**leaderboard**（S2C）：分片包（`varInt partIndex` + `varInt totalParts` + `string data(32767B)`，每片 ≤30000 字节），拼接后为 `{"players":[{"name","kills","deaths","matches","wins"},...]}`——全服档案的排行字段快照。排序（K/D、胜率、总击杀）由客户端本地完成；履历页打开时经 `REQUEST_LEADERBOARD` 触发，重新切页即更新。详见 NETWORK.md §5.2。

**shop_data**（S2C）：`bool eligible` + `varInt itemCount` + 循环 `string itemId(256B)` + `varInt price` + `varInt maxPurchase`。数据流：客户端打开 ShopScreen 时在**构造器**发送 `REQUEST_SHOP`（放 init 会因界面刷新循环重复请求）→ 服务端校验（活跃对局 + isCompetitive）→ 回 `shop_data` → `ShopDataCache` 缓存并刷新界面。

**open_config_screen**（S2C）：空包。**request_config_sync**（C2S）：`string clientHash`（客户端本地缓存的核心配置哈希，空载荷防御解码兼容旧版客户端）。服务端限频 **2 次/秒/玩家**，超限拒绝并提示；哈希一致仅回 `config_meta` 小包，不一致下发全量。

**config_meta**（S2C）：`string configHash` + `string inUseMapsJson`。详见第 4 节哈希握手。

**config_sync / config_update**：分片机制详见第 4 节。

### 3.4 限频与防护汇总

| 位置 | 规则 |
|---|---|
| `request_config_sync`（C2S） | 服务端限频 2 次/秒/玩家，超限拒绝并提示 |
| `config_update`（C2S） | 重组防护：`totalParts ∈ [1,64]`、`partIndex` 越界丢弃、重复分片（位图去重）丢弃、单玩家累积 > 2MB 清空重组状态并 WARN、断线清理缓冲 |
| 客户端自身发包 | 客户端自超 50 次/秒抛 `ConfigSyncRateLimitException` 崩溃（自检） |

### 3.5 兼容性注意

- 枚举字段使用 **ordinal 序列化**：`ActionType`、`MatchStatusPayload.StatusType` 只能在枚举**末尾**追加新值，中间插入/删除会导致两端语义错位。
- 服务端与客户端模组版本必须一致；Fabric API 使用锁定的 0.115.6+1.21.1（`fabric.mod.json` 声明 `"fabric-api": "*"`，可按需收紧为 `>=0.115.6+1.21.1`）。
- 新增 S2C/C2S 包时两端必须同步注册，否则解码不一致会被原版断开连接。
- **不使用自定义命令参数类型**（服务器环境限制）：战队名参数用原生 `StringArgumentType.string()`，引号包裹空格名由 Brigadier 原生解析；命令树同步无自定义类型依赖。
- 原版限制：S2C 单包 64KB、C2S 单包 32768 字节——配置同步因此引入分片（下节）。

## 4. 配置同步与分片机制

配置 JSON（含 Base64 背景图）可能远超原版单包限制，两端均分片：

- **分片单位**：按 **UTF-8 字节**切分，每片 ≤ 30000 字节，且不切断多字节字符（中文安全）。
- **S2C `config_sync`**：`varInt partIndex` + `varInt totalParts` + `string data(≤32767B)`，data 拼接为核心配置 JSON `{"hash","maps","global"}`。触发时机：玩家 JOIN 哈希不匹配/超时兜底、任意 OP 保存配置后全服广播、玩家请求且哈希不匹配。
- **C2S `config_update`**：字段同上，客户端同样按 30000 字节/片发送（绕过原版 C2S 32768 字节硬限制）。
- **哈希握手省带宽**：JOIN 时服务端先发 `config_meta` 小包（核心配置 SHA-256 + inUseMaps）；客户端与本机磁盘缓存（`ConfigDiskCache`，按服务器标识隔离存 `config/cstmm_client_cache/<serverKey>.json`）比对哈希，一致则回报哈希、服务端跳过全量下发（重连/重复进服从数 MB 降至约 200 字节）。哈希不匹配/无缓存/3 秒未回应 → 兜底全量下发。`request_config_sync` 携带客户端缓存哈希（空载荷防御解码兼容旧版）。
- **`config_meta` 的 inUseMaps**：当前活跃对局的地图列表（独立于哈希之外随小包同步——若计入哈希，对局开始/结束会令全员缓存频繁失效），客户端据此在配置界面阻止删除活跃地图并弹窗。
- **服务端重组防护**（防 OOM/DoS）：
  - `totalParts` 必须 ∈ [1, 64]，否则丢弃并 WARN；
  - `partIndex` 必须在范围内，重复分片（位图去重）直接丢弃；
  - 单玩家累积数据 > **2 MB** 时清空其重组状态并 WARN（合法上限 64 片 × 30KB ≈ 1.92MB，不会误伤）；
  - 断线自动清理重组缓冲。
- **重组完成后校验流程**：出生点非空、地图 ID 去重（新 ID 查重复）、活跃对局的地图禁删改 → 落盘（原子写）→ 全服广播新配置（客户端落盘新哈希，之后重连免全量）。
- **命令侧配置编辑**：`/cstmm data edit map|global`、`delete map` 走同一 ConfigManager 写盘入口（`updateMap`/`updateGlobalConfig`/`removeMap`，含出生点非空与 ID 查重校验），成功后经 `NetworkHandler.broadcastConfigUpdate()` 全服广播（在线客户端免重连拿到新配置）。
- **开局时序**：`MatchManager.startMatch` 仅在初始化成功后才将玩家移出队列。

### 4.1 客户端同步保护

- 配置界面有未保存修改时，**阻断被动配置同步刷新**（防覆盖编辑内容）。
- 保存前校验失败（出生点为空 / ID 重复）的地图拒绝落盘。
- 旧 `maps.json` 的 `minPlayers` 自动迁移为 `minRedPlayers = minBluePlayers = minPlayers/2`（至少 1）；旧 `matchMode` 字段由 Gson 自动忽略。

## 5. 对局生命周期

```
入队（mapId@mode 复合键；单一无队伍名单，仅做容量检查 = maxRed + maxBlue 之和）
  ──► 开局条件：该模式队列总人数 ≥ minRedPlayers + minBluePlayers
  ──► 开局时才分配队伍（QuickMatchEngine.splitIntoTeams，与快速匹配共用分队核心）：
        随机打乱 → 战队聚组 → 战队优先 + 人数平衡分队（遵守 maxRed/maxBlue）→ fixMinPlayers 修正两队最低人数
  ──► 同图竞技/休闲队列先到先得；开局成功后 dissolveQueuesForMap 解散该图全部剩余队列并提示玩家
  ──► startMatch（session.setCompetitive(queueMode)）
        竞技：InventoryManager 存包清包 + 发 defaultGear + 商店可用 + 友伤启用
        休闲：不动背包 + 不发装备 + 商店禁用 + 友伤禁用（同队伤害取消）
  ──► 准备阶段（prepareTime）→ 计分/计时 → 结束
        胜负：KILLS 先达 targetKills / TIMER 到 maxDuration 击杀多者
        平局：tieRule = DRAW 直接结束 / OVERTIME 发起加时投票（全票通过延长）
        结束：竞技恢复背包 + 传送原点；休闲仅传送原点；记录战绩（仅此时一次）
  ──► 地图进入 cooldownSeconds 冷却
```

**快速匹配**：每秒尝试 → 优先搜未占用且不在冷却的地图直接开局 → 人数不足时与同模式地图队列合并（`tryStartWithOtherQueue`，合并后达两队最低人数之和即开）→ 超过 `quickTimeout` 秒进入补位。

**补位**：加入同模式进行中对局（需 `reinforceable: true`），遵守队伍平衡，统一走 `MatchManager.registerAndSetupPlayer()`；CONDITIONAL 补到该队最低人数，ALWAYS 补到该队满员（`maxRedPlayers`/`maxBluePlayers`；为 0 = 无上限的队把所有等待玩家全部补入）。冷却中的地图被快速匹配过滤。

**边界系统**：对局内玩家离开其队伍生效的边界矩形开始警告计时 → 超过 `boundaryWarningTime` 秒处决，对方队 + `boundaryPenaltyKills`，本人记惩罚死亡，计时重置。**红蓝两队分别判定**：红队（team=1）优先用 `redBoundary`，蓝队（team=2）优先用 `blueBoundary`，队伍专属边界全 0（未配置）时回退公共 `boundary`（`MapConfig.getBoundaryForTeam`）；三边界全 0 时整体跳过检测。判定细节：坐标 floor 到方块后判定（站在边界方块上算界内）；某维 Min > Max 时自动交换。

**重生**：对局未结束自动传回本队出生点（`dimension` 维度正确传送）。

**计时器**：对局结束（含超时）需等待投票结果再终局，禁止重复调用 `handleTimerEnd`；投票超时/对局已结束时静默清理。踢人发起者有 `kickCooldownSeconds` 冷却。

## 6. 数据持久化与保护

| 文件 | 内容 | 说明 |
|---|---|---|
| `config/cstmm/configs/maps.json` | 地图配置 | 原子写盘；单条坏数据仅跳过该条 |
| `config/cstmm/configs/global.json` | 全局配置 | 原子写盘 |
| `config/cstmm/data/players/<player_uuid>.json` | 玩家档案（战绩 + 头像绑定 avatarType/avatarId，逐玩家一文件，**进服即创建**） | 原子写盘；**损坏档案不加载、不覆盖**，玩家进服收到聊天警告，管理员修复后重启生效 |
| `config/cstmm/data/bags/<player_uuid>.json` | 对局背包快照（逐玩家一文件） | 原子写盘；已有未恢复快照绝不被覆盖；损坏文件不加载、不覆盖、不删除；恢复 = 清背包 → 恢复 → 成功才删快照（null 字段保护），失败保留可重试；旧版单文件 `bags.json` 启动时自动迁移并重命名为 `bags.json.migrated` |
| `config/cstmm/data/clans.json` | 战队数据（名称/缩写/徽标 base64/队长/成员/上限） | 原子写盘；损坏时 `loadFailed` 拒绝一切战队变更（创建/加入/退出等全部返回错误提示）且绝不覆盖用户文件，管理员修复后重启生效 |
| **客户端** `config/cstmm/client/config.json` | 客户端个性化配置（当前仅界面主题色 themeColor） | 懒加载 + 变更即原子落盘；损坏按默认值兜底 |
| **客户端** `config/cstmm/client/cache/config_files/<host_port>.json` | 配置磁盘缓存（核心配置 JSON + 哈希，按服务器隔离；标识中冒号等非法字符替换为下划线） | 哈希一致时重连免收全量配置；损坏/缺失静默降级为全量重拉 |
| **客户端** `config/cstmm/client/cache/badges/<host_port>/<badgeId>.b64` | 徽标磁盘缓存（内容寻址，按服务器隔离，逐徽标一文件） | 分片收齐即落盘；进服上报已知 id 服务端免重发；文件名经 hex16 白名单校验，损坏静默忽略 |

战绩只在**对局结束时**记录一次（防双重计分）；停服时统一保存（单点注册，不重复）。`getGlobalConfig()` 返回共享只读实例以减少开销。

## 7. 管理员与调试命令

| 命令 | 权限 | 说明 |
|---|---|---|
| `/cstmm match forceend <sessionId>` | OP≥2 | 强制结束指定对局；支持完整 UUID 或 `match list` 显示的 8 位短 ID（Tab 可补全，前缀歧义时会列出候选） |
| `/cstmm config` | OP≥2 | 打开配置界面（唯一入口，匹配菜单不提供配置入口） |
| `/cstmm reload` | OP≥2 | 从磁盘重新加载配置 |
| `/cstmm data restore bags <玩家>` | OP≥2 | 恢复玩家全部已保存背包 |
| `/cstmm data restore bags <玩家> <槽位>` | OP≥2 | 恢复指定槽位（0-40） |
| `/cstmm data get player <玩家名\|UUID> [字段]` | OP≥2 | 查看玩家战绩档案（支持离线玩家，全量输出含头像绑定行）；可选字段输出单值：kills / deaths / matches / wins / penaltydeaths / kd / name / uuid / avatar（未绑定时显示"未绑定"）/ bags（背包快照概要：保存时间+非空物品数） |
| `/cstmm data get clan "<战队名>" [字段]` | OP≥2 | 查看战队信息；可选字段输出单值：name / abbr / limit / leader / members / badge（超长 base64 只回长度与文件位置）/ createdat。**战队名含空格必须加引号**（string() 参数由 Brigadier 原生读引号并剥引号；无引号输入读到空格即停 → 解析失败）；**引号内名字首尾不得有空白**（`" Team Kun"` 不合法，执行器拒绝并提示正确写法 `"Team Kun"`）。Tab 补全：引号输入（`"Te`）→ 建议 `"Team Kun"` 完整引号名；无引号输入 → 裸词候选。字段为独立参数，Tab 裸字段词 |
| `/cstmm data get map <地图ID> [字段]` | OP≥2 | 查看单张地图配置：省略字段输出全字段摘要；指定字段输出单值（id / displayname / enabled / wincondition / targetkills / maxduration / tierule / boundary / redboundary / blueboundary / redspawns / bluespawns / minplayers / cooldownseconds / minredplayers / minblueplayers / preparetime / boundarywarningtime / boundarypenaltykills / kickcooldownseconds / reinforcementmode / maxredplayers / maxblueplayers / reinforceable / dimension / background / shopitems） |
| `/cstmm data get global [字段]` | OP≥2 | 不带字段输出 global.json 原文；指定字段输出单值：quicktimeout / gear（默认装备列表） |
| `/cstmm data delete player <玩家名\|UUID> [profile\|bags\|avatar\|all]` | OP≥2 | 删除玩家数据：省略类型默认 all（战绩档案 players/<uuid>.json + 背包快照 bags/<uuid>.json）；avatar=清除头像绑定（写盘并同步在线客户端三处展示点，原本无绑定时提示无可删数据）；档案删除同时解除损坏标记，玩家重进服后从零建档 |
| `/cstmm data delete clan "<战队名>"` | OP≥2 | 删除战队（等同队长解散：清除三索引并落盘，在线成员收到通知与最新 MINE）。**战队名含空格必须加引号**（string() 原生引号解析）；引号内名字首尾不得有空白（同 get） |
| `/cstmm data delete map <地图ID>` | OP≥2 | 删除地图配置（写盘并广播全服；正在对局中使用的地图拒绝删除，防对局僵死） |
| `/cstmm data get maps\|clans` | OP≥2 | 读取对应 JSON 文件原文；≤1500 字符直接聊天输出，超出则回文件路径 + 开头预览 |
| `/cstmm data edit player <玩家名\|UUID> <字段> <值>` | OP≥2 | 修改档案字段：kills / deaths / matches / wins / penaltyDeaths（支持离线玩家，改后写盘并同步在线客户端） |
| `/cstmm data edit player <玩家名\|UUID> avatar <qq\|bili> <账号ID>` / `avatar clear` | OP≥2 | 设置/清除玩家头像绑定（支持离线玩家；账号 ID 纯数字，校验规则与客户端个性化页一致；改后写盘，在线玩家同步履历页/战队成员列表/队列页三处头像展示） |
| `/cstmm data edit player <玩家名\|UUID> name <新名字>` | OP≥2 | 修改玩家档案显示名（仅影响履历/查询展示，不改 UUID；改后写盘并同步在线客户端） |
| `/cstmm data edit map <地图ID> <字段> <值>` | OP≥2 | 命令侧修改地图配置任意字段（改后经 ConfigManager 写盘并广播全服在线客户端）：标量与整数字段直接赋值（targetkills/maxduration/boundarywarningtime/minredplayers/minblueplayers ≥1，其余整数 ≥0）；enabled/reinforceable 接受 true/false/1/0；wincondition=KILLS\|TIMER、tierule=OVERTIME\|DRAW、reinforcementmode=CONDITIONAL\|ALWAYS；boundary/redboundary/blueboundary 用 6 整数 `<minX> <minY> <minZ> <maxX> <maxY> <maxZ>` 或 clear 清空（全 0=未配置回退公共）；redspawns/bluespawns 用 `add <x> <y> <z>` / `remove <序号>`（至少保留 1 个）/ 不可 clear；shopitems 用 `add <物品ID> [价格] [限购]`（末尾两个纯数字解析为价格与限购，支持 "xxx 64" 数量后缀但此时需同时给价格限购）/ `remove <序号>` / `clear`；id 改名（非 ENDED 对局占用时拒绝，与删除同一保护）查重；displayname/background 接受任意文本（background 支持 clear） |
| `/cstmm data edit global <字段> <值>` | OP≥2 | 命令侧修改全局配置（改后写盘并广播全服）：quicktimeout <秒≥1>；gear set <槽位> <物品ID>（槽位与配置界面同口径：armor.head/chest/legs/feet/body、weapon.mainhand/offhand、container.0~35，兼容简写 head/chest/legs/feet/mainhand/offhand；物品ID 可含数量后缀或 SNBT）；gear remove <槽位>（跨写法等价匹配，`head` 可删 `armor.head` 条目）/ gear clear。gear set 按槽位等价键覆盖同槽旧配置（head 与 armor.head 视为同一槽，不产生重复条目） |
| `/cstmm data edit clan "<战队名>" <字段> <值>` | OP≥2 | 修改战队字段：name / abbr / limit（人数上限）/ leader（转让队长，支持离线成员）/ badge（URL、base64 ≤48KiB 或 clear 清空；游戏内命令 256 字符上限，超长 base64 经服务器控制台输入；改后写盘并推送成员客户端）。**战队名含空格必须加引号**（string() 原生引号解析）；**引号内名字首尾不得有空白**（`" Team Kun"` 不合法）。Tab 补全：引号输入 → `"完整名"`；字段（裸词）→ 值（leader=成员名、badge=clear、limit=常用数字） |
| `/cstmm debug match_info_hud <t\|f>` | OP≥2 | 调试：强制显示/关闭比赛信息栏（屏幕顶部中央，淡入约 1 秒）。**仅作用于执行者自己的客户端**（信息栏为客户端显示，仅限游戏内玩家执行，不影响其他玩家）。开启后立即向执行者推送一次，此后每秒向其推送首个活跃对局的 HUD；无活跃对局时向执行者发调试预览计分板（地图名"HUD 调试预览"+ 0:0 + 90 秒倒计时，仅中央计分板无队伍卡片，验证显示链路）；关闭时立即向执行者发清空包（inGame=false）复位其 HUD |
| `/cstmm debug bandwidth start\|stop\|get\|view` | OP≥2 | 调试：服务器带宽记录（Netty 管线最外侧字节计数器，统计每个连接加密压缩后的线路字节，含原版与模组全部流量；玩家 JOIN 时注入）。start=清零并开始逐秒采样；stop=停止并返回时长/总量/峰值汇总；get=当前最近 1 秒出/入站速率；view=总量/均值/峰值摘要 + 最近 15 条逐秒样本。采样线程为常驻 1Hz 守护线程 |
| `/cstmm debug exception_protect_test [confirm]` | OP≥2 | 调试：全局异常保护链路测试（**危险操作，两步确认**）。不带参数=发起（发出警告并开启 30 秒确认窗口）；`confirm`=窗口内确认后从执行器抛出人为测试异常，经本命令的 guard 包装走与生产完全相同的保护链路（ModGuardian.engage → 控制台完整堆栈 → 全服警告广播 → 活跃对局强制结算 → 模组停用，服务器不崩）。保护已启用时被 guard 拦截并提示；重启服务器恢复 |

玩家可用命令见 [README.md](README.md) §五。

## 8. 配置文件字段参考

`maps.json`（数组，每项一张地图）：

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `id` | string | 必填 | 地图唯一 ID（不可重复） |
| `displayName` | string | "" | 显示名 |
| `enabled` | bool | true | 是否启用 |
| `winCondition` | string | KILLS | `KILLS` / `TIMER` |
| `targetKills` | int | 10 | 击杀目标 |
| `maxDuration` | int | 1800 | 时限（秒，TIMER 用） |
| `tieRule` | string | OVERTIME | `OVERTIME` / `DRAW` |
| `minRedPlayers` / `minBluePlayers` | int | 1 / 1 | 两队各自最低人数（开局门槛 + CONDITIONAL 补位阈值） |
| `maxRedPlayers` / `maxBluePlayers` | int | 0 | 最高人数，0 = 无上限 |
| `cooldownSeconds` | int | 0 | 对局结束后的地图冷却 |
| `reinforceable` | bool | false | 是否允许补位 |
| `reinforcementMode` | string | CONDITIONAL | `CONDITIONAL` / `ALWAYS` |
| `prepareTime` | int | 5 | 准备阶段秒数 |
| `boundaryWarningTime` | int | 10 | 边界警告秒数 |
| `boundaryPenaltyKills` | int | 5 | 越界处决惩罚击杀 |
| `kickCooldownSeconds` | int | 60 | 踢人投票发起冷却 |
| `dimension` | string | minecraft:overworld | 对局维度 ID（非法值回退主世界） |
| `boundary` | object | 全 0 | 公共边界 `minX..maxZ` 六个 int |
| `redBoundary` / `blueBoundary` | object | 全 0 | 红/蓝队专属边界，全 0 时该队回退 `boundary` |
| `redSpawns` / `blueSpawns` | 数组 | [] | 出生点 `{"x","y","z"}`（保存校验非空） |
| `shopItems` | 数组 | [] | 本图商品 `{"itemId","price","maxPurchase"}`（ShopItem 类保留在 GlobalConfig） |
| `backgroundBase64` | string | "" | 卡片背景图（PNG 的 Base64） |

`global.json`：`quickTimeout`（快速匹配超时秒数，唯一全局对局参数）、`defaultGear`（竞技默认装备 `{"slot","itemId"}`）。slot 与配置界面校验器同口径：`armor.head/chest/legs/feet/body` 或 `container.0~container.35`（`/item replace entity` 语法）；同时兼容简写 `head/chest/legs/feet/mainhand/offhand` 与 `weapon.mainhand/offhand`（`EquipmentManager.applyToSlot` 全部识别：armor.* 映射对应盔甲槽、weapon.* 映射主手/副手、container.N 替换主背包 N 号槽、armor.body 玩家无该槽位进背包/掉落、未知槽位塞背包或掉落；slot 为 null/空串的条目跳过）。

## 9. 日志规范

所有 LOGGER 消息统一 `[CSTMM - 模块名]` 前缀，例如：

```
[CSTMM - MatchManager] Started match on town with 8 players
[CSTMM - ClientNetwork] Failed to parse config sync
```

模块名与类/职责对应（与代码实际一致）：`Main`、`Network`、`ClientNetwork`、`Client`、`ConfigManager`、`MatchManager`、`QueueManager`、`VoteManager`、`ClanManager`、`InventoryManager`、`PlayerDataManager`、`EquipmentManager`、`EventListener`、`MatchScheduler`、`BlockPosAdapter`、`Commands`、`MatchMenuScreen`、`ClanCache`、`QueueStatusCache`、`BadgeCache`、`Base64ImageDecoder`。

## 10. 常见问题排查

| 现象 | 原因与处理 |
|---|---|
| 进服提示"未与服务端握手成功" | 服务端未装本模组或两端版本不一致；核对 `gradle.properties` 的 `mod_version`（构建后部署两端）与 Fabric API 版本 |
| 配置界面空白/地图列表空 | 配置同步未完成（等几秒）或分片失败，查看客户端 `[CSTMM - ClientNetwork]` 日志 |
| 保存配置后部分地图消失 | 保存校验拒绝（出生点为空或 ID 重复），看服务端 `[CSTMM - ConfigManager]` WARN |
| 保存配置时提示地图正在使用 | 该地图处于活跃对局中（`inUseMaps` 保护），先结束对局再改 |
| 匹配一直不开局 | 检查地图 `minRedPlayers/minBluePlayers`、是否已有对局占用、`cooldownSeconds` 冷却 |
| 商店显示"无法购买" | 仅竞技模式对局内可购买（BUY_ITEM 预检 + 发放双校验）；确认对局未 ENDED |
| 玩家战绩丢失且提示档案损坏 | `data/players/` 下 JSON 损坏；按日志中的玩家 UUID 修复或删除该文件（删除后从零开始） |
| 对局中玩家卡在边界外被反复处决 | 检查 `boundary` 是否配置正确；死亡重生会自动送回出生点 |
| HUD 一直显示旧对局数据 | 客户端未收到 `inGame=false` 清除包或断线未重置；查看 `[CSTMM - ClientNetwork]` 日志 |
| 客户端商店数据不刷新 | `shop_data` 缓存问题；ShopScreen 构造器会发 REQUEST_SHOP，检查服务端限频与 isCompetitive 校验 |

## 全局异常保护（保护模式）

模组全部服务端入口（每秒调度、五个事件监听器、全部 C2S 网络接收器与 JOIN/DISCONNECT 回调、全部 43 个命令执行器、初始化注册与生命周期回调）均经 manager/ModGuardian 包装：

- 任一入口抛出未捕获 Throwable（含 Error）→ **进入保护模式**：完整异常堆栈打印到服务器控制台、向全体在线玩家广播警告、尽力强制结算所有活跃对局（恢复玩家原点/背包/游戏模式），此后模组全部功能停用（各入口直接跳过，命令提示"模组已进入保护模式"），服务器本体继续运行，保护持续到重启；
- 秒级调度不再逐任务吞异常——任一子系统异常即进入保护模式，避免带病运行；
- 停服保存（SERVER_STOPPING）为独立 try/catch：单项保存失败不跳过另一项（停服阶段保护模式无意义）。
