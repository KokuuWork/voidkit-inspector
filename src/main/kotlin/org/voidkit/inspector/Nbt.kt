package org.voidkit.inspector

import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtIo
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * NBT 的编解码，以及「从世界对象里提取方块 / 物品身份」的工具。
 *
 * 原则：NBT 只在**游戏主线程**提取（方块实体是世界状态，离线程读不安全），
 * 转成不可变的 [ByteArray] 后再交给写线程，写线程只碰字节流。
 *
 * ## ⚠️ 1.20.1 与 1.21.1 在这里的**数据模型断层**（本文件是断层的主要落点）
 *
 * Minecraft **1.20.5** 把物品 / 方块的附加数据从 NBT `tag` 迁到了
 * 「data component」系统。1.21.x 用 `DataComponents` + `ItemStack.components`，
 * 1.20.1 只有原始 NBT `ItemStack.tag`。对应关系：
 *
 * | 1.21.x（main / mc-1.21.1-forge） | 1.20.1（本分支） |
 * |---|---|
 * | `stack.components.isEmpty()` | `stack.tag?.isEmpty != false` |
 * | `stack.save(registry)` | `stack.save(CompoundTag())` |
 * | `ItemStack.parse(registry, tag)` | `ItemStack.of(tag)` |
 * | `isSameItemSameComponents(a,b)` | `isSameItemSameTags(a,b)` |
 * | `BlockEntity.loadStatic(pos,state,tag,registry)` | `loadStatic(pos,state,tag)` |
 * | `be.saveWithFullMetadata(registry)` | `be.saveWithFullMetadata()` |
 *
 * 数据库里存的是**不透明的字节流**（[encode] / [decode]），所以两个版本线的
 * 库文件结构完全一致、不需要迁移 —— 变的只是「怎么从世界里取字节」这一层。
 */
object Nbt {

    /** 把 [CompoundTag] 编码成二进制（未压缩，快；NBT 本身够小）。 */
    fun encode(tag: CompoundTag?): ByteArray? {
        if (tag == null) return null
        return runCatching {
            val bos = ByteArrayOutputStream()
            DataOutputStream(bos).use { NbtIo.write(tag, it) }
            bos.toByteArray()
        }.getOrNull()
    }

    /** 反向解码。 */
    fun decode(bytes: ByteArray?): CompoundTag? {
        if (bytes == null || bytes.isEmpty()) return null
        return runCatching {
            DataInputStream(ByteArrayInputStream(bytes)).use { NbtIo.read(it) }
        }.getOrNull()
    }

    /** 方块注册名（`minecraft:stone`）。 */
    fun blockId(state: BlockState): String =
        BuiltInRegistries.BLOCK.getKey(state.block)?.toString() ?: "minecraft:air"

    /** 物品注册名（`mekanism:ingot_steel`）。 */
    fun itemId(stack: ItemStack): String =
        BuiltInRegistries.ITEM.getKey(stack.item)?.toString() ?: "minecraft:air"

    /**
     * 方块实体的 NBT（箱子内容、机器状态、告示牌文字、Create 装置…）。
     *
     * 没有方块实体（普通石头）就返回 null，不浪费一个字节。
     *
     * 1.20.1 的 `saveWithFullMetadata()` **不接受** `HolderLookup.Provider`
     * （1.21.x 才需要，因为 component 要查注册表）。
     */
    fun blockEntityNbt(level: Level, pos: BlockPos): ByteArray? {
        val be = level.getBlockEntity(pos) ?: return null
        return encode(runCatching { be.saveWithFullMetadata() }.getOrNull())
    }

    /**
     * 物品的 NBT（附魔、自定义名、整合包物品的私有数据）。
     *
     * 只有**带数据**的物品才存 —— 一块普通圆石没有，存了就是浪费。
     * 返回的是包含 `id`/`count`/`tag` 的完整标签，便于将来回滚重建。
     *
     * 1.20.1 没有 component 系统，「有没有数据」看 `stack.tag` 是否为 null / 空。
     */
    fun itemNbt(stack: ItemStack): ByteArray? {
        if (stack.isEmpty) return null
        val t = stack.tag
        // 只有 count 的裸物品（无 tag）不必存 —— 回滚时按记录的数量重建即可
        if (t == null || t.isEmpty) return null
        return encode(runCatching { stack.save(CompoundTag()) }.getOrNull())
    }
}

