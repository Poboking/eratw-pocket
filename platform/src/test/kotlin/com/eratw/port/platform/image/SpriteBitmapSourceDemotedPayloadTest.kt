package com.eratw.port.platform.image

import com.eratw.port.core.host.GraphicsPool
import com.eratw.port.core.image.PngDecoder
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 降级缓冲取图口（`SpriteBitmapSource.bitmapOf`）的**不晋升**契约（2026-11-08）。
 *
 * ## 钉的是哪条欠账
 *
 * 玩家报 0.2.43（versionCode 19 / `0664085`）「查看其他人的位置」时 OOM：Java 堆 512 MiB
 * 贴顶、栈顶 `ResultKt.createFailure` ← `StallSentinel.loop`，VM 却停在
 * `QueueInputSink.waitInput` 的 `Condition.await`（页已打完，正等输入）。屏上是几十~上百张
 * `highres_顔絵_服_通常_*`（600×600）。三档自救都在，但**页中检查点只在 VM 指令循环推进时
 * 触发**，等待输入期间它们全静默。
 *
 * 根因：core 把合成缓冲压成 PNG 后，平台侧在「降级态 + 位图缓存 miss」时读的是
 * `resident ?: buffer.pixels` —— `GraphicsBuffer.pixels` 是**有状态读**，会把 PNG 解回
 * `IntArray(600*600)`（1.44 MiB）并发布新代 Surface。渲染只是纯消费者，却借了 VM 的写路径
 * 读口，把页尾刚压下去的像素逐张钉回 Dalvik 堆，之后再没有 G 语句把它们压回去。
 *
 * 修法 = 状态读与快照读分流：core 开 `demotedCredential()`（零分配凭据）与
 * `demotedPngBytes()`（字节），平台侧 `BitmapFactory` 直接解成 native 位图，全程不进
 * Java 堆像素阵列；堆外载荷（OOM-B mmap）经 `Payload.readBytes()` 一次性拷贝交付。
 *
 * ## 为什么这里大半是源码面钉子
 *
 * 本模块单测跑纯 JVM，`android.graphics.Bitmap` / `BitmapFactory` 是桩（恒返回 null），
 * 真解码这一段没有可执行证据 —— 与 `GraphicsDecodePreflightGateTest` 同一口径：接线用源码钉，
 * 可执行的那一半（载荷逐字可还原、凭据随换代失效、读载荷不晋升）走 core 的真实对象。
 * 真机侧另有 `GameHost.graphicsPoolReportLine` 的「降级 native 取图 N 次」读数可对照。
 */
class SpriteBitmapSourceDemotedPayloadTest {

    private fun mainSource(relative: String): String {
        val workingDir = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            File(workingDir, "src/main/kotlin/com/eratw/port/platform/image/$relative"),
            File(workingDir, "platform/src/main/kotlin/com/eratw/port/platform/image/$relative"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: throw AssertionError("定位不到 platform 主源 $relative（尝试 ${candidates.map { it.path }}）")
        return file.readText()
    }

    /** 去掉整行 `//` 注释：源码面钉子只管代码，注释里引用旧写法不该把钉子判红。 */
    private fun codeOnly(source: String): String =
        source.lineSequence().joinToString("\n") { line ->
            val i = line.indexOf("//")
            if (i >= 0) line.substring(0, i) else line
        }

    /** [bitmapOf] 里「降级支」的代码区间：只读凭据口 → 常规像素读口之前。 */
    private fun demotedBranch(): String {
        val code = codeOnly(mainSource("SpriteBitmapSource.kt"))
        val start = code.indexOf("val demotedCred = if (resident == null) buffer.demotedCredential() else null")
        assertTrue("bitmapOf 必须先向 core 问只读凭据口（丢了就是退回 pixels 晋升）", start > 0)
        val pixelsRead = code.indexOf("val pixels = try {")
        assertTrue("常规像素读口仍在（CSV 父图 / GCREATEFROMFILE 首访问延迟解码要用）", pixelsRead > start)
        return code.substring(start, pixelsRead)
    }

    @Test
    fun `降级支绝不借有状态读口 - 一个字都不许碰 buffer pixels`() {
        val branch = demotedBranch()
        assertFalse(
            "降级支读 buffer.pixels 就会把 1.44 MiB/张透明晋升回 Java 堆（本波 OOM 的根因）",
            branch.contains("buffer.pixels"),
        )
        assertFalse(
            "旧的 isPixelsDemoted 判据只认状态不认载荷凭据，不许留在降级支",
            branch.contains("isPixelsDemoted"),
        )
        assertTrue("降级支必须直接 native 解 PNG，不落 IntArray", branch.contains("BitmapFactory.decodeByteArray("))
        assertTrue(
            "解码口径必须与 core 的直通 ARGB 同源（inPremultiplied=false + inScaled=false）",
            branch.contains("BitmapImageDecoder.pixelArrayDecodeOptions()"),
        )
        assertTrue(
            "翻回 premul 元数据才与常规支 createBitmap(IntArray) 逐字同构，缺了会跳色",
            branch.contains("setPremultiplied(true)"),
        )
    }

    @Test
    fun `降级支按凭据身份判缓存 - 换代后旧位图立即失效`() {
        val branch = demotedBranch()
        val source = codeOnly(mainSource("SpriteBitmapSource.kt"))
        assertTrue("降级条目必须入独立缓存（bufferCache 32 MiB 装不下一屏头像工作集）",
            branch.contains("demotedCache.get(buffer.id)") && branch.contains("demotedCache.put(buffer.id,"))
        assertFalse("降级支不许再碰 bufferCache（两代凭据体系混用必出脏命中）", branch.contains("bufferCache"))
        assertTrue("缓存判定必须先走零分配凭据口（堆外载荷 readBytes 是一次性拷贝，每帧读就是白付）",
            branch.contains("cachedDemoted.credRef.get() === demotedCred"))
        assertTrue("新条目必须登记弱引用凭据", branch.contains("DemotedEntry(bitmap, WeakReference(demotedCred))"))
        // 凭据必须是弱引用：强引用会在缓冲晋升后把旧代载荷钉在 Java 堆里
        //（账本归零、载荷常驻——本仓猎过的第二类 OOM 出口）。
        val entryDecl = source.substringAfter("private class DemotedEntry").take(200)
        assertTrue("DemotedEntry 凭据必须弱引用", entryDecl.contains("credRef: WeakReference<Any"))
        assertTrue("native 取图次数要记账（崩报取证读数）", branch.contains("demotedPngDecodeCount.incrementAndGet()"))
    }

    @Test
    fun `降级位图逐出走延迟回收 - native 堆不许等 GC`() {
        val source = codeOnly(mainSource("SpriteBitmapSource.kt"))
        val cacheDecl = source.substringAfter("private val demotedCache").substringBefore("private fun demotedCacheBudgetBytes")
        assertTrue("demotedCache 必须挂 entryRemoved 回收钩", cacheDecl.contains("entryRemoved"))
        assertTrue("逐出的位图进既有延迟回收队列（宽限期 + 主线程排干）", cacheDecl.contains("sheetRecycle.add(oldValue.bitmap"))
        assertTrue("trimCaches 收口降级表（onTrimMemory 才够得到它）",
            source.substringAfter("fun trimCaches()").substringBefore("}").contains("demotedCache.evictAll()"))
        assertTrue("invalidate（换代）同样收口",
            source.substringAfter("fun invalidate()").substringBefore("/**").contains("demotedCache.evictAll()"))
        assertTrue("cacheBytes 读数必须含降级表，否则崩报里又是一笔算不清的账",
            source.substringAfter("fun cacheBytes()").take(400).contains("demotedCache.size()"))
    }

    @Test
    fun `降级支解码失败走缺图支 - 绝不回落晋升`() {
        val branch = demotedBranch()
        assertTrue("native 落位图也可能 OOM，必须兜住", branch.contains("catch (t: OutOfMemoryError)"))
        assertTrue("BitmapFactory 对畸形字节会抛非 Exception，必须一并兜住", branch.contains("catch (t: Throwable)"))
        assertTrue("堆外载荷读不出（晋升竞态被换代口 close）也要独立出口，不许回落晋升",
            branch.contains("if (demotedPng == null) {"))
        val failAt = branch.indexOf("if (bitmap == null || bitmap.isRecycled")
        assertTrue("载荷损坏 / 尺寸不符要有独立的失败出口", failAt > 0)
        val span = branch.substring(failAt, minOf(branch.length, failAt + 400))
        assertTrue(span.contains("return null"))
        assertFalse("失败出口不许回落 pixels 晋升", span.contains("buffer.pixels"))
        assertTrue("失败出口顺手摘掉该 gid 的旧缓存条目", span.contains("demotedCache.remove(buffer.id)"))
    }

    @Test
    fun `常规像素读口的 OOM 兜底不许被本波改动带走`() {
        val code = codeOnly(mainSource("SpriteBitmapSource.kt"))
        val read = code.indexOf("val pixels = try {")
        assertTrue(read > 0)
        val span = code.substring(read, minOf(code.length, read + 400))
        assertTrue("首访问延迟解码仍是 Java 堆 IntArray，try 罩住的钉子必须原样在", span.contains("resident ?: buffer.pixels"))
        assertTrue(span.contains("catch (t: OutOfMemoryError)"))
    }

    // ---- 可执行的一半：core 侧只读凭据/字节口 ----

    @Test
    fun `平台侧拿到的载荷逐字可还原 且读载荷不晋升缓冲`() {
        val pool = GraphicsPool()
        val buffer = pool.get(701)
        val side = 64
        val seed = 0x7F00005B
        assertTrue(buffer.createFromArgb(side, side, IntArray(side * side) { idx -> seed + idx }))
        val expected = buffer.pixels.copyOf()
        assertTrue(buffer.demotePixelsToPng() > 0L)

        val cred = buffer.demotedCredential()
        assertNotNull("降级代必须给出零分配凭据", cred)
        val png = buffer.demotedPngBytes()
        assertNotNull("降级代必须把 PNG 字节交给渲染侧", png)
        val restored = PngDecoder.decode(png!!).pixels
        assertEquals(side, buffer.width)
        assertEquals(side, buffer.height)
        assertTrue("载荷解回来与降级前逐字一致", expected.contentEquals(restored))

        // 渲染侧反复取图（每帧一次）都不许把像素晋升回去、不许动池级账本。
        repeat(4) {
            assertSame("同一代反复读凭据恒等：缓存有效性凭据就是它", cred, buffer.demotedCredential())
            assertSame("堆内载荷反复读字节也恒等（零分配）", png, buffer.demotedPngBytes())
            assertNull(buffer.pixelsIfResident())
            assertTrue(buffer.isPixelsDemoted)
        }
        assertEquals("只读不换代 ⇒ 账本只能剩 PNG 载荷", png.size.toLong(), pool.residentBytes())
    }

    @Test
    fun `写后再降级换出新载荷 - 平台缓存必须判失效`() {
        val pool = GraphicsPool()
        val buffer = pool.get(702)
        val side = 64
        assertTrue(buffer.createFromArgb(side, side, IntArray(side * side) { idx -> 0x01000000 + idx }))
        assertTrue(buffer.demotePixelsToPng() > 0L)
        val firstCred = buffer.demotedCredential()
        val first = buffer.demotedPngBytes()
        assertNotNull(firstCred)
        assertNotNull(first)

        buffer.setPixel(0, 0, 0xFF112233.toInt()) // 写路径 ⇒ 透明晋升
        assertNull("晋升期间凭据不在当代", buffer.demotedCredential())
        assertNull("晋升期间载荷不在当代", buffer.demotedPngBytes())

        assertTrue(buffer.demotePixelsToPng() > 0L)
        val secondCred = buffer.demotedCredential()
        val second = buffer.demotedPngBytes()
        assertNotNull(secondCred)
        assertNotNull(second)
        assertFalse("换代必须换凭据对象，否则平台侧会复用上一代的脸", firstCred === secondCred)
        assertFalse("换载荷必须换对象", first === second)
        assertEquals(0xFF112233.toInt(), PngDecoder.decode(second!!).pixels[0])
    }
}
