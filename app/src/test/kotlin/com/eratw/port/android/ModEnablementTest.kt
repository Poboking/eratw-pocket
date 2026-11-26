package com.eratw.port.android

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批2-3 启停标记（`mods/rt2/<packageId>.off`）的盘面语义。
 *
 * 断言的是「启停就是磁盘事实」这条契约：没有额外偏好表、没有内存缓存，
 * 因此每个用例都在真临时目录里造文件 / 删文件，再看 [ModEnablement] 的读数。
 */
class ModEnablementTest {

    private fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile()

    @Test
    fun `停用即建标记 启用即删标记 都没有也算成功`() {
        val rt2 = tempDir("rt2-enable-basic")
        try {
            assertFalse(ModEnablement.isDisabled(rt2, "eratw.family"))

            assertTrue(ModEnablement.setEnabled(rt2, "eratw.family", false))
            assertTrue(ModEnablement.markerFile(rt2, "eratw.family").isFile)
            assertTrue(ModEnablement.isDisabled(rt2, "eratw.family"))

            // 重复停用是幂等成功（createNewFile 已存在返回 false，但意图已达成）。
            assertTrue(ModEnablement.setEnabled(rt2, "eratw.family", false))

            assertTrue(ModEnablement.setEnabled(rt2, "eratw.family", true))
            assertFalse(ModEnablement.isDisabled(rt2, "eratw.family"))

            // 本来就没有标记时再启用也算成功：调用方是设置页按钮，不该因「无事可做」报错。
            assertTrue(ModEnablement.setEnabled(rt2, "eratw.family", true))
        } finally {
            rt2.deleteRecursively()
        }
    }

    @Test
    fun `禁用集合只认 off 词干`() {
        val rt2 = tempDir("rt2-enable-set")
        try {
            assertTrue(ModEnablement.disabledPackageIds(rt2).isEmpty())

            ModEnablement.setEnabled(rt2, "eratw.family", false)
            ModEnablement.setEnabled(rt2, "kojo-abc12345", false)
            // 同名的 manifest 与继续存在的其它文件都不该被当成禁用标记。
            File(rt2, "eratw.family.manifest").writeText("ignored")
            File(rt2, "notes.txt").writeText("ignored")

            assertEquals(
                setOf("eratw.family", "kojo-abc12345"),
                ModEnablement.disabledPackageIds(rt2),
            )
        } finally {
            rt2.deleteRecursively()
        }
    }

    @Test
    fun `按包名同时摘 erbm 与 manifest`() {
        val rt2 = tempDir("rt2-enable-filter")
        try {
            val familyModule = File(rt2, "eratw.family.erbm").also { it.writeText("m") }
            val familyManifest = File(rt2, "eratw.family.manifest").also { it.writeText("m") }
            val memoryManifest = File(rt2, "eratw.memory.manifest").also { it.writeText("m") }
            ModEnablement.setEnabled(rt2, "eratw.family", false)

            // 两侧必须一起被摘：.erbm 与 .manifest 单向配对，只摘一侧会让装配链 fail-closed 砖化。
            assertEquals(
                listOf(memoryManifest),
                ModEnablement.enabledOnly(rt2, listOf(familyModule, familyManifest, memoryManifest)),
            )
            // 没有停用标记时原样返回（不做多余分配，也不改顺序）。
            ModEnablement.setEnabled(rt2, "eratw.family", true)
            assertEquals(
                listOf(familyModule, familyManifest, memoryManifest),
                ModEnablement.enabledOnly(rt2, listOf(familyModule, familyManifest, memoryManifest)),
            )
        } finally {
            rt2.deleteRecursively()
        }
    }

    @Test
    fun `非法包名拒绝拼路径`() {
        val rt2 = tempDir("rt2-enable-guard")
        try {
            for (bad in listOf("", " ", "../evil", "a/b", "a\\b", ".hidden", "-lead")) {
                val thrown = runCatching { ModEnablement.markerFile(rt2, bad) }.exceptionOrNull()
                assertTrue("包名「$bad」应被拒绝而不是拼成路径", thrown is IllegalArgumentException)
            }
            // 合法字符集（含点、下划线、中划线）照常可用。
            assertTrue(ModEnablement.markerFile(rt2, "kojo-Ab_9-x.y").name.endsWith(".off"))
        } finally {
            rt2.deleteRecursively()
        }
    }

    @Test
    fun `rt2 目录取自 mod 链约定`() {
        val root = tempDir("rt2-enable-root")
        try {
            assertEquals(ModRuntimeAssembly.manifestDirFor(root), ModEnablement.rt2Dir(root))
            assertTrue(ModEnablement.rt2Dir(root).path.replace('\\', '/').endsWith("mods/rt2"))
        } finally {
            root.deleteRecursively()
        }
    }
}
