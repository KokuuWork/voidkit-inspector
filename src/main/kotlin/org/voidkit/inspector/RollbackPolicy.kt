package org.voidkit.inspector

import net.minecraft.nbt.CompoundTag

/**
 * 回滚分类 —— 智能回滚五层设计（issue #8）中的「第 1 层：分类感知」。
 *
 * 科技整合包里无脑回滚必然翻车：补回一个机械动力传动轴的方块，应力网络照样断；
 * 补回 AE2 线缆，ME 网络照样瘫。所以在回滚之前，先把每条记录**分类**，
 * 给不同类别不同的处置策略 —— 这是「智能」的地基。
 *
 * 判定依据完全来自审计记录本身（不依赖世界状态）：
 * - 记录里带方块实体 NBT → 这个方块有「内部状态」（箱子内容、机器配置）；
 *   NBT 的 `id` 字段（如 `minecraft:chest`、`create:mechanical_drill`）给出注册命名空间。
 * - `minecraft:` 命名空间的带数据方块是原版容器 / 告示牌等，恢复 NBT 是安全的。
 * - 非 `minecraft:` 命名空间的带数据方块是模组机器，回滚一半可能让多方块结构 / 网络
 *   处于不一致状态 —— 默认跳过并列出警告，服主显式 `--include-machines` 才回滚。
 * - 无 NBT 且命名空间在机器名单里的（线缆、传动件、管道这类「结构零件」），同样默认跳过。
 */
enum class RollbackCategory(val label: String) {

    /** 普通方块（石头、泥土、木板…）：无内部状态，直接还原方块即可。 */
    NORMAL("普通方块"),

    /** 原版容器 / 带数据方块（箱子、熔炉、告示牌…）：还原方块并恢复方块实体 NBT。 */
    RESTORE_NBT("容器/含数据方块"),

    /** 模组机器 / 结构零件（Create、Mek、AE2…）：默认跳过，避免破坏多方块结构与网络。 */
    MACHINE("机器/装置"),
}

object RollbackPolicy {

    /**
     * 给一条审计记录分类。
     *
     * @param material 方块注册名，如 `minecraft:chest`、`create:cogwheel`
     * @param nbt      审计时保存的方块实体 NBT，无则 null
     * @param machineNamespaces 配置里的「机器命名空间」名单（线缆、传动件这类无 NBT 的零件按命名空间拦截）
     */
    fun classify(material: String, nbt: CompoundTag?, machineNamespaces: Set<String>): RollbackCategory {
        val blockNs = material.substringBefore(':', "")
        // 有方块实体 NBT：按 NBT 里 `id` 字段的命名空间判（比方块 id 更准 —— 机器的 BE 与方块同名命名空间）
        if (nbt != null) {
            val beId = nbt.getString("id")
            val beNs = if (beId.isNotBlank()) beId.substringBefore(':', blockNs) else blockNs
            return if (beNs == "minecraft") RollbackCategory.RESTORE_NBT else RollbackCategory.MACHINE
        }
        // 无 NBT：线缆 / 传动件 / 管道这类结构零件按命名空间拦截，其余视为普通方块
        return if (blockNs in machineNamespaces) RollbackCategory.MACHINE else RollbackCategory.NORMAL
    }
}
