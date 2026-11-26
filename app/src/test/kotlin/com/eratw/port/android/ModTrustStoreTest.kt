package com.eratw.port.android

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第三方代码包信任标记（ADR-013 D3 / task-153 S4b）的定向单测。
 *
 * 只测盘面语义，不启动 Android 运行时：空标记与 .off 同构、无产物拒写、非法包名拒写拒删、
 * 撤销即时生效（下次装载回 NeedsTrust）、列表只认合法产物与普通标记文件，以及设置页状态机
 * 「取消二次确认不写盘」。写盘一律走临时目录，tearDown 清场。
 */
class ModTrustStoreTest {
    private val tempDir: File = Files.createTempDirectory("mod-trust-store-").toFile()
    private val rt2Dir: File = File(tempDir, "mods/rt2").apply { mkdirs() }

    @After
    fun tearDown() {
        tempDir.walkBottomUp().forEach { file ->
            file.setWritable(true)
            file.delete()
        }
    }

    private fun codeDir(): File = File(rt2Dir, MOD_CODE_DIR_NAME)

    /** 造一份合法产物：code/<pkg>/<16 位小写十六进制>.mod（装载侧与列表侧都认的形状）。 */
    private fun createArtifact(packageId: String, key: String = "0123456789abcdef"): File {
        val dir = File(codeDir(), packageId).apply { mkdirs() }
        return File(dir, key + MOD_CODE_ARTIFACT_EXTENSION).apply {
            writeBytes(byteArrayOf(0x45, 0x52, 0x42, 0x43))
        }
    }

    private fun marker(packageId: String): File = File(rt2Dir, ModDexGate.trustMarkerName(packageId))

    @Test
    fun `信任写空标记且被装载闸识别`() {
        createArtifact(PKG)
        assertTrue(ModTrustStore.trust(rt2Dir, PKG))
        val markerFile = marker(PKG)
        assertTrue(markerFile.isFile)
        assertEquals("标记名必须复用 ModDexGate 的 .trusted 后缀", "$PKG.trusted", markerFile.name)
        assertEquals("标记必须与 .off 同构（空文件）", 0L, markerFile.length())
        assertArrayEquals("标记内容必须是空的", ByteArray(0), markerFile.readBytes())
        assertTrue(ModDexGate.isTrusted(rt2Dir, PKG))
        assertTrue(
            "临时文件必须已换名消失",
            rt2Dir.listFiles().orEmpty().none { it.name.endsWith(".tmp") },
        )
    }

    @Test
    fun `重复信任幂等`() {
        createArtifact(PKG)
        assertTrue(ModTrustStore.trust(rt2Dir, PKG))
        val first = marker(PKG).readBytes()
        assertTrue(ModTrustStore.trust(rt2Dir, PKG))
        assertArrayEquals(first, marker(PKG).readBytes())
        assertEquals(listOf(PKG), ModTrustStore.trustedPackages(rt2Dir))
    }

    @Test
    fun `撤销信任删标记且幂等`() {
        createArtifact(PKG)
        assertTrue(ModTrustStore.trust(rt2Dir, PKG))
        assertTrue(ModTrustStore.distrust(rt2Dir, PKG))
        assertFalse(marker(PKG).exists())
        assertFalse(ModDexGate.isTrusted(rt2Dir, PKG))
        assertTrue("本来就没有标记时也算撤销成功", ModTrustStore.distrust(rt2Dir, PKG))
        assertTrue(ModTrustStore.trustedPackages(rt2Dir).isEmpty())
    }

    @Test
    fun `没有代码产物拒写标记`() {
        assertFalse("rt2 尚无 code 目录时不得落信任", ModTrustStore.trust(rt2Dir, PKG))
        val emptyPkgDir = File(codeDir(), PKG).apply { mkdirs() }
        assertFalse("空包目录不得落信任", ModTrustStore.trust(rt2Dir, PKG))
        File(emptyPkgDir, "not-a-sha.mod").writeText("x")
        assertFalse("坏产物文件名不得落信任", ModTrustStore.trust(rt2Dir, PKG))
        assertFalse(marker(PKG).exists())
    }

    @Test
    fun `非法包名拒写拒删且不增文件`() {
        createArtifact(PKG)
        val before = rt2Dir.list().orEmpty().sorted()
        listOf("", " ", "../escape", "a/b", "a\\b", ".hidden", "-lead").forEach { bad ->
            assertFalse("非法包名不该写标记：[$bad]", ModTrustStore.trust(rt2Dir, bad))
            assertFalse("非法包名不该删标记：[$bad]", ModTrustStore.distrust(rt2Dir, bad))
        }
        assertEquals(before, rt2Dir.list().orEmpty().sorted())
        assertTrue(ModTrustStore.trustedPackages(rt2Dir).isEmpty())
    }

    @Test
    fun `包列表只列有合法产物的包并计数`() {
        createArtifact("demo.mod")
        createArtifact("other.mod", "fedcba9876543210")
        createArtifact("other.mod")
        File(codeDir(), "empty.mod").mkdirs()
        createArtifact("bad name")
        val junk = File(codeDir(), "junk.mod").apply { mkdirs() }
        File(junk, "readme.txt").writeText("no artifact")
        val packages = ModTrustStore.codePackages(codeDir())
        assertEquals(listOf("demo.mod", "other.mod"), packages.map { it.packageId })
        assertEquals("产物计数按包聚合", listOf(1, 2), packages.map { it.artifactCount })
    }

    @Test
    fun `信任列表只认普通文件标记且包名规范`() {
        createArtifact("demo.mod")
        createArtifact("other.mod")
        ModTrustStore.trust(rt2Dir, "demo.mod")
        File(rt2Dir, "other.mod.trusted").mkdirs()
        File(rt2Dir, "bad name.trusted").writeText("")
        File(rt2Dir, "read me.trusted").writeText("")
        File(rt2Dir, "notes.txt").writeText("")
        assertEquals(listOf("demo.mod"), ModTrustStore.trustedPackages(rt2Dir))
        assertFalse("目录名带 .trusted 不算已信任", ModDexGate.isTrusted(rt2Dir, "other.mod"))
    }

    @Test
    fun `目录标记不算信任撤销后可清掉`() {
        createArtifact("demo.mod")
        File(rt2Dir, "demo.mod.trusted").mkdirs()
        assertFalse(ModDexGate.isTrusted(rt2Dir, "demo.mod"))
        assertFalse(ModTrustStore.trustedPackages(rt2Dir).contains("demo.mod"))
        assertTrue(ModTrustStore.distrust(rt2Dir, "demo.mod"))
        assertFalse(File(rt2Dir, "demo.mod.trusted").exists())
    }

    @Test
    fun `没有代码目录时列表为空且不抛`() {
        assertTrue(ModTrustStore.codePackages(codeDir()).isEmpty())
        assertTrue(ModTrustBoard(rt2Dir).scan().isEmpty())
    }

    @Test
    fun `取消二次确认不写盘`() {
        createArtifact(PKG)
        val board = ModTrustBoard(rt2Dir)
        assertEquals(1, board.scan().size)
        assertFalse(board.rows.single().trusted)
        board.requestTrust(PKG)
        assertEquals(PKG, board.pendingTrustPackageId)
        board.cancelTrust()
        assertNull(board.pendingTrustPackageId)
        assertFalse("取消后不得出现标记", marker(PKG).exists())
        assertEquals(listOf(false), board.rows.map { it.trusted })
    }

    @Test
    fun `确认信任落盘并重扫为已信任`() {
        createArtifact(PKG)
        val board = ModTrustBoard(rt2Dir)
        assertEquals(1, board.scan().size)
        board.requestTrust(PKG)
        assertTrue(board.confirmTrust())
        assertNull(board.pendingTrustPackageId)
        assertTrue(board.rows.single().trusted)
        assertTrue(ModDexGate.isTrusted(rt2Dir, PKG))
    }

    @Test
    fun `撤销信任即时重扫回未信任`() {
        createArtifact(PKG)
        assertTrue(ModTrustStore.trust(rt2Dir, PKG))
        val board = ModTrustBoard(rt2Dir)
        assertTrue(board.scan().single().trusted)
        assertTrue(board.revoke(PKG))
        assertFalse(board.rows.single().trusted)
        assertFalse("撤销后下次装载必须回 NeedsTrust", ModDexGate.isTrusted(rt2Dir, PKG))
    }

    @Test
    fun `多包信任态各自独立`() {
        createArtifact("demo.mod")
        createArtifact("other.mod")
        assertTrue(ModTrustStore.trust(rt2Dir, "demo.mod"))
        val rows = ModTrustBoard(rt2Dir).scan()
        assertEquals(listOf("demo.mod", "other.mod"), rows.map { it.packageId })
        assertEquals(listOf(true, false), rows.map { it.trusted })
    }

    @Test
    fun `无待确认时确认返回false且不写盘`() {
        createArtifact(PKG)
        val board = ModTrustBoard(rt2Dir)
        assertFalse(board.confirmTrust())
        assertFalse(marker(PKG).exists())
    }

    // ------------------------------------------------------------------
    // task-199：信任面统一到 ModDexIdentity.resolve 单一身份桥
    // ------------------------------------------------------------------

    /** 造一个双身份包：磁盘身份 = `code/<disk>/` 目录名，运行期身份 = `.codeid` 侧车声明。 */
    private fun createDualIdentityArtifact(diskPackageId: String, runtimePackageId: String): File {
        val artifact = createArtifact(diskPackageId)
        ModCodeIdentity.write(rt2Dir, diskPackageId, runtimePackageId)
        return artifact
    }

    @Test
    fun `双身份包解析为运行期身份且信任只写运行期标记`() {
        createDualIdentityArtifact(DUAL_DISK_ID, DUAL_RUNTIME_ID)
        assertEquals(
            "双身份包必须解析成运行期身份",
            MarkerTarget.Target(DUAL_RUNTIME_ID),
            ModTrustStore.resolveMarkerId(rt2Dir, DUAL_DISK_ID),
        )
        assertEquals(DUAL_RUNTIME_ID, ModTrustStore.resolveRuntimeId(rt2Dir, DUAL_DISK_ID))
        assertNull(ModTrustStore.markerBlockedReason(rt2Dir, DUAL_DISK_ID))

        assertTrue("信任必须落盘成功", ModTrustStore.trust(rt2Dir, DUAL_DISK_ID))

        assertTrue("标记必须落在运行期身份上（装载闸读取键）", marker(DUAL_RUNTIME_ID).isFile)
        assertEquals("标记必须与 .off 同构（空文件）", 0L, marker(DUAL_RUNTIME_ID).length())
        assertFalse("不得再写磁盘身份标记（装载闸从不读它）", marker(DUAL_DISK_ID).exists())
        assertTrue(ModTrustStore.isTrusted(rt2Dir, DUAL_DISK_ID))
        assertTrue("装载闸读取键必须信任", ModDexGate.isTrusted(rt2Dir, DUAL_RUNTIME_ID))
        assertTrue("信任表行必须显示已信任", ModTrustBoard(rt2Dir).scan().single().trusted)
    }

    @Test
    fun `双身份撤销同时清运行期标记与旧版磁盘标记`() {
        createDualIdentityArtifact(DUAL_DISK_ID, DUAL_RUNTIME_ID)
        assertTrue(ModTrustStore.trust(rt2Dir, DUAL_DISK_ID))
        // 旧版缺陷会把信任写在磁盘身份上；撤销必须顺手清掉，玩家才不会觉得「撤销没生效」。
        marker(DUAL_DISK_ID).writeText("", Charsets.UTF_8)

        assertTrue("撤销必须成功", ModTrustStore.distrust(rt2Dir, DUAL_DISK_ID))

        assertFalse("运行期标记必须删净", marker(DUAL_RUNTIME_ID).exists())
        assertFalse("旧版磁盘身份标记也必须删净", marker(DUAL_DISK_ID).exists())
        assertFalse(ModTrustStore.isTrusted(rt2Dir, DUAL_DISK_ID))
        assertFalse(ModDexGate.isTrusted(rt2Dir, DUAL_RUNTIME_ID))
    }

    @Test
    fun `双身份包侧车损坏时解析被拒且信任操作盘面零写入`() {
        createDualIdentityArtifact(DUAL_DISK_ID, DUAL_RUNTIME_ID)
        File(rt2Dir, ModCodeIdentity.fileName(DUAL_DISK_ID)).writeText("garbage", Charsets.UTF_8)
        val before = rt2Dir.walkTopDown()
            .map { it.relativeTo(rt2Dir).path to it.isFile }
            .sortedBy { it.first }
            .toList()

        assertEquals(
            MarkerTarget.Blocked(TrustMarkerBlockReason.IDENTITY_CORRUPT),
            ModTrustStore.resolveMarkerId(rt2Dir, DUAL_DISK_ID),
        )
        assertEquals(
            TrustMarkerBlockReason.IDENTITY_CORRUPT,
            ModTrustStore.markerBlockedReason(rt2Dir, DUAL_DISK_ID),
        )
        assertNull("坏档不得猜运行期身份", ModTrustStore.resolveRuntimeId(rt2Dir, DUAL_DISK_ID))
        assertFalse("坏档信任必须受控失败", ModTrustStore.trust(rt2Dir, DUAL_DISK_ID))
        assertFalse("坏档撤销必须受控失败", ModTrustStore.distrust(rt2Dir, DUAL_DISK_ID))
        assertFalse(ModTrustStore.isTrusted(rt2Dir, DUAL_DISK_ID))

        val row = ModTrustBoard(rt2Dir).scan().single()
        assertFalse("坏档行必须显示未信任", row.trusted)
        assertEquals(TrustMarkerBlockReason.IDENTITY_CORRUPT, row.blockedReason)
        val after = rt2Dir.walkTopDown()
            .map { it.relativeTo(rt2Dir).path to it.isFile }
            .sortedBy { it.first }
            .toList()
        assertEquals("坏档时信任面必须盘面零写入", before, after)
    }

    @Test
    fun `非法包名解析被拒为非法包名原因且盘面不变`() {
        createArtifact(PKG)
        val before = rt2Dir.list().orEmpty().sorted()
        assertEquals(
            MarkerTarget.Blocked(TrustMarkerBlockReason.INVALID_PACKAGE_ID),
            ModTrustStore.resolveMarkerId(rt2Dir, "../escape"),
        )
        assertEquals(
            TrustMarkerBlockReason.INVALID_PACKAGE_ID,
            ModTrustStore.markerBlockedReason(rt2Dir, "../escape"),
        )
        assertNull(ModTrustStore.resolveRuntimeId(rt2Dir, "../escape"))
        assertFalse(ModTrustStore.isTrusted(rt2Dir, "../escape"))
        assertFalse(ModTrustStore.trust(rt2Dir, "../escape"))
        assertFalse(ModTrustStore.distrust(rt2Dir, "../escape"))
        assertEquals("非法包名不得改动盘面", before, rt2Dir.list().orEmpty().sorted())
    }

    @Test
    fun `单身份包解析回磁盘身份且既有信任行为不变`() {
        createArtifact(PKG)
        assertEquals(
            "缺侧车 ⇒ 运行期身份回落到磁盘身份",
            MarkerTarget.Target(PKG),
            ModTrustStore.resolveMarkerId(rt2Dir, PKG),
        )
        assertEquals(PKG, ModTrustStore.resolveRuntimeId(rt2Dir, PKG))
        assertTrue(ModTrustStore.trust(rt2Dir, PKG))
        assertEquals("单身份包标记名逐字节不变", "$PKG.trusted", marker(PKG).name)
        assertEquals(0L, marker(PKG).length())
        assertTrue(ModTrustStore.isTrusted(rt2Dir, PKG))
        assertTrue(ModTrustStore.distrust(rt2Dir, PKG))
        assertFalse("单身份包撤销同样删磁盘身份标记", marker(PKG).exists())
    }

    @Test
    fun `信任表坏档行状态文案复用身份损坏说明`() {
        val zh = SettingsStrings.of(SettingsLanguage.ZH)
        assertEquals("未信任", modTrustRowStateLabel(zh, ModTrustRow(PKG, 2, false)))
        assertEquals("已信任", modTrustRowStateLabel(zh, ModTrustRow(PKG, 2, true)))
        assertEquals(
            "坏档行必须说明补信任没用",
            zh.modCodeStateCorrupt,
            modTrustRowStateLabel(zh, ModTrustRow(PKG, 2, false, TrustMarkerBlockReason.IDENTITY_CORRUPT)),
        )
    }

    @Test
    fun `信任失败文案按原因分流且成功不上屏失败文案`() {
        val zh = SettingsStrings.of(SettingsLanguage.ZH)
        assertNull("成功不产生失败文案", modTrustFailureCopy(zh, written = true, blocked = null))
        assertEquals(
            "身份坏档要说清补信任没用",
            zh.modCodeStateCorrupt,
            modTrustFailureCopy(zh, written = false, blocked = TrustMarkerBlockReason.IDENTITY_CORRUPT),
        )
        assertEquals(
            "非法包名沿用写盘失败文案",
            zh.modTrustWriteFailed,
            modTrustFailureCopy(zh, written = false, blocked = TrustMarkerBlockReason.INVALID_PACKAGE_ID),
        )
        assertEquals(
            "普通写盘失败沿用写盘失败文案",
            zh.modTrustWriteFailed,
            modTrustFailureCopy(zh, written = false, blocked = null),
        )
    }

    private companion object {
        private const val PKG = "demo.mod"
        private const val DUAL_DISK_ID = "kojo-0123abcd"
        private const val DUAL_RUNTIME_ID = "author.dual"
    }
}
