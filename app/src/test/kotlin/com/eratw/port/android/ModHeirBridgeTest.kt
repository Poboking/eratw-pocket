package com.eratw.port.android

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 家督继任玩家面数据桥的纯数据面（task-160 / Lane HB）。
 *
 * 钉四件事：① 请求队列按包去重、后到覆盖、首投递序；② drain 取走即清、不重投；
 * ③ 非法包名 / 空实体（`EntityId.NULL`）静默拒绝；④ 快照槽发布 / 读取 / 收口清空，以及
 * 「没有活 FamilyApi 时 apply 必须清空快照」的兜底（设置页据此整块不渲染）。
 *
 * 作者面真兑现（真 [com.eratw.port.modfamily.FamilyApi] 与会话）在 `ModHeirWireTest`；
 * 本文件不装会话、不碰 Android，纯 JVM。
 */
class ModHeirBridgeTest {

    private val pkg = "eratw.family"

    @Before
    fun reset() {
        ModHeirRequests.clear()
        ModHeirFeed.clear()
    }

    @After
    fun cleanup() {
        ModHeirRequests.clear()
        ModHeirFeed.clear()
    }

    @Test
    fun `入队按包去重 后到覆盖且保持首投递序`() {
        assertTrue(ModHeirRequests.requestChoose(pkg, 11L))
        assertTrue(ModHeirRequests.requestChoose("other.pkg", 22L))
        // 同包后到覆盖：值变、位次不变（先到先兑现的队列语义）。
        assertTrue(ModHeirRequests.requestChoose(pkg, 33L))
        assertEquals("同包多次点击只留一条积压", 2, ModHeirRequests.pendingCount())
        val drained = ModHeirRequests.drain()
        assertEquals("出队条数 = 去重后的包数 2", 2, drained.size)
        val first = drained[0] as ModHeirRequest.Choose
        assertEquals("首投递序：先入队的包先出队", pkg, first.packageId)
        assertEquals("同包后到覆盖：兑现最后一次点选", 33L, first.heirRaw)
        assertEquals("第二包位次不变", 22L, (drained[1] as ModHeirRequest.Choose).heirRaw)
    }

    @Test
    fun `drain 取走即清 不重投`() {
        assertTrue(ModHeirRequests.requestClear(pkg))
        assertEquals("一次 drain 取走一条", 1, ModHeirRequests.drain().size)
        assertEquals("取走即清：积压归零", 0, ModHeirRequests.pendingCount())
        assertTrue("没被取走的请求不会在下一拍重投", ModHeirRequests.drain().isEmpty())
    }

    @Test
    fun `非法包名与空实体被静默拒绝`() {
        assertFalse("包名以符号开头必须拒绝", ModHeirRequests.requestChoose(".bad", 1L))
        assertFalse("包名带空格必须拒绝", ModHeirRequests.requestChoose("bad pkg", 1L))
        assertFalse("EntityId.NULL(0) 不是合法候选", ModHeirRequests.requestChoose(pkg, 0L))
        assertFalse("清除同样走包名形状闸", ModHeirRequests.requestClear(""))
        assertEquals("被拒绝的请求不进积压", 0, ModHeirRequests.pendingCount())
    }

    @Test
    fun `快照发布后可读 清除后为空`() {
        assertNull("初始状态没有快照", ModHeirFeed.read())
        val snapshot = ModHeirSnapshot(
            packageId = pkg,
            mode = "manual",
            candidates = listOf(ModHeirCandidate(7L), ModHeirCandidate(9L, "阿甲")),
            pendingRaw = 9L,
        )
        ModHeirFeed.publish(snapshot)
        assertEquals("发布即读到同一份快照", snapshot, ModHeirFeed.read())
        ModHeirFeed.clear()
        assertNull("收口清空后设置页读不到上一局快照", ModHeirFeed.read())
    }

    @Test
    fun `无 family 面时 不发布快照且请求被丢弃`() {
        ModHeirFeed.publish(ModHeirSnapshot(pkg, "manual", emptyList(), 5L))
        val requests = listOf(ModHeirRequest.Choose(pkg, 5L), ModHeirRequest.Clear(pkg))
        assertFalse("没有活 FamilyApi 时 apply 必须返 false", ModHeirBridge.apply(requests, null, "manual"))
        assertNull("快照必须被清空（设置页整块不渲染）", ModHeirFeed.read())
        assertTrue("已取走的请求不回流", ModHeirRequests.drain().isEmpty())
    }
}
