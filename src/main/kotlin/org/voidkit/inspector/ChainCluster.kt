package org.voidkit.inspector

/**
 * 因果链聚类 —— issue #8 五层设计中的第 3 层。
 *
 * 违规破坏往往不是一次动作，而是一条链：拆箱子 → 拿东西 → 放 TNT → 炸。
 * 单条审计记录只能回答「谁动了这个方块」，回答不了「这次破坏的全貌」。
 *
 * 聚类规则（同一玩家 + 邻近区域 + 连续时间段）：
 * - **时间断链**：同一玩家两次操作间隔超过 `chainIdleMinutes`（默认 5 分钟）即断链；
 * - **空间断链**：新事件与链上已有事件的最近距离超过 `chainRadiusBlocks`（默认 32 格）即断链；
 * - **维度断链**：换维度了必然是新链（跨维度谈「距离」没有意义）。
 *
 * 每条链附 [Chain.id]（该玩家该时间窗内的序号，从 1 起），配合
 * `/inspect rollback chain:<玩家>:<链id>` 可一键回滚整条链。
 *
 * 实现为**纯数据计算**：输入输出都是审计记录与聚合结果，不碰世界状态、不碰主线程，
 * 查询线程里跑，也方便做单元测试。
 */
object ChainCluster {

    /**
     * 把一段时间内的审计记录聚类成操作链。
     *
     * @param rows 查询结果（任意顺序，内部按时间升序重排）
     * @param idleMinutes 时间断链阈值（分钟）：相邻两次操作间隔超过即断链
     * @param radiusBlocks 空间断链阈值（格）：新事件与链上最近事件的距离超过即断链
     */
    fun cluster(rows: List<InspectorDb.QueryRow>, idleMinutes: Int, radiusBlocks: Int): List<Chain> {
        if (rows.isEmpty()) return emptyList()
        val events = rows.sortedWith(compareBy<InspectorDb.QueryRow> { it.time }.thenBy { it.id })
        val idleMs = idleMinutes.coerceAtLeast(0) * 60_000L
        val radiusSq = radiusBlocks.toLong() * radiusBlocks.toLong()

        val chains = ArrayList<Chain>()
        var cur = ArrayList<InspectorDb.QueryRow>()
        var lastTime = Long.MIN_VALUE

        fun flush() {
            if (cur.isEmpty()) return
            val first = cur.first()
            val players = cur.mapTo(LinkedHashSet()) { it.actor }
            chains += Chain(chains.size + 1, first.world, players, first.time, cur.last().time, cur.toList())
            cur = ArrayList()
        }

        for (e in events) {
            if (cur.isNotEmpty()) {
                val gap = e.time - lastTime
                val sameWorld = e.world == cur.first().world
                if (gap > idleMs || !sameWorld || nearestDistSq(cur, e) > radiusSq) flush()
            }
            cur += e
            lastTime = e.time
        }
        flush()
        return chains
    }

    /** 新事件到链上已有事件的最近距离的平方（欧氏距离，逐轴平方和）。 */
    private fun nearestDistSq(chain: List<InspectorDb.QueryRow>, e: InspectorDb.QueryRow): Long {
        var best = Long.MAX_VALUE
        for (p in chain) {
            val dx = (p.x - e.x).toLong()
            val dy = (p.y - e.y).toLong()
            val dz = (p.z - e.z).toLong()
            val d = dx * dx + dy * dy + dz * dz
            if (d < best) best = d
            if (d == 0L) break
        }
        return best
    }
}

/**
 * 一条操作链：同一玩家在邻近区域、连续时间段内的一串审计操作。
 *
 * @param id 链序号（该玩家该时间窗内从 1 递增），回滚命令用它定位：`chain:<玩家>:<id>`
 * @param world 链所在维度（聚类按维度断链，一条链只在一个维度里）
 * @param players 参与玩家（查询按玩家过滤，通常只有 1 人）
 * @param events 链内全部审计记录，按时间升序
 */
class Chain(
    val id: Int,
    val world: String,
    val players: Set<String>,
    val startTime: Long,
    val endTime: Long,
    val events: List<InspectorDb.QueryRow>,
) {

    /** 事件数。 */
    val eventCount: Int get() = events.size

    /** 涉及方块数：链内出现过的不同坐标数（拆的箱子、放的平台、炸的坑各算各的位置）。 */
    val blockCount: Int get() = events.mapTo(HashSet()) { Triple(it.x, it.y, it.z) }.size

    /** 链的中心坐标（所有事件的算术平均）。 */
    val centerX: Int get() = (events.sumOf { it.x.toLong() } / events.size).toInt()
    val centerY: Int get() = (events.sumOf { it.y.toLong() } / events.size).toInt()
    val centerZ: Int get() = (events.sumOf { it.z.toLong() } / events.size).toInt()

    /** 参与玩家名（通常 1 人）。 */
    val playerNames: String get() = players.joinToString(", ")

    /**
     * 操作摘要，如「3 容器拿取 · 12 方块破坏 · 1 放置」。
     * 只列出现过的动作，顺序固定：拿取 → 破坏 → 放置 → 点火 → 放入 → 击杀 → 其他。
     */
    fun summary(): String {
        var take = 0; var breakN = 0; var place = 0; var fire = 0; var put = 0; var kill = 0; var other = 0
        for (e in events) when (e.action) {
            Actions.CONTAINER_TAKE -> take++
            Actions.BREAK -> breakN++
            Actions.PLACE -> place++
            Actions.PLACE_FIRE -> fire++
            Actions.CONTAINER_PUT -> put++
            Actions.KILL -> kill++
            else -> other++
        }
        val parts = ArrayList<String>()
        if (take > 0) parts += "$take 容器拿取"
        if (breakN > 0) parts += "$breakN 方块破坏"
        if (place > 0) parts += "$place 放置"
        if (fire > 0) parts += "$fire 点火"
        if (put > 0) parts += "$put 容器放入"
        if (kill > 0) parts += "$kill 击杀"
        if (other > 0) parts += "$other 其他"
        return if (parts.isEmpty()) "无操作" else parts.joinToString(" · ")
    }
}
