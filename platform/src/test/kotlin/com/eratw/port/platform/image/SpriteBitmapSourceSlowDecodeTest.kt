package com.eratw.port.platform.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主线程同步解码的慢样本取证行（BOARD 在册缺口 ⑦，EP1 波 · L4 stall-stack）。
 *
 * ## 钉的是什么
 *
 * `SpriteBitmapSource` 在缓存**未命中**时是**主线程同步解码**：`onDraw` → `frame` → `frameOf`
 * → `decodeFileResource`（SpriteBitmapSource.kt 的 `files.decodeByResourceName`）与 `bitmapOf`
 * 的 fileSource 支（`files.decodeFileByPath`）。语料最大的单张立绘工作表是 `8000x4000` 的 webp，
 * 解成 ARGB_8888 是 8000×4000×4 = 128,000,000 B ≈ **122 MiB**（同一式在本文件的
 * `SHEET_CACHE_BUDGET_BYTES` KDoc 里推导过），而那张表一旦撞不进预算就会**每帧重解**
 * （`sheetBytes` KDoc 记过那条退化）。此前这条链在日志里一个字都不留 ⇒ 玩家那句
 * 「点去白玉楼时候未响应」只能读成 ANR，读不出是哪张图。
 *
 * ## 判式为什么必须钉死（两种写错都只能装机才发现）
 *
 * - **门槛太低**：正常的小图取图（几毫秒到几十毫秒）也被记出来 ⇒ logcat 天天是行，
 *   真慢的那条被淹掉；
 * - **没有同键冷却**：逐出后每帧重解的形 ⇒ 60 行/秒，正是本用例要防的那一形。
 *
 * 纯 JVM 口径：只测 companion 的判式与常数，**不构造** `SpriteBitmapSource`
 * （构造期要建 `Handler(Looper.getMainLooper())` 与三张 `LruCache`，同
 * [SpriteBitmapSourceSheetBudgetTest] / `SpriteBitmapSourcePackTest` 的先例）。
 * 真机上「200 ms 会命中多少行」没有设备 ⇒ 交付报告 TODO(verify)，这里不编读数。
 */
class SpriteBitmapSourceSlowDecodeTest {

    /** 语料最大单张工作表解码后的字节数（ARGB_8888）。 */
    private val largestSheetBytes: Long = 8000L * 4000L * 4L

    // ---- 门槛的取值理由 ----

    @Test
    fun `门槛是板上草稿那个二百毫秒且不低到把正常取图也算进去`() {
        // 200 ms：BOARD 在册缺口 ⑦ 判词里那份作废草稿用的就是这个数，沿用才与前一路人工读报可比；
        // 同时它明显高于「缓存命中/小图」抖动（一帧预算 16.7 ms 的十倍上下），不会天天刷屏。
        assertEquals(200L, SpriteBitmapSource.SLOW_DECODE_LOG_MS)
        assertTrue(
            "门槛必须远高于一次帧预算（16.7ms），否则正常取图也报",
            SpriteBitmapSource.SLOW_DECODE_LOG_MS > 100L,
        )
        assertTrue(
            "门槛必须远低于玩家报 ANR 的那一档（本仓「卡死」下沿 5 000ms），否则只读得到「已经卡死」",
            SpriteBitmapSource.SLOW_DECODE_LOG_MS < 5_000L,
        )
    }

    @Test
    fun `门槛对应的最坏形状是语料最大单张的一二二MiB解码`() {
        // 122 MiB 这一张（推导同 SHEET_CACHE_BUDGET_BYTES 那条：8000×4000×4）就是
        // 「主线程同步解码」的最坏形状；门槛取到秒级就只能读到「已经卡死」，读不出是哪张图。
        // 这里钉的是**式子**，不是真机毫秒读数（本机 adb devices 为空 ⇒ TODO(verify)）。
        assertEquals(122L, largestSheetBytes / (1024L * 1024L))
        // 常态下这张**容得进** sheetCache ⇒ 只有未命中的那一帧慢；撞不进预算（或被逐出）
        // 才是「每帧重解」那一形，判式里的同键冷却就是为它设的（见下一条用例）。
        assertTrue(SpriteBitmapSource.fitsSheetCache(largestSheetBytes, SpriteBitmapSource.SHEET_CACHE_BUDGET_BYTES))
    }

    @Test
    fun `同键冷却与长页钉发同量级以免每帧重解刷屏`() {
        // 「逐出 ⇒ 每帧重解」那一形没有冷却就是 60 行/秒；5 s 与 app 侧
        // RunTrail.LONG_TURN_PIN_COOLDOWN_MS 同量级（跨模块只作口径引用，platform 不依赖 app）。
        assertEquals(5_000L, SpriteBitmapSource.SLOW_DECODE_LOG_COOLDOWN_MS)
    }

    // ---- 判式三条 ----

    @Test
    fun `只有主线程的慢解码才报`() {
        assertTrue(SpriteBitmapSource.shouldLogSlowDecode(onMain = true, elapsedMs = 200L, sameKeyAsLast = false, sinceLastLogMs = -1L))
        assertFalse(
            "VM 线程 / 预取线程慢是提前量问题，与「点了没反应」无关",
            SpriteBitmapSource.shouldLogSlowDecode(onMain = false, elapsedMs = 9_000L, sameKeyAsLast = false, sinceLastLogMs = -1L),
        )
    }

    @Test
    fun `门槛边界含等号差一毫秒不报`() {
        assertTrue(SpriteBitmapSource.shouldLogSlowDecode(true, SpriteBitmapSource.SLOW_DECODE_LOG_MS, false, -1L))
        assertFalse(SpriteBitmapSource.shouldLogSlowDecode(true, SpriteBitmapSource.SLOW_DECODE_LOG_MS - 1L, false, -1L))
        assertFalse("负耗时（假时钟倒着走）不许变成一行读数", SpriteBitmapSource.shouldLogSlowDecode(true, -5L, false, -1L))
    }

    @Test
    fun `同键在冷却内只报一行`() {
        assertFalse(
            "同一张图在 5 秒内的第二次慢解码不再记行（每帧重解那一形）",
            SpriteBitmapSource.shouldLogSlowDecode(true, 900L, sameKeyAsLast = true, sinceLastLogMs = SpriteBitmapSource.SLOW_DECODE_LOG_COOLDOWN_MS - 1L),
        )
        assertTrue(
            "冷却到点照报：让「还在慢、慢到第几秒」在日志里是一条斜线",
            SpriteBitmapSource.shouldLogSlowDecode(true, 900L, sameKeyAsLast = true, sinceLastLogMs = SpriteBitmapSource.SLOW_DECODE_LOG_COOLDOWN_MS),
        )
        assertTrue(
            "还没记过（负数）时首条必走",
            SpriteBitmapSource.shouldLogSlowDecode(true, 900L, sameKeyAsLast = true, sinceLastLogMs = -1L),
        )
    }

    @Test
    fun `不同键之间不互相压制`() {
        assertTrue(
            "一屏两张大图都在慢是两件事，各记一行",
            SpriteBitmapSource.shouldLogSlowDecode(true, 300L, sameKeyAsLast = false, sinceLastLogMs = 0L),
        )
    }

    // ---- 零行为：取证口只判「报不报」，不改解码语义 ----

    @Test
    fun `取证行不碰任何缓存与解码判式`() {
        // 源码面（口径同 app 侧 StrictModeGuardTest）：logSlowDecode 里只允许时钟、判式与一行 Log，
        // 不许长出缓存读写、回收、负缓存登记这类动作 —— 那正是「顺手改行为」的形状。
        val file = sourceFile()
        val body = file.readText(Charsets.UTF_8)
            .substringAfter("private fun logSlowDecode(")
            .substringBefore("\n    }")
        for (banned in listOf("fileCache", "sheetCache", "bufferCache", "missingFiles", "missingPaths", "recycle", "evictAll", "prefetch")) {
            assertFalse("取证行里不该出现「$banned」：$body", body.contains(banned))
        }
        assertTrue("判式必须走纯函数（可 JVM 直测）：$body", body.contains("shouldLogSlowDecode("))
        assertTrue("整段要 runCatching 兜住（埋点不许把游戏搞崩）", body.contains("runCatching"))
        assertTrue("两条同步解码路都要覆盖：资源名", file.readText(Charsets.UTF_8).contains("logSlowDecode(\"资源名 \$resource\""))
        assertTrue("两条同步解码路都要覆盖：路径", file.readText(Charsets.UTF_8).contains("logSlowDecode(\"路径 \$sourcePath\""))
    }

    private fun sourceFile(): java.io.File {
        val candidates = listOf(
            java.io.File("../platform/src/main/kotlin/com/eratw/port/platform/image/SpriteBitmapSource.kt"),
            java.io.File("platform/src/main/kotlin/com/eratw/port/platform/image/SpriteBitmapSource.kt"),
            java.io.File("src/main/kotlin/com/eratw/port/platform/image/SpriteBitmapSource.kt"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("找不到 SpriteBitmapSource.kt（试了 ${candidates.joinToString { it.path }}）")
    }
}
