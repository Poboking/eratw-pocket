package com.eratw.port.android

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.mod.CompiledModule
import com.eratw.port.core.mod.ErbModuleContainer
import com.eratw.port.core.patch.LinkOrderException
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.patch.ModuleDescriptor
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.modapi.ModApi
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * ModDX-2 DX2-B：`ModChainAssembly` 装配期**引擎 API 版本闸**判据（契约 §2 判据 2/3）。
 *
 * - 达标（minApi ≤ ModApi.VERSION）与缺席段（缺省 0 = 不设闸）⇒ 正常装配；
 * - 任一包 minApi > ModApi.VERSION ⇒ [LinkOrderException] 整链拒载，消息逐包归因
 *   （包名 + 需要的 min-api + 当前 VERSION）；
 * - 反向证明 ≥2（契约判据 3）：把超版包的 min-api 声明摘除（经 codec 重出 `minApi=0` 的
 *   等价 manifest，闸失去输入）⇒ 同一份超版包**入链成功**（函数出现在链接产物表 = 闸要拦
 *   的坏事真实发生）；恢复原字节（sha-256 与摘除前一致，证明翻转只由 min-api 声明决定、
 *   逐字节确定）⇒ 闸复绿（重新拒载）。
 *
 * 版本读数全部动态取 [ModApi.VERSION]（组长随收口递增 VERSION 时本测试不需要改写）。
 */
class ModChainAssemblyMinApiGateTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** base：ENTRY@0 / TARGET@1，各一条 _RET。 */
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

    /** Mod 包只加新函数 ANEW（无覆盖声明 ⇒ 免签名真目录夹具）。 */
    private fun writeContainer(root: File, packageId: String, fileName: String) {
        val dir = File(root, "mods/rt2").apply { mkdirs() }
        val b = CodeBuilder()
        val entry = b.declareFunction("ANEW", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 0)
        val cm = CompiledModule(
            packageId,
            b.build(entry),
            listOf(FuncMeta(index = 0, key = "ANEW")),
            emptyMap(),
        )
        File(dir, fileName).writeBytes(ErbModuleContainer.encode(cm, allowSuperCalls = true))
    }

    private fun manifestBytes(packageId: String, minApi: Int = 0): ByteArray =
        ManifestCodec.encode(ModuleDescriptor(packageId = packageId, minApi = minApi))

    private fun writeManifest(root: File, packageId: String, fileName: String, minApi: Int = 0) {
        val dir = File(root, "mods/rt2").apply { mkdirs() }
        File(dir, fileName).writeBytes(manifestBytes(packageId, minApi))
    }

    private fun manifestFile(root: File, fileName: String): File = File(root, "mods/rt2/$fileName")

    private fun assemble(root: File) =
        ModChainAssembly.assemble(ChainAssemblyInputs(root, true, base(), baseMetas))

    /**
     * 「闸摘除」夹具：经 codec 自身重出**未声明 min-api** 的等价 manifest
     * （decode → `copy(minApi = 0)` → encode，magic 由 /6 归位 /3、段表由编码器统一裁决）。
     * 不手工删段：`/6` 的段数判定写死（20 段），删掉末段不改首行会让读侧在声明结束前截断
     * 拒载 —— 那是坏档而不是「没声明 min-api」。
     */
    private fun stripMinApiDeclaration(bytes: ByteArray): ByteArray =
        ManifestCodec.encode(ManifestCodec.decode(bytes).copy(minApi = 0))

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xff
            sb.append("0123456789abcdef"[v ushr 4]).append("0123456789abcdef"[v and 0x0f])
        }
        return sb.toString()
    }

    private fun assertGateRejects(root: File, expectedPkg: String, requiredMinApi: Int) {
        try {
            assemble(root)
            fail("任一包 minApi > ModApi.VERSION 必须整链拒载")
        } catch (e: LinkOrderException) {
            val m = e.message!!
            assertTrue("消息须点名包名，实得：$m", m.contains(expectedPkg))
            assertTrue("消息须带需要的 min-api，实得：$m", m.contains(requiredMinApi.toString()))
            assertTrue("消息须带当前 VERSION，实得：$m", m.contains("ModApi.VERSION ${ModApi.VERSION}"))
            assertTrue("消息须给「需要更新 app」归因，实得：$m", m.contains("请更新 app"))
        }
    }

    // ------------------------------------------------------------------
    // 判据 3：闸测试 ≥3 例
    // ------------------------------------------------------------------

    @Test
    fun `minApi达标时正常装配且函数入链`() {
        val root = tmp.newFolder("game")
        writeManifest(root, "mod.a", "a.manifest", minApi = ModApi.VERSION)
        writeContainer(root, "mod.a", "a.erbm")
        val value = assemble(root)
        assertNotNull("minApi == 当前 VERSION 必须放行（双闭达标）", value)
        assertTrue(
            "达标包函数必须在链接产物里",
            value!!.functionTable.all().any { it.key == "ANEW" },
        )
    }

    @Test
    fun `超版包整链拒载且消息带包名与双版本`() {
        val root = tmp.newFolder("game")
        writeManifest(root, "mod.a", "a.manifest", minApi = ModApi.VERSION + 1)
        writeContainer(root, "mod.a", "a.erbm")
        assertGateRejects(root, "mod.a", ModApi.VERSION + 1)
    }

    @Test
    fun `缺席minApi段按缺省0不设闸照常装配`() {
        val root = tmp.newFolder("game")
        writeManifest(root, "mod.a", "a.manifest", minApi = 0)
        writeContainer(root, "mod.a", "a.erbm")
        val value = assemble(root)
        assertNotNull("旧 manifest（无 min-api 段）必须零闸通过", value)
        assertTrue(value!!.functionTable.all().any { it.key == "ANEW" })
    }

    // ------------------------------------------------------------------
    // 反向证明 ≥2：闸摘除 → 超版包入链红；恢复 sha 一致 → 复绿
    // ------------------------------------------------------------------

    /**
     * 反向证明①（容器形态超版包）：摘除 min-api 声明后同一份包入链成功（函数出现在链接
     * 产物表 —— 闸要拦的坏事真实发生）；恢复原字节（sha-256 与摘除前一致）后闸复绿。
     */
    @Test
    fun `反向证明①_闸摘除则超版包入链_恢复sha一致复绿`() {
        val root = tmp.newFolder("game")
        writeManifest(root, "mod.a", "a.manifest", minApi = ModApi.VERSION + 1)
        writeContainer(root, "mod.a", "a.erbm")
        val original = manifestFile(root, "a.manifest").readBytes()
        val originalSha = sha256(original)
        assertGateRejects(root, "mod.a", ModApi.VERSION + 1)

        // 闸摘除（min-api 声明不在场 = minApi 缺省 0 = 不设闸）：超版包入链 = 危害发生。
        manifestFile(root, "a.manifest").writeBytes(stripMinApiDeclaration(original))
        val value = assemble(root)
        assertNotNull("闸摘除后装配必须放行（证明闸是唯一拦点）", value)
        assertTrue(
            "超版包函数必须在链接产物里（入链红 = 闸缺失时的真实后果）",
            value!!.functionTable.all().any { it.key == "ANEW" },
        )

        // 恢复原字节（sha 一致）⇒ 闸复绿：重新拒载。
        manifestFile(root, "a.manifest").writeBytes(original)
        val restored = manifestFile(root, "a.manifest").readBytes()
        assertTrue("恢复后 sha-256 必须与摘除前一致", sha256(restored) == originalSha)
        assertGateRejects(root, "mod.a", ModApi.VERSION + 1)
    }

    /**
     * 反向证明②（载体形态超版包 + 达标容器包共存）：闸对**全部启用 manifest** 生效，
     * 载体包（无容器）超版同样整链拒载；摘除载体的 min-api 段后装配放行且链只装配
     * 达标容器包（载体跳过 = 既有单向配对语义不变）；恢复 sha 一致后闸复绿。
     */
    @Test
    fun `反向证明②_载体manifest超版同样拒载_摘除后仅达标包入链`() {
        val root = tmp.newFolder("game")
        writeManifest(root, "mod.carrier", "carrier.manifest", minApi = ModApi.VERSION + 1)
        writeManifest(root, "mod.demo", "demo.manifest", minApi = 0)
        writeContainer(root, "mod.demo", "demo.erbm")
        val original = manifestFile(root, "carrier.manifest").readBytes()
        val originalSha = sha256(original)
        assertGateRejects(root, "mod.carrier", ModApi.VERSION + 1)

        manifestFile(root, "carrier.manifest").writeBytes(stripMinApiDeclaration(original))
        val value = assemble(root)
        assertNotNull("载体摘除 min-api 后装配必须放行", value)
        assertTrue(
            "达标容器包函数必须在链接产物里",
            value!!.functionTable.all().any { it.key == "ANEW" },
        )

        manifestFile(root, "carrier.manifest").writeBytes(original)
        val restored = manifestFile(root, "carrier.manifest").readBytes()
        assertTrue("恢复后 sha-256 必须与摘除前一致", sha256(restored) == originalSha)
        assertGateRejects(root, "mod.carrier", ModApi.VERSION + 1)
    }
}
