package com.eratw.port.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「清除已导入素材」的**接线哨兵**（CONTRACT-CLEAR-R1 §2 / W 车道）。
 *
 * 这一路的失效模式是「底下修好了、按钮没接上」或「接上了但没走平台层」：
 *
 * 1. 卡片上的两颗按钮必须真把 `onClear` 往下传到 Activity（少传一层就是死按钮）；
 * 2. 确认框点「清除」之后才调 [com.eratw.port.platform.storage.ResourcePackCleanup.clear]，
 *    且**必须**把 APK assets 树一起传下去 —— 平台侧「基准源存活闸」在盘上摘要读不到时只认它，
 *    漏传的后果是老盘上永远只回一句「基准清单读不到」；
 * 3. 清理与导入共用同一把单飞闸（`resourcePackBusy`），否则两批文件系统写入互相踩；
 * 4. 忙碌判据必须把 `Clearing` 算进去，否则删除进行中还能再点导入；
 * 5. 玩家面一句话都不许在壳层内联：按钮标签、确认框三个键、结果区句子全部走 [ResourcePackCopy]。
 *
 * `SettingsActivity` 挂在 Android 运行时上，JVM 单测面实例化不了，照
 * [FlatStatementsPreflightWireTest] / [QolGateWireTest] 的源码面口径钉形状。
 */
class ResourcePackCleanupWireTest {

    private fun sourceOf(name: String): String {
        val candidates = listOf(
            File("src/main/kotlin/com/eratw/port/android/$name"),
            File("../app/src/main/kotlin/com/eratw/port/android/$name"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 $name（试了 ${candidates.joinToString { it.path }}）")
        // 仓里是 CRLF 工作树：多行锚点必须先归一，否则「明明在」也 0 命中。
        return file.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }

    @Test
    fun `清理调用走平台层且必须带上 APK assets 做基准`() {
        val src = sourceOf("SettingsActivity.kt")
        assertTrue(
            "清理必须调平台层 ResourcePackCleanup.clear（壳层不许自己遍历删文件）",
            src.contains("                val report = ResourcePackCleanup.clear(gameRootFile, target, base)"),
        )
        assertTrue(
            "基准源必须是 APK assets 树：base 为空时平台侧存活闸会判「基准清单读不到」，" +
                "老盘上清理永远做不成",
            src.contains("        val base = AndroidAssetTree(assets)"),
        )
        assertTrue(
            "assets 树必须在清理调用之前取好（后台线程不读宿主）",
            src.indexOf("val base = AndroidAssetTree(assets)") <
                src.indexOf("val report = ResourcePackCleanup.clear(gameRootFile, target, base)"),
        )
    }

    @Test
    fun `清理与导入共用同一把单飞闸`() {
        val src = sourceOf("SettingsActivity.kt")
        val gate = "resourcePackBusy.compareAndSet(false, true)"
        assertEquals(
            "三处单飞入口（压缩包导入 / 直投导入 / 清理）都必须过同一把闸 resourcePackBusy：" +
                "少一处就能让两批文件系统写入互相踩",
            3,
            src.split(gate).size - 1,
        )
        assertTrue(
            "清理线程必须在 finally 里放闸，否则一次异常就让素材卡永久变灰",
            src.contains("            } finally {\n                resourcePackBusy.set(false)\n            }\n        }, \"eratw-respack-clear\").start()"),
        )
    }

    @Test
    fun `两张素材卡都把清理入口传下去了`() {
        val src = sourceOf("SettingsActivity.kt")
        assertTrue(
            "背景图卡必须带上 onClear（少传一层就是死按钮）",
            src.contains("                                        onClear = onClearBackground,"),
        )
        assertTrue(
            "BGM 卡必须带上 onClear（少传一层就是死按钮）",
            src.contains("                                        onClear = onClearBgm,"),
        )
        assertEquals(
            "两颗清理按钮都要真的渲染出来",
            2,
            src.split("        MaterialClearButton(").size - 1,
        )
        assertTrue(
            "按钮标签走文案表，不许内联",
            src.contains("text = if (state is ResourcePackUiState.Clearing) copy.clearRunningText else copy.clearBackgroundButton,") &&
                src.contains("text = if (state is ResourcePackUiState.Clearing) copy.clearRunningText else copy.clearBgmButton,"),
        )
    }

    @Test
    fun `确认框挡住删除动作且句子全走文案表`() {
        val src = sourceOf("SettingsActivity.kt")
        assertTrue(
            "确认框必须先清 clearTarget 再动手（防连点两次跑两遍清理）",
            // 第四批把确认框统一到 ShellDialogActions 之后动作参数名是 onConfirm（原为 onClick），
            // 锚点跟着改；钉的仍是「先置空、再动盘」这个顺序。
            src.contains(
                "                            onConfirm = {\n" +
                    "                                clearTarget = null\n" +
                    "                                clearImportedMaterials(pendingClear)",
            ),
        )
        assertEquals(
            "清理只许从确认框那一处发起：除函数定义外不得有第二个调用点（卡片按钮只置 clearTarget）",
            1,
            src.split("clearImportedMaterials(pendingClear)").size - 1,
        )
        val copyKeys = listOf(
            "copy.clearConfirmTitle",
            "copy.clearConfirmOk",
            "copy.clearConfirmCancel",
            "clearConfirmBody(materialCopy, pendingClear)",
        )
        copyKeys.forEach { key ->
            val actual = if (key.startsWith("clearConfirmBody")) key else key.replace("copy.", "materialCopy.")
            assertTrue("确认框必须用文案表的 $actual，不许内联字面量", src.contains(actual))
        }
    }

    @Test
    fun `忙碌判据把清理态算进去`() {
        val src = sourceOf("SettingsActivity.kt")
        val expected = listOf(
            "                                val materialBusy = backgroundPackState.isPackBusy ||",
            "                                    bgmPackState.isPackBusy ||",
            "                                    archivePackState.isPackBusy",
        ).joinToString("\n")
        assertTrue("素材卡忙碌判据必须经 isPackBusy（它同时算 Importing 与 Clearing）", src.contains(expected))
        assertTrue(
            "isPackBusy 必须同时覆盖导入与清理两态：只算 Importing 会让清理进行中还能再点导入",
            src.contains("    get() = this is ResourcePackUiState.Importing || this is ResourcePackUiState.Clearing"),
        )
    }

    @Test
    fun `清理结果区只在真动了盘时才顶重启按钮`() {
        val src = sourceOf("SettingsActivity.kt")
        assertTrue(
            "结果区必须只在删了文件或被覆盖的随包文件被还原时给重启按钮与提示：" +
                "「没找到需要清理的东西」不该顺手让人重开一局",
            src.contains("    if (summary.deletedFiles > 0 || summary.restoredBaseFiles > 0) {") &&
                src.contains(
                    "        Text(text = copy.clearRestartHint, color = UiColors.TextMuted, style = ShellType.Label)",
                ),
        )
        assertTrue(
            "清理进行态要有一句进行中文案，不能一片空白",
            src.contains("            text = copy.clearRunningText,"),
        )
    }
}
