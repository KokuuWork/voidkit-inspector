package org.voidkit.inspector.platform.neoforge

import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.ModContainer
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent
import org.voidkit.core.api.ConfigSnapshots
import org.voidkit.core.core.PluginRegistry
import org.voidkit.inspector.InspectorFeature

/**
 * voidkit_inspector —— 操作审计（COI）· **NeoForge 1.21.1 加载器入口**。
 *
 * 记录方块、容器、实体的玩家操作，落到嵌入式 SQLite；支持模组方块 / 物品的 NBT；
 * 内置对科技整合包（机械动力 / Mek / AE2）自动化产线的过滤，避免日志被机器刷爆。
 *
 * 一个模块：[InspectorFeature]。事件订阅在 [InspectorEvents]（同包，平台层）。
 */
@Mod(VoidkitInspectorNeoForge.MODID)
class VoidkitInspectorNeoForge(bus: IEventBus, container: ModContainer) {

    init {
        val feature = InspectorFeature()
        PluginRegistry.register(feature, MODID)

        // 事件桥注册（游戏总线）—— 平台层负责解包事件，业务层零加载器依赖
        InspectorEvents.register(feature)

        // 公开配置快照给面板（web 插件不需要 import 本插件）
        ConfigSnapshots.register("inspector") {
            val c = org.voidkit.inspector.InspectorStore.config
            mapOf(
                "inspector.logPlayer" to c.logPlayer,
                "inspector.logAutomation" to c.logAutomation,
                "inspector.logNatural" to c.logNatural,
                "inspector.logContainers" to c.logContainers,
                "inspector.logKills" to c.logKills,
                "inspector.retentionDays" to c.retentionDays,
                "inspector.rollbackEnabled" to c.rollbackEnabled,
            )
        }

        // 配置在服务端启动阶段读 —— 构造期保持零 IO
        //
        // ⚠️ `ServerAboutToStartEvent` 是**游戏总线**事件，必须挂 `NeoForge.EVENT_BUS`。
        //    挂到构造器注入的 mod 总线上会抛（E2E 实测，mod 进 ERROR、Done 永不出现）：
        //    `IllegalArgumentException: Listener for event class
        //     ...ServerAboutToStartEvent takes an argument that is not valid for this bus`
        //    —— NeoForge 的 mod 总线**不转发**游戏事件，与直觉相反。
        NeoForge.EVENT_BUS.addListener { _: ServerAboutToStartEvent ->
            org.voidkit.inspector.InspectorStore.load()
        }
    }

    companion object {
        const val MODID = "voidkit_inspector"
    }
}

/** 保留旧类名（`VoidkitInspector`）的引用，方便外部脚本/文档对齐。 */
typealias VoidkitInspector = VoidkitInspectorNeoForge