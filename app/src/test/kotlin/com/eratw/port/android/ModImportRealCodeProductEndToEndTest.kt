package com.eratw.port.android

import com.eratw.port.core.compile.PatchCompiler
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.platform.storage.ModStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「真 pack-mod 代码产物 → 真导入链」跨模块 e2e（第二段：玩家侧导入）。
 *
 * ## 两段接力，不是同进程 A/B
 *
 * 第一段在 `tools/modgen` 侧（`RealCodeProductFixtureTest`）：真跑 `pack-mod --code`（同一模块连打
 * 两次逐字节相同），把真产物 zip 冻结成 Base64 常量 [FIXTURE_BASE64]，并逐项断言条目布局。
 * 本段（`:app` 测试源集）内嵌**同一份** Base64，走真 `ModImportController.import` 收下：
 * `code.mod` 应落成 `<rt2>/code/<packageId>/<sha256 前 16 位>.mod` 只读文件，
 * 同时 `.erbm` / `.manifest` / `<pkg>.assets` 三条平行产物照常落盘。
 *
 * 为什么拆两段：`pack-mod` 实现是 `internal object PackMod`，CLI `ManifestBuild` 对 pack-mod 分支
 * 直接 `exitProcess`；`:app` 测试源集不依赖 `:tools:modgen`，本 lane 授权写面也不含
 * `app/build.gradle.kts`。与既有 `AuthorClosedLoopSmokeTest` ↔ `ModImportAuthorPackEndToEndTest`
 * 同一套接力口径（两者都靠「同一份字节 + 两端各自断言」钉住）。
 *
 * 包身份：夹具模块的 `PACKAGE_ID` 就是 app 端 import 派生值
 * （`kojo-` + sha256("main.ERB") 前 8 位十六进制 = [EXPECTED_PACKAGE_ID]），因此内层 `.mod`
 * 的 `manifest.json.packageId` 与导入端 identity 校验同值 —— 既有 controller e2e 手写 code.mod
 * 字节时没有「真产物」这一层，本类把它补上。
 *
 * ## 夹具字节与 modgen 侧交叉钉死（entrymanifest 波之后）
 *
 * [FIXTURE_BASE64] 与 `RealCodeProductFixtureTest.REAL_PACK_BASE64` **逐字节相同**，且内层
 * `manifest.json` 现在带 `entrypoint`（= [EXPECTED_ENTRYPOINT]，键序 packageId → version →
 * entrypoint → apiVersion）：本类只走到导入/落盘面，`ModCodeCarrierAssemblyEndToEndTest` 能拿同一份
 * 字节一路装到真 `EraMod`。两侧哨兵：[FIXTURE_ZIP_SHA256]（整包 sha256）与 [CODE_MOD_SHA16]
 * （内层 `code.mod` sha256 前 16 位 = 内容寻址文件名主体）在 modgen 侧 `RealCodeProductFixtureTest`
 * 有同值常量，两侧各自对本侧解码字节复算后断言 —— 只重钉一侧的 Base64 或只改一侧 digest 都会红。
 *
 * 刷新夹具：任何修改夹具模块正文 / `PackModZip` 打包格式 / `PackMod` 元数据 / 入口 FQN 的波次都要
 * 同步刷新 [FIXTURE_BASE64] 与两个 digest 常量（modgen 侧 `RealCodeProductFixtureTest` 每次运行把
 * 最新 Base64 写到 `tools/modgen/build/code-e2e-fixture.txt`，直接从那里取；四处必须同源，勿手改单侧）。
 */
class ModImportRealCodeProductEndToEndTest {

    @Test
    fun `真代码产物包——真导入后落内容寻址只读mod且旁路产物照常`() {
        val zip = fixtureZip()
        val codeBytes = assertFixtureLayout(zip)

        val tmp = Files.createTempDirectory("eratw-realcodee2e").toFile()
        val controller = controllerIn(tmp)
        // 派生口径自检：app 端按源文件名集合派生的 id 必须等于夹具模块自述 id（否则 identity 闸必红）。
        assertEquals(
            "导入端 packageId 派生口径漂了？夹具模块 PACKAGE_ID 需同步",
            EXPECTED_PACKAGE_ID,
            controller.packageIdFor(listOf(ROOT_SOURCE_NAME)),
        )
        val detail = imported(controller.import(listOf(PickedModFile("code-e2e.ZIP", zip))))
        val targetPackageId = detail.patchFileName.removeSuffix(ModPackageInventory.MODULE_EXTENSION)
        assertEquals("真产物链的派生 id 应与夹具自述 id 同值", EXPECTED_PACKAGE_ID, targetPackageId)

        // 旁路产物：manifest 版本随包级元数据落盘、资源计数与资源字节照常。
        val manifest = File(rt2Of(tmp), targetPackageId + ModPackageInventory.MANIFEST_EXTENSION)
        assertEquals(PACK_META_VERSION, ManifestCodec.decode(manifest.readBytes()).version)
        assertEquals("元数据条目不计数，资源只有一条", 1, detail.resourceEntryCount)
        val assets = assetDirOf(tmp, targetPackageId)
        assertTrue("资源目录应落在目标包名下：$assets", assets.isDirectory)
        assertArrayEquals(README_BYTES, File(assets, "README.txt").readBytes())

        // 代码产物：内容寻址名 + 完整 sha + 只读 + 字节原样 + 父目录口径。
        val store = ModCodeStore(rt2Of(tmp))
        val artifacts = store.list(targetPackageId)
        assertEquals("真代码产物应恰好落一件：$artifacts", 1, artifacts.size)
        val artifact = artifacts.single()
        val fullSha = sha256Hex(codeBytes)
        assertEquals(fullSha.take(SHA16_CHARS), artifact.file.name.removeSuffix(MOD_CODE_ARTIFACT_EXTENSION))
        assertEquals("list 只从文件名恢复 16 位前缀", fullSha.take(SHA16_CHARS), artifact.sha256Hex)
        assertEquals(codeBytes.size.toLong(), artifact.sizeBytes)
        assertEquals(targetPackageId, artifact.file.parentFile.name)
        assertEquals(MOD_CODE_DIR_NAME, artifact.file.parentFile.parentFile.name)
        assertArrayEquals("盘上字节必须与包内 code.mod 逐字节相同", codeBytes, artifact.file.readBytes())
        assertFalse("代码产物必须只读（DexClassLoader 硬约束）", artifact.file.canWrite())
        // 内层 manifest 身份也由落盘产物独立复核（不信包外任何声明）。
        assertEquals(targetPackageId, innerManifestPackageId(artifact.file.readBytes()))

        assertNoResidue(tmp)

        // 重复导入同一份真产物：幂等（同 sha ⇒ 复用同一件，不新增、不改写）。
        val again = imported(controllerIn(tmp).import(listOf(PickedModFile("code-e2e.ZIP", zip))))
        assertEquals(targetPackageId, again.patchFileName.removeSuffix(ModPackageInventory.MODULE_EXTENSION))
        val againArtifacts = store.list(targetPackageId)
        assertEquals("重复导入不得新增代码产物", 1, againArtifacts.size)
        assertEquals(artifact.file.name, againArtifacts.single().file.name)
        assertArrayEquals(codeBytes, againArtifacts.single().file.readBytes())
        assertNoResidue(tmp)
    }

    @Test
    fun `真代码产物包的identity不符——整包failed且不留code目录`() {
        val zip = fixtureZip()
        // 只替换 code.mod：内层 manifest 的 packageId 改成形状合法但与本包无关的 id。
        val wrongInner = codeModZipOf(WRONG_INNER_PACKAGE_ID)
        val mutated = zipOf(
            *zipEntryNames(zip).map { name ->
                name to if (name == CODE_MOD_ENTRY) wrongInner else zipEntryBytes(zip, name)
            }.toTypedArray(),
        )

        val tmp = Files.createTempDirectory("eratw-realcodee2e-fail").toFile()
        val outcome = controllerIn(tmp).import(listOf(PickedModFile("code-e2e.ZIP", mutated)))
        assertTrue("identity 不符必须整包失败，实为 $outcome", outcome is ModImportOutcome.Failed)
        outcome as ModImportOutcome.Failed
        assertEquals(ModImportFailureKind.IDENTITY, outcome.kind)

        // 整包 failed：代码目录/产物/包容器都不许留，临时残留必须清干净。
        assertEquals(emptyList<CodeArtifact>(), ModCodeStore(rt2Of(tmp)).list(EXPECTED_PACKAGE_ID))
        val codeDir = File(rt2Of(tmp), MOD_CODE_DIR_NAME)
        assertFalse("失败路径不得留下 code 目录内容：${codeDir.list()?.toList()}", codeDir.exists())
        assertFalse(
            "失败路径不得落包容器",
            File(rt2Of(tmp), EXPECTED_PACKAGE_ID + ModPackageInventory.MODULE_EXTENSION).exists(),
        )
        assertNoResidue(tmp)
    }

    /** 真产物布局自检（防「假链路」）；返回 `code.mod` 原字节。 */
    private fun assertFixtureLayout(zip: ByteArray): ByteArray {
        assertEquals(
            "整包 sha256 与 modgen 侧哨兵同值（改模板/写侧要同步重钉两侧）",
            FIXTURE_ZIP_SHA256,
            sha256Hex(zip),
        )
        // 包**内容摘要**（条目名 + 未压缩字节）与 modgen 侧同值：跨机可比的跨模块哨兵
        // （整包 sha256 随 JDK 的 zlib 版本而变，只在冻结那台机成立）。
        assertEquals(
            "包内容摘要与 modgen 侧哨兵同值（改模板/写侧要同步重钉两侧）",
            FIXTURE_ZIP_CONTENT_SHA,
            zipContentDigest(zip),
        )
        assertEquals(
            "条目布局（字典序；元数据/代码产物在 zip 根）",
            listOf(
                CODE_MOD_ENTRY,
                PACK_META_ENTRY_NAME,
                "$EXPECTED_PACKAGE_ID.assets/README.txt",
                ROOT_SOURCE_NAME,
            ),
            zipEntryNames(zip),
        )
        assertEquals(
            "包级元数据内容",
            "{\"schema\":1,\"packageId\":\"$EXPECTED_PACKAGE_ID\",\"version\":\"$PACK_META_VERSION\"}",
            String(zipEntryBytes(zip, PACK_META_ENTRY_NAME), Charsets.UTF_8),
        )
        assertEquals("补丁源原样", PATCH_SOURCE, String(zipEntryBytes(zip, ROOT_SOURCE_NAME), Charsets.UTF_8))
        assertArrayEquals(README_BYTES, zipEntryBytes(zip, "$EXPECTED_PACKAGE_ID.assets/README.txt"))
        val codeBytes = zipEntryBytes(zip, CODE_MOD_ENTRY)
        assertTrue("code.mod 不能为空", codeBytes.isNotEmpty())
        assertEquals(
            "内层 manifest 的键序与值（packageId → version → entrypoint → apiVersion）：entrymanifest 波之后真产物带入口",
            "{\"packageId\":\"$EXPECTED_PACKAGE_ID\",\"version\":\"1\"," +
                "\"entrypoint\":\"$EXPECTED_ENTRYPOINT\",\"apiVersion\":1}",
            String(zipEntryBytes(codeBytes, "manifest.json"), Charsets.UTF_8),
        )
        assertEquals(
            "code.mod sha256 前 16 位 = 内容寻址文件名主体，与 modgen 侧哨兵同值",
            CODE_MOD_SHA16,
            sha256Hex(codeBytes).take(SHA16_CHARS),
        )
        assertEquals(
            "code.mod 是作者编译好的 .mod（zip）：内层 manifest 身份 = 模块自述 id",
            EXPECTED_PACKAGE_ID,
            innerManifestPackageId(codeBytes),
        )
        return codeBytes
    }

    /** 内层 `.mod` 的 `manifest.json.packageId`（正则取字段：产物是作者编译物，不引 JSON 依赖）。 */
    private fun innerManifestPackageId(modZip: ByteArray): String {
        val manifest = String(zipEntryBytes(modZip, "manifest.json"), Charsets.UTF_8)
        val match = Regex("\"packageId\"\\s*:\\s*\"([^\"]+)\"").find(manifest)
            ?: throw AssertionError("内层 manifest 没有 packageId：$manifest")
        return match.groupValues[1]
    }

    // ------------------------------------------------------------------
    // 夹具：mini base + 临时 mods（与其它导入用例同款，private 故复制）
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

    private fun fixtureZip(): ByteArray = Base64.getDecoder().decode(FIXTURE_BASE64)

    /**
     * zip 内容摘要（条目名 + 未压缩字节），与压缩实现无关 ⇒ 跨机可比。
     *
     * 与 modgen 侧 `zipContentDigest` 同一算法（两端各持一份实现，不共享代码，
     * 免得「同一份实现算出的摘要」变成同义反复）。
     */
    private fun zipContentDigest(zipBytes: ByteArray): String {
        val perEntry = sortedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                perEntry[entry.name] = zis.readBytes()
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        for ((name, content) in perEntry) {
            digest.update(name.toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(MessageDigest.getInstance("SHA-256").digest(content))
            digest.update(0)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** 正常路径与失败路径共用的残留判据：rt2 下不得有 `.tmp-` / `.bak-`，code 目录不得有临时件。 */
    private fun assertNoResidue(tmp: File) {
        val rt2Residue = rt2Of(tmp).list()?.filter { it.contains(".tmp-") || it.contains(".bak-") }.orEmpty()
        assertTrue("rt2 临时残留：$rt2Residue", rt2Residue.isEmpty())
        val codeResidue = File(rt2Of(tmp), MOD_CODE_DIR_NAME)
            .walkTopDown()
            .filter { it.isFile && it.name.startsWith("code-") && it.name.endsWith(".tmp") }
            .map { it.name }
            .toList()
        assertTrue("code 目录临时残留：$codeResidue", codeResidue.isEmpty())
    }

    private fun zipEntryNames(zip: ByteArray): List<String> =
        ZipInputStream(ByteArrayInputStream(zip)).use { zin ->
            val names = ArrayList<String>()
            while (true) {
                val entry = zin.nextEntry ?: break
                names += entry.name
            }
            names
        }

    private fun zipEntryBytes(zip: ByteArray, name: String): ByteArray {
        ZipInputStream(ByteArrayInputStream(zip)).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                if (entry.name == name) return zin.readBytes()
            }
        }
        throw AssertionError("entry not found: $name")
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
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

    /**
     * 失败面合成内层产物：清单形状与真产物同构（带 entrypoint / apiVersion），**只**把 packageId
     * 换成无关值，确保红的是 identity 闸而不是清单闸（真产物恒带入口，identity 不符仍要合成）。
     */
    private fun codeModZipOf(innerPackageId: String): ByteArray = zipOf(
        "classes.dex" to FAKE_DEX,
        "manifest.json" to (
            """{"packageId":"$innerPackageId","version":"1","entrypoint":"$EXPECTED_ENTRYPOINT","apiVersion":1}"""
            ).toByteArray(Charsets.UTF_8),
    )

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) {
        assertEquals(expected.toList(), actual.toList())
    }

    private fun assertArrayEquals(message: String, expected: ByteArray, actual: ByteArray) {
        assertEquals(message, expected.toList(), actual.toList())
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        /** 与 `ModImportController.PACKAGE_ID_BYTES = 4` 同口径（4 字节 = 8 位十六进制）。 */
        const val SHA16_CHARS = 16

        /** 源文件名集合（单个根 ERB）：导入端派生 packageId 的输入。 */
        const val ROOT_SOURCE_NAME = "main.ERB"

        /** app 端按 [ROOT_SOURCE_NAME] 派生的 packageId = 夹具模块自述 id。 */
        const val EXPECTED_PACKAGE_ID = "kojo-224af7e0"

        /** 内层 manifest 故意写错的形状合法 id（不同于本包派生 id）。 */
        const val WRONG_INNER_PACKAGE_ID = "kojo-deadbeef"

        /**
         * 内层 `manifest.json.entrypoint`：app 测试源集里**真实存在**的载体入口类 FQN
         * （`com.eratw.codee2e.CodeE2eMod`）。装配面会用 `CodeE2eMod::class.java.name` 断言同值，
         * 故改动入口 FQN 必须同步夹具与测试类两处。
         */
        const val EXPECTED_ENTRYPOINT = "com.eratw.codee2e.CodeE2eMod"

        /** 代码产物 zip 根条目名（与 modgen 侧 `PackModCode.CODE_MOD_ENTRY` 同合同）。 */
        const val CODE_MOD_ENTRY = "code.mod"

        /** 包级元数据条目名（与 modgen 侧 `PackModMeta.PACK_META_ENTRY_NAME` 同合同）。 */
        const val PACK_META_ENTRY_NAME = "eratw-pack.json"

        /** 夹具模块自述版本（经元数据落进产物 manifest）。 */
        const val PACK_META_VERSION = "1.0"

        /** 可编译的最小补丁源（= 夹具 zip 里的 `main.ERB`）。 */
        const val PATCH_SOURCE = "@B_HELLO\nCALL A_ONE(3)\nPRINTL HELLO_PATCH\nRETURN 0\n"

        /** 随包资源原字节（夹具 README.txt）。 */
        val README_BYTES = "code-e2e asset\n".toByteArray(Charsets.UTF_8)

        /** 内层假 dex（本闸不解析内容；dex ABI 闸在 app 侧 ModDexGate）。 */
        val FAKE_DEX = byteArrayOf(0x64, 0x65, 0x78, 0x0A, 0x30, 0x33, 0x35, 0x00)

        /** 合成 base sha（64 位小写十六进制）。 */
        const val BASE_SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

        /**
         * 整包 sha256（跨模块哨兵，与 modgen 侧 `RealCodeProductFixtureTest.FIXTURE_ZIP_SHA256`
         * 同值）：只重钉一侧 Base64 / 只改一侧 digest 时本断言必红。
         */
        const val FIXTURE_ZIP_SHA256 = "f4734bb9c6e3fa76b2663e8ff5dc05512b4c8a3f3612d58da1dc09e2c1da0d16"

        /**
         * 包内容摘要（跨模块哨兵，与 modgen 侧 `RealCodeProductFixtureTest.FIXTURE_ZIP_CONTENT_SHA`
         * 同值；跨机可比 —— 整包 sha256 随 JDK 的 zlib 版本而变）。
         */
        const val FIXTURE_ZIP_CONTENT_SHA =
            "67cf96333bd29a61739f000a61b7282878461b34d5331c77a5630c952678a9b9"

        /**
         * 内层 `code.mod` sha256 前 16 位（内容寻址文件名主体；与 modgen 侧
         * `RealCodeProductFixtureTest.SHA16_HEX` 同值哨兵）。
         */
        const val CODE_MOD_SHA16 = "1c0db36226c68428"

        /**
         * 真 `pack-mod --code --entrypoint` 产物（zip 字节，Base64），与
         * `RealCodeProductFixtureTest.REAL_PACK_BASE64` 逐字节相同。
         * 内容（条目字典序、时间戳 1980-01-01）：`code.mod`（作者编译好的内层 .mod，内层 manifest
         * 带 `entrypoint` = [EXPECTED_ENTRYPOINT]）+
         * `eratw-pack.json`（`{"schema":1,"packageId":"kojo-224af7e0","version":"1.0"}`）+
         * `<PACKAGE_ID>.assets/README.txt` + `main.ERB`。
         * 刷新方式见类 KDoc（modgen 侧每次运行落 `tools/modgen/build/code-e2e-fixture.txt`）。
         */
        const val FIXTURE_BASE64 =
            "UEsDBBQACAgIAAAAIQAAAAAAAAAAAAAAAAAIAAkAY29kZS5tb2RVVAUAAYA1zhIL8GZmEWHg4OBgYGBQZEAG3AycDMk5icXFqcV6" +
            "KakVoSGsDIwNpueEvD3XPjAyMEtlYAjwZufY+eS3IRdQNUh/AE6zeIFm5SbmZaalFpfoZRXn58FNWx2m5eFzzs/H/6tP4KagU+fO" +
            "n7puamTk6WPsbxpwKVDLW1fr3JnzzKckmCpXVqq6TmX9ObNS1aHxyZ+YvVcWHZlUtPfJnylXQhaH2KYrvM2a8mr2fKaQ2RETJNku" +
            "50Rwg9239cWNPTFAF6SB3cfIJMKAcCGy20G+RQXY/I5uArLpvCgm+AAxdh8HeLOygVQwAWEbkH4DVg8AUEsHCFtXnhHuAAAAiAEA" +
            "AFBLAwQUAAgICAAAACEAAAAAAAAAAAAAAAAADwAJAGVyYXR3LXBhY2suanNvblVUBQABgDXOEqtWKk7OSM1NVLIy1FEqSEzOTkxP" +
            "9UxRslLKzs/K1zUyMklMM081UNJRKkstKs7MzwPKGOoZKNUCAFBLBwiVgbsdOQAAADgAAABQSwMEFAAICAgAAAAhAAAAAAAAAAAA" +
            "AAAAAB8ACQBrb2pvLTIyNGFmN2UwLmFzc2V0cy9SRUFETUUudHh0VVQFAAGANc4SS85PSdVNNUpVSCwuTi3hAgBQSwcINNsf7BEA" +
            "AAAPAAAAUEsDBBQACAgIAAAAIQAAAAAAAAAAAAAAAAAIAAkAbWFpbi5FUkJVVAUAAYA1zhJzcIr3cPXx8edydvTxUXCM9/dz1TDW" +
            "5AoI8vQL8VEAS8UHOIY4e3AFuYaEBvkpGHABAFBLBwj6BXSzMgAAADMAAABQSwECFAAUAAgICAAAACEAW1eeEe4AAACIAQAACAAJ" +
            "AAAAAAAAAAAAAAAAAAAAY29kZS5tb2RVVAUAAYA1zhJQSwECFAAUAAgICAAAACEAlYG7HTkAAAA4AAAADwAJAAAAAAAAAAAAAAAt" +
            "AQAAZXJhdHctcGFjay5qc29uVVQFAAGANc4SUEsBAhQAFAAICAgAAAAhADTbH+wRAAAADwAAAB8ACQAAAAAAAAAAAAAArAEAAGtv" +
            "am8tMjI0YWY3ZTAuYXNzZXRzL1JFQURNRS50eHRVVAUAAYA1zhJQSwECFAAUAAgICAAAACEA+gV0szIAAAAzAAAACAAJAAAAAAAA" +
            "AAAAAAATAgAAbWFpbi5FUkJVVAUAAYA1zhJQSwUGAAAAAAQABAAaAQAAhAIAAAAA"
    }
}
