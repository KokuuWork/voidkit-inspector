package org.voidkit.inspector.platform.forge

import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.monster.Creeper
import net.minecraft.world.entity.item.PrimedTnt
import net.minecraft.world.entity.vehicle.MinecartTNT
import net.minecraft.world.entity.boss.wither.WitherBoss
import net.minecraft.world.entity.projectile.Fireball
import net.minecraft.world.level.Explosion
import net.minecraftforge.event.entity.item.ItemTossEvent
import net.minecraftforge.event.entity.living.LivingDeathEvent
import net.minecraftforge.event.entity.player.EntityItemPickupEvent
import net.minecraftforge.event.entity.player.PlayerContainerEvent
import net.minecraftforge.event.entity.player.PlayerEvent
import net.minecraftforge.event.entity.player.PlayerInteractEvent
import net.minecraftforge.event.level.BlockEvent
import net.minecraftforge.event.level.ExplosionEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import org.voidkit.inspector.InspectorFeature

/**
 * Forge 事件桥 —— **平台层，只做「事件 → 纯 MC 参数」的解包**。
 *
 * 判定与落库逻辑全在 [InspectorFeature]（加载器无关），这里一个 if 判定都没有。
 *
 * ## 本文件被两条版本线共用，但有一处不同
 *
 * - `mc/1.21.1-forge`（1.21.1 + Forge 52）
 * - `mc/1.20.1`（1.20.1 + Forge 47）—— **本分支**
 *
 * 两者的 **import 包名完全相同**（都是 `net.minecraftforge.*`，1.20→1.21 的
 * Forge 事件改名发生在 1.16→1.17，不是 1.20→1.21），差异只在 `Explosion`
 * 的实体字段结构上（见 [explosionName]）。这正是本 mod 平台层能这么薄的原因。
 *
 * ## 与 NeoForge 版（`platform/neoforge/InspectorEvents.kt`）的差异
 *
 * 1. import 包名（`net.neoforged.neoforge.event.*` → `net.minecraftforge.event.*`）
 * 2. 事件总线（`NeoForge.EVENT_BUS` → `MinecraftForge.EVENT_BUS`）
 * 3. **捡拾事件形状不同**（阶段 + 字段可见性，见 [onPickup]）
 */
class InspectorEvents(private val feature: InspectorFeature) {

    @SubscribeEvent
    fun onBlockBreak(e: BlockEvent.BreakEvent) {
        val level = e.level as? ServerLevel ?: return
        feature.onBlockBreak(level, e.pos, e.state, e.player)
    }

    @SubscribeEvent
    fun onBlockPlace(e: BlockEvent.EntityPlaceEvent) {
        val level = e.level as? ServerLevel ?: return
        feature.onBlockPlace(level, e.pos, e.placedBlock, e.entity)
    }

    @SubscribeEvent
    fun onExplosion(e: ExplosionEvent.Detonate) {
        val level = e.level as? ServerLevel ?: return
        feature.onExplosion(level, e.affectedBlocks, explosionName(e.explosion))
    }

    /**
     * 爆炸来源判定 —— 这里是平台层，因为要读 `Explosion` 的实体字段。
     *
     * 判定结果直接进审计库的 actor 列（`#creeper` / `#tnt` / …），
     * 名单在 `alwaysLogSources` 里可配，不在名单里的爆炸不记。
     *
     * ## ⚠️ 1.20.1 与 1.21.1 的 `Explosion` **字段结构不同**（1.20.1 线的移植要点）
     *
     * 1.20.1 的 `Explosion` 只有**一个**实体字段（`source` / SRG `field_9185`），
     * **没有** 1.21.1 那对 `directSourceEntity` / `indirectSourceEntity`：
     * - `getDirectSourceEntity()` 返回 `Entity`（SRG `method_46406`）
     * - `getIndirectSourceEntity()` 返回 **`LivingEntity`**（SRG `method_8347`）
     *   —— 返回类型比 1.21.1 的 `Entity` **更窄**，而且**可能为 null**
     *
     * Kotlin 侧写法也随之不同：1.21.1 能当属性访问（`explosion.directSourceEntity`），
     * 1.20.1 必须显式调 getter，且 `indirect` 要声明成可空。
     * 记为 `Entity?` 上转型是安全的（`LivingEntity` 是 `Entity` 的子类）。
     */
    private fun explosionName(explosion: Explosion): String {
        val direct = explosion.directSourceEntity
        val indirect: Entity? = explosion.indirectSourceEntity
        return when {
            direct is Creeper || indirect is Creeper -> "#creeper"
            direct is MinecartTNT || direct is PrimedTnt -> "#tnt"
            direct is WitherBoss || indirect is WitherBoss -> "#wither"
            direct is Fireball -> "#ghast"
            else -> "#explosion"
        }
    }

    @SubscribeEvent
    fun onContainerOpen(e: PlayerContainerEvent.Open) {
        val player = e.entity as? ServerPlayer ?: return
        feature.onContainerOpen(player, e.container)
    }

    @SubscribeEvent
    fun onContainerClose(e: PlayerContainerEvent.Close) {
        val player = e.entity as? ServerPlayer ?: return
        feature.onContainerClose(player, e.container)
    }

    @SubscribeEvent
    fun onLogout(e: PlayerEvent.PlayerLoggedOutEvent) {
        val player = e.entity as? ServerPlayer ?: return
        feature.onLogout(player)
    }

    @SubscribeEvent
    fun onDeath(e: LivingDeathEvent) {
        val killer = e.source.entity as? ServerPlayer ?: return
        val victim: Entity = e.entity
        feature.onDeath(victim, killer)
    }

    @SubscribeEvent
    fun onToss(e: ItemTossEvent) {
        feature.onToss(e.player, e.entity)
    }

    /**
     * 捡拾事件 —— **NeoForge 与 Forge 唯一形状不同的事件**（共两处差异）。
     *
     * 1. **阶段**：NeoForge `ItemEntityPickupEvent.Pre`（有 Pre/Post 两阶段）；
     *    Forge 52 `EntityItemPickupEvent` 是**单阶段**（无嵌套 Pre 类）。
     *    审计只需要「捡起时刻的快照」，所以两边都取 Pre / 单阶段那一版。
     * 2. **玩家访问方式**：NeoForge `ItemEntityPickupEvent.player` 是 public 字段；
     *    Forge 的 `PlayerEvent.player` 是 **private** 字段（javap 核实），
     *    只能走 `getEntity()` —— 直接写 `e.player` 编译报
     *    `Cannot access 'field player: Player!': it is private`。
     * 3. **物品访问方式**：NeoForge 是字段 `itemEntity`；
     *    Forge 没有该字段，必须走 `getItem()`（返回 `ItemEntity`）。
     */
    @SubscribeEvent
    fun onPickup(e: EntityItemPickupEvent) {
        feature.onPickup(e.entity, e.item)
    }

    @SubscribeEvent
    fun onLeftClick(e: PlayerInteractEvent.LeftClickBlock) {
        val player = e.entity as? ServerPlayer ?: return
        if (feature.onInspectClick(player, e.pos)) e.isCanceled = true
    }

    @SubscribeEvent
    fun onRightClick(e: PlayerInteractEvent.RightClickBlock) {
        val player = e.entity as? ServerPlayer ?: return
        if (feature.onInspectClick(player, e.pos)) e.isCanceled = true
    }

    companion object {
        /** 幂等守卫：同一 JVM 只注册一次（构造器可能被重复触发）。 */
        @Volatile
        private var registered = false

        fun register(feature: InspectorFeature) {
            if (registered) return
            registered = true
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(InspectorEvents(feature))
        }
    }
}
