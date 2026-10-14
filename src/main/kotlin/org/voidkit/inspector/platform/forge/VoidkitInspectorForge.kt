package org.voidkit.inspector.platform.forge

import net.minecraftforge.event.server.ServerAboutToStartEvent
import net.minecraftforge.fml.common.Mod
import org.voidkit.core.api.ConfigSnapshots
import org.voidkit.core.core.PluginRegistry
import org.voidkit.inspector.InspectorFeature

/**
 * voidkit_inspector —— 操作审计（COI）· **Forge 加载器入口**。
 *
 * 记录方块、容器、实体的玩家操作，落到嵌入式 SQLite；支持模组方块 / 物品的 NBT；
 * 内置对科技整合包（机械动力 / Mek / AE2）自动化产线的过滤，避免日志被机器刷爆。
 *
 * 一个模块：[InspectorFeature]。事件订阅在 [InspectorEvents]（同包，平台层）。
 *
 * ## 本文件被两条版本线共用（1.21.1 + Forge 52 / 1.20.1 + Forge 47），代码逐行相同
 *
 * ## 与 NeoForge 入口的差异（⚠️ 真机 E2E 踩到的坑）
 *
 * **① Forge 的 `@Mod` 不支持构造器注入，必须用无参构造器。**
 * 照抄 NeoForge 的 `class X(bus: IEventBus, container: ModContainer)` 能编译过，
 * 但真机启动直接炸：
 * ```
 * Failed to create mod instance. ModID: voidkit_inspector
 * java.lang.NoSuchMethodException: ...VoidkitInspectorForge.<init>()
 * ```
 * 然后模组进 ERROR 状态、`Done (` 永不出现。**编译过 ≠ 能跑**。
 *
 * **② 两条总线不能混用。** `ServerAboutToStartEvent` 是**游戏总线**事件，
 * 必须挂 `MinecraftForge.EVENT_BUS`；挂到 mod 总线上抛
 * `IllegalArgumentException: ... not a subtype of ... IModBusEvent`，同样让 mod 进 ERROR。
 * 顺带一提：本入口其实**不需要 mod 总线**，所以干脆不取它。
 *
 * **③ `@Mod` 没有 `dist` 属性**（NeoForge 专有）。本模组纯服务端、无双 jar，
 * 不需要区分物理端，故无需 `FMLEnvironment.dist` 判断。
 *
 * **④ 1.20.1 与 1.21.1 的差异全在业务代码之外的另一层**：
 * `Explosion` 的实体字段结构（见 [InspectorEvents.explosionName]）、
 * 以及构建侧「1.20.1 要 reobf 成 SRG、1.21.1 用官方映射」。本入口本身无差异。
 */
@Mod(VoidkitInspectorForge.MODID)
class VoidkitInspectorForge {

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
        // ⚠️ `ServerAboutToStartEvent` 是**游戏总线（MinecraftForge.EVENT_BUS）**事件，
        //    不是 mod 总线事件。挂到 mod 总线上会直接抛：
        //    `IllegalArgumentException: Listener for event class ...ServerAboutToStartEvent
        //     takes an argument that is not a subtype of the base type interface IModBusEvent`
        //    → mod 进 ERROR 状态、Done 永不出现（E2E 实测）。
        //    NeoForge 那份挂在构造器注入的 IEventBus 上恰好能过，是因为 NeoForge 的
        //    mod 总线会转发游戏事件；Forge 的不会，必须显式选对总线。
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener { _: ServerAboutToStartEvent ->
            org.voidkit.inspector.InspectorStore.load()
        }
    }

    companion object {
        const val MODID = "voidkit_inspector"
    }
}
