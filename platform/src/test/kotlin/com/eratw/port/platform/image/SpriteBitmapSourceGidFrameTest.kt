package com.eratw.port.platform.image

import com.eratw.port.core.host.GRAPHICS_SLOT
import com.eratw.port.core.host.GraphicsBuffer
import com.eratw.port.core.host.GraphicsPool
import com.eratw.port.core.host.RuntimeAux
import com.eratw.port.render.view.ImageFrame
import com.eratw.port.render.view.ImageFrameSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `CBGSETG` 的 gid 取图口（`ImageFrameSource.frameByGid` → `SpriteBitmapSource`）行为面。
 *
 * ## 钉的东西
 *
 * 1. **真 buffer → 帧**：core 的 `GCREATE` 路径（`GraphicsBuffer.createFrom`）真建一条带像素的
 *    缓冲，`frameByGid(gid)` 必须出非空帧，且句柄就是**同一块**缓冲的像素数组、四个几何读
 *    与缓冲宽高逐项相等（`srcW/srcH` 与 `destW/destH` 都是缓冲尺寸，= `CbgLayerSnapshot`
 *    的 `widthPx`/`heightPx`）。
 * 2. **失败回落**：gid 未建 / 只 `get` 过的空壳 / 已 `GDISPOSE` / 负数 gid 一律 null，且
 *    「未建」那条**不得**为 gid 长出池条目（`GraphicsPool.peek` 只查不建）。
 * 3. **默认实现零行为**：只实现 `frame` 的既有假体不实现新方法也能编译，`frameByGid` 恒 null，
 *    `cacheRebuilds` / `hasPendingRebuild` 仍是接口缺省（0 / false）。
 * 4. **生产口接线**：不注入时 `frameByGid` 走 `bitmapOf`：给一条只有 `fileSource`、像素未驻留的
 *    缓冲时，必须走那条**文件源快路**（懒解码源一次都不被调）并在桩里解不出文件时回落 null。
 *
 * 像素支（无 `fileSource` 的驻留缓冲 → `Bitmap.createBitmap`）在纯 JVM 桩里跑不完：
 * `Bitmap.createBitmap` 返回 null，`BitmapEntry` 的非空校验会先抛 NPE（真机上该 API 不返回
 * null，只抛 OOM，已被既有 catch 兜住）。这条支路登记为未覆盖项，不在用例里假装钉过。
 *
 * ## 为什么用例要注入句柄（`gidFrameHandle`）
 *
 * 本模块单测是纯 JVM（`unitTests.isReturnDefaultValues = true`），`android.graphics.Bitmap`
 * 是桩：`createBitmap` 恒返回 null、也没有可构造的 `Bitmap` 实例。所以「非空帧」这一半只能
 * 用**不透明句柄替身**（`ImageFrame.handle` 本来就是 `Any?`），注入替身时返回的正是缓冲
 * 自己的 `pixels` 数组 —— 用例因此能断言「帧里的句柄 === 那条缓冲的像素」，而不只是
 * 「返回了个东西」。gid 查表 / 只查不建 / 尺寸闸 / 几何映射这些被测逻辑全在
 * `frameByGid` 本体里照跑，被替掉的只有「像素数组 → Bitmap」最后一跳。
 *
 * 真机侧不可自动化的两段（真 `Bitmap` 像素内容、`fileSource` 快路不解整张）登记在交付报告
 * 的未覆盖项里，不在这里假装钉过。
 */
class SpriteBitmapSourceGidFrameTest {

    private val sources = ArrayList<SpriteBitmapSource>()
    private val roots = ArrayList<File>()

    @After
    fun tearDown() {
        for (s in sources) runCatching { s.close() }
        for (r in roots) runCatching { r.deleteRecursively() }
    }

    /** 真 `SpriteBitmapSource`（空资源根：文件层恒查不到，所有命中都来自 gid 缓冲）。 */
    private fun newSource(
        aux: RuntimeAux,
        handle: ((GraphicsBuffer) -> Any?)? = null,
    ): SpriteBitmapSource {
        val root = File(
            System.getProperty("java.io.tmpdir"),
            "eratw-gidframe-${System.nanoTime()}-${roots.size}",
        )
        root.mkdirs()
        roots.add(root)
        val src = SpriteBitmapSource(
            aux,
            GameImageFiles(root),
            clockMs = { 0L },
            gidFrameHandle = handle,
        )
        sources.add(src)
        return src
    }

    private fun newPool(aux: RuntimeAux): GraphicsPool = aux.slot(GRAPHICS_SLOT) { GraphicsPool() }

    // ------------------------------------------------------------------
    // 1) 真 buffer → 帧（可证伪主体）
    // ------------------------------------------------------------------

    @Test
    fun `GCREATE 的缓冲经 frameByGid 出帧 - 句柄与尺寸都来自同一缓冲`() {
        val aux = RuntimeAux()
        val pool = newPool(aux)
        val pixels = IntArray(4 * 3) { 0xFF000000.toInt() or it }
        pool.get(7).createFrom(4, 3, pixels)
        val src = newSource(aux) { buffer -> buffer.pixels }

        val frame = src.frameByGid(7)
        assertNotNull("已 GCREATE 的 gid 必须出帧", frame)
        frame!!
        assertSame("句柄必须就是该缓冲的像素数组（真 buffer → 帧）", pixels, frame.handle)
        assertTrue("有句柄 ⇒ usable", frame.usable)
        // 几何：src 与 dest 都是缓冲尺寸，与 CbgLayerSnapshot.widthPx/heightPx 同源。
        assertEquals(0, frame.srcX)
        assertEquals(0, frame.srcY)
        assertEquals(4, frame.srcW)
        assertEquals(3, frame.srcH)
        assertEquals(4, frame.destW)
        assertEquals(3, frame.destH)
        assertEquals("有效宽 = destW", 4, frame.width)
        assertEquals("有效高 = destH", 3, frame.height)
        assertEquals("快照尺寸同源", 4, pool.peek(7)!!.width)
        assertEquals("快照尺寸同源", 3, pool.peek(7)!!.height)
    }

    @Test
    fun `换尺寸后 frameByGid 跟着缓冲走 - 不是缓存了旧宽高`() {
        val aux = RuntimeAux()
        val pool = newPool(aux)
        pool.get(3).createFrom(2, 2, IntArray(4))
        val src = newSource(aux) { buffer -> buffer.pixels }
        assertEquals(2, src.frameByGid(3)!!.width)
        // 同日 gid 再 GCREATE：几何必须换代（与 CbgLayerSnapshot 的 widthPx 同一条刷新路径）。
        pool.get(3).createFrom(6, 1, IntArray(6))
        val frame = src.frameByGid(3)!!
        assertEquals(6, frame.srcW)
        assertEquals(1, frame.srcH)
        assertEquals(6, frame.destW)
        assertEquals(1, frame.destH)
    }

    // ------------------------------------------------------------------
    // 2) 失败回落
    // ------------------------------------------------------------------

    @Test
    fun `gid 未建 - 返回 null 且不为它登记池条目`() {
        val aux = RuntimeAux()
        val pool = newPool(aux)
        val src = newSource(aux) { buffer -> buffer.pixels }

        assertNull("池里没有这个 gid", src.frameByGid(9))
        assertEquals("peek 只查不建：取图不得长出条目", 0, pool.entryCount())

        // 惰性登记出来的空壳（GCREATE 前 / GDISPOSE 后同形）：有条目、没像素 ⇒ 不画。
        pool.get(5)
        assertEquals(1, pool.entryCount())
        assertNull("未创建的条目不得出帧", src.frameByGid(5))
    }

    @Test
    fun `已 GDISPOSE 的 gid - 返回 null`() {
        val aux = RuntimeAux()
        val pool = newPool(aux)
        pool.get(3).createFrom(2, 2, IntArray(4))
        val src = newSource(aux) { buffer -> buffer.pixels }
        assertNotNull("前置：dispose 前必须出帧", src.frameByGid(3))

        pool.peek(3)!!.dispose()
        assertNull("GDISPOSE 之后宽高归零 ⇒ 不画", src.frameByGid(3))
    }

    @Test
    fun `负数 gid 不是缓冲编号 - 直接 null`() {
        val aux = RuntimeAux()
        val pool = newPool(aux)
        val src = newSource(aux) { buffer -> buffer.pixels }
        // CbgLayerSnapshot.gid 用 -1 表示「无 gid」（按名层），不许拿它去查图形池。
        assertNull(src.frameByGid(-1))
        assertEquals(0, pool.entryCount())
    }

    // ------------------------------------------------------------------
    // 3) 生产口接线：不注入 ⇒ 走既有 bitmapOf（fileSource 快路不解整张）
    // ------------------------------------------------------------------

    @Test
    fun `缺省注入时 gid 帧走 bitmapOf 的 fileSource 快路 - 不解整张且桩里解不出来回落 null`() {
        val aux = RuntimeAux()
        val pool = newPool(aux)
        // `GCREATEFROMFILE` 的形状：有 fileSource、像素未驻留。桩里 BitmapFactory 解不出文件，
        // 整条链只该走到 fileSource 快路并返回 null，**不得**回头去触发懒解码源。
        var loadCalls = 0
        pool.get(1).createDeferredFromFile(2, 2, "gid-frame-file-source.png") {
            loadCalls++
            null
        }
        val src = newSource(aux) // 不注入：生产路径

        assertNull("桩里没有位图 ⇒ 本帧不画", src.frameByGid(1))
        assertEquals("fileSource 快路不得去解整张（load 一次都不该被调）", 0, loadCalls)
        assertNull("未注册的 gid 照旧 null", src.frameByGid(2))
    }

    // ------------------------------------------------------------------
    // 4) 默认实现零行为
    // ------------------------------------------------------------------

    /** 改造前就存在的实现形态：只实现 `frame`，不知道 gid。 */
    private class FileOnlyFrameSource : ImageFrameSource {
        override fun frame(resource: String): ImageFrame? = ImageFrame(handle = "file:$resource")
    }

    @Test
    fun `只实现 frame 的既有假体不实现新方法也编译 - frameByGid 恒 null`() {
        val fake: ImageFrameSource = FileOnlyFrameSource()
        assertNotNull("前置：按名路照旧", fake.frame("BG"))
        assertNull("默认实现零行为", fake.frameByGid(0))
        assertNull("默认实现零行为", fake.frameByGid(7))
        assertEquals("缺省无原地重写通路", 0L, fake.cacheRebuilds)
        assertFalse("缺省无待重建", fake.hasPendingRebuild)
    }
}
