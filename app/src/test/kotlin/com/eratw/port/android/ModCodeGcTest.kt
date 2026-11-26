package com.eratw.port.android

import com.eratw.port.core.save.SaveDiagnostics
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-013 D4 / S6 定向判据：dex 代码产物树（`mods/rt2/code/<packageId>/` 下的产物）接入「卸载删净」与
 * 「孤儿 GC」。
 *
 * 判的是五件事：
 * 1. 显式卸载删净本包产物（含只读件）+ 空包目录，别包产物一动不动；幂等。
 * 2. 卸载顺带删 `<pkg>.trusted`（同包名重装不得静默继承旧信任）。
 * 3. 孤儿 GC 只清「确定无主」：完整包 / 包级没删成的半成品 / 同 sha 仍被活包引用的一律保留；
 *    台账（`released-packages.txt`）只取证、不阻止回收。
 * 4. `code/` 根永不删（止于根之前），`code/` 不进包成员枚举（包级判据不碰它）。
 * 5. 取证面：每次回收都在 `MOD_PKG_GC` 面留一行 `code/<pkg>` 说明。
 */

private const val PKG_A = "kojo-aaaa1111"
private const val PKG_B = "kojo-bbbb2222"

private const val PAYLOAD_V1 = "dex-payload-v1"
private const val PAYLOAD_V2 = "dex-payload-v2"
private const val PAYLOAD_SHARED = "dex-payload-shared"

private fun tempRoot(label: String): File = Files.createTempDirectory(label).toFile()

private fun rt2Of(root: File): File = File(root, "mods/rt2").apply { mkdirs() }

private fun modsOf(root: File): File = File(root, "mods").apply { mkdirs() }

private fun codeOf(rt2: File): File = File(rt2, MOD_CODE_DIR_NAME)

private fun packageCodeDir(rt2: File, packageId: String): File = File(codeOf(rt2), packageId)

/**
 * 完整包成员（五件套 + 设置侧车）：包级判据判「完整包」，显式卸载也能返回 true。
 *
 * `.codeid` 是身份侧车而不是普通成员：这里必须写**合法**侧车（磁盘身份 == 运行期身份），
 * 塞垃圾字节会被信任面判成身份损坏、受控拒绝信任（task-199 fail-closed），夹具意图是「完整包」。
 */
private fun writePackageMembers(rt2: File, packageId: String) {
    val sidecarName = ModCodeIdentity.fileName(packageId)
    for (name in ModPackageInventory.memberNames(packageId)) {
        if (name == sidecarName) continue
        File(rt2, name).writeText("member")
    }
    ModCodeIdentity.write(rt2, packageId, packageId)
    File(rt2, ModSettingsStore.memberName(packageId)).writeText("settings")
}

/** 只写 manifest（无 `.erbm`）：包级判据判「半成品：缺容器」。 */
private fun writeHalfPackage(rt2: File, packageId: String) {
    File(rt2, packageId + ModPackageInventory.MANIFEST_EXTENSION).writeText("manifest")
}

private fun installCode(rt2: File, packageId: String, payload: String): CodeArtifact =
    ModCodeStore(rt2).install(packageId, payload.toByteArray())

private fun artifactNames(rt2: File, packageId: String): List<String> =
    ModCodeStore(rt2).list(packageId).map { it.file.name }

private fun gc(rt2: File, mods: File) = ModPackageInventory.gcOrphans(rt2, mods)

private fun hasCodeTrace(fragment: String): Boolean = SaveDiagnostics.snapshot().any { line ->
    line.contains("MOD_PKG_GC") && line.contains("code/") && line.contains(fragment)
}

/** 只读产物在 Windows 上会挡 `delete()`：整树先 `setWritable(true)` 再删（同 ModCodeStoreTest）。 */
private fun cleanup(root: File) {
    root.walkBottomUp().forEach { entry ->
        entry.setWritable(true)
        entry.delete()
    }
}

private fun trustMarker(rt2: File, packageId: String): File =
    File(rt2, ModDexGate.trustMarkerName(packageId))

class ModCodeUninstallTest {

    @Test
    fun `卸载删净本包代码产物且别包产物不动`() {
        val root = tempRoot("mod-code-uninstall")
        try {
            val rt2 = rt2Of(root)
            writePackageMembers(rt2, PKG_A)
            writePackageMembers(rt2, PKG_B)
            installCode(rt2, PKG_A, PAYLOAD_V1)
            installCode(rt2, PKG_A, PAYLOAD_V2)
            installCode(rt2, PKG_B, PAYLOAD_SHARED)

            assertTrue(ModManageUninstall.uninstall(rt2, PKG_A))

            assertFalse("卸载后本包代码目录必须删净", packageCodeDir(rt2, PKG_A).exists())
            assertTrue("别的包代码目录不得动", packageCodeDir(rt2, PKG_B).isDirectory)
            assertEquals("别的包产物不得动", 1, artifactNames(rt2, PKG_B).size)
            assertTrue("别的包包级成员不得动", File(rt2, PKG_B + ModPackageInventory.MODULE_EXTENSION).isFile)
            assertTrue("code/ 根目录永删不得", codeOf(rt2).isDirectory)
        } finally {
            cleanup(root)
        }
    }

    @Test
    fun `卸载幂等且没有代码目录也返回成功`() {
        val root = tempRoot("mod-code-uninstall-idempotent")
        try {
            val rt2 = rt2Of(root)
            writePackageMembers(rt2, PKG_A)
            installCode(rt2, PKG_A, PAYLOAD_V1)

            assertTrue(ModManageUninstall.uninstall(rt2, PKG_A))
            assertFalse(packageCodeDir(rt2, PKG_A).exists())
            // 第二次：成员与代码目录都已不在，仍须 true（幂等，且新接线不得把「本就没有」判失败）。
            assertTrue(ModManageUninstall.uninstall(rt2, PKG_A))

            // 从未落过代码目录的包：卸载同样成功。
            writePackageMembers(rt2, PKG_B)
            assertTrue(ModManageUninstall.uninstall(rt2, PKG_B))
            assertFalse(packageCodeDir(rt2, PKG_B).exists())
        } finally {
            cleanup(root)
        }
    }

    @Test
    fun `卸载不碰 code 根与手放件`() {
        val root = tempRoot("mod-code-uninstall-stray")
        try {
            val rt2 = rt2Of(root)
            writePackageMembers(rt2, PKG_A)
            installCode(rt2, PKG_A, PAYLOAD_V1)
            // code/ 根下的非包目录与散文件：不是本包产物，卸载一律不碰。
            val handmade = File(codeOf(rt2), ".handmade").apply { mkdirs() }
            val handmadeFile = File(handmade, "note.txt").apply { writeText("player file") }
            val stray = File(codeOf(rt2), "stray.txt").apply { writeText("player file") }

            assertTrue(ModManageUninstall.uninstall(rt2, PKG_A))

            assertFalse(packageCodeDir(rt2, PKG_A).exists())
            assertTrue(codeOf(rt2).isDirectory)
            assertTrue("非包名目录不得删", handmadeFile.isFile)
            assertTrue("code/ 根下散文件不得删", stray.isFile)
        } finally {
            cleanup(root)
        }
    }

    @Test
    fun `卸载删信任标记且重装不自动信任`() {
        val root = tempRoot("mod-code-uninstall-trust")
        try {
            val rt2 = rt2Of(root)
            writePackageMembers(rt2, PKG_A)
            installCode(rt2, PKG_A, PAYLOAD_V1)
            assertTrue("先决：有产物才能落信任标记", ModTrustStore.trust(rt2, PKG_A))
            assertTrue(ModDexGate.isTrusted(rt2, PKG_A))

            assertTrue(ModManageUninstall.uninstall(rt2, PKG_A))

            assertFalse("卸载必须删净信任标记", trustMarker(rt2, PKG_A).exists())
            assertFalse("卸载后不得仍是已信任态", ModDexGate.isTrusted(rt2, PKG_A))

            // 同包名重装：包成员与产物都回来，但信任必须回到「待确认」初始态，不得静默继承。
            writePackageMembers(rt2, PKG_A)
            installCode(rt2, PKG_A, PAYLOAD_V2)
            assertFalse("重装后不得自动信任（否则旧信任被静默继承）", ModDexGate.isTrusted(rt2, PKG_A))
        } finally {
            cleanup(root)
        }
    }

    @Test
    fun `卸载仍拒绝非法包名`() {
        val root = tempRoot("mod-code-uninstall-invalid")
        try {
            val rt2 = rt2Of(root)
            val rejected = runCatching { ModManageUninstall.uninstall(rt2, "../evil") }.exceptionOrNull()
            assertTrue("非法包名必须拒在拼路径之前", rejected is IllegalArgumentException)
        } finally {
            cleanup(root)
        }
    }
}

class ModCodeGcTest {

    @Test
    fun `孤儿代码目录整树回收但 code 根保留`() {
        val root = tempRoot("mod-code-gc-orphan")
        try {
            val rt2 = rt2Of(root)
            val mods = modsOf(root)
            val orphan = installCode(rt2, PKG_A, PAYLOAD_V1)
            assertTrue(orphan.file.isFile)

            gc(rt2, mods)

            assertFalse("无包级成员的包目录必须整树回收", packageCodeDir(rt2, PKG_A).exists())
            assertTrue("code/ 根目录永删不得（止于根之前）", codeOf(rt2).isDirectory)
            assertTrue("回收必须进取证面", hasCodeTrace("孤儿代码目录已回收"))
        } finally {
            cleanup(root)
        }
    }

    @Test
    fun `完整包代码产物保留含多版本`() {
        val root = tempRoot("mod-code-gc-complete")
        try {
            val rt2 = rt2Of(root)
            val mods = modsOf(root)
            writePackageMembers(rt2, PKG_A)
            installCode(rt2, PKG_A, PAYLOAD_V1)
            installCode(rt2, PKG_A, PAYLOAD_V2)

            gc(rt2, mods)

            assertTrue("完整包代码目录必须保留", packageCodeDir(rt2, PKG_A).isDirectory)
            assertEquals("完整包多版本产物一件都不许动", 2, artifactNames(rt2, PKG_A).size)
            assertTrue(hasCodeTrace("包级成员仍在"))
        } finally {
            cleanup(root)
        }
    }

    @Test
    fun `半成品包代码产物随包级判据一起回收`() {
        val root = tempRoot("mod-code-gc-half")
        try {
            val rt2 = rt2Of(root)
            val mods = modsOf(root)
            writeHalfPackage(rt2, PKG_A)
            installCode(rt2, PKG_A, PAYLOAD_V1)

            gc(rt2, mods)

            assertFalse(
                "包级判据先删半成品 manifest",
                File(rt2, PKG_A + ModPackageInventory.MANIFEST_EXTENSION).exists(),
            )
            assertFalse("包级成员删净后代码目录同轮回收", packageCodeDir(rt2, PKG_A).exists())
        } finally {
            cleanup(root)
        }
    }

    @Test
    fun `同 sha 被活包引用的产物保留、独有产物删`() {
        val root = tempRoot("mod-code-gc-shared")
        try {
            val rt2 = rt2Of(root)
            val mods = modsOf(root)
            writePackageMembers(rt2, PKG_A)
            installCode(rt2, PKG_A, PAYLOAD_SHARED)
            // 孤儿包：一份与活包同内容（同 sha），一份自己独有。
            installCode(rt2, PKG_B, PAYLOAD_SHARED)
            installCode(rt2, PKG_B, PAYLOAD_V2)
            val orphanDir = packageCodeDir(rt2, PKG_B)
            assertEquals("先决：孤儿目录两件产物", 2, artifactNames(rt2, PKG_B).size)

            gc(rt2, mods)

            assertTrue("被引用 sha 的产物必须留在孤儿目录里", orphanDir.isDirectory)
            assertEquals("只保留被引用的那一件", 1, artifactNames(rt2, PKG_B).size)
            assertEquals(
                "留下的必须仍是活包引用的地址键",
                artifactNames(rt2, PKG_A),
                artifactNames(rt2, PKG_B),
            )
            assertTrue(hasCodeTrace("被引用地址键"))
        } finally {
            cleanup(root)
        }
    }

    @Test
    fun `台账记录不阻止已释放包回收且留取证`() {
        val root = tempRoot("mod-code-gc-ledger-reclaim")
        try {
            val rt2 = rt2Of(root)
            val mods = modsOf(root)
            installCode(rt2, PKG_A, PAYLOAD_V1)
            assertTrue("先决：台账写入成功", ModStorageReclaim.record(rt2, PKG_A))

            gc(rt2, mods)

            assertFalse("台账只是取证不是所有权，不得让它挡住回收", packageCodeDir(rt2, PKG_A).exists())
            assertTrue("台账取证必须写明", hasCodeTrace("台账记载为显式卸载"))
            assertTrue("回收台账本身不是回收对象", ModStorageReclaim.releasedPackageIds(rt2).contains(PKG_A))
        } finally {
            cleanup(root)
        }
    }

    @Test
    fun `重导活包旧台账条目下代码产物保留`() {
        val root = tempRoot("mod-code-gc-ledger-reimport")
        try {
            val rt2 = rt2Of(root)
            val mods = modsOf(root)
            assertTrue(ModStorageReclaim.record(rt2, PKG_A))
            // 重导：包级成员与产物都回来（台账旧条目惰性失效，照 reclaimedPackageIds 边界）。
            writePackageMembers(rt2, PKG_A)
            installCode(rt2, PKG_A, PAYLOAD_V1)

            gc(rt2, mods)

            assertTrue("重导活包的代码产物必须保留", packageCodeDir(rt2, PKG_A).isDirectory)
            assertEquals(1, artifactNames(rt2, PKG_A).size)
            assertTrue("旧台账条目不得被 GC 顺手抹掉", ModStorageReclaim.listFile(rt2).isFile)
        } finally {
            cleanup(root)
        }
    }

    @Test
    fun `code 根不进包成员枚举且手放件不被包级判据碰`() {
        val root = tempRoot("mod-code-gc-inventory")
        try {
            val rt2 = rt2Of(root)
            val mods = modsOf(root)
            writePackageMembers(rt2, PKG_A)
            installCode(rt2, PKG_A, PAYLOAD_V1)
            val stray = File(codeOf(rt2), "stray.txt").apply { writeText("player file") }
            val handmade = File(codeOf(rt2), ".handmade").apply { mkdirs() }
            val handmadeFile = File(handmade, "note.txt").apply { writeText("player file") }

            gc(rt2, mods)

            assertTrue("活包代码产物保留", packageCodeDir(rt2, PKG_A).isDirectory)
            assertTrue("code/ 根不是包成员，散文件不得被包级判据删", stray.isFile)
            assertTrue("非包名目录不是包成员，不得删", handmadeFile.isFile)
            assertTrue("code/ 根目录永删不得", codeOf(rt2).isDirectory)
        } finally {
            cleanup(root)
        }
    }

    @Test
    fun `GC 幂等且 code 目录缺失不抛`() {
        val root = tempRoot("mod-code-gc-idempotent")
        try {
            val rt2 = rt2Of(root)
            val mods = modsOf(root)
            gc(rt2, mods)
            assertFalse("没有 code 目录时 no-op", codeOf(rt2).exists())

            writePackageMembers(rt2, PKG_A)
            installCode(rt2, PKG_A, PAYLOAD_V1)
            gc(rt2, mods)
            assertEquals("完整包第二轮也不得动", 1, artifactNames(rt2, PKG_A).size)
            gc(rt2, mods)
            assertEquals("重复 GC 幂等", 1, artifactNames(rt2, PKG_A).size)
        } finally {
            cleanup(root)
        }
    }

    @Test
    fun `控制器级链路 信任扫描到卸载后行与产物一起消失`() {
        val root = tempRoot("mod-code-gc-controller")
        try {
            val rt2 = rt2Of(root)
            writePackageMembers(rt2, PKG_A)
            installCode(rt2, PKG_A, PAYLOAD_V1)
            val board = ModTrustBoard(rt2)

            assertEquals("代码包的产物数必须被控制器扫出来", 1, board.scan().single().artifactCount)
            board.requestTrust(PKG_A)
            assertTrue(board.confirmTrust())
            assertTrue("确认后控制器行是已信任", board.scan().single().trusted)

            assertTrue(ModManageUninstall.uninstall(rt2, PKG_A))

            assertTrue("卸载后控制器不再有该行", board.scan().isEmpty())
            assertFalse("卸载后信任标记不存在", trustMarker(rt2, PKG_A).exists())
            assertFalse("卸载后代码目录不存在", packageCodeDir(rt2, PKG_A).exists())
        } finally {
            cleanup(root)
        }
    }
}
