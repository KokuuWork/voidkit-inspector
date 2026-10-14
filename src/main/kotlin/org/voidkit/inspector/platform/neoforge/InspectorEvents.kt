package org.voidkit.inspector.platform.neoforge

import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.monster.Creeper
import net.minecraft.world.entity.item.PrimedTnt
import net.minecraft.world.entity.vehicle.MinecartTNT
import net.minecraft.world.entity.boss.wither.WitherBoss
import net.minecraft.world.entity.projectile.Fireball
import net.minecraft.world.level.Explosion
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.item.ItemTossEvent
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
import net.neoforged.neoforge.event.level.BlockEvent
import net.neoforged.neoforge.event.level.ExplosionEvent
import org.voidkit.inspector.InspectorFeature

/**
 * NeoForge 1.21.1 事件桥 —— **平台层，只做「事件 → 纯 MC 参数」的解包**。
 *
 * 判定与落库逻辑全在 [InspectorFeature]（加载器无关），这里一个 if 判定都没有。
 * 换加载器 = 换一份这个文件，业务代码零改动。
 *
 * ## 为什么这么薄
 *
 * NeoForge 与 Forge 1.21.1 的事件类**几乎逐字相同**，只是包名不同：
 * `net.neoforged.neoforge.event.*` ↔ `net.minecraftforge.event.*`，
 * 且两者都用 Mojang 官方映射（方法名一致，不像 1.20.1 要过 SRG）。
 * 所以两份平台层的差异只有 import 与「捡拾事件」那一个类的形状。
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
     */
    private fun explosionName(explosion: Explosion): String {
        val direct = explosion.directSourceEntity
        val indirect = explosion.indirectSourceEntity
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
     * 捡拾事件 —— **NeoForge 与 Forge 唯一形状不同的事件**。
     *
     * NeoForge：`ItemEntityPickupEvent.Pre`（有 Pre/Post 两阶段）。
     * Forge 52：`EntityItemPickupEvent`（单阶段，无嵌套 Pre）。
     * 审计只需要「捡起时刻的快照」，所以两边都取 Pre / 单阶段那一版。
     */
    @SubscribeEvent
    fun onPickup(e: ItemEntityPickupEvent.Pre) {
        feature.onPickup(e.player, e.itemEntity)
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
            NeoForge.EVENT_BUS.register(InspectorEvents(feature))
        }
    }
}