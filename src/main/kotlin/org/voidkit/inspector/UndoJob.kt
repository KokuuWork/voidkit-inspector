package org.voidkit.inspector

import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.Container
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.entity.BlockEntity

/**
 * 回滚的撤销（undo）执行器 —— issue #8 五层设计中的第 5 层。
 *
 * ## 撤销什么
 *
 * [UndoJob] 的任务清单来自 journal 里记录的「回滚前原始状态」，
 * 按**原 job 应用顺序的倒序**逐个恢复 —— 回滚先做了什么，撤销就先撤什么，
 * 与回滚自身的「倒序撤销」原则同构。
 *
 * ## 与 RollbackJob 的关系
 *
 * 同样走分帧（每秒 [blocksPerSecond] 个操作，防雪崩），实现同一个 [SteppedJob]
 * 接口，[InspectorFeature] 的推进逻辑完全复用。任务构造期只碰纯数据
 * （journal 反序列化），方块 / 物品注册名解析推迟到主线程应用期。
 *
 * ## 保守原则不变
 *
 * - 容器物品撤销只做「把塞回去的取出来」，取不出来的如实报告，绝不丢到地上；
 * - 机器容器遵循回滚同样的命名空间规则（journal 里存了 includeMachines 标记）；
 * - 方块已不在注册表（整合包换版本）：跳过并计数，绝不硬塞。
 */
class UndoJob private constructor(
    private val dim: String,
    private val includeMachines: Boolean,
    private val blocksPerSecond: Int,
    /** 撤销任务，已按原 job 应用顺序的倒序排列。 */
    internal val tasks: List<Task>,
) : SteppedJob {

    /** 单个撤销动作，由 journal 里的 [UndoAction] 反向映射而来。 */
    internal sealed interface Task

    /** 把该位置恢复成回滚前的方块（material 为 air 表示当时是空气）。坐标用标量存，应用期才构造 BlockPos。 */
    internal class UndoBlockTask(val x: Int, val y: Int, val z: Int, val material: String, val nbt: ByteArray?) : Task {
        internal val pos: BlockPos get() = BlockPos(x, y, z)
    }

    /** 把回滚塞进容器的物品再取出来。 */
    internal class UndoTakeItemsTask(val x: Int, val y: Int, val z: Int, val material: String, val amount: Int) : Task {
        internal val pos: BlockPos get() = BlockPos(x, y, z)
    }

    // ---------------- 统计（主线程写） ----------------

    private var restoredBlocks = 0
    private var restoredContainers = 0
    private var removedBlocks = 0
    private var itemsTaken = 0

    /** 未能取走的物品：material -> 数量（容器没了 / 已被取走 / 容器满了的反向——物品不在了）。 */
    private val unTakenItems = LinkedHashMap<String, Int>()

    private val runtimeMachineSkips = LinkedHashMap<String, Int>()
    private var containerMissing = 0
    private var errors = 0

    private var idx = 0
    private var finished = false
    private var report: List<String>? = null

    override val isDone: Boolean get() = finished

    /** 每秒由 [InspectorFeature.onSecond] 调一次，在主线程上分批应用任务。 */
    override fun tick(server: MinecraftServer) {
        if (finished) return
        val level = levelOf(server)
        if (level == null) {
            finish(listOf("撤销中止：维度 $dim 当前不可用"))
            return
        }
        var budget = blocksPerSecond
        while (budget > 0 && idx < tasks.size) {
            apply(level, tasks[idx++])
            budget--
        }
        if (idx >= tasks.size) finishReport()
    }

    override fun pollReport(): List<String>? {
        val r = report
        report = null
        return r
    }

    // ---------------- 任务应用（主线程） ----------------

    private fun apply(level: ServerLevel, task: Task) {
        when (task) {
            is UndoBlockTask -> {
                if (task.material == AIR) {
                    level.setBlock(task.pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3)
                    removedBlocks++
                    return
                }
                val state = RollbackJob.blockStateOf(task.material)
                if (state == null) {
                    // 方块已不在注册表：跳过并计数，绝不硬塞
                    errors++
                    return
                }
                level.setBlock(task.pos, state, 3)
                restoredBlocks++
                val nbt = Nbt.decode(task.nbt)
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

            is UndoTakeItemsTask -> {
                val be = level.getBlockEntity(task.pos)
                if (be !is Container) {
                    containerMissing++
                    unTakenItems.merge(task.material, task.amount, Int::plus)
                    return
                }
                val beKey = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.type)?.toString() ?: "unknown"
                val beNs = beKey.substringBefore(':')
                if (beNs != "minecraft" && !includeMachines) {
                    runtimeMachineSkips.merge(beKey, 1, Int::plus)
                    unTakenItems.merge(task.material, task.amount, Int::plus)
                    return
                }
                // 按物品注册名取走最多 amount 个（撤销回滚的「塞回去」），绝不丢到地上
                var remaining = task.amount
                for (i in 0 until be.containerSize) {
                    if (remaining <= 0) break
                    val slot = be.getItem(i)
                    if (slot.isEmpty) continue
                    if (Nbt.itemId(slot) == task.material) {
                        val take = minOf(remaining, slot.count)
                        slot.shrink(take)
                        if (slot.isEmpty) be.setItem(i, ItemStack.EMPTY)
                        remaining -= take
                    }
                }
                if (remaining == 0) {
                    itemsTaken++
                } else {
                    val taken = task.amount - remaining
                    if (taken > 0) itemsTaken++
                    unTakenItems.merge(task.material, remaining, Int::plus)
                }
            }
        }
    }

    // ---------------- 报告 ----------------

    private fun finishReport() {
        finished = true
        val lines = ArrayList<String>()
        lines += "===== 撤销完成（$dim）====="
        lines += "还原方块 $restoredBlocks（含容器/数据 $restoredContainers）｜移除放置 $removedBlocks｜取回物品 $itemsTaken 组"
        val machineTotal = runtimeMachineSkips.values.sum()
        if (machineTotal > 0) {
            lines += "跳过机器容器 $machineTotal 处（撤销不越过机器命名空间保护）："
            for ((id, n) in runtimeMachineSkips.entries.take(8)) lines += "  $id ×$n"
        }
        if (containerMissing > 0) lines += "容器不存在的物品取回跳过 $containerMissing 组"
        if (unTakenItems.isNotEmpty()) {
            lines += "未能取回的物品（已被移走 / 容器已不在）："
            for ((mat, n) in unTakenItems.entries.take(5)) lines += "  $mat ×$n"
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

        private const val AIR = "minecraft:air"

        /**
         * 由 journal 记录构造撤销任务。纯数据操作（倒序 + 标量坐标），
         * 可在查询线程调用；BlockPos 与注册名解析推迟到主线程应用期。
         */
        fun create(entry: JournalEntry, blocksPerSecond: Int): UndoJob {
            // 撤销 = 原应用顺序的倒序
            val tasks = entry.actions.reversed().map { a ->
                when (a) {
                    is UndoBlockState -> UndoBlockTask(a.x, a.y, a.z, a.material, a.nbt)
                    is UndoItemsInserted -> UndoTakeItemsTask(a.x, a.y, a.z, a.material, a.amount)
                }
            }
            return UndoJob(entry.dim, entry.includeMachines, blocksPerSecond, tasks)
        }
    }
}
