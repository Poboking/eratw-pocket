package com.eratw.port.android

import com.eratw.port.core.entity.ICharaSlotProjector
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modapi.EntityId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回合级交互 witness frame 读数（ModDX-4 M4 判据 1）：与当前 TARGET 同位置
 * （`CFLAG:<角色>:300` 現在位置）的其他角色清单 + 全局变量读法。
 *
 * 判据对应：
 * - witness 内容正确（同位置入场 / 不同位置不入 / TARGET 本身不入）；
 * - TARGET 无效（-1）⇒ witness 空且 metadata 如实透传 `-1`，不发明替代锚；
 * - TARGET 越界（≥ CHARANUM）⇒ 同上（不崩、照常出帧）；
 * - metadata 形状 = `ModInteractionWire` 的五个键。
 */
class ModWitnessFrameReaderTest {

    @Test
    fun `同位置的其他角色入选且不含 TARGET 本身`() {
        val store = VariableStore()
        repeat(3) { store.characters.addBare() }
        // 位置：0 号与 1 号同在 1 号房，2 号在别处；TARGET = 0。
        store.writeInt("CFLAG", longArrayOf(0, POSITION), 1L)
        store.writeInt("CFLAG", longArrayOf(1, POSITION), 1L)
        store.writeInt("CFLAG", longArrayOf(2, POSITION), 2L)
        store.writeInt("TARGET", longArrayOf(0), 0L)

        val context = ModWitnessFrameReader(store).readContext()

        assertEquals(ModInteractionWire.TURN_INTERACTION_ID, context.interactionId)
        assertEquals("0", context.metadata[ModInteractionWire.TARGET_KEY])
        assertEquals("1", context.metadata[ModInteractionWire.TARGET_POS_KEY])
        assertEquals("1", context.metadata[ModInteractionWire.WITNESS_KEY])
        assertEquals("1", context.metadata[ModInteractionWire.WITNESS_COUNT_KEY])
        assertEquals(EntityId.NULL, context.actor)
    }

    @Test
    fun `TARGET 无效时 witness 空且 metadata 如实透传 -1`() {
        val store = VariableStore()
        store.characters.addBare()
        store.writeInt("CFLAG", longArrayOf(0, POSITION), 1L)
        // 不写 TARGET：全局变量缺省读数 0 之外的无效形态这里显式置 -1。
        store.writeInt("TARGET", longArrayOf(0), -1L)

        val context = ModWitnessFrameReader(store).readContext()

        assertEquals("-1", context.metadata[ModInteractionWire.TARGET_KEY])
        assertEquals("", context.metadata[ModInteractionWire.WITNESS_KEY])
        assertEquals("0", context.metadata[ModInteractionWire.WITNESS_COUNT_KEY])
        assertNull("无效 TARGET 不得伪造主目标实体", context.primaryTarget)
        assertTrue(context.targets.isEmpty())
    }

    @Test
    fun `TARGET 越界时 witness 空且不崩`() {
        val store = VariableStore()
        store.characters.addBare()
        store.writeInt("TARGET", longArrayOf(0), 9L)

        val context = ModWitnessFrameReader(store).readContext()

        assertEquals("9", context.metadata[ModInteractionWire.TARGET_KEY])
        assertEquals("0", context.metadata[ModInteractionWire.WITNESS_COUNT_KEY])
        assertTrue(context.targets.isEmpty())
    }

    @Test
    fun `已投射实体进入 targets 与 primaryTarget`() {
        val store = VariableStore()
        val kernel = RecordingProjectorKernel()
        store.charaSlotProjector = kernel.projector
        repeat(2) { store.characters.addBare() }
        store.writeInt("CFLAG", longArrayOf(0, POSITION), 3L)
        store.writeInt("CFLAG", longArrayOf(1, POSITION), 3L)
        store.writeInt("TARGET", longArrayOf(0), 0L)
        kernel.project(0)
        kernel.project(1)

        val context = ModWitnessFrameReader(store).readContext()

        assertEquals("投影后的 TARGET 与在场者实体身份进 context", 1, context.targets.size)
        assertEquals(kernel.entityIdOf(0), context.primaryTarget)
        assertEquals(kernel.entityIdOf(1), context.targets.single())
    }

    private companion object {
        /** `CFLAG` 的 `現在位置` 元素号（ DatingForensics.CFLAG_POSITION 单源）。 */
        const val POSITION = 300L
    }
}

/** 测试替身：真 [RealCharaSlotProjector] 需要宿主装配全家桶，这里用最小内存投影记录。 */
private class RecordingProjectorKernel {
    private val entityToChara = mutableMapOf<Long, Int>()
    private var nextRaw = 1L

    val projector = object : ICharaSlotProjector {
        override val rosterGeneration: Long = 0L
        override fun project(entityId: Long): Int = TODO("测试不走进场投射")
        override fun unproject(entityId: Long): Boolean = TODO("测试不退场")
        override fun charIndexOf(entityId: Long): Int = entityToChara[entityId] ?: -1
        override fun charIndexOfChecked(entityId: Long, expectedGeneration: Long): Int =
            charIndexOf(entityId)
        override fun entityAt(charIndex: Int): Long =
            entityToChara.entries.firstOrNull { it.value == charIndex }?.key ?: 0L
        override fun projectedEntities(): LongArray = entityToChara.keys.toLongArray()
        override fun syncFromCharaSlot(entityId: Long) = Unit
        override fun onCharacterRowsPermuted(oldToNewIndexMap: IntArray) = Unit
        override fun onCharacterRowDeleted(deletedIndex: Int) = Unit
        override fun onWorldReset() = Unit
    }

    fun project(chara: Int): Long {
        val raw = nextRaw++
        entityToChara[raw] = chara
        return raw
    }

    fun entityIdOf(chara: Int): EntityId = EntityId(projector.entityAt(chara))
}
