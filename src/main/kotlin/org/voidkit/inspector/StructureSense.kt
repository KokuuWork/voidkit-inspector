package org.voidkit.inspector

/**
 * 结构感知 —— 智能回滚五层设计（issue #8）中的「第 2 层：多方块/结构感知」。
 *
 * 对「属于更大结构」的方块，要么整结构一起回滚，要么整结构跳过，绝不回滚一半：
 *
 * - **双格方块成对**：门（`half=upper/lower`）、床（`part=head/foot`）占两格。
 *   两半的审计记录都在同一批里才回滚（同一回滚单元相邻执行）；缺另一半的保守跳过 ——
 *   宁可不回滚，也不留下半扇门 / 半张床。
 * - **模组机器连通体**：被分类层跳过的机器 / 线缆按 6 向邻接聚类成连通体
 *   （同命名空间或同方块实体类型才算连通），预览与报告按连通体聚合输出
 *   （例如「create 连通体 ×3（共 17 格，已整体跳过）」），让服主看清整结构规模
 *   而不是逐格噪音；`--include-machines` 时整连通体一起回滚，仍绝不只回滚一半。
 *
 * 聚类 / 配对都是**纯函数**：输入方块快照列表，输出分组结果，可直接 JUnit 测试
 * （不起 MC 服务端）。涉及注册表的知识（材质是不是门 / 床）由调用方以 lambda 注入，
 * 无法判定时调用方沿用保守策略（默认跳过），本层不抛异常。
 */
object StructureSense {

    /** 双格方块种类：门占垂直两格（上/下），床占水平两格（床头/床尾）。 */
    enum class DoubleKind { DOOR, BED }

    /** 一个方块快照：来自审计记录的纯数据，不持有任何世界对象。 */
    data class Cell(
        val world: String,
        val x: Int,
        val y: Int,
        val z: Int,
        val material: String,
        /** 方块实体类型 id（NBT 的 `id` 字段），无则 null。 */
        val beType: String? = null,
        /** 动作码（[Actions]）：双格配对要求两半是同一种操作（都在破坏或都在放置的记录里）。 */
        val action: Int = Actions.BREAK,
    )

    /** 双格配对结果：[pairs] 是输入列表的下标对；[unpaired] 是没找到另一半的下标。 */
    data class PairingResult(val pairs: List<Pair<Int, Int>>, val unpaired: List<Int>)

    /** 一个机器 / 线缆连通体：同一维度内 6 向邻接且同命名空间（或同 BE 类型）的方块团。 */
    data class MachineComponent(
        val world: String,
        /** 该连通体的主导命名空间（跨命名空间靠同 BE 类型连通时取方块最多的那个）。 */
        val namespace: String,
        val cells: List<Cell>,
    ) {
        val size: Int get() = cells.size
    }

    // ---------------- 双格方块成对 ----------------

    /**
     * 把双格方块的快照两两配对（贪心，输入顺序优先）。
     *
     * 配对规则：同一维度、同一动作、同一材质，且位置满足该种类的成对方向 ——
     * 门只认垂直邻居（y±1），床只认水平邻居（x/z±1）。找不到另一半的按未配对返回，
     * 由调用方保守跳过。
     */
    fun pairDoubleBlocks(cells: List<Cell>, kindOf: (String) -> DoubleKind?): PairingResult {
        val kinds = cells.map { kindOf(it.material) }
        val byPos = HashMap<PosKey, MutableList<Int>>()
        cells.forEachIndexed { i, c -> byPos.getOrPut(PosKey(c.world, c.x, c.y, c.z)) { ArrayList() }.add(i) }

        val used = BooleanArray(cells.size)
        val pairs = ArrayList<Pair<Int, Int>>()
        val unpaired = ArrayList<Int>()
        for (i in cells.indices) {
            if (used[i]) continue
            used[i] = true
            val kind = kinds[i]
            if (kind == null) {
                unpaired += i
                continue
            }
            val a = cells[i]
            val j = partnerCandidates(kind, a)
                .flatMap { byPos[PosKey(a.world, a.x + it[0], a.y + it[1], a.z + it[2])] ?: emptyList() }
                .firstOrNull { k -> !used[k] && kinds[k] == kind && cells[k].material == a.material && cells[k].action == a.action }
            if (j == null) {
                unpaired += i
            } else {
                used[j] = true
                pairs += i to j
            }
        }
        return PairingResult(pairs, unpaired)
    }

    /** 双格成对方向：门取垂直两条轴，床取水平四条轴（都在 6 向邻接内）。 */
    private fun partnerCandidates(kind: DoubleKind, a: Cell): List<IntArray> = when (kind) {
        DoubleKind.DOOR -> listOf(intArrayOf(0, 1, 0), intArrayOf(0, -1, 0))
        DoubleKind.BED -> listOf(
            intArrayOf(1, 0, 0), intArrayOf(-1, 0, 0), intArrayOf(0, 0, 1), intArrayOf(0, 0, -1),
        )
    }

    // ---------------- 机器 / 线缆连通体 ----------------

    /**
     * 把机器快照按 6 向邻接聚成连通体：相邻且（同命名空间 或 同方块实体类型）才算连通，
     * 跨维度天然断连（位置键含维度）。输入应为已按分类层判定的机器 / 结构零件快照。
     */
    fun clusterMachines(cells: List<Cell>): List<MachineComponent> {
        val byPos = HashMap<PosKey, Int>()
        cells.forEachIndexed { i, c -> byPos[PosKey(c.world, c.x, c.y, c.z)] = i }

        val visited = BooleanArray(cells.size)
        val components = ArrayList<MachineComponent>()
        for (start in cells.indices) {
            if (visited[start]) continue
            val group = ArrayList<Int>()
            val queue = ArrayDeque<Int>()
            visited[start] = true
            queue += start
            while (queue.isNotEmpty()) {
                val cur = queue.removeFirst()
                group += cur
                val c = cells[cur]
                for (d in SIX_DIRS) {
                    val ni = byPos[PosKey(c.world, c.x + d[0], c.y + d[1], c.z + d[2])] ?: continue
                    if (visited[ni] || !connected(c, cells[ni])) continue
                    visited[ni] = true
                    queue += ni
                }
            }
            val world = cells[group[0]].world
            // 主导命名空间：取方块最多的；并列时取先出现的（groupingBy 保序）
            val namespace = group.groupingBy { cells[it].material.substringBefore(':', "") }
                .eachCount().maxWithOrNull(compareBy { it.value })?.key ?: ""
            components += MachineComponent(world, namespace, group.map { cells[it] })
        }
        return components
    }

    /** 连通判定：同命名空间直接连通；不同命名空间只有双方带同一方块实体类型才连通。 */
    private fun connected(a: Cell, b: Cell): Boolean {
        if (a.material.substringBefore(':', "") == b.material.substringBefore(':', "")) return true
        val be = a.beType ?: return false
        return be == b.beType
    }

    // ---------------- 报告聚合 ----------------

    /**
     * 按命名空间聚合连通体，输出报告行（按格数从多到少）：
     * `create 连通体 ×3（共 17 格，已整体跳过）`。
     *
     * @param included false=默认跳过场景；true=`--include-machines` 整连通体回滚场景
     */
    fun summarizeComponents(components: List<MachineComponent>, included: Boolean): List<String> {
        if (components.isEmpty()) return emptyList()
        data class Agg(var comps: Int, var cells: Int)
        val byNs = LinkedHashMap<String, Agg>()
        for (c in components) byNs.getOrPut(c.namespace) { Agg(0, 0) }.let { it.comps++; it.cells += c.size }
        val note = if (included) "整连通体回滚" else "已整体跳过"
        return byNs.entries.sortedByDescending { it.value.cells }
            .map { (ns, agg) -> "$ns 连通体 ×${agg.comps}（共 ${agg.cells} 格，$note）" }
    }

    /** 6 向邻接的方向表。 */
    private val SIX_DIRS = listOf(
        intArrayOf(1, 0, 0), intArrayOf(-1, 0, 0),
        intArrayOf(0, 1, 0), intArrayOf(0, -1, 0),
        intArrayOf(0, 0, 1), intArrayOf(0, 0, -1),
    )

    /** 位置键（含维度）：跨维度位置不同键，天然断连。 */
    private data class PosKey(val world: String, val x: Int, val y: Int, val z: Int)
}
