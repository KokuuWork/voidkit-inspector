package org.voidkit.inspector

import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.Container
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties

/**
 * 分帧执行的世界改动任务的公共接口：回滚与它的撤销（undo）都按「每秒一批」推进，
 * InspectorFeature 的 tickRollback 只认这个接口，不关心具体是回滚还是撤销。
 */
interface SteppedJob {
    val isDone: Boolean
    fun tick(server: MinecraftServer)

    /** 取走完成报告（取一次就没了）。null = 还没跑完。 */
    fun pollReport(): List<String>?
}

/**
 * 智能回滚执行器 —— issue #8 五层设计中的第 1 层（分类感知）v1 落地。
 *
 * ## 设计要点
 *
 * - **先分类，再动手**：所有任务在构造时（查询线程）就按 [RollbackPolicy] 分类完毕，
 *   机器 / 结构零件默认跳过，服主可用 `--include-machines` 显式覆盖。
 * - **结构感知（第 2 层）**：双格方块（门 / 床）两半的记录都在同一批里才回滚，
 *   缺一半保守跳过；被跳过的机器按 6 向邻接聚成连通体，报告按连通体聚合输出，
 *   `--include-machines` 时整连通体一起回滚 —— 绝不回滚一半。见 [StructureSense]。
 * - **分帧执行（防雪崩）**：回滚按每秒 [blocksPerSecond] 个操作分批落到主线程，
 *   大范围回滚不会一 tick 烧掉 TPS。
 * - **倒序撤销**：任务按审计 id 从新到旧排列，后发生的变化先被撤销，
 *   「放机器 → 拆机器」这类闭环操作能自然抵消，不会留下幽灵方块。
 * - **保守优先（不出问题）**：容器物品恢复只做「把拿走的塞回去」，塞不下的如实报告，
 *   绝不丢到地上（防复制 / 防卡实体）；机器一律先跳过；绝不自动封禁、绝不删除任何东西。
 *
 * v1 范围（Part of #8）：方块破坏 / 放置还原、原版容器 NBT 恢复、被拿取物品塞回。
 * 因果链聚类、整结构回滚留作后续部分；回滚执行时逐 target 拍「回滚前快照」，
 * 交给 [UndoJournal] 落库 —— 回滚的撤销（undo）见 [UndoJob]。
 */
class RollbackJob private constructor(
    private val dim: String,
    private val includeMachines: Boolean,
    private val blocksPerSecond: Int,
    private val tasks: List<Task>,
    /** 构造期（结构感知阶段）聚出的机器 / 线缆连通体（预览与报告用，线程安全只读）。 */
    private val machineComponents: List<StructureSense.MachineComponent>,
    /** 构造期因缺另一半被保守跳过的双格方块：material -> 次数。 */
    private val doubleBlockSkips: Map<String, Int>,
) : SteppedJob {

    /** 单个回滚动作。构造期生成，主线程消费。 */
    private sealed interface Task

    /** 还原被破坏的方块（可带方块实体 NBT）。 */
    private class RestoreBlock(val pos: BlockPos, val material: String, val nbt: CompoundTag?, val category: RollbackCategory) : Task

    /** 清除被放置的方块。 */
    private class RemoveBlock(val pos: BlockPos) : Task

    /** 把被拿走的物品塞回容器（NBT 在应用期才解析，构造期没有 registryAccess）。 */
    private class RestoreItems(val pos: BlockPos, val material: String, val amount: Int, val itemTag: CompoundTag?) : Task

    // ---------------- 统计（主线程写） ----------------

    private var restoredBlocks = 0
    private var restoredContainers = 0
    private var removedBlocks = 0
    private var itemsRestored = 0

    /** 塞不回去的物品：material -> 数量（容器满了 / 容器不在了）。 */
    private val unrestoredItems = LinkedHashMap<String, Int>()

    /** 运行期跳过的机器（容器位置现在是模组机器方块实体）：BE id -> 次数。 */
    private val runtimeMachineSkips = LinkedHashMap<String, Int>()

    private var containerMissing = 0
    private var errors = 0

    private var idx = 0
    private var finished = false
    private var report: List<String>? = null

    /** 回滚前快照（undo journal 的原料）：主线程应用任务时逐个记录。 */
    private val undoActions = ArrayList<UndoAction>()

    // ---------------- 预览统计（构造期算好，线程安全只读） ----------------

    /** 分类计数，供 preview 命令展示。 */
    val previewNormal: Int
    val previewNbt: Int
    val previewRemove: Int
    val previewItems: Int

    /** 构造期聚出的机器 / 线缆连通体（只读快照，线程安全）：默认跳过场景按连通体聚合展示。 */
    val previewMachineComponents: List<StructureSense.MachineComponent> = machineComponents

    /** 构造期因缺另一半被保守跳过的双格方块：material -> 次数（只读快照，线程安全）。 */
    val previewDoubleBlockSkips: Map<String, Int> = doubleBlockSkips

    init {
        var normal = 0
        var nbtBlocks = 0
        var remove = 0
        var items = 0
        for (t in tasks) {
            when (t) {
                is RestoreBlock -> if (t.category == RollbackCategory.RESTORE_NBT) nbtBlocks++ else normal++
                is RemoveBlock -> remove++
                is RestoreItems -> items++
            }
        }
        previewNormal = normal
        previewNbt = nbtBlocks
        previewRemove = remove
        previewItems = items
    }

    // ---------------- 执行 ----------------

    /** 还有没有活可干。 */
    override val isDone: Boolean get() = finished

    /**
     * 每秒由 [InspectorFeature.onSecond] 调一次，在主线程上分批应用任务。
     */
    override fun tick(server: MinecraftServer) {
        if (finished) return
        val level = levelOf(server)
        if (level == null) {
            finish(listOf("回滚中止：维度 $dim 当前不可用"))
            return
        }
        var budget = blocksPerSecond
        while (budget > 0 && idx < tasks.size) {
            apply(level, tasks[idx++])
            budget--
        }
        if (idx >= tasks.size) finishReport()
    }

    /** 取走完成报告（取一次就没了）。null = 还没跑完。 */
    override fun pollReport(): List<String>? {
        val r = report
        report = null
        return r
    }

    /**
     * 取走本次回滚记录的「回滚前快照」（undo journal 的原料）。
     * job 跑完（isDone）后由 InspectorFeature 调用一次，取走即清空。
     */
    fun drainUndoActions(): List<UndoAction> {
        val list = ArrayList(undoActions)
        undoActions.clear()
        return list
    }

    // ---------------- 任务应用（主线程） ----------------

    private fun apply(level: ServerLevel, task: Task) {
        when (task) {
            is RestoreBlock -> {
                val state = blockStateOf(task.material)
                if (state == null) {
                    // 方块已不在注册表（整合包换过版本）：跳过并计数，绝不硬塞
                    errors++
                    return
                }
                // 动手前先拍快照：undo 要恢复到「回滚前」的样子（含空气）
                undoActions.add(snapshotBlock(level, task.pos))
                level.setBlock(task.pos, state, 3)
                restoredBlocks++
                val nbt = task.nbt
                if (nbt != null && state.hasBlockEntity()) {
                    // ⚠️ 1.20.1 的 loadStatic **只收三个参数**（没有 HolderLookup.Provider），
                    //    1.21.x 才需要传注册表（component 解析要用）。
                    val be = BlockEntity.loadStatic(task.pos, state, nbt)
                    if (be != null) {
                        level.setBlockEntity(be)
                        restoredContainers++
                    } else {
                        errors++
                    }
                }
            }

            is RemoveBlock -> {
                undoActions.add(snapshotBlock(level, task.pos))
                level.setBlock(task.pos, Blocks.AIR.defaultBlockState(), 3)
                removedBlocks++
            }

            is RestoreItems -> {
                val be = level.getBlockEntity(task.pos)
                if (be !is Container) {
                    // 容器方块不在了（没被这条回滚恢复，或本来就是空气）——如实报告，不硬造方块
                    containerMissing++
                    return
                }
                val beKey = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.type)?.toString() ?: "unknown"
                val beNs = beKey.substringBefore(':')
                if (beNs != "minecraft" && !includeMachines) {
                    runtimeMachineSkips.merge(beKey, 1, Int::plus)
                    return
                }
                // ⚠️ 1.20.1 没有 component 系统：还原物品用 `ItemStack.of(tag)`
                //    直接从 NBT 重建（1.21.x 是 `ItemStack.parse(registry, tag)`，
                //    返回 Optional，且 component 解析要注册表）。
                val stack = task.itemTag?.let { ItemStack.of(it) }
                if (stack == null || stack.isEmpty) {
                    errors++
                    return
                }
                // diff 记录的 nbt 是当时整组物品的快照，count 可能大于实际拿走的数量，以 amount 为准
                stack.count = task.amount.coerceAtLeast(1)
                val toInsert = stack.count
                val leftover = insertIntoContainer(be, stack)
                if (leftover <= 0) {
                    itemsRestored++
                    undoActions.add(UndoItemsInserted(task.pos.x, task.pos.y, task.pos.z, task.material, toInsert))
                } else {
                    if (leftover < toInsert) {
                        // 只塞进去了一部分，也要按实际塞进去的数量记 journal
                        undoActions.add(UndoItemsInserted(task.pos.x, task.pos.y, task.pos.z, task.material, toInsert - leftover))
                    }
                    unrestoredItems.merge(task.material, leftover, Int::plus)
                }
            }
        }
    }

    /** 把一叠物品塞进容器：先叠加同类，再放空格。返回塞不下的数量，绝不丢地上。 */
    private fun insertIntoContainer(container: Container, stack: ItemStack): Int {
        val remaining = stack.copy()
        val slotMax = minOf(container.maxStackSize, remaining.maxStackSize)

        // 第一轮：叠加到已有同类
        for (i in 0 until container.containerSize) {
            if (remaining.isEmpty) return 0
            val slot = container.getItem(i)
            if (slot.isEmpty) continue
            // ⚠️ 1.20.1：判定「同类物品」用 `isSameItemSameTags`（比 NBT tag）；
            //    1.21.x 改叫 `isSameItemSameComponents`（比 component）。
            if (slot.count < slotMax && ItemStack.isSameItemSameTags(slot, remaining)) {
                val move = minOf(slotMax - slot.count, remaining.count)
                slot.count += move
                remaining.count -= move
            }
        }
        // 第二轮：放空格
        for (i in 0 until container.containerSize) {
            if (remaining.isEmpty) return 0
            if (container.getItem(i).isEmpty) {
                val move = minOf(slotMax, remaining.count)
                container.setItem(i, remaining.split(move))
            }
        }
        return remaining.count
    }

    // ---------------- 报告 ----------------

    private fun finishReport() {
        finished = true
        val lines = ArrayList<String>()
        lines += "===== 回滚完成（$dim）====="
        lines += "还原方块 $restoredBlocks（含容器/数据 $restoredContainers）｜清除放置 $removedBlocks｜恢复物品 $itemsRestored 组"
        // 结构感知：机器按连通体聚合展示，让服主看清整结构规模而不是逐格噪音
        val machineTotal = previewMachineComponents.sumOf { it.size }
        if (machineTotal > 0) {
            lines += if (includeMachines) {
                "机器/结构零件按整连通体回滚 $machineTotal 处（结构感知：不做半截回滚）："
            } else {
                "跳过机器/结构零件 $machineTotal 处（结构感知：不做半截回滚；要连机器一起回滚加 --include-machines）："
            }
            lines += StructureSense.summarizeComponents(previewMachineComponents, includeMachines)
        }
        val doubleTotal = previewDoubleBlockSkips.values.sum()
        if (doubleTotal > 0) {
            lines += "双格方块（门/床）缺另一半记录，整体跳过 $doubleTotal 处（结构感知：绝不回滚一半）："
            lines += previewDoubleBlockSkips.entries.take(8).map { (mat, n) -> "  $mat ×$n" }
        }
        if (runtimeMachineSkips.isNotEmpty()) {
            lines += "回滚位置现在是模组机器，跳过 ${runtimeMachineSkips.values.sum()} 处（宁可不回滚也不破坏运转中的机器）："
            lines += runtimeMachineSkips.entries.take(8).map { (id, n) -> "  $id ×$n" }
        }
        if (containerMissing > 0) lines += "容器不存在的物品恢复跳过 $containerMissing 组"
        if (unrestoredItems.isNotEmpty()) {
            lines += "容器已满未放回的物品："
            for ((mat, n) in unrestoredItems.entries.take(5)) lines += "  $mat ×$n"
        }
        if (errors > 0) lines += "异常跳过 $errors 处（详见服务端日志）"
        report = lines
    }

    private fun finish(lines: List<String>) {
        finished = true
        report = lines
    }

    private fun levelOf(server: MinecraftServer): ServerLevel? {
        for (l in server.allLevels) {
            if (l.dimension().location().toString() == dim) return l
        }
        return null
    }

    /** 拍下该位置回滚前的方块快照（空气也如实记），undo 据此恢复到「回滚前」。 */
    private fun snapshotBlock(level: ServerLevel, pos: BlockPos): UndoBlockState {
        val state = level.getBlockState(pos)
        val material = if (state.isAir) "minecraft:air" else Nbt.blockId(state)
        return UndoBlockState(pos.x, pos.y, pos.z, material, Nbt.blockEntityNbt(level, pos))
    }

    companion object {

        /**
         * 每条审计记录的处置计划（结构感知装配的中间产物）：
         * 直接生成任务 / 机器跳过（进连通体聚类）/ 双格方块待配对。
         */
        private sealed interface Plan {
            class Direct(val task: Task) : Plan
            object MachineSkip : Plan
            object DoubleHalf : Plan
        }

        /**
         * 由审计记录构造回滚任务。在查询线程调用 —— 只碰纯数据（NBT 解码、
         * 注册表只读查询），不碰世界状态；方块 / 物品的注册名解析也推迟到主线程应用期。
         *
         * 装配分三遍：① 逐条分类（[RollbackPolicy]），机器默认跳过并收集快照做连通体聚类，
         * 双格方块挂起待配对；② 双格方块成对（缺另一半的保守跳过）；
         * ③ 按记录原序装配任务，配对的两半相邻执行（同一回滚单元）。
         *
         * @param rows 查询结果（已是 id 从新到旧），只处理回滚相关的动作
         */
        fun create(
            rows: List<InspectorDb.QueryRow>,
            includeMachines: Boolean,
            machineNamespaces: Set<String>,
            blocksPerSecond: Int,
            dim: String,
        ): RollbackJob {
            val plans = ArrayList<Pair<InspectorDb.QueryRow, Plan>>()
            val machineCells = ArrayList<StructureSense.Cell>()
            for (row in rows) {
                when (row.action) {
                    Actions.BREAK -> {
                        val nbt = Nbt.decode(row.nbt)
                        val category = RollbackPolicy.classify(row.material, nbt, machineNamespaces)
                        when {
                            category == RollbackCategory.MACHINE && !includeMachines -> {
                                machineCells += StructureSense.Cell(
                                    row.world, row.x, row.y, row.z, row.material, beTypeOf(nbt), row.action,
                                )
                                plans += row to Plan.MachineSkip
                            }
                            doubleKindOf(row.material) != null -> plans += row to Plan.DoubleHalf
                            else -> plans += row to Plan.Direct(RestoreBlock(BlockPos(row.x, row.y, row.z), row.material, nbt, category))
                        }
                    }

                    Actions.PLACE, Actions.PLACE_FIRE -> {
                        // 放置类没有 NBT 快照（放置事件也存了 BE，但撤销放置只需清方块）；
                        // 机器 / 结构零件同样先跳过，避免拆掉产线上仍在运转的一部分
                        if (RollbackPolicy.classify(row.material, null, machineNamespaces) == RollbackCategory.MACHINE && !includeMachines) {
                            machineCells += StructureSense.Cell(row.world, row.x, row.y, row.z, row.material, null, row.action)
                            plans += row to Plan.MachineSkip
                        } else if (doubleKindOf(row.material) != null) {
                            plans += row to Plan.DoubleHalf
                        } else {
                            plans += row to Plan.Direct(RemoveBlock(BlockPos(row.x, row.y, row.z)))
                        }
                    }

                    Actions.CONTAINER_TAKE -> plans += row to Plan.Direct(
                        RestoreItems(BlockPos(row.x, row.y, row.z), row.material, row.amount, Nbt.decode(row.nbt))
                    )
                }
            }

            // —— 双格方块成对：两半记录都在同一批里才回滚，缺另一半保守跳过（绝不回滚一半） ——
            val doubleSkips = LinkedHashMap<String, Int>()
            val partnerOf = HashMap<Int, Int>()
            val doubleIndices = plans.indices.filter { plans[it].second is Plan.DoubleHalf }
            if (doubleIndices.isNotEmpty()) {
                val cells = doubleIndices.map { idx ->
                    val row = plans[idx].first
                    StructureSense.Cell(row.world, row.x, row.y, row.z, row.material, null, row.action)
                }
                val paired = StructureSense.pairDoubleBlocks(cells) { doubleKindOf(it) }
                for ((a, b) in paired.pairs) partnerOf[doubleIndices[a]] = doubleIndices[b]
                for (u in paired.unpaired) doubleSkips.merge(plans[doubleIndices[u]].first.material, 1, Int::plus)
            }

            // —— 按记录原序装配任务：配对的两半相邻执行（同一回滚单元），缺一半的跳过 ——
            val tasks = ArrayList<Task>()
            val emitted = BooleanArray(plans.size)
            for (i in plans.indices) {
                if (emitted[i]) continue
                when (val plan = plans[i].second) {
                    is Plan.Direct -> {
                        tasks += plan.task
                        emitted[i] = true
                    }
                    is Plan.MachineSkip -> emitted[i] = true
                    is Plan.DoubleHalf -> {
                        emitted[i] = true
                        val j = partnerOf[i] ?: continue
                        emitted[j] = true
                        for (k in listOf(i, j)) {
                            val row = plans[k].first
                            val pos = BlockPos(row.x, row.y, row.z)
                            if (row.action == Actions.BREAK) {
                                val nbt = Nbt.decode(row.nbt)
                                tasks += RestoreBlock(pos, row.material, nbt, RollbackPolicy.classify(row.material, nbt, machineNamespaces))
                            } else {
                                tasks += RemoveBlock(pos)
                            }
                        }
                    }
                }
            }

            val components = StructureSense.clusterMachines(machineCells)
            return RollbackJob(dim, includeMachines, blocksPerSecond, tasks.toList(), components, doubleSkips)
        }

        /**
         * 用 BlockState 属性判定双格方块（结构感知的注册表知识，注入给纯函数）：
         * 有 `DOUBLE_BLOCK_HALF` 属性（lower/upper）= 门（垂直两格），
         * 有 `BED_PART` 属性（head/foot）= 床（水平两格）。
         * 不硬编码方块名列表，模组自定义门 / 床只要复用原版属性就能被识别。
         */
        fun doubleKindOf(material: String): StructureSense.DoubleKind? {
            val rl = ResourceLocation.tryParse(material) ?: return null
            val block = BuiltInRegistries.BLOCK[rl] ?: return null
            val state = block.defaultBlockState()
            return when {
                state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) -> StructureSense.DoubleKind.DOOR
                state.hasProperty(BlockStateProperties.BED_PART) -> StructureSense.DoubleKind.BED
                else -> null
            }
        }

        /** 方块实体类型 id（NBT 的 `id` 字段），连通体聚类用；无则 null。 */
        private fun beTypeOf(nbt: CompoundTag?): String? {
            val id = nbt?.getString("id") ?: return null
            return id.ifBlank { null }
        }

        fun blockStateOf(id: String): BlockState? {
            val rl = ResourceLocation.tryParse(id) ?: return null
            val block = BuiltInRegistries.BLOCK[rl] ?: return null
            return block.defaultBlockState()
        }
    }
}
