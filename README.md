# Voidkit Inspector

Voidkit 套件的**操作审计**模组 —— CoreProtect 风格的方块 / 容器 / 实体操作记录，
专为**科技整合包**（机械动力 Create / Mekanism / AE2）设计。

> 从 Voidkit 套件独立发布，但仍依赖 `voidkit_core` 作为前置模组。

## 为什么是它

普通查插件在科技包里会翻车：一条自动化产线刷几百万圆石、产几万铁锭，全记进数据库，
结果真正的破坏行为被淹没在海量噪音里，查不出来。Inspector 从架构上解决了这个问题。

## 核心特性

| 能力 | 说明 |
|---|---|
| **多线程写入** | 主线程只做 O(1) 入队，唯一写线程批量事务落库（WAL），队列满自动丢弃，**绝不卡 TPS** |
| **多线程查询** | 独立查询线程池 + 各自读连接，查询与写入并行、不占主线程、不卡磁盘 |
| **嵌入式 SQLite** | 零配置单文件数据库，方块/物品/维度/玩家名全字典化，行小而密 |
| **复合索引 + keyset 分页** | 几十万条记录翻页毫秒级，不会像老插件那样查一次遍历整表 |
| **完整 NBT** | 模组方块实体、带组件的物品都存全量 NBT |
| **箱子 GUI 查询** | 结果以物品图标直观展示，悬停看「谁 / 何时 / 动作 / 坐标」，翻页不刷屏 |

## 科技包友好（核心设计）

科技整合包里绝大多数方块/容器变化是**自动化产线**干的，不是玩家。Inspector 的灵魂是
**来源分类**——先把「是谁干的」认出来，再决定记不记：

- 机械臂 / 管道 / 假玩家驱动的操作 → **自动识别，默认不记**
- 自动化来源名取方块实体命名空间，日志里能看出 `#create` / `#mekanism` / `#ae2`
- 树叶腐烂 / 流体 → 默认不记；爆炸 / 苦力怕 / 末影人 → 该记的照记
- 结果：一条 Create 产线刷几百万圆石**进不了库**，那一下真正的破坏行为才查得出来

## 快速开始

前置：`voidkit_core`（同版本，含 `org.voidkit.core.api` 契约与 `core.platform` 适配层）。

```bash
./fetch-core.sh          # 从 voidkit Release 下载 core jar 到 libs/
./build.sh build         # 产出 build/libs/voidkit_inspector-<版本>.jar
```

服务端部署：把 `voidkit_inspector` 和 `voidkit_core` 两个 jar 一起放进 `mods/`。

## 命令

| 命令 | 说明 |
|---|---|
| `/inspect`（别名 `/coi` `/ins`） | 切换检查模式，点击方块查看历史 |
| `/inspect lookup u:玩家 r:半径 t:1h a:place b:石头` | 参数查询，结果弹箱子 GUI |
| `/inspect lookup ... --text` | 退回文字输出（方便控制台 / 复制） |
| `/inspect here` | 查看准星对准方块的历史 |
| `/inspect page` | 翻页 |
| `/inspect status` | 数据库状态 |
| `/inspect purge <天>` | 清理 N 天前的记录 |

查询参数：`u:` 玩家、`r:` 半径、`t:` 时间（`1h`/`1d`）、`a:` 动作（`place`/`break`/`open`/`kill`）、
`b:` 方块/物品 id。

## 权限

命令分**查询**与**改数据**两类，节点分开 —— 给版主「查」的权限，不等于让他能回滚世界或清库。

| 节点 | 覆盖的命令 | 说明 |
|---|---|---|
| `voidkit.inspector` | `/inspect` `/inspect on\|off` `/inspect here` `/inspect lookup` `/inspect page` `/inspect chain` `/inspect status` | **只读**：查看历史 |
| `voidkit.inspector.rollback` | `/inspect preview` `/inspect rollback` | 会**改世界**（回滚） |
| `voidkit.inspector.purge` | `/inspect purge <天>` | 会**删历史数据** |

- 三个命令名等价：`/inspect`（别名 `/coi` `/ins`）。
- 没装 `voidkit_perm` 时按原版 OP 判定（**只有 OP 能用**，普通玩家一律拒绝）。
- ⚠️ **升级注意**：本次之前所有子命令都只由 `voidkit.inspector` 把守。
  如果你的服依赖「有 `voidkit.inspector` 就能回滚」，升级后需要额外授予
  `voidkit.inspector.rollback` / `voidkit.inspector.purge`。
  这是刻意的：**查看历史**与**改写世界**不该是同一个权限。

## 架构

插件只依赖 core 的 `org.voidkit.core.api` 契约，并通过 `core.platform` 适配层
跨 NeoForge / Forge / Fabric 移植。详见 [Voidkit 套件](https://github.com/KokuuWork/voidkit)。

## License

MIT
