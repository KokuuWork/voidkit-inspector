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

/**
 * 智能回滚执行器 —— issue #8 五层设计中的第 1 层（分类感知）v1 落地。
 *
 * ## 设计要点
 *
 * - **先分类，再动手**：所有任务在构造时（查询线程）就按 [RollbackPolicy] 分类完毕，
 *   机器 / 结构零件默认跳过并计数，服主可用 `--include-machines` 显式覆盖。
 * - **分帧执行（防雪崩）**：回滚按每秒 [blocksPerSecond] 个操作分批落到主线程，
 *   大范围回滚不会一 tick 烧掉 TPS。
 * - **倒序撤销**：任务按审计 id 从新到旧排列，后发生的变化先被撤销，
 *   「放机器 → 拆机器」这类闭环操作能自然抵消，不会留下幽灵方块。
 * - **保守优先（不出问题）**：容器物品恢复只做「把拿走的塞回去」，塞不下的如实报告，
 *   绝不丢到地上（防复制 / 防卡实体）；机器一律先跳过；绝不自动封禁、绝不删除任何东西。
 *
 * v1 范围（Part of #8）：方块破坏 / 放置还原、原版容器 NBT 恢复、被拿取物品塞回。
 * 因果链聚类、整结构回滚、回滚的撤销（undo）留作后续部分。
 */
class RollbackJob private constructor(
    private val dim: String,
    private val includeMachines: Boolean,
    private val blocksPerSecond: Int,
    private val tasks: List<Task>,
    /** 构造期（分类阶段）被跳过的机器：material -> 次数。 */
    private val createMachineSkips: Map<String, Int>,
) {

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

    // ---------------- 预览统计（构造期算好，线程安全只读） ----------------

    /** 分类计数，供 preview 命令展示。 */
    val previewNormal: Int
    val previewNbt: Int
    val previewRemove: Int
    val previewItems: Int

    /** 构造期因分类被跳过的机器：material -> 次数（只读快照，线程安全）。 */
    val previewMachineSkips: Map<String, Int> = createMachineSkips

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
    val isDone: Boolean get() = finished

    /**
     * 每秒由 [InspectorFeature.onSecond] 调一次，在主线程上分批应用任务。
     */
    fun tick(server: MinecraftServer) {
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
    fun pollReport(): List<String>? {
        val r = report
        report = null
        return r
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
                level.setBlock(task.pos, state, 3)
                restoredBlocks++
                val nbt = task.nbt
                if (nbt != null && state.hasBlockEntity()) {
                    val be = BlockEntity.loadStatic(task.pos, state, nbt, level.registryAccess())
                    if (be != null) {
                        level.setBlockEntity(be)
                        restoredContainers++
                    } else {
                        errors++
                    }
                }
            }

            is RemoveBlock -> {
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
                val stack = task.itemTag?.let { ItemStack.parse(level.registryAccess(), it).orElse(null) }
                if (stack == null) {
                    errors++
                    return
                }
                // diff 记录的 nbt 是当时整组物品的快照，count 可能大于实际拿走的数量，以 amount 为准
                stack.count = task.amount.coerceAtLeast(1)
                val leftover = insertIntoContainer(be, stack)
                if (leftover <= 0) {
                    itemsRestored++
                } else {
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
            if (slot.count < slotMax && ItemStack.isSameItemSameComponents(slot, remaining)) {
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
        val machineLines = ArrayList<String>()
        for ((id, n) in previewMachineSkips) machineLines += "  $id ×$n"
        for ((id, n) in runtimeMachineSkips) machineLines += "  $id ×$n"
        val machineTotal = previewMachineSkips.values.sum() + runtimeMachineSkips.values.sum()
        if (machineTotal > 0) {
            lines += "跳过机器/结构零件 $machineTotal 处（多方块结构与网络不做半截回滚；要连机器一起回滚加 --include-machines）"
            lines += machineLines.take(8)
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

    companion object {

        /**
         * 由审计记录构造回滚任务。在查询线程调用 —— 只碰纯数据（NBT 解码），
         * 不碰世界状态；方块 / 物品的注册名解析也推迟到主线程应用期。
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
            val tasks = ArrayList<Task>()
            val machineSkips = LinkedHashMap<String, Int>()
            for (row in rows) {
                val pos = BlockPos(row.x, row.y, row.z)
                when (row.action) {
                    Actions.BREAK -> {
                        val nbt = Nbt.decode(row.nbt)
                        val category = RollbackPolicy.classify(row.material, nbt, machineNamespaces)
                        if (category == RollbackCategory.MACHINE && !includeMachines) {
                            machineSkips.merge(row.material, 1, Int::plus)
                        } else {
                            tasks.add(RestoreBlock(pos, row.material, nbt, category))
                        }
                    }

                    Actions.PLACE, Actions.PLACE_FIRE -> {
                        // 放置类没有 NBT 快照（放置事件也存了 BE，但撤销放置只需清方块）；
                        // 机器 / 结构零件同样先跳过，避免拆掉产线上仍在运转的一部分
                        if (RollbackPolicy.classify(row.material, null, machineNamespaces) == RollbackCategory.MACHINE && !includeMachines) {
                            machineSkips.merge(row.material, 1, Int::plus)
                        } else {
                            tasks.add(RemoveBlock(pos))
                        }
                    }

                    Actions.CONTAINER_TAKE -> tasks.add(RestoreItems(pos, row.material, row.amount, Nbt.decode(row.nbt)))
                }
            }
            return RollbackJob(dim, includeMachines, blocksPerSecond, tasks.toList(), machineSkips)
        }

        fun blockStateOf(id: String): BlockState? {
            val rl = ResourceLocation.tryParse(id) ?: return null
            val block = BuiltInRegistries.BLOCK[rl] ?: return null
            return block.defaultBlockState()
        }
    }
}
