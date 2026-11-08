package com.eratw.port.android

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R3 八个事件写点的 **app 侧接线哨兵**（源码面钉形状，与 [GameHostMeasureWireTest] 同族——
 * `GameHost` / `MainActivity` 挂在 Android 运行时上，JVM 单测面实例化不了）。
 *
 * 钉的是「事件真的从这些慢路径发出来」：删掉任一行 `EventJournal.record(...)` 或把它挪出
 * 对应函数，本类当场红。行为判据（编码/环/落盘/轮转）在 core 的 `HostEventCodecTest` /
 * `HostEventRingTest` 与 app 的 `EventJournalTest`，这里只管接线不断。
 */
class EventWiringTest {

    private fun sourceOf(name: String): String {
        val candidates = listOf(
            File("src/main/kotlin/com/eratw/port/android/$name"),
            File("../app/src/main/kotlin/com/eratw/port/android/$name"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 $name（试了 ${candidates.joinToString { it.path }}）")
        // 仓里是 CRLF 工作树：多行锚点必须先归一，否则「明明在」也 0 命中（2026-11-08 实测）。
        return file.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }

    @Test
    fun `GameHost 装载相位经 LoadTrace 监听进事件链`() {
        val src = sourceOf("GameHost.kt")
        assertTrue("装载监听必须装上（trace.onSample = { s ->）", src.contains("trace.onSample = { s ->"))
        assertTrue(
            "load.phase 必须带 stage/usedMiB/maxMiB/t 四列（缺一列因果链就断一段）",
            src.contains("\"load.phase\"") &&
                src.contains("\"stage\" to s.stage") &&
                src.contains("\"usedMiB\" to s.usedMiB.toString()") &&
                src.contains("\"maxMiB\" to s.maxMiB.toString()") &&
                src.contains("\"t\" to s.atMs.toString()"),
        )
    }

    @Test
    fun `GameHost 回合边界与输入提交各有写点`() {
        val src = sourceOf("GameHost.kt")
        assertTrue(
            "turn.end 必须在 onTurnReady 里发（页边界：每页至多一条）",
            src.contains("EventJournal.record(\"turn.end\", mapOf(\"ms\" to ms.toString()))"),
        )
        assertTrue(
            "input.submit 必须在 submitInternal 里发（含被闸丢掉的提交）",
            src.contains("EventJournal.record(\n            \"input.submit\"") &&
                src.contains("\"kind\" to when {") &&
                src.contains("\"len\" to text.length.toString()"),
        )
    }

    @Test
    fun `GameHost 错误与回收写点`() {
        val src = sourceOf("GameHost.kt")
        assertTrue(
            "error 必须在 writeErrorReport 开头发（kind + 失败族/相位）",
            src.contains("EventJournal.record(\n            \"error\",\n            mapOf(\"kind\" to kind, \"phase\" to lastFailureScreenKind.name),"),
        )
        assertTrue(
            "回收量非零时发 resource.evict（0 = 没收到，不记）",
            src.contains("private fun journalEvict(freedMiB: Long)") &&
                src.contains("if (freedMiB <= 0L) return") &&
                src.contains("EventJournal.record(\"resource.evict\", mapOf(\"freedMiB\" to freedMiB.toString()))"),
        )
        assertTrue(
            "回合边界各路回收都要经 journalEvict 记账（至少预算档 + 堆水位档）",
            src.split("journalEvict(").size - 1 >= 2,
        )
    }

    @Test
    fun `MainActivity 与 EratwApplication 的进程级写点`() {
        val activity = sourceOf("MainActivity.kt")
        assertTrue("oom 必须在 onTrimMemory 里发", activity.contains("EventJournal.record(\"oom\", mapOf(\"level\" to level.toString()))"))
        assertTrue(
            "dirty_exit 必须在 RunWatch.beginProcess 成功分支发（state=dirty/clean）",
            activity.contains(".onSuccess { dirtyExit ->") &&
                activity.contains("EventJournal.record(\"dirty_exit\", mapOf(\"state\" to if (dirtyExit) \"dirty\" else \"clean\"))"),
        )
        val app = sourceOf("EratwApplication.kt")
        assertTrue("主进程开局必须绑定落盘目录", app.contains("EventJournal.begin(this)"))
        assertTrue(
            "session.start 必须带 build + pid",
            app.contains("\"session.start\"") &&
                app.contains("\"build\" to \"\${BuildConfig.VERSION_NAME}+\${BuildConfig.VERSION_CODE}\"") &&
                app.contains("\"pid\" to android.os.Process.myPid().toString()"),
        )
    }

    @Test
    fun `DevTools 声明 caps 并暴露 events`() {
        val src = sourceOf("DevToolsHost.kt")
        assertTrue("caps 必须在命令分派表里", src.contains("\"caps\" -> caps()"))
        assertTrue("events 必须在命令分派表里（缺省 50 行）", src.contains("\"events\" -> events(arg.toIntOrNull() ?: 50)"))
        assertTrue("caps 一行声明 schema 与四类能力", src.contains("schema=1 caps=read,write,snapshot,events"))
        assertTrue("events 未 begin 时如实说只读内存环", src.contains("mem-only（未绑定落盘目录）"))
    }
}
