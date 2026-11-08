package com.eratw.port.android

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 存档求援（堆贴顶）的**回收量口径**接线哨兵（2026-11-07 报告 `5783b90` 残余）。
 *
 * `GameHost` 挂在 Android 运行时上（app 的 JVM 单测面没有 Robolectric，实例化不了），
 * 与 [GameHostSharpWireTest] 同族用**源码面**钉。
 *
 * ## 钉死的缺陷
 *
 * 图的回收分三档：① 可重解像素；② `GraphicsPool.releaseUnused`（预算档 / 够大一律 dispose）；
 * ③ `SpritePool.evictCompositedSpritesWithinBudget`（摘具名 Sprite、dispose 它钉住的降级缓冲）。
 * 旧形状里 ③ 的释放量**没有并进** [GameHost.releaseUnusedGraphics] 的返回值，而
 * `FileSystemOps.retryAfterOom` 只认这个返回值 ⇒ 当 ①② 都被 `exclude` 护住、只有 ③
 * 真腾出空间时，core 读到 0，打出「已回收=0KiB，不重试」，连 GC 提示都不发。
 * 报告现场正是 178 张降级 PNG 压满堆、两发存档都走了「退路」。
 *
 * 所以这里只钉两件可判的事（与 `SessionReleaseFieldAuditTest` 同一条理由：
 * 视图类不可实例化，但接线形状在源码里）：
 * 1. [GameHost.releaseUnusedGraphics] 必须把 ③ 的字节数并进 `ReleaseResult.bytes`；
 * 2. [GameHost.evictCompositedSpritesIfOverBudget] 必须把「真正释放的字节」交回去
 *    （`pixels × 4`，降级态按 `retainedBytes` 入账），而不是恒交 `Unit`。
 */
class SaveOomReclaimWiringTest {

    private fun sourceOf(name: String): String {
        val candidates = listOf(
            File("src/main/kotlin/com/eratw/port/android/$name"),
            File("../app/src/main/kotlin/com/eratw/port/android/$name"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 $name（试了 ${candidates.joinToString { it.path }}）")
        return file.readText(Charsets.UTF_8)
    }

    /** 先剥注释再取函数体：注释掉的行不能让闸误判为通过。 */
    private fun bodyOf(src: String, signature: String): String {
        val code = src
            .replace(Regex("""/\*[\s\S]*?\*/"""), "")
            .lineSequence()
            .joinToString("\n") { line ->
                val i = line.indexOf("//")
                if (i >= 0) line.substring(0, i) else line
            }
        val start = code.indexOf(signature)
        assertTrue("源码里找不到 $signature", start >= 0)
        val open = code.indexOf('{', start)
        assertTrue("$signature 后没有函数体", open >= 0)
        var depth = 0
        var i = open
        while (i < code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(open + 1, i)
                }
                else -> {}
            }
            i++
        }
        error("$signature 的函数体没有配平")
    }

    @Test
    fun `releaseUnusedGraphics 把第三档回收量并进返回值`() {
        val src = sourceOf("GameHost.kt")
        val body = bodyOf(src, "private fun releaseUnusedGraphics(")
        assertTrue(
            "必须接住第三档的释放量（否则它白干、core 读到 0 就不重试存档）",
            body.contains("val thirdTierBytes = evictCompositedSpritesIfOverBudget("),
        )
        assertTrue(
            "第三档字节必须累加进 ReleaseResult.bytes 交回 core",
            body.contains("GraphicsPool.ReleaseResult(result.buffers, result.bytes + thirdTierBytes)"),
        )
    }

    @Test
    fun `合成图逐出档交回真实释放字节数`() {
        val src = sourceOf("GameHost.kt")
        val sig = src.indexOf("private fun evictCompositedSpritesIfOverBudget(")
        assertTrue("找不到 evictCompositedSpritesIfOverBudget", sig >= 0)
        val signature = src.substring(sig, src.indexOf('{', sig))
        assertTrue("这一档必须返回 Long（Unit 会让调用方永远算成 0）", signature.contains("): Long"))

        val body = bodyOf(src, "private fun evictCompositedSpritesIfOverBudget(")
        assertTrue(
            "释放量按像素条数 × 4（降级态就是 PNG 载荷的 retainedBytes）",
            body.contains("val freedBytes = evicted.pixels * 4L"),
        )
        assertTrue("必须把释放量交回去", body.contains("return freedBytes"))
        // 三条早退路径（无 SpritePool / 判式不成立 / 一张没逐出）都得交 0，不能漏。
        assertTrue(
            "早退路径必须 return 0L",
            Regex("""\breturn 0L""").findAll(body).count() >= 3,
        )
    }
}
