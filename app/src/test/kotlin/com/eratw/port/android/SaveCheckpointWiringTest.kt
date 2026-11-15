package com.eratw.port.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 堆顶自救「存档检查点」的**接线哨兵**（lane `bug-autosave`，2026-11-10）。
 *
 * `GameHost` 挂在 Android 运行时上（app 的 JVM 单测面没有 Robolectric，实例化不了），
 * 与 [SaveOomReclaimWiringTest] 同族用**源码面**钉接线形状。纯判式与台账由
 * [SaveCheckpointRunnerTest] 钉；这里钉的是**只有源码能证明**的四件事：
 *
 * 1. `saveCheckpointHook` 不再是 null（0e88fb9c9 的产品裁定位已由主人放开）；
 * 2. 实写只能在 VM 线程的两个**既有**语句安全点兑现（`midPageCheckpoint` / `onTurnReady`）——
 *    UI 线程那一发只登记；
 * 3. 写入器是既有 `FileSystemOps.saveData`，槽位 = 官方自动存档 99（跨模块常量对账）；
 * 4. 先逐出后存，且逐出体**不含** `releaseImagePixelsIfHeapHigh`（那会自我递归）。
 *
 * ## 反向证明（怎么让它红）
 *
 * - 把 `midPageCheckpoint` 里的 `drainSaveCheckpoint("midpage")` 删掉 ⇒
 *   「两个安全点都兑现」红，且 VM 长时间跑一页时请求只能等页尾，`SaveCheckpointRunnerTest`
 *   里「安全点恰好写一次」也不再代表真实现；
 * - 把 `saveCheckpointHook` 改回 `= null` ⇒ 第 1 条红（后台自救回到只逐出不存档）；
 * - 把 `evictBeforeSaveCheckpoint` 的逐出换成 `releaseImagePixelsIfHeapHigh()` ⇒ 第 4 条红。
 */
class SaveCheckpointWiringTest {

    private fun sourceOf(name: String): String {
        val candidates = listOf(
            File("src/main/kotlin/com/eratw/port/android/$name"),
            File("../app/src/main/kotlin/com/eratw/port/android/$name"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 $name（试了 ${candidates.joinToString { it.path }}）")
        return file.readText(Charsets.UTF_8)
    }

    /** core-vm 侧的权威常量文件（跨模块对账用；app 单测工作目录 = app/）。 */
    private fun coreBeginFlow(): String {
        val candidates = listOf(
            File("../../erak-engine/core-vm/src/main/kotlin/com/eratw/port/core/proc/BeginFlow.kt"),
            File("../core-vm/src/main/kotlin/com/eratw/port/core/proc/BeginFlow.kt"),
            File("core-vm/src/main/kotlin/com/eratw/port/core/proc/BeginFlow.kt"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 core/proc/BeginFlow.kt（试了 ${candidates.joinToString { it.path }}）")
        return file.readText(Charsets.UTF_8)
    }

    /** 先剥注释再取函数体：注释掉的行不能让闸误判为通过（与 SaveOomReclaimWiringTest 同款）。 */
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
    fun `检查点注入位已接真通道不再为null`() {
        val src = sourceOf("GameHost.kt")
        assertFalse(
            "旧形状（= null 的产品裁定位）必须已被替换，否则后台自救仍然只逐出不存档",
            src.contains("var saveCheckpointHook: (() -> Boolean)? = null"),
        )
        assertTrue(
            "缺省值必须登记一笔真请求",
            src.contains("var saveCheckpointHook: (() -> CheckpointSignal)? = { requestSaveCheckpoint() }"),
        )
        assertTrue(
            "自救那一发必须把「未接线」显式补成三态之一，不许把 null 漏下去",
            src.contains("saveCheckpointHook?.invoke() ?: CheckpointSignal.NOT_WIRED"),
        )
    }

    @Test
    fun `实写只在两个既有语句安全点兑现`() {
        val src = sourceOf("GameHost.kt")
        val mid = bodyOf(src, "private fun midPageCheckpoint(")
        assertTrue(
            "页中检查点（VM 线程、两条语句之间）必须兑现挂起请求",
            mid.contains("drainSaveCheckpoint(\"midpage\")"),
        )
        assertTrue(
            "回合边界（onTurnReady，进入等待点前）必须兑现挂起请求 —— 停机在输入等待时就是靠它",
            src.contains("drainSaveCheckpoint(\"turn\")"),
        )
    }

    @Test
    fun `兑现函数只许VM线程且UI侧只登记`() {
        val src = sourceOf("GameHost.kt")
        val drain = bodyOf(src, "private fun drainSaveCheckpoint(")
        assertTrue("必须硬闸 VM 线程（写通道 = FileSystemOps.saveData 的单线程前提）", drain.contains("onVmThread()"))
        assertTrue("兑现必须走 SaveCheckpointRunner", drain.contains("saveCheckpoint.drainAtSafePoint("))

        val request = bodyOf(src, "private fun requestSaveCheckpoint(")
        assertTrue("UI 侧只许登记", request.contains("saveCheckpoint.request("))
        assertFalse("UI 侧一次 IO 都不许做", request.contains("saveData"))
    }

    @Test
    fun `写入器复用SAVEDATA通道且槽位与core一致为99`() {
        val src = sourceOf("GameHost.kt")
        val writer = bodyOf(src, "private fun saveCheckpointWriter(")
        assertTrue("必须复用既有 SAVEDATA 写入器，不新造文件格式", writer.contains("FileSystemOps"))
        assertTrue("实写只能经 systemOps.saveData", writer.contains("ops.saveData(slot, text)"))

        val appSlot = sourceOf("ExitForensics.kt")
        assertTrue(
            "槽位常量必须是 99（Process.cs:136 const AutoSaveIndex = 99）",
            appSlot.contains("const val AUTO_SAVE_SLOT = 99"),
        )
        assertTrue(
            "core 侧常量必须仍是 99（两侧漂移会让自动存档落到别的槽）",
            coreBeginFlow().contains("const val AUTO_SAVE_SLOT = 99"),
        )
    }

    @Test
    fun `先逐出后存且逐出体不递归`() {
        val src = sourceOf("GameHost.kt")
        val body = bodyOf(src, "private fun evictBeforeSaveCheckpoint(")
        assertTrue("必须收「重解必得同一张图」那一档", body.contains("memory.releaseResourceImagePixels()"))
        assertTrue(
            "必须收画过的合成缓冲（预算档）",
            body.contains("memory.releaseUnusedGraphics(usePixelBudget = true"),
        )
        assertFalse(
            "不许调 releaseImagePixelsIfHeapHigh —— 它会 drainReclaimRequests，同线程同语句序列上会自我递归",
            body.contains("releaseImagePixelsIfHeapHigh"),
        )
    }

    @Test
    fun `标签文本与官方链读同一格SAVEDATA_TEXT`() {
        val src = sourceOf("GameHost.kt")
        val body = bodyOf(src, "private fun currentSavedataText(")
        assertTrue(
            "必须读会话变量池里的 SAVEDATA_TEXT（同一格变量，不许另存旁路状态）",
            body.contains("readStr(\"SAVEDATA_TEXT\""),
        )
        // 官方链的读口（ProcessRuntime 里 calls.savedataText 的实现）必须还是同一行读法：
        // 两侧漂移会让本路写进去的标签与读档界面显示的不是同一格。
        val runtime = File("../../erak-engine/core-vm/src/main/kotlin/com/eratw/port/core/proc/ProcessRuntime.kt")
        assertTrue(
            "找不到 ProcessRuntime.kt（app 单测工作目录 = app/，core-vm 在同级引擎仓 ../erak-engine）",
            runtime.isFile,
        )
        assertTrue(
            "core 侧 savedataText 必须仍读同一格 SAVEDATA_TEXT",
            runtime.readText(Charsets.UTF_8).contains("readStr(\"SAVEDATA_TEXT\""),
        )
    }
    @Test
    fun `台账进报告补充现场`() {
        val src = sourceOf("GameHost.kt")
        assertTrue(
            "失败只留痕不杀局：台账必须被报告读得到（save=ok / save=fail:<class>）",
            src.contains("saveCheckpoint.ledgerLine()"),
        )
    }
}