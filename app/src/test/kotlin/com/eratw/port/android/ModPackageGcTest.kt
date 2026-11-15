package com.eratw.port.android

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.save.SaveDiagnostics
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RT2 MODGC 裁定 3/4/5 的定向判据：`mods/rt2/` 孤儿包 GC（半成品 / 源失联）+ provenance 形状。
 *
 * 五件事：
 * 1. 半成品（缺容器或缺 manifest，含只剩 `.sources`）在 [ModChainAssembly.assemble]
 *    **收集容器之前**清掉 ⇒ 不再触发单向配对的启动砖化；
 * 2. 源失联（`.sources` 名单一个都不在 `mods/`）整套删除；任一源在场 ⇒ 整套保留；
 * 3. `.sources` 缺失 / 空名单 ⇒ 保守不删（老包与手放件没有 provenance）；
 * 4. 安全阀：内置 `eratw.*` 与非 `kojo-` 前缀手放件永不删除；
 * 5. 关态零接触；provenance 排序与写序同源。
 */
class ModPackageGcTest {

    private fun tempRoot(): File = Files.createTempDirectory("mod-pkg-gc").toFile()

    private fun rt2(root: File): File = File(root, "mods/rt2").apply { mkdirs() }

    private fun mods(root: File): File = File(root, "mods").apply { mkdirs() }

    private fun put(dir: File, name: String, bytes: ByteArray = byteArrayOf(1, 2, 3)) {
        File(dir, name).writeBytes(bytes)
    }

    /** 最小 base：一条 ENTRY 函数即可让 assemble 的签名/链接面有合法输入。 */
    private fun base(): Program {
        val b = CodeBuilder()
        val entry = b.declareFunction("ENTRY", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 0)
        return b.build(entry)
    }

    private fun assertAllPresent(dir: File, names: List<String>) {
        for (name in names) assertTrue("$name 应保留", File(dir, name).isFile)
    }

    private fun assertAllGone(dir: File, names: List<String>) {
        for (name in names) assertFalse("$name 应被 GC", File(dir, name).exists())
    }

    // ------------------------------------------------------------------
    // 判据 1：执行点在容器收集之前 —— 半成品不再砖化启动
    // ------------------------------------------------------------------

    @Test
    fun `半成品与only-sources在收集容器前清除且装配不再整包拒`() {
        SaveDiagnostics.clear()
        val root = tempRoot()
        val dir = rt2(root)
        // 三种半成品：容器无 manifest / manifest 无容器 / 只剩 provenance。
        put(dir, "kojo-aaaaaaaa.erbm", byteArrayOf(9, 9, 9))
        put(dir, "kojo-bbbbbbbb.manifest")
        put(dir, "kojo-cccccccc.sources", "gone.ERB\n".toByteArray(Charsets.UTF_8))
        // 内置资产前缀不是 kojo-：永不删除。
        put(dir, "eratw.demo.manifest")

        val plan = ModChainAssembly.assemble(
            ChainAssemblyInputs(gameRoot = root, enabled = true, base = base(), baseMetas = emptyList()),
        )

        assertNull("半成品清空后无容器 ⇒ 完全旁路（旧行为是单向配对整包拒）", plan)
        assertAllGone(
            dir,
            listOf("kojo-aaaaaaaa.erbm", "kojo-bbbbbbbb.manifest", "kojo-cccccccc.sources"),
        )
        assertAllPresent(dir, listOf("eratw.demo.manifest"))
        assertTrue(
            "GC 逐件留痕进取证面",
            SaveDiagnostics.snapshot().any { it.contains("MOD_PKG_GC") && it.contains("kojo-aaaaaaaa.erbm") },
        )
    }

    // ------------------------------------------------------------------
    // 判据 2/3：源失联 vs 保守保留
    // ------------------------------------------------------------------

    @Test
    fun `源失联整套删除而任一源在场或无provenance保守保留`() {
        val root = tempRoot()
        val dir = rt2(root)
        val md = mods(root)

        // ① 失联：provenance 名单全不在 mods/。
        val lost = ModPackageInventory.memberNames("kojo-11111111")
        for (name in lost) put(dir, name)
        File(dir, "kojo-11111111.sources").writeText("gone1.ERB\ngone2.ERH\n", Charsets.UTF_8)

        // ② 在场：任一留档源仍在 mods/。
        val kept = ModPackageInventory.memberNames("kojo-22222222")
        for (name in kept) put(dir, name)
        File(md, "still.ERB").writeText("src", Charsets.UTF_8)
        File(dir, "kojo-22222222.sources").writeText("still.ERB\n", Charsets.UTF_8)

        // ③ 无 provenance（老包 / 手放件形状）：保守不删。
        val legacy = listOf("kojo-33333333.erbm", "kojo-33333333.manifest")
        for (name in legacy) put(dir, name)

        // ④ 空名单：同上，保守不删。
        val emptyProv = ModPackageInventory.memberNames("kojo-44444444")
        for (name in emptyProv) put(dir, name)
        File(dir, "kojo-44444444.sources").writeText("\n \n", Charsets.UTF_8)

        // ⑤ 安全阀：内置与手放件。
        put(dir, "eratw.demo.erbm")
        put(dir, "eratw.demo.manifest")
        put(dir, "handmade.erbm")
        put(dir, "handmade.manifest")

        ModPackageInventory.gcOrphans(dir, md)

        assertAllGone(dir, lost)
        assertAllPresent(dir, kept)
        assertAllPresent(dir, legacy)
        assertAllPresent(dir, emptyProv)
        assertAllPresent(
            dir,
            listOf("eratw.demo.erbm", "eratw.demo.manifest", "handmade.erbm", "handmade.manifest"),
        )
    }

    @Test
    fun `provenance 文件本身被改坏按读不出处理保守保留`() {
        val root = tempRoot()
        val dir = rt2(root)
        val names = listOf("kojo-55555555.erbm", "kojo-55555555.manifest")
        for (name in names) put(dir, name)
        // 含路径分隔符的行被丢弃；只剩这些 ⇒ 名单为空 ⇒ 保守保留。
        File(dir, "kojo-55555555.sources").writeText("../escape.ERB\nsub/dir.ERH\n", Charsets.UTF_8)

        ModPackageInventory.gcOrphans(dir, mods(root))

        assertAllPresent(dir, names + "kojo-55555555.sources")
    }

    // ------------------------------------------------------------------
    // 判据 5：关态零接触 + provenance 形状/写序
    // ------------------------------------------------------------------

    @Test
    fun `关态零接触不扫不删半成品`() {
        val root = tempRoot()
        val dir = rt2(root)
        val names = listOf("kojo-66666666.erbm", "kojo-66666666.sources")
        for (name in names) put(dir, name)
        File(dir, "kojo-66666666.sources").writeText("gone.ERB\n", Charsets.UTF_8)

        val plan = ModChainAssembly.assemble(
            ChainAssemblyInputs(gameRoot = root, enabled = false, base = base(), baseMetas = emptyList()),
        )

        assertNull("关态照旧完全旁路", plan)
        assertAllPresent(dir, names)
    }

    @Test
    fun `provenance 字节按 compareNames 排序且写序先于 manifest 与容器`() {
        val bytes = ModPackageInventory.sourcesBytes(listOf("b.ERB", "A.erh"))
        assertEquals("A.erh\nb.ERB\n", String(bytes, Charsets.UTF_8))

        val src = sourceOf("ModImportController.kt")
        val sourcesAt = src.indexOf("saveArtifact(rt2Dir, sourcesName")
        val manifestAt = src.indexOf("saveArtifact(rt2Dir, manifestName")
        val erbmAt = src.indexOf("saveArtifact(rt2Dir, erbmName")
        assertTrue(
            "写序必须是 provenance → manifest → 容器（实得 $sourcesAt/$manifestAt/$erbmAt）",
            sourcesAt in 0 until manifestAt && manifestAt in 0 until erbmAt,
        )
    }

    /** 生产源码面读取（与 `ModRuntimeAssemblyWireTest.sourceOf` 同款；CRLF 归一）。 */
    private fun sourceOf(name: String): String {
        val candidates = listOf(
            File("src/main/kotlin/com/eratw/port/android/$name"),
            File("../app/src/main/kotlin/com/eratw/port/android/$name"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 $name（${candidates.joinToString { it.path }}）")
        return file.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }
}
