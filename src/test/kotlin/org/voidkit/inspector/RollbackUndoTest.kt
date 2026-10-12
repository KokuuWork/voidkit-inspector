package org.voidkit.inspector

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * 回滚撤销（undo）测试：journal 编解码、落库/读取、undo 倒序、
 * 时间窗拒绝与重复 undo 拒绝。
 *
 * [RollbackJournal] 是纯 SQLite 存储层（不依赖 Minecraft 运行时），
 * [UndoJob.create] 只做纯数据倒序 —— 都可以直接在单测里跑。
 */
class RollbackUndoTest {

    @TempDir
    lateinit var tmp: Path

    private val MIN = 60_000L

    private fun block(x: Int, y: Int = 64, z: Int = 0, material: String = "minecraft:stone", nbt: ByteArray? = null) =
        UndoBlockState(x, y, z, material, nbt)

    private fun items(x: Int, y: Int = 64, z: Int = 0, material: String = "minecraft:diamond", amount: Int = 3) =
        UndoItemsInserted(x, y, z, material, amount)

    // ---------- journal 编解码 ----------

    @Test
    fun `journal 编解码往返保留全部动作`() {
        val actions = listOf(
            block(1, 64, 2, "minecraft:chest", byteArrayOf(1, 2, 3)),
            block(3, 65, 4, "minecraft:air", null),
            items(5, 66, 6, "minecraft:diamond", 12),
        )
        val decoded = UndoCodec.decode(UndoCodec.encode(actions))
        assertEquals(3, decoded.size)

        val b1 = decoded[0] as UndoBlockState
        assertEquals(1, b1.x); assertEquals(64, b1.y); assertEquals(2, b1.z)
        assertEquals("minecraft:chest", b1.material)
        assertTrue(b1.nbt!!.contentEquals(byteArrayOf(1, 2, 3)))

        val b2 = decoded[1] as UndoBlockState
        assertEquals("minecraft:air", b2.material)
        assertEquals(null, b2.nbt)

        val i = decoded[2] as UndoItemsInserted
        assertEquals(5, i.x); assertEquals(66, i.y); assertEquals(6, i.z)
        assertEquals("minecraft:diamond", i.material)
        assertEquals(12, i.amount)
    }

    @Test
    fun `解码空与损坏数据返回空列表`() {
        assertTrue(UndoCodec.decode(null).isEmpty())
        assertTrue(UndoCodec.decode(ByteArray(0)).isEmpty())
        assertTrue(UndoCodec.decode(byteArrayOf(9, 9, 9)).isEmpty())
    }

    // ---------- journal 落库 / 读取 ----------

    private fun openJournal(): RollbackJournal = RollbackJournal(tmp.resolve("journal.db")).also { it.open() }

    @Test
    fun `落库后缺省读取最近一条未撤销记录`() {
        val journal = openJournal()
        try {
            val now = System.currentTimeMillis()
            val id1 = journal.saveAsync(
                now, "Admin", "minecraft:overworld", "rollback u:Grief", false,
                0, 64, 0, listOf(block(0), items(2)),
            ).get()
            val id2 = journal.saveAsync(
                now, "Admin", "minecraft:overworld", "rollback u:Grief2", true,
                5, 64, 5, listOf(block(5)),
            ).get()
            assertTrue(id2 > id1)

            val prep = journal.prepareUndoAsync(null, windowMs = 30 * MIN).get()
            assertTrue(prep is UndoPreparation.Ok)
            val entry = (prep as UndoPreparation.Ok).entry
            assertEquals(id2, entry.id)
            assertEquals(now, entry.createdAt)
            assertEquals("Admin", entry.actor)
            assertEquals("rollback u:Grief2", entry.label)
            assertTrue(entry.includeMachines)
            assertFalse(entry.undone)
            assertEquals(1, entry.actions.size)
            assertEquals("minecraft:stone", (entry.actions[0] as UndoBlockState).material)
        } finally {
            journal.close()
        }
    }

    @Test
    fun `按 jobId 读取指定记录`() {
        val journal = openJournal()
        try {
            val id = journal.saveAsync(
                System.currentTimeMillis(), "Admin", "minecraft:the_nether", "chain:Grief:3", false,
                1, 2, 3, listOf(block(1, 64, 2)),
            ).get()
            val prep = journal.prepareUndoAsync(id, windowMs = 30 * MIN).get()
            val entry = (prep as UndoPreparation.Ok).entry
            assertEquals("minecraft:the_nether", entry.dim)
            assertEquals("chain:Grief:3", entry.label)
        } finally {
            journal.close()
        }
    }

    @Test
    fun `没有可撤销记录时返回 NotFound`() {
        val journal = openJournal()
        try {
            assertTrue(journal.prepareUndoAsync(null, 30 * MIN).get() is UndoPreparation.NotFound)
            assertTrue(journal.prepareUndoAsync(999L, 30 * MIN).get() is UndoPreparation.NotFound)
        } finally {
            journal.close()
        }
    }

    // ---------- 时间窗 ----------

    @Test
    fun `超过撤销时间窗拒绝`() {
        val journal = openJournal()
        try {
            val id = journal.saveAsync(
                1_000L, "Admin", "minecraft:overworld", "old", false,
                0, 64, 0, listOf(block(0)),
            ).get()
            val prep = journal.prepareUndoAsync(id, windowMs = 30 * MIN, nowMs = 1_000L + 30 * MIN + 1).get()
            assertTrue(prep is UndoPreparation.WindowExpired)
            // 超窗拒绝不能误标记：记录仍处于未撤销状态
            assertFalse((prep as UndoPreparation.WindowExpired).entry.undone)
        } finally {
            journal.close()
        }
    }

    @Test
    fun `恰好等于时间窗不拒绝`() {
        val journal = openJournal()
        try {
            val id = journal.saveAsync(
                1_000L, "Admin", "minecraft:overworld", "edge", false,
                0, 64, 0, listOf(block(0)),
            ).get()
            val prep = journal.prepareUndoAsync(id, windowMs = 30 * MIN, nowMs = 1_000L + 30 * MIN).get()
            assertTrue(prep is UndoPreparation.Ok)
        } finally {
            journal.close()
        }
    }

    // ---------- 重复 undo ----------

    @Test
    fun `同一回滚第二次 undo 被拒绝`() {
        val journal = openJournal()
        try {
            val id = journal.saveAsync(
                System.currentTimeMillis(), "Admin", "minecraft:overworld", "rollback", false,
                0, 64, 0, listOf(block(0), items(1)),
            ).get()
            assertTrue(journal.prepareUndoAsync(id, 30 * MIN).get() is UndoPreparation.Ok)
            val second = journal.prepareUndoAsync(id, 30 * MIN).get()
            assertTrue(second is UndoPreparation.AlreadyUndone)
            // 缺省路径也拿不到已撤销的记录了
            assertTrue(journal.prepareUndoAsync(null, 30 * MIN).get() is UndoPreparation.NotFound)
        } finally {
            journal.close()
        }
    }

    @Test
    fun `释放标记后仍可撤销`() {
        val journal = openJournal()
        try {
            val id = journal.saveAsync(
                System.currentTimeMillis(), "Admin", "minecraft:overworld", "rollback", false,
                0, 64, 0, listOf(block(0)),
            ).get()
            assertTrue(journal.prepareUndoAsync(id, 30 * MIN).get() is UndoPreparation.Ok)
            journal.releaseUndoAsync(id).get()
            assertTrue(journal.prepareUndoAsync(id, 30 * MIN).get() is UndoPreparation.Ok)
        } finally {
            journal.close()
        }
    }

    @Test
    fun `撤销标记跨连接持久`() {
        val path = tmp.resolve("journal-persist.db")
        val journal = RollbackJournal(path).also { it.open() }
        val id = journal.saveAsync(
            System.currentTimeMillis(), "Admin", "minecraft:overworld", "rollback", false,
            0, 64, 0, listOf(block(0)),
        ).get()
        assertTrue(journal.prepareUndoAsync(id, 30 * MIN).get() is UndoPreparation.Ok)
        journal.close()
        // 模拟重启：重新打开后该记录仍是已撤销状态
        val journal2 = RollbackJournal(path).also { it.open() }
        try {
            assertTrue(journal2.prepareUndoAsync(id, 30 * MIN).get() is UndoPreparation.AlreadyUndone)
        } finally {
            journal2.close()
        }
    }

    // ---------- undo 倒序 ----------

    @Test
    fun `undo 任务按原应用顺序的倒序排列`() {
        val entry = JournalEntry(
            id = 1, createdAt = System.currentTimeMillis(), actor = "Admin",
            dim = "minecraft:overworld", label = "rollback", includeMachines = false, undone = false,
            actions = listOf(
                block(1, 64, 1, "minecraft:chest", byteArrayOf(1)),
                items(2, 64, 2, "minecraft:diamond", 5),
                block(3, 64, 3, "minecraft:air", null),
            ),
        )
        val job = UndoJob.create(entry, blocksPerSecond = 100)
        assertEquals(3, job.tasks.size)
        // 原顺序 block(1) → items(2) → block(3)，撤销应反过来
        val t0 = job.tasks[0] as UndoJob.UndoBlockTask
        assertEquals(3, t0.x)
        assertEquals("minecraft:air", t0.material)
        val t1 = job.tasks[1] as UndoJob.UndoTakeItemsTask
        assertEquals(2, t1.x)
        assertEquals("minecraft:diamond", t1.material)
        assertEquals(5, t1.amount)
        val t2 = job.tasks[2] as UndoJob.UndoBlockTask
        assertEquals(1, t2.x)
        assertEquals("minecraft:chest", t2.material)
        assertTrue(t2.nbt!!.contentEquals(byteArrayOf(1)))
    }

    @Test
    fun `undo 继承 journal 的机器标记与维度`() {
        val entry = JournalEntry(
            id = 2, createdAt = System.currentTimeMillis(), actor = "Admin",
            dim = "minecraft:the_end", label = "rollback", includeMachines = true, undone = false,
            actions = listOf(items(0, 64, 0, "minecraft:diamond", 1)),
        )
        val job = UndoJob.create(entry, blocksPerSecond = 100)
        assertEquals(1, job.tasks.size)
        val t = job.tasks[0] as UndoJob.UndoTakeItemsTask
        assertEquals(1, t.amount)
    }

    @Test
    fun `空 journal 构造出空撤销任务`() {
        val entry = JournalEntry(
            id = 3, createdAt = System.currentTimeMillis(), actor = "Admin",
            dim = "minecraft:overworld", label = "rollback", includeMachines = false, undone = false,
            actions = emptyList(),
        )
        assertTrue(UndoJob.create(entry, blocksPerSecond = 100).tasks.isEmpty())
    }
}
