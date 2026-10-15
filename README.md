# Voidkit Inspector —— 操作审计

Voidkit 套件的**操作审计**模组 —— CoreProtect 风格的方块 / 容器 / 实体操作记录，
专为**科技整合包**（机械动力 Create / Mekanism / AE2）设计。

<div align="center">

## 🛠 Bug 维修群

> **项目还在测试版（beta），遇到任何问题都欢迎来群里找我们。**

### **一键加群** → [`498700382`](https://qm.qq.com/q/498700382)

[![QQ群](https://img.shields.io/badge/QQ%E7%BE%A4%20%C2%B7%20Bug%20%E7%BB%B4%E4%BF%AE%E7%BE%A4-4f46e5?style=for-the-badge&logo=tencent-qq&label=%E5%8A%A0%E7%BE%A4)](https://qm.qq.com/q/498700382)

**这是 Voidkit 的 Bug 维修群。**
装不上、启动崩、模块不生效、配置改了不生效、和别的 mod 冲突 —— 都来群里说。
报 bug 请附上：**版本线**（如 1.21.1 Forge）、**服务端日志**、**相关配置**。

</div>

---


> 从 Voidkit 套件独立发布，但仍依赖 `voidkit_core` 作为前置模组。
> 纯服务端模组，无客户端 jar。

## 下载

**三条版本线挂在同一个 release 下，按文件名后缀区分** —— 下载前先看清自己的环境：

| Minecraft | 加载器 | 文件 |
|---|---|---|
| 1.21.1 | NeoForge 21.1.x | `voidkit_inspector-0.1.0-beta.1+mc1.21.1-neoforge.jar` |
| 1.21.1 | Forge 52.1.x | `voidkit_inspector-0.1.0-beta.1+mc1.21.1-forge.jar` |
| 1.20.1 | Forge 47.4.x | `voidkit_inspector-0.1.0-beta.1+mc1.20.1-forge.jar` |

→ [`KokuuWork/voidkit-inspector` Releases](https://github.com/KokuuWork/voidkit-inspector/releases)

> ⚠️ **必须同时安装 `voidkit_core`，且 core 的版本线要与本 jar 一致。**
> 缺 core 时 mod 会被加载器**静默跳过** —— 服务端照常启动，只是没有审计，
> 非常难发现。core 从 `KokuuWork/voidkit` 的 release 下载对应的那一个
> （NeoForge 版与 Forge 版的 jar 内含不同加载器 API，不能互相顶替）。

## 为什么是它

普通查插件在科技包里会翻车：一条自动化产线刷几百万圆石、产几万铁锭，全记进数据库，
结果真正的破坏行为被淹没在海量噪音里，查不出来。Inspector 从架构上解决了两件事：

**① 记录面：来源分类（该记的记）**
先把「是谁干的」认出来，再决定记不记 —— 机械臂 / 管道 / 假玩家驱动的操作自动识别、默认不记，
一条 Create 产线刷几百万圆石**进不了库**，那一下真正的破坏行为才查得出来。

**② 恢复面：智能回滚五层（不只是「查」，还能「退回去」）**
无脑回滚在科技包里必然翻车：补回一个 Create 传动轴，应力网络照样断；补回 AE2 线缆，
ME 网络照样瘫。所以回滚做了五层设计（见下文），核心原则是
**宁可不回滚，也不留下半扇门 / 半条产线**。

## 支持矩阵与分支对应

| Minecraft | 加载器 | 分支 | jar |
|---|---|---|---|
| 1.21.1 | NeoForge 21.1.x | `main` | `voidkit_inspector-<版本>+mc1.21.1-neoforge.jar` |
| 1.21.1 | Forge 52.1.x | `mc/1.21.1-forge` | `voidkit_inspector-<版本>+mc1.21.1-forge.jar` |
| 1.20.1 | Forge 47.4.x | `mc/1.20.1` | `voidkit_inspector-<版本>+mc1.20.1-forge.jar` |

- **本分支 `main` 对应 1.21.1 + NeoForge**；其它版本线见上表。
- 所有版本线的 jar 挂在**同一个 release** 下，按文件名后缀区分，勿混用。
- Inspector jar 必须与 `voidkit_core` **同版本线**（`mods.toml` 有强制依赖检查）。

---

## 1. 记录面：来源分类（科技包友好的核心）

科技整合包里绝大多数方块 / 容器变化是**自动化产线**干的，不是玩家。Inspector 的灵魂是
**来源闸门** —— 三类来源，各有各的默认处置：

| 来源类别 | 判定方式 | 默认 |
|---|---|---|
| `PLAYER` 真人玩家 | 有网络连接的真 `ServerPlayer` | **记** |
| `AUTOMATION` 自动化 | 官方 `FakePlayer` 类、或 `ServerPlayer` 但无网络连接（模组自造假玩家） | **不记** |
| `NATURAL` 自然 | 树叶腐烂 / 流体 / 重力方块 / 生物 | **不记** |
| `OTHER` 无法归类 | 兜底 | 不记 |

> ⚠️ **不能用 `connection == null` 当唯一判据**：官方 `FakePlayer` 自带一个
> `FakeConnection`，connection 不是 null，必须先判 `Platform.isFakePlayer` 拦掉。
> 判定已收口到 core 的 `platform` 层 —— NeoForge / Forge 有官方类，Fabric 没有。

**强制记录名单**（`alwaysLogSources`，`#` 前缀）：即便来源类别被关也照记 ——
苦力怕爆炸、TNT、火蔓延、岩浆流动、末影人搬方块这些「没有真人玩家但要追责」的事件。

自动化来源名取方块实体命名空间，日志里能直接看出 `#create` / `#mekanism` / `#ae2`。

### 记什么 / 不记什么

| 事件 | 默认 | 动作码 |
|---|---|---|
| 玩家破坏方块（含方块实体 NBT） | 记 | `BREAK` |
| 玩家放置方块 | 记 | `PLACE` |
| 玩家点火 / 放岩浆 | 记 | `PLACE_FIRE` |
| 容器拿取 / 放入（开合快照 diff） | 记 | `CONTAINER_TAKE` / `CONTAINER_PUT` |
| 玩家击杀实体 | 记 | `KILL` |
| 苦力怕 / TNT / 凋灵 / 恶魂爆炸 | 记（走强制名单） | `BREAK` |
| 末影人搬方块 | 记（走强制名单） | `PLACE` |
| 整链回滚执行 | 记（审计回滚本身） | `ROLLBACK` |
| 玩家丢出 / 捡起物品 | **不记**（太吵） | `DROP` / `PICKUP` |
| 自动化产线的一切操作 | **不记** | — |
| 树叶腐烂 / 流体 | **不记** | — |

### 性能（主线程零压力）

| 机制 | 做法 |
|---|---|
| 写入 | 主线程只做 O(1) 入队；唯一写线程批量事务落库（WAL）；**队列满自动丢弃，绝不卡 TPS** |
| 查询 | 独立查询线程池 + 各自读连接，与写入并行，不占主线程、不卡磁盘 |
| 雪崩闸门 | 单 tick 入队上限 `maxEventsPerTick`（2000）+ 单来源每秒上限 `maxPerSourcePerSecond`（500） |
| 存储 | 嵌入式 SQLite 零配置单文件；方块 / 物品 / 维度 / 玩家名全字典化，行小而密 |
| 分页 | 复合索引 + **keyset 分页**，几十万条翻页毫秒级（不像老插件查一次遍历整表） |
| 完整 NBT | 模组方块实体、带组件的物品都存全量 NBT |

---

## 2. 恢复面：智能回滚五层

> 对应 voidkit issue #8。这是 Inspector 从「查插件」变成「能退回去的关键」。

### 第 1 层 · 分类感知（`RollbackPolicy`）

无脑回滚必然翻车，所以先把每条记录**分类**，给不同类别不同策略。
判定依据完全来自审计记录本身（不依赖世界状态）：

| 类别 | 判据 | 默认处置 |
|---|---|---|
| `NORMAL` 普通方块 | 无方块实体 NBT，且命名空间不在机器名单 | 直接还原 |
| `RESTORE_NBT` 容器 / 含数据方块 | 带 NBT 且 NBT 的 `id` 是 `minecraft:` 命名空间 | 还原方块 + 恢复 NBT |
| `MACHINE` 机器 / 装置 | 带 NBT 但命名空间是模组的（Create / Mek / AE2…），或无 NBT 但命名空间在机器名单（线缆 / 传动件 / 管道） | **默认跳过**，`--include-machines` 才动 |

补回一个 Create 传动轴，应力网络照样断 —— 所以机器默认不碰。

### 第 2 层 · 结构感知（`StructureSense`）

对「属于更大结构」的方块，**要么整结构一起回滚，要么整结构跳过，绝不回滚一半**：

- **双格方块成对**：门（`half=upper/lower`）、床（`part=head/foot`）占两格。
  两半的记录都在同一批里才回滚；**缺另一半的保守跳过** —— 宁可不回滚，
  也不留下半扇门 / 半张床。
- **模组机器连通体**：被跳过的机器 / 线缆按 6 向邻接聚类成连通体（同命名空间或同方块实体
  类型才算连通），预览按连通体聚合输出（例如「create 连通体 ×3（共 17 格，已整体跳过）」），
  让服主看清整结构规模而不是逐格噪音。`--include-machines` 时整连通体一起回滚。

聚类 / 配对都是**纯函数**，不碰世界状态、不碰主线程，可直接 JUnit 测试。

### 第 3 层 · 因果链聚类（`ChainCluster`）

违规破坏往往不是一次动作，而是一条链：拆箱子 → 拿东西 → 放 TNT → 炸。
单条记录只能回答「谁动了这个方块」，回答不了「这次破坏的全貌」。

聚类规则（同一玩家）：

- **时间断链**：相邻两次操作间隔超过 `chainIdleMinutes`（默认 5 分钟）
- **空间断链**：新事件与链上最近事件距离超过 `chainRadiusBlocks`（默认 32 格）
- **维度断链**：换维度必然是新链（跨维度谈距离没有意义）

每条链带 `chain:<玩家>:<链id>` 编号，可一键回滚整条链。

### 第 4 层 · 预览 / 分帧执行（`RollbackJob`）

`preview` 与 `rollback` **用同一批数据**（都受 `maxRollbackEvents` / `maxRollbackRadius` 约束），
预览有效期 `rollbackPreviewTtlSeconds`（默认 5 分钟），超时必须重新预览 —— 防「预览小的、执行大的」。
执行按 `rollbackBlocksPerSecond`（默认 2000）分帧推进，永不卡主线程。

### 第 5 层 · 回滚撤销（`UndoJournal` / `UndoJob`）

回滚把世界从 A 改成 B，要「撤销」就得知道 A 长什么样 —— 而审计库存的是「玩家干了什么」，
不是「回滚那一刻世界什么样」，且两次操作之间可能隔着一次重启。

所以每次回滚执行时，把每个目标的**回滚前原始状态**落进 SQLite 的 `rollback_journal` 表，
跨重启可用。约束：

- 每个回滚**只能撤销一次**（journal 侧 `UPDATE … WHERE undone=0` 原子抢占）
- 只允许撤销 `rollbackUndoWindowMinutes`（默认 30 分钟）内执行的回滚
  —— 间隔越久，回滚后世界上的新改动越多，撤销越容易误伤合法改动
- 撤销本身也进审计（`UNDO` 来源 + `ROLLBACK` 动作码），谁撤的、撤了多少，一清二楚

---

## 3. 命令

命令前缀 `/inspect`，别名 `/coi`、`/ins`。权限节点统一 `voidkit.inspector`。

| 命令 | 说明 |
|---|---|
| `/inspect` | 切换检查模式（左键 / 右键点击方块查看历史，再输一次关闭） |
| `/inspect on` / `off` | 显式开关检查模式 |
| `/inspect here` | 查看准星对准方块的历史 |
| `/inspect lookup u:玩家 r:半径 t:1h a:place b:石头` | 参数查询，结果弹箱子 GUI |
| `/inspect lookup ... --text` | 退回文字输出（方便控制台 / 复制） |
| `/inspect page` | 翻页（keyset 分页） |
| `/inspect status` | 数据库状态（队列深度 / 已写 / 丢弃 / 待审） |
| `/inspect purge <天>` | 清理 N 天前的记录（分批删，不长时间锁库） |
| **`/inspect preview <filters>`** | **回滚预览**：按分类 / 结构 / 连通体聚合输出，不改世界 |
| **`/inspect rollback <filters>`** | **执行回滚**（需先 preview 且未过期） |
| **`/inspect rollback --include-machines`** | 连模组机器一起回滚（默认跳过） |
| **`/inspect rollback --undo [jobId]`** | **撤销最近一次回滚**（缺省 = 最近一次） |
| **`/inspect chain <玩家> [filters]`** | **列出该玩家的操作链** |
| **`/inspect rollback chain:<玩家>:<链id>`** | **整链回滚** |

查询参数：`u:` 玩家、`r:` 半径、`t:` 时间（`1h`/`1d`）、`a:` 动作（`place`/`break`/`open`/`kill`）、
`b:` 方块/物品 id。

## 3.5 权限

命令分**查询**与**改数据**两类，节点分开 —— 给版主「查」的权限，不等于让他能回滚世界或清库。

| 节点 | 覆盖的命令 | 说明 |
|---|---|---|
| `voidkit.inspector` | `/inspect` `/inspect on\|off` `/inspect here` `/inspect lookup` `/inspect page` `/inspect chain` `/inspect status` | **只读**：查看历史 |
| `voidkit.inspector.rollback` | `/inspect preview` `/inspect rollback` | 会**改世界**（回滚） |
| `voidkit.inspector.purge` | `/inspect purge <天>` | 会**删历史数据** |

- 三个命令名等价：`/inspect`（别名 `/coi` `/ins`），别名**逐条容错**——整合包里撞名只跳过这一个，不连累另外两个。
- ⚠️ **升级注意**：`voidkit.inspector.rollback` / `voidkit.inspector.purge` 是新增节点。
  本次之前所有子命令都只由 `voidkit.inspector` 把守，如果你的服依赖
  「有 `voidkit.inspector` 就能回滚」，升级后需要额外授予这两个。
  这是刻意的：**查看历史**与**改写世界**不该是同一个权限。
- 没装 `voidkit_perm` 时按原版 OP 判定（**只有 OP 能用**）。审计权限敏感，
  **刻意不并入 `voidkit.admin`** —— 应该单独授予，而不是「给了管理总权限就顺带拿到审计与回滚」。

## 架构

四层，换加载器只碰 `platform/`：

```
org/voidkit/inspector/
├── InspectorFeature.kt     业务主体：判定 + 落库 + 命令 + API（零加载器 import）
├── Source.kt               来源分类，经 core 的 Platforms.current 判自动化
├── InspectorDb.kt          SQLite：队列 / 批量写 / 保留期清理
├── RollbackJob.kt          回滚执行器（分帧 + 预览复用）
├── RollbackPolicy.kt       第 1 层：分类感知
├── StructureSense.kt       第 2 层：结构感知（双格 + 机器连通体）
├── ChainCluster.kt         第 3 层：因果链聚类
├── UndoJournal.kt          第 5 层：回滚快照与撤销
├── AuditMenu.kt            检查模式 GUI
└── platform/
    └── neoforge/           ← 唯一 import 加载器 API 的地方
        ├── InspectorEvents.kt        12 个事件适配，只解包转调
        └── VoidkitInspectorNeoForge.kt  @Mod 入口
```

`Source.kt` 不用自己造工厂 —— 它直接复用 core 的 `Platforms.current.isFakePlayer`
/ `isUnconnectedPlayer`，因为 core 已经把加载器差异收口了。

---

## 4. API（经 `voidkit_core` 的 `ApiRouter` 暴露，给管理面板）

| 端点 | 说明 |
|---|---|
| `GET /api/inspector` | 状态：各记录开关、保留天数、队列深度、已写 / 丢弃 / 待审、检查模式人数 |
| `GET /api/inspector/lookup?world=&x=&y=&z=&r=&actor=&action=&material=&limit=` | 面板查询，返回 `nextCursor` + 行数组（含 `hasNbt` 标记） |

---

## 5. 配置

落 `voidkit/inspector/config.json`（JSONC，带完整中文注释）。核心几项：

| 配置 | 默认 | 说明 |
|---|---|---|
| `databaseFile` | `inspector/inspector.db` | SQLite 文件路径，相对 `voidkit/` 根目录 |
| `retentionDays` | 30 | 记录保留天数，0 = 永久 |
| `logPlayer` | `true` | 记录真人玩家 |
| `logAutomation` | **`false`** | 记录自动化来源 —— 科技包数据量头号来源，默认关 |
| `logNatural` | **`false`** | 记录自然来源 —— 几乎全是噪音 |
| `alwaysLogSources` | `#creeper #tnt #fire #lava #enderman #wither #ghast #lightning` | 强制记录名单（关类别也照记） |
| `logItemDropPickup` | `false` | 记录丢 / 捡物品 —— 太吵 |
| `blockBlacklist` / `itemBlacklist` / `worldBlacklist` | 空 | 黑名单，即便真人操作也不记 |
| `maxEventsPerTick` | 2000 | 单 tick 入队上限，防雪崩闸门 |
| `maxPerSourcePerSecond` | 500 | 单来源每秒上限，兜底防「模组冒充真人」刷爆库 |
| `queueCapacity` | 100000 | 写队列容量，满了丢弃而非阻塞主线程 |
| `queryThreads` | 2 | 查询线程池大小 |
| `rollbackEnabled` | `true` | 智能回滚总开关 |
| `maxRollbackEvents` / `maxRollbackRadius` | 10000 / 50 | 单次回滚的事件数 / 半径上限，防手滑 |
| `rollbackMachineNamespaces` | `create mekanism ae2 appliedenergistics2 immersiveengineering thermal` | 机器命名空间名单 |
| `rollbackPreviewTtlSeconds` | 300 | 预览有效期 |
| `rollbackUndoEnabled` | `true` | 回滚撤销总开关 |
| `rollbackUndoWindowMinutes` | 30 | 撤销时间窗 |
| `chainIdleMinutes` / `chainRadiusBlocks` | 5 / 32 | 因果链的时间 / 空间断链阈值 |

---

## 6. 数据位置（`<服务端根>/voidkit/`）

```
inspector/
├── config.json        带中文注释的全部配置
└── inspector.db       审计记录 + 回滚 journal（SQLite WAL）
```

数据只在 `voidkit/` 下，不散落。

---

## 7. 开发

```bash
./fetch-core.sh        # 从 voidkit Release 下载 voidkit_core jar到 libs/
                       # 默认按当前分支自动推断版本线（MC 版本 + 加载器）
./build.sh build       # →build/libs/voidkit_inspector-<版本>+mc<版本>-<加载器>.jar
./build.sh runServer   # 开发服务端
```

三条版本线的构建配置各不相同（**代码逐行相同，差的只有构建与平台层**）：

| 分支 | MC / 加载器 | 构建插件 | Gradle | 发布 jar 来自 | 运行时映射 | JDK |
|---|---|---|---|---|---|---|
| `main` | 1.21.1 NeoForge 21.1.x | ModDevGradle | 8.12.1 | `jar` | 官方映射，不reobf | 21 |
| `mc/1.21.1-forge` | 1.21.1 Forge 52.1.x | ForgeGradle 7 + `jarjar` | 9.3.1 | `jarJar` | 官方映射，不 reobf | 21（配置期还解析 25） |
| `mc/1.20.1` | 1.20.1 Forge 47.4.x | ModDevGradle `legacyforge` | 8.12.1 | **`reobfJar`** | **SRG，要重混淆** | **17** |

- core 前置是 **`compileOnly libs/` 下的 jar**（由 `fetch-core.sh` 下载），
  不是组合构建 —— Inspector 只需要 core 的 12 个 API 符号，不需要源码级联编译。
- 全 Kotlin，注释讲「为什么」；零 mixin；中文注释。
- SQLite 驱动 jarJar 进本 jar（`strictly '[3.46,)'` 只锁下界，让 FML 仲裁）。
  开发运行时 `runServer` 从 classes 加载、jarJar 不生效，所以 `additionalRuntimeClasspath`
  补了 SQLite 原生库与 Kotlin 运行时 —— **发布 jar 里不要重复补**，会和 core 的 jarJar
  撞成两个同名模块，JPMS 直接 `ResolutionException`。
- 测试：`./gradlew test` —— `ChainCluster` / `RollbackPolicy` / `StructureSense`
  都是纯数据计算，不需要起 MC 服务端。

### 跨版本移植时踩过的坑

写新版本线时几乎必然会再遇到这几个，每一个都是**编译过、真机才炸**：

- **1.20.x↔ 1.21.x 的数据模型断层**（不是 API 改名，是模型换了）：

  | 用途 | 1.21.x | 1.20.1 |
  |---|---|---|
  | 物品附加数据 | `DataComponents` / `stack.components` | NBT `stack.tag` |
  | 保存方块实体 | `be.saveWithFullMetadata(registry)` | `be.saveWithFullMetadata()` |
  | 还原方块实体 | `BlockEntity.loadStatic(pos, state, tag, registry)` | `loadStatic(pos, state, tag)` |
  | 从 NBT 还原物品 | `ItemStack.parse(registry, tag).orElse(null)` | `ItemStack.of(tag)` |
  | 同类物品判定 | `isSameItemSameComponents` | `isSameItemSameTags` |
  | GUI 物品名/lore | `stack.set(DataComponents.CUSTOM_NAME, …)` + `ItemLore` | 手写 NBT `display` 子标签 + `Component.Serializer.toJson` |

  改动集中在 `Nbt.kt` / `AuditMenu.kt` / `RollbackJob.kt` / `UndoJob.kt` 四个文件，
  **业务层与平台层都不用动** —— 这就是四层架构的回报。
- **`reobfJar` 的产物目录不是 `build/libs/`？** 恰恰相反：ModDevGradle 下
  `reobfJar` 产 `build/libs/`（发布态，SRG 名），`jar` 产 `build/devlibs/`（开发态，官方名）。
  装错目录 = 装错形态，正式服直接类找不到。
- **`reobfJar` 不继承 `jar` 上设的 `archiveFileName`**，不显式配会退回默认名
  （丢掉版本线信息，发布时三个版本的 jar 名字会撞）。
- **Forge 的 `mods.toml` 每个 `[[dependencies.*]]` 必须显式 `mandatory = true`** ——
  NeoForge 默认填 true，Forge 不填就没有。另外 loader modId 必须是 `forge`，
  且没有 `type = "required"`（那是 NeoForge 的字段）。
- **`@Mod` 类不能用构造器注入**（Forge 52 不支持，只能无参构造器），
  且 **`ServerAboutToStartEvent` 是游戏总线事件**，挂到mod 总线上会抛
  `IllegalArgumentException: … not valid for this bus` —— NeoForge 的 mod 总线
  **不转发**游戏事件，与直觉相反。
- **Forge 52 的服务端日志不打印模组清单**（NeoForge 才打），E2E 判据别照抄 NeoForge 那套。
- **E2E 判据只用ASCII 锚点**（如 `voidkit_inspector\(1\)`）：服务端日志中文编码不固定，
  同版本下 GBK / UTF-8 都实测到，grep 中文会随机假失败。
- **Windows 上起 Forge 服务端要用 `win_args.txt`**，不能选 `unix_args.txt` ——
  后者classpath 用 `:` 分隔，Windows 的 java 认`;`，会在 boot layer 就炸
  `InvalidPathException: Illegal char <:>`，且错误发生在 mod 加载**之前**，极易误判成模组有问题。

### 端到端验证

`tools/e2e-*.sh`各自对应一条版本线，用**真实服务端 + 发布态jar** 跑一遍：

```bash
SERVER_DIR=<已装好的服务端目录> ./tools/e2e-<版本线>-server.sh
```

判据不是「服务端起来了」—— mods.toml 写错时 mod 会被**静默跳过**，`Done` 照样出现。
真正的判据是core 的模块统计里 `voidkit_inspector(1)` + `voidkit/inspector/inspector.db`
的 WAL 有实际写入（证明事件桥真的被调用、jarJar 的 SQLite 原生库可用）。