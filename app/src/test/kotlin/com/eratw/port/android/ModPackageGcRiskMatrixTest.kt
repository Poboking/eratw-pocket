package com.eratw.port.android

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.mod.CompiledModule
import com.eratw.port.core.mod.ErbModuleContainer
import com.eratw.port.core.mod.ModDx3AssetMap
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.patch.ModuleDescriptor
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.save.SaveDiagnostics
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ModPackageInventory.gcOrphans` 误删风险矩阵（lane gcaudit / task-99 审计 → gcguard 修复同波）。
 *
 * 审计对象是**会删玩家盘上文件**的判据：`mods/rt2/` 的孤儿包 GC 在每次装载会话时跑一次
 * （`ModChainAssembly.assembleWithCatalog` 开态入口，先于容器收集；见
 * `ModChainAssembly.kt:234-239`），删除面 = `.sources` / `.sources.sha256` / `.manifest` /
 * `.erbm` / `.settings` / `<pkg>.assets` 整组。
 *
 * **新语义（2026-11-18 审计裁决，main 已撤销「源失联」删除判据）**：
 * - 只删**半成品**（缺 `.erbm` 或 `.manifest`）——它是启动 fail-closed 砖化的直接来源；
 * - 完整包（两件都在）**一律保留**：源改名 `.bak` / 移入子目录 / 被删 / 名字被改 / 源是目录
 *   都不再构成删除依据；源真失联时只写**一条** `SaveDiagnostics` 的 `MOD_PKG_GC` 保留取证行；
 * - 清理只走玩家的**显式卸载**（`ModManageUninstall`）。
 *
 * 本类就是这条语义的守卫：所有用例走**真落盘 + 真 `ModChainAssembly.assemble`**（生产入口，
 * GC 在装配前自动跑），断言写「应然」——任何人把删除判据加回来，这里的用例必须红。
 * 用例名里的「新语义」两条是本次修复专属：保留且只留一条取证行 / 正常包零取证行。
 */
class ModPackageGcRiskMatrixTest {

    // ------------------------------------------------------------------
    // 夹具：真 base + 真容器 + 真 provenance + 真设置/资源面
    // ------------------------------------------------------------------

    private fun tempRoot(): File = Files.createTempDirectory("mod-pkg-gc-risk").toFile()

    private fun rt2(root: File): File = File(root, "mods/rt2").apply { mkdirs() }

    private fun mods(root: File): File = File(root, "mods").apply { mkdirs() }

    /** base：单函数 ENTRY（与真装配的最小输入同形；mod 只加新函数不覆盖）。 */
    private fun base(): Program {
        val b = CodeBuilder()
        val entry = b.declareFunction("ENTRY", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 0)
        return b.build(entry)
    }

    private fun baseMetas(): List<FuncMeta> = listOf(FuncMeta(index = 0, key = "ENTRY"))

    /** 签名段侧车（无签名段的最小形状）：装配链有界尾读的上界输入。 */
    private fun writeMeta(root: File) {
        File(root, "erbc.v1/program.meta").apply { parentFile?.mkdirs() }
            .writeText("end\n", Charsets.UTF_8)
    }

    /** 生产装配入口：开态 ⇒ 先跑 gcOrphans，再收容器、真装配。 */
    private fun assemble(root: File) = ModChainAssembly.assemble(
        ChainAssemblyInputs(
            gameRoot = root,
            enabled = true,
            base = base(),
            baseMetas = baseMetas(),
        ),
    )

    /** mod 包内容：新增函数（零基线容器，无覆盖声明 ⇒ 不碰签名闸）。 */
    private fun moduleProgram(functionName: String): Program {
        val b = CodeBuilder()
        val entry = b.declareFunction(functionName, b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 0)
        return b.build(entry)
    }

    /**
     * 写一个「健康、可装配、玩家在用」的 kojo 包：
     * 四件套（provenance → 指纹 → manifest → 容器，与导入写序同源）+ `mods/` 留档源
     * + `.settings` + `.assets/` + 覆盖侧车 + **代码身份侧车**（真实格式，建模带 code.mod 的完整包）。
     * 返回 packageId。
     */
    private fun writeLivePackage(
        root: File,
        packageId: String,
        sourceNames: List<String> = listOf(SOURCE_A),
        sourceTexts: List<String> = listOf(SOURCE_TEXT),
        functionName: String = "B_GREET",
    ): String {
        val dir = rt2(root)
        val md = mods(root)
        File(dir, packageId + ModPackageInventory.MANIFEST_EXTENSION).writeBytes(
            ManifestCodec.encode(ModuleDescriptor(packageId = packageId)),
        )
        val cm = CompiledModule(
            packageId,
            moduleProgram(functionName),
            listOf(FuncMeta(index = 0, key = functionName)),
            emptyMap(),
        )
        File(dir, packageId + ModPackageInventory.MODULE_EXTENSION)
            .writeBytes(ErbModuleContainer.encode(cm, allowSuperCalls = true))
        File(dir, packageId + ModPackageInventory.SOURCES_EXTENSION)
            .writeBytes(ModPackageInventory.sourcesBytes(sourceNames))
        val picked = sourceNames.mapIndexed { i, name ->
            PickedModFile(name, sourceTexts[i].toByteArray(Charsets.UTF_8))
        }
        File(dir, packageId + ModPackageInventory.FINGERPRINT_EXTENSION)
            .writeBytes(ModSourceFingerprint.bytesFor(picked))
        for (i in sourceNames.indices) {
            File(md, sourceNames[i]).writeBytes(sourceTexts[i].toByteArray(Charsets.UTF_8))
        }
        assertTrue(
            "夹具前置：设置应写入成功",
            ModSettingsStore.write(dir, packageId, mapOf("volume" to "80")),
        )
        File(dir, packageId + ModPackageInventory.ASSET_DIR_SUFFIX).apply { mkdirs() }
            .resolve("face.png").writeBytes(byteArrayOf(1, 2, 3))
        // ERA-A3/ERA-W2：无论有无 overrides 都落空表侧车（导入链 ⑤⁗ fail-closed 覆盖旧表）。
        File(dir, packageId + ModDx3AssetMap.SIDECAR_EXTENSION)
            .writeBytes(ModDx3AssetMap.encode(packageId, emptyList()).toByteArray(Charsets.UTF_8))
        // 代码身份侧车（第 7 件成员）：只有带 code.mod 的包才写，磁盘口径是 kojo 目录名，
        // 内容首行 `RT2CODEID/1` + 第二行运行期身份 —— 这里手写真实格式，GC 必须整组保留。
        ModCodeIdentity.write(dir, packageId, CODE_RUNTIME_ID)
        return packageId
    }

    /** 完整包全部成员（四件套 + 覆盖侧车 + 代码身份侧车 + 设置 + 资源面）一个都不少。 */
    private fun assertPackageIntact(root: File, packageId: String) {
        for (name in ModPackageInventory.memberNames(packageId)) {
            assertTrue("$name 应保留（完整包一律保留）", File(rt2(root), name).isFile)
        }
        assertTrue(
            "设置应保留（不再连带删）",
            ModSettingsStore.settingsFile(rt2(root), packageId).isFile,
        )
        assertTrue(
            "资源面应保留（不再连带删）",
            File(rt2(root), packageId + ModPackageInventory.ASSET_DIR_SUFFIX)
                .resolve("face.png").isFile,
        )
    }

    /** 源真失联：必须留且只留一条「完整包保留」取证行，点名包并写明不构成删除依据。 */
    private fun assertKeptTrace(packageId: String) {
        val notes = SaveDiagnostics.snapshot()
        val kept = notes.filter { it.contains("MOD_PKG_GC") && it.contains("完整包保留") }
        assertEquals("源失联只写一条保留取证行（不刷屏）：$notes", 1, kept.size)
        assertTrue(
            "取证行必须点名包并写明理由：${kept.single()}",
            kept.single().contains(packageId) &&
                kept.single().contains("源失联不构成删除依据"),
        )
        assertFalse(
            "不得出现任何删除留痕：$notes",
            notes.any { it.contains("孤儿包成员已删除") },
        )
    }

    /** 源都在的正常包：一条 MOD_PKG_GC 行都不许有（防取证面每启动每包一条噪声）。 */
    private fun assertNoGcTrace() {
        val notes = SaveDiagnostics.snapshot()
        assertTrue("正常包不得留 MOD_PKG_GC 行：$notes", notes.none { it.contains("MOD_PKG_GC") })
    }

    // ------------------------------------------------------------------
    // 风险 1：源改名 / 移子目录 / 被删，但包本身仍可装配（审计时最危险，现已保留）
    // ------------------------------------------------------------------

    @Test
    fun `源仅改名备份仍整套保留——完整包不因源失联被删`() {
        SaveDiagnostics.clear()
        val root = tempRoot()
        try {
            writeMeta(root)
            val pkg = writeLivePackage(root, PKG_A)

            val before = assemble(root)
            assertNotNull("前置：包本身可装配（mod 正在用）", before)
            assertTrue(
                "前置：链接产物里有 mod 新函数",
                before!!.functionTable.all().any { it.key == "B_GREET" },
            )

            // 玩家只是把留档源改名备份（.bak），内容与包都原封不动。
            val src = File(mods(root), SOURCE_A)
            val renamed = File(mods(root), SOURCE_A + ".bak")
            assertTrue("前置：改名成功", src.renameTo(renamed))

            val after = assemble(root)
            assertNotNull("完整包一律保留：源失联不是删除依据", after)
            assertTrue(
                "mod 新函数仍可达（装配链未受影响）",
                after!!.functionTable.all().any { it.key == "B_GREET" },
            )
            assertPackageIntact(root, pkg)
            assertTrue("改名后的源仍在盘上（玩家没删它）", renamed.isFile)
            assertKeptTrace(pkg)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `源移入 mods 子目录仍整套保留——撤销「只查一层」的删除判据`() {
        SaveDiagnostics.clear()
        val root = tempRoot()
        try {
            writeMeta(root)
            val pkg = writeLivePackage(root, PKG_A)
            assertNotNull("前置：包可装配", assemble(root))

            // 玩家把源收进自建子目录整理（mods/backup/），文件仍在。
            val src = File(mods(root), SOURCE_A)
            val sub = File(mods(root), "backup").apply { mkdirs() }
            val moved = File(sub, SOURCE_A)
            assertTrue("前置：移动成功", src.renameTo(moved))

            val after = assemble(root)
            assertNotNull("子目录里的源也是「源仍在盘上」：完整包一律保留", after)
            assertPackageIntact(root, pkg)
            assertTrue("源文件本身仍在盘上", moved.isFile)
            assertKeptTrace(pkg)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `源被删后仍整套保留并留取证行——清理只走显式卸载`() {
        SaveDiagnostics.clear()
        val root = tempRoot()
        try {
            writeMeta(root)
            val pkg = writeLivePackage(root, PKG_A)
            assertNotNull("前置：包可装配", assemble(root))

            assertTrue("前置：玩家删掉留档源", File(mods(root), SOURCE_A).delete())

            val after = assemble(root)
            assertNotNull("删源 ≠ 卸载：完整包保留（删除意图只从显式卸载来）", after)
            assertPackageIntact(root, pkg)
            assertKeptTrace(pkg)
            // 取证行仍落在存档取证面（[eratw][save]）：玩家删除当时零屏上提示。
            assertTrue(
                "保留取证行仍走存档取证通道",
                SaveDiagnostics.snapshot().any {
                    it.contains("MOD_PKG_GC") && it.startsWith("[eratw][save]")
                },
            )

            // 显式卸载才是唯一删除意图：仍能删净（含设置与资源面）。
            assertTrue("显式卸载应删净", ModManageUninstall.uninstall(rt2(root), pkg))
            for (name in ModPackageInventory.memberNames(pkg)) {
                assertFalse("显式卸载后 $name 应删净", File(rt2(root), name).exists())
            }
            assertFalse(
                "显式卸载后资源目录应删净",
                File(rt2(root), pkg + ModPackageInventory.ASSET_DIR_SUFFIX).exists(),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // 风险 2：只判名字存在，不判内容/指纹（保留方向正确，粒度仍是粗）
    // ------------------------------------------------------------------

    @Test
    fun `源同名但内容被改仍整套保留——GC 不读内容指纹`() {
        SaveDiagnostics.clear()
        val root = tempRoot()
        try {
            writeMeta(root)
            val pkg = writeLivePackage(root, PKG_A)
            val sidecar = File(rt2(root), pkg + ModPackageInventory.FINGERPRINT_EXTENSION)
            val oldSidecar = sidecar.readText(Charsets.UTF_8)

            // 玩家/编辑器改写源内容（名字不变）：指纹侧车已不匹配，但 isFile 仍真。
            val changedText = "$SOURCE_TEXT\n; player edited\n"
            File(mods(root), SOURCE_A).writeText(changedText, Charsets.UTF_8)
            val newSidecar = String(
                ModSourceFingerprint.bytesFor(
                    listOf(PickedModFile(SOURCE_A, changedText.toByteArray(Charsets.UTF_8))),
                ),
                Charsets.UTF_8,
            )
            assertFalse("前置：内容确实变了（指纹不一致）", oldSidecar == newSidecar)

            assertNotNull("名字仍在 ⇒ 包保留（重编器负责按指纹重编）", assemble(root))
            assertPackageIntact(root, pkg)
            assertNoGcTrace()
        } finally {
            root.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // 风险 3：多源 any 语义 + 同名源被另一包占用（两种方向都保留）
    // ------------------------------------------------------------------

    @Test
    fun `多源包任一源在场或全失联都整套保留——删除依据已撤销`() {
        SaveDiagnostics.clear()
        val root = tempRoot()
        try {
            writeMeta(root)
            val pkg = writeLivePackage(
                root,
                PKG_A,
                sourceNames = listOf(SOURCE_A, SOURCE_B),
                sourceTexts = listOf(SOURCE_TEXT, SOURCE_TEXT),
            )

            assertTrue("前置：删掉其中一个源", File(mods(root), SOURCE_A).delete())
            assertNotNull("任一源在场 ⇒ 整套保留", assemble(root))
            assertPackageIntact(root, pkg)
            assertNoGcTrace()

            assertTrue("再删最后一个源", File(mods(root), SOURCE_B).delete())
            assertNotNull("全部失联 ⇒ 也整套保留（不再是删除依据）", assemble(root))
            assertPackageIntact(root, pkg)
            assertKeptTrace(pkg)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `同名源被另一包占用也判在场——只看文件名不看内容归属（过保守但不删）`() {
        SaveDiagnostics.clear()
        val root = tempRoot()
        try {
            writeMeta(root)
            // A 的源只有 shared.ERB；B 的源是 shared.ERB + b.ERB（B 后导，覆盖写了同名文件）。
            val pkgA = writeLivePackage(
                root,
                PKG_A,
                sourceNames = listOf(SHARED),
                sourceTexts = listOf(SOURCE_TEXT),
                functionName = "B_GREET_A",
            )
            val pkgB = writeLivePackage(
                root,
                PKG_B,
                sourceNames = listOf(SHARED, SOURCE_B),
                sourceTexts = listOf(SOURCE_TEXT, SOURCE_TEXT),
                functionName = "B_GREET_B",
            )
            assertTrue(
                "前置：B 的独有源被删（B 仍靠 shared.ERB 在场保留）",
                File(mods(root), SOURCE_B).delete(),
            )

            assertNotNull("真装配：两个包都保留", assemble(root))
            assertPackageIntact(root, pkgA)
            assertPackageIntact(root, pkgB)
            // A 的真实源内容早已被 B 的同名文件覆盖，判据仍判 A 健康 ⇒
            // 不会误删，但重编 A 时会把 B 的内容当 A 的源（错误方向在上层，不在 GC）。
            assertTrue(
                "方向确认：GC 不会因同名源易主而误删 A",
                File(rt2(root), pkgA + ModPackageInventory.MODULE_EXTENSION).isFile,
            )
            assertNoGcTrace()
        } finally {
            root.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // 风险 4：provenance 坏行 / 空名单 / 内置名的丢弃口径
    // ------------------------------------------------------------------

    @Test
    fun `provenance 空名单或全路径分隔符行整体按读不出处理——保留且零取证行`() {
        SaveDiagnostics.clear()
        val root = tempRoot()
        try {
            writeMeta(root)
            val empty = writeLivePackage(root, PKG_A, functionName = "B_GREET_A")
            File(rt2(root), empty + ModPackageInventory.SOURCES_EXTENSION)
                .writeText("\n  \n", Charsets.UTF_8)
            val sepOnly = writeLivePackage(root, PKG_B, functionName = "B_GREET_B")
            File(rt2(root), sepOnly + ModPackageInventory.SOURCES_EXTENSION)
                .writeText("../escape.ERB\nsub/dir.ERH\n", Charsets.UTF_8)

            assertNotNull("保守：两份包都该保留并继续装配", assemble(root))
            assertPackageIntact(root, empty)
            assertPackageIntact(root, sepOnly)
            // 读不出 = 无法证明失联 ⇒ 连取证行也不写（避免误报噪声）。
            assertNoGcTrace()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `provenance 部分坏行被丢弃后剩余行失联——整套保留但坏行口径仍是缝`() {
        SaveDiagnostics.clear()
        val root = tempRoot()
        try {
            writeMeta(root)
            val pkg = writeLivePackage(root, PKG_A)
            // 一坏行（带分隔符，被 filter 丢掉）+ 一真行（不在盘上）⇒ 名单非空且全失联。
            File(rt2(root), pkg + ModPackageInventory.SOURCES_EXTENSION)
                .writeText("gone.ERB\nsub/dir.ERH\n", Charsets.UTF_8)

            assertNotNull("坏行+失联行也整套保留（删除路径已撤销）", assemble(root))
            assertPackageIntact(root, pkg)
            assertKeptTrace(pkg)
            // 坏行仍被静默丢弃：取证行只说 1 个留档源（应然=整份不可信；已不构成删除风险）。
            val notes = SaveDiagnostics.snapshot()
            assertTrue(
                "坏行被丢弃的口径仍可见于取证行：$notes",
                notes.any { it.contains("MOD_PKG_GC") && it.contains("源 1 个留档源") },
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `provenance 列出内置名 eratw 也不被丢弃——保留且留取证行（源名侧仍无白名单）`() {
        SaveDiagnostics.clear()
        val root = tempRoot()
        try {
            writeMeta(root)
            val pkg = writeLivePackage(root, PKG_A)
            File(rt2(root), pkg + ModPackageInventory.SOURCES_EXTENSION)
                .writeText("eratw.demo.ERB\n", Charsets.UTF_8)
            // 内置面同前缀 manifest 必保留（包名侧安全阀仍生效）；载体形态无容器 ⇒ 装配旁路。
            File(rt2(root), "eratw.demo.manifest").writeBytes(
                ManifestCodec.encode(ModuleDescriptor(packageId = "eratw.demo")),
            )

            assertNotNull("源名侧没有白名单，但已不构成删除依据：整套保留", assemble(root))
            assertPackageIntact(root, pkg)
            assertTrue("eratw.* 包名安全阀仍保留内置件", File(rt2(root), "eratw.demo.manifest").isFile)
            assertKeptTrace(pkg)
        } finally {
            root.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // 风险 5：源在 mods/ 但为目录（isFile 语义，已不构成删除依据）
    // ------------------------------------------------------------------

    @Test
    fun `源名在 mods 但为目录仍整套保留——形状异常不再触发删除`() {
        SaveDiagnostics.clear()
        val root = tempRoot()
        try {
            writeMeta(root)
            val pkg = writeLivePackage(root, PKG_A)
            val src = File(mods(root), SOURCE_A)
            assertTrue("前置：源文件改名为同名目录（模拟盘上损坏/异常同步）", src.delete())
            val asDir = File(mods(root), SOURCE_A).apply { mkdirs() }
            assertTrue("前置：目录已建", asDir.isDirectory)

            assertNotNull("形状异常也视为「源面不可判」：整套保留", assemble(root))
            assertPackageIntact(root, pkg)
            assertKeptTrace(pkg)
        } finally {
            root.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // 新语义专属：保留 + 取证行，一个不少 / 一条不多
    // ------------------------------------------------------------------

    @Test
    fun `新语义—完整包源全失联保留全部成员且只留一条保留取证行`() {
        SaveDiagnostics.clear()
        val root = tempRoot()
        try {
            writeMeta(root)
            val pkg = writeLivePackage(root, PKG_A)
            assertTrue("前置：删光留档源（真失联）", File(mods(root), SOURCE_A).delete())

            val plan = assemble(root)
            assertNotNull("完整包必须保留（装配不旁路）", plan)
            // 一个成员都不少：四件套 + 设置 + 资源面。
            assertPackageIntact(root, pkg)
            assertFalse("留档源确实不在了（真失联）", File(mods(root), SOURCE_A).exists())

            val notes = SaveDiagnostics.snapshot()
            val kept = notes.filter { it.contains("MOD_PKG_GC") && it.contains("完整包保留") }
            assertEquals("失联只写一条保留取证行（每次启动每包一条，不刷屏）：$notes", 1, kept.size)
            assertTrue(
                "取证行点名包与理由：${kept.single()}",
                kept.single().contains(pkg) && kept.single().contains("源失联不构成删除依据"),
            )
            assertFalse(
                "不得再有任何删除留痕：$notes",
                notes.any { it.contains("孤儿包成员已删除") },
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `新语义—源都在的完整包不留任何 MOD_PKG_GC 行`() {
        SaveDiagnostics.clear()
        val root = tempRoot()
        try {
            writeMeta(root)
            val pkg = writeLivePackage(root, PKG_A)

            assertNotNull("前置：正常装配", assemble(root))
            assertPackageIntact(root, pkg)
            assertNoGcTrace()
        } finally {
            root.deleteRecursively()
        }
    }

    private companion object {
        private const val PKG_A = "kojo-aaaa1111"
        private const val PKG_B = "kojo-bbbb2222"
        private const val SOURCE_A = "KOJO_RISK_A.ERB"
        private const val SOURCE_B = "KOJO_RISK_B.ERH"
        private const val SHARED = "KOJO_RISK_SHARED.ERB"
        private const val SOURCE_TEXT = "@B_GREET\nRETURN 0\n"

        /** 代码身份侧车的运行期身份（作者声明 id 口径，独立于磁盘 kojo 目录名）。 */
        private const val CODE_RUNTIME_ID = "kojo.codeid.fixture"
    }
}
