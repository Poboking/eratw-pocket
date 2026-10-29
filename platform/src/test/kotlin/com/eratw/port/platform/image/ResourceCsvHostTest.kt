package com.eratw.port.platform.image

import com.eratw.port.core.host.ImageDecoderHost
import com.eratw.port.core.image.PngImage
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ResourceCsvHost] 的**纯逻辑面**（扫盘判定 + 相对路径换算）—— `AndroidApiSurfaceGuardTest`
 * 登记要求 a 的落地：这个类只允许在 `imageSize` 里碰 `BitmapFactory`，其余判定必须在纯 JVM
 * 下有覆盖（`android.jar` 是桩，`unitTests.isReturnDefaultValues` ⇒ 桩下调用恒返回缺省值）。
 *
 * 为什么要单独钉「路径分隔符」：`relativeOf` 的产物是 CSV 第二列图片路径的拼接口径
 * （`AppContents.cs` 的 `ContentDir` 相对路径），真机 `File.separatorChar` 恒 `/`，
 * 而这条测试跑在 Windows（`\`）上。实现若不认 `\`，真机对、CI 红；实现若**依赖**
 * `File.separatorChar`，两边产物不同 —— 两种都得挡掉，所以这里两种分隔符都喂。
 */
class ResourceCsvHostTest {

    private object NoopDecoder : ImageDecoderHost {
        override fun decode(path: String, bytes: ByteArray): PngImage? = null
    }

    // ------------------------------------------------------------------
    // 相对路径换算
    // ------------------------------------------------------------------

    @Test
    fun `根下的路径换算成相对路径`() {
        assertEquals(
            "resources/csv/chara.csv",
            ResourceCsvHost.relativeOf("/sdcard/eratw", "/sdcard/eratw/resources/csv/chara.csv"),
        )
    }

    @Test
    fun `Windows 反斜杠同样换算成正斜杠`() {
        assertEquals(
            "resources/csv/chara.csv",
            ResourceCsvHost.relativeOf(
                """C:\games\eratw""",
                """C:\games\eratw\resources\csv\chara.csv""",
            ),
        )
    }

    @Test
    fun `根尾部的分隔符不影响结果`() {
        // 调用方可能传带尾分隔符的根（`/sdcard/eratw/`）；少 trim 一个字符就会得到
        // `/resources/...` 这种前导斜杠，拼出来的图片路径多一级、资源查找全 miss。
        assertEquals(
            "resources/a.csv",
            ResourceCsvHost.relativeOf("/sdcard/eratw/", "/sdcard/eratw/resources/a.csv"),
        )
        assertEquals(
            "resources/a.csv",
            ResourceCsvHost.relativeOf("""C:\games\eratw\""", """C:\games\eratw\resources\a.csv"""),
        )
    }

    @Test
    fun `不在根下的路径原样保留但去前导分隔符`() {
        // 兜底分支（`full` 不以 `base` 开头）：返回绝对路径的相对形态，至少保证分隔符统一，
        // 而不是抛错 —— 扫盘用的是 `gameRoot` 的子树，正常走不到，但换算函数不能因此炸。
        assertEquals(
            "other/x.csv",
            ResourceCsvHost.relativeOf("/sdcard/eratw", "/other/x.csv"),
        )
    }

    // ------------------------------------------------------------------
    // CSV 后缀判定
    // ------------------------------------------------------------------

    @Test
    fun `CSV 后缀大小写不敏感`() {
        assertTrue(ResourceCsvHost.isCsvName("a.csv"))
        assertTrue(ResourceCsvHost.isCsvName("A.CSV"))
        assertTrue(ResourceCsvHost.isCsvName("混合.Csv"))
        assertFalse(ResourceCsvHost.isCsvName("a.csv.bak"))
        assertFalse(ResourceCsvHost.isCsvName("a.png"))
        assertFalse(ResourceCsvHost.isCsvName("csv"))
    }

    // ------------------------------------------------------------------
    // 扫盘端到端（真临时目录，不碰 Android API）
    // ------------------------------------------------------------------

    @Test
    fun `listCsvFiles 只收 resources 子树下的 csv 并给相对路径`() {
        val gameRoot = Files.createTempDirectory("rescsv").toFile()
        try {
            val csvDir = File(gameRoot, "resources/csv").apply { mkdirs() }
            File(csvDir, "chara.csv").writeText("a,b\n")
            File(csvDir, "UPPER.CSV").writeText("a,b\n")
            File(csvDir, "note.txt").writeText("x")
            // resources 之外的同名文件不收（原版只扫 ContentDir 子树）。
            File(gameRoot, "outside.csv").writeText("a,b\n")
            // resources 不存在时返回空表（不是抛错）。
            val host = ResourceCsvHost(gameRoot, NoopDecoder)
            val listed = host.listCsvFiles().sorted()
            assertEquals(
                listOf("resources/csv/UPPER.CSV", "resources/csv/chara.csv"),
                listed,
            )
            assertTrue("相对路径必须正斜杠分隔：$listed", listed.none { it.contains('\\') })
        } finally {
            gameRoot.deleteRecursively()
        }
    }

    @Test
    fun `resources 目录不存在时返回空表`() {
        val gameRoot = Files.createTempDirectory("rescsv-empty").toFile()
        try {
            assertTrue(ResourceCsvHost(gameRoot, NoopDecoder).listCsvFiles().isEmpty())
        } finally {
            gameRoot.deleteRecursively()
        }
    }
}
