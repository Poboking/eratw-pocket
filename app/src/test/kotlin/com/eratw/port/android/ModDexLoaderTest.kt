package com.eratw.port.android

import com.eratw.port.modapi.ModApi
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private const val TEST_PACKAGE_ID: String = "eratw.dexload"
private const val DISK_PACKAGE_ID: String = "kojo-0badc0de"
private const val DECLARED_PACKAGE_ID: String = "author.codemod"
private const val SHA_PREFIX_LENGTH: Int = 16
private const val ENGINE_API_VERSION: Int = ModApi.VERSION
private const val SURFACE_AHEAD: Int = ModApi.VERSION + 1
private const val MALFORMED_SHA: String = "deadbeef"
private const val MISMATCH_FILE_NAME: String = "0000000000000000.mod"
private val PAYLOAD_BYTES: ByteArray = "fake-mod-dex-payload".toByteArray(Charsets.UTF_8)
private val OTHER_PAYLOAD_BYTES: ByteArray = "another-mod-dex-payload".toByteArray(Charsets.UTF_8)

/** 失败面 reason 绝不能出现的内部细节：实现类名、异常类名、包路径片段。 */
private val FORBIDDEN_REASON_TOKENS: List<String> =
    listOf("Exception", "java.", "kotlin.", "ModCodeStore", "DexLoader", "IllegalState", "boom", ".kt")

/**
 * ADR-013 S4a 装载骨架的定向用例：闸序（ABI 闸 → 总闸 → 包名 → 信任 → 盘面 → sha → 工厂）、
 * 数据面收口、以及每道闸的反向证明（同一夹具放开这道闸就往下走一步）。
 */
class ModDexLoaderTest {
    // ------------------------------------------------------------------
    // 闸序
    // ------------------------------------------------------------------

    @Test
    fun `总闸关闭时直接 Disabled 且不建类加载器`() {
        val rt2 = tempDir()
        writeArtifact(rt2, bytes = PAYLOAD_BYTES)
        val factory = RecordingFactory()
        val outcome = loaderOf(rt2, factory = factory, enabled = false)
            .load(TEST_PACKAGE_ID, metaOf(compiledSurface = null), ENGINE_API_VERSION)
        assertTrue("总闸关必须是 Disabled，实际：$outcome", outcome is LoadOutcome.Disabled)
        assertEquals("总闸关时类加载器构造次数必须为 0", 0, factory.createdFor.size)
    }

    @Test
    fun `总闸关闭的反证 同一盘面把总闸打开即装载成功`() {
        val rt2 = tempDir()
        trust(rt2)
        writeArtifact(rt2, bytes = PAYLOAD_BYTES)
        val factory = RecordingFactory()
        val outcome = loaderOf(rt2, factory = factory, enabled = true)
            .load(TEST_PACKAGE_ID, metaOf(compiledSurface = null), ENGINE_API_VERSION)
        assertTrue("总闸打开时应装载成功，实际：$outcome", outcome is LoadOutcome.Loaded)
        assertEquals(1, factory.createdFor.size)
    }

    @Test
    fun `ABI 闸先于一切盘面 IO 超前编译面 Rejected 且空盘不查信任`() {
        val rt2 = tempDir()
        val factory = RecordingFactory()
        var gateCalls = 0
        val gate: (PackMeta, Int, ModDexGate.SurfaceCompat) -> ModDexGate.Verdict = { meta, engine, compat ->
            gateCalls += 1
            ModDexGate.verdict(meta, engine, compat)
        }
        val outcome = loaderOf(rt2, factory = factory, gate = gate)
            .load(TEST_PACKAGE_ID, metaOf(compiledSurface = SURFACE_AHEAD), ENGINE_API_VERSION)
        assertTrue("超前编译面必须 Rejected，实际：$outcome", outcome is LoadOutcome.Rejected)
        assertEquals("ABI 闸只判一次", 1, gateCalls)
        assertEquals("Rejected 时类加载器构造次数必须为 0", 0, factory.createdFor.size)
    }

    @Test
    fun `闸的反证 同一空盘把编译面降到引擎版本即走到 NeedsTrust`() {
        val outcome = loaderOf(tempDir())
            .load(TEST_PACKAGE_ID, metaOf(compiledSurface = ENGINE_API_VERSION), ENGINE_API_VERSION)
        assertTrue("闸放行后应先撞信任确认，实际：$outcome", outcome is LoadOutcome.NeedsTrust)
    }

    // ------------------------------------------------------------------
    // 放行面
    // ------------------------------------------------------------------

    @Test
    fun `未声明编译面的老包直接放行并装载`() {
        val rt2 = tempDir()
        trust(rt2)
        writeArtifact(rt2, bytes = PAYLOAD_BYTES)
        val outcome = loaderOf(rt2).load(TEST_PACKAGE_ID, metaOf(compiledSurface = null), ENGINE_API_VERSION)
        val loaded = assertLoaded(outcome)
        assertEquals(TEST_PACKAGE_ID, loaded.packageId)
        assertEquals("Loaded 必须带完整 64 位 sha256", sha256Hex(PAYLOAD_BYTES), loaded.sha256Hex)
        assertSame(ClassLoader.getSystemClassLoader(), loaded.loader)
    }

    @Test
    fun `声明面与引擎同版本时放行并装载`() {
        val rt2 = tempDir()
        trust(rt2)
        writeArtifact(rt2, bytes = PAYLOAD_BYTES)
        val outcome = loaderOf(rt2)
            .load(TEST_PACKAGE_ID, metaOf(compiledSurface = ENGINE_API_VERSION), ENGINE_API_VERSION)
        assertSame(ClassLoader.getSystemClassLoader(), assertLoaded(outcome).loader)
    }

    // ------------------------------------------------------------------
    // 信任面
    // ------------------------------------------------------------------

    @Test
    fun `没有信任标记时 NeedsTrust 且不建类加载器`() {
        val rt2 = tempDir()
        writeArtifact(rt2, bytes = PAYLOAD_BYTES)
        val factory = RecordingFactory()
        val outcome = loaderOf(rt2, factory = factory)
            .load(TEST_PACKAGE_ID, metaOf(compiledSurface = null), ENGINE_API_VERSION)
        assertTrue("未信任必须 NeedsTrust，实际：$outcome", outcome is LoadOutcome.NeedsTrust)
        assertEquals("未信任时类加载器构造次数必须为 0", 0, factory.createdFor.size)
    }

    @Test
    fun `信任闸的反证 补上 trusted 标记后同一盘面走到产物缺失`() {
        val rt2 = tempDir()
        val before = loaderOf(rt2).load(TEST_PACKAGE_ID, metaOf(compiledSurface = null), ENGINE_API_VERSION)
        assertTrue("先声明未信任：$before", before is LoadOutcome.NeedsTrust)
        trust(rt2)
        val after = loaderOf(rt2).load(TEST_PACKAGE_ID, metaOf(compiledSurface = null), ENGINE_API_VERSION)
        assertTrue("补上信任标记后应越过信任闸、撞上产物缺失，实际：$after", after is LoadOutcome.MissingArtifact)
    }

    @Test
    fun `信任已确认但盘上没有产物时 MissingArtifact`() {
        val rt2 = tempDir()
        trust(rt2)
        val outcome = loaderOf(rt2).load(TEST_PACKAGE_ID, metaOf(compiledSurface = null), ENGINE_API_VERSION)
        assertTrue("缺产物必须 MissingArtifact，实际：$outcome", outcome is LoadOutcome.MissingArtifact)
        assertTrue("缺产物必须带可上屏原因", (outcome as LoadOutcome.MissingArtifact).reason.isNotBlank())
    }

    // ------------------------------------------------------------------
    // 内容寻址校验
    // ------------------------------------------------------------------

    @Test
    fun `sha256 与内容寻址文件名不符时 IntegrityFailed 且不建类加载器`() {
        val rt2 = tempDir()
        trust(rt2)
        writeArtifact(rt2, bytes = PAYLOAD_BYTES, fileName = MISMATCH_FILE_NAME)
        val factory = RecordingFactory()
        val outcome = loaderOf(rt2, factory = factory)
            .load(TEST_PACKAGE_ID, metaOf(compiledSurface = null), ENGINE_API_VERSION)
        assertTrue("sha 不符必须 IntegrityFailed，实际：$outcome", outcome is LoadOutcome.IntegrityFailed)
        assertEquals("校验失败时类加载器构造次数必须为 0", 0, factory.createdFor.size)
    }

    @Test
    fun `空产物即使文件名 sha 正确也 IntegrityFailed`() {
        val rt2 = tempDir()
        trust(rt2)
        writeArtifact(rt2, bytes = ByteArray(0))
        val outcome = loaderOf(rt2).load(TEST_PACKAGE_ID, metaOf(compiledSurface = null), ENGINE_API_VERSION)
        assertTrue("空产物必须 IntegrityFailed，实际：$outcome", outcome is LoadOutcome.IntegrityFailed)
    }

    @Test
    fun `盘上多版本而清单未指定引用时拒绝猜`() {
        val rt2 = tempDir()
        trust(rt2)
        writeArtifact(rt2, bytes = PAYLOAD_BYTES)
        writeArtifact(rt2, bytes = OTHER_PAYLOAD_BYTES)
        val factory = RecordingFactory()
        val outcome = loaderOf(rt2, factory = factory)
            .load(TEST_PACKAGE_ID, metaOf(compiledSurface = null), ENGINE_API_VERSION)
        assertTrue("多版本未指定必须拒猜，实际：$outcome", outcome is LoadOutcome.AmbiguousArtifact)
        assertEquals("拒猜时类加载器构造次数必须为 0", 0, factory.createdFor.size)
    }

    @Test
    fun `多版本的反证 清单指定 sha 后精确选中该版本装载`() {
        val rt2 = tempDir()
        trust(rt2)
        writeArtifact(rt2, bytes = PAYLOAD_BYTES)
        val wantedSha256 = sha256Hex(OTHER_PAYLOAD_BYTES)
        writeArtifact(rt2, bytes = OTHER_PAYLOAD_BYTES)
        val outcome = loaderOf(rt2).load(
            TEST_PACKAGE_ID,
            metaOf(compiledSurface = null),
            ENGINE_API_VERSION,
            expectedSha256 = wantedSha256,
        )
        assertEquals("必须按清单引用精确选中版本", wantedSha256, assertLoaded(outcome).sha256Hex)
    }

    @Test
    fun `清单 sha 形态非法时 IntegrityFailed 而不是抛异常`() {
        val rt2 = tempDir()
        trust(rt2)
        writeArtifact(rt2, bytes = PAYLOAD_BYTES)
        val outcome = loaderOf(rt2).load(
            TEST_PACKAGE_ID,
            metaOf(compiledSurface = null),
            ENGINE_API_VERSION,
            expectedSha256 = MALFORMED_SHA,
        )
        assertTrue("sha 形态非法必须数据面收口，实际：$outcome", outcome is LoadOutcome.IntegrityFailed)
    }

    @Test
    fun `清单指定的 sha 在盘上不存在时 MissingArtifact`() {
        val rt2 = tempDir()
        trust(rt2)
        writeArtifact(rt2, bytes = PAYLOAD_BYTES)
        val factory = RecordingFactory()
        val outcome = loaderOf(rt2, factory = factory).load(
            TEST_PACKAGE_ID,
            metaOf(compiledSurface = null),
            ENGINE_API_VERSION,
            expectedSha256 = sha256Hex(OTHER_PAYLOAD_BYTES),
        )
        assertTrue("引用版本不在盘上必须 MissingArtifact，实际：$outcome", outcome is LoadOutcome.MissingArtifact)
        assertEquals(0, factory.createdFor.size)
    }

    // ------------------------------------------------------------------
    // 运行期身份（ADR-013 S6：.codeid 声明的作者 id）
    // ------------------------------------------------------------------

    @Test
    fun `侧车声明作者身份时运行期身份取声明值且工厂按它建 loader`() {
        val rt2 = tempDir()
        sidecar(rt2)
        trust(rt2, DECLARED_PACKAGE_ID)
        writeArtifact(rt2, bytes = PAYLOAD_BYTES, packageId = DISK_PACKAGE_ID)
        val factory = RecordingFactory()
        val loaded = assertLoaded(
            loaderOf(rt2, factory = factory)
                .load(DISK_PACKAGE_ID, metaOf(compiledSurface = null), ENGINE_API_VERSION),
        )
        assertEquals("磁盘身份必须原样保留", DISK_PACKAGE_ID, loaded.diskPackageId)
        assertEquals("运行期身份取侧车声明值", DECLARED_PACKAGE_ID, loaded.runtimePackageId)
        assertEquals("兼容读口指向运行期身份", DECLARED_PACKAGE_ID, loaded.packageId)
        assertEquals("类加载器工厂必须拿运行期身份建 loader", listOf(DECLARED_PACKAGE_ID), factory.createdFor)
    }

    @Test
    fun `无侧车的老包回退磁盘身份且装载路径与改动前一致`() {
        val rt2 = tempDir()
        trust(rt2, DISK_PACKAGE_ID)
        writeArtifact(rt2, bytes = PAYLOAD_BYTES, packageId = DISK_PACKAGE_ID)
        val factory = RecordingFactory()
        val loaded = assertLoaded(
            loaderOf(rt2, factory = factory)
                .load(DISK_PACKAGE_ID, metaOf(compiledSurface = null), ENGINE_API_VERSION),
        )
        assertEquals(DISK_PACKAGE_ID, loaded.diskPackageId)
        assertEquals("无侧车时运行期身份回退磁盘 id", DISK_PACKAGE_ID, loaded.runtimePackageId)
        assertEquals(listOf(DISK_PACKAGE_ID), factory.createdFor)
    }

    @Test
    fun `运行期身份决定信任闸 只有磁盘id的信任标记时 NeedsTrust`() {
        val rt2 = tempDir()
        sidecar(rt2)
        trust(rt2, DISK_PACKAGE_ID)
        writeArtifact(rt2, bytes = PAYLOAD_BYTES, packageId = DISK_PACKAGE_ID)
        val factory = RecordingFactory()
        val outcome = loaderOf(rt2, factory = factory)
            .load(DISK_PACKAGE_ID, metaOf(compiledSurface = null), ENGINE_API_VERSION)
        assertTrue("磁盘 id 的信任标记不得顶替运行期身份的信任，实际：$outcome", outcome is LoadOutcome.NeedsTrust)
        val needsTrust = requireNotNull(outcome as? LoadOutcome.NeedsTrust)
        assertEquals(DECLARED_PACKAGE_ID, needsTrust.runtimePackageId)
        assertEquals("未信任时类加载器构造次数必须为 0", 0, factory.createdFor.size)
    }

    @Test
    fun `信任闸的反证 补上声明身份的信任标记后同一盘面即装载`() {
        val rt2 = tempDir()
        sidecar(rt2)
        trust(rt2, DISK_PACKAGE_ID)
        writeArtifact(rt2, bytes = PAYLOAD_BYTES, packageId = DISK_PACKAGE_ID)
        val before = loaderOf(rt2)
            .load(DISK_PACKAGE_ID, metaOf(compiledSurface = null), ENGINE_API_VERSION)
        assertTrue("先声明按磁盘 id 信任不足以放行：$before", before is LoadOutcome.NeedsTrust)
        trust(rt2, DECLARED_PACKAGE_ID)
        val after = assertLoaded(
            loaderOf(rt2).load(DISK_PACKAGE_ID, metaOf(compiledSurface = null), ENGINE_API_VERSION),
        )
        assertEquals("放行后运行期身份仍是声明值", DECLARED_PACKAGE_ID, after.runtimePackageId)
    }

    @Test
    fun `侧车坏档时 fail-closed 拒载并点名侧车路径`() {
        val rt2 = tempDir()
        trust(rt2, DISK_PACKAGE_ID)
        writeArtifact(rt2, bytes = PAYLOAD_BYTES, packageId = DISK_PACKAGE_ID)
        val sidecarFile = rawSidecar(rt2, "RT2CODEID/1\nnot a valid id!\n")
        val factory = RecordingFactory()
        val outcome = loaderOf(rt2, factory = factory)
            .load(DISK_PACKAGE_ID, metaOf(compiledSurface = null), ENGINE_API_VERSION)
        val corrupt = requireNotNull(outcome as? LoadOutcome.CorruptIdentity)
        assertEquals(DISK_PACKAGE_ID, corrupt.diskPackageId)
        assertNull("坏档不得猜测运行期身份", corrupt.runtimePackageId)
        assertEquals("侧车路径必须原样带出", sidecarFile.absolutePath, corrupt.sidecarPath)
        assertTrue("拒载文案要点名身份侧车损坏：${corrupt.reason}", corrupt.reason.contains("代码身份侧车损坏"))
        assertTrue("拒载文案要点名侧车路径：${corrupt.reason}", corrupt.reason.contains(sidecarFile.absolutePath))
        assertEquals("坏档必须 fail-closed，不得建其 loader", 0, factory.createdFor.size)
    }

    @Test
    fun `侧车坏档的头行 行数 超长三种形状都 fail-closed`() {
        val samples = listOf(
            "RT2CODEID/2\n$DECLARED_PACKAGE_ID\n",
            "RT2CODEID/1\n$DECLARED_PACKAGE_ID",
            "RT2CODEID/1\n" + "a".repeat(300) + "\n",
        )
        for ((index, text) in samples.withIndex()) {
            val rt2 = tempDir()
            trust(rt2, DISK_PACKAGE_ID)
            writeArtifact(rt2, bytes = PAYLOAD_BYTES, packageId = DISK_PACKAGE_ID)
            rawSidecar(rt2, text)
            val outcome = loaderOf(rt2)
                .load(DISK_PACKAGE_ID, metaOf(compiledSurface = null), ENGINE_API_VERSION)
            assertTrue("第 " + index + " 种坏档必须拒载，实际：$outcome", outcome is LoadOutcome.CorruptIdentity)
        }
    }

    @Test
    fun `ABI闸先于身份解析 坏侧车也先判 Rejected`() {
        val rt2 = tempDir()
        rawSidecar(rt2, "RT2CODEID/1\nbroken\n")
        val outcome = loaderOf(rt2)
            .load(DISK_PACKAGE_ID, metaOf(compiledSurface = SURFACE_AHEAD), ENGINE_API_VERSION)
        assertTrue("ABI 闸必须先拒，实际：$outcome", outcome is LoadOutcome.Rejected)
        assertNull("身份未解析时 runtime 必须为空", outcome.runtimePackageId)
    }

    @Test
    fun `总闸关先于身份解析 坏侧车也先判 Disabled 且零工厂`() {
        val rt2 = tempDir()
        trust(rt2, DISK_PACKAGE_ID)
        writeArtifact(rt2, bytes = PAYLOAD_BYTES, packageId = DISK_PACKAGE_ID)
        rawSidecar(rt2, "RT2CODEID/1\nbroken\n")
        val factory = RecordingFactory()
        val outcome = loaderOf(rt2, factory = factory, enabled = false)
            .load(DISK_PACKAGE_ID, metaOf(compiledSurface = null), ENGINE_API_VERSION)
        assertTrue("总闸必须先拒，实际：$outcome", outcome is LoadOutcome.Disabled)
        assertNull(outcome.runtimePackageId)
        assertEquals(0, factory.createdFor.size)
    }

    @Test
    fun `产物定位仍按磁盘id 声明身份的 code 目录不算数`() {
        val rt2 = tempDir()
        sidecar(rt2)
        trust(rt2, DECLARED_PACKAGE_ID)
        writeArtifact(rt2, bytes = PAYLOAD_BYTES, packageId = DECLARED_PACKAGE_ID)
        val factory = RecordingFactory()
        val outcome = loaderOf(rt2, factory = factory)
            .load(DISK_PACKAGE_ID, metaOf(compiledSurface = null), ENGINE_API_VERSION)
        assertTrue("产物必须按磁盘 id 找，实际：$outcome", outcome is LoadOutcome.MissingArtifact)
        assertEquals(0, factory.createdFor.size)
    }

    @Test
    fun `日志一行同时打磁盘id与运行期身份`() {
        val rt2 = tempDir()
        sidecar(rt2)
        trust(rt2, DECLARED_PACKAGE_ID)
        writeArtifact(rt2, bytes = PAYLOAD_BYTES, packageId = DISK_PACKAGE_ID)
        val lines = mutableListOf<String>()
        val outcome = loaderOf(rt2, logSink = { lines += it })
            .load(DISK_PACKAGE_ID, metaOf(compiledSurface = null), ENGINE_API_VERSION)
        assertTrue("应该装载成功，实际：$outcome", outcome is LoadOutcome.Loaded)
        val line = lines.single()
        assertTrue("日志要带磁盘 id：$line", line.contains("disk=$DISK_PACKAGE_ID"))
        assertTrue("日志要带运行期身份：$line", line.contains("runtime=$DECLARED_PACKAGE_ID"))
        assertTrue("日志要带结果：$line", line.contains("outcome=loaded"))
    }

    @Test
    fun `身份未解析的失败面 runtime 为空但磁盘id照打`() {
        val lines = mutableListOf<String>()
        val outcome = loaderOf(tempDir(), logSink = { lines += it }, enabled = false)
            .load(DISK_PACKAGE_ID, metaOf(compiledSurface = null), ENGINE_API_VERSION)
        assertTrue(outcome is LoadOutcome.Disabled)
        assertEquals(DISK_PACKAGE_ID, outcome.diskPackageId)
        assertNull("总闸关时身份未解析", outcome.runtimePackageId)
        val line = lines.single()
        assertTrue("日志要带磁盘 id：$line", line.contains("disk=$DISK_PACKAGE_ID"))
        assertTrue("未解析时 runtime 打占位符：$line", line.contains("runtime=-"))
    }

    // ------------------------------------------------------------------
    // 数据面收口与幂等
    // ------------------------------------------------------------------

    @Test
    fun `类加载器工厂抛异常时 LoaderFailed 收口且不向上抛`() {
        val rt2 = tempDir()
        trust(rt2)
        writeArtifact(rt2, bytes = PAYLOAD_BYTES)
        val factory = RecordingFactory()
        factory.failure = IllegalStateException("boom")
        val outcome = loaderOf(rt2, factory = factory)
            .load(TEST_PACKAGE_ID, metaOf(compiledSurface = null), ENGINE_API_VERSION)
        assertTrue("工厂异常必须收敛成 LoaderFailed，实际：$outcome", outcome is LoadOutcome.LoaderFailed)
        assertFalse("reason 不得泄漏异常细节", reasonOf(outcome).contains("boom"))
    }

    @Test
    fun `重复装载同一包结果一致且盘面不变`() {
        val rt2 = tempDir()
        trust(rt2)
        val artifact = writeArtifact(rt2, bytes = PAYLOAD_BYTES)
        val factory = RecordingFactory()
        val loader = loaderOf(rt2, factory = factory)
        val first = assertLoaded(loader.load(TEST_PACKAGE_ID, metaOf(compiledSurface = null), ENGINE_API_VERSION))
        val second = assertLoaded(loader.load(TEST_PACKAGE_ID, metaOf(compiledSurface = null), ENGINE_API_VERSION))
        assertEquals(first.sha256Hex, second.sha256Hex)
        assertEquals("两次装载各建一次 loader", 2, factory.createdFor.size)
        assertEquals("重复装载不得改动产物字节", sha256Hex(PAYLOAD_BYTES), sha256Hex(artifact.readBytes()))
        assertEquals("重复装载不得新增产物文件", 1, artifact.parentFile?.listFiles()?.size ?: 0)
    }

    @Test
    fun `非法包名直接 InvalidPackage 且不抛异常不建 loader`() {
        val factory = RecordingFactory()
        val outcome = loaderOf(tempDir(), factory = factory)
            .load("../evil", metaOf(compiledSurface = null), ENGINE_API_VERSION)
        assertTrue("非法包名必须数据面收口，实际：$outcome", outcome is LoadOutcome.InvalidPackage)
        assertEquals(0, factory.createdFor.size)
    }

    @Test
    fun `所有失败原因的文案不含内部类名与异常名`() {
        val empty = tempDir()
        val trustedEmpty = tempDir().also { trust(it) }
        val badSha = tempDir().also {
            trust(it)
            writeArtifact(it, bytes = PAYLOAD_BYTES, fileName = MISMATCH_FILE_NAME)
        }
        val multiVersion = tempDir().also {
            trust(it)
            writeArtifact(it, bytes = PAYLOAD_BYTES)
            writeArtifact(it, bytes = OTHER_PAYLOAD_BYTES)
        }
        val loaderFails = tempDir().also {
            trust(it)
            writeArtifact(it, bytes = PAYLOAD_BYTES)
        }
        val corruptIdentity = tempDir().also {
            trust(it, DISK_PACKAGE_ID)
            writeArtifact(it, bytes = PAYLOAD_BYTES, packageId = DISK_PACKAGE_ID)
            rawSidecar(it, "RT2CODEID/1\nbroken\n")
        }
        val failingFactory = RecordingFactory()
        failingFactory.failure = IllegalStateException("boom")
        val meta = metaOf(compiledSurface = null)
        val outcomes = listOf(
            loaderOf(empty, enabled = false).load(TEST_PACKAGE_ID, meta, ENGINE_API_VERSION),
            loaderOf(empty).load(TEST_PACKAGE_ID, meta, ENGINE_API_VERSION),
            loaderOf(trustedEmpty).load(TEST_PACKAGE_ID, meta, ENGINE_API_VERSION),
            loaderOf(badSha).load(TEST_PACKAGE_ID, meta, ENGINE_API_VERSION),
            loaderOf(multiVersion).load(TEST_PACKAGE_ID, meta, ENGINE_API_VERSION),
            loaderOf(loaderFails, factory = failingFactory).load(TEST_PACKAGE_ID, meta, ENGINE_API_VERSION),
            loaderOf(corruptIdentity).load(DISK_PACKAGE_ID, meta, ENGINE_API_VERSION),
            loaderOf(empty).load("../evil", meta, ENGINE_API_VERSION),
        )
        for (outcome in outcomes) {
            assertFalse("这一组都应是失败面，实际：$outcome", outcome is LoadOutcome.Loaded)
            val reason = reasonOf(outcome)
            assertTrue("失败面必须带可上屏 reason，实际：$outcome", reason.isNotBlank())
            for (token in FORBIDDEN_REASON_TOKENS) {
                assertFalse("reason 泄漏内部细节「$token」：$reason", reason.contains(token))
            }
        }
    }

    @Test
    fun `每次装载只记一行日志且日志异常不影响装载结果`() {
        val rt2 = tempDir()
        trust(rt2)
        writeArtifact(rt2, bytes = PAYLOAD_BYTES)
        val lines = mutableListOf<String>()
        val logged = loaderOf(rt2, logSink = { lines += it })
            .load(TEST_PACKAGE_ID, metaOf(compiledSurface = null), ENGINE_API_VERSION)
        assertTrue("应该装载成功，实际：$logged", logged is LoadOutcome.Loaded)
        assertEquals("每次装载只记一行日志", 1, lines.size)
        assertTrue("日志行要带包名", lines.single().contains(TEST_PACKAGE_ID))
        assertTrue("日志行要带结果", lines.single().contains("outcome=loaded"))
        val throwingSink = loaderOf(rt2, logSink = { throw IllegalStateException("log sink 崩了") })
            .load(TEST_PACKAGE_ID, metaOf(compiledSurface = null), ENGINE_API_VERSION)
        assertTrue("日志面异常不得改写装载结果，实际：$throwingSink", throwingSink is LoadOutcome.Loaded)
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private fun tempDir(): File = Files.createTempDirectory("mod-dex-loader").toFile()

    private fun trust(rt2: File, packageId: String = TEST_PACKAGE_ID) {
        File(rt2, ModDexGate.trustMarkerName(packageId)).writeText("")
    }

    /** 用生产写端落一份合法侧车（线格式真源不在这里复刻）。 */
    private fun sidecar(
        rt2: File,
        diskPackageId: String = DISK_PACKAGE_ID,
        declared: String = DECLARED_PACKAGE_ID,
    ) {
        ModCodeIdentity.write(rt2, diskPackageId, declared)
    }

    /** 手写坏侧车字节（头不对 / 行数不对 / 超长都用它）。 */
    private fun rawSidecar(rt2: File, text: String, diskPackageId: String = DISK_PACKAGE_ID): File =
        File(rt2, ModCodeIdentity.fileName(diskPackageId)).also { it.writeText(text) }

    private fun writeArtifact(
        rt2: File,
        bytes: ByteArray,
        packageId: String = TEST_PACKAGE_ID,
        fileName: String = sha256Hex(bytes).take(SHA_PREFIX_LENGTH) + ".mod",
    ): File {
        val dir = File(File(rt2, MOD_CODE_DIR_NAME), packageId)
        dir.mkdirs()
        val file = File(dir, fileName)
        file.writeBytes(bytes)
        return file
    }

    private fun metaOf(compiledSurface: Int?): PackMeta =
        PackMeta(
            packageId = TEST_PACKAGE_ID,
            declaredVersion = "2.0",
            version = "2.0",
            ignored = null,
            evidence = PackMetaEvidence(compiledSurface = compiledSurface),
        )

    private fun loaderOf(
        rt2: File,
        factory: RecordingFactory = RecordingFactory(),
        enabled: Boolean = true,
        gate: (PackMeta, Int, ModDexGate.SurfaceCompat) -> ModDexGate.Verdict = ModDexGate::verdict,
        logSink: (String) -> Unit = {},
    ): ModDexLoader = ModDexLoader(
        codeStore = ModCodeStore(rt2),
        gate = gate,
        factory = factory,
        logSink = logSink,
        dexLoaderEnabled = enabled,
    )

    private fun assertLoaded(outcome: LoadOutcome): LoadOutcome.Loaded {
        assertTrue("期望 Loaded，实际：$outcome", outcome is LoadOutcome.Loaded)
        return requireNotNull(outcome as? LoadOutcome.Loaded)
    }

    private fun reasonOf(outcome: LoadOutcome): String = when (outcome) {
        is LoadOutcome.Loaded -> ""
        is LoadOutcome.Disabled -> outcome.reason
        is LoadOutcome.Rejected -> outcome.reason
        is LoadOutcome.NeedsTrust -> outcome.reason
        is LoadOutcome.InvalidPackage -> outcome.reason
        is LoadOutcome.MissingArtifact -> outcome.reason
        is LoadOutcome.AmbiguousArtifact -> outcome.reason
        is LoadOutcome.IntegrityFailed -> outcome.reason
        is LoadOutcome.IoFailed -> outcome.reason
        is LoadOutcome.LoaderFailed -> outcome.reason
        is LoadOutcome.CorruptIdentity -> outcome.reason
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(separator = "") { "%02x".format(it) }

    private class RecordingFactory : DexLoaderFactory {
        val createdFor = mutableListOf<String>()
        var failure: RuntimeException? = null

        override fun create(packageId: String, artifactFile: File): ClassLoader {
            createdFor += packageId
            failure?.let { throw it }
            return ClassLoader.getSystemClassLoader()
        }
    }
}
