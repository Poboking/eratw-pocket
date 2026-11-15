package com.eratw.port.android

import com.eratw.port.core.patch.BaseSignatureCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * RT2 G 车（P1-5）：ERB Mod 代码载体装配缝哨兵（`ModChainAssembly`）。
 *
 * 锁三件事：① 关态（`modRuntime=false`）一个字节都不读（B1 纪律延伸）；
 * ② 开态 `mods/rt2/` 出现 `.erbm` 容器而装配链未落 = fail-closed 整包拒绝（不半装、
 * 不静默丢弃）；③ 签名真目录（boot meta 尾部签名段 → `BaseSignatureCatalog.parse`
 * → `signatureHashOf`）未知键 / 缺段 / 旧档恒空串 fail-closed、命中键得稳定指纹。
 */
class ModChainAssemblyWireTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ------------------------------------------------------------------
    // ① 关态零接触 / 开态旁路
    // ------------------------------------------------------------------

    @Test
    fun `关态零接触_容器在场也不读`() {
        val root = tmp.newFolder("game")
        val rt2 = File(root, "mods/rt2").apply { mkdirs() }
        File(rt2, "sample.erbm").writeBytes(byteArrayOf(1, 2, 3))
        // 关态：即便容器在场也必须静默通过（不读盘、不抛、不半装）。
        ModChainAssembly.attach(root, enabled = false)
    }

    @Test
    fun `开态空表旁路_无容器目录静默通过`() {
        val root = tmp.newFolder("game")
        ModChainAssembly.attach(root, enabled = true)
        File(root, "mods/rt2").mkdirs()
        ModChainAssembly.attach(root, enabled = true)
    }

    @Test
    fun `开态发现容器_整包fail-closed拒绝`() {
        val root = tmp.newFolder("game")
        val rt2 = File(root, "mods/rt2").apply { mkdirs() }
        File(rt2, "sample.erbm").writeBytes(byteArrayOf(1, 2, 3))
        try {
            ModChainAssembly.attach(root, enabled = true)
            org.junit.Assert.fail("装配链未落时出现 ERB 模块容器必须整包拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "异常须点名 fail-closed 与余量归属，实得：${e.message}",
                e.message!!.contains("fail-closed") && e.message!!.contains("G 车"),
            )
        }
    }

    // ------------------------------------------------------------------
    // ② 签名真目录（判据 4 的真目录来源）
    // ------------------------------------------------------------------

    /** 规范串先经读侧 decode 自证合法，不依赖写侧格式细节（两侧一致性由既有对拍闸锁）。 */
    private val canonical = "SIG1|I,-,r,0|ret:I"

    private fun metaTextWithSection(): String =
        "end\nsignatures 1\nsignature KNOWN $canonical\n"

    @Test
    fun `签名真目录_命中键得稳定指纹_未知键空串`() {
        assertTrue(
            "测试规范串本身必须被读侧 decode 认可",
            BaseSignatureCatalog.decode(canonical) != null,
        )
        val dir = ModChainAssembly.signatureDirectoryOf(metaTextWithSection())
        val fp = dir("known") // 键大小写归一（FuncMeta.key 口径）
        assertTrue("命中键必须给出非空 SHA-256 指纹", fp.isNotEmpty())
        assertEquals("指纹必须确定（同规范串同指纹）", fp, dir("KNOWN"))
        assertEquals("未知键 fail-closed 空串", "", dir("UNKNOWN"))
    }

    @Test
    fun `签名真目录_缺段旧档恒空串_不炸不猜`() {
        val dir = ModChainAssembly.signatureDirectoryOf("end\n")
        assertEquals("旧档无签名段 = 空表 = 恒空串", "", dir("KNOWN"))
        val dirBad = ModChainAssembly.signatureDirectoryOf(
            "end\nsignatures x\nsignature KNOWN bad-canonical\n",
        )
        assertEquals("段头计数非法 = 整段空表", "", dirBad("KNOWN"))
    }
}
