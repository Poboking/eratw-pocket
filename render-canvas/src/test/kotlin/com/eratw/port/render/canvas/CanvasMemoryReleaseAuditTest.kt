package com.eratw.port.render.canvas

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 内存压力收口（`releaseMemoryCaches` / `viewBitmapCacheBytes`）的**源码面**回归闸
 * （2026-11-02，heap-budget 车道）。
 *
 * ## 为什么又是源码面
 *
 * [CanvasConsoleView] 是 Android `View`，本模块只有纯 JVM 单测（无 Robolectric，
 * `render-canvas/build.gradle.kts` 只引 `kotlin("test")` + `junit-jupiter`），**实例化不了**；
 * 本机 `adb devices` 为空 ⇒ 真机 native 堆读数也取不到。同 [SessionReleaseFieldAuditTest]
 * 的口径：本夹具**不冒充**驻留读数，只钉两件可判的事 ——
 *
 * 1. 深/浅两档各自动哪几块位图（动错的代价是当场闪帧或后台继续顶着整屏像素）；
 * 2. 「丢位图必须同批作废脏判定」这条配对纪律（漏一条 = 回前台背景永久不贴，
 *    见 [onDetachedFromWindow] 与 `releaseSession` 的 KDoc）。
 *
 * ## 这一刀要结清的病
 *
 * 端点报告里「native 驻留偏高」3 份的峰值是 286~361 MiB，而此前 `onTrimMemory` 的收口面
 * 只够得到平台侧三张 `LruCache`（预算 32 + 16 + 192 = 240 MiB）。本屏这四块
 * （整屏保留面 + 背景烘焙面 + 行栅格缓存 24 MiB + 行位图池 6 MiB，稳态约 50 MiB）
 * **从来没有任何 trim 入口**，也从来不进任何账面 —— 于是「收了不降」与「根本没收到这块」
 * 在报告里长得一模一样。
 */
class CanvasMemoryReleaseAuditTest {

    private val src: String by lazy {
        Path.of("").toAbsolutePath()
            .resolve("src/main/kotlin/com/eratw/port/render/canvas/CanvasConsoleView.kt")
            .readText()
    }

    /** 取某个 `fun xxx(` 的函数体（按大括号配平；与 SessionReleaseFieldAuditTest 同一把尺）。 */
    private fun bodyOf(signature: String): String {
        val start = src.indexOf(signature)
        assertTrue(start >= 0, "源码里找不到 $signature")
        val open = src.indexOf('{', start)
        assertTrue(open >= 0, "$signature 后没有函数体")
        var depth = 0
        var i = open
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return stripComments(src.substring(open + 1, i))
                }
                else -> {}
            }
            i++
        }
        error("$signature 的函数体没有配平")
    }

    private fun stripComments(body: String): String =
        body.lineSequence().joinToString("\n") { line ->
            val i = line.indexOf("//")
            if (i >= 0) line.substring(0, i) else line
        }

    /** 本屏持有的**全部**四块位图账面（漏一块，报告里的 native 账面就永远算不满）。 */
    private val cacheBlocks = listOf("rowCache.bytes", "rowPoolBytes", "screenSurface", "backing")

    @Test
    fun `账面读数覆盖本屏四块位图`() {
        val body = bodyOf("fun viewBitmapCacheBytes()")
        val missing = cacheBlocks.filter { !body.contains(it) }
        assertEquals(emptyList(), missing, "viewBitmapCacheBytes 漏算：$missing")
    }

    /** 线程闸：不在主线程一律不做（把正在烤/正贴的那张抽走就是 AIOOBE 级的崩）。 */
    @Test
    fun `收口只在 UI 线程生效`() {
        val body = bodyOf("fun releaseMemoryCaches(deep: Boolean)")
        assertTrue(body.contains("Looper.myLooper()"), "releaseMemoryCaches 缺主线程判定")
        assertTrue(body.contains("Looper.getMainLooper()"), "releaseMemoryCaches 缺主线程判定")
        assertTrue(
            Regex("""!=\s*Looper\.getMainLooper\(\)\s*\)\s*return 0L""").containsMatchIn(body),
            "非主线程必须**直接返回**而不是照做 —— 回收动作只能在画它的那条线程上做",
        )
    }

    /** 浅档（前台 RUNNING 系）也收的两块：行栅格缓存 + 行位图池，且清完池子要归零计数。 */
    @Test
    fun `两档都收行缓存与行池`() {
        val body = bodyOf("fun releaseMemoryCaches(deep: Boolean)")
        val light = body.substringBefore("if (deep)")
        for (needed in listOf("rowCache.clear()", "rowBitmapPool.clear()", "rowPoolBytes = 0L")) {
            assertTrue(light.contains(needed), "浅档段缺 $needed")
        }
    }

    /**
     * 顺序纪律：`rowCache.clear()` 会逐条走 `onEvict` ⇒ 至多 [ROW_POOL_MAX_BYTES] 落进池子，
     * 所以**清完缓存必须接着清池**，否则「收 24 MiB」只把字节从缓存挪进池子，账面反弹成负。
     */
    @Test
    fun `先清缓存再清池`() {
        val light = bodyOf("fun releaseMemoryCaches(deep: Boolean)").substringBefore("if (deep)")
        assertTrue(
            light.indexOf("rowCache.clear()") < light.indexOf("rowBitmapPool.clear()"),
            "行缓存要先于行池交出去，否则 evict 回调会把刚清的字节填回池子",
        )
    }

    /** 深档（后台系四档）：整屏保留面 + 背景烘焙面，且配对作废脏判定。 */
    @Test
    fun `深档交还整屏面并同批作废脏判定`() {
        val body = bodyOf("fun releaseMemoryCaches(deep: Boolean)")
        val deep = body.substringAfter("if (deep)")
        for (needed in listOf(
            "planner.notifySurfaceLost()",
            "screenSurface = null",
            "screenSurfaceCanvas = null",
            "surfaceWidth = 0",
            "surfaceHeight = 0",
            "backing = null",
            "backingCanvas = null",
            "bakeGuard.invalidate()",
        )) {
            assertTrue(deep.contains(needed), "深档段缺 $needed —— 漏一条就是回前台黑屏或背景永不重烘")
        }
    }

    /** 浅档**不许**动整屏面：玩家正看着这一屏，抽走保留面 = 当场闪一帧空白。 */
    @Test
    fun `浅档不动整屏面`() {
        val light = bodyOf("fun releaseMemoryCaches(deep: Boolean)").substringBefore("if (deep)")
        for (forbidden in listOf("screenSurface", "backing", "notifySurfaceLost", "bakeGuard")) {
            assertFalse(light.contains(forbidden), "浅档段动了 $forbidden：前台收口只许动行缓存与行池")
        }
    }

    /**
     * 全程不 `recycle()`（口径同 [ensureScreenSurface] 的注释）：贴图是异步上屏的，
     * 硬件渲染器可能还持有这一帧的引用，UI 线程当场回收撞
     * `Canvas: trying to use a recycled bitmap` 直接崩。
     */
    @Test
    fun `收口不主动回收位图`() {
        val body = bodyOf("fun releaseMemoryCaches(deep: Boolean)")
        assertFalse(body.contains(".recycle()"), "只许丢引用，不许 recycle —— 理由见函数 KDoc")
        assertFalse(body.contains("recycle("), "只许丢引用，不许 recycle —— 理由见函数 KDoc")
    }

    /** 返回值必须是差分而不是自报数量：收了几个字节由账面说话。 */
    @Test
    fun `返回值取前后账面差`() {
        val body = bodyOf("fun releaseMemoryCaches(deep: Boolean)")
        assertTrue(body.contains("val before = viewBitmapCacheBytes()"), "收口前要先取账面")
        assertTrue(body.contains("before - viewBitmapCacheBytes()"), "收口量必须取前后差")
    }
}
