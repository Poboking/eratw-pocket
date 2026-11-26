package com.eratw.port.android

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.mod.CompiledModule
import com.eratw.port.core.mod.ErbModuleContainer
import com.eratw.port.core.mod.ModChainRuntimePlan
import com.eratw.port.core.patch.BaseSignatureCatalog
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.patch.ModuleDescriptor
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.ModContext
import com.eratw.port.modfamily.FamilyDeclarations
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 「玩家在设置页停用某个 mod」的**真装配**垂直切片（task-94 / Lane CW）。
 *
 * 既有覆盖只到零件：`ModEnablementTest` 锁标记读写，`ModCarrierRegistry` 的过滤函数
 * 由自身单测锁；没有任何一条用例从盘上标记一路走到「这个包真的没被装载」。
 * 本文件补的是这条端到端链，链路里每一环都走生产入口，不手搓装配：
 *
 * 1. 标记判定 = `ModEnablement.isDisabled` / `disabledPackageIds`
 *    （`ModEnablement.kt:43`、`:69`）；
 * 2. 载体侧过滤 = `ModCarrierRegistry.providers`（`ModCarrierRegistry.kt:46-54`）
 *    —— 生产调用点 `GameHost.kt:1355`；
 * 3. 装配侧过滤 = `ModRuntimeAssembly.manifestFilesIn`（`ModRuntimeAssembly.kt:787-795`）
 *    与 `ModChainAssembly.collectErbModules`（`ModChainAssembly.kt:459-469`）；
 * 4. 装配裁决 = `ModRuntimeAssembly.attach` 返回的 `ModRuntimeSession.host`
 *    （`ModRuntimeHost.contextOf`、`installedContexts`）与 `logSink` 上的
 *    `mod.install pkg=… active=…` 真格式行；
 * 5. 设置页读数 = `ModManageScanner.scan`（`ModManageModel.kt:107` 的 enabled 口径）
 *    与 `ModManageDiagnostics.record` / `snapshot`（`ModManageDiagnostics.kt:68-99`）。
 *
 * 判据（对应 task-94 目标 1-5）：
 * - 标记在场的包：不进 carriers / 不进 snapshot / 不建 context / 生命周期回调零调用；
 * - 删标记：下一次装配把它装回来（设置页同一个按钮的打开方向）；
 * - 停用期间 `<pkg>.assets/`、`.settings`、`.sources.sha256`、`.manifest`、`.erbm`
 *   逐字节保留（防「停用即删除」的实现回归）；
 * - 停用包不被设置页 / 诊断面误判为「装载失败」（只有真缺 manifest 的泄漏才报失败）；
 * - 反向证明：`ModCarrierRegistry.providers` 的过滤一被拆掉，本文件立刻红。
 */
class ModEnablementAssemblyEndToEndTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val logs: MutableList<String> = mutableListOf()

    // ------------------------------------------------------------------
    // 夹具：临时游戏根（生产形状 = 全部内置 manifest 都在盘上）
    // ------------------------------------------------------------------

    private fun tempGameRoot(): Path {
        val root = Files.createTempDirectory("mod-toggle-assembly")
        Files.createDirectories(root.resolve("mods").resolve("rt2"))
        return root
    }

    private fun rt2Dir(root: Path): File = ModEnablement.rt2Dir(root.toFile())

    /** 无停用标记的游戏根（目录不存在 ⇒ `disabledPackageIds` 空集 ⇒ providers 走全量分支）。 */
    private fun unmarkedRoot(): File =
        File(System.getProperty("java.io.tmpdir"), "mod-toggle-nomarkers-" + System.nanoTime())

    /** 生产载体的 packageId 名单（`ModRegistry.carriers` 的公开读面）。 */
    private fun carrierPackageIds(): List<String> =
        ModCarrierRegistry.providers(unmarkedRoot()).map { it.first }

    /** 生产形状游戏根：`mods/rt2/` 下投递全部注册载体的内置 manifest（同 `GameHost` 装机形状）。 */
    private fun productionRoot(): Path {
        val root = tempGameRoot()
        val rt2 = rt2Dir(root)
        for (packageId in carrierPackageIds()) {
            val manifest = File("src/main/assets/mods/rt2/$packageId.manifest")
            assertTrue("内置 RT2 manifest 缺席（先跑 generateBuiltinRt2Manifest）：${manifest.path}", manifest.isFile)
            Files.copy(manifest.toPath(), rt2.toPath().resolve(manifest.name))
        }
        return root
    }

    private fun writeDeclarationManifest(root: Path, fileName: String, packageId: String) {
        val bytes = ManifestCodec.encode(ModuleDescriptor(packageId = packageId))
        Files.write(rt2Dir(root).toPath().resolve(fileName), bytes)
    }

    /** 走真入口停用（与设置页按钮同一入口：`ModEnablement.setEnabled(..., enabled = false)`）。 */
    private fun disable(root: Path, packageId: String) {
        assertTrue("停用标记必须写成功：$packageId", ModEnablement.setEnabled(rt2Dir(root), packageId, false))
        assertTrue(ModEnablement.isDisabled(rt2Dir(root), packageId))
    }

    /** 走真入口重新启用（删标记）。 */
    private fun enable(root: Path, packageId: String) {
        assertTrue("删除停用标记必须成功：$packageId", ModEnablement.setEnabled(rt2Dir(root), packageId, true))
        assertFalse(ModEnablement.isDisabled(rt2Dir(root), packageId))
    }

    /** 生产装配入口：直打 [ModRuntimeAssembly.attach]（缺省 newHost = 宿主编排）。 */
    private fun attach(root: Path, store: VariableStore, providers: List<Pair<String, EraMod>>): ModRuntimeSession =
        checkNotNull(
            ModRuntimeAssembly.attach(
                gameRoot = root.toFile(),
                store = store,
                nativeRegistry = NativeMethodRegistry(),
                onVmThread = { true },
                enabled = true,
                logSink = { line -> logs += line },
                modProviders = providers,
            ),
        ) { "开态生产装配必须产出会话；日志=$logs" }

    private fun installLines(): List<String> = logs.filter { it.startsWith(INSTALL_PREFIX) }

    private fun assertNoInstallLine(packageId: String) {
        assertTrue(
            "停用包不得出现在任何装载上报行里（连失败行都不许有）；日志=${installLines()}",
            logs.none { it.startsWith("$INSTALL_PREFIX$packageId ") },
        )
    }

    /** 生命周期计数载体：四个回调各记一次，用来证明「停用包一次都没被回调」。 */
    private class CountingMod : EraMod {
        var loads: Int = 0
        var enables: Int = 0
        var disables: Int = 0
        var unloads: Int = 0
        val loadedPackageIds: MutableList<String> = mutableListOf()

        override fun onLoad(context: ModContext) {
            loads++
            loadedPackageIds += context.packageId
        }

        override fun onEnable() {
            enables++
        }

        override fun onDisable() {
            disables++
        }

        override fun onUnload() {
            unloads++
        }
    }

    // ------------------------------------------------------------------
    // 目标 1 / 2：生产载体注册表 + 真装配（marker 在 ⇒ 不装载；删 marker ⇒ 装载）
    // ------------------------------------------------------------------

    @Test
    fun `生产注册表真装配_停用标记让该包整个不进providers与宿主entries`() {
        val family = FamilyDeclarations.PACKAGE_ID
        val allPackages = carrierPackageIds()
        assertTrue("生产注册表必须包含 family 载体，实际=$allPackages", family in allPackages)
        val root = productionRoot()
        disable(root, family)

        // 链路证据 ①：盘上 marker ⇒ ModCarrierRegistry.providers 少且只少这一个包。
        val providers = ModCarrierRegistry.providers(root.toFile())
        assertEquals(
            "停用一个包只应摘掉它自己，其余载体原样在场",
            allPackages.toSet() - family,
            providers.map { it.first }.toSet(),
        )
        assertTrue("停用包不得出现在 providers 里", providers.none { it.first == family })

        // 链路证据 ②：真装配后宿主 entries 里没有该包，且日志里连失败行都没有。
        val session = attach(root, VariableStore(), providers)
        try {
            assertNull("停用包不得建 context（= 不进 providers / 不建 entry）", session.host.contextOf(family))
            assertTrue(
                "installedContexts 不得含停用包",
                session.host.installedContexts.none { it.packageId == family },
            )
            assertNoInstallLine(family)
            assertEquals("每个在场载体恰好一条装载上报", providers.size, installLines().size)
            assertTrue(
                "其余载体必须真装成功；日志=${installLines()}",
                installLines().all { it.endsWith(" active=true") },
            )
        } finally {
            session.shutdown()
        }
    }

    @Test
    fun `删掉停用标记后下一次装配把该包装回来`() {
        val family = FamilyDeclarations.PACKAGE_ID
        val root = productionRoot()
        disable(root, family)

        val disabledSession = attach(root, VariableStore(), ModCarrierRegistry.providers(root.toFile()))
        try {
            assertNull("第一次装配（marker 在场）：不建 context", disabledSession.host.contextOf(family))
            assertNoInstallLine(family)
        } finally {
            disabledSession.shutdown()
        }

        // 设置页「打开」按钮的生产入口：删标记。
        enable(root, family)
        logs.clear()
        val enabledSession = attach(root, VariableStore(), ModCarrierRegistry.providers(root.toFile()))
        try {
            assertNotNull(
                "删标记后的下一次装配必须把包装回来（marker 是唯一判据）",
                enabledSession.host.contextOf(family),
            )
            assertTrue(
                "装配日志必须出现该包的 active=true 真格式行；日志=${installLines()}",
                logs.any { it == "$INSTALL_PREFIX$family active=true" },
            )
        } finally {
            enabledSession.shutdown()
        }
    }

    // ------------------------------------------------------------------
    // 目标 4：设置页读数 / 诊断上屏不得把「停用」误判成「装载失败」
    // ------------------------------------------------------------------

    @Test
    fun `设置页扫描把停用包标为enabled_false且不落进skipped`() {
        val family = FamilyDeclarations.PACKAGE_ID
        val root = productionRoot()
        disable(root, family)

        val scan = ModManageScanner.scan(root.toFile())
        assertTrue("停用包的 manifest 必须在盘上、必须被列出来", scan.mods.any { it.packageId == family })
        val entry = checkNotNull(scan.mods.firstOrNull { it.packageId == family })
        assertFalse("设置页读数口径：marker 在场 = enabled=false", entry.enabled)
        assertTrue("停用不是「读不出来的坏 manifest」：skipped 必须为空，实际=${scan.skipped}", scan.skipped.isEmpty())
        assertFalse("内置载体不得被误读成「缺模块」半成品", entry.missingModule)
        assertEquals("停用只是灰一行，不减少列表条数", carrierPackageIds().size, scan.mods.size)

        // 诊断面：把这次真装配的整条日志线喂进去，停用包不得被记成失败。
        val session = attach(root, VariableStore(), ModCarrierRegistry.providers(root.toFile()))
        try {
            ModManageDiagnostics.reset()
            logs.forEach { ModManageDiagnostics.record(it) }
            val snapshot = ModManageDiagnostics.snapshot()
            assertTrue(
                "停用包不得出现在「装载失败」诊断快照里；实际=$snapshot",
                snapshot.isEmpty(),
            )
        } finally {
            session.shutdown()
        }
    }

    @Test
    fun `正向对照_真缺manifest的载体才会被诊断面记为装载失败`() {
        val livePkg = "mod.controls.live"
        val ghostPkg = "mod.controls.ghost"
        val root = tempGameRoot()
        writeDeclarationManifest(root, "$livePkg.manifest", livePkg)

        val live = CountingMod()
        val ghost = CountingMod()
        val session = attach(root, VariableStore(), listOf(livePkg to live, ghostPkg to ghost))
        try {
            assertTrue(
                "正常载体必须真装成功；日志=${installLines()}",
                logs.any { it == "$INSTALL_PREFIX$livePkg active=true" },
            )
            assertNull("缺失 manifest 的泄漏载体不得建 context（fail-closed）", session.host.contextOf(ghostPkg))
            assertTrue(
                "泄漏载体必须走 fail-closed：日志里要有一条 active=false 真格式行；日志=${installLines()}",
                logs.any { it.startsWith("$INSTALL_PREFIX$ghostPkg active=false failure=") },
            )
            assertEquals("泄漏载体不进生命周期", 0, ghost.loads)
            assertEquals(0, ghost.enables)

            ModManageDiagnostics.reset()
            logs.forEach { ModManageDiagnostics.record(it) }
            val snapshot = ModManageDiagnostics.snapshot()
            assertTrue(
                "诊断面必须真读到失败（证明上一例的「空快照」不是空断言）；实际=$snapshot",
                snapshot.containsKey(ghostPkg),
            )
            assertFalse("正常包不得被记失败", snapshot.containsKey(livePkg))
        } finally {
            session.shutdown()
        }
    }

    // ------------------------------------------------------------------
    // 目标 1 的生命周期面：停用包四个回调零调用，正常包各一次
    // ------------------------------------------------------------------

    @Test
    fun `停用包生命周期回调零调用_正常包各一次_跨页边界与收口`() {
        val livePkg = "mod.live"
        val offPkg = "mod.off"
        val root = tempGameRoot()
        writeDeclarationManifest(root, "$livePkg.manifest", livePkg)
        writeDeclarationManifest(root, "$offPkg.manifest", offPkg)
        disable(root, offPkg)

        val live = CountingMod()
        val off = CountingMod()
        // 刻意把停用包的 provider 也交进来：证明「停止」的裁决在装配面，
        // 而不是依赖 provider 名单"恰好"过滤掉（生产 GameHost 两层都在，见 ModCarrierRegistry）。
        val session = attach(root, VariableStore(), listOf(livePkg to live, offPkg to off))
        try {
            assertEquals("正常包 onLoad 恰好一次", 1, live.loads)
            assertEquals("正常包 onEnable 恰好一次", 1, live.enables)
            assertEquals("正常包拿到的 context 归属自己", listOf(livePkg), live.loadedPackageIds)
            assertEquals("停用包 onLoad 不得被调用", 0, off.loads)
            assertEquals("停用包 onEnable 不得被调用", 0, off.enables)
            assertTrue("停用包不得拿到 context", off.loadedPackageIds.isEmpty())
            assertNull("停用包不得建 context", session.host.contextOf(offPkg))
            // 罚站口径：provider 泄漏时装配不是静默忽略，而是 fail-closed 上报（设置页才能解释）。
            assertTrue(
                "泄漏的停用 provider 必须 fail-closed 上报，不得静默吞掉；日志=${installLines()}",
                logs.any { it.startsWith("$INSTALL_PREFIX$offPkg active=false failure=") },
            )
            assertTrue(
                "装配失败原因必须点名声明快照不一致（构造段 fail-closed）；日志=$logs",
                logs.any { it.startsWith("[$offPkg][ERROR] 装配失败（构造段）") },
            )

            session.onTurnBoundary()
            assertEquals("页边界不得给停用包补回调", 0, off.loads)
        } finally {
            session.shutdown()
        }
        assertEquals("收口时停用包 onDisable 不得被调用", 0, off.disables)
        assertEquals("收口时停用包 onUnload 不得被调用", 0, off.unloads)
        assertEquals("正常包收口 onDisable 一次", 1, live.disables)
        assertEquals("正常包收口 onUnload 一次", 1, live.unloads)
    }

    // ------------------------------------------------------------------
    // 目标 1 的容器侧（ModChainAssembly）：停用包的坏 .erbm 不砖化启动
    // ------------------------------------------------------------------

    private val canonical: String = "SIG1|I,-,r,0|ret:I"
    private val fingerprint: String by lazy { BaseSignatureCatalog.fingerprint(canonical) }

    private fun base(): Program {
        val b = CodeBuilder()
        val entry = b.declareFunction("ENTRY", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 0)
        b.declareFunction("TARGET", b.here(), arity = 1, slotCount = 1)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 0)
        return b.build(entry)
    }

    private val baseMetas = listOf(
        FuncMeta(index = 0, key = "ENTRY"),
        FuncMeta(index = 1, key = "TARGET", argIntCount = 1),
    )

    private fun moduleProgram(): Program {
        val b = CodeBuilder()
        val entry = b.declareFunction("TARGET", b.here(), arity = 1, slotCount = 1)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 0)
        return b.build(entry)
    }

    private val moduleMetas = listOf(FuncMeta(index = 0, key = "TARGET", argIntCount = 1))

    private fun writeModuleManifest(root: Path, fileName: String, packageId: String) {
        val descriptor = ModuleDescriptor(packageId = packageId, overrideFunctions = setOf("TARGET"))
        Files.write(rt2Dir(root).toPath().resolve(fileName), ManifestCodec.encode(descriptor))
    }

    private fun writeModuleContainer(root: Path, fileName: String, packageId: String, garbage: Boolean) {
        val bytes = if (garbage) {
            byteArrayOf(1, 2, 3, 4, 5)
        } else {
            val cm = CompiledModule(
                packageId,
                moduleProgram(),
                moduleMetas,
                mapOf("TARGET" to fingerprint),
            )
            ErbModuleContainer.encode(cm, allowSuperCalls = true)
        }
        Files.write(rt2Dir(root).toPath().resolve(fileName), bytes)
    }

    private fun writeProgramMeta(root: Path) {
        val meta = root.resolve("erbc.v1").resolve("program.meta")
        Files.createDirectories(meta.parent)
        Files.write(meta, "end\nsignatures 1\nsignature TARGET $canonical\n".toByteArray())
    }

    private fun assemble(root: Path): ModChainRuntimePlan? = ModChainAssembly.assemble(
        ChainAssemblyInputs(
            gameRoot = root.toFile(),
            enabled = true,
            base = base(),
            baseMetas = baseMetas,
        ),
    )

    @Test
    fun `装配链容器侧_停用包的坏容器被标记过滤_整链照常装配`() {
        val root = tempGameRoot()
        val livePkg = "mod.chain.live"
        val offPkg = "mod.chain.off"
        writeModuleManifest(root, "$livePkg.manifest", livePkg)
        writeModuleContainer(root, "$livePkg.erbm", livePkg, garbage = false)
        writeModuleManifest(root, "$offPkg.manifest", offPkg)
        writeModuleContainer(root, "$offPkg.erbm", offPkg, garbage = true)
        writeProgramMeta(root)
        disable(root, offPkg)

        // marker 在场 ⇒ collectErbModules 先摘掉坏容器，装配链不得看到它（否则坏档整包抛）。
        val plan = assemble(root)
        assertNotNull("停用包的坏容器不得让整条链旁路/抛错：正常包必须照常装配", plan)
        assertTrue("正常包的覆盖声明必须真生效（hasOverrides）", plan!!.hasOverrides)
    }

    @Test
    fun `装配链容器侧_删标记后坏容器整包拒_证明过滤是唯一原因`() {
        val root = tempGameRoot()
        val livePkg = "mod.chain.live"
        val offPkg = "mod.chain.off"
        writeModuleManifest(root, "$livePkg.manifest", livePkg)
        writeModuleContainer(root, "$livePkg.erbm", livePkg, garbage = false)
        writeModuleManifest(root, "$offPkg.manifest", offPkg)
        writeModuleContainer(root, "$offPkg.erbm", offPkg, garbage = true)
        writeProgramMeta(root)
        disable(root, offPkg)
        enable(root, offPkg)

        // 套内反向对照：同一份坏容器、只把 marker 删掉 ⇒ fail-closed 整包拒。
        try {
            assemble(root)
            fail("删掉 marker 后坏 .erbm 必须 fail-closed 整包拒（证明上一例的红线来自 marker 过滤）")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "拒载原因必须点名坏档容器；实际=${e.message}",
                e.message.orEmpty().contains("坏档"),
            )
        }
    }

    // ------------------------------------------------------------------
    // 目标 3：停用期间包的资源 / 侧车一件不少（逐字节）
    // ------------------------------------------------------------------

    private fun md5Of(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    /** 某包在 `mods/rt2/` 下的全部成员（文件 + `<pkg>.assets` 整棵树）→ 相对名 : md5。 */
    private fun digestTree(rt2: File, packageId: String): Map<String, String> {
        val members = rt2.listFiles().orEmpty()
            .filter { it.name.startsWith("$packageId.") || it.name == "$packageId.assets" }
        val out = sortedMapOf<String, String>()
        for (member in members) {
            if (member.isDirectory) {
                member.walkTopDown().filter { it.isFile }.forEach { file ->
                    val relative = file.toRelativeString(member).replace(File.separatorChar, '/')
                    out["${member.name}/$relative"] = md5Of(file)
                }
            } else {
                out[member.name] = md5Of(member)
            }
        }
        return out
    }

    @Test
    fun `停用不删资源_资产设置侧车容器在真装配前后逐字节保留`() {
        val offPkg = "kojo-demo"
        val livePkg = "mod.chain.live"
        val root = tempGameRoot()
        val rt2 = rt2Dir(root)

        // 正常数据包：合法容器 + manifest + 签名段（让链装配真的非旁路）。
        writeModuleManifest(root, "$livePkg.manifest", livePkg)
        writeModuleContainer(root, "$livePkg.erbm", livePkg, garbage = false)
        writeProgramMeta(root)

        // 停用包全套侧车：.manifest / .erbm / .settings / .sources.sha256 / .assets 树。
        writeModuleManifest(root, "$offPkg.manifest", offPkg)
        Files.write(rt2.toPath().resolve("$offPkg.erbm"), byteArrayOf(1, 2, 3, 4, 5))
        Files.write(rt2.toPath().resolve("$offPkg.settings"), "growth=3\n".toByteArray())
        Files.write(rt2.toPath().resolve("$offPkg.sources.sha256"), "deadbeef\n".toByteArray())
        val assets = rt2.toPath().resolve("$offPkg.assets")
        Files.createDirectories(assets.resolve("sub"))
        Files.write(assets.resolve("a.txt"), "alpha".toByteArray())
        Files.write(assets.resolve("sub").resolve("b.bin"), byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7))
        disable(root, offPkg)

        val before = digestTree(rt2, offPkg)
        assertEquals(
            "夹具自检：五类侧车 + marker 必须都在快照里，实际=${before.keys}",
            setOf(
                "$offPkg.manifest",
                "$offPkg.erbm",
                "$offPkg.settings",
                "$offPkg.sources.sha256",
                "$offPkg.assets/a.txt",
                "$offPkg.assets/sub/b.bin",
                "$offPkg.off",
            ),
            before.keys,
        )

        // 真链装配（开态先跑 ModPackageInventory.gcOrphans，再收集容器）+ 真运行时装配。
        val plan = assemble(root)
        assertNotNull("正常包必须装配出来，停用包不得参与配对", plan)
        assertTrue(plan!!.hasOverrides)
        val session = attach(root, VariableStore(), listOf(livePkg to CountingMod()))
        try {
            assertTrue(
                "正常包必须真装成功；日志=${installLines()}",
                logs.any { it == "$INSTALL_PREFIX$livePkg active=true" },
            )
            assertNoInstallLine(offPkg)
        } finally {
            session.shutdown()
        }

        val after = digestTree(rt2, offPkg)
        assertEquals(
            "停用期间包的资源/设置/侧车/容器一个字节都不许少或变（防「停用即删除」回归）",
            before,
            after,
        )
        assertTrue(
            "停用标记本身也不得被装配顺手删掉（否则下次启动悄悄启用）",
            ModEnablement.markerFile(rt2, offPkg).isFile,
        )
    }

    // ------------------------------------------------------------------
    // 判据边界：marker 命名 / 位置不对 ⇒ 不算停用
    // ------------------------------------------------------------------

    @Test
    fun `marker命名或位置不对不算停用_包照常装载`() {
        val family = FamilyDeclarations.PACKAGE_ID
        val root = productionRoot()
        val rt2 = rt2Dir(root)
        // 错名：.offx 不是停用后缀；错位：mods/ 根下不是 mods/rt2/。
        assertTrue(File(rt2, "$family.offx").createNewFile())
        assertTrue(root.resolve("mods").resolve("$family.off").toFile().createNewFile())
        assertTrue("这两个文件都不是停用标记", ModEnablement.disabledPackageIds(rt2).isEmpty())

        val providers = ModCarrierRegistry.providers(root.toFile())
        assertTrue("标记命名/位置不对 ⇒ providers 必须原样装回该包", providers.any { it.first == family })
        val scan = ModManageScanner.scan(root.toFile())
        assertTrue("设置页读数必须是 enabled=true", scan.mods.first { it.packageId == family }.enabled)

        val session = attach(root, VariableStore(), providers)
        try {
            assertNotNull("标记命名/位置不对 ⇒ 包照常装载", session.host.contextOf(family))
            assertTrue(
                "装配日志必须有该包 active=true；日志=${installLines()}",
                logs.any { it == "$INSTALL_PREFIX$family active=true" },
            )
        } finally {
            session.shutdown()
        }
    }

    private companion object {
        const val INSTALL_PREFIX: String = "mod.install pkg="
    }
}
