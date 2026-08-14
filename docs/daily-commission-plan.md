# LunaGC 6.7 私服「每日委托（Daily Commission）」系统构建计划

> 适用范围：`E:\YSPrivateServer\Server\LunaGC`（包根 `emu.grasscutter`，Java 17 编译目标 / 运行于 Java 21）。
> 本文档为**调研结论 + 实施计划**，不含任何服务器 IP、账号、密码、UID 等敏感信息。
> 客户端协议（6.7）字段号为混淆、非连续编号，一切字段号以 `src/generated` 生成类为准。

---

## 0. 结论速览（TL;DR）

| 维度 | 结论 |
|---|---|
| 协议 | **本仓库无任何 DailyTask/每日委托消息**（无 `DailyTaskInfo`、`GetDailyTaskInfoReq/Rsp`、`DailyTaskInfoNotify`、`DailyTaskRewardNotify`、`DailyTaskProgressNotify` 等生成类；`PacketOpcodes.java` 亦无对应 opcode）。唯一相关残留：`PersonalLineAllDataRsp.cur_finished_daily_task_count`。 |
| 数据 | **客户端资源里有完整委托数据**，但服务器无 Java 类加载它们：`resources/ExcelBinOutput/DailyTaskExcelConfigData.json`（委托定义）、`DailyTaskLevelExcelConfigData.json`（等级档位）、`DailyTaskRewardExcelConfigData.json`（奖励掉落）。 |
| 可行性 | **可行**。骨架可纯用本仓库资源 + 现有 Quest/Watcher/World 时间/奖励系统搭建；客户端可见面板需要额外补齐协议（仓库内缺失，需抓包/外部 proto）。 |
| 推荐路线 | 以「客户端可见面板」为**目标架构**（路线 A），以「无头骨架」为**立即可落地阶段**（路线 B）。骨架的 Manager/数据/刷新/发奖逻辑在路线 A 下 100% 复用，仅补协议层即可切换。 |
| 刷新点 | **每日 04:00（UTC+8 / Asia/Shanghai）**，基于真实墙钟时间计算（非服务器本地时区、非游戏内 World 时间）。 |
| 奖励 | 单个委托：原石 10 + 摩拉/冒险阅历/好感阅历（`ActionReason.DailyTaskScore(26)`）；全清 4 个：额外原石 + 阅历 +（可选）传说钥匙（`DailyTaskExchangeLegendaryKey(72)`）。 |

---

## 1. 调研结论摘要

### 1.1 协议现状（有/无委托消息）

**结论：本仓库 `src/generated` 内没有每日委托相关消息；`PacketOpcodes.java` 内没有每日委托相关 opcode。**

核实过程（均为实际 grep/read，非推断）：

1. 全仓库搜索 `DailyTask|DailyTaskInfo|GetDailyTaskInfo|DailyTaskReward|DailyTaskScore`：
   - 唯一命中 `DailyTask*` 的是 `src/generated/.../PersonalLineAllDataRspOuterClass.java` 的字段 `cur_finished_daily_task_count`（见 1.2）；以及 `game/props/ActionReason.java` 里的 `DailyTaskScore(26)/DailyTaskHost(27)/DailyTaskGuest(33)/DailyTaskExchangeLegendaryKey(72)`。
2. 生成 proto 目录里搜 `Daily|Commission`：只命中**无关**消息 `BuoyantCombatDailyInfo`、`DailyDungeonEntryInfo`、`EffigyDailyInfo`、`FlightDailyRecord`、`GetDailyDungeonEntryInfoReq/Rsp`（这些是「每日副本/活动」，不是「每日委托」）。
3. 生成 proto 目录里按 `source: *.proto` 头注释搜 `Task`：仅 `BartenderTaskInfo`、`CancelCoopTaskReq/Rsp`、`GetDailyDungeonEntryInfoReq/Rsp`。
4. `PacketOpcodes.java`（716 行）全文无 `GetDailyTaskInfoReq`、`DailyTaskInfoNotify`、`DailyTaskRewardNotify`、`DailyTaskProgressNotify`、`DailyTaskUnlockedCitiesReq/Rsp`、`DailyTaskScoreRewardNotify` 等任何条目。文件末尾 `BANNED_PACKETS` 只有 3 个无关 opcode。
5. 全仓库搜消息名变体（`DailyTaskInfoNotify|GetDailyTaskInfo|DailyTaskRewardNotify|DailyTaskProgress|DailyTaskUnlockedCities|DailyTaskScoreReward|DailyTaskVar`）：**0 命中**。
6. 仓库无 `proto/` 源码目录（`build.gradle` 里 `protobuf files('proto/')` 但该目录不存在，生成类是预先提交进 `src/generated` 的快照）。`patch/` 是 Rust 客户端拦截器（hk4e-patch），不含 proto 定义；`GM Handbook/` 是纯文本手册。

**含义**：客户端（6.7）确实有委托 UI（每天 4 个委托、凯瑟琳/冒险家协会入口），客户端会发 `GetDailyTaskInfoReq` 等消息；但本服务器的 proto 快照**漏掉了这批消息的生成类与 opcode 映射**。要“客户端可见”，必须从外部补齐协议（见 1.4）。

### 1.2 字段号混淆的实证（重要）

`PersonalLineAllDataRspOuterClass.java` 中字段 `cur_finished_daily_task_count`：

- 注释写 `uint32 cur_finished_daily_task_count = 11;`（旧/官方号）。
- 但生成代码实际序列化用 `output.writeUInt32(12, ...)` 与 `computeUInt32Size(12, ...)`。

即：**注释字段号（11）≠ 实际 wire 字段号（12）**。这印证了约束里“字段号被混淆、非连续编号，不能照抄旧版 Grasscutter，字段号以本仓库生成类为准”。因此**任何新增 DailyTask 消息的字段号都必须来自 6.7 客户端实际协议（抓包）或对应 proto 快照，绝不能照抄旧版 Grasscutter 的 `.proto` 字段号**。

### 1.3 数据现状（已实测：资源完整，服务器尚未加载）

- **没有“委托任务模板”**：quest 数据（`resources/ExcelBinOutput/QuestExcelConfigData.json`，约 28.5 MB；`resources/BinOutput/Quest/*.json`，2918 个主任务文件）里没有“每日委托”类型标记。`MainQuestData.type` 用 `QuestType` 枚举（`AQ/FQ/LQ/EQ/DQ/IQ/VQ/WQ`），`DQ(4)` 名义上是“Daily Quest”，但 6.7 的每日委托**不走 Quest 系统**（少数委托通过 `questId` 关联到 QuestExcel，见下）。
- **客户端资源里有全地区完整委托数据**（已用脚本核实）：
  - `resources/ExcelBinOutput/DailyTaskExcelConfigData.json`：**677 条**委托，`cityId` 覆盖 **1~7**（蒙德/璃月/稻妻/须弥/枫丹/纳塔/至冬），**33 个 poolId**（`1001,1002,1003,1101,1102,1103,1104,2003,2101,3001,3101~3104,3201,3203~3205,3301~3304,3401~3404,3501~3504,4001,5001,6001`）。
  - 字段：`id`、`cityId`、`poolId`、`type`（`DAILY_TASK_SCENE`）、`rarity`、`oldGroupVec[]`/`newGroupVec[]`（场景组 ID 数组）、`finishType`（实测仅 3 种：`DAILY_FINISH_MONSTER_NUM` 杀怪数 / `DAILY_FINISH_CHALLENGE` 挑战 / `DAILY_FINISH_GADGET_ID_NUM` 物件交互；**部分为空**）、`finishProgress`（如 8）、`finishParam1`/`finishParam2`、`taskRewardId`（指向奖励表 1/2/3）、`centerPosition`（场景事件点位名，如 `"Event_10100V2"`）、`enterDistance`/`exitDistance`/`radarRadius`、`titleTextMapHash`/`descriptionTextMapHash`/`targetTextMapHash`（客户端文案 hash）。
  - **257 条带 `questId`**（如 id=20500 → questId=2000101，cityId=1、poolId=2003、taskRewardId=2）：即“对话/任务型委托”关联到 QuestExcel 数据；其余 420 条为纯场景组型（`finishType` 非空）。
  - `resources/ExcelBinOutput/DailyTaskLevelExcelConfigData.json`：**12 档**玩家等级（`minPlayerLevel`/`maxPlayerLevel` 覆盖 1~60），每档有 `scoreDropId`（如 203000000）与 `scorePreviewRewardId`。
  - `resources/ExcelBinOutput/DailyTaskRewardExcelConfigData.json`：`taskRewardId` 1/2/3 → `dropVec[]`，内按等级档给 `dropId`（201000000~201001500、202000000~202001500 等）与 `previewRewardId`。
  - 注意：后两个文件 id 键是**大写 `ID`**，第一个是小写 `id`；资源类需 `@SerializedName("ID")` 处理。
- **服务器当前未加载这三张表**：本仓库 `GameData` 没有 `DailyTaskDataMap` 等字段、没有加载类。**已核实 `src/main/java/emu/grasscutter/data/excels/daily/` 下已存在 3 个 `GameResource` 数据类**（`DailyTaskData.java`、`DailyTaskLevelData.java`、`DailyTaskRewardData.java`，已含 `finishParam1/2`、`questId` 与 `dropVec` 内部类，`@ResourceType` 注解齐备），但 `GameData` 中**尚未注册对应静态 map**（grep 无 `dailyTaskDataMap` 字段）——即“数据类已建、加载接线未完成”。计划按“补齐 GameData 注册 → 走通加载”推进。

### 1.4 可行性判断与两条路线

**路线 A（目标）：实现“客户端可见委托面板”** —— 需要：
1. 6.7 的 DailyTask proto 消息定义 + 混淆字段号（`DailyTaskInfo`、`DailyTaskDataNotify`/`DailyTaskInfoNotify`、`GetDailyTaskInfoReq/Rsp`、`GetDailyTaskRewardReq/Rsp`、`DailyTaskRewardNotify`、`DailyTaskProgressNotify`、`DailyTaskUnlockedCitiesReq/Rsp`、`DailyTaskScoreRewardNotify` 等）；
2. 每条消息的 opcode（客户端发包号 / 服务器回包号）。

这两者**本仓库都没有**，唯一可靠来源是：6.7 客户端抓包（在真服/私服通信中截获 opcode 与 wire 字节），或取得与 6.7 一致的 proto 快照后重生成。这是外部前置工作，非本仓库内可完成。

**路线 B（回退）：无头实现** —— 服务器自建委托管理器，仅用仓库内已有的 3 份 DailyTask 资源 + 现有系统：
- 每日 04:00 派发 4 个随机委托（按 `cityId`/冒险等级过滤）；
- 完成判定走现有触发链（`WatcherTriggerType.TRIGGER_DAILY_TASK(301)` / `TRIGGER_MONSTER_DIE(109)` / `TRIGGER_KILL_GROUP_MONSTER(422)` / quest 完成事件）；MVP 阶段可先用“定时/登录自动完成”或“怪物击杀计数”简化；
- 完成后 `player.getInventory().addItem(...)` 发奖（`ActionReason.DailyTaskScore`）；
- **客户端不弹委托面板**（发不了 `DailyTaskInfoNotify`），但奖励照发；可选通过 `ShowCommonTipsNotify` / `ServerAnnounceNotify` / 邮件给玩家提示。

**推荐**：**先做路线 B 骨架（可立即实施、可测试、无外部依赖），把 Manager / 资源加载 / 04:00 UTC+8 刷新 / 派发 / 完成 / 发奖 / `/refreshdailyquest` 命令全部落地并验证；路线 A 协议就绪后，在同一套 Manager 上补“协议层 + 面板可见”，骨架逻辑零改动或极小改动。**（回退路径：若路线 A 永远无法取得协议，路线 B 即最终形态，功能等价、仅缺客户端面板展示。）

---

## 2. 总体设计

### 2.1 数据结构（玩家态）

新增一个 `DailyCommissionManager`（`BasePlayerManager` 子类，模式同 `QuestManager`），其持久化字段挂在 `Player` 上（随 `Player.save()` 走 Morphia 存库，不另开集合）：

- `List<Integer> activeTaskIds`：今日已派发的 4 个委托 id（指向 `DailyTaskExcelConfigData.id`）。
- `Map<Integer, Integer> taskProgress`：`taskId -> 当前进度`（0..finishProgress）。
- `int finishedTaskCount`：今日已完成并已发奖的委托数（0..4）。
- `boolean isScoreRewardTaken`：全清奖励是否已领取（防重复）。
- `int lastCommissionResetDayKey`：上次刷新的“委托日键”（`LocalDate.toEpochDay()` 的 int 值，见第 4 节）。

> 说明：`Player` 已有 `lastDailyReset`（服务器本地午夜、用于锻造点/纪行/树脂），**不与其复用**——委托刷新点独立（04:00 UTC+8），避免改动现有每日重置逻辑引发副作用。

### 2.2 服务端流程

1. **登录下发**：`Player.onLogin()` → `manager.onLogin()`：判断是否跨过 04:00 刷新点，若是先刷新；随后（路线 A）发送 `DailyTaskInfoNotify` 让客户端面板显示；无头路线则只更新内存/可选提示。
2. **每日刷新**：`Player.onTick()`（已每秒随 `GameServer.onTick()` 调用）→ `manager.onTick()`：检测 04:00 边界，触发 `resetDailyTasks(false)`。
3. **任务完成**：击杀/交互等触发（`GameQuest.finish()` 的 `PlayerCompleteQuestEvent` 或活动 watcher / `TRIGGER_DAILY_TASK`）→ `manager.onTaskProgress(taskId, delta)` / `manager.completeTask(taskId)`。
4. **领奖**：单个完成即时发奖（`completeTask` 内 `grantTaskReward`）；`finishedTaskCount == 4` 时可领全清奖励（`claimScoreReward()`，发奖后置 `isScoreRewardTaken=true`）。
5. **命令刷新**：`/refreshdailyquest [@UID]` → `manager.resetDailyTasks(true)`（清进度、计数归零、重置领奖标记、重抽 4 个、重置日键，并发送最新面板通知）。

### 2.3 「选 4 个委托」池子策略（按 cityId / poolId / 玩家等级 / 已解锁地区）

1. **确定委托地区（cityId）**：
   - 地区→城市映射：`CityData`（`GameData.getCityDataMap()`）含 `cityId`、`sceneId`、`areaIdVec`（该城市所属区域 id 列表）。
   - **“已解锁城市”判定**：对每个 `CityData`，若 `player.getUnlockedSceneAreas(cityData.getSceneId())`（`Set<Integer>` 区域 id）与 `cityData.getAreaIdVec()` 有交集，则该城市已解锁。
   - **委托地区选择**（MVP 简化，可配置）：默认取“已解锁城市中 cityId 最大的一个”（即进度最新地区，与官服“在新地区做委托”直觉一致）；或提供一个配置项 `server.dailyquest.commissionCity` 强制指定；完整版由凯瑟琳交互设置。
2. **按等级档位取 scaling**：用 `DailyTaskLevelData`（12 档，`minPlayerLevel~maxPlayerLevel`）取当前档的 `groupReviseLevel`（场景组等级修正）与 `scoreDropId`/`scorePreviewRewardId`（全清奖励用）。注意：等级档位决定**数值缩放**，不决定“哪些任务可选”（任务池本身无等级字段）。
3. **按 cityId/poolId 过滤候选**：
   - 初筛：`DailyTaskData.cityId == 委托地区`。
   - 若启用 poolId 细分：同一城市内再按 `poolId` 分组，优先从“常规场景组池”（`type==DAILY_TASK_SCENE` 且 `questId==0` 且 `finishType!=null`）抽取；`questId>0` 的对话型委托作为独立子池，按需掺入。
   - 兜底：该 cityId 候选不足 4 时，放宽到“全部已解锁城市”合并池。
4. **抽 4 个**：对候选 `List<DailyTaskData>` 做 `Collections.shuffle`，取前 4 个；**去重**（同一 `id` 不重复），并尽量保证 `finishType` 多样化（杀怪/挑战/物件交互各取一些，避免 4 个全杀怪）。
5. **确定性/可测**：`resetDailyTasks` 内部封装成 `pickDailyTasks(cityId, level, Random)` 纯函数，便于 `/refreshdailyquest` 与单测复现（可注入种子）。

---

## 3. 逐文件改动清单

### 3.1 资源类（3 份 DailyTask JSON → 数据类已建，待接线）

> 现状：`src/main/java/emu/grasscutter/data/excels/daily/` 下**已存在** `DailyTaskData.java`、`DailyTaskLevelData.java`、`DailyTaskRewardData.java`（含 `@ResourceType`、`@Getter`、`getId()`、`onLoad()`，字段含 `finishParam1/2`、`questId`、`dropVec` 内部类 `DropVecEntry{dropId, previewRewardId}`）。本节以实际文件为准，列清字段契约；**剩余工作只有 `GameData` 注册 + 按需补辅助方法**，不要重复建类。

**A. `src/main/java/emu/grasscutter/data/excels/daily/DailyTaskData.java`**（`@ResourceType(name = "DailyTaskExcelConfigData.json")`）
- 字段契约：`int id`、`int cityId`、`int poolId`、`String type`、`int rarity`、`List<Integer> oldGroupVec`、`List<Integer> newGroupVec`、`String finishType`（`DAILY_FINISH_MONSTER_NUM`/`DAILY_FINISH_CHALLENGE`/`DAILY_FINISH_GADGET_ID_NUM`/null）、`int finishProgress`、`int finishParam1`、`int finishParam2`、`int taskRewardId`、`String centerPosition`（如 `"Event_10100V2"`）、`int enterDistance`、`int exitDistance`、`long titleTextMapHash`、`long descriptionTextMapHash`、`long targetTextMapHash`、`int radarRadius`、`int questId`（0=无关联）。
- 注意：`oldGroupVec/newGroupVec` 是 `List<Integer>`（JSON 数组），不是 `int[]`；`onLoad()` 里对二者做 `Collections.emptyList()` 兜底。

**B. `src/main/java/emu/grasscutter/data/excels/daily/DailyTaskLevelData.java`**（`@ResourceType(name = "DailyTaskLevelExcelConfigData.json")`）
- 字段（id 键大写 `ID`，需 `@SerializedName("ID")`）：`int id`、`int minPlayerLevel`、`int maxPlayerLevel`、`int groupReviseLevel`、`int scoreDropId`、`int scorePreviewRewardId`（12 档）。

**C. `src/main/java/emu/grasscutter/data/excels/daily/DailyTaskRewardData.java`**（`@ResourceType(name = "DailyTaskRewardExcelConfigData.json")`）
- 字段（id 键大写 `ID`）：`int id`、`List<DropVecEntry> dropVec`；`DropVecEntry { int dropId; int previewRewardId; }`。

> 注册原理：`ResourceLoader.getResourceDefClasses()` 反射发现所有 `@ResourceType` 的 `GameResource` 子类；`GameData.getMapByResourceDef()` 用 `Utils.lowerCaseFirstChar(SimpleName) + "Map"` 找 GameData 里的静态 map 字段。因此类名与 GameData 字段命名一致即自动加载。

### 3.2 修改 `src/main/java/emu/grasscutter/data/GameData.java`（**尚未做**，关键接线点）

在静态 map 区域新增三个字段（`@Getter` 由 Lombok 生成 getter，**字段名必须与类名对应**）：

```java
@Getter private static final Int2ObjectMap<DailyTaskData> dailyTaskDataMap = new Int2ObjectOpenHashMap<>();
@Getter private static final Int2ObjectMap<DailyTaskLevelData> dailyTaskLevelDataMap = new Int2ObjectOpenHashMap<>();
@Getter private static final Int2ObjectMap<DailyTaskRewardData> dailyTaskRewardDataMap = new Int2ObjectOpenHashMap<>();
```

并提供筛选辅助方法（供派发/奖励使用）：
- `public static Int2ObjectMap<DailyTaskData> getDailyTaskDataMap()`
- `public static DailyTaskLevelData getDailyTaskLevelDataByLevel(int playerLevel)`（按 `minPlayerLevel <= level <= maxPlayerLevel` 取档）。
- `public static DailyTaskRewardData getDailyTaskRewardDataById(int taskRewardId)`。

> 验收：启动后日志/命令可见 `dailyTaskDataMap.size()==677`、`dailyTaskLevelDataMap.size()==12`、`dailyTaskRewardDataMap.size()==3`。

### 3.3 新增管理器 `src/main/java/emu/grasscutter/game/dailycommission/DailyCommissionManager.java`

`extends BasePlayerManager`（`BasePlayerManager` 位于 `emu.grasscutter.game.player`，已有 `protected final transient Player player` 与 `getPlayer()`/`save()`）。

关键方法签名：

```java
public final class DailyCommissionManager extends BasePlayerManager {
    public DailyCommissionManager(Player player);

    public void onLogin();                                  // 登录：先判刷新，再下发（路线A面板/无头提示）
    public void onTick();                                   // 每 tick：检测 04:00 刷新点
    public void resetDailyTasks(boolean force);             // 派发 4 个随机委托；force=true 供命令绕过刷新点
    public List<DailyTaskData> getActiveTasks();            // 当前 4 个委托数据
    public void onTaskProgress(int taskId, int delta);      // 进度累加（供 watcher/quest 触发调用）
    public void completeTask(int taskId);                   // 完成：进度打满 + 发单个奖励 + 计数+1 + 判全清
    public void grantTaskReward(DailyTaskData task);        // 单个委托发奖（ActionReason.DailyTaskScore）
    public void claimScoreReward();                         // 全清奖励（防重复，isScoreRewardTaken）
    public boolean hasResetPassed();                        // 是否已跨 04:00（见第4节）
    public void sendInfoNotify();                           // 路线A：DailyTaskInfoNotify；无头：空实现/可选提示
}
```

内部实现要点：
- `resetDailyTasks(force)`：
  1. 读取 `GameData.getDailyTaskDataMap()`，按玩家已解锁城市（`Player.getUnlockedSceneAreas()` 或简化按 `cityId` 全量）与冒险等级档位过滤候选；
  2. 随机取 4 个（不足 4 个就用现有候选补满/允许少于 4）；
  3. 清空 `activeTaskIds`、`taskProgress`，`finishedTaskCount = 0`、`isScoreRewardTaken = false`；
  4. `lastCommissionResetDayKey = 当前委托日键`；
  5. `save()` + `sendInfoNotify()`。
- `completeTask(taskId)`：幂等（已在完成集合则忽略）；进度置满 → `grantTaskReward` → `finishedTaskCount++`；若 `finishedTaskCount == 4` 且未领，可提示可领全清（是否自动发全清见第 5 节）。

### 3.4 修改 `src/main/java/emu/grasscutter/game/player/Player.java`

1. 新增字段（Morphia 自动持久化，均 `@Getter @Setter`）：
   ```java
   @Getter @Setter private transient DailyCommissionManager dailyCommissionManager;
   @Getter @Setter private List<Integer> activeDailyTaskIds;
   @Getter @Setter private Map<Integer, Integer> dailyTaskProgress;
   @Getter @Setter private int finishedDailyTaskCount;
   @Getter @Setter private boolean dailyScoreRewardTaken;
   @Getter @Setter private int lastCommissionResetDayKey;
   ```
   （若嫌字段散在 Player，也可引入一个 `@Embedded` 的 `PlayerDailyCommissionData`，二选一；MVP 推荐直接挂 Player 字段，减少类数量。）
2. 构造/`onLoad` 处初始化：`this.dailyCommissionManager = new DailyCommissionManager(this);`（参考 `questManager = new QuestManager(this)`，位于 Player 构造，约第 220 行）。
3. `onLogin()`（约第 1351 行 `this.doDailyReset();` 附近）加 `this.getDailyCommissionManager().onLogin();`。
4. `onTick()`（约第 1255 行 `this.getQuestManager().onTick();` 附近）加 `this.getDailyCommissionManager().onTick();`。
5. 登录数据库加载处（参考 `questManager::loadFromDatabase`）确保 Player 字段随 `save()`/加载正常往返（无需单独集合）。

### 3.5 新增命令 `src/main/java/emu/grasscutter/command/commands/RefreshDailyQuestCommand.java`

- 类路径：`emu.grasscutter.command.commands.RefreshDailyQuestCommand`。
- 注解：
  ```java
  @Command(
      label = "refreshdailyquest",
      aliases = {"rdq", "refreshdaily", "dailyrefresh"},
      usage = "[@UID]",
      permission = "server.dailyquest",           // 建议权限键（可按需改）
      permissionTargeted = "server.dailyquest.others",
      targetRequirement = TargetRequirement.ONLINE)
  public final class RefreshDailyQuestCommand implements CommandHandler { ... }
  ```
- 参数解析：`execute(Player sender, Player targetPlayer, List<String> args)`。
  - **`@UID` 由 `CommandMap.getTargetPlayer(...)` 在进入 execute 前解析**：它扫描 `args` 中 `@` 开头的参数并移除，替换 `targetPlayer`；无 `@UID` 时 `targetPlayer` 默认 = `sender`（对自己）。因此命令体无需自己解析 UID，直接用 `targetPlayer`。
  - 命令体：
    ```java
    @Override
    public void execute(Player sender, Player targetPlayer, List<String> args) {
        if (targetPlayer == null) { // 控制台且未指定 @UID
            CommandHandler.sendMessage(null, "usage: refreshdailyquest [@UID]");
            return;
        }
        targetPlayer.getDailyCommissionManager().resetDailyTasks(true);
        CommandHandler.sendTranslatedMessage(sender, "commands.refreshdailyquest.success",
            targetPlayer.getNickname());   // 或 sendMessage 直接输出
    }
    ```
- **调用点**：`resetDailyTasks(true)` 直接复用“派发 4 个 + 清进度 + 计数归零 + 重置领奖标记 + 重置日键 + 下发通知”的同一方法，`force=true` 使其绕过“是否已跨 04:00”的判断（这就是“绕过 4 点刷新点 + 用于测试”的实现）。
- 权限注册：`CommandMap` 通过反射自动发现 `@Command` 注解类并注册（`CommandMap.registerCommand`/`invoke` 已存在），无需额外注册代码；仅需在权限配置/默认权限里给该命令授权。

### 3.6 协议层（路线 A 完成后才动，含字段号来源说明）

> 以下为路线 A 的**占位改动清单**，实际字段号/opcode 待从 6.7 客户端抓包或 proto 快照确认后才能落地，**禁止**照抄旧版 Grasscutter。

- 新增生成类（放 `src/generated` 或重新生成）：`DailyTaskInfo`、`DailyTaskInfoNotify`（或 `DailyTaskDataNotify`）、`GetDailyTaskInfoReq/Rsp`、`GetDailyTaskRewardReq/Rsp`、`DailyTaskRewardNotify`、`DailyTaskProgressNotify`、`DailyTaskUnlockedCitiesReq/Rsp`、`DailyTaskScoreRewardNotify`。
- `PacketOpcodes.java`：新增对应 opcode 常量（值来自抓包）。
- 新增 `server/packet/recv/HandlerGetDailyTaskInfoReq.java`（`@Opcodes(...)` + `extends PacketHandler`）：返回 `GetDailyTaskInfoRsp`/推送 `DailyTaskInfoNotify`。
- 新增 `server/packet/recv/HandlerGetDailyTaskRewardReq.java`：校验 `taskId` 已完成 → 调 `claimScoreReward()`/`grantTaskReward()` → 回 `GetDailyTaskRewardRsp` + `DailyTaskRewardNotify`。
- 新增 `server/packet/send/PacketDailyTaskInfoNotify.java`、`PacketDailyTaskRewardNotify.java`、`PacketDailyTaskProgressNotify.java`（均 `extends BasePacket`，opcode 传入 super）。

> 字段号依据来源（写死进计划，防止未来踩坑）：`PersonalLineAllDataRsp.cur_finished_daily_task_count` 在本仓库注释为 `= 11`、实际 `writeUInt32(12, ...)`——证明注释不可信。**每日委托消息的字段号只能来自 6.7 客户端抓包 wire 字节或同版本 proto 快照**。

---

## 4. 每日刷新逻辑（04:00 UTC+8）

### 4.1 触发时机

- 检测点：`DailyCommissionManager.onTick()`（挂在 `Player.onTick()`，由 `GameServer.onTick()` 每秒驱动）与 `onLogin()`。
- 实现方式（**与时区无关、可靠**）：

  ```java
  private static final ZoneId RESET_ZONE = ZoneId.of("Asia/Shanghai"); // UTC+8，无夏令时，稳定

  /** 计算“委托日键”：以每天 04:00(UTC+8) 为界翻转，返回稳定单调递增的 int。 */
  private static int currentCommissionDayKey() {
      // 原理：当前 UTC+8 时刻减 4 小时后再取日期，等价于“04:00 才进入新的一天”。
      // 例：2025-01-02 03:59(UTC+8) -> 减4h = 01-01 23:59 -> 日键 = 20250101 对应的 epochDay
      //     2025-01-02 04:00(UTC+8) -> 减4h = 01-02 00:00 -> 日键 = 20250102 对应的 epochDay
      return (int) ZonedDateTime.now(RESET_ZONE)
              .minusHours(4)
              .toLocalDate()
              .toEpochDay();
  }

  /** 是否已跨过 04:00 刷新点（相对上次刷新记录的日键）。 */
  public boolean hasResetPassed() {
      return currentCommissionDayKey() != getLastCommissionResetDayKey();
  }
  ```

- `resetDailyTasks(false)` 仅在 `hasResetPassed()` 为真时执行；执行后把 `lastCommissionResetDayKey` 更新为当前值，避免重复刷新。

### 4.2 与服务器本地时区 / 世界时间的关系

- **不用 `ZoneId.systemDefault()`**：现有 `Player.doDailyReset()`（第 1262-1263 行）用的是 `ZoneId.systemDefault()`（服务器本地午夜），其语义是“本地自然日午夜”，与“04:00 委托刷新”不一致。委托刷新必须固定用 `ZoneId.of("Asia/Shanghai")`，与服务器跑在哪个时区无关。
- **不用游戏内 World 时间**：`World.getGameTime()/getTotalGameTimeDays()` 是游戏内时间，玩家可用 `ChangeGameTimeReq`（`HandlerChangeGameTimeReq` → `World.changeTime`）改变，也会随挂机流逝；用它判定“每天 04:00 刷新”会被玩家反复拨时间刷委托。委托刷新必须基于**真实墙钟时间**（`ZonedDateTime.now(RESET_ZONE)`）。
- 因此“4 点刷新”与 `World` 时间**解耦**：`World` 时间只影响委托的“游戏内玩法进度”（如击杀、昼夜），不参与“哪一天派发”的判定。

### 4.3 命令刷新与刷新的关系

- `/refreshdailyquest [@UID]` → `resetDailyTasks(true)`：**强制**重抽，无需等待 `hasResetPassed()`，同时清空进度、`finishedDailyTaskCount` 归零、`isScoreRewardTaken=false`、`lastCommissionResetDayKey` 置为当前日键。这保证“刷新后进度清零、当日计数重置”，且不会因命令刷新后再次被 `onTick` 重复刷新（日键已同步为当前值）。

---

## 5. 奖励设计

### 5.1 奖励来源

- 数据驱动（完整版，推荐）：`DailyTaskData.taskRewardId` → `DailyTaskRewardData.dropVec[].dropId` → 走现有 `DropSystem`（`emu.grasscutter.game.drop.DropSystem`）掉落；等级档位用 `DailyTaskLevelData.scoreDropId`。
- 硬编码兜底（MVP，便于先跑通闭环）：直接 `addItem` 虚拟货币/阅历，数值可配置。

### 5.2 单个委托奖励（建议数值，可调）

| 物品 | itemId | 建议数值 | 说明 |
|---|---|---|---|
| 原石 | 201 | 10 | `setPrimogems(+10)`，虚拟物品 |
| 摩拉 | 202 | 3000（随世界等级放大） | `setMora(+n)` |
| 冒险阅历 | 102 | 100 | `addExpDirectly(100)` |
| 好感阅历（陪伴阅历） | 105 | 10 | 自动分摊给出战队伍（`upgradeAvatarFetterLevel`） |

- 发放方式：`player.getInventory().addItem(itemId, count, ActionReason.DailyTaskScore)`——`Inventory.addItem` 内部对 201/202/102/105 走 `addVirtualItem` 分支（原石→`setPrimogems`、摩拉→`setMora`、冒险阅历→`addExpDirectly`、好感阅历→`upgradeAvatarFetterLevel`），无需手动分别调用。

### 5.3 全清 4 个奖励

- 触发条件：`finishedDailyTaskCount == 4` 且 `!isScoreRewardTaken`。
- 建议：原石 20 + 摩拉 5000 + 冒险阅历 150 + 好感阅历 20 +（可选）传说钥匙 1（itemId 107，`ActionReason.DailyTaskExchangeLegendaryKey(72)`）。
- 领取方式（二选一，写清决策）：(a) 第 4 个完成时**自动发放**（无头路线最省事，客户端无需二次确认）；(b) 客户端面板“领取”按钮 → `GetDailyTaskRewardReq` → `claimScoreReward()`（路线 A 完整版）。MVP 用 (a)，路线 A 切 (b) 时把自动发放改为置“可领取”状态。

### 5.4 用的 ActionReason

- 单个/全清货币与阅历：`ActionReason.DailyTaskScore(26)`。
- 传说钥匙：`ActionReason.DailyTaskExchangeLegendaryKey(72)`。
- （若将来做多人委托主/客机：`DailyTaskHost(27)` / `DailyTaskGuest(33)` 已存在，可留作扩展。）

### 5.5 完成触发点（保持 GameQuest.finish() hook 或等价）

- 委托**不是** Quest，故主路径不挂 `GameQuest.finish()`。
- 现有可复用触发链：
  1. `GameQuest.finish()` 末尾已调用 `getActivityManager().triggerWatcher(WatcherTriggerType.TRIGGER_FINISH_QUEST_AND, subQuestId)`（`GameQuest.java` 第 248-251 行）——若把某个委托包装成“隐藏 quest”驱动，可复用此 hook；
  2. 更直接：`WatcherTriggerType.TRIGGER_DAILY_TASK(301)`、`TRIGGER_MONSTER_DIE(109)`、`TRIGGER_KILL_GROUP_MONSTER(422)`、`TRIGGER_KILL_MONSTER_IN_AREA(335)` 已存在，可在 `ActivityWatcher` 体系里新增一个 `DailyTaskWatcher`（`@ActivityWatcherType(WatcherTriggerType.TRIGGER_DAILY_TASK)`），或在怪物死亡处（现有 `MonsterDieWatcher` 等）转发进度到 `DailyCommissionManager.onTaskProgress(...)`。
- **结论**：MVP 用“登录/定时自动完成”或“怪物击杀计数（TRIGGER_MONSTER_DIE → onTaskProgress）”；完整版用“加载委托场景组 + `finishType` 精确追踪（DAILY_FINISH_MONSTER_NUM → 击杀数）”。奖励与全清逻辑集中在 `DailyCommissionManager`，与“命令刷新后进度清零/计数重置”天然一致（`resetDailyTasks` 一并清）。

---

## 6. 边界情况

1. **跨天/跨 04:00**：`onTick`/`onLogin` 每次都用 `currentCommissionDayKey() != lastCommissionResetDayKey` 判断，跨过 04:00 自动重抽并清空昨日进度；旧进度**不结转**、昨日未领奖励**作废**（与官服一致）。
2. **断线重登**：`onLogin` 先判刷新再下发；未跨天则保留 `activeTaskIds/progress/count`，只重新推送面板（路线 A）或提示（无头）。
3. **任务失败**：委托无“失败”态（`finishType` 不产生 fail）；若加载场景组失败/怪物组缺失，`completeTask` 仍可按“进度达标即完成”兜底，避免卡死。`resetDailyTasks` 对模板缺失做跳过（见下）。
4. **模板缺失**：`GameData.getDailyTaskDataMap().get(taskId)` 为 null 时，`resetDailyTasks` 过滤掉该 id 并换抽；`grantTaskReward` 对 `taskRewardId`/`DailyTaskRewardData` 缺失时走硬编码兜底数值，绝不 NPE。
5. **与现有 activity watcher 的相互作用**：`GameQuest.finish()` 已触发的 `TRIGGER_FINISH_QUEST_AND` 等 watcher 与委托无关，保持不动；新增的 `DailyTaskWatcher` 用独立 trigger（`TRIGGER_DAILY_TASK`），避免污染纪行/活动进度。注意 `resetDailyTasks(true)` 只清委托自身字段，不动 `Player.lastDailyReset`、纪行、树脂等。
6. **命令刷新后的计数一致性**：`resetDailyTasks(true)` 一次性清 `taskProgress/finishedDailyTaskCount/isScoreRewardTaken` 并重抽，杜绝“刷新后旧计数残留导致二次发奖”。
7. **并发/重入**：`completeTask` 幂等（完成后从活跃集合移除或标记），`grantTaskReward` 每次只发一次；`resetDailyTasks` 加 `synchronized`（参考 `Player.doDailyReset()` 的 `private synchronized`）。
8. **时区/夏令时**：固定 `ZoneId.of("Asia/Shanghai")`（UTC+8 无夏令时），结果稳定，不随服务器时区漂移。

---

## 7. 测试计划

> 服务器当前正在运行（端口 8088/22101），测试流程只描述、**不执行**构建与重启；实施者自行在测试环境操作。

### 7.1 本地构建步骤

1. 源码改动就绪后，在 `E:\YSPrivateServer\Server\LunaGC` 执行：
   - Windows：`.\gradlew.bat jar`（或项目自带的 `gradlew-jar.bat`）。
   - 产物：`LunaGC-6.6.0.jar`（`build.gradle`：`archiveBaseName = 'LunaGC'`、`version = '6.6.0'`、主类 `emu.grasscutter.Grasscutter`；根目录已存在同名 jar）。
2. 编译期 Java：`build.gradle` 为 `source/targetCompatibility = VERSION_17`（`.sdkmanrc` 为 `java=17.0.0-tem`）；运行期用 Java 21（按项目要求）。若 `lib/` 依赖未变，无需额外拉取。
3. 替换运行中的 jar 前先停服（**不要**在当前运行实例上热替换），重新启动后验证。

### 7.2 验证步骤（客户端连 `http://127.0.0.1:8088`）

1. **资源加载**：启动日志确认无 `DailyTaskData` 加载报错；`/give` 或日志确认 `GameData.getDailyTaskDataMap().size()` > 0（可用临时日志或命令打印）。
2. **派发**：新号/触发 04:00 或执行 `/refreshdailyquest`，日志打印 4 个 taskId，且 `cityId`/等级档位过滤符合预期。
3. **完成与发奖**：
   - 无头 MVP：触发完成（登录/定时/击杀）→ 观察原石/摩拉/阅历变化（`/give` 前记录数值比对）。
   - 校验 `ActionReason`：奖励提示（`PacketItemAddHintNotify`）来源显示为委托奖励。
4. **全清奖励**：完成 4 个 → 观察全清额外奖励只发一次；重复触发不重复发。
5. **命令**：`/refreshdailyquest`（对自己）与 `/refreshdailyquest @<某UID>`（对他人，需权限）→ 进度清空、计数归零、重抽 4 个；连续两次命令不叠加奖励。
6. **跨 04:00**：改服务器/测试机 UTC+8 时间到 03:59 与 04:01 各一次（或临时把日键函数注入假时间），确认 04:00 整点翻转、旧进度清空。
7. **路线 A 面板**（协议就绪后）：登录后客户端委托面板显示 4 个委托；完成/领取后 `DailyTaskProgressNotify`/`DailyTaskRewardNotify` 正确刷新 UI。

---

## 8. 分阶段实施步骤（最小可用 → 完整）

### 阶段 0：资源层（先打通数据）
- 新增 `DailyTaskData` / `DailyTaskLevelData` / `DailyTaskRewardData` 三个资源类 + `GameData` 三个 map 字段。
- **验收**：启动无报错，日志/命令可见三份数据数量正确（`id` 大写 `ID` 字段能正确反序列化）。

### 阶段 1：管理器骨架 + 无头发奖闭环（MVP）
- 新增 `DailyCommissionManager`；`Player` 挂字段 + `onLogin`/`onTick` 调用；`resetDailyTasks`（随机 4 个 + 硬编码奖励）；`completeTask`（登录/定时自动完成）+ `grantTaskReward` + `claimScoreReward`。
- 新增 `/refreshdailyquest` 命令。
- **验收**：每日 04:00（UTC+8）或命令触发后，玩家拿到 4 个委托对应的原石/摩拉/阅历；全清额外奖励只发一次；命令刷新后进度与计数正确清零。

### 阶段 2：真实完成触发（用现有触发链）
- 用 `WatcherTriggerType.TRIGGER_DAILY_TASK(301)` 或 `TRIGGER_MONSTER_DIE(109)` / `TRIGGER_KILL_GROUP_MONSTER(422)` 把击杀/交互进度接入 `onTaskProgress`，替换“登录自动完成”。
- **验收**：击杀/交互达到 `finishProgress` 时自动完成并发奖；未达标不发放。

### 阶段 3：场景组驱动（接近官服玩法，可选高价值）
- 按 `DailyTaskData.oldGroupVec/newGroupVec` 在对应 `Scene` 加载委托场景组（参考现有 `SceneGroup`/触发器加载机制），按 `finishType` 精确追踪。
- **验收**：客户端世界里能看到委托目标点/怪群，完成后回凯瑟琳领奖的完整链路（受场景组加载能力制约，此阶段工作量最大）。

### 阶段 4：协议层 / 客户端可见面板（路线 A，依赖外部协议）
- 抓包/取得 6.7 DailyTask proto 与 opcode → 补生成类 + opcode + handler + packet → 接上 `sendInfoNotify`/`claimScoreReward` 的客户端回包。
- **验收**：客户端委托面板完整显示、进度实时刷新、领奖弹窗正确。
- **回退**：若长期无法取得协议，停留在阶段 1~3 的无头形态（功能等价，仅无面板）。

---

## 附：关键源码依据（路径/行号，便于复核）

- 协议 handler 注册：`src/main/java/emu/grasscutter/server/game/GameServerPacketHandler.java`（`registerHandlers` 反射 + `@Opcodes`）；`src/main/java/emu/grasscutter/net/packet/PacketOpcodes.java`（opcode 常量，无 DailyTask 条目）；`src/main/java/emu/grasscutter/net/packet/Opcodes.java`（注解）。
- 字段号混淆实证：`src/generated/main/java/emu/grasscutter/net/proto/PersonalLineAllDataRspOuterClass.java`（注释 `=11` vs `writeUInt32(12, ...)`）。
- 每日刷新现状：`src/main/java/emu/grasscutter/game/player/Player.java`（`doDailyReset` 约 1258 行；`onTick` 约 1230 行；`onLogin` 约 1351 行）。
- 任务系统：`src/main/java/emu/grasscutter/game/quest/GameQuest.java`（`start/finish/fail`）、`QuestManager.java`（`addQuest/queueEvent`）、`data/excels/quest/QuestData.java`、`data/binout/MainQuestData.java`（`QuestType`）。
- 资源加载：`src/main/java/emu/grasscutter/data/ResourceLoader.java`（`@ResourceType` 反射发现 + `getMapByResourceDef`）、`data/GameData.java`。
- 奖励：`src/main/java/emu/grasscutter/game/inventory/Inventory.java`（`addItem`/`addVirtualItem`：201 原石、202 摩拉、102 冒险阅历、105 好感阅历、107 传说钥匙）、`game/props/ActionReason.java`（`DailyTaskScore(26)`、`DailyTaskExchangeLegendaryKey(72)`）。
- watcher 触发：`src/main/java/emu/grasscutter/game/props/WatcherTriggerType.java`（`TRIGGER_DAILY_TASK(301)`、`TRIGGER_MONSTER_DIE(109)`、`TRIGGER_KILL_GROUP_MONSTER(422)`、`TRIGGER_FINISH_QUEST_AND(700)`）。
- 命令：`src/main/java/emu/grasscutter/command/CommandHandler.java`（`execute(Player, Player, List<String>)`）、`CommandMap.java`（`getTargetPlayer` 自动解析 `@UID`）、`Command.java`（注解）。
- 构建：`build.gradle`（`archiveBaseName='LunaGC'`、`version='6.6.0'`、主类 `emu.grasscutter.Grasscutter`）。
- 上游：`E:\YSPrivateServer\Server\tools\lunagc-fork` 无 `game/daily_tasks/`（与本仓库同为 6.7 基座，无参考实现；主线上游 Grasscutter 有 `game/dailytask/DailyTaskManager` + `DailyTaskData` + 一批 `DailyTask*` proto，需自行对照，字段号不能照抄）。
