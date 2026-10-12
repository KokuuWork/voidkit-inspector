package org.voidkit.inspector

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 因果链聚类逻辑测试：时间断链、空间断链、正常连链、维度断链与统计摘要。
 *
 * [InspectorDb.QueryRow] 与 [ChainCluster] 都是纯数据，不起 MC 服务端。
 */
class ChainClusterTest {

    private val MIN = 60_000L

    private fun row(
        id: Long,
        time: Long,
        x: Int,
        y: Int = 64,
        z: Int = 0,
        action: Int = Actions.BREAK,
        world: String = "minecraft:overworld",
        actor: String = "Steve",
        material: String = "minecraft:stone",
    ) = InspectorDb.QueryRow(
        id, time, world, x, y, z, actor, null, SourceKind.PLAYER.id,
        action, material, 1, null,
    )

    // ---------- 正常连链 ----------

    @Test
    fun `时间连续且位置邻近的操作连成一条链`() {
        val t0 = 1_700_000_000_000L
        val rows = listOf(
            row(1, t0, x = 0, z = 0),
            row(2, t0 + 30_000, x = 3, z = 2),
            row(3, t0 + 90_000, x = 8, z = 5),
            row(4, t0 + 120_000, x = 10, z = 8),
        )
        val chains = ChainCluster.cluster(rows, idleMinutes = 5, radiusBlocks = 32)
        assertEquals(1, chains.size)
        val c = chains[0]
        assertEquals(1, c.id)
        assertEquals(4, c.eventCount)
        assertEquals(t0, c.startTime)
        assertEquals(t0 + 120_000, c.endTime)
        assertEquals(setOf("Steve"), c.players)
        assertEquals(4, c.blockCount)
    }

    @Test
    fun `乱序输入按时间重排后聚类`() {
        val t0 = 1_700_000_000_000L
        // 查询结果按 id 从新到旧返回：故意倒序传入
        val rows = listOf(
            row(3, t0 + 90_000, x = 8),
            row(1, t0, x = 0),
            row(2, t0 + 30_000, x = 3),
        )
        val chains = ChainCluster.cluster(rows, idleMinutes = 5, radiusBlocks = 32)
        assertEquals(1, chains.size)
        assertEquals(t0, chains[0].startTime)
        assertEquals(t0 + 90_000, chains[0].endTime)
    }

    // ---------- 时间断链 ----------

    @Test
    fun `超过空闲阈值的间隔断链`() {
        val t0 = 1_700_000_000_000L
        val rows = listOf(
            row(1, t0, x = 0),
            row(2, t0 + 4 * MIN, x = 1),      // 4 分钟：还在 5 分钟内，同链
            row(3, t0 + 10 * MIN, x = 2),     // 距上一条 6 分钟：断链
            row(4, t0 + 12 * MIN, x = 3),     // 2 分钟：接在第 2 条链上
        )
        val chains = ChainCluster.cluster(rows, idleMinutes = 5, radiusBlocks = 32)
        assertEquals(2, chains.size)
        assertEquals(2, chains[0].eventCount)
        assertEquals(2, chains[1].eventCount)
        assertEquals(1, chains[0].id)
        assertEquals(2, chains[1].id)
    }

    @Test
    fun `恰好等于阈值不断链`() {
        val t0 = 1_700_000_000_000L
        val rows = listOf(
            row(1, t0, x = 0),
            row(2, t0 + 5 * MIN, x = 1), // 间隔 == idleMinutes：不算超时
        )
        val chains = ChainCluster.cluster(rows, idleMinutes = 5, radiusBlocks = 32)
        assertEquals(1, chains.size)
    }

    // ---------- 空间断链 ----------

    @Test
    fun `超出空间半径断链`() {
        val t0 = 1_700_000_000_000L
        val rows = listOf(
            row(1, t0, x = 0, z = 0),
            row(2, t0 + MIN, x = 20, z = 20),   // 距链最近事件 ~28 格：同链
            row(3, t0 + 2 * MIN, x = 20, z = 100), // 距链最近事件 80 格：断链
            row(4, t0 + 3 * MIN, x = 19, z = 98),  // 接在第 2 条链上
        )
        val chains = ChainCluster.cluster(rows, idleMinutes = 5, radiusBlocks = 32)
        assertEquals(2, chains.size)
        assertEquals(2, chains[0].eventCount)
        assertEquals(2, chains[1].eventCount)
    }

    @Test
    fun `与链上较远处的历史事件邻近仍算同链`() {
        val t0 = 1_700_000_000_000L
        val rows = listOf(
            row(1, t0, x = 0, z = 0),
            row(2, t0 + MIN, x = 30, z = 0),       // 半径边缘
            row(3, t0 + 2 * MIN, x = 60, z = 0),   // 距 #2 30 格：同链（与 #1 远）
            row(4, t0 + 3 * MIN, x = 2, z = 0),    // 回到起点附近：与 #1 距离 2 格，同链
        )
        val chains = ChainCluster.cluster(rows, idleMinutes = 5, radiusBlocks = 32)
        assertEquals(1, chains.size)
        assertEquals(4, chains[0].eventCount)
    }

    // ---------- 维度断链 ----------

    @Test
    fun `跨维度断链`() {
        val t0 = 1_700_000_000_000L
        val rows = listOf(
            row(1, t0, x = 0, world = "minecraft:overworld"),
            row(2, t0 + MIN, x = 0, world = "minecraft:the_nether"),
        )
        val chains = ChainCluster.cluster(rows, idleMinutes = 5, radiusBlocks = 32)
        assertEquals(2, chains.size)
        assertEquals("minecraft:overworld", chains[0].world)
        assertEquals("minecraft:the_nether", chains[1].world)
    }

    // ---------- 统计与摘要 ----------

    @Test
    fun `摘要按固定顺序列出非零动作`() {
        val t0 = 1_700_000_000_000L
        val rows = listOf(
            row(1, t0, x = 0, action = Actions.CONTAINER_TAKE, material = "minecraft:diamond"),
            row(2, t0 + 1000, x = 1, action = Actions.CONTAINER_TAKE, material = "minecraft:diamond"),
            row(3, t0 + 2000, x = 2, action = Actions.CONTAINER_TAKE, material = "minecraft:diamond"),
            row(4, t0 + 3000, x = 3, action = Actions.BREAK),
            row(5, t0 + 4000, x = 4, action = Actions.PLACE, material = "minecraft:tnt"),
            row(6, t0 + 5000, x = 5, action = Actions.BREAK),
            row(7, t0 + 6000, x = 6, action = Actions.PLACE_FIRE, material = "minecraft:fire"),
        )
        val c = ChainCluster.cluster(rows, idleMinutes = 5, radiusBlocks = 32).single()
        assertEquals("3 容器拿取 · 2 方块破坏 · 1 放置 · 1 点火", c.summary())
        assertEquals(7, c.eventCount)
        assertEquals(7, c.blockCount)
        assertEquals(3, c.centerX)
        assertEquals(0, c.centerZ)
    }

    @Test
    fun `同坐标的多次操作只算一个方块`() {
        val t0 = 1_700_000_000_000L
        val rows = listOf(
            row(1, t0, x = 5, z = 5, action = Actions.PLACE, material = "minecraft:tnt"),
            row(2, t0 + 1000, x = 5, z = 5, action = Actions.PLACE_FIRE, material = "minecraft:fire"),
            row(3, t0 + 2000, x = 5, z = 5, action = Actions.BREAK),
        )
        val c = ChainCluster.cluster(rows, idleMinutes = 5, radiusBlocks = 32).single()
        assertEquals(3, c.eventCount)
        assertEquals(1, c.blockCount)
    }

    @Test
    fun `空输入返回空列表`() {
        assertTrue(ChainCluster.cluster(emptyList(), idleMinutes = 5, radiusBlocks = 32).isEmpty())
    }
}
