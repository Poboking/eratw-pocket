package com.eratw.port.android.market

import com.eratw.port.android.BaseCompileContext
import com.eratw.port.android.ModImportController
import com.eratw.port.android.ModPackageInventory
import com.eratw.port.android.ModRuntimeAssembly
import com.eratw.port.android.ModSettingsStore
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.platform.storage.ModStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * 市场桥「第三处删除面」的定向单测（task-98）：换包与卸载联动都要把旧包的全套清干净，
 * 不只 import 四件套 —— `<packageId>.settings` 设置侧车与 `<packageId>.assets/` 资源整树
 * 同样是旧包的盘上遗体，漏删会让同内容包装回时旧设置静默复活。
 *
 * 夹具照 [MarketGameBridgeTest] 的真链口径（mini 侧车 + 合成计数 + 真导入链 + 真
 * MarketVerifierImpl），hermetic 零语料依赖；删除面断言直接看 `mods/rt2/` 的盘上真相。
 *
 * 反向证明锚：把 [MarketGameBridge.deletePackageArtifacts] 里的侧车两段删除摘掉 ⇒
 * `换包删净旧包设置与资源目录` / `卸载联动删净设置与资源目录` 两条用例红。
 */
class MarketGameBridgeSidecarCleanTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var marketRoot: File
    private lateinit var gameRoot: File

    @Before
    fun setUp() {
        marketRoot = tmp.newFolder("market")
        gameRoot = tmp.newFolder("game")
    }

    // ------------------------------------------------------------------
    // 夹具（MarketGameBridgeTest.kt:57-202 同源形状，只留本文件用到的部分）
    // ------------------------------------------------------------------

    /** mini base 的侧车：A_ONE/A_TWO/A_SUM/A_DEF 函数表 + A_SUM 整型方法。 */
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

    /** 合成 base 基线计数（六个读数自洽即可）。 */
    private fun miniBaseCounts(): com.eratw.port.core.compile.PatchCompiler.BaseCounts =
        com.eratw.port.core.compile.PatchCompiler.BaseCounts(
            instructionCount = 4000,
            functionCount = 4,
            statementCount = 100,
            stringCount = 10,
            longCount = 5,
            exprCount = 20,
        )

    /** 不引用 base 池条目/用户变量的补丁源（符号面只要 A_ONE 在函数表里）。 */
    private val patchSource = """
        @B_HELLO
        CALL A_ONE(3)
        PRINTL HELLO_PATCH
        RETURN 0
    """.trimIndent() + "\n"

    /** 换文件集用的第二份补丁源（不同函数名与不同源名 ⇒ packageId 必变）。 */
    private val patchSourceV2 = """
        @B_SECOND
        CALL A_TWO(1)
        RETURN 0
    """.trimIndent() + "\n"

    /** 真链桥：控制器照 SettingsActivity 的随用随建形态在工厂里现造。 */
    private fun newBridge(): MarketGameBridge = MarketGameBridge(
        marketRoot = marketRoot,
        gameRoot = gameRoot,
        controllerFactory = { root ->
            ModImportController(
                modStore = ModStore(ModStore.modsDirFor(root)),
                baseSource = {
                    BaseCompileContext(miniMetadata(), miniBaseCounts(), BASE_SHA, csvDir = null)
                },
                gameRoot = root,
            )
        },
        verifier = MarketVerifierImpl(),
    )

    private fun ledger(): MarketImportsLedger =
        MarketImportsLedger(File(marketRoot, MarketImportsLedger.FILE_NAME))

    private fun rt2Dir(): File = ModRuntimeAssembly.manifestDirFor(gameRoot)

    /**
     * 真实导入产物五件（非代码包）：`.sources` / `.sources.sha256` / `.manifest` / `.erbm` /
     * `.assetmap`。`.codeid` 是只有带 `code.mod` 的包才有的附加侧车，`.settings` / `.trusted`
     * 同样是侧车，都不在本判据里。这里显式列出而不走
     * [ModPackageInventory.memberNames]（后者是「全部包成员」名单，会随侧车增减漂移）。
     */
    private fun importedArtifacts(packageId: String): List<String> = listOf(
        packageId + ModPackageInventory.SOURCES_EXTENSION,
        packageId + ModPackageInventory.FINGERPRINT_EXTENSION,
        packageId + ModPackageInventory.MANIFEST_EXTENSION,
        packageId + ModPackageInventory.MODULE_EXTENSION,
        packageId + ModPackageInventory.ASSETMAP_EXTENSION,
    )

    private fun utf8(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    /** 成功出口统一断言：不是 Ok 就 AssertionError。 */
    private fun okOrThrow(outcome: MarketBridgeOutcome): MarketBridgeOutcome.Ok =
        outcome as? MarketBridgeOutcome.Ok
            ?: throw AssertionError("导入应成功，实为 $outcome")

    /**
     * 在市场库里装一个包：pack.json + files 树真实落盘（逐文件 sha256 按真实字节算，
     * 清单要过真 MarketVerifierImpl 的解析闸），返回记账条目。
     */
    private fun installPack(
        kind: String,
        id: String,
        version: String,
        files: List<Pair<String, ByteArray>>,
    ): InstalledPack {
        val dir = File(File(File(File(marketRoot, MarketContract.PACKS_DIR), kind), id), version)
        val entries = ArrayList<String>(files.size)
        var total = 0L
        for ((rel, bytes) in files) {
            val target = File(dir, MarketContract.CONTENT_DIR + "/" + rel)
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
            entries += "{\"path\":\"" + MarketContract.CONTENT_DIR + "/" + rel + "\",\"bytes\":" +
                bytes.size + ",\"sha256\":\"" + sha256Hex(bytes) + "\"}"
            total += bytes.size
        }
        val packJson = "{\"schema\":" + MarketContract.SCHEMA_VERSION +
            ",\"kind\":\"" + kind + "\",\"id\":\"" + id + "\",\"version\":\"" + version + "\"," +
            "\"name\":\"测试包\",\"author\":\"tester\",\"license\":\"MIT\"," +
            "\"redistribute\":true,\"minAppVersion\":\"0.0.1\",\"files\":[" +
            entries.joinToString(",") + "]}"
        File(dir, MarketContract.MANIFEST_FILE).writeText(packJson, Charsets.UTF_8)
        return InstalledPack(
            kind = kind,
            id = id,
            version = version,
            name = "测试包",
            author = "tester",
            license = "MIT",
            installedAtMs = System.currentTimeMillis(),
            containerSha256 = null,
            contentDigest = null,
            fileCount = files.size,
            bytes = total,
        )
    }

    /** 64 位小写 hex 的 SHA-256。 */
    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString(separator = "") { b -> "%02x".format(b.toInt() and BYTE_MASK) }

    // ------------------------------------------------------------------
    // 盘上遗体夹具：设置侧车、资源整树、别的包与内置资产的负证
    // ------------------------------------------------------------------

    /** 写出 `<pkg>.settings`（内容任意，本笔只认路径）。 */
    private fun putSettings(packageId: String, text: String = "volume=7\n") {
        val file = File(rt2Dir(), ModSettingsStore.memberName(packageId))
        file.parentFile?.mkdirs()
        file.writeText(text, Charsets.UTF_8)
    }

    /** 写出 `<pkg>.assets/` 整树（含子目录，验证 deleteRecursively）。 */
    private fun putAssets(packageId: String) {
        val dir = File(rt2Dir(), packageId + ModPackageInventory.ASSET_DIR_SUFFIX)
        File(dir, "img").mkdirs()
        File(dir, "img/portrait.png").writeBytes(byteArrayOf(1, 2, 3))
    }

    private fun settingsOf(packageId: String): File =
        File(rt2Dir(), ModSettingsStore.memberName(packageId))

    private fun assetsOf(packageId: String): File =
        File(rt2Dir(), packageId + ModPackageInventory.ASSET_DIR_SUFFIX)

    private companion object {
        /** 合成 base sha（64 位小写十六进制）。 */
        private val BASE_SHA = "a".repeat(64)

        private const val BYTE_MASK = 0xFF
    }

    // ------------------------------------------------------------------
    // 判据 1：换包（recordImport 删旧）删净旧包设置 + 资源整树
    // ------------------------------------------------------------------

    @Test
    fun `换包删净旧包设置与资源目录`() {
        val bridge = newBridge()
        val v1 = installPack("kojo-pack", "demo", "1.0.0", listOf("KOJO.ERB" to utf8(patchSource)))
        val idA = okOrThrow(bridge.importInstalled(v1, true)).packageId

        // 旧包的盘上遗体：设置侧车（设置页写的）+ 资源整树；再摆一个「别的包」与内置资产当负证。
        putSettings(idA)
        putAssets(idA)
        val otherId = "kojo-deadbeef"
        putSettings(otherId, "volume=1\n")
        putAssets(otherId)
        File(rt2Dir(), "eratw.builtin" + ModPackageInventory.ASSET_DIR_SUFFIX).mkdirs()

        // 换文件集 ⇒ packageId 必变 ⇒ 走 recordImport 的旧包删除分支。
        val v2 = installPack("kojo-pack", "demo", "1.1.0", listOf("KOJO_V2.ERB" to utf8(patchSourceV2)))
        val idB = okOrThrow(bridge.importInstalled(v2, true)).packageId
        assertTrue("换文件集 ⇒ 身份必变（$idA vs $idB）", idA != idB)

        assertFalse("旧包设置侧车必须删净", settingsOf(idA).exists())
        assertFalse("旧包资源整树必须删净（含子目录）", assetsOf(idA).exists())
        assertTrue("负证：别的包设置零触碰", settingsOf(otherId).isFile)
        assertTrue("负证：别的包资源零触碰", File(assetsOf(otherId), "img/portrait.png").isFile)
        assertTrue(
            "负证：内置 eratw.* 资产零触碰",
            File(rt2Dir(), "eratw.builtin" + ModPackageInventory.ASSET_DIR_SUFFIX).isDirectory,
        )
        assertEquals("台账改指新包", idB, ledger().lookup("kojo-pack", "demo")?.packageId)
    }

    // ------------------------------------------------------------------
    // 判据 2：末版本卸载联动（removeArtifacts 删）删净设置 + 资源整树
    // ------------------------------------------------------------------

    @Test
    fun `卸载联动删净设置与资源目录`() {
        val bridge = newBridge()
        val pack = installPack("kojo-pack", "demo", "1.0.0", listOf("KOJO.ERB" to utf8(patchSource)))
        val packageId = okOrThrow(bridge.importInstalled(pack, true)).packageId
        putSettings(packageId)
        putAssets(packageId)
        val otherId = "kojo-deadbeef"
        putSettings(otherId, "volume=1\n")
        putAssets(otherId)

        assertEquals(
            "末版本联动 = Cleaned",
            BridgeSyncOutcome.Cleaned(packageId),
            bridge.syncUninstalled("kojo-pack", "demo", 0),
        )
        assertFalse("设置侧车必须删净", settingsOf(packageId).exists())
        assertFalse("资源整树必须删净", assetsOf(packageId).exists())
        assertTrue("负证：别的包设置零触碰", settingsOf(otherId).isFile)
        assertTrue("负证：别的包资源零触碰", File(assetsOf(otherId), "img/portrait.png").isFile)
        assertNull("台账清掉", ledger().lookup("kojo-pack", "demo"))
    }

    // ------------------------------------------------------------------
    // 判据 3：removed 计数只数导入产物 —— 只剩侧车时仍判 Kept(GamesAbsent)
    // ------------------------------------------------------------------

    @Test
    fun `只剩侧车时产物的 removed 计数仍为 0 判 Kept GamesAbsent`() {
        val bridge = newBridge()
        val pack = installPack("kojo-pack", "demo", "1.0.0", listOf("KOJO.ERB" to utf8(patchSource)))
        val packageId = okOrThrow(bridge.importInstalled(pack, true)).packageId
        // 模拟 gc 已把导入产物清掉、只剩设置与资源遗体。
        for (name in importedArtifacts(packageId)) {
            assertTrue("前提：产物删掉 $name", File(rt2Dir(), name).delete())
        }
        putSettings(packageId)
        putAssets(packageId)

        assertEquals(
            "侧车不算 removed ⇒ 零产物可删 = Kept(GamesAbsent)",
            BridgeSyncOutcome.Kept(BridgeSyncOutcome.Cause.GamesAbsent),
            bridge.syncUninstalled("kojo-pack", "demo", 0),
        )
        assertFalse("机会性清理：残留设置也删净", settingsOf(packageId).exists())
        assertFalse("机会性清理：残留资源也删净", assetsOf(packageId).exists())
        assertNotNull("Kept(GamesAbsent) 不清台账（安全态）", ledger().lookup("kojo-pack", "demo"))
    }

    // ------------------------------------------------------------------
    // 判据 4：侧车删除失败 = Problem（与导入产物同口径，留重试面）
    // ------------------------------------------------------------------

    @Test
    fun `设置侧车删不掉时折 Failed 台账不清且恢复后重试删净侧车`() {
        // 逼败手段 = 句柄锁（与 MarketGameBridgeTest 判据5 同口径）：FileOutputStream 打开的
        // 文件没有 FILE_SHARE_DELETE，Windows 上 File.delete() 必败；Unix 上不成立 ⇒ 显式跳过。
        assumeTrue(
            "句柄锁逼败只在 Windows 成立",
            System.getProperty("os.name")?.contains("windows", ignoreCase = true) == true,
        )
        val bridge = newBridge()
        val pack = installPack("kojo-pack", "demo", "1.0.0", listOf("KOJO.ERB" to utf8(patchSource)))
        val packageId = okOrThrow(bridge.importInstalled(pack, true)).packageId
        putSettings(packageId)

        val lock = FileOutputStream(settingsOf(packageId))
        try {
            val outcome = bridge.syncUninstalled("kojo-pack", "demo", 0)
            val failed = outcome as? BridgeSyncOutcome.Failed
                ?: throw AssertionError("删不掉侧车必须折 Failed，实为 $outcome")
            assertTrue("人话必须点名删不掉的件：${failed.problem}", failed.problem.isNotEmpty())
            assertNotNull("台账不清（保重试面）", ledger().lookup("kojo-pack", "demo"))
        } finally {
            lock.close()
        }

        // 重试：导入四件套已在上一轮删完 ⇒ removed == 0 ⇒ 按契约判 Kept(GamesAbsent)（台账
        // 残留行是 §0.3 已 Bless 的安全态），但侧车本身必须在这一轮删净 —— 不能因为「零产物」
        // 就跳过侧车清理。钉住「removed 不数侧车」与「侧车机会性清理」两条语义的叠加态。
        assertEquals(
            "产物已删完的重试 = Kept(GamesAbsent)",
            BridgeSyncOutcome.Kept(BridgeSyncOutcome.Cause.GamesAbsent),
            bridge.syncUninstalled("kojo-pack", "demo", 0),
        )
        assertFalse("重试后侧车清完", settingsOf(packageId).exists())
        assertNotNull("Kept(GamesAbsent) 不清台账（安全态，下次同包装回覆盖自愈）", ledger().lookup("kojo-pack", "demo"))
    }

    // ------------------------------------------------------------------
    // 判据 5：名字拼装同源（源级哨兵，照 CX 波口径）
    // ------------------------------------------------------------------

    @Test
    fun `源码哨兵 删除面名字全部引用同源出口且无侧车字面量`() {
        val source = sourceOfMain("market/MarketGameBridge.kt")
        assertTrue(
            "容器三件套必须引用 ModPackageInventory 同源后缀常量",
            listOf("SOURCES_EXTENSION", "MANIFEST_EXTENSION", "MODULE_EXTENSION").all {
                source.contains("ModPackageInventory.$it")
            },
        )
        assertFalse(
            "附加侧车不参与容器三件套判据（不许拿 memberNames 顶替）",
            source.contains("ModPackageInventory.memberNames(packageId)"),
        )
        assertTrue(
            "设置侧车名必须引用 ModSettingsStore.memberName",
            source.contains("ModSettingsStore.memberName(packageId)"),
        )
        assertTrue(
            "资源目录后缀必须引用 ModPackageInventory.ASSET_DIR_SUFFIX",
            source.contains("ModPackageInventory.ASSET_DIR_SUFFIX"),
        )
        assertFalse("不许内联设置后缀字面量", source.contains("\".settings\""))
        assertFalse("不许内联资源目录后缀字面量", source.contains("\".assets\""))
    }

    /** 读仓库主源码：仓库根用 `user.dir` 起算（app 模块目录或仓库根两种测试启动口径）。 */
    private fun sourceOfMain(relative: String): String {
        val root = System.getProperty("user.dir") ?: throw AssertionError("user.dir 缺失")
        val candidates = listOf(
            File(root, "app/src/main/kotlin/com/eratw/port/android/$relative"),
            File(root, "src/main/kotlin/com/eratw/port/android/$relative"),
            File(root, "eratw-android/app/src/main/kotlin/com/eratw/port/android/$relative"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: throw AssertionError("找不到主源码 $relative，试过：${candidates.map { it.path }}")
        return file.readText(Charsets.UTF_8)
    }
}
