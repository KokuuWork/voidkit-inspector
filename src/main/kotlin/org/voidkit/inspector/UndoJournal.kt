package org.voidkit.inspector

import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.Statement
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 回滚日志（journal）—— 回滚的撤销（undo，issue #8 第 5 层）的地基。
 *
 * ## 为什么要有 journal
 *
 * 回滚把世界从状态 A 改到了状态 B。要「撤销回滚」，必须知道 A 长什么样 ——
 * 审计库里存的是「玩家当时干了什么」，不是「回滚那一刻世界长什么样」，
 * 而且回滚执行和撤销之间可能隔着一次重启。所以每次回滚 job 执行时，
 * 把每个 target 的**回滚前原始状态**（方块快照 / 容器里塞进去的物品）
 * 落进 SQLite 的 `rollback_journal` 表，跨重启可用。
 *
 * ## 记录什么
 *
 * 回滚 job 应用每个任务（主线程）之前拍快照，完成后一次性落库：
 * - 还原方块前：该位置原来的方块 + 方块实体 NBT（[UndoBlockState]，含空气）；
 * - 往容器塞物品后：实际塞进去了什么、塞了多少（[UndoItemsInserted]，撤销时取走）。
 *
 * ## 安全约束（undo 命令侧执行）
 *
 * - 每条 journal 记录只能 undo 一次：[RollbackJournal.prepareUndo] 用
 *   `UPDATE … WHERE undone=0` 原子抢占，两个管理员同时 undo 也只有一人成功。
 * - 只允许撤销时间窗内的回滚：窗口检查与抢占在同一线程里完成，无竞态。
 */

/** 一条可撤销的动作（回滚前的世界快照片段）。纯数据，编解码不依赖 Minecraft 运行时。 */
sealed class UndoAction {
    abstract val x: Int
    abstract val y: Int
    abstract val z: Int
}

/** 回滚前的方块快照：material 为 `minecraft:air` 表示当时是空气。nbt 是方块实体编码，无则 null。 */
class UndoBlockState(
    override val x: Int,
    override val y: Int,
    override val z: Int,
    val material: String,
    val nbt: ByteArray?,
) : UndoAction()

/** 回滚往容器里塞进了 `amount` 个 material；撤销时把它们取走。 */
class UndoItemsInserted(
    override val x: Int,
    override val y: Int,
    override val z: Int,
    val material: String,
    val amount: Int,
) : UndoAction()

/** [UndoAction] 列表与 journal BLOB 之间的编解码。DataOutput 二进制格式，带版本号。 */
object UndoCodec {

    private const val VERSION = 1
    private const val TYPE_BLOCK = 1
    private const val TYPE_ITEMS = 2

    fun encode(actions: List<UndoAction>): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { out ->
            out.writeInt(VERSION)
            out.writeInt(actions.size)
            for (a in actions) {
                when (a) {
                    is UndoBlockState -> {
                        out.writeByte(TYPE_BLOCK)
                        out.writeInt(a.x); out.writeInt(a.y); out.writeInt(a.z)
                        out.writeUTF(a.material)
                        if (a.nbt != null) {
                            out.writeBoolean(true)
                            out.writeInt(a.nbt.size)
                            out.write(a.nbt)
                        } else {
                            out.writeBoolean(false)
                        }
                    }
                    is UndoItemsInserted -> {
                        out.writeByte(TYPE_ITEMS)
                        out.writeInt(a.x); out.writeInt(a.y); out.writeInt(a.z)
                        out.writeUTF(a.material)
                        out.writeInt(a.amount)
                    }
                }
            }
        }
        return bos.toByteArray()
    }

    fun decode(bytes: ByteArray?): List<UndoAction> {
        if (bytes == null || bytes.isEmpty()) return emptyList()
        return runCatching {
            val actions = ArrayList<UndoAction>()
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                val version = input.readInt()
                if (version != VERSION) return@runCatching emptyList<UndoAction>()
                val n = input.readInt()
                repeat(n) {
                    when (input.readByte().toInt()) {
                        TYPE_BLOCK -> {
                            val x = input.readInt(); val y = input.readInt(); val z = input.readInt()
                            val material = input.readUTF()
                            val nbt = if (input.readBoolean()) {
                                val len = input.readInt()
                                ByteArray(len).also { input.readFully(it) }
                            } else null
                            actions += UndoBlockState(x, y, z, material, nbt)
                        }
                        TYPE_ITEMS -> {
                            val x = input.readInt(); val y = input.readInt(); val z = input.readInt()
                            val material = input.readUTF()
                            val amount = input.readInt()
                            actions += UndoItemsInserted(x, y, z, material, amount)
                        }
                    }
                }
            }
            actions
        }.getOrDefault(emptyList())
    }
}

/** 一条已落库的回滚日志。actions 为该 job 应用顺序（undo 时自行倒序）。 */
class JournalEntry(
    val id: Long,
    val createdAt: Long,
    val actor: String,
    val dim: String,
    val label: String,
    val includeMachines: Boolean,
    val undone: Boolean,
    val actions: List<UndoAction>,
)

/** prepareUndo 的结果：Ok 已抢占成功（可执行 undo），其余为拒绝原因。 */
sealed class UndoPreparation {
    class Ok(val entry: JournalEntry) : UndoPreparation()
    object NotFound : UndoPreparation()
    object AlreadyUndone : UndoPreparation()
    class WindowExpired(val entry: JournalEntry) : UndoPreparation()
}

/**
 * 回滚日志的 SQLite 持久化。
 *
 * 独立于 [InspectorDb]（后者是审计事件的高吞吐流水线，这里是低频的 job 级读写）：
 * 单条写线程攒批的意义不大，用独立的单线程 executor 串行执行即可 ——
 * 回滚一分钟最多几次，journal 写入不可能成为瓶颈。
 * 与审计库共文件（WAL 一写多读），互不阻塞。
 */
class RollbackJournal(private val dbPath: Path) : AutoCloseable {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "voidkit-inspector-journal").apply { isDaemon = true }
    }

    private lateinit var conn: Connection

    fun open() {
        val dir = dbPath.toAbsolutePath().parent
        if (dir != null) Files.createDirectories(dir)
        conn = openConnection()
        conn.createStatement().use { st ->
            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS rollback_journal (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "created_at INTEGER NOT NULL," +
                    "actor TEXT NOT NULL," +
                    "dim TEXT NOT NULL," +
                    "label TEXT NOT NULL," +
                    "include_machines INTEGER NOT NULL DEFAULT 0," +
                    "undone INTEGER NOT NULL DEFAULT 0," +
                    "cx INTEGER NOT NULL DEFAULT 0," +
                    "cy INTEGER NOT NULL DEFAULT 0," +
                    "cz INTEGER NOT NULL DEFAULT 0," +
                    "actions BLOB NOT NULL" +
                    ")"
            )
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_rj_active ON rollback_journal(undone, id)")
        }
    }

    override fun close() {
        executor.shutdown()
        runCatching { executor.awaitTermination(4, TimeUnit.SECONDS) }
        if (::conn.isInitialized) runCatching { conn.close() }
    }

    /**
     * 落一条 journal（回滚 job 完成后调用，异步执行不占主线程）。
     * [createdAt] 由调用方传入（= job 执行开始时刻），undo 时间窗按它算。
     * centroid 为各动作的平均坐标（审计事件的位置），无动作时给 0。
     */
    fun saveAsync(
        createdAt: Long,
        actor: String,
        dim: String,
        label: String,
        includeMachines: Boolean,
        cx: Int, cy: Int, cz: Int,
        actions: List<UndoAction>,
    ): CompletableFuture<Long> =
        CompletableFuture.supplyAsync({
            conn.prepareStatement(
                "INSERT INTO rollback_journal(created_at,actor,dim,label,include_machines,undone,cx,cy,cz,actions)" +
                    " VALUES(?,?,?,?,?,0,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS,
            ).use { ps ->
                ps.setLong(1, createdAt)
                ps.setString(2, actor)
                ps.setString(3, dim)
                ps.setString(4, label)
                ps.setInt(5, if (includeMachines) 1 else 0)
                ps.setInt(6, cx)
                ps.setInt(7, cy)
                ps.setInt(8, cz)
                ps.setBytes(9, UndoCodec.encode(actions))
                ps.executeUpdate()
                ps.generatedKeys.use { rs -> if (rs.next()) rs.getLong(1) else -1L }
            }
        }, executor)

    /**
     * 原子地「找到并抢占」一次 undo：缺省取最近一条未撤销的记录（或指定 jobId），
     * 校验时间窗后用 `UPDATE … WHERE undone=0` 抢占 —— 抢到的那个人才有执行权。
     * 整个流程在单线程 executor 里完成，天然无竞态。
     *
     * [nowMs] 供测试注入当前时间（边界用例需要确定性）。
     */
    fun prepareUndoAsync(jobId: Long?, windowMs: Long, nowMs: Long = System.currentTimeMillis()): CompletableFuture<UndoPreparation> =
        CompletableFuture.supplyAsync({
            val entry = load(jobId) ?: return@supplyAsync UndoPreparation.NotFound as UndoPreparation
            if (entry.undone) return@supplyAsync UndoPreparation.AlreadyUndone
            if (nowMs - entry.createdAt > windowMs) {
                return@supplyAsync UndoPreparation.WindowExpired(entry)
            }
            val claimed = conn.prepareStatement(
                "UPDATE rollback_journal SET undone=1 WHERE id=? AND undone=0"
            ).use { ps ->
                ps.setLong(1, entry.id)
                ps.executeUpdate() > 0
            }
            if (claimed) UndoPreparation.Ok(entry) else UndoPreparation.AlreadyUndone
        }, executor)

    /**
     * 释放一次已抢占的 undo 标记（如启动撤销时发现任务通道被占），
     * 让这条回滚仍然可以被撤销，不会被白白浪费掉唯一一次撤销机会。
     */
    fun releaseUndoAsync(entryId: Long): CompletableFuture<Unit> =
        CompletableFuture.supplyAsync({
            conn.prepareStatement("UPDATE rollback_journal SET undone=0 WHERE id=?").use { ps ->
                ps.setLong(1, entryId)
                ps.executeUpdate()
            }
            Unit
        }, executor)

    /** 审计事件的位置（journal 记录的质心），undo 完成后落审计用。 */
    fun centroidOf(entryId: Long): CompletableFuture<Triple<Int, Int, Int>?> =
        CompletableFuture.supplyAsync({
            conn.prepareStatement("SELECT cx,cy,cz FROM rollback_journal WHERE id=?").use { ps ->
                ps.setLong(1, entryId)
                ps.executeQuery().use { rs -> if (rs.next()) Triple(rs.getInt(1), rs.getInt(2), rs.getInt(3)) else null }
            }
        }, executor)

    /** 读一条记录；id 为 null 时取最近一条**未撤销**的。 */
    private fun load(jobId: Long?): JournalEntry? {
        val sql = "SELECT id,created_at,actor,dim,label,include_machines,undone,actions FROM rollback_journal" +
            (if (jobId == null) " WHERE undone=0 ORDER BY id DESC LIMIT 1" else " WHERE id=?")
        return conn.prepareStatement(sql).use { ps ->
            if (jobId != null) ps.setLong(1, jobId)
            ps.executeQuery().use { rs ->
                if (!rs.next()) null else JournalEntry(
                    id = rs.getLong(1),
                    createdAt = rs.getLong(2),
                    actor = rs.getString(3) ?: "?",
                    dim = rs.getString(4) ?: "?",
                    label = rs.getString(5) ?: "",
                    includeMachines = rs.getInt(6) != 0,
                    undone = rs.getInt(7) != 0,
                    actions = UndoCodec.decode(rs.getBytes(8)),
                )
            }
        }
    }

    private fun openConnection(): Connection {
        val cfg = SQLiteConfig()
        cfg.setJournalMode(SQLiteConfig.JournalMode.WAL)
        cfg.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL)
        cfg.setBusyTimeout(5000)
        val ds = SQLiteDataSource(cfg)
        ds.url = "jdbc:sqlite:${dbPath.toAbsolutePath()}"
        return ds.connection
    }
}
