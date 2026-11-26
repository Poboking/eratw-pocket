package com.eratw.port.android

import com.eratw.port.core.save.SaveDiagnostics
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * task-102 判据（A）：`SaveDiagnostics` 环形缓冲在报告里的**按 tag 分节**。
 *
 * 记法（同一枚缓冲，两个写入面）：
 * - 存档侧（`SAVEDATA` / `SIDECAR_GC` / `WRITE` / `SIDECAR_MOVE` …）→ 「存档落盘留痕」小节；
 * - 模组包侧（唯一 tag `MOD_PKG_GC`，[ModPackageInventory] / [ModSettingsStore] 写）→
 *   「模组包清理留痕」独立小节。
 *
 * 钉四件事：`MOD_PKG_GC` 不再出现在存档节；`SIDECAR_GC` 仍在存档节；无行不出空节；
 * 路径/详情里偶然含 `MOD_PKG_GC` 字样的存档行不许被误归。
 *
 * 全部纯 JVM：直接调 [ErrorReportCollector.saveDiagLines]（只读环形缓冲，不碰 Android Context）。
 */
class ErrorReportModPkgGcSectionTest {

    private val saveHeading = "存档落盘留痕"
    private val modHeading = "模组包清理留痕"

    @Before
    fun clearDiag() = SaveDiagnostics.clear()

    @After
    fun clearAfter() = SaveDiagnostics.clear()

    private fun headOf(lines: List<String>): String = lines.firstOrNull().orEmpty()

    @Test
    fun `MOD_PKG_GC落在模组包清理留痕独立小节`() {
        SaveDiagnostics.note("MOD_PKG_GC", "kojo-aaaa1111.assets.tmp-1", "资源面残留目录已删除（夹具）")

        val lines = ErrorReportCollector.saveDiagLines()

        assertTrue("必须出模组包清理留痕小节，实得 $lines", headOf(lines).startsWith(modHeading))
        assertTrue("该节要带条数说明，实得 ${headOf(lines)}", headOf(lines).contains("最近 1 条"))
        assertTrue("证据行本体必须原样保留", lines.drop(1).any { it.contains("MOD_PKG_GC") })
        assertFalse(
            "只有模组包行时不许出空的「存档落盘留痕」节：$lines",
            lines.any { it.startsWith(saveHeading) },
        )
    }

    @Test
    fun `SIDECAR_GC仍在存档落盘留痕小节`() {
        SaveDiagnostics.note("SIDECAR_GC", "save07.sav.rt2", "伴生件残留已删除（夹具）")

        val lines = ErrorReportCollector.saveDiagLines()

        assertTrue("存档侧留痕必须仍在存档节，实得 $lines", headOf(lines).startsWith(saveHeading))
        assertTrue("该节证据行要原样保留", lines.drop(1).any { it.contains("SIDECAR_GC") })
        assertFalse(
            "只有存档行时不许出空的「模组包清理留痕」节：$lines",
            lines.any { it.startsWith(modHeading) },
        )
    }

    @Test
    fun `无任何留痕时两个小节都不出`() {
        assertEquals("空缓冲必须返回空表（不出空节）", emptyList<String>(), ErrorReportCollector.saveDiagLines())
    }

    @Test
    fun `混排时两节各自计数且旧在前顺序不变`() {
        SaveDiagnostics.note("WRITE", "save01.sav", "原子换名失败，已退原地覆盖（夹具①）")
        SaveDiagnostics.note("MOD_PKG_GC", "kojo-bbbb2222.settings.tmp", "设置过程残留文件已删除（夹具）")
        SaveDiagnostics.note("SIDECAR_GC", "save02.sav.rt2", "伴生件残留已删除（夹具②）")
        SaveDiagnostics.note("MOD_PKG_GC", "kojo-cccc3333.assets.tmp-2", "资源面残留目录已删除（夹具）")

        val lines = ErrorReportCollector.saveDiagLines()

        val saveAt = lines.indexOfFirst { it.startsWith(saveHeading) }
        val modAt = lines.indexOfFirst { it.startsWith(modHeading) }
        assertTrue("两个小节都必须出现：$lines", saveAt >= 0 && modAt >= 0)
        assertTrue("存档节要按存档侧两行计数", lines[saveAt].contains("最近 2 条"))
        assertTrue("模组包节要按模组包侧两行计数", lines[modAt].contains("最近 2 条"))
        assertFalse("模组包行不许落进存档节", lines.subList(saveAt, modAt).any { it.contains("MOD_PKG_GC") })
        assertFalse("存档行不许落进模组包节", lines.drop(modAt).any { it.contains("SIDECAR_GC") })
        assertTrue(
            "同一节内保持旧在前",
            lines[modAt + 1].contains("kojo-bbbb2222") && lines[modAt + 2].contains("kojo-cccc3333"),
        )
    }

    @Test
    fun `路径里含MOD_PKG_GC字样——存档行不被误归到模组包节`() {
        SaveDiagnostics.note("WRITE", "logs/MOD_PKG_GC-backup.txt", "原子换名失败（夹具）")

        val lines = ErrorReportCollector.saveDiagLines()

        assertTrue("该行是存档侧，必须落存档节，实得 $lines", headOf(lines).startsWith(saveHeading))
        assertFalse("不许为它凭空出模组包节：$lines", lines.any { it.startsWith(modHeading) })
    }
}
