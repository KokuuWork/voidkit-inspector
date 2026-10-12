package org.voidkit.inspector

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Container
import net.minecraft.world.entity.monster.Creeper
import net.minecraft.world.entity.item.PrimedTnt
import net.minecraft.world.entity.vehicle.MinecartTNT
import net.minecraft.world.entity.boss.wither.WitherBoss
import net.minecraft.world.entity.projectile.Fireball
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Explosion
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.phys.BlockHitResult
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.event.entity.item.ItemTossEvent
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
import net.neoforged.neoforge.event.level.BlockEvent
import net.neoforged.neoforge.event.level.ExplosionEvent
import org.voidkit.core.VoidkitCore
import org.voidkit.core.api.ApiJson
import org.voidkit.core.api.ApiRouter
import org.voidkit.core.api.Feature
import org.voidkit.core.api.VoidkitServer
import org.voidkit.core.api.VoidkitServices
import org.voidkit.core.util.ConfigFiles
import org.voidkit.core.util.Msg
import org.voidkit.core.util.Text
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 操作审计模块。
 *
 * ## 记录什么（「该记的记」）
 *
 * | 事件 | 触发 | 默认 |
 * |---|---|---|
 * | 玩家破坏 / 放置方块 | [BlockEvent.BreakEvent] / [BlockEvent.EntityPlaceEvent] | 记（含方块实体 NBT） |
 * | 玩家容器拿取 / 放入 | [PlayerContainerEvent] 开合快照 diff | 记（含物品 NBT） |
 * | 玩家击杀实体 | [LivingDeathEvent] | 记 |
 * | 苦力怕 / TNT 爆炸 | [ExplosionEvent.Detonate] | 记（`#creeper` `#tnt`） |
 * | 末影人搬方块 | [BlockEvent.EntityPlaceEvent]（非玩家实体） | 记（`#enderman`） |
 * | 玩家丢 / 捡物品 | [ItemTossEvent] / [ItemEntityPickupEvent] | 默认不记（太吵） |
 *
 * ## 不记什么（「不该记的不记」）
 *
 * 靠 [Source] 分类把「机械臂 / 管道 / 假玩家」识别为自动化来源、默认丢弃；树叶腐烂、
 * 流体流动等自然来源也默认丢弃。这是对付机械动力 / Mek / AE2 产线的核心：
 * 一条产线一分钟几万次方块变化，全记进去既拖慢写入，又稀释真正的违规行为记录。
 * 详情见 [InspectorConfig] 的「来源闸门」。
 *
 * ## 查询（「查得快」）
 *
 * - 检查模式：`/inspect` 后左键/右键方块，异步查该方块历史，不卡主线程。
 * - 参数查询：`/inspect lookup u:玩家 r:半径 t:1h a:place`，keyset 分页翻页。
 * - 所有查询走独立线程池 + WAL 多读，游戏主线程只做 O(1) 入队，不影响 TPS。
 */
class InspectorFeature : Feature() {

    override val id = "inspector"
    override val displayName = "操作审计"
    override val description = "方块/容器/实体操作审计：检查模式 + 查询，SQLite 存储"

    /** 数据库句柄。onServerStart 打开，onServerStop 关闭。 */
    private var db: InspectorDb? = null

    /** 处于检查模式的玩家。 */
    private val inspecting = ConcurrentHashMap.newKeySet<UUID>()

    /** 玩家打开容器时的快照，关闭时 diff 出拿取/放入。 */
    private val openContainers = ConcurrentHashMap<UUID, OpenContainer>()

    /** 玩家最近一次查询，供 `/inspect page` 翻页。 */
    private val lookups = ConcurrentHashMap<UUID, LookupState>()

    /** 玩家最近一次回滚预览（preview 与 rollback 用同一批数据，防止预览一套执行另一套）。 */
    private val pendingRollback = ConcurrentHashMap<UUID, PendingRollback>()

    /** 进行中的回滚任务（同一时刻最多一个）。 */
    private var rollbackRun: RollbackRun? = null

    /** 一次回滚预览：预览指纹（用于 rollback 校验）+ 构造好的回滚任务 + 创建时间 + 整链审计信息。 */
    private class PendingRollback(val key: String, val job: RollbackJob, val createdAt: Long, val chainAudit: ChainAudit? = null)

    /** 一次正在执行的回滚：任务 + 发起人（报告发给谁）+ 整链回滚的审计信息（普通回滚为 null）。 */
    private class RollbackRun(val job: RollbackJob, val playerUuid: UUID, val chainAudit: ChainAudit? = null)

    /** 整链回滚完成后写进审计库的标记：位置取链中心，tag 形如 `chain:<玩家>:<链id>`。 */
    private class ChainAudit(val world: String, val x: Int, val y: Int, val z: Int, val tag: String, val eventCount: Int, val executor: String)

    // ---------- 速率限制（主线程单线程访问，无需加锁） ----------

    private var tickWindow = 0L
    private var tickCount = 0
    private var secWindow = 0L
    private val secCount = HashMap<String, Int>()

    private var purgeTick = 0

    // ---------- 生命周期 ----------

    override fun onServerStart(server: MinecraftServer) {
        InspectorStore.load()
        if (db == null) {
            val path = ConfigFiles.resolve(InspectorStore.config.databaseFile)
            val c = InspectorStore.config
            db = InspectorDb(
                path,
                c.queueCapacity,
                c.batchSize,
                c.flushIntervalMs,
                c.queryThreads,
                c.purgeBatchSize,
            ).also { it.open() }
        }
        purgeTick = 0
    }

    override fun onServerStop(server: MinecraftServer) {
        db?.close()
        db = null
        inspecting.clear()
        openContainers.clear()
        lookups.clear()
        pendingRollback.clear()
        rollbackRun = null
    }

    override fun onReload(server: MinecraftServer) {
        InspectorStore.load()
        // 保留天数变了就立刻重排一次清理
        schedulePurge()
    }

    override fun onSecond(server: MinecraftServer) {
        // 回滚分帧执行：即使模块被临时关掉，跑了一半的回滚也要收尾，不能留半截世界
        tickRollback(server)
        if (!enabled) return
        // 每 6 小时清理一次过期记录
        if (++purgeTick >= 6 * 3600) {
            purgeTick = 0
            schedulePurge()
        }
    }

    /** 回滚任务每秒推进一批，跑完把报告发给发起人。整链回滚完成后额外落一条审计。 */
    private fun tickRollback(server: MinecraftServer) {
        val run = rollbackRun ?: return
        run.job.tick(server)
        val lines = run.job.pollReport() ?: return
        rollbackRun = null
        val player = server.playerList.getPlayer(run.playerUuid)
        if (player != null) {
            for (line in lines) Msg.info(player, line)
        } else {
            // 发起人已下线：报告落服务端日志，不能无声消失
            for (line in lines) VoidkitCore.LOG.info("[审计回滚] {}", line)
        }
        // 整链回滚的审计：回滚本身也是一次世界改动，得能查到「谁在何时回滚了哪条链」。
        // 沿用现有 Source 入库机制，来源标记为 CHAIN；actor 记发起回滚的管理员。
        val audit = run.chainAudit
        if (audit != null) {
            enqueue(
                LogEvent(now(), audit.world, audit.x, audit.y, audit.z, audit.executor, null,
                    SourceKind.CHAIN, Actions.ROLLBACK, audit.tag, audit.eventCount, null)
            )
        }
    }

    private fun schedulePurge() {
        val cfg = InspectorStore.config
        if (cfg.retentionDays <= 0) return
        db?.schedulePurge(System.currentTimeMillis() - cfg.retentionDays * 86_400_000L)
    }

    // ---------- 速率闸门 ----------

    /** 单 tick 预算 + 单来源每秒预算，都过了才放行。 */
    private fun admit(actor: String): Boolean {
        val cfg = InspectorStore.config
        val now = System.currentTimeMillis()

        // 50ms ≈ 1 tick
        val tw = now / 50
        if (tw != tickWindow) {
            tickWindow = tw
            tickCount = 0
        }
        if (tickCount >= cfg.maxEventsPerTick) return false
        tickCount++

        val sw = now / 1000
        if (sw != secWindow) {
            secWindow = sw
            secCount.clear()
        }
        val n = (secCount[actor] ?: 0) + 1
        secCount[actor] = n
        return n <= cfg.maxPerSourcePerSecond
    }

    private fun enqueue(e: LogEvent) {
        db?.offer(e)
    }

    private fun dimBlacklisted(level: Level): Boolean =
        level.dimension().location().toString() in InspectorStore.worldBlacklist

    private fun dim(level: Level): String = level.dimension().location().toString()

    private fun now(): Long = System.currentTimeMillis()

    // ---------- 方块事件 ----------

    @SubscribeEvent
    fun onBlockBreak(e: BlockEvent.BreakEvent) {
        if (!enabled || !InspectorStore.config.logBlockBreak) return
        val level = e.level as? ServerLevel ?: return
        if (dimBlacklisted(level)) return

        val player = e.player
        val kind = Source.kindOfPlayer(player)
        val actor: String
        val uuid: String?
        when (kind) {
            SourceKind.PLAYER -> {
                if (!InspectorStore.config.logPlayer) return
                actor = Source.playerName(player!!)
                uuid = Source.playerUuid(player!!)
            }
            SourceKind.AUTOMATION -> {
                if (!InspectorStore.config.logAutomation) return
                actor = Source.automationName(level, e.pos)
                uuid = null
            }
            else -> return
        }

        val material = Nbt.blockId(e.state)
        if (material in InspectorStore.blockBlacklist) return
        if (!admit(actor)) return

        // 破坏发生在方块实体被移除之前，此时还能读到它的内容（箱子里有什么）
        enqueue(
            LogEvent(now(), dim(level), e.pos.x, e.pos.y, e.pos.z, actor, uuid, kind,
                Actions.BREAK, material, 1, Nbt.blockEntityNbt(level, e.pos))
        )
    }

    @SubscribeEvent
    fun onBlockPlace(e: BlockEvent.EntityPlaceEvent) {
        if (!enabled || !InspectorStore.config.logBlockPlace) return
        val level = e.level as? ServerLevel ?: return
        if (dimBlacklisted(level)) return

        val entity = e.entity
        val kind = Source.kindOfEntity(entity)
        val actor: String
        val uuid: String?
        when (kind) {
            SourceKind.PLAYER -> {
                if (!InspectorStore.config.logPlayer) return
                val p = entity as net.minecraft.world.entity.player.Player
                actor = Source.playerName(p)
                uuid = Source.playerUuid(p)
            }
            SourceKind.AUTOMATION -> {
                if (!InspectorStore.config.logAutomation) return
                actor = Source.automationName(level, e.pos)
                uuid = null
            }
            SourceKind.NATURAL -> {
                val name = Source.naturalName(entity!!)
                if (!(InspectorStore.config.logNatural || name in InspectorStore.alwaysLog)) return
                actor = name
                uuid = null
            }
            else -> return
        }

        val material = Nbt.blockId(e.placedBlock)
        if (material in InspectorStore.blockBlacklist) return
        if (!admit(actor)) return

        // 玩家点火 / 放岩浆单独落一个动作码，方便 `/inspect lookup a:fire` 精准追责；
        // 火焰蔓延、岩浆流动属于自然来源，不走这条（见 onBlockPlace 的自然分支）。
        val action = if (kind == SourceKind.PLAYER &&
            (material == "minecraft:fire" || material == "minecraft:lava")
        ) Actions.PLACE_FIRE else Actions.PLACE

        enqueue(
            LogEvent(now(), dim(level), e.pos.x, e.pos.y, e.pos.z, actor, uuid, kind,
                action, material, 1, Nbt.blockEntityNbt(level, e.pos))
        )
    }

    // ---------- 爆炸（苦力怕 / TNT 的「该记」） ----------

    @SubscribeEvent
    fun onExplosion(e: ExplosionEvent.Detonate) {
        if (!enabled) return
        val level = e.level as? ServerLevel ?: return
        if (dimBlacklisted(level)) return

        val name = explosionName(e.explosion)
        // 只记可识别的敌对爆炸来源（默认 #creeper #tnt #wither #ghast）
        if (name !in InspectorStore.alwaysLog) return

        var logged = 0
        for (pos in e.affectedBlocks) {
            val state = level.getBlockState(pos)
            if (state.isAir) continue
            val material = Nbt.blockId(state)
            if (material in InspectorStore.blockBlacklist) continue
            if (!admit(name)) return
            enqueue(
                LogEvent(now(), dim(level), pos.x, pos.y, pos.z, name, null, SourceKind.NATURAL,
                    Actions.BREAK, material, 1, Nbt.blockEntityNbt(level, pos))
            )
            if (++logged >= 500) break
        }
    }

    private fun explosionName(explosion: Explosion): String {
        val direct = explosion.directSourceEntity
        val indirect = explosion.indirectSourceEntity
        return when {
            direct is Creeper || indirect is Creeper -> "#creeper"
            direct is MinecartTNT || direct is PrimedTnt -> "#tnt"
            direct is WitherBoss || indirect is WitherBoss -> "#wither"
            direct is Fireball -> "#ghast"
            else -> "#explosion"
        }
    }

    // ---------- 容器拿取 / 放入 ----------

    private class OpenContainer(
        val world: String,
        val pos: BlockPos?,
        val before: MutableMap<Pair<Container, Int>, ItemStack>,
    )

    @SubscribeEvent
    fun onContainerOpen(e: PlayerContainerEvent.Open) {
        if (!enabled || !InspectorStore.config.logContainers) return
        val player = e.entity as? ServerPlayer ?: return
        if (Source.kindOfPlayer(player) != SourceKind.PLAYER) return
        snapshotContainer(player, e.container)?.let { openContainers[player.uuid] = it }
    }

    @SubscribeEvent
    fun onContainerClose(e: PlayerContainerEvent.Close) {
        if (!enabled || !InspectorStore.config.logContainers) return
        val player = e.entity as? ServerPlayer ?: return
        val open = openContainers.remove(player.uuid) ?: return
        diffContainer(player, e.container, open)
    }

    /**
     * 玩家退出时清掉他的会话状态。
     *
     * 异常退出（崩溃 / 断线）可能不触发容器 Close 事件 —— 不清的话 openContainers
     * 会一直持有整个容器快照（含全部格子的 ItemStack 副本，还强引用着 Container 本体，
     * 阻止已卸载的方块实体被 GC），来过一个开过箱子的玩家就攒一份。
     */
    @SubscribeEvent
    fun onLogout(e: PlayerEvent.PlayerLoggedOutEvent) {
        val player = e.entity as? ServerPlayer ?: return
        inspecting.remove(player.uuid)
        openContainers.remove(player.uuid)
        lookups.remove(player.uuid)
        pendingRollback.remove(player.uuid)
    }

    private fun snapshotContainer(player: ServerPlayer, menu: net.minecraft.world.inventory.AbstractContainerMenu): OpenContainer? {
        var pos: BlockPos? = null
        val before = HashMap<Pair<Container, Int>, ItemStack>()
        for (slot in menu.slots) {
            val c = slot.container ?: continue
            if (c is net.minecraft.world.entity.player.Player) continue
            if (c is BlockEntity && pos == null) pos = c.blockPos
            before[c to slot.index] = slot.item.copy()
        }
        if (before.isEmpty()) return null
        return OpenContainer(dim(player.level()), pos, before)
    }

    private fun diffContainer(player: ServerPlayer, menu: net.minecraft.world.inventory.AbstractContainerMenu, open: OpenContainer) {
        if (dimBlacklisted(player.level())) return
        val now = HashMap<Pair<Container, Int>, ItemStack>()
        for (slot in menu.slots) {
            val c = slot.container ?: continue
            if (c is net.minecraft.world.entity.player.Player) continue
            now[c to slot.index] = slot.item.copy()
        }

        val pos = open.pos ?: player.blockPosition()
        val world = open.world
        var logged = 0
        val keys = HashSet<Pair<Container, Int>>(open.before.keys)
        keys.addAll(now.keys)

        for (key in keys) {
            val b = open.before[key]
            val n = now[key]
            if (b == null && n == null) continue
            if (b == null || b.isEmpty) {
                if (n != null && !n.isEmpty) logDelta(player, world, pos, n, n.count, Actions.CONTAINER_PUT)
            } else if (n == null || n.isEmpty) {
                logDelta(player, world, pos, b, b.count, Actions.CONTAINER_TAKE)
            } else if (b.item == n.item) {
                val delta = n.count - b.count
                if (delta > 0) logDelta(player, world, pos, n, delta, Actions.CONTAINER_PUT)
                else if (delta < 0) logDelta(player, world, pos, b, -delta, Actions.CONTAINER_TAKE)
            } else {
                logDelta(player, world, pos, b, b.count, Actions.CONTAINER_TAKE)
                logDelta(player, world, pos, n, n.count, Actions.CONTAINER_PUT)
            }
            if (++logged >= 256) break
        }
    }

    private fun logDelta(player: ServerPlayer, world: String, pos: BlockPos, stack: ItemStack, amount: Int, action: Int) {
        if (amount <= 0) return
        val material = Nbt.itemId(stack)
        if (material in InspectorStore.itemBlacklist) return
        val actor = Source.playerName(player)
        if (!admit(actor)) return
        enqueue(
            LogEvent(now(), world, pos.x, pos.y, pos.z, actor, Source.playerUuid(player),
                SourceKind.PLAYER, action, material, amount, Nbt.itemNbt(stack, player.level().registryAccess()))
        )
    }

    // ---------- 击杀 ----------

    @SubscribeEvent
    fun onDeath(e: LivingDeathEvent) {
        if (!enabled || !InspectorStore.config.logKills) return
        val level = e.entity.level() as? ServerLevel ?: return
        if (dimBlacklisted(level)) return
        val killer = e.source.entity as? ServerPlayer ?: return
        if (Source.kindOfPlayer(killer) != SourceKind.PLAYER) return

        val actor = Source.playerName(killer)
        if (!admit(actor)) return
        val victimId = BuiltInRegistries.ENTITY_TYPE.getKey(e.entity.type)?.toString() ?: "unknown"
        val p = e.entity.blockPosition()
        enqueue(
            LogEvent(now(), dim(level), p.x, p.y, p.z, actor, Source.playerUuid(killer),
                SourceKind.PLAYER, Actions.KILL, victimId, 1, null)
        )
    }

    // ---------- 丢 / 捡（默认关） ----------

    @SubscribeEvent
    fun onToss(e: ItemTossEvent) {
        if (!enabled || !InspectorStore.config.logItemDropPickup) return
        val player = e.player
        if (Source.kindOfPlayer(player) != SourceKind.PLAYER) return
        val level = player.level() as? ServerLevel ?: return
        if (dimBlacklisted(level)) return
        val stack = e.entity.item
        val material = Nbt.itemId(stack)
        if (material in InspectorStore.itemBlacklist) return
        val actor = Source.playerName(player)
        if (!admit(actor)) return
        val p = player.blockPosition()
        enqueue(
            LogEvent(now(), dim(level), p.x, p.y, p.z, actor, Source.playerUuid(player),
                SourceKind.PLAYER, Actions.DROP, material, stack.count, Nbt.itemNbt(stack, level.registryAccess()))
        )
    }

    @SubscribeEvent
    fun onPickup(e: ItemEntityPickupEvent.Pre) {
        if (!enabled || !InspectorStore.config.logItemDropPickup) return
        val player = e.player
        if (Source.kindOfPlayer(player) != SourceKind.PLAYER) return
        val level = player.level() as? ServerLevel ?: return
        if (dimBlacklisted(level)) return
        val stack = e.itemEntity.item
        val material = Nbt.itemId(stack)
        if (material in InspectorStore.itemBlacklist) return
        val actor = Source.playerName(player)
        if (!admit(actor)) return
        val p = player.blockPosition()
        enqueue(
            LogEvent(now(), dim(level), p.x, p.y, p.z, actor, Source.playerUuid(player),
                SourceKind.PLAYER, Actions.PICKUP, material, stack.count, Nbt.itemNbt(stack, level.registryAccess()))
        )
    }

    // ---------- 检查模式 ----------

    @SubscribeEvent
    fun onLeftClick(e: PlayerInteractEvent.LeftClickBlock) {
        val player = e.entity as? ServerPlayer ?: return
        if (player.uuid !in inspecting) return
        e.isCanceled = true
        inspectBlock(player, e.pos)
    }

    @SubscribeEvent
    fun onRightClick(e: PlayerInteractEvent.RightClickBlock) {
        val player = e.entity as? ServerPlayer ?: return
        if (player.uuid !in inspecting) return
        e.isCanceled = true
        inspectBlock(player, e.pos)
    }

    private fun inspectBlock(player: ServerPlayer, pos: BlockPos) {
        val spec = InspectorDb.QuerySpec(world = dim(player.level()), x = pos.x, y = pos.y, z = pos.z, limit = 10)
        lookups[player.uuid] = LookupState(spec)
        runLookup(player, spec, "方块历史 @ ${pos.x},${pos.y},${pos.z}", gui = true)
    }

    // ---------- 查询执行（异步，不占主线程） ----------

    private class LookupState(val spec: InspectorDb.QuerySpec, var cursor: Long? = null, var gui: Boolean = true)

    private fun runLookup(player: ServerPlayer, spec: InspectorDb.QuerySpec, label: String, gui: Boolean) {
        val db = db ?: run { Msg.err(player, "数据库未就绪"); return }
        Msg.info(player, "查询中…")
        db.queryAsync(spec).whenComplete { res, err ->
            VoidkitServer.get()?.execute {
                if (err != null) {
                    Msg.err(player, "查询失败：${err.message}")
                    return@execute
                }
                lookups[player.uuid]?.cursor = res.nextCursor
                if (gui) renderGui(player, res, label) else renderText(player, res, label)
            }
        }
    }

    /** GUI 模式：结果弹只读箱子界面。 */
    private fun renderGui(player: ServerPlayer, res: InspectorDb.QueryResult, label: String) {
        if (res.rows.isEmpty()) {
            Msg.info(player, "$label：没有记录")
            return
        }
        AuditMenu.open(player, res.rows, res.nextCursor != null) { pageNext(player) }
    }

    /** 文字模式：结果按行发到聊天（`--text` 时退回这里）。 */
    private fun renderText(player: ServerPlayer, res: InspectorDb.QueryResult, label: String) {
        if (res.rows.isEmpty()) {
            Msg.info(player, "$label：没有记录")
            return
        }
        Msg.info(player, "===== $label =====")
        for (row in res.rows) {
            player.sendSystemMessage(AuditMenu.formatRow(row))
        }
        if (res.nextCursor != null) {
            player.sendSystemMessage(
                Msg.prefix().append(Text.legacy("&8[&b下一页 &7→&8](run:/inspect page \"&7查看下一页\")"))
            )
        }
    }

    // ---------- 命令 ----------

    override fun registerCommands(dispatcher: CommandDispatcher<CommandSourceStack>) {
        registerInspectorCommand(dispatcher, "inspect")
        registerInspectorCommand(dispatcher, "coi")
        registerInspectorCommand(dispatcher, "ins")
    }

    private fun registerInspectorCommand(dispatcher: CommandDispatcher<CommandSourceStack>, name: String) {
        val perm = { s: CommandSourceStack -> VoidkitServices.permission().has(s, "voidkit.inspector") }
        dispatcher.register(
            Commands.literal(name)
                .requires(perm)
                .executes { toggle(it.source) }
                .then(Commands.literal("on").executes { setMode(it.source, true) })
                .then(Commands.literal("off").executes { setMode(it.source, false) })
                .then(Commands.literal("here").executes { inspectHere(it.source) })
                .then(
                    Commands.literal("lookup")
                        .then(Commands.argument("filters", StringArgumentType.greedyString())
                            .executes { lookup(it.source, StringArgumentType.getString(it, "filters")) })
                )
                .then(Commands.literal("page").executes { page(it.source) })
                .then(Commands.literal("status").executes { status(it.source) })
                .then(
                    Commands.literal("purge")
                        .then(Commands.argument("days", IntegerArgumentType.integer(1))
                            .executes { purge(it.source, IntegerArgumentType.getInteger(it, "days")) })
                )
                .then(
                    Commands.literal("preview")
                        .then(Commands.argument("filters", StringArgumentType.greedyString())
                            .executes { previewRollback(it.source, StringArgumentType.getString(it, "filters")) })
                )
                .then(
                    Commands.literal("rollback")
                        .then(Commands.argument("filters", StringArgumentType.greedyString())
                            .executes { rollback(it.source, StringArgumentType.getString(it, "filters")) })
                )
                .then(
                    Commands.literal("chain")
                        .then(Commands.argument("player", StringArgumentType.word())
                            .executes { chain(it.source, StringArgumentType.getString(it, "player"), "") })
                        .then(Commands.argument("player", StringArgumentType.word())
                            .then(Commands.argument("filters", StringArgumentType.greedyString())
                                .executes { chain(it.source, StringArgumentType.getString(it, "player"), StringArgumentType.getString(it, "filters")) }))
                )
        )
    }

    private fun toggle(source: CommandSourceStack): Int {
        val player = source.player ?: run { Msg.err(source, "仅玩家可用"); return 0 }
        if (inspecting.remove(player.uuid)) {
            Msg.ok(player, "检查模式已关闭")
        } else {
            inspecting.add(player.uuid)
            Msg.ok(player, "检查模式已开启 —— 左键/右键点击方块查看历史，再输一次关闭")
        }
        return 1
    }

    private fun setMode(source: CommandSourceStack, on: Boolean): Int {
        val player = source.player ?: return 0
        if (on) {
            inspecting.add(player.uuid)
            Msg.ok(player, "检查模式已开启")
        } else {
            inspecting.remove(player.uuid)
            Msg.ok(player, "检查模式已关闭")
        }
        return 1
    }

    private fun inspectHere(source: CommandSourceStack): Int {
        val player = source.player ?: return 0
        val hit = player.pick(20.0, 0f, false)
        if (hit is BlockHitResult) {
            inspectBlock(player, hit.blockPos)
            return 1
        }
        Msg.err(player, "没有对准方块")
        return 0
    }

    private fun lookup(source: CommandSourceStack, args: String): Int {
        val player = source.player ?: run { Msg.err(source, "仅玩家可用"); return 0 }
        val gui = !args.contains("--text")
        val spec = buildLookup(player, args)
        lookups[player.uuid] = LookupState(spec, gui = gui)
        runLookup(player, spec, "查询结果", gui)
        return 1
    }

    private fun page(source: CommandSourceStack): Int {
        val player = source.player ?: return 0
        pageNext(player)
        return 1
    }

    /** 翻到下一页：沿用上次查询的 spec + 当前游标，按上次的 GUI/文字模式重新渲染。 */
    private fun pageNext(player: ServerPlayer): Boolean {
        val st = lookups[player.uuid] ?: run { Msg.err(player, "先执行一次 /inspect lookup 或 /inspect here"); return false }
        val cursor = st.cursor ?: run { Msg.info(player, "没有下一页了"); return false }
        val s = st.spec
        val next = InspectorDb.QuerySpec(
            world = s.world, x = s.x, y = s.y, z = s.z, radius = s.radius,
            actor = s.actor, action = s.action, material = s.material,
            sourceKinds = s.sourceKinds, since = s.since, until = s.until,
            limit = s.limit, beforeId = cursor,
        )
        runLookup(player, next, "查询结果", st.gui)
        return true
    }

    private fun status(source: CommandSourceStack): Int {
        val cfg = InspectorStore.config
        val stats = db?.stats() ?: emptyMap()
        Msg.info(source, "===== 审计状态 =====")
        Msg.info(source, "记录：玩家=${on(cfg.logPlayer)} 自动化=${on(cfg.logAutomation)} 自然=${on(cfg.logNatural)} 容器=${on(cfg.logContainers)} 击杀=${on(cfg.logKills)}")
        Msg.info(source, "保留 ${cfg.retentionDays} 天 | 数据库 ${cfg.databaseFile}")
        Msg.info(source, "已入队 ${stats["queued"] ?: 0} | 已落库 ${stats["written"] ?: 0} | 丢弃 ${stats["dropped"] ?: 0} | 待写 ${stats["pending"] ?: 0}")
        Msg.info(source, "检查模式玩家：${inspecting.size}")
        return 1
    }

    private fun purge(source: CommandSourceStack, days: Int): Int {
        db?.schedulePurge(System.currentTimeMillis() - days * 86_400_000L)
        Msg.ok(source, "已提交清理任务：删除 $days 天前的记录（后台执行）")
        return 1
    }

    private fun on(b: Boolean): String = if (b) "开" else "关"

    // ---------- 因果链聚类（issue #8 第 3 层：破坏链识别与整链回滚） ----------

    /** 链查询的默认时间窗：1 小时。 */
    private val DEFAULT_CHAIN_WINDOW_MS = 3_600_000L

    /**
     * `/inspect chain <玩家> [t:时间]`：把该玩家时间窗内的操作按
     * 「同一玩家 + 邻近区域 + 连续时间段」聚成操作链并列出，附整链回滚提示。
     */
    private fun chain(source: CommandSourceStack, target: String, filters: String): Int {
        val player = source.player ?: run { Msg.err(source, "仅玩家可用"); return 0 }
        val cfg = InspectorStore.config
        val windowMs = parseChainWindow(filters) ?: DEFAULT_CHAIN_WINDOW_MS
        val spec = InspectorDb.QuerySpec(
            world = dim(player.level()),
            actor = target,
            since = System.currentTimeMillis() - windowMs,
            limit = 500,
        )
        Msg.info(player, "正在聚类操作链…")
        fetchAll(spec, cfg.maxRollbackEvents) { rows, error ->
            if (error != null) {
                Msg.err(player, "读取失败：$error")
                return@fetchAll
            }
            if (rows.isEmpty()) {
                Msg.info(player, "时间窗内没有 $target 的记录")
                return@fetchAll
            }
            val chains = ChainCluster.cluster(rows, cfg.chainIdleMinutes, cfg.chainRadiusBlocks)
            val fmt = SimpleDateFormat("MM-dd HH:mm:ss")
            Msg.info(player, "===== $target 的操作链（共 ${chains.size} 条，时间窗 ${describeWindow(windowMs)}）=====")
            for (c in chains) {
                Msg.info(player, "#${c.id} ${fmt.format(Date(c.startTime))} ~ ${fmt.format(Date(c.endTime))} · ${c.eventCount} 事件 · ${c.blockCount} 方块 · 中心 @ ${c.centerX}, ${c.centerY}, ${c.centerZ}")
                Msg.info(player, "    ${c.summary()}")
                Msg.info(player, "    整链回滚：/inspect rollback chain:$target:${c.id} t:${describeWindow(windowMs)}")
            }
        }
        return 1
    }

    /** 从过滤器里取 t: 时间窗（只认时间参数，其余忽略）；没有则 null（用默认窗）。 */
    private fun parseChainWindow(filters: String): Long? {
        var window: Long? = null
        for (token in filters.split(Regex("\\s+"))) {
            val idx = token.indexOf(':')
            if (idx <= 0) continue
            if (token.substring(0, idx).lowercase() in setOf("t", "time")) {
                parseTime(token.substring(idx + 1))?.let { window = it }
            }
        }
        return window
    }

    /** 把毫秒窗还原成 t: 参数（分钟取整，不整除退到秒），保证链列表与回滚提示能互相照抄。 */
    private fun describeWindow(ms: Long): String =
        if (ms > 0 && ms % 60_000L == 0L) "${ms / 60_000L}m" else "${ms / 1000L}s"

    /** 链回滚参数：`chain:<玩家>:<链id>` + 可选 t: 时间窗与 --include-machines。 */
    private class ChainRef(val target: String, val chainId: Int, val windowMs: Long, val includeMachines: Boolean)

    /** 解析链回滚参数。格式不对时给出提示并返回 null。 */
    private fun parseChainRef(player: ServerPlayer, args: String): ChainRef? {
        val tokens = args.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val parts = tokens.first().removePrefix("chain:").split(':')
        val target = parts.getOrNull(0)
        val chainId = parts.getOrNull(1)?.toIntOrNull()
        var windowMs = DEFAULT_CHAIN_WINDOW_MS
        var includeMachines = false
        for (token in tokens.drop(1)) {
            val idx = token.indexOf(':')
            if (idx <= 0) {
                if (token == "--include-machines") includeMachines = true
                continue
            }
            if (token.substring(0, idx).lowercase() in setOf("t", "time")) {
                parseTime(token.substring(idx + 1))?.let { windowMs = it }
            }
        }
        if (target.isNullOrBlank() || chainId == null || chainId < 1) {
            Msg.err(player, "链回滚格式：/inspect rollback chain:<玩家>:<链id> [t:时间窗]（链 id 与时间窗以 /inspect chain <玩家> 的结果为准）")
            return null
        }
        return ChainRef(target, chainId, windowMs, includeMachines)
    }

    /** 预览指纹：与普通回滚同思路，链回滚以「玩家 + 链 id + 时间窗」定位那批数据。 */
    private fun chainKey(player: ServerPlayer, ref: ChainRef): String =
        "chain=${ref.target}:${ref.chainId}|w=${dim(player.level())}|t=${ref.windowMs}|im=${ref.includeMachines}"

    private fun previewChainRollback(player: ServerPlayer, args: String): Int {
        val cfg = InspectorStore.config
        val ref = parseChainRef(player, args) ?: return 0
        val spec = InspectorDb.QuerySpec(
            world = dim(player.level()),
            actor = ref.target,
            since = System.currentTimeMillis() - ref.windowMs,
            limit = 500,
        )
        Msg.info(player, "正在读取审计记录…")
        fetchAll(spec, cfg.maxRollbackEvents) { rows, error ->
            if (error != null) {
                Msg.err(player, "读取失败：$error")
                return@fetchAll
            }
            val chain = ChainCluster.cluster(rows, cfg.chainIdleMinutes, cfg.chainRadiusBlocks)
                .firstOrNull { it.id == ref.chainId }
            if (chain == null) {
                Msg.err(player, "时间窗内没有链 #${ref.chainId} —— 先 /inspect chain ${ref.target} 确认链 id 与 t: 时间窗一致")
                return@fetchAll
            }
            // 回滚要「倒序撤销」：链内事件按审计 id 从新到旧交给任务构造器
            val job = RollbackJob.create(
                chain.events.sortedByDescending { it.id },
                ref.includeMachines,
                cfg.idSet(cfg.rollbackMachineNamespaces),
                cfg.rollbackBlocksPerSecond,
                chain.world,
            )
            val key = chainKey(player, ref)
            val audit = ChainAudit(chain.world, chain.centerX, chain.centerY, chain.centerZ,
                "chain:${ref.target}:${ref.chainId}", chain.eventCount, Source.playerName(player))
            pendingRollback[player.uuid] = PendingRollback(key, job, System.currentTimeMillis(), audit)
            val fmt = SimpleDateFormat("MM-dd HH:mm:ss")
            val lines = ArrayList<String>()
            lines += "===== 整链回滚预览（链 #${chain.id}，${chain.eventCount} 条记录）====="
            lines += "玩家 ${chain.playerNames} ｜ ${fmt.format(Date(chain.startTime))} ~ ${fmt.format(Date(chain.endTime))} ｜ ${chain.summary()}"
            lines += "直接还原方块 ${job.previewNormal}｜还原容器/含数据 ${job.previewNbt}｜清除放置 ${job.previewRemove}｜恢复物品 ${job.previewItems} 组"
            val skipTotal = job.previewMachineSkips.values.sum()
            if (skipTotal > 0) {
                lines += "默认跳过机器/结构零件 $skipTotal 处（防断网络，宁可不回滚也不回滚一半）："
                for ((mat, n) in job.previewMachineSkips.entries.take(8)) lines += "  $mat ×$n"
                if (!ref.includeMachines) lines += "确认连机器一起回滚：加 --include-machines 重新 preview"
            }
            lines += "确认执行：/inspect rollback chain:${ref.target}:${ref.chainId} t:${describeWindow(ref.windowMs)}${if (ref.includeMachines) " --include-machines" else ""}（${cfg.rollbackPreviewTtlSeconds} 秒内有效）"
            for (line in lines) Msg.info(player, line)
        }
        return 1
    }

    // ---------- 智能回滚（issue #8 v1：分类感知 + 预览 + 执行） ----------

    /**
     * 回滚查询用比 lookup 更大的分页（500/页），时间窗 / 半径 / 玩家三要素都必填 ——
     * 回滚是世界改动操作，宁可让管理员多打几个字，也不做含糊的默认值。
     */
    private fun parseRollbackArgs(player: ServerPlayer, args: String): Pair<InspectorDb.QuerySpec, Boolean>? {
        val includeMachines = args.contains("--include-machines")
        val base = buildLookup(player, args)
        if (base.actor.isNullOrBlank()) {
            Msg.err(player, "必须指定玩家：u:名字（这是回滚的安全带）")
            return null
        }
        if (base.since == null) {
            Msg.err(player, "必须指定时间窗：t:1h / t:30m / t:1d（防止误回滚太老的数据）")
            return null
        }
        if ((base.radius ?: 0) > InspectorStore.config.maxRollbackRadius) {
            Msg.err(player, "半径超过上限 ${InspectorStore.config.maxRollbackRadius}，分区域多次回滚更安全")
            return null
        }
        val spec = InspectorDb.QuerySpec(
            world = base.world, x = base.x, y = base.y, z = base.z, radius = base.radius,
            actor = base.actor, action = base.action, material = base.material,
            since = base.since, limit = 500,
        )
        return spec to includeMachines
    }

    /** 预览指纹：rollback 必须带一模一样的过滤参数 + 站在原位，才能执行对应的那批预览数据。 */
    private fun rollbackKey(spec: InspectorDb.QuerySpec, includeMachines: Boolean): String =
        "u=${spec.actor}|w=${spec.world}|p=${spec.x},${spec.y},${spec.z}|r=${spec.radius}" +
            "|t=${spec.since}|a=${spec.action}|m=${spec.material}|im=$includeMachines"

    private fun previewRollback(source: CommandSourceStack, args: String): Int {
        val player = source.player ?: run { Msg.err(source, "仅玩家可用"); return 0 }
        val cfg = InspectorStore.config
        if (!cfg.rollbackEnabled) {
            Msg.err(player, "回滚功能未开启（inspector.rollbackEnabled）")
            return 0
        }
        // 整链回滚：chain:<玩家>:<链id> 开头走链路径，先聚类定位链，再复用同一套预览/执行
        if (args.trim().startsWith("chain:")) return previewChainRollback(player, args)
        val parsed = parseRollbackArgs(player, args) ?: return 0
        val (spec, includeMachines) = parsed
        Msg.info(player, "正在读取审计记录…")
        fetchAll(spec, cfg.maxRollbackEvents) { rows, error ->
            if (error != null) {
                Msg.err(player, "读取失败：$error")
                return@fetchAll
            }
            if (rows.isEmpty()) {
                Msg.info(player, "没有找到可回滚的记录")
                return@fetchAll
            }
            val job = RollbackJob.create(
                rows, includeMachines,
                cfg.idSet(cfg.rollbackMachineNamespaces),
                cfg.rollbackBlocksPerSecond,
                spec.world ?: "未知维度",
            )
            val key = rollbackKey(spec, includeMachines)
            pendingRollback[player.uuid] = PendingRollback(key, job, System.currentTimeMillis())
            val lines = ArrayList<String>()
            lines += "===== 回滚预览（${rows.size} 条记录）====="
            lines += "直接还原方块 ${job.previewNormal}｜还原容器/含数据 ${job.previewNbt}｜清除放置 ${job.previewRemove}｜恢复物品 ${job.previewItems} 组"
            val skipTotal = job.previewMachineSkips.values.sum()
            if (skipTotal > 0) {
                lines += "默认跳过机器/结构零件 $skipTotal 处（防断网络，宁可不回滚也不回滚一半）："
                for ((mat, n) in job.previewMachineSkips.entries.take(8)) lines += "  $mat ×$n"
                if (!includeMachines) {
                    lines += "确认连机器一起回滚：加 --include-machines 重新 preview"
                }
            }
            lines += "确认执行：/inspect rollback ${args.trim()}（需站在原位，${cfg.rollbackPreviewTtlSeconds} 秒内有效）"
            for (line in lines) Msg.info(player, line)
        }
        return 1
    }

    private fun rollback(source: CommandSourceStack, args: String): Int {
        val player = source.player ?: run { Msg.err(source, "仅玩家可用"); return 0 }
        val cfg = InspectorStore.config
        if (!cfg.rollbackEnabled) {
            Msg.err(player, "回滚功能未开启（inspector.rollbackEnabled）")
            return 0
        }
        if (rollbackRun != null) {
            Msg.err(player, "已有一次回滚正在执行，等它跑完再发起下一次")
            return 0
        }
        // 整链回滚：校验链预览的指纹后，执行预览时构造好的那条链的回滚任务
        if (args.trim().startsWith("chain:")) {
            val ref = parseChainRef(player, args) ?: return 0
            val pending = pendingRollback[player.uuid]
            if (pending == null) {
                Msg.err(player, "先执行 /inspect preview ${args.trim()} 预览，再确认执行")
                return 0
            }
            if (pending.key != chainKey(player, ref)) {
                Msg.err(player, "链参数与预览不一致 —— 请重新 preview（预览和执行必须用同一批数据）")
                return 0
            }
            val ttlMs = cfg.rollbackPreviewTtlSeconds * 1000L
            if (System.currentTimeMillis() - pending.createdAt > ttlMs) {
                pendingRollback.remove(player.uuid)
                Msg.err(player, "预览已过期，请重新 preview")
                return 0
            }
            pendingRollback.remove(player.uuid)
            rollbackRun = RollbackRun(pending.job, player.uuid, pending.chainAudit)
            Msg.ok(player, "开始回滚链 #${ref.chainId}（每秒最多 ${cfg.rollbackBlocksPerSecond} 个操作），完成后出报告")
            return 1
        }
        val parsed = parseRollbackArgs(player, args) ?: return 0
        val (spec, includeMachines) = parsed
        val pending = pendingRollback[player.uuid]
        if (pending == null) {
            Msg.err(player, "先执行 /inspect preview ${args.trim()} 预览，再确认执行")
            return 0
        }
        if (pending.key != rollbackKey(spec, includeMachines)) {
            Msg.err(player, "过滤参数与预览不一致 —— 请重新 preview（预览和执行必须用同一批数据）")
            return 0
        }
        val ttlMs = cfg.rollbackPreviewTtlSeconds * 1000L
        if (System.currentTimeMillis() - pending.createdAt > ttlMs) {
            pendingRollback.remove(player.uuid)
            Msg.err(player, "预览已过期，请重新 preview")
            return 0
        }
        pendingRollback.remove(player.uuid)
        rollbackRun = RollbackRun(pending.job, player.uuid)
        Msg.ok(player, "开始回滚（每秒最多 ${cfg.rollbackBlocksPerSecond} 个操作），完成后出报告")
        return 1
    }

    /**
     * 按游标翻页拉取全部记录（runQuery 单页上限 500），拉满 [cap] 条为止。
     * 回调回到主线程执行，和 runLookup 同一套线程约定。
     */
    private fun fetchAll(spec: InspectorDb.QuerySpec, cap: Int, cb: (List<InspectorDb.QueryRow>, String?) -> Unit) {
        val all = ArrayList<InspectorDb.QueryRow>()
        fun step(store: InspectorDb, cursor: Long?) {
            val page = InspectorDb.QuerySpec(
                world = spec.world, x = spec.x, y = spec.y, z = spec.z, radius = spec.radius,
                actor = spec.actor, action = spec.action, material = spec.material,
                since = spec.since, until = spec.until, limit = 500, beforeId = cursor,
            )
            store.queryAsync(page).whenComplete { res, err ->
                VoidkitServer.get()?.execute {
                    if (err != null) {
                        cb(all, err.message)
                        return@execute
                    }
                    // 查询按 id 从新到旧返回，直接顺序追加即为「倒序撤销」要的次序
                    all += res.rows
                    if (res.nextCursor != null && all.size < cap) {
                        step(store, res.nextCursor)
                    } else {
                        cb(all.take(cap), null)
                    }
                }
            }
        }
        val store = db
        if (store == null) {
            cb(emptyList(), "数据库未就绪")
            return
        }
        step(store, null)
    }

    // ---------- 查询参数解析 ----------

    private fun buildLookup(player: ServerPlayer, args: String): InspectorDb.QuerySpec {
        var actor: String? = null
        var radius = 10
        var since: Long? = null
        var action: Int? = null
        var material: String? = null
        var limit = 10

        for (token in args.split(Regex("\\s+"))) {
            if (token.isBlank()) continue
            val idx = token.indexOf(':')
            if (idx <= 0) continue
            val key = token.substring(0, idx).lowercase()
            val value = token.substring(idx + 1)
            when (key) {
                "u", "user", "player" -> actor = value
                "r", "radius" -> radius = value.toIntOrNull()?.coerceIn(1, 200) ?: radius
                "t", "time" -> since = parseTime(value)?.let { System.currentTimeMillis() - it }
                "a", "action" -> action = parseAction(value)
                "b", "i", "m", "material", "block", "item" -> material = value.lowercase()
                "p", "size" -> limit = (value.toIntOrNull() ?: limit).coerceIn(1, 100)
            }
        }

        val p = player.blockPosition()
        return InspectorDb.QuerySpec(
            world = dim(player.level()),
            x = p.x, y = p.y, z = p.z,
            radius = radius,
            actor = actor,
            action = action,
            material = material,
            since = since,
            limit = limit,
        )
    }

    private fun parseTime(s: String): Long? {
        var total = 0L
        var num = 0L
        var hasUnit = false
        for (ch in s) {
            if (ch.isDigit()) {
                num = num * 10 + (ch - '0')
            } else {
                val mult = when (ch.lowercaseChar()) {
                    's' -> 1000L
                    'm' -> 60_000L
                    'h' -> 3_600_000L
                    'd' -> 86_400_000L
                    else -> 0L
                }
                if (mult > 0) {
                    total += num * mult
                    num = 0
                    hasUnit = true
                }
            }
        }
        if (num > 0) total += num * 1000L // 尾数无单位按秒
        return if (hasUnit || total > 0) total else null
    }

    private fun parseAction(s: String): Int? = when (s.lowercase()) {
        "place", "放置" -> Actions.PLACE
        "break", "destroy", "破坏" -> Actions.BREAK
        "put", "放入" -> Actions.CONTAINER_PUT
        "take", "取出" -> Actions.CONTAINER_TAKE
        "kill", "击杀" -> Actions.KILL
        "drop", "丢出" -> Actions.DROP
        "pickup", "捡起" -> Actions.PICKUP
        "fire", "点火", "lava", "岩浆" -> Actions.PLACE_FIRE
        else -> null
    }

    // ---------- API ----------

    override fun registerApi(router: ApiRouter) {
        router.get("/api/inspector") { _ ->
            val cfg = InspectorStore.config
            val stats = db?.stats() ?: emptyMap()
            ApiJson.obj(
                "ok", true,
                "enabled", enabled,
                "logPlayer", cfg.logPlayer,
                "logAutomation", cfg.logAutomation,
                "logNatural", cfg.logNatural,
                "logContainers", cfg.logContainers,
                "logKills", cfg.logKills,
                "retentionDays", cfg.retentionDays,
                "queued", stats["queued"] ?: 0,
                "written", stats["written"] ?: 0,
                "dropped", stats["dropped"] ?: 0,
                "pending", stats["pending"] ?: 0,
                "inspecting", inspecting.size,
            )
        }

        // 面板查询：GET /api/inspector/lookup?world=&x=&y=&z=&r=&actor=&action=&material=&limit=
        router.get("/api/inspector/lookup") { req ->
            val store = db ?: return@get ApiJson.obj("ok", false, "error", "no_db")
            val world = req.query("world")
            val x = req.query("x")?.toIntOrNull()
            val y = req.query("y")?.toIntOrNull()
            val z = req.query("z")?.toIntOrNull()
            val spec = InspectorDb.QuerySpec(
                world = world,
                x = x, y = y, z = z,
                radius = req.query("r")?.toIntOrNull(),
                actor = req.query("actor"),
                action = req.query("action")?.toIntOrNull(),
                material = req.query("material"),
                limit = req.queryInt("limit", 20),
            )
            val res = store.query(spec)
            ApiJson.obj(
                "ok", true,
                "nextCursor", res.nextCursor ?: -1L,
                "rows", res.rows.map { row ->
                    ApiJson.obj(
                        "id", row.id,
                        "time", row.time,
                        "world", row.world,
                        "x", row.x, "y", row.y, "z", row.z,
                        "actor", row.actor,
                        "action", row.action,
                        "material", row.material,
                        "amount", row.amount,
                        "source", row.source,
                        "hasNbt", row.nbt != null,
                    )
                },
            )
        }
    }
}
