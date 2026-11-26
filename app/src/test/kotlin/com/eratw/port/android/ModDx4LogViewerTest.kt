package com.eratw.port.android

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * mod 日志回看的 app 侧读数口（MODDX4-G8）：`ModRuntimeLogViewerEntry` 的认主活引用通道。
 *
 * 断言口径（契约 §3）：未接线 / provider 返回 null（会话已收口）⇒ read 空表——设置页据此
 * 渲染诚实空态，不造假数据（fail-closed）；接线后透传 provider 的快照；认主卸载照
 * `ModRuntimeOverviewEntry` 同源口径：旧壳无权撅新壳的接线。
 */
class ModDx4LogViewerTest {

    private val ownerA = Any()
    private val ownerB = Any()

    @After
    fun cleanWiring() {
        // 单例通道防泄漏：本类的用例各自认主，收尾全摘（谁都在自己的用例里装/摘成对，这里是兜底）。
        ModRuntimeLogViewerEntry.uninstall(ownerA)
        ModRuntimeLogViewerEntry.uninstall(ownerB)
    }

    @Test
    fun `未接线时 read 返回空表_诚实空态`() {
        assertNull("前置自检：通道未接线", ModRuntimeLogViewerEntry.wiredOwnerSnapshot())
        assertEquals(
            "未接线 = 空表（fail-closed，不造假数据）",
            emptyList<String>(),
            ModRuntimeLogViewerEntry.read("some-pkg"),
        )
    }

    @Test
    fun `接线后 read 透传 provider 的只读快照`() {
        val snapshot = listOf("[some-pkg][INFO] hello")
        ModRuntimeLogViewerEntry.install(ownerA) { pkg ->
            if (pkg == "some-pkg") snapshot else emptyList()
        }
        assertSame("接线身份 = 后装者", ownerA, ModRuntimeLogViewerEntry.wiredOwnerSnapshot())
        assertEquals("透传 provider 快照", snapshot, ModRuntimeLogViewerEntry.read("some-pkg"))
        assertEquals(
            "provider 自答空表照传",
            emptyList<String>(),
            ModRuntimeLogViewerEntry.read("other-pkg"),
        )
    }

    @Test
    fun `provider 返回 null_会话已收口时 read 回空表`() {
        ModRuntimeLogViewerEntry.install(ownerA) { _ -> null }
        assertEquals(
            "host 引用为 null（会话收口）⇒ 空表，不崩不造假",
            emptyList<String>(),
            ModRuntimeLogViewerEntry.read("some-pkg"),
        )
    }

    @Test
    fun `认主卸载_旧 owner 无权撅新壳的接线`() {
        ModRuntimeLogViewerEntry.install(ownerA) { _ -> listOf("from-a") }
        ModRuntimeLogViewerEntry.install(ownerB) { _ -> listOf("from-b") }
        // 后装者覆盖旧接线（幂等）；旧壳此时无权摘。
        assertEquals("后装者覆盖旧接线", listOf("from-b"), ModRuntimeLogViewerEntry.read("any"))
        ModRuntimeLogViewerEntry.uninstall(ownerA)
        assertEquals("旧 owner 的卸载被认主拒绝", listOf("from-b"), ModRuntimeLogViewerEntry.read("any"))
        assertSame("接线仍在", ownerB, ModRuntimeLogViewerEntry.wiredOwnerSnapshot())
        ModRuntimeLogViewerEntry.uninstall(ownerB)
        assertEquals("归属正主的卸载生效", emptyList<String>(), ModRuntimeLogViewerEntry.read("any"))
        assertNull("装/摘成对后通道回到未接线", ModRuntimeLogViewerEntry.wiredOwnerSnapshot())
    }
}
