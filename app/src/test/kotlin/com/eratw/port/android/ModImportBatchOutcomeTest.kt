package com.eratw.port.android

import com.eratw.port.core.compile.PatchCompiler
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.platform.storage.ModStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * task-178 批导入出口判据：**逐包顺序导入 + 坏包隔离 + 逐项结果**。
 *
 * 批量层（[ModImportController.importBatch]）只做「分组 + 循环 + 收结果」，每项直接调既有
 * 单包 [ModImportController.import]——所以这里的断言分两层：
 *
 * 1. **事务与隔离**（真链，mini 侧车 + 合成 base 计数注入）：两个好包都落产物；好包 /
 *    坏包 / 好包混排时坏包只失败自己、后续照装；坏包在前不动后续；全坏每项各自失败；
 *    同批同名资源目录时后者点名**先到包**与**本次包**且先到包文件不动（不静默覆盖）。
 * 2. **结果模型与 UI 行序**：[ModImportBatchOutcome] 的计数、[modImportBatchResultLines]
 *    的行序（汇总行在首 / 逐项按选中序 / 批级失败垫尾）、[ModImportUiReducer] 进
 *    [ModImportUiState.BatchFinished]、入口卡摘要走批量文案。
 *
 * 失败隔离另有两处**注入**判据（不碰生产分支）：单项异常要记在那项头上并继续；循环本体
 * 迭代异常要保留已完成项并在 [ModImportBatchOutcome.batchFailure] 上留原因。
 *
 * 全部纯 JVM：base 上下文注入，不碰 Android 类。
 */
class ModImportBatchOutcomeTest {

    // ------------------------------------------------------------------
    // 夹具：mini 侧车 + 合成 base 计数 + 临时 mods 目录（照 ModImportControllerTest 复制）
    // ------------------------------------------------------------------

    private fun miniMetadata(): BootMetadata {
        val names = listOf("A_ONE", "A_TWO", "A_SUM", "A_DEF")
        val argCounts = mapOf("A_TWO" to 1, "A_DEF" to 3)
        return BootMetadata(
            functions = names.mapIndexed { i, name ->
                FuncMeta(
                    index = i,
                    key = name,
                    name = name,
                    isMethod = name == "A_SUM",
                    argIntCount = argCounts[name] ?: 0,
                    argStrCount = 0,
                    returnsStr = false,
                    defIndex = i,
                    position = "mini.ERB:${i + 1}",
                )
            },
            methodTypes = mapOf("A_SUM" to BootMetadata.MethodType.INT),
        )
    }

    private fun miniBaseCounts(): PatchCompiler.BaseCounts = PatchCompiler.BaseCounts(
        instructionCount = 4000,
        functionCount = 4,
        statementCount = 100,
        stringCount = 10,
        longCount = 5,
        exprCount = 20,
    )

    private fun contextOf(): BaseCompileContext =
        BaseCompileContext(miniMetadata(), miniBaseCounts(), BASE_SHA, csvDir = null)

    private fun controllerIn(tmp: File): ModImportController = ModImportController(
        modStore = ModStore(File(tmp, "mods")),
        baseSource = { contextOf() },
        gameRoot = tmp,
    )

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** 一个合法口上包 zip：单个 ERB 源（自定义入口函数）→ 独立 packageId。 */
    private fun goodZip(entryFunction: String, sourceName: String): ByteArray =
        zipOf(sourceName to "@$entryFunction\nCALL A_ONE(3)\nRETURN 0\n".toByteArray(Charsets.UTF_8))

    /** 不是 zip 的字节：相位⓪解包即失败（最省的可控坏包）。 */
    private fun badZip(): ByteArray = "这不是 zip".toByteArray(Charsets.UTF_8)

    private fun imported(outcome: ModImportOutcome): ImportedPatchDetail =
        (outcome as? ModImportOutcome.Imported)?.detail
            ?: throw AssertionError("该项应成功，实为 $outcome")

    private fun failedOf(outcome: ModImportOutcome): ModImportOutcome.Failed =
        outcome as? ModImportOutcome.Failed
            ?: throw AssertionError("该项应失败，实为 $outcome")

    /** 一个包落盘的五件（代码产物面；`.codeid` 只属代码包，本批是普通口上包不出）。 */
    private fun artifactNames(packageId: String): List<String> = listOf(
        packageId + ModPackageInventory.SOURCES_EXTENSION,
        packageId + ModPackageInventory.FINGERPRINT_EXTENSION,
        packageId + ModPackageInventory.MANIFEST_EXTENSION,
        packageId + ModPackageInventory.MODULE_EXTENSION,
    )

    private fun assertPackageArtifactsOnDisk(tmp: File, packageId: String) {
        val rt2 = File(File(tmp, "mods"), "rt2")
        for (name in artifactNames(packageId)) {
            assertTrue("$packageId 的产物 $name 应落盘", File(rt2, name).isFile)
        }
    }

    private fun assertPackageArtifactsAbsent(tmp: File, packageId: String) {
        val rt2 = File(File(tmp, "mods"), "rt2")
        for (name in artifactNames(packageId)) {
            assertFalse("$packageId 不该留产物 $name", File(rt2, name).exists())
        }
    }

    // ------------------------------------------------------------------
    // 判据 1：两个好包都成功（各自身份、产物、源都在盘上）
    // ------------------------------------------------------------------

    @Test
    fun `两个好包逐个成功——两包产物与源都在盘上`() {
        val tmp = tempDir()
        val controller = controllerIn(tmp)
        val outcome = controller.importBatch(
            listOf(
                PickedModFile("kojo_a.ZIP", goodZip("B_HELLO_A", "KOJO_A.ERB")),
                PickedModFile("kojo_b.ZIP", goodZip("B_HELLO_B", "KOJO_B.ERB")),
            ),
        )

        assertEquals("两项都成功：$outcome", 2, outcome.succeededCount)
        assertEquals(0, outcome.failedCount)
        assertNull(outcome.batchFailure)
        assertEquals("逐项名 = zip 显示名且保序", listOf("kojo_a.ZIP", "kojo_b.ZIP"), outcome.items.map { it.zipName })

        val aId = controller.packageIdFor(listOf("KOJO_A.ERB"))
        val bId = controller.packageIdFor(listOf("KOJO_B.ERB"))
        assertPackageArtifactsOnDisk(tmp, aId)
        assertPackageArtifactsOnDisk(tmp, bId)
        assertTrue("好包的源留档", File(File(tmp, "mods"), "KOJO_A.ERB").isFile)
        assertTrue("好包的源留档", File(File(tmp, "mods"), "KOJO_B.ERB").isFile)
        assertEquals(
            "入口函数逐包独立",
            listOf("B_HELLO_A", "B_HELLO_B"),
            outcome.items.map { imported(it.outcome).entryFunction },
        )
    }

    // ------------------------------------------------------------------
    // 判据 2：失败隔离（好 / 坏 / 好，坏包只坏自己）
    // ------------------------------------------------------------------

    @Test
    fun `好包坏包好包——坏包只失败自己且后续照装`() {
        val tmp = tempDir()
        val controller = controllerIn(tmp)
        val outcome = controller.importBatch(
            listOf(
                PickedModFile("kojo_good_1.ZIP", goodZip("B_HELLO_G1", "KOJO_G1.ERB")),
                PickedModFile("kojo_bad.ZIP", badZip()),
                PickedModFile("kojo_good_2.ZIP", goodZip("B_HELLO_G2", "KOJO_G2.ERB")),
            ),
        )

        assertEquals("2 成功 / 1 失败", 2, outcome.succeededCount)
        assertEquals(1, outcome.failedCount)
        assertNull("坏包属逐项失败，不是批级失败", outcome.batchFailure)
        assertEquals(
            "行序 = 选中序",
            listOf("kojo_good_1.ZIP", "kojo_bad.ZIP", "kojo_good_2.ZIP"),
            outcome.items.map { it.zipName },
        )
        assertEquals(ModImportFailureKind.STORAGE, failedOf(outcome.items[1].outcome).kind)
        assertPackageArtifactsOnDisk(tmp, controller.packageIdFor(listOf("KOJO_G1.ERB")))
        assertPackageArtifactsOnDisk(tmp, controller.packageIdFor(listOf("KOJO_G2.ERB")))
    }

    @Test
    fun `坏包在前——后续好包照装`() {
        val tmp = tempDir()
        val controller = controllerIn(tmp)
        val outcome = controller.importBatch(
            listOf(
                PickedModFile("kojo_bad.ZIP", badZip()),
                PickedModFile("kojo_good.ZIP", goodZip("B_HELLO_LATE", "KOJO_LATE.ERB")),
            ),
        )

        assertEquals(1, outcome.succeededCount)
        assertEquals(1, outcome.failedCount)
        assertEquals("后一项仍成功", ModImportOutcome.Imported::class, outcome.items[1].outcome::class)
        assertPackageArtifactsOnDisk(tmp, controller.packageIdFor(listOf("KOJO_LATE.ERB")))
    }

    @Test
    fun `全坏——每项各自失败且成功数为零`() {
        val tmp = tempDir()
        val controller = controllerIn(tmp)
        val outcome = controller.importBatch(
            listOf(
                PickedModFile("a.ZIP", badZip()),
                PickedModFile("b.ZIP", badZip()),
                PickedModFile("c.ZIP", badZip()),
            ),
        )

        assertEquals(0, outcome.succeededCount)
        assertEquals(3, outcome.failedCount)
        assertNull(outcome.batchFailure)
        assertTrue("每一项都有自己的失败结果", outcome.items.all { it.outcome is ModImportOutcome.Failed })
        assertTrue(
            "每项失败都有玩家可读消息",
            outcome.items.all { failedOf(it.outcome).message.isNotBlank() },
        )
    }

    @Test
    fun `坏包不留产物——同名包重导仍按单包路径成功`() {
        val tmp = tempDir()
        val controller = controllerIn(tmp)
        val badFirst = controller.import(listOf(PickedModFile("re.ZIP", badZip())))
        assertTrue("先来一发坏包", badFirst is ModImportOutcome.Failed)

        val outcome = controller.importBatch(listOf(PickedModFile("re.ZIP", goodZip("B_HELLO_RE", "KOJO_RE.ERB"))))
        assertEquals(1, outcome.succeededCount)
        val packageId = controller.packageIdFor(listOf("KOJO_RE.ERB"))
        assertPackageArtifactsOnDisk(tmp, packageId)
    }

    // ------------------------------------------------------------------
    // 判据 3：单包 API 语义不变（批的单 zip 与裸 import 结果同形）
    // ------------------------------------------------------------------

    @Test
    fun `单包 import 与 importBatch 单 zip 结果同形`() {
        val tmpA = tempDir()
        val singleFile = listOf(PickedModFile("one.ZIP", goodZip("B_HELLO_ONE", "KOJO_ONE.ERB")))
        val single = imported(controllerIn(tmpA).import(singleFile))
        val tmpB = tempDir()
        val batch = controllerIn(tmpB).importBatch(
            listOf(PickedModFile("one.ZIP", goodZip("B_HELLO_ONE", "KOJO_ONE.ERB"))),
        )

        assertEquals(1, batch.items.size)
        val detail = imported(batch.items.single().outcome)
        assertEquals("产物名同形", single.patchFileName, detail.patchFileName)
        assertEquals(single.functionCount, detail.functionCount)
        assertEquals(single.entryFunction, detail.entryFunction)
        assertEquals(single.sourceFileNames, detail.sourceFileNames)
    }

    // ------------------------------------------------------------------
    // 判据 4：同批同名资源目录——后者失败点名先到包（不静默覆盖）
    // ------------------------------------------------------------------

    @Test
    fun `同批同名资源目录——后者失败点名先到包且先到包文件仍在`() {
        val tmp = tempDir()
        val controller = controllerIn(tmp)
        val firstId = controller.packageIdFor(listOf("KOJO_MEDIA_1.ERB"))
        val secondId = controller.packageIdFor(listOf("KOJO_MEDIA_2.ERB"))
        val firstZip = zipOf(
            "KOJO_MEDIA_1.ERB" to "@B_HELLO_M1\nCALL A_ONE(3)\nRETURN 0\n".toByteArray(Charsets.UTF_8),
            "eratw-pack.json" to PACK_META_JSON.toByteArray(Charsets.UTF_8),
            "resources/mod/demo/a.png" to PNG,
        )
        val secondZip = zipOf(
            "KOJO_MEDIA_2.ERB" to "@B_HELLO_M2\nCALL A_ONE(4)\nRETURN 0\n".toByteArray(Charsets.UTF_8),
            "eratw-pack.json" to PACK_META_JSON.toByteArray(Charsets.UTF_8),
            "resources/mod/demo/b.png" to PNG,
        )

        val outcome = controller.importBatch(
            listOf(PickedModFile("media_1.ZIP", firstZip), PickedModFile("media_2.ZIP", secondZip)),
        )

        assertEquals("先到包成功", 1, outcome.succeededCount)
        assertEquals("后到包被拒", 1, outcome.failedCount)
        val failed = failedOf(outcome.items[1].outcome)
        assertEquals(ModImportFailureKind.STORAGE, failed.kind)
        assertTrue("点名冲突目录：${failed.message}", failed.message.contains("demo"))
        assertTrue("点名先到包：${failed.message}", failed.message.contains(firstId))
        assertTrue("点名本次包：${failed.message}", failed.message.contains(secondId))
        assertTrue("先到包的文件不得被动", File(tmp, "resources/mod/demo/a.png").isFile)
        assertFalse("被拒包不得落自己的文件", File(tmp, "resources/mod/demo/b.png").exists())
        assertPackageArtifactsOnDisk(tmp, firstId)
        assertPackageArtifactsAbsent(tmp, secondId)
    }

    // ------------------------------------------------------------------
    // 判据 5：分组口径（zip 各成一项、散装源合成一项、项序 = 选中序）
    // ------------------------------------------------------------------

    @Test
    fun `分组——zip各成一项散装源合成一项且项序为选中序`() {
        val groups = ModImportBatchRunner.groupsOf(
            listOf(
                PickedModFile("x.ZIP", goodZip("B_X", "X.ERB")),
                PickedModFile("LOOSE_A.ERB", "srcA".toByteArray()),
                PickedModFile("LOOSE_B.ERB", "srcB".toByteArray()),
                PickedModFile("y.zip", goodZip("B_Y", "Y.ERB")),
            ),
        )
        assertEquals("zip+散装组+zip = 3 组", 3, groups.size)
        assertEquals(listOf("x.ZIP"), groups[0].map { it.displayName })
        assertEquals("散装源合成一项", listOf("LOOSE_A.ERB", "LOOSE_B.ERB"), groups[1].map { it.displayName })
        assertEquals(listOf("y.zip"), groups[2].map { it.displayName })
    }

    @Test
    fun `散装多文件合成一项导入——项名为连好的源名清单`() {
        val tmp = tempDir()
        val controller = controllerIn(tmp)
        val outcome = controller.importBatch(
            listOf(
                PickedModFile("LOOSE_A.ERB", "@B_LOOSE_A\nCALL A_ONE(1)\nRETURN 0\n".toByteArray(Charsets.UTF_8)),
                PickedModFile("LOOSE_B.ERB", "@B_LOOSE_B\nCALL A_ONE(2)\nRETURN 0\n".toByteArray(Charsets.UTF_8)),
            ),
        )

        assertEquals("散装多文件 = 一个口上包 = 一项", 1, outcome.items.size)
        assertEquals(
            "项名按 nameListSeparator 连源名",
            "LOOSE_A.ERB、LOOSE_B.ERB",
            outcome.items.single().zipName,
        )
        assertEquals(1, outcome.succeededCount)
        assertEquals(2, imported(outcome.items.single().outcome).functionCount)
    }

    @Test
    fun `空选择——批级失败且无逐项`() {
        val tmp = tempDir()
        val outcome = controllerIn(tmp).importBatch(emptyList())

        assertTrue(outcome.items.isEmpty())
        val batchFailure = outcome.batchFailure ?: throw AssertionError("空选择应有批级失败")
        assertEquals(ModImportFailureKind.STORAGE, batchFailure.kind)
        assertEquals(ModImportCopy.ZH.noFilesPicked, batchFailure.message)
        assertEquals(0, outcome.succeededCount)
        assertEquals(0, outcome.failedCount)
    }

    // ------------------------------------------------------------------
    // 判据 6：注入式失败隔离（单项异常 / 循环本体异常）
    // ------------------------------------------------------------------

    @Test
    fun `单项异常——该项记未预期且后续继续`() {
        val tmp = tempDir()
        val controller = controllerIn(tmp)
        val zips = listOf(
            PickedModFile("g1.ZIP", goodZip("B_HELLO_I1", "KOJO_I1.ERB")),
            PickedModFile("g2.ZIP", goodZip("B_HELLO_I2", "KOJO_I2.ERB")),
            PickedModFile("g3.ZIP", goodZip("B_HELLO_I3", "KOJO_I3.ERB")),
        )
        val groups = ModImportBatchRunner.groupsOf(zips)
        var calls = 0
        val outcome = ModImportBatchRunner.run(
            groups = groups,
            copy = ModImportCopy.ZH,
            importOne = { group ->
                calls += 1
                if (calls == 2) throw BatchTestBoom("boom-on-second")
                controller.import(group)
            },
            unexpected = { unexpectedOf(it) },
        )

        assertEquals(3, outcome.items.size)
        assertNull("单项异常不该升级成批级失败", outcome.batchFailure)
        assertEquals(ModImportOutcome.Imported::class, outcome.items[0].outcome::class)
        val failed = failedOf(outcome.items[1].outcome)
        assertEquals(ModImportFailureKind.UNEXPECTED, failed.kind)
        assertTrue("异常原话进消息：${failed.message}", failed.message.contains("boom-on-second"))
        assertEquals("第三项照跑", ModImportOutcome.Imported::class, outcome.items[2].outcome::class)
        assertPackageArtifactsOnDisk(tmp, controller.packageIdFor(listOf("KOJO_I3.ERB")))
    }

    @Test
    fun `循环本体异常——已完成项保留且批级失败非空`() {
        val tmp = tempDir()
        val controller = controllerIn(tmp)
        val groups = ModImportBatchRunner.groupsOf(
            listOf(
                PickedModFile("a1.ZIP", goodZip("B_HELLO_E1", "KOJO_E1.ERB")),
                PickedModFile("a2.ZIP", goodZip("B_HELLO_E2", "KOJO_E2.ERB")),
            ),
        )
        val exploding = object : AbstractList<List<PickedModFile>>() {
            override val size: Int get() = groups.size
            override fun get(index: Int): List<PickedModFile> {
                if (index == 1) error("iteration-boom")
                return groups[index]
            }
        }
        val outcome = ModImportBatchRunner.run(
            groups = exploding,
            copy = ModImportCopy.ZH,
            importOne = { controller.import(it) },
            unexpected = { unexpectedOf(it) },
        )

        assertEquals("已完成的第一项原样保留", 1, outcome.items.size)
        assertEquals(ModImportOutcome.Imported::class, outcome.items.single().outcome::class)
        val batchFailure = outcome.batchFailure ?: throw AssertionError("循环异常应记成批级失败")
        assertEquals(ModImportFailureKind.UNEXPECTED, batchFailure.kind)
        assertTrue("迭代异常原话进消息：${batchFailure.message}", batchFailure.message.contains("iteration-boom"))
        assertPackageArtifactsOnDisk(tmp, controller.packageIdFor(listOf("KOJO_E1.ERB")))
    }

    // ------------------------------------------------------------------
    // 判据 7：UI 状态 / 行序 / 卡片摘要
    // ------------------------------------------------------------------

    @Test
    fun `批导入完成事件——进入批结果态且卡片摘要报成功失败计数`() {
        val state = ModImportUiReducer.reduce(
            ModImportUiState.Idle(null),
            ModImportEvent.BatchImportFinished(
                ModImportBatchOutcome(
                    items = listOf(
                        ModImportBatchOutcome.Item("good.ZIP", importedOutcome()),
                        ModImportBatchOutcome.Item(
                            "bad.ZIP",
                            ModImportOutcome.Failed(ModImportFailureKind.STORAGE, "坏了"),
                        ),
                    ),
                ),
                report = null,
            ),
        )

        val batch = state as? ModImportUiState.BatchFinished
            ?: throw AssertionError("应进入批结果态，实为 $state")
        assertEquals(1, batch.outcome.succeededCount)
        assertEquals(1, batch.outcome.failedCount)
        assertEquals("入口卡摘要报本批计数", "上次批量导入：1 个成功 / 1 个失败", modImportCardSummary(state, ModImportCopy.ZH))
    }

    @Test
    fun `批结果行——汇总行在首逐项按选中序批级失败垫尾`() {
        val outcome = ModImportBatchOutcome(
            items = listOf(
                ModImportBatchOutcome.Item("second.ZIP", importedOutcome()),
                ModImportBatchOutcome.Item("first.ZIP", ModImportOutcome.Failed(ModImportFailureKind.COMPILE, "编译坏了")),
            ),
            batchFailure = ModImportOutcome.Failed(ModImportFailureKind.UNEXPECTED, "循环坏了"),
        )

        val lines = modImportBatchResultLines(outcome, ModImportCopy.ZH)

        assertEquals("汇总行 + 2 逐项 + 批级失败", 4, lines.size)
        assertEquals("汇总行在首", ModImportBatchResultLine.Summary(1, 1), lines[0])
        assertEquals("逐项行按 items 原序", ModImportBatchResultLine.Succeeded("second.ZIP"), lines[1])
        val failedLine = lines[2] as? ModImportBatchResultLine.Failed ?: throw AssertionError("第二行应是失败项：${lines[2]}")
        assertEquals("first.ZIP", failedLine.zipName)
        assertEquals(ModImportCopy.ZH.failureTitleCompile, failedLine.title)
        assertEquals("编译坏了", failedLine.message)
        val batchFailed = lines[3] as? ModImportBatchResultLine.BatchFailed
            ?: throw AssertionError("末行应是批级失败：${lines[3]}")
        assertEquals(ModImportCopy.ZH.failureTitleUnexpected, batchFailed.title)
        assertEquals("循环坏了", batchFailed.message)
    }

    @Test
    fun `批级失败时的卡片摘要走失败文案`() {
        val state = ModImportUiState.BatchFinished(
            outcome = ModImportBatchOutcome(
                items = emptyList(),
                batchFailure = ModImportOutcome.Failed(ModImportFailureKind.STORAGE, "没有选择任何文件"),
            ),
            report = null,
        )

        assertEquals(
            "批级失败按单包失败口径报卡片",
            ModImportCopy.ZH.cardFailedFormat.replace("{0}", ModImportCopy.ZH.failureTitleStorage),
            modImportCardSummary(state, ModImportCopy.ZH),
        )
    }

    // ------------------------------------------------------------------
    // 夹具尾巴
    // ------------------------------------------------------------------

    /** 一个真实成功的单包导入结果（UI 层用例要真 [ImportedPatchDetail]，不手搓字段）。 */
    private fun importedOutcome(): ModImportOutcome {
        val tmp = tempDir()
        return controllerIn(tmp).import(listOf(PickedModFile("ui.ZIP", goodZip("B_HELLO_UI", "KOJO_UI.ERB"))))
    }

    /**
     * 注入式用例的「未预期异常折法」替身：生产口径是 [ModImportController.importBatch] 传进的
     * 私有 unexpectedFailure（文案走 [ModImportCopy.unexpectedFormat]）；这里只要 kind 与
     * 「原话进消息」两个判据同形，避免为测试在控制器上开内部口。
     */
    private fun unexpectedOf(e: Exception): ModImportOutcome.Failed =
        ModImportOutcome.Failed(ModImportFailureKind.UNEXPECTED, "未预期：${e.message}")

    /** 注入式异常用自定义类型（不用 RuntimeException，detekt TooGenericExceptionThrown）。 */
    private class BatchTestBoom(message: String) : RuntimeException(message)

    private companion object {
        const val BASE_SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val PACK_META_JSON = "{\"schema\":1," +
            "\"packageId\":\"eratw.demo\",\"version\":\"1.0\",\"resources\":\"demo\"}"
        val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)

        fun tempDir(): File = Files.createTempDirectory("eratw-batch-import").toFile()
    }
}
