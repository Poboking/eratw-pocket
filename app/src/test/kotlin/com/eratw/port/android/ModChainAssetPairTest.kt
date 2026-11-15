package com.eratw.port.android

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.proc.FuncMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * RT2-ERBM：仓库**真实随包两件套**直过 `ModChainAssembly.assemble` 真链的端到端闸。
 *
 * 与 `ModChainAssemblyChainTest` 的手搓容器互补：那边锁判据形状，这边锁「随包资产字节
 * 真的能装配」——两件套（eratw.demo.erbm + eratw.demo.manifest）从 app 模块 assets 目录
 * 原样搬进临时游戏根的 mods/rt2，走与生产逐位相同的装配路径（容器解码 → 单向配对 →
 * 五步链接 → 三视图派生）。资产是坏档/真源重生没跟上的话，`DemoModuleAssetTest` 的
 * 字节闸先红；这条锁的是「字节对了也必须真的装配出一个 plan」。
 */
class ModChainAssetPairTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 仓库真实资产目录：候选按 Gradle 测试工作目录的三种落位逐个试。 */
    private fun repoAssetDir(): File {
        val candidates = listOf(
            File("src/main/assets/mods/rt2"),
            File("../app/src/main/assets/mods/rt2"),
            File("../../app/src/main/assets/mods/rt2"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("找不到仓库 rt2 资产目录（试了 ${candidates.joinToString { it.path }}）")
    }

    /** base：单函数 ENTRY，一条 _RET（函数表基线 = 1）。 */
    private fun base(): Program {
        val b = CodeBuilder()
        val entry = b.declareFunction("ENTRY", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 0)
        return b.build(entry)
    }

    private val baseMetas = listOf(FuncMeta(index = 0, key = "ENTRY"))

    @Test
    fun `仓库真实两件套直过真链_plan非空且函数表加一`() {
        val root = tmp.newFolder("game")
        val rt2 = File(root, "mods/rt2").apply { mkdirs() }
        val erbm = File(repoAssetDir(), "eratw.demo.erbm")
        val manifest = File(repoAssetDir(), "eratw.demo.manifest")
        assertTrue("随包两件套必须都在仓库 assets 里", erbm.isFile && manifest.isFile)
        erbm.copyTo(File(rt2, erbm.name))
        manifest.copyTo(File(rt2, manifest.name))

        val value = ModChainAssembly.assemble(ChainAssemblyInputs(root, true, base(), baseMetas))

        assertNotNull("随包容器在场必须真装配，不许旁路", value)
        val v = value!!
        assertFalse("demo 无覆盖声明 ⇒ hasOverrides=false", v.hasOverrides)
        assertNull("无覆盖时 functionDispatch=null（v6.2-c 直调快路径）", v.functionDispatch)
        assertEquals("函数表 = base + demo 一条", base().functions.size + 1, v.functionTable.all().size)
        assertTrue(
            "链里必须有 demo 的函数",
            v.functionTable.all().any { it.key == "ERATW_DEMO_PING" },
        )
    }

    @Test
    fun `仓库真实manifest是载体形态无容器时仍旁路`() {
        // 只放 family 载体 manifest（仓库里真实的那个）、不放任何 .erbm ⇒ 空表完全旁路。
        // 这锁住「单向配对后载体 manifest 不触发装配」的边界：旁路判据只看容器。
        val root = tmp.newFolder("game")
        val rt2 = File(root, "mods/rt2").apply { mkdirs() }
        val family = File(repoAssetDir(), "eratw.family.manifest")
        assertTrue("仓库里必须有 family 载体 manifest", family.isFile)
        family.copyTo(File(rt2, family.name))

        val value = ModChainAssembly.assemble(ChainAssemblyInputs(root, true, base(), baseMetas))

        assertNull("只有载体 manifest（无容器）必须旁路返回 null", value)
    }

    @Test
    fun `孤儿容器缺manifest整包拒`() {
        // 反向证明①的生产面：真实 .erbm 在场但没有同包名 manifest ⇒ host 闸 fail-closed。
        val root = tmp.newFolder("game")
        val rt2 = File(root, "mods/rt2").apply { mkdirs() }
        val family = File(repoAssetDir(), "eratw.family.manifest")
        family.copyTo(File(rt2, family.name))
        File(repoAssetDir(), "eratw.demo.erbm").copyTo(File(rt2, "eratw.demo.erbm"))

        try {
            ModChainAssembly.assemble(ChainAssemblyInputs(root, true, base(), baseMetas))
            fail("孤儿容器（容器有、同包名 manifest 无）必须整包拒")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "异常须点名配对不齐，实得：${e.message}",
                e.message!!.contains("配对不齐"),
            )
        }
    }
}
