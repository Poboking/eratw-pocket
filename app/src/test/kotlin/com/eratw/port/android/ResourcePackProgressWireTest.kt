package com.eratw.port.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 素材直投（背景图 / BGM 两张卡）**进度接线**的哨兵（第二十一批）。
 *
 * 玩家反馈「导入时间太长，一度以为卡了」：此前 `installSource` 不报任何读数，卡上只有一句
 * 「正在导入素材…」，结果区对进行态什么都不画。这一批加了进度模型 + 卡上进度块 + 忙态拒绝
 * 可见化，失效模式有三类，都在源码面钉：
 *
 * 1. **底下报了、壳层没接**：`installSource` 的 `onProgress` 必须真传进去，并回 UI 线程落到
 *    发起那一槽（漏接就是白写一套进度模型）；
 * 2. **接了但卡上没画**：两张直投卡都要摆 [MaterialImportProgress]，且必须摆在下手按钮之后、
 *    清除按钮之前（玩家的视线顺序是「点 → 看进展 → 决定要不要清」）；
 * 3. **静默失败回潮**：单飞闸拒绝不许再只有一行 `Log.w`，必须落成卡上可见的 `ImportBlocked`。
 *
 * `SettingsActivity` 挂在 Android 运行时上，JVM 单测面实例化不了，照
 * [ResourcePackCleanupWireTest] 的源码面口径钉形状。
 */
class ResourcePackProgressWireTest {

    private fun sourceOf(name: String): String {
        val candidates = listOf(
            File("src/main/kotlin/com/eratw/port/android/$name"),
            File("../app/src/main/kotlin/com/eratw/port/android/$name"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 $name（试了 ${candidates.map { it.path }}）")
        // 仓里是 CRLF 工作树：多行锚点必须先归一，否则「明明在」也 0 命中。
        return file.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }

    @Test
    fun `直投把进度回调真接进平台层`() {
        val src = sourceOf("SettingsActivity.kt")
        assertTrue(
            "直投必须把 onProgress 传给 installSource（不传则整条进度链空转）",
            src.contains(
                "installSource(material, target) { progress ->",
            ),
        )
        assertTrue(
            "进度必须落到发起那一槽（postPackProgress），不能只打日志",
            src.contains("                    postPackProgress(slot, progress)"),
        )
        assertTrue(
            "postPackProgress 必须走 UI 线程并把进度折成 ImportProgressed",
            src.contains("ResourcePackEvent.ImportProgressed(progress)"),
        )
    }

    @Test
    fun `两张直投卡都画进度块`() {
        val src = sourceOf("SettingsActivity.kt")
        assertEquals(
            "背景图卡与 BGM 卡都必须摆进度块（少一张就是那张卡仍然静默）",
            2,
            src.split("MaterialImportProgress(progress = state.progress, copy = copy)").size - 1,
        )
        assertTrue(
            "进度块必须摆在下手按钮之后、清除按钮之前",
            src.indexOf("MaterialImportProgress(progress = state.progress, copy = copy)") <
                src.indexOf("copy.clearBackgroundButton"),
        )
        // 进度块本体：细带走壳层 ShellProgress（不是 Material 的进度条），比例取 fraction。
        assertTrue(
            "进度带必须走壳层的 ShellProgress（Material 那版高度 / 圆角都不可控）",
            src.contains(
                "ShellProgress(accent = UiColors.Accent, progress = fraction, " +
                    "modifier = Modifier.fillMaxWidth())",
            ),
        )
        assertTrue(
            "比例必须取 ResourcePackProgress.fraction（字节优先、退化按文件数、都不可知为 null）",
            src.contains("val fraction = progress?.fraction"),
        )
        // 无读数（或总量不可知）时画不定态扫描带：卡面不许出现一片静止。
        assertTrue(
            "读数为 null 时必须保留进行态那句（同时由 ShellProgress 的 null 档画扫描带）",
            src.contains("null, ResourcePackPhase.SCANNING -> copy.progressScanningText"),
        )
    }

    @Test
    fun `单飞闸拒绝不再静默`() {
        val src = sourceOf("SettingsActivity.kt")
        assertEquals(
            "导入与清理两处单飞拒绝都必须落成可见的 ImportBlocked",
            2,
            src.split("ResourcePackEvent.ImportBlocked(copy.importBlockedText, packStateOf(slot).status)").size - 1,
        )
        assertTrue(
            "旧的静默 return 不许回潮（它让玩家分不清「点了没生效」和「卡死」）",
            !src.contains("\"素材直投请求被忽略：上一次导入还在进行\""),
        )
        assertTrue(
            "清理请求的静默 return 同样不许回潮",
            !src.contains("\"清理请求被忽略：上一次导入或清理还在进行\""),
        )
    }
}
