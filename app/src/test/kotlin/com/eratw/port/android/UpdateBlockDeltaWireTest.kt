package com.eratw.port.android

import com.eratw.port.core.update.Artifact
import com.eratw.port.core.update.AssetsChannel
import com.eratw.port.core.update.DataArtifactKind
import com.eratw.port.core.update.UpdateInfo
import com.eratw.port.core.update.UpdateManifest
import com.eratw.port.core.update.UpdatePolicy
import com.eratw.port.platform.storage.BlockDeltaAssetTree
import com.eratw.port.platform.storage.ZipAssetTree
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * U8b：块级差量（P2-13）**接进 app 与平台**的定向单测。
 *
 * U8 造好了零件（清单新键的判式、四参选件、`BlockDeltaAssetTree`、发布侧），但客户端一行没接 ——
 * 本文件钉的就是接线本身，分三段，各钉一类只会在这里才出现的错：
 *
 * 1. **选件**（[UpdateDownloadPlan.selectDataArtifact]）。最贵的一条是「块级新键缺席时必须与
 *    改前逐字同结论」：线上发出去的每一张清单都没有那三行，而这条通道不许因为接了新档
 *    就把老包的判定挪动一格。判式本体在 core（`BlockDeltaPolicyTest` 钉过四参形传 null 等价于
 *    三参形），这里钉的是**装配点**：app 只从 [UpdateInfo.assetsBlockDelta] 取件、不自己比基线，
 *    交出去的档位与件同源。
 * 2. **落地路由**（[UpdateDownloadPlan.stagedApplyPlan] + [UpdateManager.applyBundle]）。
 *    判据取自**包内实况**而不是清单声明：块级包里被补丁覆盖的条目在归档里没有整条字节，
 *    拿普通 zip 树去解它，解包器看不到那些文件、一个字节都不写、照样返回成功，版本标记照写
 *    —— 盘上停着的还是旧语料，而屏上没有任何异常。本文件第 4 个用例把这一形态钉成红：
 *    同一份包走块级树会抛、走普通树会「成功但什么都没装」。
 * 3. **既有闸与回落**：`rejectedEntryCount > 0 ⇒ 整包拒装` 这条闸对块级档同样生效
 *    （含补丁形状数，不只 zip-slip）；差量档（文件级与块级）抛错要记坏基线、全量档不记。
 *
 * 跨语言的字节级还原正确性不在本文件（那是 platform 的 `BlockDeltaAssetTreeTest`，它吃
 * `devtools/emit_update.py` 真跑出来的补丁）。本文件的补丁夹具按设备侧判式自己写，
 * 只用来证明**路由走对了**，不用于证明「两侧对同一个字节序理解相同」—— 那种互证抓不住错位。
 */
class UpdateBlockDeltaWireTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val block = Artifact("eratw-assets-blockdelta-9.zip", 20_480L, "b".repeat(64))
    private val fileDelta = Artifact("eratw-assets-8to9.zip", 102_400L, "c".repeat(64))
    private val full = Artifact("eratw-assets-9.zip", 712_345_678L, "d".repeat(64))

    private fun info(
        baseVersion: Long,
        delta: Artifact? = null,
        blockDelta: Artifact? = null,
        fullArtifact: Artifact? = full,
    ): UpdateInfo = UpdateInfo(
        format = 1,
        generated = "2026-11-05T00:00:00Z",
        note = "",
        apk = null,
        assets = AssetsChannel(9L, full),
        mirrors = emptyList(),
        warnings = emptyList(),
        assetsFull = fullArtifact,
        assetsDelta = delta,
        assetsBlockDelta = blockDelta,
        assetsBaseVersion = baseVersion,
    )

    // ------------------------------------------------------------------
    // 一、选件：块级新键缺席时与改前逐字同结论
    // ------------------------------------------------------------------

    @Test
    fun `块级键缺席时选件与三参判定逐字同结论且档位不含块级`() {
        val cases = listOf(
            "只有全量" to info(UpdateManifest.NO_ASSETS_BASE_VERSION),
            "全量 + 文件级差量（够格）" to info(8L, delta = fileDelta),
            "全量 + 文件级差量（基线不符）" to info(5L, delta = fileDelta),
            "只有旧键 assets 那一形" to info(8L, delta = fileDelta, fullArtifact = null),
            "什么都没发" to info(8L, fullArtifact = null),
        )
        for (trusted in listOf(true, false)) {
            for (installed in listOf(0L, 5L, 8L, 9L)) {
                for ((label, subject) in cases) {
                    val before = UpdatePolicy.dataArtifactFor(subject, installed, trusted)
                    val after = UpdateDownloadPlan.selectDataArtifact(subject, installed, trusted)
                    assertNull("$label：夹具不该带块级件", subject.assetsBlockDelta)
                    assertEquals(
                        "接线把 $label @ installed=$installed trusted=$trusted 的落点挪动了",
                        before?.fileName, after.artifact?.fileName,
                    )
                    assertEquals(
                        "档位必须与选中的件同源",
                        UpdatePolicy.dataArtifactKind(subject, null, installed, trusted),
                        after.kind,
                    )
                    if (after.artifact == null) {
                        assertEquals(DataArtifactKind.NONE, after.kind)
                    } else {
                        assertFalse(
                            "块级件不在清单里却判成 BLOCK_DELTA 就是谎报",
                            after.kind == DataArtifactKind.BLOCK_DELTA,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `块级件够格时选它并如实报 BLOCK_DELTA 档`() {
        val subject = info(8L, delta = fileDelta, blockDelta = block)
        val selected = UpdateDownloadPlan.selectDataArtifact(subject, installedVersion = 8L, diskTrusted = true)
        assertEquals(block.fileName, selected.artifact?.fileName)
        assertEquals(DataArtifactKind.BLOCK_DELTA, selected.kind)
    }

    @Test
    fun `块级件不够格时落回文件级差量与改前同结论`() {
        val subject = info(8L, delta = fileDelta, blockDelta = block)
        // 盘上已经不是基线那一版：块级与文件级一起不成立 ⇒ 全量（与三参判定同结论）。
        val mismatch = UpdateDownloadPlan.selectDataArtifact(subject, installedVersion = 9L, diskTrusted = true)
        assertEquals(full.fileName, mismatch.artifact?.fileName)
        assertEquals(DataArtifactKind.FULL, mismatch.kind)
        // 盘不可信（体检不过或这个基线失败过）：两档一起关死。
        val untrusted = UpdateDownloadPlan.selectDataArtifact(subject, installedVersion = 8L, diskTrusted = false)
        assertEquals(full.fileName, untrusted.artifact?.fileName)
        assertEquals(DataArtifactKind.FULL, untrusted.kind)
    }

    @Test
    fun `陈旧产物回收的保名集认识块级包`() {
        // 检查取到清单后**第一件事**就是顺手回收下载目录（选件之前）。块级那件不在保名集里，
        // 下一次冷启检查就会把刚下好、正等应用的包当残留删掉 —— 表现是「每次都重下一遍」，
        // 而 sha 与选件判定一个字都没错，是最难归因的那一类。
        val subject = info(8L, delta = fileDelta, blockDelta = block)
        val keep = UpdateManager.knownArtifacts(subject).map { it.fileName }
        assertTrue("保名集里要点名块级包：$keep", keep.contains(block.fileName))
        assertTrue("文件级差量照旧在保名集里：$keep", keep.contains(fileDelta.fileName))
        assertTrue("全量件照旧在保名集里：$keep", keep.contains(full.fileName))
        // 键缺席时保名集一字不变（三件：assets、assets-full、assets-delta）。。
        val legacy = UpdateManager.knownArtifacts(info(8L, delta = fileDelta))
        assertEquals(3, legacy.size)
        assertFalse("键缺席时保名集不该多出一条：$legacy", legacy.any { it.fileName.contains("blockdelta") })
    }

    // ------------------------------------------------------------------
    // 二、落地路由：三件包内读数 → 哪棵树 / 拒装 / 记不记坏基线
    // ------------------------------------------------------------------

    @Test
    fun `包里有 update-blocks 条目才走块级树`() {
        val blockPlan = UpdateDownloadPlan.stagedApplyPlan(0, hasBlockPatches = true, isDeltaPackage = true)
        assertTrue(blockPlan is UpdateDownloadPlan.StagedApply.Apply)
        val apply = blockPlan as UpdateDownloadPlan.StagedApply.Apply
        assertEquals(DataArtifactKind.BLOCK_DELTA, apply.kind)
        assertTrue(apply.usesBlockDeltaTree)

        // 只有整条文件、没有补丁条目 = 现行文件级差量：一个字都不许挪。
        val filePlan = UpdateDownloadPlan.stagedApplyPlan(0, hasBlockPatches = false, isDeltaPackage = true) as UpdateDownloadPlan.StagedApply.Apply
        assertEquals(DataArtifactKind.FILE_DELTA, filePlan.kind)
        assertFalse(filePlan.usesBlockDeltaTree)

        val fullPlan = UpdateDownloadPlan.stagedApplyPlan(0, hasBlockPatches = false, isDeltaPackage = false) as UpdateDownloadPlan.StagedApply.Apply
        assertEquals(DataArtifactKind.FULL, fullPlan.kind)
        assertFalse(fullPlan.usesBlockDeltaTree)
    }

    @Test
    fun `非法条目数大于零一律整包拒装`() {
        for (hasBlocks in listOf(true, false)) {
            val plan = UpdateDownloadPlan.stagedApplyPlan(3, hasBlockPatches = hasBlocks, isDeltaPackage = true)
            assertTrue("rejected=3 hasBlocks=$hasBlocks 必须判拒装", plan is UpdateDownloadPlan.StagedApply.Rejected)
            assertEquals(3, (plan as UpdateDownloadPlan.StagedApply.Rejected).rejectedEntryCount)
        }
    }

    @Test
    fun `差量档失败记坏基线而全量档不记`() {
        assertTrue((UpdateDownloadPlan.stagedApplyPlan(0, true, true) as UpdateDownloadPlan.StagedApply.Apply).recordBadBaseOnFailure)
        assertTrue((UpdateDownloadPlan.stagedApplyPlan(0, false, true) as UpdateDownloadPlan.StagedApply.Apply).recordBadBaseOnFailure)
        assertFalse((UpdateDownloadPlan.stagedApplyPlan(0, false, false) as UpdateDownloadPlan.StagedApply.Apply).recordBadBaseOnFailure)
    }

    // ------------------------------------------------------------------
    // 三、真包落地：路由走错就装不出东西（本文件最贵的一条）
    // ------------------------------------------------------------------

    @Test
    fun `块级包走块级树把补丁还原落盘`() {
        val gameRoot = newRoot()
        disk(gameRoot, "resources/a.bin", "ABCDEFGH".toByteArray())
        val zip = bundle(
            patchEntry("resources/a.bin", old = "ABCDEFGH".toByteArray(), new = "ABXDEFGH".toByteArray(), block = 4),
            ZipAssetTree.DELTA_LIST_NAME to ByteArray(0),
        )
        val applied = UpdateManager.applyBundle(zip, gameRoot, version = 9L)
        assertTrue("应当走块级树并落盘，实际 $applied", applied is UpdateManager.BundleApply.Applied)
        applied as UpdateManager.BundleApply.Applied
        assertEquals(DataArtifactKind.BLOCK_DELTA, applied.kind)
        // 这两行是本文件最要紧的断言：普通 zip 树里**没有**这条资产（只有 update-blocks 下的补丁），
        // 走错树时 copiedFiles 会是 0、盘上还是旧字节，而安装照样返回成功。
        assertEquals(1, applied.result.copiedFiles)
        assertEquals(8L, applied.result.copiedBytes)
        assertArrayEq("ABXDEFGH", File(gameRoot, "resources/a.bin").readBytes())
    }

    @Test
    fun `块级包缺基线文件时判死且不落半份语料`() {
        val gameRoot = newRoot()
        // 盘上没有 resources/b.bin：补丁引用的旧文件不在，块级树必须在动盘之前抛。
        val zip = bundle(
            patchEntry("resources/b.bin", old = "ABCDEFGH".toByteArray(), new = "ABXDEFGH".toByteArray(), block = 4),
            ZipAssetTree.DELTA_LIST_NAME to ByteArray(0),
        )
        val crashed = UpdateManager.applyBundle(zip, gameRoot, version = 9L)
        assertTrue("基线缺文件必须走失败支，实际 $crashed", crashed is UpdateManager.BundleApply.Crashed)
        crashed as UpdateManager.BundleApply.Crashed
        assertEquals(DataArtifactKind.BLOCK_DELTA, crashed.kind)
        // 这一条就是「下一次检查别再试块级」的凭据（core 的 diskTrusted 同一道闸）。
        assertTrue("块级档失败必须记坏基线", crashed.recordBadBase)
        assertFalse("不许留下半份语料", File(gameRoot, "resources/b.bin").exists())
    }

    @Test
    fun `块级路径下清单删除表仍然生效`() {
        // 块级包与文件级包共用同一张 `update-delta.txt`（发布侧只写那一份表）：树是
        // BlockDeltaAssetTree，但删除面走 GameAssetInstaller 的既有那一拍。接线时如果忘了
        // 把 DeltaDeletionSource 交给解包器，表现不是报错而是「该删的旧文件永远留在盘上」。
        val gameRoot = newRoot()
        disk(gameRoot, "resources/a.bin", "ABCDEFGH".toByteArray())
        disk(gameRoot, "resources/gone.bin", "旧版才有".toByteArray())
        val zip = bundle(
            patchEntry("resources/a.bin", old = "ABCDEFGH".toByteArray(), new = "ABXDEFGH".toByteArray(), block = 4),
            ZipAssetTree.DELTA_LIST_NAME to "resources/gone.bin\n".toByteArray(),
        )
        val applied = UpdateManager.applyBundle(zip, gameRoot, version = 9L) as UpdateManager.BundleApply.Applied
        assertEquals(DataArtifactKind.BLOCK_DELTA, applied.kind)
        assertArrayEq("ABXDEFGH", File(gameRoot, "resources/a.bin").readBytes())
        assertFalse("删除表那条必须照删", File(gameRoot, "resources/gone.bin").exists())
        assertEquals(1, applied.result.deletedFiles)
        assertEquals("表内只有一条，不该有受阻的", 0, applied.result.blockedDeletions)
    }

    @Test
    fun `形状不合格的补丁计入拒绝数并整包拒装`() {
        val gameRoot = newRoot()
        disk(gameRoot, "resources/a.bin", "ABCDEFGH".toByteArray())
        val zip = bundle(
            "update-blocks/resources/a.bin" to "这不是补丁".toByteArray(),
            ZipAssetTree.DELTA_LIST_NAME to ByteArray(0),
        )
        val rejected = UpdateManager.applyBundle(zip, gameRoot, version = 9L)
        assertTrue("坏补丁必须整包拒装，实际 $rejected", rejected is UpdateManager.BundleApply.RejectedBundle)
        rejected as UpdateManager.BundleApply.RejectedBundle
        assertEquals(1, rejected.rejectedEntryCount)
        assertEquals(DataArtifactKind.BLOCK_DELTA, rejected.kind)
        // 拒装不碰盘：那条内容还是旧文件。
        assertArrayEq("ABCDEFGH", File(gameRoot, "resources/a.bin").readBytes())
    }

    @Test
    fun `zip-slip 混在块级包里同样整包拒装`() {
        val gameRoot = newRoot()
        disk(gameRoot, "resources/a.bin", "ABCDEFGH".toByteArray())
        val zip = bundle(
            patchEntry("resources/a.bin", old = "ABCDEFGH".toByteArray(), new = "ABXDEFGH".toByteArray(), block = 4),
            "../escape.bin" to "y".toByteArray(),
            ZipAssetTree.DELTA_LIST_NAME to ByteArray(0),
        )
        val rejected = UpdateManager.applyBundle(zip, gameRoot, version = 9L)
        assertTrue("越界条目必须整包拒装，实际 $rejected", rejected is UpdateManager.BundleApply.RejectedBundle)
        rejected as UpdateManager.BundleApply.RejectedBundle
        // 计数只来自 ZipAssetTree 那一道既有闸（这里不自己再判一遍 zip-slip）。
        assertEquals(1, rejected.rejectedEntryCount)
        assertEquals(DataArtifactKind.BLOCK_DELTA, rejected.kind)
        assertFalse("游戏根之外不许落任何东西", File(gameRoot.parentFile, "escape.bin").exists())
        assertArrayEq("ABCDEFGH", File(gameRoot, "resources/a.bin").readBytes())
        assertTrue(UpdateManager.rejectedProblem(rejected.rejectedEntryCount, rejected.kind).contains("补丁条目"))
    }

    @Test
    fun `非块级档的拒装原话与改前逐字同文`() {
        assertEquals(
            "数据包里有 2 条非法路径，已拒绝应用并丢弃该包",
            UpdateManager.rejectedProblem(2, DataArtifactKind.FULL),
        )
        assertEquals(
            "数据包里有 2 条非法路径，已拒绝应用并丢弃该包",
            UpdateManager.rejectedProblem(2, DataArtifactKind.FILE_DELTA),
        )
    }

    @Test
    fun `非块级包带非法条目仍走既有那道拒装闸`() {
        // 这条钉的是**回归**：接线时把「先问 rejectedEntryCount 再落地」搬进了 stagedApplyPlan，
        // 老路径（文件级差量与全量）那道闸一根毛都不许掉 —— 少了它，一份被人改过的包会
        // 直接往玩家游戏根外面写文件。破坏性验证实测：把 plan 里的计数闸短路，本条与
        // 「包里有 update-blocks 条目才走块级树」同时红，两条一起挡住这类改动。
        val gameRoot = newRoot()
        val zip = bundle(
            "resources/ok.bin" to "正常一条".toByteArray(),
            "../escape.bin" to "越界一条".toByteArray(),
            ZipAssetTree.DELTA_LIST_NAME to ByteArray(0),
        )
        val rejected = UpdateManager.applyBundle(zip, gameRoot, version = 9L)
        assertTrue("越界条目必须整包拒装，实际 $rejected", rejected is UpdateManager.BundleApply.RejectedBundle)
        rejected as UpdateManager.BundleApply.RejectedBundle
        assertEquals(1, rejected.rejectedEntryCount)
        assertEquals(DataArtifactKind.FILE_DELTA, rejected.kind)
        assertFalse("拒装不许碰盘：一条都不该落", File(gameRoot, "resources/ok.bin").exists())
        assertFalse("游戏根之外不许落任何东西", File(gameRoot.parentFile, "escape.bin").exists())
    }

    @Test
    fun `文件级差量与全量包仍走普通 zip 树`() {
        val gameRoot = newRoot()
        disk(gameRoot, "resources/old.bin", "要删掉".toByteArray())
        val deltaZip = bundle(
            "resources/new.bin" to "新内容".toByteArray(),
            ZipAssetTree.DELTA_LIST_NAME to "resources/old.bin\n".toByteArray(),
        )
        val applied = UpdateManager.applyBundle(deltaZip, gameRoot, version = 9L) as UpdateManager.BundleApply.Applied
        assertEquals(DataArtifactKind.FILE_DELTA, applied.kind)
        assertArrayEq("新内容", File(gameRoot, "resources/new.bin").readBytes())
        assertFalse("清单删除表在应用点仍生效", File(gameRoot, "resources/old.bin").exists())

        val fullZip = bundle("resources/full.bin" to "整条".toByteArray())
        val fullApplied = UpdateManager.applyBundle(fullZip, gameRoot, version = 10L) as UpdateManager.BundleApply.Applied
        assertEquals(DataArtifactKind.FULL, fullApplied.kind)
        assertArrayEq("整条", File(gameRoot, "resources/full.bin").readBytes())
    }

    @Test
    fun `连归档都打不开时不记坏基线也不抛`() {
        val gameRoot = newRoot()
        val notAZip = File(temp.newFolder("raw"), "eratw-assets-9.zip")
        notAZip.writeBytes("不是 zip".toByteArray())
        val crashed = UpdateManager.applyBundle(notAZip, gameRoot, version = 9L)
        assertTrue(crashed is UpdateManager.BundleApply.Crashed)
        crashed as UpdateManager.BundleApply.Crashed
        assertNull("探不出包内形态就不许声称知道档位", crashed.kind)
        assertFalse("与改前同形：读不出删除表 ⇒ 不记坏基线", crashed.recordBadBase)
        assertNotNull(crashed.cause)
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private fun newRoot(): File = File(temp.newFolder("game"), "eratw").apply { mkdirs() }

    private fun disk(root: File, rel: String, bytes: ByteArray) {
        val target = File(root, rel)
        target.parentFile!!.mkdirs()
        target.writeBytes(bytes)
    }

    private fun assertArrayEq(text: String, actual: ByteArray) =
        assertEquals(text, String(actual, Charsets.UTF_8))

    /** 造一条块级补丁条目：op 流按「整段字面量 + 若干 COPY 块」摆，与设备侧读法同一条算式。 */
    private fun patchEntry(rel: String, old: ByteArray, new: ByteArray, block: Int): Pair<String, ByteArray> {
        val header = buildString {
            append("EBPD1\n")
            append("fmt 1\n")
            append("block ").append(block).append('\n')
            append("base-sha256 ").append(shaHex(old)).append('\n')
            append("result-sha256 ").append(shaHex(new)).append('\n')
            append("result-size ").append(new.size).append('\n')
            append("op-count 2\n")
            append("\n")
        }.toByteArray(Charsets.UTF_8)
        // op-count 2 的固定形状：新内容的前半按字面量发，后半引用旧文件的第 1 块。
        val literal = new.copyOfRange(0, block)
        val body = byteArrayOf('R'.code.toByte()) + int32(literal.size) + literal +
            byteArrayOf('C'.code.toByte()) + int32(1)
        return BlockDeltaAssetTree.PATCH_DIR_PREFIX + rel to header + body
    }

    private fun int32(value: Int): ByteArray = byteArrayOf(
        (value shr 24 and 0xFF).toByte(),
        (value shr 16 and 0xFF).toByte(),
        (value shr 8 and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )

    private fun shaHex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString(separator = "") { "%02x".format(it.toInt() and 0xFF) }

    private fun bundle(vararg entries: Pair<String, ByteArray>): File {
        // 一条用例可能造两份包（文件级 + 全量），所以这里**不能**用 newFolder（第二次会撞名抛
        // IOException）；同一个临时根下按纳秒取名即可。
        val dir = File(temp.root, "zips").apply { mkdirs() }
        val file = File(dir, "eratw-assets-${System.nanoTime()}.zip")
        ZipOutputStream(file.outputStream().buffered()).use { out ->
            for ((name, bytes) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return file
    }
}
