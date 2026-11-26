package com.eratw.port.android

import com.eratw.port.core.save.SaveDiagnostics
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * task-95 收口判据：`mods/rt2/<packageId>.settings` 纳入**包成员面**的三面。
 *
 * 钉四件事：
 * 1. **同源拼装**：成员名 / 路径 / 原子写临时名都出自 [ModSettingsStore]，[ModPackageInventory]
 *    里不得再写 `.settings` 字面量（源码级哨兵，防将来两边各自改名漂移）；
 * 2. **卸载删净**：显式卸载删设置，不碰别的包设置；
 * 3. **孤儿 GC 连带删**：只有该包被既有 orphanishReason 判为**半成品**时才连带删设置；
 *    源失联已不构成删除依据（2026-11-18 审计裁决撤销），改为整套保留 + 一条取证行；
 *    只有设置 / 只有资源目录 / 健康包一律保留（作者先摆设置再导入是合法顺序）；
 * 4. **过程残留**：`.<packageId>.settings.tmp` 在无同名正式设置文件、且 `lastModified` 早于
 *    24h 时间窗（[ModPackageInventory.RESIDUE_GC_MIN_AGE_MILLIS]）时清掉并留痕；有活配置、
 *    窗内（可能是在途写）/ 时间不可读时保守保留；非 `kojo-` 前缀 / 坏形状 / 目录不碰。
 */
class ModSettingsPackageMemberTest {

    private fun tempRoot(prefix: String): File = Files.createTempDirectory(prefix).toFile()

    private fun rt2Of(root: File): File = File(root, "mods/rt2").apply { mkdirs() }

    private fun modsOf(root: File): File = File(root, "mods").apply { mkdirs() }

    private fun writeArtifacts(dir: File, packageId: String) {
        for (name in ModPackageInventory.memberNames(packageId)) {
            File(dir, name).writeText("artifact:$name", Charsets.UTF_8)
        }
    }

    private fun writeSettings(dir: File, packageId: String, volume: String = "8") {
        assertTrue(
            "测试前置：设置应写入成功",
            ModSettingsStore.write(dir, packageId, mapOf("cfg.volume" to volume)),
        )
    }

    // ------------------------------------------------------------------
    // 判据 1：同源拼装 + 源码哨兵
    // ------------------------------------------------------------------

    @Test
    fun `成员与残留名同源导出且删除名单含设置侧车`() {
        val id = "kojo-1a2b3c4d"
        assertEquals(id + ModSettingsStore.SETTINGS_EXTENSION, ModSettingsStore.memberName(id))
        assertEquals(
            "." + ModSettingsStore.memberName(id) + ".tmp",
            ModSettingsStore.tempMemberName(id),
        )
        assertEquals(id, ModSettingsStore.tempResiduePackageId(ModSettingsStore.tempMemberName(id)))
        for (bad in listOf("", "kojo-x.settings", ".kojo-x.settings", "..settings.tmp", "kojo-x.tmp")) {
            assertNull("非残留形状必须返回 null：$bad", ModSettingsStore.tempResiduePackageId(bad))
        }

        // 导入写序名单仍只列导入产物（设置由设置页独立写）；删除面由卸载侧 = 本名单 + 设置。
        assertFalse(
            "memberNames 是导入写序契约，不得混入设置（导入后不一定存在）",
            ModPackageInventory.memberNames(id).contains(ModSettingsStore.memberName(id)),
        )
    }

    @Test
    fun `源码哨兵 包成员面不另写设置字面量而引用同源出口`() {
        val inventory = sourceOf("ModPackageInventory.kt")
        assertFalse(
            "ModPackageInventory 里不得出现 \".settings\" 字面量（必须引用 ModSettingsStore）",
            inventory.contains("\".settings\""),
        )
        assertFalse(
            "ModPackageInventory 里不得出现 \".settings.tmp\" 字面量",
            inventory.contains("\".settings.tmp\""),
        )
        assertTrue(
            "成员名必须经 ModSettingsStore.memberName 拼装",
            inventory.contains("ModSettingsStore.memberName("),
        )
        assertTrue(
            "成员后缀必须引用 ModSettingsStore.SETTINGS_EXTENSION",
            inventory.contains("ModSettingsStore.SETTINGS_EXTENSION"),
        )
        assertTrue(
            "设置残留清理必须经 ModSettingsStore.gcTempResidues 转交（同源归属）",
            inventory.contains("ModSettingsStore.gcTempResidues("),
        )

        val store = sourceOf("ModSettingsStore.kt")
        assertTrue(
            "settingsFile 必须走 memberName 单源拼装",
            store.contains("File(rt2Dir, memberName(packageId))"),
        )
        assertTrue(
            "write 的临时文件必须走 tempMemberName 单源拼装",
            store.contains("File(rt2Dir, tempMemberName(packageId))"),
        )
        assertTrue(
            "残留清理必须走 tempResiduePackageId 同源解析",
            store.contains("tempResiduePackageId(file.name)"),
        )

        // 删除面成员拼装也走同源出口：卸载侧不得另写 `.settings` 字面量。
        val uninstall = sourceOf("ModManageUninstall.kt")
        assertFalse(
            "ModManageUninstall 里不得出现 \".settings\" 字面量",
            uninstall.contains("\".settings\""),
        )
        assertTrue(
            "卸载成员名单必须经 ModPackageInventory.memberNames 拼装",
            uninstall.contains("ModPackageInventory.memberNames(packageId)"),
        )
        assertTrue(
            "卸载成员名单必须经 ModSettingsStore.memberName 拼装",
            uninstall.contains("ModSettingsStore.memberName(packageId)"),
        )
    }

    // ------------------------------------------------------------------
    // 判据 2：显式卸载删净（重导保留不归这里，见 ModImportControllerTest）
    // ------------------------------------------------------------------

    @Test
    fun `卸载删净本包设置且不碰别的包设置`() {
        val root = tempRoot("rt2-settings-uninstall")
        val rt2 = rt2Of(root)
        try {
            writeArtifacts(rt2, "kojo-aaaa1111")
            writeSettings(rt2, "kojo-aaaa1111", "9")
            writeArtifacts(rt2, "kojo-bbbb2222")
            writeSettings(rt2, "kojo-bbbb2222", "3")

            assertTrue(ModManageUninstall.uninstall(rt2, "kojo-aaaa1111"))

            assertFalse(
                "卸载必须删净设置文件",
                ModSettingsStore.settingsFile(rt2, "kojo-aaaa1111").exists(),
            )
            assertEquals(
                "别的包的设置原样保留",
                mapOf("cfg.volume" to "3"),
                ModSettingsStore.read(rt2, "kojo-bbbb2222"),
            )
            assertFalse(
                "本包任何成员都不许残留",
                (ModPackageInventory.memberNames("kojo-aaaa1111") +
                    ModSettingsStore.memberName("kojo-aaaa1111"))
                    .any { File(rt2, it).exists() },
            )
        } finally {
            root.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // 判据 3：孤儿 GC 连带删（判据仍只看既有 orphanishReason）
    // ------------------------------------------------------------------

    @Test
    fun `GC 半成品包连带删设置并留痕`() {
        SaveDiagnostics.clear()
        val root = tempRoot("rt2-settings-gc-half")
        val rt2 = rt2Of(root)
        try {
            // 只有 manifest：缺容器 = 半成品（既有判据 2）。
            File(rt2, "kojo-cccc3333" + ModPackageInventory.MANIFEST_EXTENSION).writeText("m")
            writeSettings(rt2, "kojo-cccc3333")

            ModPackageInventory.gcOrphans(rt2, modsOf(root))

            assertFalse(
                "半成品包被清时设置必须一起走",
                ModSettingsStore.settingsFile(rt2, "kojo-cccc3333").exists(),
            )
            assertTrue(
                "设置删除也要留 MOD_PKG_GC 痕",
                SaveDiagnostics.snapshot().any {
                    it.contains("MOD_PKG_GC") && it.contains("kojo-cccc3333.settings")
                },
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `GC 源失联包连设置一起保留并留取证行`() {
        SaveDiagnostics.clear()
        val root = tempRoot("rt2-settings-gc-lost")
        val rt2 = rt2Of(root)
        try {
            writeArtifacts(rt2, "kojo-dddd4444")
            File(rt2, "kojo-dddd4444" + ModPackageInventory.SOURCES_EXTENSION)
                .writeText("gone.ERB\n", Charsets.UTF_8)
            writeSettings(rt2, "kojo-dddd4444")

            ModPackageInventory.gcOrphans(rt2, modsOf(root))

            assertTrue(
                "源失联不再是删除依据：设置必须保留",
                ModSettingsStore.settingsFile(rt2, "kojo-dddd4444").isFile,
            )
            assertTrue(
                "整包成员一个不少（含设置）",
                (ModPackageInventory.memberNames("kojo-dddd4444") +
                    ModSettingsStore.memberName("kojo-dddd4444"))
                    .all { File(rt2, it).isFile },
            )
            assertTrue(
                "只留一条「完整包保留」取证行",
                SaveDiagnostics.snapshot().any {
                    it.contains("MOD_PKG_GC") && it.contains("完整包保留")
                },
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `GC 只有设置或只有资源目录时保守保留`() {
        val root = tempRoot("rt2-settings-gc-lonely")
        val rt2 = rt2Of(root)
        try {
            writeSettings(rt2, "kojo-eeee5555")
            val assets = File(rt2, "kojo-ffff6666" + ModPackageInventory.ASSET_DIR_SUFFIX)
                .apply { mkdirs() }
            File(assets, "a.txt").writeText("asset")

            ModPackageInventory.gcOrphans(rt2, modsOf(root))

            assertTrue(
                "只有设置、没有包文件时保守保留（作者先摆设置再导入是合法顺序）",
                ModSettingsStore.settingsFile(rt2, "kojo-eeee5555").isFile,
            )
            assertTrue("只有资源目录时同样保留", File(assets, "a.txt").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `GC 健康包保留设置`() {
        val root = tempRoot("rt2-settings-gc-healthy")
        val rt2 = rt2Of(root)
        val mods = modsOf(root)
        try {
            writeArtifacts(rt2, "kojo-77777777")
            File(rt2, "kojo-77777777" + ModPackageInventory.SOURCES_EXTENSION)
                .writeText("still.ERB\n", Charsets.UTF_8)
            File(mods, "still.ERB").writeText("src")
            writeSettings(rt2, "kojo-77777777", "6")

            ModPackageInventory.gcOrphans(rt2, mods)

            assertEquals(
                "健康包的设置必须保留",
                mapOf("cfg.volume" to "6"),
                ModSettingsStore.read(rt2, "kojo-77777777"),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // 判据 4：设置原子写过程残留
    // ------------------------------------------------------------------

    @Test
    fun `GC 清理无活配置的设置写残留且保留活配置同名残留`() {
        SaveDiagnostics.clear()
        val root = tempRoot("rt2-settings-gc-tmp")
        val rt2 = rt2Of(root)
        try {
            // ① 无正式设置文件 ⇒ 残留是崩溃遗留，删掉并留痕。
            //    （拨到时间窗外：窗内残留按「可能是在途写」保守保留，见 RESIDUE_GC_MIN_AGE_MILLIS。）
            val dead = ModSettingsStore.tempMemberName("kojo-88888888")
            File(rt2, dead).writeText("half-written")
            File(rt2, dead).setLastModified(
                System.currentTimeMillis() - ModPackageInventory.RESIDUE_GC_MIN_AGE_MILLIS - 60_000L,
            )
            // ② 有正式设置文件 ⇒ 可能正处替换写窗口，保守保留。
            writeSettings(rt2, "kojo-99999999")
            val live = ModSettingsStore.tempMemberName("kojo-99999999")
            File(rt2, live).writeText("half-written")
            // ③ 安全阀：非 kojo- 前缀 / 坏形状 / 同名目录一律不碰。
            File(rt2, ModSettingsStore.tempMemberName("eratw.foo")).writeText("x")
            File(rt2, "kojo-aaa.settings.tmp.extra").writeText("x")
            val oddDir = File(rt2, ModSettingsStore.tempMemberName("kojo-bbbbbbbb")).apply { mkdirs() }

            ModPackageInventory.gcOrphans(rt2, modsOf(root))

            assertFalse("无活配置的写残留应删除", File(rt2, dead).exists())
            assertTrue("有活配置时残留保守保留", File(rt2, live).isFile)
            assertTrue("非 kojo- 前缀不碰", File(rt2, ModSettingsStore.tempMemberName("eratw.foo")).isFile)
            assertTrue("坏形状不碰", File(rt2, "kojo-aaa.settings.tmp.extra").isFile)
            assertTrue("同名目录不碰", oddDir.isDirectory)
            val notes = SaveDiagnostics.snapshot()
            assertTrue(
                "删除要留 MOD_PKG_GC 痕：$notes",
                notes.any { it.contains("MOD_PKG_GC") && it.contains("kojo-88888888.settings.tmp") },
            )
            assertFalse(
                "活配置同名残留不得被留痕成删除",
                notes.any { it.contains("kojo-99999999.settings.tmp") },
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `设置临时名解析不吞正式设置文件`() {
        assertNull(
            "正式设置文件不是残留",
            ModSettingsStore.tempResiduePackageId("kojo-1a2b3c4d" + ModSettingsStore.SETTINGS_EXTENSION),
        )
        assertNotNull(
            "tempMemberName 必须能被同源解析回来",
            ModSettingsStore.tempResiduePackageId(ModSettingsStore.tempMemberName("kojo-1a2b3c4d")),
        )
    }

    // ------------------------------------------------------------------
    // 源码读取（与 RailChipSizingGateTest 同款；Gradle 单测 cwd = app 模块目录）
    // ------------------------------------------------------------------

    private fun sourceOf(name: String): String {
        val dir = File(System.getProperty("user.dir"))
        val candidates = listOf(
            File(dir, "app/src/main/kotlin/com/eratw/port/android/$name"),
            File(dir, "src/main/kotlin/com/eratw/port/android/$name"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 $name（${candidates.joinToString { it.path }}）")
        return file.readText(Charsets.UTF_8)
    }
}
