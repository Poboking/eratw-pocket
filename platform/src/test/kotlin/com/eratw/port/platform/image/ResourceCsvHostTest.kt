package com.eratw.port.platform.image

import com.eratw.port.core.host.ImageDecoderHost
import com.eratw.port.core.image.PngImage
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    // ------------------------------------------------------------------
    // CSV 图片路径大小写回退（11-11 Haruki 模拟器首启 ENOENT 72 条）
    // 立项与根因层见内部契约 RESIDUAL-CASEFOLD（未随公开仓发布）。
    //
    // 为什么不测 `File(gameRoot, path).isFile`：Windows NTFS 本身大小写不敏感，
    // 请求 `9_COM.webp` 直接就能打开 `9_com.webp`，那样单测在 Windows 上必然假绿，
    // 证不出 Android（ext4/f2fs 大小写敏感）的失败形状。这里钉的是纯枚举口径的
    // `resolveCaseInsensitive`：它按目录里**真实存在的文件名**做忽略大小写匹配，
    // 返回的 File 名字必须与真实名逐字符相同 —— 两平台行为一致、可反向证明。
    // ------------------------------------------------------------------

    /** 造 `resources/<dirName>/<fileName>` 最小树；回调用完自行删。 */
    private fun withImageTree(dirName: String, fileName: String, body: (File, File) -> Unit) {
        val root = Files.createTempDirectory("rescsv-case").toFile()
        try {
            val dir = File(root, "resources/$dirName").apply { mkdirs() }
            val image = File(dir, fileName).apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            body(root, image)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `精确路径逐段精确命中且返回真实名`() {
        withImageTree("As06MARK-IV", "9_com.webp") { root, image ->
            val got = ResourceCaseFold.fold(root, "resources/As06MARK-IV/9_com.webp")
            assertEquals(image.absolutePath, got?.absolutePath)
        }
    }

    @Test
    fun `文件名大小写不匹配回退到真实名`() {
        // 真机日志里命中 48 次的那一形态：CSV 写 102_COM.webp、盘上是 102_com.webp。
        withImageTree("As06MARK-IV", "102_com.webp") { root, image ->
            val got = ResourceCaseFold.fold(root, "resources/As06MARK-IV/102_COM.webp")
            assertEquals(image.absolutePath, got?.absolutePath)
        }
    }

    @Test
    fun `反向大小写不匹配同样回退`() {
        // 真机日志里 74_com.webp / 54_com.webp 请求小写、盘上是大写 COM。
        withImageTree("As06MARK-IV", "74_COM.webp") { root, image ->
            val got = ResourceCaseFold.fold(root, "resources/As06MARK-IV/74_com.webp")
            assertEquals(image.absolutePath, got?.absolutePath)
        }
    }

    @Test
    fun `目录段大小写不匹配也逐段回退`() {
        withImageTree("As06MARK-IV", "MeiLing_Sp_Expression_SuggestS.webp") { root, image ->
            val got = ResourceCaseFold.fold(
                root,
                "RESOURCES/AS06MARK-IV/meiling_sp_expression_suggests.webp",
            )
            assertEquals(image.absolutePath, got?.absolutePath)
        }
    }

    @Test
    fun `反斜杠分隔同样逐段回退`() {
        withImageTree("As06MARK-IV", "9_com.webp") { root, image ->
            val got = ResourceCaseFold.fold(root, """resources\As06MARK-IV\9_COM.webp""")
            assertEquals(image.absolutePath, got?.absolutePath)
        }
    }

    @Test
    fun `含点点段或空路径一律拒绝回退`() {
        withImageTree("As06MARK-IV", "9_com.webp") { root, _ ->
            assertNull(ResourceCaseFold.fold(root, ""))
            assertNull(ResourceCaseFold.fold(root, "resources/../9_com.webp"))
            assertNull(ResourceCaseFold.fold(root, "resources/./As06MARK-IV/9_COM.webp"))
        }
    }

    @Test
    fun `真缺文件返回 null 不误命中邻文件`() {
        withImageTree("As06MARK-IV", "9_com.webp") { root, _ ->
            assertNull(ResourceCaseFold.fold(root, "resources/As06MARK-IV/999_COM.webp"))
            assertNull(ResourceCaseFold.fold(root, "resources/NO_SUCH_DIR/9_COM.webp"))
        }
    }

    @Test
    fun `readBytes 接线到回退后读到真实图片字节`() {
        // Windows 上 `File(gameRoot, path).isFile` 本身就会因 NTFS 不敏感而命中，
        // 所以这条只作「四处接线没被改坏」的回归钉，不作回退语义的判据。
        withImageTree("As06MARK-IV", "9_com.webp") { root, _ ->
            val host = ResourceCsvHost(root, NoopDecoder)
            assertArrayEquals(
                byteArrayOf(1, 2, 3, 4),
                host.readBytes("resources/As06MARK-IV/9_COM.webp"),
            )
        }
    }
}
