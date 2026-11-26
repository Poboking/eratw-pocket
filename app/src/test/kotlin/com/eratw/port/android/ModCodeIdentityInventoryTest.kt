package com.eratw.port.android

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 新增的 `<diskPkg>.codeid` 侧车在**盘面成员面**的接入判据（ADM 裁决 6）：
 *
 * - [ModPackageInventory.memberNames] 是「包在 `mods/rt2` 下的全部文件产物」单源：漏了侧车 ⇒
 *   卸载/替换会静默留一个孤儿文件，故 `memberNames` 必须含它（且写序在覆盖侧车之后）；
 * - [ModPackageInventory.gcOrphans] 走 [ModPackageInventory.packageIdOf] 的后缀表：`.codeid`
 *   进表后「只剩身份侧车的半成品」能被清掉，完整包则整包保留；
 * - [ModManageUninstall.uninstall] 直接复用 `memberNames` ⇒ 卸载自动删净侧车。
 *
 * 纯 JVM：`mods/rt2` 与 `mods` 都用临时目录模拟。
 */
class ModCodeIdentityInventoryTest {

    private val roots = ArrayList<File>()

    @After
    fun tearDown() {
        for (root in roots) {
            root.walkBottomUp().forEach { it.setWritable(true) }
            root.deleteRecursively()
        }
        roots.clear()
    }

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { roots += it }

    private fun writeMembers(rt2: File) {
        for (name in ModPackageInventory.memberNames(PKG)) {
            File(rt2, name).writeText("member", Charsets.UTF_8)
        }
    }

    @Test
    fun `成员名单含代码身份侧车并排在覆盖侧车之后`() {
        assertEquals(
            "文件产物六件：provenance → 内容指纹 → manifest → 容器 → 覆盖侧车 → 代码身份侧车",
            listOf(
                "$PKG.sources",
                "$PKG.sources.sha256",
                "$PKG.manifest",
                "$PKG.erbm",
                "$PKG.assetmap",
                "$PKG.codeid",
            ),
            ModPackageInventory.memberNames(PKG),
        )
    }

    @Test
    fun `只剩身份侧车的半成品被孤儿 GC 清掉`() {
        val rt2 = tempDir("rt2")
        val mods = tempDir("mods")
        File(rt2, "$PKG.codeid").writeText("RT2CODEID/1\nauthor.x\n", Charsets.UTF_8)

        ModPackageInventory.gcOrphans(rt2, mods)

        assertFalse(
            "没有容器/manifest 的 .codeid 半成品必须清掉（否则永远孤儿）",
            File(rt2, "$PKG.codeid").exists(),
        )
    }

    @Test
    fun `完整包的代码身份侧车随包保留`() {
        val rt2 = tempDir("rt2")
        val mods = tempDir("mods")
        writeMembers(rt2)

        ModPackageInventory.gcOrphans(rt2, mods)

        for (name in ModPackageInventory.memberNames(PKG)) {
            assertTrue("完整包成员必须保留：$name", File(rt2, name).isFile)
        }
    }

    @Test
    fun `卸载删净代码身份侧车`() {
        val rt2 = tempDir("rt2")
        writeMembers(rt2)

        assertTrue("卸载必须成功", ModManageUninstall.uninstall(rt2, PKG))

        assertFalse("卸载不得留 .codeid 孤儿", File(rt2, "$PKG.codeid").exists())
    }

    @Test
    fun `包管理扫描只认 manifest——身份侧车不会凭空多出一个包`() {
        assertTrue("前提：名单含 .codeid", "$PKG.codeid" in ModPackageInventory.memberNames(PKG))
        // 扫描器源码判据：只枚举 `*.manifest`（Import 侧车不会成为可管理包）。
        val scanner = Regex(
            """endsWith\(\s*(?:ModRuntimeAssembly|ModPackageInventory)\.MANIFEST_EXTENSION\s*\)""",
        )
        assertTrue(
            "ModManageScanner 必须只按 manifest 后缀枚举",
            scanner.containsMatchIn(sourceOf("ModManageModel.kt")),
        )
    }

    private fun sourceOf(fileName: String): String =
        File(repoRoot(), "app/src/main/kotlin/com/eratw/port/android/$fileName").readText(Charsets.UTF_8)

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "app/src/main/kotlin/com/eratw/port/android").isDirectory) return dir
            dir = dir.parentFile
        }
        error("找不到仓库根（app/src/main/kotlin/com/eratw/port/android）")
    }

    private companion object {
        const val PKG = "kojo-1a2b3c4d"
    }
}
