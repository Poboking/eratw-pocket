package com.eratw.port.android

import com.eratw.port.core.compile.PatchCompiler
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.mod.ErbModuleContainer
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.platform.storage.ModStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * L5 / CONTRACT-RT2-M2LEGACY 存量 `.patch` 迁移的定向锁。
 *
 * 判据（契约 §3）：成功才删且三件套读回通过、失败不删且本次导入照常 `Imported`、无存量
 * 逐字节零变化、幂等同 `packageId` 原地替换、关态不触碰总闸、手放 `.patch` 不算存量。
 *
 * 纯 JVM：base 上下文注入（[BaseCompileContextSource] 是接口），`modsDir` 用临时目录，
 * 真 [PatchCompiler] / [ModStore] / [ErbModuleContainer] / [ManifestCodec] 走全链，不碰 Android 类。
 * 「删除相位先后」由成功用例（旧档删 + 三件套 decode 通过）与失败用例（旧档保留）对拍证明。
 */
class LegacyPatchMigrationTest {

    // ------------------------------------------------------------------
    // 夹具：mini 侧车 + 合成 base 计数（与 ModImportControllerTest 同源口径）
    // ------------------------------------------------------------------

    private fun miniMetadata(): BootMetadata {
        val names = listOf("A_ONE", "A_TWO", "A_SUM", "A_DEF")
        val argCounts = mapOf("A_TWO" to 1, "A_DEF" to 3)
        return BootMetadata(
            functions = names.mapIndexed { i, name ->
                FuncMeta(
                    index = i, key = name, name = name,
                    isMethod = name == "A_SUM",
                    argIntCount = argCounts[name] ?: 0, argStrCount = 0,
                    returnsStr = false, defIndex = i, position = "mini.ERB:${i + 1}",
                )
            },
            methodTypes = mapOf("A_SUM" to BootMetadata.MethodType.INT),
        )
    }

    private fun contextOf(): BaseCompileContext = BaseCompileContext(
        miniMetadata(),
        PatchCompiler.BaseCounts(
            instructionCount = 4000, functionCount = 4, statementCount = 100,
            stringCount = 10, longCount = 5, exprCount = 20,
        ),
        "a".repeat(64),
        csvDir = null,
    )

    private fun controllerIn(tmp: File): ModImportController = ModImportController(
        modStore = ModStore(File(tmp, "mods")),
        baseSource = { contextOf() },
        gameRoot = tmp,
    )

    private fun modsDir(tmp: File): File = File(tmp, "mods").apply { mkdirs() }

    /** 迁移包三件套真读回校验（与 `verifyWrittenPackage` 同款）：容器/manifest packageId + provenance。 */
    private fun assertMigrationPackage(rt2: File, packageId: String, expectedSources: List<String>) {
        val erbm = File(rt2, packageId + ModPackageInventory.MODULE_EXTENSION)
        val manifest = File(rt2, packageId + ModPackageInventory.MANIFEST_EXTENSION)
        val sources = File(rt2, packageId + ModPackageInventory.SOURCES_EXTENSION)
        assertTrue("迁移包 .erbm 在场", erbm.isFile)
        assertTrue("迁移包 .manifest 在场", manifest.isFile)
        assertTrue("迁移包 .sources 在场", sources.isFile)
        assertEquals("容器 packageId 读回一致", packageId, ErbModuleContainer.decode(erbm.readBytes()).packageId)
        assertEquals("manifest packageId 读回一致", packageId, ManifestCodec.decode(manifest.readBytes()).packageId)
        assertEquals(
            "provenance 逐行 = 留档源名（与 packageIdFor / sourcesBytes 同序）",
            expectedSources.sortedWith { a, b -> ModStore.compareNames(a, b) }.joinToString("\n") + "\n",
            sources.readText(Charsets.UTF_8),
        )
    }

    private fun erbmCount(rt2: File): Int =
        rt2.listFiles().orEmpty().count { it.name.endsWith(ModPackageInventory.MODULE_EXTENSION) }

    // ------------------------------------------------------------------
    // 判据 1：有存量 + 有留档源 ⇒ 迁移成功、旧档删、独立 packageId 三件套
    // ------------------------------------------------------------------

    @Test
    fun `有存量且有留档源——迁移成功后旧档删除且产出独立三件套`() {
        val tmp = tempDir()
        val mods = modsDir(tmp)
        File(mods, "K16_A.ERB").writeBytes(SOURCE_A.toByteArray(Charsets.UTF_8))
        File(mods, "K16_B.ERB").writeBytes(SOURCE_B.toByteArray(Charsets.UTF_8))
        val union = File(mods, ModImportController.UNION_PATCH_NAME).apply { writeBytes(byteArrayOf(0)) }
        val perImport = File(mods, "K16_A.patch").apply { writeBytes(byteArrayOf(0)) }

        val controller = controllerIn(tmp)
        val outcome = controller.import(listOf(PickedModFile("K16_C.ERB", SOURCE_C.toByteArray(Charsets.UTF_8))))
        val detail = (outcome as? ModImportOutcome.Imported)?.detail
            ?: throw AssertionError("本次导入应照常成功，实为 $outcome")

        assertFalse("并集补丁在迁移成功后删除", union.isFile)
        assertFalse("per-import 旧产物在迁移成功后删除", perImport.isFile)

        val allNames = listOf("K16_A.ERB", "K16_B.ERB", "K16_C.ERB")
        val migrationId = controller.packageIdFor(allNames)
        assertNotEquals("迁移包与本次导入包区分", "$migrationId.erbm", detail.patchFileName)
        val rt2 = File(mods, "rt2")
        assertMigrationPackage(rt2, migrationId, allNames)
        assertTrue("本次导入包仍在（异集合并存）", File(rt2, detail.patchFileName).isFile)
        assertTrue(
            "迁移成功不产生迁移告警：${detail.parseWarnings}",
            detail.parseWarnings.none { it.contains("存量 .patch 未迁移") },
        )
    }

    // ------------------------------------------------------------------
    // 判据 3：无存量 ⇒ 逐字节零变化（与关闭迁移相位对拍产物字节）
    // ------------------------------------------------------------------

    @Test
    fun `无存量——导入行为逐字节零变化且手放 patch 保留`() {
        // A 臂：缺省（迁移相位开着，但无存量 ⇒ NoLegacy）；B 臂：显式关掉迁移相位。
        // 两臂产物字节逐字节相等 = 迁移相位对无存量场景零副作用。
        fun run(migrationEnabled: Boolean): Pair<File, String> {
            val tmp = tempDir()
            val mods = modsDir(tmp)
            val hand = File(mods, "handmade.patch").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val outcome = controllerIn(tmp).import(
                listOf(PickedModFile("K16_A.ERB", SOURCE_A.toByteArray(Charsets.UTF_8))),
                legacyMigrationEnabled = migrationEnabled,
            )
            val detail = (outcome as? ModImportOutcome.Imported)?.detail
                ?: throw AssertionError("导入应成功，实为 $outcome")
            assertTrue("玩家手放 .patch 不算存量、保留给装载侧判定", hand.isFile)
            assertTrue("无迁移告警：${detail.parseWarnings}", detail.parseWarnings.none { it.contains("存量 .patch 未迁移") })
            val rt2 = File(mods, "rt2")
            assertEquals("rt2 里只有本次导入一个包", 1, erbmCount(rt2))
            return rt2 to detail.patchFileName.removeSuffix(ModPackageInventory.MODULE_EXTENSION)
        }
        val (rt2A, idA) = run(true)
        val (rt2B, idB) = run(false)
        assertEquals("两臂 packageId 一致", idA, idB)
        for (ext in listOf(
            ModPackageInventory.SOURCES_EXTENSION,
            ModPackageInventory.MANIFEST_EXTENSION,
            ModPackageInventory.MODULE_EXTENSION,
        )) {
            assertArrayEquals(
                "无存量时逐字节零变化：$ext",
                File(rt2A, idA + ext).readBytes(),
                File(rt2B, idB + ext).readBytes(),
            )
        }
    }

    // ------------------------------------------------------------------
    // 判据 3：有存量但无留档源 ⇒ 不迁移、保留旧档、不猜源（纯迁移器单测）
    // ------------------------------------------------------------------

    @Test
    fun `无留档源——不迁移保留旧档且 writePackage 不被调用`() {
        val dir = tempDir()
        val union = File(dir, ModImportController.UNION_PATCH_NAME).apply { writeBytes(byteArrayOf(0)) }
        val perImport = File(dir, "orphan.patch").apply { writeBytes(byteArrayOf(0)) }
        var called = false
        // importDiskNames 命中 orphan.patch（本次导入过同名源），但盘上没有 .ERB/.ERH 文件。
        val result = LegacyPatchMigrator.migrate(
            modsDir = dir,
            importDiskNames = setOf("orphan.ERB"),
            copy = ModImportCopy.ZH,
            writePackage = { called = true; MigrationWriteOutcome.Ok("should-not-happen") },
        )
        val kept = result as? MigrationResult.Kept
            ?: throw AssertionError("无留档源应 Kept，实为 $result")
        assertFalse("绝不把无源场景塞进新管线（writePackage 不得调用）", called)
        assertTrue("并集旧档保留", union.isFile)
        assertTrue("per-import 旧档保留", perImport.isFile)
        assertEquals("旧档清单回填给控制器（保留不删）", 2, kept.stalePatches.size)
        assertTrue(
            "告警点名原因：${kept.warnings}",
            kept.warnings.any { it.contains("找不到留档") },
        )
    }

    // ------------------------------------------------------------------
    // 判据 2：编译失败 ⇒ 不删旧档、本次导入照常 Imported、逐条告警点名原因
    // ------------------------------------------------------------------

    @Test
    fun `留档源编译失败——不迁移保留旧档且本次导入照常成功并点名原因`() {
        val tmp = tempDir()
        val mods = modsDir(tmp)
        // 老用户盘：一个坏留档源（函数头之前的全局 #DIM ⇒ 常量闸 I6 编译硬拒，改判后仍真失败）+ 并集旧档。
        File(mods, "K16_BAD.ERB").writeBytes(BAD_SOURCE.toByteArray(Charsets.UTF_8))
        val union = File(mods, ModImportController.UNION_PATCH_NAME).apply { writeBytes(byteArrayOf(0)) }

        val outcome = controllerIn(tmp)
            .import(listOf(PickedModFile("K16_GOOD.ERB", SOURCE_A.toByteArray(Charsets.UTF_8))))
        val detail = (outcome as? ModImportOutcome.Imported)?.detail
            ?: throw AssertionError("迁移失败不得回退本次导入成功，实为 $outcome")

        assertTrue("编译失败 ⇒ 旧档保留", union.isFile)
        assertTrue("本次导入包仍在", File(File(mods, "rt2"), detail.patchFileName).isFile)
        assertTrue(
            "告警点名迁移失败：${detail.parseWarnings}",
            detail.parseWarnings.any { it.contains("存量 .patch 未迁移") },
        )
        assertTrue(
            "告警带出编译失败判据与定位（全局 #DIM ⇒ GLOBAL_COUNT + 文件:行号）：${detail.parseWarnings}",
            detail.parseWarnings.any { it.contains("GLOBAL_COUNT") && it.contains("K16_BAD.ERB") },
        )
    }

    // ------------------------------------------------------------------
    // 判据 2/3：写盘或读回失败 ⇒ 不删旧档、点名原因（纯迁移器单测）
    // ------------------------------------------------------------------

    @Test
    fun `迁移链写盘失败——不迁移保留旧档且告警点名原因`() {
        val dir = tempDir()
        File(dir, "K16_A.ERB").writeBytes(SOURCE_A.toByteArray(Charsets.UTF_8))
        val union = File(dir, ModImportController.UNION_PATCH_NAME).apply { writeBytes(byteArrayOf(0)) }
        val result = LegacyPatchMigrator.migrate(
            modsDir = dir,
            importDiskNames = setOf("K16_A.ERB"),
            copy = ModImportCopy.ZH,
            writePackage = { MigrationWriteOutcome.Err("补丁产物落盘失败：模拟 IO 拒绝") },
        )
        val kept = result as? MigrationResult.Kept
            ?: throw AssertionError("写盘失败应 Kept，实为 $result")
        assertTrue("写盘失败 ⇒ 旧档保留", union.isFile)
        assertEquals("旧档清单回填（保留不删）", 1, kept.stalePatches.size)
        assertTrue(
            "告警点名原因：${kept.warnings}",
            kept.warnings.any { it.contains("补丁产物落盘失败") },
        )
    }

    // ------------------------------------------------------------------
    // 判据 4：幂等 —— 同集合重复导入同 packageId 原地替换、旧档删后不再触发迁移
    // ------------------------------------------------------------------

    @Test
    fun `幂等——同集合重复导入迁移包原地替换且旧档删后不再触发迁移`() {
        val tmp = tempDir()
        val mods = modsDir(tmp)
        File(mods, "K16_A.ERB").writeBytes(SOURCE_A.toByteArray(Charsets.UTF_8))
        File(mods, ModImportController.UNION_PATCH_NAME).writeBytes(byteArrayOf(0))
        val controller = controllerIn(tmp)
        val source = listOf(PickedModFile("K16_B.ERB", SOURCE_B.toByteArray(Charsets.UTF_8)))

        val first = (controller.import(source) as? ModImportOutcome.Imported)?.detail
            ?: throw AssertionError("第一次导入应成功")
        assertFalse("第一次迁移后并集补丁删除", File(mods, ModImportController.UNION_PATCH_NAME).exists())
        val migrationId = controller.packageIdFor(listOf("K16_A.ERB", "K16_B.ERB"))
        val rt2 = File(mods, "rt2")
        assertMigrationPackage(rt2, migrationId, listOf("K16_A.ERB", "K16_B.ERB"))
        assertEquals("首轮两包并存（本次包 + 迁移包）", 2, erbmCount(rt2))

        val second = (controller.import(source) as? ModImportOutcome.Imported)?.detail
            ?: throw AssertionError("第二次导入应成功")
        assertEquals("同集合重导 = 同 packageId 原地替换", first.patchFileName, second.patchFileName)
        assertEquals("旧档删后不再触发迁移、不产生第二个包", 2, erbmCount(rt2))
        assertTrue("迁移包未被误删", File(rt2, "$migrationId.erbm").isFile)
        assertTrue(
            "第二轮无迁移告警：${second.parseWarnings}",
            second.parseWarnings.none { it.contains("存量 .patch 未迁移") },
        )
    }

    // ------------------------------------------------------------------
    // 判据 5：关态零消费 —— 总闸缺省已按主人裁定翻 true，但显式关态路径仍不消费产物；
    // 迁移不依赖开关，关态导入照样产包。
    // ------------------------------------------------------------------

    @Test
    fun `关态零消费——显式关态仍不装载且迁移不依赖开关仍产包`() {
        assertTrue("Mod 运行总闸缺省已按裁定翻 true", UiPrefs.DEFAULT_MOD_RUNTIME)
        val tmp = tempDir()
        val mods = modsDir(tmp)
        File(mods, "K16_A.ERB").writeBytes(SOURCE_A.toByteArray(Charsets.UTF_8))
        File(mods, ModImportController.UNION_PATCH_NAME).writeBytes(byteArrayOf(0))
        val outcome = controllerIn(tmp).import(
            listOf(PickedModFile("K16_B.ERB", SOURCE_B.toByteArray(Charsets.UTF_8))),
            modRuntimeEnabled = false,
        )
        val detail = (outcome as? ModImportOutcome.Imported)?.detail
            ?: throw AssertionError("关态导入应成功，实为 $outcome")
        assertNotNull("关态成功页状态说明在场", detail.modRuntimeOffHint)
        assertFalse("迁移仍发生（不受总闸影响）", File(mods, ModImportController.UNION_PATCH_NAME).exists())
        val migrationId = controllerIn(tmp).packageIdFor(listOf("K16_A.ERB", "K16_B.ERB"))
        assertTrue(
            "迁移包仍落盘（关态不被消费，但产物在场）",
            File(File(mods, "rt2"), "$migrationId.erbm").isFile,
        )
    }

    private companion object {
        private const val SOURCE_A = "@B_A\nCALL A_ONE(1)\nRETURN 0\n"
        private const val SOURCE_B = "@B_B\nRETURN 0\n"
        private const val SOURCE_C = "@B_C\nRETURN 0\n"

        /**
         * 函数头之前的全局 #DIM ⇒ 常量闸硬拒（core-vm/src/main/kotlin/com/eratw/port/core/compile/
         * PatchCompiler.kt:1033-1039 抛 PatchCompileException；不变量 I6 / R7 铁律 3）。
         * d6da78151 只把「撞 base 同名函数」改成覆盖装载 + 告警，这一面在改判前后都真编译失败
         * （同闸先例 = ModImportControllerTest「I6 二——全局 DIM 补丁被编译期拒绝且文案含文件与行号」）。
         */
        private const val BAD_SOURCE = "#DIM GLOBAL_COUNT = 5\n@B_BAD\nRETURN 0\n"
    }
}

/** 临时目录（JVM 测试专用；每个用例独立，避免 mods 内容互相污染）。 */
private fun tempDir(): File = Files.createTempDirectory("eratw-legacymig").toFile()
