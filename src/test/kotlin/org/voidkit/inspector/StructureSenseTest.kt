package org.voidkit.inspector

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 结构感知（issue #8 第 2 层）测试：双格方块成对（门垂直 / 床水平）、
 * 机器连通体聚类（6 向邻接 + 同命名空间）、跨维度断连与空输入边界。
 *
 * [StructureSense] 是纯函数层（输入方块快照列表，输出分组结果），
 * 注册表知识（材质是不是门 / 床）以 lambda 注入 —— 不起 MC 服务端即可测。
 */
class StructureSenseTest {

    /** 测试用的「注册表知识」：`test:door_*` 是门（垂直两格），`test:bed_*` 是床（水平两格）。 */
    private val kindOf: (String) -> StructureSense.DoubleKind? = { material ->
        when {
            material.startsWith("test:door") -> StructureSense.DoubleKind.DOOR
            material.startsWith("test:bed") -> StructureSense.DoubleKind.BED
            else -> null
        }
    }

    private fun cell(
        x: Int,
        y: Int = 64,
        z: Int = 0,
        material: String,
        world: String = "minecraft:overworld",
        beType: String? = null,
        action: Int = Actions.BREAK,
    ) = StructureSense.Cell(world, x, y, z, material, beType, action)

    // ---------- 双格方块成对 ----------

    @Test
    fun `门上下两半配成一对`() {
        val cells = listOf(
            cell(0, 64, 0, "test:door_oak"),
            cell(0, 65, 0, "test:door_oak"),
        )
        val r = StructureSense.pairDoubleBlocks(cells, kindOf)
        assertEquals(1, r.pairs.size)
        assertEquals(listOf(0 to 1), r.pairs)
        assertTrue(r.unpaired.isEmpty())
    }

    @Test
    fun `门只记录一半时保守不配对`() {
        // 破坏门往往只留下半的记录（原版连带摧毁另一半不触发事件）——缺另一半必须跳过
        val cells = listOf(cell(0, 64, 0, "test:door_oak"))
        val r = StructureSense.pairDoubleBlocks(cells, kindOf)
        assertTrue(r.pairs.isEmpty())
        assertEquals(listOf(0), r.unpaired)
    }

    @Test
    fun `床水平相邻配对而垂直相邻不配对`() {
        val horizontal = listOf(
            cell(0, 64, 0, "test:bed_white"),  // 床尾
            cell(1, 64, 0, "test:bed_white"),  // 床头
        )
        val h = StructureSense.pairDoubleBlocks(horizontal, kindOf)
        assertEquals(1, h.pairs.size)
        assertTrue(h.unpaired.isEmpty())

        // 两张床上下叠放：不是床的成对方向，各自按缺另一半处理
        val vertical = listOf(
            cell(0, 64, 0, "test:bed_white"),
            cell(0, 65, 0, "test:bed_white"),
        )
        val v = StructureSense.pairDoubleBlocks(vertical, kindOf)
        assertTrue(v.pairs.isEmpty())
        assertEquals(setOf(0, 1), v.unpaired.toSet())
    }

    @Test
    fun `不同材质的同种双格方块不配对`() {
        val cells = listOf(
            cell(0, 64, 0, "test:door_oak"),
            cell(0, 65, 0, "test:door_spruce"),
        )
        val r = StructureSense.pairDoubleBlocks(cells, kindOf)
        assertTrue(r.pairs.isEmpty())
        assertEquals(setOf(0, 1), r.unpaired.toSet())
    }

    @Test
    fun `跨维度的两半不配对`() {
        val cells = listOf(
            cell(0, 64, 0, "test:door_oak", world = "minecraft:overworld"),
            cell(0, 65, 0, "test:door_oak", world = "minecraft:the_nether"),
        )
        val r = StructureSense.pairDoubleBlocks(cells, kindOf)
        assertTrue(r.pairs.isEmpty())
        assertEquals(setOf(0, 1), r.unpaired.toSet())
    }

    @Test
    fun `双格配对要求两半是同一动作`() {
        // 一半是破坏记录、一半是放置记录：不是同一个回滚单元，不配对
        val cells = listOf(
            cell(0, 64, 0, "test:door_oak", action = Actions.BREAK),
            cell(0, 65, 0, "test:door_oak", action = Actions.PLACE),
        )
        val r = StructureSense.pairDoubleBlocks(cells, kindOf)
        assertTrue(r.pairs.isEmpty())
        assertEquals(setOf(0, 1), r.unpaired.toSet())
    }

    @Test
    fun `三扇门相邻时逐对配对不串门`() {
        // 两扇并排的门：各自上下配对；多余的一半保守跳过
        val cells = listOf(
            cell(0, 64, 0, "test:door_oak"),
            cell(0, 65, 0, "test:door_oak"),
            cell(1, 64, 0, "test:door_oak"),
            cell(1, 65, 0, "test:door_oak"),
        )
        val r = StructureSense.pairDoubleBlocks(cells, kindOf)
        assertEquals(2, r.pairs.size)
        assertTrue(r.unpaired.isEmpty())
        // 配对的两半都在同一列
        for ((a, b) in r.pairs) assertEquals(cells[a].x, cells[b].x)
    }

    @Test
    fun `空输入配对返回空结果`() {
        val r = StructureSense.pairDoubleBlocks(emptyList(), kindOf)
        assertTrue(r.pairs.isEmpty())
        assertTrue(r.unpaired.isEmpty())
    }

    // ---------- 机器连通体 ----------

    @Test
    fun `相邻同命名空间的机器聚成一个连通体`() {
        val cells = listOf(
            cell(0, 64, 0, "create:cogwheel"),
            cell(1, 64, 0, "create:cogwheel"),
            cell(1, 65, 0, "create:belt"),  // 垂直方向也连通
        )
        val comps = StructureSense.clusterMachines(cells)
        assertEquals(1, comps.size)
        assertEquals(3, comps[0].size)
        assertEquals("create", comps[0].namespace)
    }

    @Test
    fun `相邻但不同命名空间的机器不连通`() {
        val cells = listOf(
            cell(0, 64, 0, "create:cogwheel"),
            cell(1, 64, 0, "mekanism:cable_basic"),
        )
        val comps = StructureSense.clusterMachines(cells)
        assertEquals(2, comps.size)
        assertTrue(comps.all { it.size == 1 })
    }

    @Test
    fun `同方块实体类型的相邻机器跨命名空间连通`() {
        val cells = listOf(
            cell(0, 64, 0, "create:cogwheel", beType = "create:axle"),
            cell(1, 64, 0, "create_new_age:axle", beType = "create:axle"),
        )
        val comps = StructureSense.clusterMachines(cells)
        assertEquals(1, comps.size)
        assertEquals(2, comps[0].size)
    }

    @Test
    fun `间隔超过一格的机器分成多个连通体`() {
        val cells = listOf(
            cell(0, 64, 0, "create:cogwheel"),
            cell(2, 64, 0, "create:cogwheel"),  // 中间隔一格空气
        )
        val comps = StructureSense.clusterMachines(cells)
        assertEquals(2, comps.size)
    }

    @Test
    fun `跨维度的机器各自成连通体`() {
        val cells = listOf(
            cell(0, 64, 0, "create:cogwheel", world = "minecraft:overworld"),
            cell(0, 64, 0, "create:cogwheel", world = "minecraft:the_nether"),
        )
        val comps = StructureSense.clusterMachines(cells)
        assertEquals(2, comps.size)
        assertEquals(setOf("minecraft:overworld", "minecraft:the_nether"), comps.map { it.world }.toSet())
    }

    @Test
    fun `空输入聚类返回空列表`() {
        assertTrue(StructureSense.clusterMachines(emptyList()).isEmpty())
    }

    // ---------- 报告聚合 ----------

    @Test
    fun `连通体按命名空间聚合输出摘要`() {
        val cells = listOf(
            cell(0, 64, 0, "create:cogwheel"),
            cell(1, 64, 0, "create:cogwheel"),
            cell(0, 64, 1, "create:cogwheel"),  // 与前两个都相邻 → 一个 3 格连通体
            cell(10, 64, 10, "create:belt"),
            cell(11, 64, 10, "create:belt"),    // 另一个 2 格连通体
        )
        val comps = StructureSense.clusterMachines(cells)
        assertEquals(2, comps.size)
        val lines = StructureSense.summarizeComponents(comps, included = false)
        assertEquals(listOf("create 连通体 ×2（共 5 格，已整体跳过）"), lines)
    }

    @Test
    fun `includeMachines 场景的摘要措辞不同`() {
        val comps = listOf(
            StructureSense.MachineComponent("minecraft:overworld", "create", listOf(cell(0, material = "create:cogwheel"))),
        )
        val lines = StructureSense.summarizeComponents(comps, included = true)
        assertEquals(listOf("create 连通体 ×1（共 1 格，整连通体回滚）"), lines)
    }
}
