package com.eratw.port.android

import com.eratw.port.core.compile.PatchCompiler
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.mod.ModResourceStore
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.platform.storage.ModStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模块包（`modgen pack-mod` 产物）→ app 导入端 → 运行期 `ModResourceStore` 读回的端到端用例。
 *
 * 治的缺陷：pack-mod 的 zip 里资源条目是 `"<模块源码 PACKAGE_ID>.assets/..."`，而 app 导入时
 * packageId 是导入端算的 `kojo-<hash>`；旧实现要求前缀严格相等 ⇒ pack-mod 的包一导入资源全丢。
 * 本用例钉住新口径：任意合法来源目录名重挂到目标 packageId，且运行期按目标 packageId 读得到。
 *
 * ## 为什么用「同合同布局的自建 zip」而不是真跑 pack-mod
 *
 * `pack-mod` 的实现在 `tools/modgen` 里是 `internal object PackMod`（`PackMod.kt:34`）与
 * `internal object PackModZip`，跨 Gradle 模块对 `:app` 测试源集不可见；唯一公开入口是
 * `ManifestBuild.kt:44` 的顶层 `main`，它对 `pack-mod` 直接 `exitProcess`
 * （`ManifestBuild.kt:53-55`）会杀掉测试 JVM；本 lane 的任务书又禁止改 `tools/modgen` 去暴露
 * public API。⇒ 按 `PackMod.kt:12-32` 的合同原文构造夹具，逐条对齐：
 *
 * 1. zip 内源路径 = 相对模块根的 `/` 分隔路径，只含 `.ERB`/`.ERH`（`PackMod.kt:17-18`）；
 * 2. 资源条目 = `"<模块源码 PACKAGE_ID>.assets/<相对路径>"`（`PackMod.kt:19-23`）；
 * 3. 条目按路径字典序、时间戳固定 1980-01-01、不写目录条目、两次打包字节相同（`PackMod.kt:26-27`）；
 * 4. 条目名无 `..` / 绝对路径 / 反斜杠 / 控制字符（`PackMod.kt:28-30`）。
 *
 * TODO(verify)：`tools/modgen` 将来若暴露 public 的 pack API（或 app 侧获准加 `:tools:modgen`
 * 测试依赖并由其公开入口返回 zip 字节），本用例应改为真跑 pack-mod 出包再导入，删掉下面的
 * [packModLikeZip] 夹具。
 */
class ModImportPackModEndToEndTest {

    // ------------------------------------------------------------------
    // 1. 主链：模块包 zip ⇒ 资源重挂到 kojo-<hash>.assets/ ⇒ 运行期读得到
    // ------------------------------------------------------------------

    @Test
    fun `模块包zip——资源重挂到目标packageId且运行期按该包读到原字节`() {
        val tmp = Files.createTempDirectory("eratw-modpacke2e").toFile()
        val controller = controllerIn(tmp)
        val zip = packModLikeZip(
            "main.ERB" to PATCH_SOURCE.toByteArray(Charsets.UTF_8),
            "$MODULE_PACKAGE_ID.assets/img/logo.png" to PNG,
            "$MODULE_PACKAGE_ID.assets/sub/deep/data.bin" to byteArrayOf(1, 2, 3),
        )

        val detail = imported(controller.import(listOf(PickedModFile("eratw-demopack.ZIP", zip))))

        // 目标 packageId 是导入端算的 kojo-<hash>，必然不等于模块包源码里的 PACKAGE_ID。
        val targetPackageId = detail.patchFileName.removeSuffix(ModPackageInventory.MODULE_EXTENSION)
        assertNotEquals("目标 packageId 必须由导入端派生", MODULE_PACKAGE_ID, targetPackageId)
        assertTrue("目标 packageId 形态：$targetPackageId", targetPackageId.startsWith("kojo-"))

        // 落盘口径：mods/rt2/<目标 packageId>.assets/<相对路径>。
        assertEquals("两条资源都应计数", 2, detail.resourceEntryCount)
        val assets = assetDirOf(tmp, targetPackageId)
        assertTrue("资源目录应落在目标包名下：$assets", assets.isDirectory)
        assertArrayEquals("图片字节原样", PNG, File(assets, "img/logo.png").readBytes())
        assertArrayEquals("嵌套资源字节原样", byteArrayOf(1, 2, 3), File(assets, "sub/deep/data.bin").readBytes())
        assertFalse("不得按模块包 PACKAGE_ID 建目录", File(rt2Of(tmp), "$MODULE_PACKAGE_ID.assets").exists())

        // 运行期读口径（同一份合同）：ModResourceStore 按目标 packageId 装配后读得回原字节。
        val api = ModResourceStore(rt2Of(tmp)).apiFor(targetPackageId)
        assertArrayEquals("运行期按目标 packageId 读回", PNG, api.open("img/logo.png")!!)
        assertArrayEquals("运行期嵌套路径读回", byteArrayOf(1, 2, 3), api.open("sub/deep/data.bin")!!)

        // 重挂必须留一条玩家可见 WARN（点名来源目录名），不静默搬。
        val warnings = detail.resourceWarnings
        assertEquals("重挂汇总成一条 WARN：$warnings", 1, warnings.size)
        assertTrue("WARN 点名来源目录名：${warnings[0]}", warnings[0].contains(MODULE_PACKAGE_ID))
        assertTrue("临时目录不得残留：${residueOf(tmp)}", residueOf(tmp).isEmpty())
    }

    // ------------------------------------------------------------------
    // 2. 回归：模块包重复导入仍按包级替换（重挂不改变既有替换语义）
    // ------------------------------------------------------------------

    @Test
    fun `模块包zip——重复导入按包级替换且旧资源消失`() {
        val tmp = Files.createTempDirectory("eratw-modpacke2e").toFile()
        val controller = controllerIn(tmp)
        val source = PATCH_SOURCE.toByteArray(Charsets.UTF_8)
        val first = packModLikeZip(
            "main.ERB" to source,
            "$MODULE_PACKAGE_ID.assets/old.png" to PNG,
        )
        val firstDetail = imported(controller.import(listOf(PickedModFile("eratw-demopack_v1.ZIP", first))))
        val packageId = firstDetail.patchFileName.removeSuffix(ModPackageInventory.MODULE_EXTENSION)
        val assets = assetDirOf(tmp, packageId)
        assertTrue("第一版资源应在", File(assets, "old.png").isFile)

        val second = packModLikeZip(
            "main.ERB" to source,
            "$MODULE_PACKAGE_ID.assets/new.png" to byteArrayOf(7, 7, 7),
        )
        val secondDetail = imported(controller.import(listOf(PickedModFile("eratw-demopack_v2.ZIP", second))))

        assertEquals("第二版只有一条资源", 1, secondDetail.resourceEntryCount)
        assertTrue("新版资源应在", File(assets, "new.png").isFile)
        assertFalse("旧版多余文件必须消失（整目录替换）", File(assets, "old.png").exists())
        assertArrayEquals("新版字节原样", byteArrayOf(7, 7, 7), File(assets, "new.png").readBytes())
        assertTrue("临时/备份目录不得残留：${residueOf(tmp)}", residueOf(tmp).isEmpty())
    }

    // ------------------------------------------------------------------
    // 3. 合同自检：夹具本身满足 pack-mod 的确定性/安全条目名合同（防夹具漂移成"假链路"）
    // ------------------------------------------------------------------

    @Test
    fun `模块包夹具——两次打包字节相同且条目名安全`() {
        val entries = arrayOf(
            "main.ERB" to PATCH_SOURCE.toByteArray(Charsets.UTF_8),
            "$MODULE_PACKAGE_ID.assets/img/logo.png" to PNG,
        )
        val a = packModLikeZip(*entries)
        val b = packModLikeZip(*entries)

        assertEquals("同输入两次打包字节必须相同（pack-mod 确定性合同）", a.toList(), b.toList())
        val names = zipEntryNames(a)
        assertEquals("条目按路径字典序", names.sorted(), names)
        assertTrue(
            "条目名不得含 ..／绝对路径／反斜杠／控制字符：$names",
            names.none { name ->
                name.split('/').any { it == ".." || it.isEmpty() } ||
                    name.startsWith('/') ||
                    name.contains('\\') ||
                    name.any { it.code < 0x20 || it.code == 0x7F }
            },
        )
    }

    // ------------------------------------------------------------------
    // 夹具：pack-mod 同合同布局 zip（见类 KDoc 的合同对齐清单）
    // ------------------------------------------------------------------

    /** 固定时间戳：1980-01-01T00:00:00Z（zip 的 DOS 时间下限，pack-mod 同款）。 */
    private val fixedTimeMillis = 315532800000L

    /** 按 pack-mod 合同造一个模块包 zip：字典序、固定时间戳、不写目录条目。 */
    private fun packModLikeZip(vararg moduleFiles: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in moduleFiles.sortedBy { it.first }) {
                val entry = ZipEntry(name)
                entry.time = fixedTimeMillis
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun zipEntryNames(zipBytes: ByteArray): List<String> =
        java.util.zip.ZipInputStream(zipBytes.inputStream()).use { zin ->
            val names = ArrayList<String>()
            while (true) {
                val entry = zin.nextEntry ?: break
                names += entry.name
            }
            names
        }

    // ------------------------------------------------------------------
    // 夹具：mini base + 临时 mods（照 ModImportAssetsTest 同款，private 故复制）
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

    private fun controllerIn(tmp: File): ModImportController =
        ModImportController(
            modStore = ModStore(File(tmp, "mods")),
            baseSource = { BaseCompileContext(miniMetadata(), miniBaseCounts(), BASE_SHA, csvDir = null) },
            gameRoot = tmp,
        )

    private fun imported(outcome: ModImportOutcome): ImportedPatchDetail =
        (outcome as? ModImportOutcome.Imported)?.detail
            ?: throw AssertionError("导入应成功，实为 $outcome")

    private fun rt2Of(tmp: File): File = File(File(tmp, "mods"), "rt2")

    private fun assetDirOf(tmp: File, packageId: String): File = File(rt2Of(tmp), packageId + ".assets")

    /** rt2 下残留的临时/备份目录（原子落盘的收尾判据：正常路径必须为空）。 */
    private fun residueOf(tmp: File): List<String> =
        rt2Of(tmp).list()?.filter { it.contains(".tmp-") || it.contains(".bak-") } ?: emptyList()

    private fun assertArrayEquals(message: String, expected: ByteArray, actual: ByteArray) {
        assertEquals(message, expected.toList(), actual.toList())
    }

    private companion object {
        /** 合成 base sha（64 位小写十六进制）。 */
        private const val BASE_SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

        /** 模块包源码里的 PACKAGE_ID（= pack-mod 给资源加的前缀）。 */
        private const val MODULE_PACKAGE_ID = "eratw.demopack"

        /** 可编译的最小补丁源（与其它导入用例同款）。 */
        private const val PATCH_SOURCE = "@B_HELLO\nCALL A_ONE(3)\nPRINTL HELLO_PATCH\nRETURN 0\n"

        /** 一张「图」的合成字节（读取侧不校验格式）。 */
        private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)
    }
}
