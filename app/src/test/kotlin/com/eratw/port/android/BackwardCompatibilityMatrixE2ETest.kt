package com.eratw.port.android

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.mod.ModDeclarationAdapter
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.GameSession
import com.eratw.port.core.save.FileSystemOps
import com.eratw.port.core.save.PackageChunk
import com.eratw.port.core.save.RootedSaveFileIo
import com.eratw.port.core.save.SaveConfig
import com.eratw.port.core.save.SaveDiagnostics
import com.eratw.port.core.save.SidecarBinding
import com.eratw.port.core.save.SidecarSaveBundle
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modapi.ArchetypeDecl
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * task-115 向后兼容矩阵（app 宿主面）：global 侧车旧容器、导入包元数据缺失、热重编指纹缺失、
 * 旧格式 provenance —— 全部用**手写旧字节 / 手摆旧盘面**验证「读得进 + 语义等价 + 不静默降级」。
 *
 * 纪律：global `.rt2` 旧容器在本文件按 `SidecarSaveBundle` 线格式常量手工拼装（[legacyContainer]），
 * **不得**用 `SidecarSaveBundle(...).encode()` 造旧档；旧档兼容用例的鉴别力正来自「旧版本
 * 编码器会写出的字节形状」，用当前编码器生成等于自己读自己。手写容器先经生产
 * [SidecarSaveBundle.decode] 自检（binding / 分片 / 段空值），再入盘交给生产 LOADGLOBAL 链。
 *
 * 装配夹具照 `ModGlobalSeedRoundTripE2ETest` 的生产缝（真 GameSession.store + 真 attach +
 * 真 FileSystemOps 落盘），不自造引擎或存档实现。
 */
class BackwardCompatibilityMatrixE2ETest {

    private val logs: MutableList<String> = mutableListOf()

    private val baseMetas = listOf(
        FuncMeta(index = 0, key = "ENTRY"),
        FuncMeta(index = 1, key = "X"),
    )

    private fun baseProgram(): Program {
        val b = CodeBuilder()
        val entry = b.declareFunction("ENTRY", b.here(), arity = 0, slotCount = 2)
        b.emit(Opcode._CALL, 1, 0, 0, 0)
        b.emit(Opcode._RET, 0, 0, 0, 1)
        b.declareFunction("X", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._PRINT_LITERAL, b.string("BASE-X"), 1, 0, 2)
        b.emit(Opcode._SET_INT, 0, b.constant(111L), 0, 3)
        b.emit(Opcode._RET, 0, 0, 0, 4)
        return b.build(entry)
    }

    private fun newSession(root: Path): GameSession = GameSession(
        gameRoot = root,
        program = baseProgram(),
        metadata = BootMetadata(functions = baseMetas),
    )

    private fun tempGameRoot(): Path {
        val root = Files.createTempDirectory("compat-matrix")
        Files.createDirectories(root.resolve("mods").resolve("rt2"))
        return root
    }

    private fun writeManifest(root: Path, packageId: String) {
        val manifest = ModManifest(
            packageId = packageId,
            archetypes = listOf(ArchetypeDecl("$packageId.trait")),
        )
        val bytes = ManifestCodec.encode(ModDeclarationAdapter.toDescriptor(manifest))
        Files.write(
            root.resolve("mods").resolve("rt2").resolve(packageId.replace('.', '_') + ".manifest"),
            bytes,
        )
    }

    private fun attach(
        session: GameSession,
        root: Path,
        providers: List<Pair<String, EraMod>>,
        entropyTail: ByteArray,
    ): ModRuntimeSession {
        for ((packageId, _) in providers) writeManifest(root, packageId)
        val attached = ModRuntimeAssembly.attach(
            gameRoot = root.toFile(),
            store = session.store,
            nativeRegistry = NativeMethodRegistry(),
            onVmThread = { true },
            enabled = true,
            logSink = { line -> logs += line },
            modProviders = providers,
            decisionEntropyTail = { entropyTail },
        )
        assertNotNull("开态装配必须返回会话（enabled=true）", attached)
        return attached!!
    }

    private fun tail(offset: Int): ByteArray = ByteArray(TAIL_BYTES) { (it + offset).toByte() }

    private fun opsOf(store: VariableStore, root: Path): FileSystemOps =
        FileSystemOps(store, RootedSaveFileIo(root), SaveConfig.ERATW)

    private fun globalSavOf(root: Path): Path = root.resolve(SaveConfig.ERATW.globalPath())

    private fun globalRt2Of(root: Path): Path = Path.of(globalSavOf(root).toString() + ".rt2")

    private fun saveGlobalExactlyOnce(session: GameSession, root: Path) {
        opsOf(session.store, root).saveGlobal()
        assertTrue("global.sav 必须真落盘", Files.isRegularFile(globalSavOf(root)))
        assertTrue("global.rt2 必须真落盘", Files.isRegularFile(globalRt2Of(root)))
    }

    private fun adoptLogs(): List<String> = logs.filter { "决策种子随档采纳" in it }

    private class ProbeMod : EraMod {
        override fun onLoad(context: ModContext) = Unit

        override fun onEnable() = Unit

        override fun onDisable() = Unit

        override fun onUnload() = Unit
    }

    // ------------------------------------------------------------------
    // 01：/3 旧 manifest 的展示字段回落 ⇒ 列表标题回落 packageId
    // ------------------------------------------------------------------

    @Test
    fun `01 RT2MANIFEST-3 旧档展示名为空时列表标题回落 packageId`() {
        // 链路：/3 只带 packageId+version（见 core 侧手写字节用例）⇒ ModManageModel 用
        // descriptor.displayName 建 InstalledMod（ModManageModel.kt:126）⇒ title 回落包名。
        val item = InstalledMod(
            packageId = "legacy.pkg",
            displayName = "",
            version = "1.4.2",
            author = "",
            description = "",
            source = ModSource.DATA_PACKAGE,
            enabled = true,
        )
        assertEquals("列表显示名必须回落 packageId（不能空白占位）", "legacy.pkg", item.title)
        assertFalse("旧档没有显示名不等于缺模块（.erbm 在场合规）", item.missingModule)
    }

    // ------------------------------------------------------------------
    // 07 / 08：global 侧车旧容器（手写字节）向后兼容
    // ------------------------------------------------------------------

    @Test
    fun `07 global 手写旧容器决策段为空任务段在场时读入不拒载且不采纳种子`() {
        val root = tempGameRoot()
        val sessionA = newSession(root)
        val attachedA = attach(sessionA, root, listOf("mod.a" to ProbeMod()), tail(21))
        saveGlobalExactlyOnce(sessionA, root)
        attachedA.shutdown()

        // 真实档解出来的 binding / 快照作为手写容器的数据面（只作载荷，不以其为容器形状）。
        val fresh = SidecarSaveBundle.decode(Files.readAllBytes(globalRt2Of(root)))
        assertTrue("前置：当前新档必须带决策段", fresh.decisionStatePayload.isNotEmpty())
        assertTrue("前置：当前新档必须带任务段", fresh.jobStatePayload.isNotEmpty())

        // 中间版本形状：状态尾段在场，但决策段长度 0（只有任务段）。旧版本升级时可能出现。
        val legacy = legacyContainer(
            fresh,
            stateTail = Pair(ByteArray(0), fresh.jobStatePayload),
        )
        // 自检手写字节：生产解码器读出的形状必须正是「决策段空、任务段在、分片数不变」。
        val decoded = SidecarSaveBundle.decode(legacy)
        assertEquals(fresh.binding, decoded.binding)
        assertEquals(fresh.chunks.size, decoded.chunks.size)
        assertTrue("手写旧容器决策段必须为空", decoded.decisionStatePayload.isEmpty())
        assertTrue("手写旧容器任务段必须在场（区分于最老零段形状）", decoded.jobStatePayload.isNotEmpty())
        Files.write(globalRt2Of(root), legacy)

        val sessionB = newSession(root)
        val attachedB = attach(sessionB, root, listOf("mod.a" to ProbeMod()), tail(22))
        val seedB = seedOf(attachedB)
        assertTrue("旧容器必须照常 LOADGLOBAL（不抛、不拒载）", opsOf(sessionB.store, root).loadGlobal())
        assertEquals("决策段为空 ⇒ 无种子可采纳，种子保持装配期值", seedB, seedOf(attachedB))
        assertEquals("旧容器不得出现采纳日志，实际=${adoptLogs()}", 0, adoptLogs().size)
        assertTrue(
            "旧容器仍必须走完应用链（restore ok 日志在场）",
            logs.any { "mod.global.rt2 restore ok" in it },
        )
        attachedB.shutdown()
    }

    @Test
    fun `08 global 手写最老容器无状态尾段零分片也能装载`() {
        val root = tempGameRoot()
        val sessionA = newSession(root)
        val attachedA = attach(sessionA, root, listOf("mod.a" to ProbeMod()), tail(23))
        saveGlobalExactlyOnce(sessionA, root)
        attachedA.shutdown()
        val fresh = SidecarSaveBundle.decode(Files.readAllBytes(globalRt2Of(root)))

        // 增补前最老形状：五个定长段 + 零分片，CRC 之后即结束（无任何状态尾段）。
        val oldest = legacyContainer(
            fresh,
            chunks = emptyList(),
            stateTail = null,
        )
        val decodedOldest = SidecarSaveBundle.decode(oldest)
        assertTrue(decodedOldest.decisionStatePayload.isEmpty())
        assertTrue(decodedOldest.jobStatePayload.isEmpty())
        assertTrue(decodedOldest.chunks.isEmpty())
        Files.write(globalRt2Of(root), oldest)

        val sessionB = newSession(root)
        val attachedB = attach(sessionB, root, listOf("mod.a" to ProbeMod()), tail(24))
        val seedB = seedOf(attachedB)
        assertTrue("最老容器（无尾段）必须照常 LOADGLOBAL", opsOf(sessionB.store, root).loadGlobal())
        assertEquals("最老容器：种子保持装配期值", seedB, seedOf(attachedB))
        assertEquals("最老容器：不得出现采纳日志", 0, adoptLogs().size)
        attachedB.shutdown()

        // 另一种「零段」形状：尾段在场但两段长度都写 0（升级期占位残留），同样可装载。
        val zeroTail = legacyContainer(
            fresh,
            stateTail = Pair(ByteArray(0), ByteArray(0)),
        )
        val decodedZero = SidecarSaveBundle.decode(zeroTail)
        assertTrue(decodedZero.decisionStatePayload.isEmpty())
        assertTrue(decodedZero.jobStatePayload.isEmpty())
        Files.write(globalRt2Of(root), zeroTail)
        val sessionC = newSession(root)
        val attachedC = attach(sessionC, root, listOf("mod.a" to ProbeMod()), tail(25))
        val seedC = seedOf(attachedC)
        assertTrue("零长度尾段容器必须照常 LOADGLOBAL", opsOf(sessionC.store, root).loadGlobal())
        assertEquals("零长度尾段：种子保持装配期值", seedC, seedOf(attachedC))
        assertEquals("零长度尾段：不得出现采纳日志", 0, adoptLogs().size)
        attachedC.shutdown()
    }

    // ------------------------------------------------------------------
    // 09 ~ 11：导入包 / 热重编 / provenance 旧面
    // ------------------------------------------------------------------

    @Test
    fun `09 导入包无 eratw-pack-json 时版本回落 1 且 minApi 视为 0`() {
        val missing = packMetaOf(emptyList())
        assertEquals("老包无元数据条目必须回落版本 1", "1", missing.version)
        assertEquals("老包 minApi 视为 0（不设闸）", 0, missing.minApi)
        assertEquals(PackMetaIgnore.MISSING, missing.ignored)
        assertFalse("回落版本不可信（导入成功但版本不来自作者）", missing.trusted)
        assertNull(missing.packageId)
        assertNull(missing.declaredVersion)

        // 真 zip 但没有 eratw-pack.json（只有 mod 源码）＝ 老包实际形状。
        val oldZip = zipOf(mapOf("mods/legacy.ERB" to "PRINTL 旧包\n".toByteArray(Charsets.UTF_8)))
        val fromZip = packMetaOf(listOf(oldZip))
        assertEquals("1", fromZip.version)
        assertEquals(0, fromZip.minApi)
        assertEquals(PackMetaIgnore.MISSING, fromZip.ignored)

        assertEquals(PackMetaIgnore.MISSING, parsePackMeta(null).ignored)
    }

    @Test
    fun `10 无 sources 指纹侧车时热重编判据回退 mtime 不误判`() {
        val root = Files.createTempDirectory("compat-recompile").toFile()
        val mods = File(root, "mods").apply { mkdirs() }
        val rt2 = File(mods, "rt2").apply { mkdirs() }
        val packageId = "kojo-legacy01"
        val erbm = File(rt2, "$packageId.erbm").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val manifest = File(rt2, "$packageId.manifest").apply { writeBytes(byteArrayOf(4)) }
        val source = File(mods, "legacy.ERB").apply { writeText("PRINTL 旧包\n") }
        assertNull(
            "前置：旧包盘面没有 .sources.sha256 指纹侧车",
            ModSourceFingerprint.readSidecar(rt2, packageId),
        )

        val artifactTime = 1_600_000_000_000L
        erbm.setLastModified(artifactTime)
        manifest.setLastModified(artifactTime)
        source.setLastModified(artifactTime - 60_000L)
        assertTrue(
            "无指纹时按 mtime 回退：源早于产物 ⇒ up-to-date",
            ModRecompiler.isUpToDate(mods, rt2, packageId, listOf("legacy.ERB")),
        )

        source.setLastModified(artifactTime + 60_000L)
        assertFalse(
            "无指纹时按 mtime 回退：源晚于产物 ⇒ 必须重编（不能误判 up-to-date）",
            ModRecompiler.isUpToDate(mods, rt2, packageId, listOf("legacy.ERB")),
        )
        assertFalse(
            "热重编判据不得为旧包静默补写指纹侧车",
            File(rt2, "$packageId.sources.sha256").exists(),
        )
    }

    @Test
    fun `11 旧格式 sources 无指纹时 GC 保守保留完整包`() {
        SaveDiagnostics.clear()
        val root = Files.createTempDirectory("compat-gc").toFile()
        val mods = File(root, "mods").apply { mkdirs() }
        val rt2 = File(mods, "rt2").apply { mkdirs() }
        val packageId = "kojo-legacy02"
        // 旧格式 provenance：纯文本逐行源名（引入即此形状，无 header / 无指纹行）。
        File(rt2, "$packageId.sources").writeBytes(
            "lost_a.ERB\nlost_b.ERB\n".toByteArray(Charsets.UTF_8),
        )
        File(rt2, "$packageId.manifest").writeBytes(byteArrayOf(1))
        File(rt2, "$packageId.erbm").writeBytes(byteArrayOf(2))
        assertNull("前置：旧格式 .sources 没有配对指纹", ModSourceFingerprint.readSidecar(rt2, packageId))

        // 名单里的源一个都不在 mods/（玩家删了留档源）—— 按现行判据仍必须保守保留整套。
        ModPackageInventory.gcOrphans(rt2, mods)

        assertTrue("完整包（容器+manifest）必须保留", File(rt2, "$packageId.erbm").isFile)
        assertTrue("完整包 manifest 必须保留", File(rt2, "$packageId.manifest").isFile)
        assertTrue("旧格式 .sources 必须保留（GC 不删 provenance）", File(rt2, "$packageId.sources").isFile)
        assertFalse(
            "GC 不得为旧包补写指纹侧车",
            File(rt2, "$packageId.sources.sha256").exists(),
        )
    }

    // ------------------------------------------------------------------
    // 手写 .rt2 线格式 helper（照 SidecarSaveBundle 规范：大端定长 + CRC32(body)）
    // ------------------------------------------------------------------

    private fun legacyContainer(
        source: SidecarSaveBundle,
        binding: SidecarBinding = source.binding,
        chunks: List<PackageChunk> = source.chunks,
        stateTail: Pair<ByteArray, ByteArray>? = null,
    ): ByteArray {
        val body = ByteArrayOutputStream()
        DataOutputStream(body).use { out ->
            out.writeInt(0x45525432)
            out.writeShort(2)
            out.writeLong(binding.uniqueCode)
            out.writeLong(binding.scriptVersion)
            out.writeInt(binding.saveTextHash)
            out.blob(source.registrySnapshot)
            out.blob(source.virtualNoSnapshot)
            out.blob(source.entityGraphPayload)
            out.writeInt(chunks.size)
            for (chunk in chunks) {
                out.blob(chunk.packageId.toByteArray(Charsets.UTF_8))
                out.blob(chunk.payload)
            }
            if (stateTail != null) {
                out.blob(stateTail.first)
                out.blob(stateTail.second)
            }
        }
        val bodyBytes = body.toByteArray()
        val crc = CRC32().apply { update(bodyBytes) }.value.toInt()
        return bodyBytes + byteArrayOf(
            (crc ushr 24).toByte(),
            (crc ushr 16).toByte(),
            (crc ushr 8).toByte(),
            crc.toByte(),
        )
    }

    private fun DataOutputStream.blob(bytes: ByteArray) {
        writeInt(bytes.size)
        write(bytes)
    }

    private fun zipOf(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun seedOf(session: ModRuntimeSession): Long {
        val state = session.decisionState
        assertNotNull("开态装配必须带决策管线", state)
        return state!!.pipeline.snapshotState().decisionSeed
    }

    private companion object {
        const val TAIL_BYTES = 16
    }
}
