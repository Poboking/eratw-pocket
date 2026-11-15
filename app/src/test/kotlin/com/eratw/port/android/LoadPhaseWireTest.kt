package com.eratw.port.android

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首屏装载分段进度的 **app 侧接线哨兵**（CONTRACT-BOOTPHASE）。
 *
 * 与 `BootWindowLayoutPolicyTest` 同族：`GameHost` / `MainActivity` 挂在 Android 运行时上，
 * app 的 JVM 单测面没有 Robolectric，实例化不了，所以用源码面钉装配形状。
 *
 * 判读只认**生效代码行**：注释掉的行一律先剥掉（11-15 的教训——`src.contains(装配串)`
 * 会被注释掉的接线骗绿），所以本哨兵同时就是反向证明的判据：撤掉 `loadPhaseSink` 接线
 * 应当当场判红。
 *
 * 本笔不重开性能线：这里不钉任何 core-vm 装载语义，只钉「装载链记账 → 宿主消费」这一条。
 */
class LoadPhaseWireTest {

    private fun sourceOf(name: String): String {
        val candidates = listOf(
            File("src/main/kotlin/com/eratw/port/android/$name"),
            File("../app/src/main/kotlin/com/eratw/port/android/$name"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 $name（试了 ${candidates.joinToString { it.path }}）")
        return file.readText(Charsets.UTF_8)
    }

    /** 生效代码行：剥掉空行与注释行，避免被注释掉的装配串喂绿。 */
    private fun effectiveLines(src: String): List<String> = src.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .filterNot { it.startsWith("//") }
        .filterNot { it.startsWith("/") }
        .filterNot { it.startsWith("*") }
        .toList()

    @Test
    fun `GameHost 的 onSample 串联进度上报且保留 EventJournal 取证面`() {
        val lines = effectiveLines(sourceOf("GameHost.kt"))
        assertTrue(
            "onSample 必须把 LoadTrace.currentProgress 上报出去（反向证明：注释掉此行本用例应红）",
            lines.any { it.startsWith("loadPhaseSink?.invoke(trace.currentProgress())") },
        )
        assertTrue("既有 EventJournal 取证面不许被替换掉", lines.any { it.startsWith("EventJournal.record(") })
        assertTrue(
            "回调必须是缺省 null 的实例字段（照 onConsoleReady 惯例）",
            lines.any { it.startsWith("internal var loadPhaseSink: ((LoadProgress?) -> Unit)? = null") },
        )
        assertTrue(
            "不许把回调塞回构造参数表（会改掉 baseline 既有的 LongParameterList 结构性 ID）",
            lines.none { it.startsWith("private val onLoadPhase:") },
        )
    }

    /** 拆行只为不触发 MaxLineLength；拼起来仍是同一条生效代码行。 */
    private val loadPhaseWireLine = "host.loadPhaseSink = { progress -> " +
        "runOnUiThread { applyLoadPhase(progress) } }"

    @Test
    fun `MainActivity 把进度接进覆盖层进度条与四条分段文案`() {
        val lines = effectiveLines(sourceOf("MainActivity.kt"))
        assertTrue(
            "宿主必须把装载线程回调切回 UI 线程",
            lines.any { it.startsWith(loadPhaseWireLine) },
        )
        assertTrue(
            "真实进度比例必须写进 shell.progress（进度条才有格数可画）",
            lines.any { it == "shell.progress = progress.fraction" },
        )
        listOf(
            "LoadPhase.PROGRAM -> copy.loadPhaseProgramLine",
            "LoadPhase.META -> copy.loadPhaseMetaLine",
            "LoadPhase.SESSION -> copy.loadPhaseSessionLine",
            "LoadPhase.STARTING -> copy.loadPhaseStartingLine",
        ).forEach { line ->
            assertTrue("分段明细缺这一条：$line", lines.any { it == line })
        }
    }

    @Test
    fun `LoadTrace 暴露唯一进度读口`() {
        val lines = effectiveLines(sourceOf("LoadTrace.kt"))
        assertTrue(
            "进度必须由加锁快照的阶段名序列折算，不许另起一套估算",
            lines.any { it.startsWith("internal fun currentProgress(): LoadProgress?") },
        )
        assertTrue(lines.any { it == "LoadPhaseProgress.of(snapshot().map { it.stage })" })
    }

    @Test
    fun `三语文案表新增四条分段明细键`() {
        val lines = effectiveLines(sourceOf("ShellFailureCopy.kt"))
        listOf("loadPhaseProgramLine", "loadPhaseMetaLine", "loadPhaseSessionLine", "loadPhaseStartingLine")
            .forEach { key ->
                assertTrue(
                    "三档都要有 $key（形状闸会另行对账键数）",
                    lines.count { it.startsWith("$key = ") } == 3,
                )
            }
    }
}
