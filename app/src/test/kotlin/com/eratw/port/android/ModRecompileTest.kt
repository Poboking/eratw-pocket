package com.eratw.port.android

import com.eratw.port.core.compile.PatchCompiler
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.mod.ErbModuleContainer
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.platform.storage.ModStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 设置页「重新编译」（Lane J）的出口判据：
 *
 * 1. **可重编集合**：只有 `mods/rt2/<pkg>.sources` 在场（且名单可解析）的 `kojo-*` 包才出现在
 *    [ModRecompiler.targets] 里；`.sources` 被删/被改坏 = 从列表消失，不崩。
 * 2. **upToDate 判据**：源与产物都在场、且内容指纹（`<pkg>.sources.sha256`）与盘上源逐条
 *    相等（无指纹的老包退回 mtime 判据）⇒ 判 upToDate，重编直接
 *    [RecompileResult.Skipped]（不白跑编译器、不动盘）。
 * 3. **源变重编**：源更新后重编走**导入链同一条**编译链（`ModImportController.compileToModule`），
 *    原子替换 `.erbm`/`.manifest` 并刷新内容指纹，**packageId 不变**（包身份 = 源名集合，
 *    不随内容变）。
 * 4. **失败保留旧件**：编译失败（I6 全局 #DIM 被拒）返回 [RecompileResult.Failed]，盘上
 *    `.erbm`/`.manifest` 字节逐字不变（半成品不落盘）。
 * 5. **坏 `.sources`**：名单为空/不可解析 ⇒ [RecompileResult.Skipped]（与「up-to-date」两种
 *    Skipped 用 reason 分开，页面上文案不同）。
 * 6. **内容指纹**（Lane O）：内容变了但 mtime 更旧 ⇒ 指纹判出待重编（mtime 会漏，用例里
 *    拿掉侧车做反向证明）；老包无指纹 ⇒ mtime 回退且重编后补写；坏指纹 ⇒ 忽略并回退，
 *    重编时替换成有效侧车。
 *
 * 全部纯 JVM：base 上下文 = mini 侧车注入，不碰 Android 类、不碰设备产物。
 */
class ModRecompileTest {

    // ------------------------------------------------------------------
    // 夹具：mini 侧车 + 合成 base 计数 + 临时 mods 目录（与 ModImportControllerTest 同源口径）
    // ------------------------------------------------------------------

    private fun miniMetadata(): BootMetadata {
        val names = listOf("A_ONE", "A_TWO", "A_SUM", "A_DEF")
        val argCounts = mapOf("A_TWO" to 1, "A_DEF" to 3)
        return BootMetadata(
            functions = names.mapIndexed { i, name ->
                FuncMeta(
                    index = i,
                    key = name,
                    name = name,
                    isMethod = name == "A_SUM",
                    argIntCount = argCounts[name] ?: 0,
                    argStrCount = 0,
                    returnsStr = false,
                    defIndex = i,
                    position = "mini.ERB:${i + 1}",
                )
            },
            methodTypes = mapOf("A_SUM" to BootMetadata.MethodType.INT),
        )
    }

    private fun miniBaseCounts(): PatchCompiler.BaseCounts =
        PatchCompiler.BaseCounts(
            instructionCount = 4000,
            functionCount = 4,
            statementCount = 100,
            stringCount = 10,
            longCount = 5,
            exprCount = 20,
        )

    /** 只引用 base 函数表里 A_ONE 的最小补丁源（符号面自洽即可）。 */
    private val patchSource = """
        @B_HELLO
        CALL A_ONE(3)
        PRINTL HELLO_PATCH
        RETURN 0
    """.trimIndent() + "\n"

    /** 源变后的新内容：换掉 entry 并把调用目标换成 A_TWO(7)，产物必然不同。 */
    private val changedSource = """
        @B_WORLD
        CALL A_TWO(7)
        PRINTL HELLO_REWRITTEN
        RETURN 0
    """.trimIndent() + "\n"

    /** 导入链会拒绝的坏源（全局 #DIM 走 I6 拒载，`ModImportControllerTest` 同源夹具）。 */
    private val rejectedSource = "#DIM GLOBAL_COUNT = 5\n\n@B_GLOBAL\nRETURN 0\n"

    private fun contextOf(): BaseCompileContext =
        BaseCompileContext(miniMetadata(), miniBaseCounts(), BASE_SHA, csvDir = null)

    private fun controllerIn(tmp: File, context: BaseCompileContext = contextOf()): ModImportController =
        ModImportController(
            modStore = ModStore(File(tmp, "mods")),
            baseSource = { context },
            gameRoot = tmp,
        )

    /** 走真导入链落一个包，返回它的 packageId（导入链内部同一套 `packageIdFor`）。 */
    private fun importPatch(tmp: File): String {
        val controller = controllerIn(tmp)
        val outcome = controller.import(
            listOf(PickedModFile(SOURCE_NAME, patchSource.toByteArray(Charsets.UTF_8))),
        )
        assertTrue("导入夹具应先成功（否则重编用例没有旧件）：$outcome", outcome is ModImportOutcome.Imported)
        return controller.packageIdFor(listOf(SOURCE_NAME))
    }

    private fun modsDir(tmp: File): File = File(tmp, "mods")

    private fun rt2Dir(tmp: File): File = File(modsDir(tmp), "rt2")

    private fun artifact(tmp: File, packageId: String, extension: String): File =
        File(rt2Dir(tmp), packageId + extension)

    /**
     * 把两件产物拨回过去：让「改源 → 源比产物新」在毫秒级时间戳上稳定成立，
     * 不依赖用例与导入之间的真实耗时（Windows/CI 上同一毫秒内写盘的抖动）。
     */
    private fun backdateArtifacts(tmp: File, packageId: String) {
        val old = System.currentTimeMillis() - 60_000
        assertTrue(artifact(tmp, packageId, ".erbm").setLastModified(old))
        assertTrue(artifact(tmp, packageId, ".manifest").setLastModified(old))
    }

    // ------------------------------------------------------------------
    // 判据 1：可重编集合（.sources 是唯一入口）
    // ------------------------------------------------------------------

    @Test
    fun `没有 sources 的包不出现在可重编列表`() {
        val tmp = tempDir()
        val packageId = importPatch(tmp)
        backdateArtifacts(tmp, packageId)

        val listed = ModRecompiler.targets(tmp)
        assertEquals("导入了 .sources 的包应出现在列表里", 1, listed.size)
        assertEquals(packageId, listed[0].packageId)
        assertEquals("源名单行数", 1, listed[0].sourceCount)
        assertTrue("产物字节数应大于 0", listed[0].artifactBytes > 0)

        // 删掉 provenance：包身份不再可重编 ⇒ 从列表消失（不崩、不抛）。
        assertTrue(artifact(tmp, packageId, ".sources").delete())
        assertTrue("没有 .sources 的包不该出现在可重编列表", ModRecompiler.targets(tmp).isEmpty())
    }

    // ------------------------------------------------------------------
    // 判据 2：upToDate（make 式：没有源比产物新）
    // ------------------------------------------------------------------

    @Test
    fun `源未变时判 upToDate 且重编直接跳过`() {
        val tmp = tempDir()
        val packageId = importPatch(tmp)

        val target = ModRecompiler.targets(tmp).single()
        assertTrue("刚导入完（源早于产物）应判 upToDate", target.upToDate)
        assertTrue(
            "产物字节数应大于 0（两件都在场）",
            target.artifactBytes ==
                artifact(tmp, packageId, ".erbm").length() + artifact(tmp, packageId, ".manifest").length(),
        )

        val erbmBefore = artifact(tmp, packageId, ".erbm").readBytes()
        val result = ModRecompiler.recompileWith(controllerIn(tmp), modsDir(tmp), packageId)
        assertTrue("upToDate 应返回 Skipped 而不是白跑编译：$result", result is RecompileResult.Skipped)
        assertEquals(ModRecompiler.SKIP_REASON_UP_TO_DATE, (result as RecompileResult.Skipped).reason)
        assertTrue(
            "Skipped 不该动盘",
            erbmBefore.contentEquals(artifact(tmp, packageId, ".erbm").readBytes()),
        )
    }

    // ------------------------------------------------------------------
    // 判据 3：源变重编（原子替换 + packageId 不变）
    // ------------------------------------------------------------------

    @Test
    fun `源变后重编替换产物且 packageId 不变`() {
        val tmp = tempDir()
        val packageId = importPatch(tmp)
        backdateArtifacts(tmp, packageId)

        val source = File(modsDir(tmp), SOURCE_NAME)
        source.writeText(changedSource, Charsets.UTF_8)
        val erbmBefore = artifact(tmp, packageId, ".erbm").readBytes()
        val manifestBefore = artifact(tmp, packageId, ".manifest").readBytes()
        assertFalse("改源后不应再判 upToDate", ModRecompiler.targets(tmp).single().upToDate)

        val result = ModRecompiler.recompileWith(controllerIn(tmp), modsDir(tmp), packageId)
        assertTrue("源变后应重编成功：$result", result is RecompileResult.Updated)
        result as RecompileResult.Updated
        assertEquals("包身份 = 源名集合，重编不许换 packageId", packageId, result.packageId)
        assertEquals(1, result.sourceCount)
        assertTrue("Updated 应带新产物字节数", result.artifactBytes > 0)

        val erbmAfter = artifact(tmp, packageId, ".erbm").readBytes()
        assertFalse("源变了，.erbm 应被替换", erbmBefore.contentEquals(erbmAfter))
        assertFalse(
            ".manifest 也应被替换",
            manifestBefore.contentEquals(artifact(tmp, packageId, ".manifest").readBytes()),
        )
        assertEquals(
            "替换后的容器仍要能读回且包身份一致",
            packageId,
            ErbModuleContainer.decode(erbmAfter).packageId,
        )
        // 重编后源不再比产物新 ⇒ 下一次判 upToDate（幂等，不会每次点都重编）。
        assertTrue("重编后应判 upToDate", ModRecompiler.targets(tmp).single().upToDate)
    }

    // ------------------------------------------------------------------
    // 判据 4：编译失败保留旧件
    // ------------------------------------------------------------------

    @Test
    fun `编译失败保留旧件并返回 Failed`() {
        val tmp = tempDir()
        val packageId = importPatch(tmp)
        backdateArtifacts(tmp, packageId)

        File(modsDir(tmp), SOURCE_NAME).writeText(rejectedSource, Charsets.UTF_8)
        val erbmBefore = artifact(tmp, packageId, ".erbm").readBytes()
        val manifestBefore = artifact(tmp, packageId, ".manifest").readBytes()

        val result = ModRecompiler.recompileWith(controllerIn(tmp), modsDir(tmp), packageId)
        assertTrue("坏源应返回 Failed 而不是抛/落半成品：$result", result is RecompileResult.Failed)
        result as RecompileResult.Failed
        assertEquals(packageId, result.packageId)
        assertTrue("Failed 应带证据串", result.message.isNotEmpty())
        assertTrue(
            "编译失败后 .erbm 必须逐字保留旧件",
            erbmBefore.contentEquals(artifact(tmp, packageId, ".erbm").readBytes()),
        )
        assertTrue(
            "编译失败后 .manifest 必须逐字保留旧件",
            manifestBefore.contentEquals(artifact(tmp, packageId, ".manifest").readBytes()),
        )
        // 源仍比产物新：列表继续显示「待重编」，玩家修好源后还能再点。
        assertFalse("失败后不该伪装成 upToDate", ModRecompiler.targets(tmp).single().upToDate)
    }

    // ------------------------------------------------------------------
    // 判据 5：坏 .sources 不崩
    // ------------------------------------------------------------------

    @Test
    fun `坏 sources 不崩只进 Skipped`() {
        val tmp = tempDir()
        val packageId = importPatch(tmp)
        backdateArtifacts(tmp, packageId)

        // provenance 被改成空行列表：读不出源名 ⇒ 保守当「无法重编」，不进 targets。
        artifact(tmp, packageId, ".sources").writeText("\n   \n", Charsets.UTF_8)
        assertTrue("坏 .sources 的包不该进可重编列表", ModRecompiler.targets(tmp).isEmpty())

        val result = ModRecompiler.recompileWith(controllerIn(tmp), modsDir(tmp), packageId)
        assertTrue("坏 .sources 应返回 Skipped（不崩）：$result", result is RecompileResult.Skipped)
        result as RecompileResult.Skipped
        assertEquals(packageId, result.packageId)
        assertTrue(
            "「读不出名单」要与「up-to-date」用不同 reason 分开（页面文案不同）",
            result.reason != ModRecompiler.SKIP_REASON_UP_TO_DATE && result.reason.contains(".sources"),
        )
    }

    // ------------------------------------------------------------------
    // 判据 6（补充）：源文件缺失 ⇒ Failed 且旧件保留
    // ------------------------------------------------------------------

    @Test
    fun `源文件缺失时返回 Failed 且旧件保留`() {
        val tmp = tempDir()
        val packageId = importPatch(tmp)
        backdateArtifacts(tmp, packageId)

        val erbmBefore = artifact(tmp, packageId, ".erbm").readBytes()
        assertTrue(File(modsDir(tmp), SOURCE_NAME).delete())

        val result = ModRecompiler.recompileWith(controllerIn(tmp), modsDir(tmp), packageId)
        assertTrue("源缺失应返回 Failed：$result", result is RecompileResult.Failed)
        result as RecompileResult.Failed
        assertTrue("Failed 应点名缺哪个源", result.message.contains(SOURCE_NAME))
        assertTrue(
            "源缺失时 .erbm 保留旧件",
            erbmBefore.contentEquals(artifact(tmp, packageId, ".erbm").readBytes()),
        )
    }

    // ------------------------------------------------------------------
    // 判据 7：内容指纹（Lane O）—— mtime 会漏的场景靠内容摘要判出
    // ------------------------------------------------------------------

    private fun fingerprintOf(tmp: File, packageId: String): File =
        artifact(tmp, packageId, ModPackageInventory.FINGERPRINT_EXTENSION)

    private fun expectedFingerprint(source: String): ByteArray =
        ModSourceFingerprint.bytesFor(
            listOf(PickedModFile(SOURCE_NAME, source.toByteArray(Charsets.UTF_8))),
        )

    @Test
    fun `内容变但 mtime 更旧时靠指纹判出待重编`() {
        val tmp = tempDir()
        val packageId = importPatch(tmp)
        val sidecar = fingerprintOf(tmp, packageId)
        assertTrue("导入链必须写内容指纹侧车", sidecar.isFile)

        // 改内容但把 mtime 拨到比产物更旧：mtime 判据会当「源未变」漏判（下面反向证明）。
        val source = File(modsDir(tmp), SOURCE_NAME)
        source.writeText(changedSource, Charsets.UTF_8)
        val stale = System.currentTimeMillis() - 120_000
        assertTrue(source.setLastModified(stale))
        assertTrue(
            "夹具前提：源 mtime 必须比产物旧（否则这条用例退化成 mtime 也能判出）",
            stale < artifact(tmp, packageId, ".erbm").lastModified(),
        )

        assertFalse(
            "内容变了，指纹必须判出待重编（这正是 mtime 漏判的场景）",
            ModRecompiler.targets(tmp).single().upToDate,
        )

        // 反向证明：拿掉侧车 ⇒ 退回 mtime ⇒ 同一盘上状态被判 upToDate（本波要堵的漏）。
        val sidecarBytes = sidecar.readBytes()
        assertTrue(sidecar.delete())
        assertTrue(
            "反向证明：无指纹时 mtime 回退会漏判",
            ModRecompiler.targets(tmp).single().upToDate,
        )
        sidecar.writeBytes(sidecarBytes)

        val result = ModRecompiler.recompileWith(controllerIn(tmp), modsDir(tmp), packageId)
        assertTrue("指纹判出源变后应重编成功：$result", result is RecompileResult.Updated)
        assertTrue(
            "重编后侧车应刷新成当前内容",
            fingerprintOf(tmp, packageId).readBytes().contentEquals(expectedFingerprint(changedSource)),
        )
        assertTrue(
            "同一输入两次算出的指纹必须逐字节相同（可复现，written 顺序无关）",
            expectedFingerprint(changedSource).contentEquals(expectedFingerprint(changedSource)),
        )
        assertTrue("重编后指纹一致，应判 upToDate", ModRecompiler.targets(tmp).single().upToDate)
    }

    @Test
    fun `老包无指纹时按 mtime 回退且重编后补写指纹`() {
        val tmp = tempDir()
        val packageId = importPatch(tmp)
        val sidecar = fingerprintOf(tmp, packageId)
        assertTrue(sidecar.delete())
        assertTrue(
            "迁移兼容：无指纹且源不新于产物 ⇒ 仍判 upToDate（老包不因缺侧车被迫重编）",
            ModRecompiler.targets(tmp).single().upToDate,
        )
        backdateArtifacts(tmp, packageId)

        val source = File(modsDir(tmp), SOURCE_NAME)
        source.writeText(changedSource, Charsets.UTF_8)
        assertFalse(
            "无指纹时按 mtime 判据：源比产物新 ⇒ 待重编",
            ModRecompiler.targets(tmp).single().upToDate,
        )

        val result = ModRecompiler.recompileWith(controllerIn(tmp), modsDir(tmp), packageId)
        assertTrue("老包重编应成功并补写指纹：$result", result is RecompileResult.Updated)
        assertTrue("重编后必须补写内容指纹侧车", sidecar.isFile)
        assertTrue(
            "补写的指纹应等于当前源内容",
            sidecar.readBytes().contentEquals(expectedFingerprint(changedSource)),
        )
        // 指纹在场后不再看 mtime：内容换回旧版、mtime 拨旧 ⇒ 内容比对仍判「变了」。
        source.writeText(patchSource, Charsets.UTF_8)
        assertTrue(source.setLastModified(System.currentTimeMillis() - 120_000))
        assertFalse(
            "有指纹后内容判据不看 mtime",
            ModRecompiler.targets(tmp).single().upToDate,
        )
    }

    @Test
    fun `指纹坏格式时回退 mtime 判据且重编替换坏指纹`() {
        val tmp = tempDir()
        val packageId = importPatch(tmp)
        val sidecar = fingerprintOf(tmp, packageId)
        sidecar.writeText("GARBAGE\n", Charsets.UTF_8)
        backdateArtifacts(tmp, packageId)

        val source = File(modsDir(tmp), SOURCE_NAME)
        source.writeText(changedSource, Charsets.UTF_8)
        assertFalse(
            "坏指纹不参与判据，按 mtime 判出待重编",
            ModRecompiler.targets(tmp).single().upToDate,
        )

        // 源 mtime 也拨旧：mtime 回退说「未变」⇒ 证明坏指纹只是被忽略、不抛也不误判。
        val stale = System.currentTimeMillis() - 120_000
        assertTrue(source.setLastModified(stale))
        assertTrue("坏指纹不应被当成有效指纹", ModRecompiler.targets(tmp).single().upToDate)

        assertTrue(source.setLastModified(System.currentTimeMillis()))
        val result = ModRecompiler.recompileWith(controllerIn(tmp), modsDir(tmp), packageId)
        assertTrue("坏指纹包重编应成功：$result", result is RecompileResult.Updated)
        assertTrue(
            "重编必须用有效指纹替换坏侧车",
            sidecar.readBytes().contentEquals(expectedFingerprint(changedSource)),
        )
        assertTrue("新指纹可解析", ModSourceFingerprint.parse(sidecar.readBytes()) != null)
    }

    @Test
    fun `指纹按源名排序且同一输入可复现`() {
        val lower = PickedModFile("b.ERB", "B".toByteArray(Charsets.UTF_8))
        val upper = PickedModFile("A.erh", "A".toByteArray(Charsets.UTF_8))
        val one = ModSourceFingerprint.bytesFor(listOf(lower, upper))
        val two = ModSourceFingerprint.bytesFor(listOf(upper, lower))
        assertTrue("源名入参顺序不影响指纹（内部按 compareNames 排序）", one.contentEquals(two))
        val text = String(one, Charsets.UTF_8)
        assertTrue("首行是带版本的头", text.startsWith(ModSourceFingerprint.HEADER + "\n"))
        val lines = text.lines().filter { it.isNotEmpty() }
        assertEquals("头行 + 两条记录", 3, lines.size)
        assertTrue(
            "记录按源名排序（A.erh 先于 b.ERB），实得 $lines",
            lines[1].endsWith("  A.erh") && lines[2].endsWith("  b.ERB"),
        )
    }

    private companion object {
        private const val SOURCE_NAME = "KOJO_PATCH.ERB"
        private val BASE_SHA = "a".repeat(64)
    }
}

private fun tempDir(): File = Files.createTempDirectory("eratw-modrecompile").toFile()
