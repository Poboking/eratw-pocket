package com.eratw.port.render.canvas

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 本屏三块 native 位图（整屏保留面 / 背景烘焙面 / 行烤图）的**分配失败不抛穿**闸
 * （2026-11-05，lane oomalloc）。
 *
 * ## 钉的是哪条病
 *
 * 端点 0.2.38/0.2.39 那批 512 MiB 挡报告里，Java 堆侧的分配点已由
 * [com.eratw.port.render.memory.PixelPreflight] 在**要之前**判一次；本屏这三块是
 * **native** 位图，Android 不暴露单进程 native 上限（没有可对的分母，理由逐字在册：
 * `platform/.../SpriteBitmapSource.kt` 的 `SHEET_CACHE_BUDGET_BYTES`、
 * `app/.../GraphicsTrimPlan.kt` 的「水位判据只看 Java 堆」两段），所以本路对它们只做两件事：
 *
 * 1. **兜住**：`Bitmap.createBitmap` 的 `OutOfMemoryError` 是 Error，
 *    `onDraw` 那道 `catch (t: RuntimeException)` 拦不住 —— 不站地兜住就是「烤一行行图 / 铺一张背景」
 *    这种纯优化动作把整局带走。
 * 2. **落到既有降级出口**：保留面 → `drawScreen` 的 `?: return`（这一帧不画，下一帧重试）；
 *    行烤图 → `rasterTextRow` 本来就有的逐段直画支（一个字都不少）；
 *    烘焙面 → `backing == null` 的自愈条件（同 `EmueraConsole.cs:924-927` 的 null 分支）。
 *    失败前先把本屏自有、可重建的行缓存与行池交出去（`releaseMemoryCaches(deep = false)`），
 *    这是「要么不炸」的那一半。
 *
 * ## 为什么又是源码面
 *
 * 口径同 [CanvasMemoryReleaseAuditTest]：`CanvasConsoleView` 是 Android `View`，
 * 本模块只有纯 JVM 单测（无 Robolectric），实例化不了；本机 `adb devices` 为空 ⇒
 * 「native 分配不下」的真机形态取不到读数。本夹具不冒充驻留读数，只钉形状与配对纪律，
 * 其中「createBitmap 真的抛 OOM 时游戏继续」那条登记为**需真机验证**。
 */
class CanvasBitmapOomDegradeAuditTest {

    private val src: String by lazy {
        Path.of("").toAbsolutePath()
            .resolve("src/main/kotlin/com/eratw/port/render/canvas/CanvasConsoleView.kt")
            .readText()
    }

    /** 取某个 `fun xxx(` 的函数体（按大括号配平；与 [CanvasMemoryReleaseAuditTest] 同一把尺）。 */
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

    /** 三块位图的分配点各自所在的那个函数（少一个函数就是漏闸）。 */
    private val allocators = listOf(
        "private fun ensureScreenSurface(bgArgb: Int): Canvas?",
        "private fun repaintBacking(",
        "private fun obtainRowBitmap(w: Int, h: Int): Bitmap?",
    )

    /** 每一个 `Bitmap.createBitmap` 点都必须被 `catch (t: OutOfMemoryError)` 罩着。 */
    @Test
    fun `三处位图分配都兜住 OOM 且都先腾自有缓存`() {
        for (signature in allocators) {
            val body = bodyOf(signature)
            assertTrue(body.contains("Bitmap.createBitmap"), "$signature 里应当有一处 createBitmap")
            assertTrue(
                body.contains("catch (t: OutOfMemoryError)"),
                "$signature 的 createBitmap 没有兜 OutOfMemoryError —— Error 不被 onDraw 的 RuntimeException 拦下",
            )
            assertTrue(
                body.contains("releaseMemoryCaches(deep = false)"),
                "$signature 失败前必须先把行缓存与行池交出去（先腾再降级）",
            )
        }
        // 全文件不许有没被 try 罩住的裸分配点。
        assertEquals(3, Regex("""Bitmap\.createBitmap\(""").findAll(src).count(),
            "本屏的 native 位图分配点应正好三处；新增一处就要同步新增一支降级")
    }

    /** 保留面失败走的是既有出口：返回 null（`drawScreen` 的 `?: return` 那条支）。 */
    @Test
    fun `保留面分配失败返回空而非抛出`() {
        val afterCatch = bodyOf(allocators[0]).substringAfter("catch (t: OutOfMemoryError)")
        assertTrue(afterCatch.contains("return null"), "保留面那支必须 return null 落回既有降级")
        assertTrue(
            src.contains("val target = ensureScreenSurface(console.backgroundArgb) ?: return"),
            "调用方必须认这条 null 出口",
        )
    }

    /** 行烤图失败落回**逐段直画**（画面一个字不少，只是不缓存），所以取图口必须是可空返回。 */
    @Test
    fun `行位图拿不到时落回逐段直画`() {
        assertTrue(bodyOf(allocators[2]).substringAfter("catch (t: OutOfMemoryError)").contains("null"))
        val row = bodyOf("private fun rasterTextRow(")
        assertTrue(row.contains("val bmp = obtainRowBitmap(wPx, hPx)"), "取行位图的入口没变")
        assertTrue(row.contains("if (bmp != null)"), "缺了 null 分支就是让烤图失败直冲 OOM")
        // 分支之后必须仍然有「逐段直画」那一支（本函数末尾的原生形态），否则少画一整行。
        val tail = row.substringAfter("if (bmp != null)")
        assertTrue(tail.contains("drawCmd("), "行位图拿不到时必须有逐段直画兜底")
        assertTrue(tail.contains("target.restore()"), "兜底支也要 restore，否则 clip 泄漏到下一行")
    }

    /** 烘焙面失败要把 backing 归 null（下一帧 `backing == null` 的自愈条件才会重烘）。 */
    @Test
    fun `烘焙面失败时把 backing 归 null`() {
        val afterCatch = bodyOf(allocators[1]).substringAfter("catch (t: OutOfMemoryError)")
        assertTrue(afterCatch.contains("backing = null"), "backing 不归 null 就会让 bakeGuard 以为已经烘好")
        assertTrue(afterCatch.contains("backingCanvas = null"))
        assertTrue(src.contains("val bmp = backing ?: return"), "绘制侧的缺背景降级支必须在")
    }

    /**
     * 两个池子的账不许混：本屏这三块是 native，**不许**拿 Java 堆头寸（`PixelPreflight`）
     * 去预检它们 —— 那样会把「Java 堆空闲」当成「native 可分配」来用，读数一错就把该画的画面判没了。
     */
    @Test
    fun `native 位图不套 Java 堆预检`() {
        assertFalse(src.contains("PixelPreflight"), "本屏的 native 分配点只做兜住 + 降级，不做 Java 堆预检")
    }
}
