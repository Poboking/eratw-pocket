package com.eratw.port.android

import com.eratw.port.core.compile.PatchCompiler
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.platform.storage.ModStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * ADR-014 S4「旁挂媒体资源面」的出口判据（[ModImportMediaAssets]）：
 * 元数据声明 `<dir>` ⇒ 口上 zip 里 `resources/mod/<dir>/…` 与 `sound/mod/<dir>/…` 落到
 * gameRoot 两棵树，并在 `mods/rt2/<packageId>.media` 写清单；其余条目静默忽略。
 *
 * 覆盖语义：
 * - 两树落盘 + 清单（版本行 `RT2MEDIA/1 dir=<dir>` + 逐行相对路径）；
 * - 同包重复导入 = 清单差集替换（旧清单内不再产出的文件删、同名文件覆盖）；
 * - `<dir>` 不匹配 / null / 空 / 非法 的分层处置（忽略 / 零动作 / WARN 零动作）；
 * - 扩展名白名单（图/文 png/jpg/jpeg/webp/txt、音频 mp3/ogg/wav，`.csv` 不在内）⇒ 整面拒；
 * - 单文件 / 单树累计 / 两树合计上限（[ModImportMediaAssets.MediaLimits] 注入小上限才可测）；
 * - 跨包同名 `<dir>` ⇒ [ModImportMediaAssets.MediaDirConflictException]（文案点名两包与目录）；
 * - 失败/冲突后盘上零残留（临时目录、正式树、清单都不留半套）；
 * - 控制器级 e2e：带 `resources` 的 `eratw-pack.json` 包导入后文件与清单都在盘上。
 *
 * 全部纯 JVM：内存 zip 夹具 + 临时目录，不碰 Android 类。
 */
class ModImportMediaAssetsTest {

    @Test
    fun `合法两树——相对路径原样落盘且清单含版本行与目录标记`() {
        val tmp = Files.createTempDirectory("eratw-media").toFile()
        val plan = MediaFixture.stageInto(
            tmp,
            MediaFixture.MEDIA_DIR,
            "resources/mod/demo/img/a.png" to MediaFixture.PNG,
            "sound/mod/demo/se.mp3" to byteArrayOf(1, 2, 3),
            "resources/mod/other/x.png" to MediaFixture.PNG,
        )

        assertEquals("只收声明目录下的两树条目", 2, plan.entryCount)
        assertTrue("无告警：${plan.warnings}", plan.warnings.isEmpty())
        plan.staged!!.commit()

        assertArrayEquals("图/文树落盘", MediaFixture.PNG, File(tmp, "resources/mod/demo/img/a.png").readBytes())
        assertArrayEquals("音频树落盘", byteArrayOf(1, 2, 3), File(tmp, "sound/mod/demo/se.mp3").readBytes())
        assertFalse("未声明目录不得落盘", File(tmp, "resources/mod/other").exists())
        val lines = File(MediaFixture.rt2Of(tmp), MediaFixture.PKG + ".media").readText().trimEnd().lines()
        assertEquals("首行 = 版本行 + 目录标记", "RT2MEDIA/1 dir=demo", lines.first())
        assertEquals(
            "其后逐行 = gameRoot 相对路径",
            listOf("resources/mod/demo/img/a.png", "sound/mod/demo/se.mp3"),
            lines.drop(1),
        )
        assertTrue("临时目录不得残留：${MediaFixture.rt2Of(tmp).list()?.toList()}", MediaFixture.residueOf(tmp).isEmpty())
    }

    @Test
    fun `同包重复导入——旧清单内不再产出的文件被删且新文件在`() {
        val tmp = Files.createTempDirectory("eratw-media").toFile()
        MediaFixture.stageInto(
            tmp,
            MediaFixture.MEDIA_DIR,
            "resources/mod/demo/a.png" to byteArrayOf(1, 1),
            "resources/mod/demo/old.png" to byteArrayOf(2, 2),
        ).staged!!.commit()
        assertTrue("第一版旧文件应在", File(tmp, "resources/mod/demo/old.png").isFile)

        MediaFixture.stageInto(
            tmp,
            MediaFixture.MEDIA_DIR,
            "resources/mod/demo/a.png" to byteArrayOf(3, 3, 3),
            "resources/mod/demo/new.png" to byteArrayOf(4, 4),
        ).staged!!.commit()

        assertFalse("旧清单里本次不再产出的文件应删", File(tmp, "resources/mod/demo/old.png").exists())
        assertArrayEquals("同名文件按新内容覆盖", byteArrayOf(3, 3, 3), File(tmp, "resources/mod/demo/a.png").readBytes())
        assertTrue("新文件在盘上", File(tmp, "resources/mod/demo/new.png").isFile)
        val lines = File(MediaFixture.rt2Of(tmp), MediaFixture.PKG + ".media").readText().trimEnd().lines()
        assertEquals(
            "清单只留本次条目",
            listOf("RT2MEDIA/1 dir=demo", "resources/mod/demo/a.png", "resources/mod/demo/new.png"),
            lines,
        )
    }

    @Test
    fun `目录名不匹配——条目静默忽略且不报错`() {
        val tmp = Files.createTempDirectory("eratw-media").toFile()
        val plan = MediaFixture.stageInto(
            tmp,
            MediaFixture.MEDIA_DIR,
            "resources/mod/other/x.png" to MediaFixture.PNG,
            "sound/mod/other/se.mp3" to byteArrayOf(1),
        )

        assertNull("没有可收下条目 ⇒ 不落盘", plan.staged)
        assertEquals(0, plan.entryCount)
        assertTrue("不同目录名不算错、不告警：${plan.warnings}", plan.warnings.isEmpty())
        assertFalse("不得建资源树", File(tmp, "resources").exists())
        assertFalse("不得写清单", File(MediaFixture.rt2Of(tmp), MediaFixture.PKG + ".media").exists())
    }

    @Test
    fun `csv 不在白名单——整面拒导且盘上零残留`() {
        val tmp = Files.createTempDirectory("eratw-media").toFile()
        val plan = MediaFixture.stageInto(
            tmp,
            MediaFixture.MEDIA_DIR,
            "resources/mod/demo/a.csv" to "x".toByteArray(Charsets.UTF_8),
        )

        assertNull("扩展名不合规 ⇒ 整面拒", plan.staged)
        val warning = plan.warnings.single()
        assertTrue("告警点名条目：$warning", warning.contains("a.csv"))
        assertFalse("零落盘（连目录都不建）", File(tmp, "resources/mod/demo").exists())
    }

    @Test
    fun `扩展名非法——整面拒导且告警列出允许范围`() {
        val tmp = Files.createTempDirectory("eratw-media").toFile()
        val plan = MediaFixture.stageInto(
            tmp,
            MediaFixture.MEDIA_DIR,
            "sound/mod/demo/se.exe" to byteArrayOf(1),
        )

        assertNull(plan.staged)
        val warning = plan.warnings.single()
        assertTrue("告警点名条目：$warning", warning.contains("se.exe"))
        assertTrue("告警列出允许扩展名：$warning", warning.contains("mp3"))
        assertFalse("零落盘", File(tmp, "sound").exists())
    }

    @Test
    fun `非法相对名——跳过并告警且不得逃逸 gameRoot`() {
        val tmp = Files.createTempDirectory("eratw-media").toFile()
        val plan = MediaFixture.stageInto(
            tmp,
            MediaFixture.MEDIA_DIR,
            "resources/mod/demo/../evil.png" to MediaFixture.PNG,
            "resources/mod/demo/ok.png" to MediaFixture.PNG,
        )

        assertEquals("只有合法条目落盘", 1, plan.entryCount)
        assertEquals("非法条目一条 WARN", 1, plan.warnings.size)
        assertTrue("告警点名非法条目：${plan.warnings.single()}", plan.warnings.single().contains("evil.png"))
        plan.staged!!.commit()
        assertFalse("不得逃逸到 gameRoot 外", File(tmp, "evil.png").exists())
        assertFalse("不得写到兄弟目录", File(tmp, "resources/mod/evil.png").exists())
        assertTrue("合法条目照常落盘", File(tmp, "resources/mod/demo/ok.png").isFile)
    }

    @Test
    fun `目录名为 null 或空——完全不动作`() {
        val tmp = Files.createTempDirectory("eratw-media").toFile()
        val nullPlan = MediaFixture.stageInto(
            tmp,
            null,
            "resources/mod/demo/a.png" to MediaFixture.PNG,
        )
        val emptyPlan = MediaFixture.stageInto(
            tmp,
            "",
            "resources/mod/demo/a.png" to MediaFixture.PNG,
        )

        assertNull("null ⇒ 零动作", nullPlan.staged)
        assertNull("空串 ⇒ 零动作", emptyPlan.staged)
        assertTrue(
            "不告警：${nullPlan.warnings + emptyPlan.warnings}",
            nullPlan.warnings.isEmpty() && emptyPlan.warnings.isEmpty(),
        )
        assertFalse("老包路径逐位不变：不得建树", File(tmp, "resources").exists())
        assertFalse("不得写清单", File(MediaFixture.rt2Of(tmp), MediaFixture.PKG + ".media").exists())
    }

    @Test
    fun `目录名非法——只告警不落盘`() {
        val tmp = Files.createTempDirectory("eratw-media").toFile()
        val plan = MediaFixture.stageInto(
            tmp,
            "bad:dir",
            "resources/mod/bad:dir/a.png" to MediaFixture.PNG,
        )

        assertNull("非法 <dir> 不落盘", plan.staged)
        assertEquals(1, plan.warnings.size)
        assertTrue("告警点名目录名：${plan.warnings.single()}", plan.warnings.single().contains("bad:dir"))
        assertFalse("不得建树", File(tmp, "resources").exists())
    }

    @Test
    fun `跨包同名目录——拒导且文案点名两个包与目录`() {
        val tmp = Files.createTempDirectory("eratw-media").toFile()
        val rt2 = MediaFixture.rt2Of(tmp)
        assertTrue(rt2.mkdirs())
        File(rt2, "otherpkg.media").writeText("RT2MEDIA/1 dir=demo\n")

        val conflict = try {
            MediaFixture.stageInto(
                tmp,
                MediaFixture.MEDIA_DIR,
                "resources/mod/demo/a.png" to MediaFixture.PNG,
            )
            throw AssertionError("跨包同名目录应拒导")
        } catch (caught: ModImportMediaAssets.MediaDirConflictException) {
            caught
        }

        val message = conflict.message.orEmpty()
        assertTrue("文案点名目录：$message", message.contains("demo"))
        assertTrue("文案点名已有包：$message", message.contains("otherpkg"))
        assertTrue("文案点名本次包：$message", message.contains(MediaFixture.PKG))
        assertFalse("拒导后零落盘", File(tmp, "resources").exists())
    }
}

/**
 * 限额面单测（拆类是为了不撞 detekt `TooManyFunctions` 的每类 11 条上限）：
 * 单文件 / 单树累计 / 两树合计三条上限各自整面拒导，且失败零残留。
 */
class ModImportMediaAssetsLimitTest {

    @Test
    fun `超单文件上限——整面拒导`() {
        val tmp = Files.createTempDirectory("eratw-media-limit").toFile()
        val plan = MediaFixture.stageIntoWithLimits(
            tmp,
            MediaFixture.MEDIA_DIR,
            ModImportMediaAssets.MediaLimits(maxResourceFileBytes = 16L),
            "resources/mod/demo/a.png" to MediaFixture.randomBytes(17, 1L),
        )

        assertNull(plan.staged)
        assertTrue("告警点名单文件上限：${plan.warnings.single()}", plan.warnings.single().contains("单文件上限"))
        assertFalse("零落盘", File(tmp, "resources").exists())
    }

    @Test
    fun `图文树累计超上限——整面拒导`() {
        val tmp = Files.createTempDirectory("eratw-media-limit").toFile()
        val plan = MediaFixture.stageIntoWithLimits(
            tmp,
            MediaFixture.MEDIA_DIR,
            ModImportMediaAssets.MediaLimits(maxResourceTotalBytes = 30L),
            "resources/mod/demo/a.png" to MediaFixture.randomBytes(20, 2L),
            "resources/mod/demo/b.png" to MediaFixture.randomBytes(20, 3L),
        )

        assertNull(plan.staged)
        assertTrue("告警点名图/文树累计上限：${plan.warnings.single()}", plan.warnings.single().contains("图/文资源总量超上限"))
        assertFalse("零落盘", File(tmp, "resources").exists())
    }

    @Test
    fun `音频树累计超上限——整面拒导`() {
        val tmp = Files.createTempDirectory("eratw-media-limit").toFile()
        val plan = MediaFixture.stageIntoWithLimits(
            tmp,
            MediaFixture.MEDIA_DIR,
            ModImportMediaAssets.MediaLimits(maxSoundTotalBytes = 10L),
            "sound/mod/demo/a.mp3" to MediaFixture.randomBytes(8, 4L),
            "sound/mod/demo/b.mp3" to MediaFixture.randomBytes(8, 5L),
        )

        assertNull(plan.staged)
        assertTrue("告警点名音频树累计上限：${plan.warnings.single()}", plan.warnings.single().contains("音频资源总量超上限"))
        assertFalse("零落盘", File(tmp, "sound").exists())
    }

    @Test
    fun `两树合计超上限——整面拒导`() {
        val tmp = Files.createTempDirectory("eratw-media-limit").toFile()
        val plan = MediaFixture.stageIntoWithLimits(
            tmp,
            MediaFixture.MEDIA_DIR,
            ModImportMediaAssets.MediaLimits(maxCombinedBytes = 10L),
            "resources/mod/demo/a.png" to MediaFixture.randomBytes(8, 6L),
            "sound/mod/demo/se.mp3" to MediaFixture.randomBytes(8, 7L),
        )

        assertNull(plan.staged)
        assertTrue("告警点名两树合计上限：${plan.warnings.single()}", plan.warnings.single().contains("两树合计超上限"))
        assertFalse("零落盘", File(tmp, "resources").exists())
        assertFalse("零落盘", File(tmp, "sound").exists())
    }
}

/**
 * 控制器级 e2e（拆类同因）：`eratw-pack.json` 带 `resources` 的包走真实导入链后，
 * 两树文件与 `<pkg>.media` 清单都在盘上；第二个包抢同名 `<dir>` 时整包拒导且不动先到包的文件。
 */
class ModImportMediaAssetsEndToEndTest {

    @Test
    fun `带 resources 的包导入后两树文件与清单都在盘上`() {
        val tmp = Files.createTempDirectory("eratw-media-e2e").toFile()
        val controller = MediaFixture.controllerIn(tmp)
        val packageId = controller.packageIdFor(listOf(MediaFixture.SOURCE_NAME))
        val zip = MediaFixture.zipOf(
            MediaFixture.SOURCE_NAME to MediaFixture.PATCH_SOURCE.toByteArray(Charsets.UTF_8),
            "eratw-pack.json" to MediaFixture.PACK_META_JSON.toByteArray(Charsets.UTF_8),
            "resources/mod/demo/img/a.png" to MediaFixture.PNG,
            "sound/mod/demo/se.mp3" to byteArrayOf(1, 2, 3),
        )

        val detail = MediaFixture.imported(controller.import(listOf(PickedModFile("kojo_media.ZIP", zip))))

        assertArrayEquals("图/文树落盘", MediaFixture.PNG, File(tmp, "resources/mod/demo/img/a.png").readBytes())
        assertArrayEquals("音频树落盘", byteArrayOf(1, 2, 3), File(tmp, "sound/mod/demo/se.mp3").readBytes())
        val lines = File(MediaFixture.rt2Of(tmp), "$packageId.media").readText().trimEnd().lines()
        assertEquals("清单首行 = 版本行 + 目录标记", "RT2MEDIA/1 dir=demo", lines.first())
        assertEquals(
            "清单逐行 = 相对路径",
            listOf("resources/mod/demo/img/a.png", "sound/mod/demo/se.mp3"),
            lines.drop(1),
        )
        assertTrue("无告警：${detail.parseWarnings}", detail.parseWarnings.isEmpty())
    }

    @Test
    fun `第二个包抢同名目录——整包拒导且不动先到包的文件`() {
        val tmp = Files.createTempDirectory("eratw-media-e2e").toFile()
        val controller = MediaFixture.controllerIn(tmp)
        val firstPackageId = controller.packageIdFor(listOf(MediaFixture.SOURCE_NAME))
        val secondPackageId = controller.packageIdFor(listOf(MediaFixture.SECOND_SOURCE_NAME))
        val first = MediaFixture.zipOf(
            MediaFixture.SOURCE_NAME to MediaFixture.PATCH_SOURCE.toByteArray(Charsets.UTF_8),
            "eratw-pack.json" to MediaFixture.PACK_META_JSON.toByteArray(Charsets.UTF_8),
            "resources/mod/demo/a.png" to MediaFixture.PNG,
        )
        MediaFixture.imported(controller.import(listOf(PickedModFile("kojo_media_1.ZIP", first))))

        val second = MediaFixture.zipOf(
            MediaFixture.SECOND_SOURCE_NAME to MediaFixture.SECOND_PATCH_SOURCE.toByteArray(Charsets.UTF_8),
            "eratw-pack.json" to MediaFixture.PACK_META_JSON.toByteArray(Charsets.UTF_8),
            "resources/mod/demo/b.png" to MediaFixture.PNG,
        )
        val outcome = controller.import(listOf(PickedModFile("kojo_media_2.ZIP", second)))
        val failed = outcome as? ModImportOutcome.Failed
            ?: throw AssertionError("跨包同名目录应整包拒导，实为 $outcome")

        assertEquals(ModImportFailureKind.STORAGE, failed.kind)
        assertTrue("文案点名目录：${failed.message}", failed.message.contains("demo"))
        assertTrue("文案点名先到包：${failed.message}", failed.message.contains(firstPackageId))
        assertTrue("文案点名本次包：${failed.message}", failed.message.contains(secondPackageId))
        assertTrue("先到包的文件不得被动", File(tmp, "resources/mod/demo/a.png").isFile)
        assertFalse("被拒包不得留清单", File(MediaFixture.rt2Of(tmp), "$secondPackageId.media").exists())
    }
}

/** 断言字节数组相等（JUnit4 的 `assertArrayEquals` 不在本仓既有测试的 import 口径里）。 */
private fun assertArrayEquals(message: String, expected: ByteArray, actual: ByteArray) {
    assertEquals(message, expected.toList(), actual.toList())
}

/**
 * 测试夹具（三个测试类共用；函数数控制在 detekt `TooManyFunctions` 的每对象 11 条上限内）。
 * 照 [ModImportAssetsTest] 的同款夹具复制：mini 侧车 + 合成 base 计数 + 内存 zip。
 */
private object MediaFixture {

    const val PKG = "kojo-media"
    const val MEDIA_DIR = "demo"
    const val SOURCE_NAME = "KOJO_MEDIA.ERB"
    const val SECOND_SOURCE_NAME = "KOJO_MEDIA_2.ERB"
    const val BASE_SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    const val PATCH_SOURCE = "@B_HELLO\nCALL A_ONE(3)\nPRINTL HELLO_PATCH\nRETURN 0\n"
    const val SECOND_PATCH_SOURCE = "@B_HELLO2\nCALL A_ONE(4)\nPRINTL HELLO_PATCH_2\nRETURN 0\n"
    const val PACK_META_JSON = "{\"schema\":1,\"packageId\":\"eratw.demo\",\"version\":\"1.0\",\"resources\":\"demo\"}"
    val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)

    fun rt2Of(tmp: File): File = File(File(tmp, "mods"), "rt2")

    fun residueOf(tmp: File): List<String> =
        rt2Of(tmp).list()?.filter { it.contains(".tmp-") || it.contains(".bak-") } ?: emptyList()

    fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
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

    fun stageInto(tmp: File, dirName: String?, vararg entries: Pair<String, ByteArray>): ModImportMediaAssets.Plan =
        stageIntoWithLimits(tmp, dirName, ModImportMediaAssets.MediaLimits(), *entries)

    fun stageIntoWithLimits(
        tmp: File,
        dirName: String?,
        limits: ModImportMediaAssets.MediaLimits,
        vararg entries: Pair<String, ByteArray>,
    ): ModImportMediaAssets.Plan = ModImportMediaAssets.stageSafely(
        listOf(zipOf(*entries)),
        ModImportMediaAssets.MediaTarget(PKG, dirName, tmp, rt2Of(tmp)),
        ModImportCopy.ZH,
        limits,
    )

    fun miniMetadata(): BootMetadata {
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

    fun miniBaseCounts(): PatchCompiler.BaseCounts = PatchCompiler.BaseCounts(
        instructionCount = 4000,
        functionCount = 4,
        statementCount = 100,
        stringCount = 10,
        longCount = 5,
        exprCount = 20,
    )

    fun contextOf(): BaseCompileContext = BaseCompileContext(miniMetadata(), miniBaseCounts(), BASE_SHA, csvDir = null)

    fun controllerIn(tmp: File): ModImportController = ModImportController(
        modStore = ModStore(File(tmp, "mods")),
        baseSource = { contextOf() },
        gameRoot = tmp,
    )

    fun imported(outcome: ModImportOutcome): ImportedPatchDetail =
        (outcome as? ModImportOutcome.Imported)?.detail
            ?: throw AssertionError("导入应成功，实为 $outcome")

    fun randomBytes(count: Int, seed: Long): ByteArray =
        ByteArray(count).also { java.util.Random(seed).nextBytes(it) }
}
