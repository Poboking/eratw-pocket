package com.eratw.port.platform

import com.eratw.port.platform.input.KeyMacroController
import com.eratw.port.platform.input.MacroFile
import com.eratw.port.platform.storage.GameAssetInstaller
import com.eratw.port.platform.storage.GameAssetTree
import com.eratw.port.render.input.InputSession
import com.eratw.port.render.input.KeyMacroTable
import com.eratw.port.render.input.MacroKeys
import java.io.File
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * audit2 L16（平台存储 / 输入 / Canvas 视图）—— 本轮修复的边界用例。
 *
 * 1. **`macro.txt` 的 UTF-16 BOM**：原版 `EncodingHandler.DetectEncoding` 的第二步
 *    `new StreamReader(filePath, UTF8Encoding)`（`EncodingHandler.cs:27`）默认
 *    `detectEncodingFromByteOrderMarks: true`，UTF-16 BOM 会让 `CurrentEncoding` 变成
 *    `Unicode` / `BigEndianUnicode`，`:29-31` 随即返回它，`File.ReadAllLines` 再按它读
 *    （`EraStreamReader.cs:52`）。修复前本层只认 UTF-8 BOM，UTF-16 文本会落进 CP932 回落
 *    解成乱码（0xFF/0xFE 在 CP932 里是合法单字节）。
 * 2. **0 字节资产也是已知长度**：解包器类头「知道资产字节数时，目标存在且字节数相同才跳过，
 *    不同就重拷」。真实语料 `sound/` 下有 18 个零字节 `bgm` 标记，修复前 `expected > 0`
 *    把 0 当成「未知」，目标被写成非空也不会重拷；反向口径（压缩条目长度是 null）
 *    仍按存在性跳过，本用例钉住这条不退化。
 * 3. **一轮规划只探一次长度**：`AndroidAssetTree.fileSize` 对压缩条目靠
 *    `openFd` 抛 `FileNotFoundException` 回答 null（`AndroidAssetTree.kt:37-42`），
 *    修复前 `plan`/`walkTree` 每条资产问两遍 ⇒ 3,971 条语料每次启动白付两倍的填栈成本。
 * 4. **宏菜单的 `console != null` 闸**：`MainWindow.cs:1567` 的菜单处理器先判
 *    `console != null` 才读宏、写框；[InputSession.consolePresent] 就是这一个条件。
 *
 * Canvas 的两处修复（图形段 `Config.DrawingParam_ShapePositionShift`、背景 cover 的
 * `BaseImage.Bitmap` 基准）在 `:render-canvas`，该模块没有单测装置，由
 * `:render-canvas:compileDebugKotlin` 过闸。
 */
class PlatformAudit2Test {

    @get:Rule
    val temp = TemporaryFolder()

    // ------------------------------------------------------------------
    // 1. macro.txt 的 UTF-16 BOM
    // ------------------------------------------------------------------

    @Test
    fun `UTF-16LE BOM 的宏文件按 Unicode 读入且剥离 BOM`() {
        val body = "グループ3:第三组\r\n宏快捷键F1:２０\r\n"
        val file = temp.newFile("macro-le.txt")
        file.writeBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + body.toByteArray(Charsets.UTF_16LE))

        val table = MacroFile.load(file)

        assertNotNull("UTF-16LE 是原版 DetectEncoding 认的第四种编码，不能当打不开文件", table)
        assertEquals("BOM 已剥掉，组行按原样匹配（KeyMacro.cs:105-120）", "第三组", table!!.groupName(3))
        assertEquals("宏名/内容逐字符读入", "２０", table.get(0, 0))
    }

    /**
     * 组行的数字决定组号，但**宏行的名称前缀才决定槽位**：简中界面下组 0 的名是
     * 「宏快捷键F{n}:」、组 g>0 的是「G{g}:宏快捷键F{n}:」（`KeyMacro.cs:49`、`:53` 经
     * `Lang.KeyMacro.MacroKeyF` / `GMacroKeyF`，`KeyMacroTable.NameScheme.SIMPLIFIED_CHINESE`），
     * 读盘按名称扫 0..119 槽、命中即 break（`:121-131`）。所以「グループ5:」换不了宏行的落点，
     * 本用例同时钉住两支：组名进组 5、两条宏行分别进组 0 与组 5 的槽。
     */
    @Test
    fun `UTF-16BE BOM 的宏文件按 BigEndianUnicode 读入`() {
        val body = "グループ5:第五组\r\n宏快捷键F2:零组\r\nG5:宏快捷键F2:五组\r\n"
        val file = temp.newFile("macro-be.txt")
        file.writeBytes(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + body.toByteArray(Charsets.UTF_16BE))

        val table = MacroFile.load(file)

        assertNotNull(table)
        assertEquals("第五组", table!!.groupName(5))
        assertEquals("无 G 前缀的名称只认组 0 槽（KeyMacro.cs:123-129）", "零组", table.get(1, 0))
        assertEquals("G5 前缀才落到组 5 槽", "五组", table.get(1, 5))
    }

    @Test
    fun `只有 BOM 的 UTF-16 文件是空表而不是打不开文件`() {
        // File.ReadAllLines 对「BOM + 零行正文」返回空数组（EraStreamReader.cs:52 不抛），
        // LoadMacroFile 因此正常返回、120 个槽保持 ResetNames 的清空态。
        assertEquals("", MacroFile.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte())))
        val file = temp.newFile("macro-bom-only.txt")
        file.writeBytes(byteArrayOf(0xFE.toByte(), 0xFF.toByte()))
        assertNotNull(MacroFile.load(file))
    }

    @Test
    fun `没有 BOM 的 UTF-16 不按 UTF-16 猜_仍回落 CP932`() {
        // 原版的 UTF-16 只由 BOM 触发：无 BOM 时 DetectEncoding 仍是 UTF-8 试解失败 →
        // Shift-JIS 回落（EncodingHandler.cs:27-39）。本用例防止「按 0x00 分布猜编码」。
        val text = "グループ0:あ"
        val noBom = text.toByteArray(Charsets.UTF_16LE)

        assertNotEquals("无 BOM 不得猜成 UTF-16", text, MacroFile.decode(noBom))
    }

    /**
     * 存盘 → 读回的闭环：`SaveMacro` 写 10 行组名 + 120 行「名称前缀 + 内容」
     * （`KeyMacro.cs:69-76`），`LoadMacroFile` 按同一批名称扫槽（`:99-133`）。
     * 用 UTF-16LE + BOM 落盘（原版 `Config.Encode` 允许任何编码，`KeyMacro.cs:68`）——
     * 只有本文件第 2 条的 BOM 分支认得它，读回必须逐槽一致。
     */
    @Test
    fun `UTF-16LE 存盘的宏表能原样读回`() {
        val origin = KeyMacroTable(KeyMacroTable.NameScheme.SIMPLIFIED_CHINESE)
        origin.loadLines(
            listOf(
                "グループ7:第七组",
                origin.nameOf(0, 0) + "零组宏",
                origin.nameOf(11, 7) + "七组第十二键",
            ),
        )
        val file = temp.newFile("macro-roundtrip.txt")
        val body = origin.serializeLines().joinToString("\r\n") + "\r\n"
        file.writeBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + body.toByteArray(Charsets.UTF_16LE))

        val reloaded = MacroFile.load(file)

        assertNotNull("UTF-16LE 存盘读不回来 ⇒ Shift+功能键记录的宏下次开机全丢", reloaded)
        assertEquals("第七组", reloaded!!.groupName(7))
        assertEquals("零组宏", reloaded.get(0, 0))
        assertEquals("七组第十二键", reloaded.get(11, 7))
        assertEquals("120 槽逐槽相同", origin.snapshot(), reloaded.snapshot())
    }

    // ------------------------------------------------------------------
    // 2. 0 字节资产的重拷判定
    // ------------------------------------------------------------------

    /**
     * 真实语料有 18 个零字节 `bgm` 标记（`app/build.gradle.kts` 的体积测算段列举）。
     * `GameAssetTree.fileSize` 对 STORED 条目返回 **0 也是有效长度**（`>= 0`），
     * 所以目标存在但非空时必须按「字节数不同」重拷，而不是当未知长度跳过。
     */
    @Test
    fun `0 字节资产的目标被写成非空时重拷为 0 字节`() {
        val assets = temp.newFolder("assets")
        val gameRoot = File(temp.newFolder("files"), "eratw")
        asset(assets, "sound/游戏bgm/町/bgm", ByteArray(0))
        val target = File(gameRoot, "sound/游戏bgm/町/bgm").apply {
            parentFile?.mkdirs()
            writeText("上一轮残留")
        }
        assertTrue("前置：目标存在且字节数不同", target.isFile && target.length() != 0L)

        val result = GameAssetInstaller(Tree(assets), gameRoot).install()

        assertEquals("0 字节是已知长度，必须重拷", 1, result.copiedFiles)
        assertEquals(0L, target.length())

        val second = GameAssetInstaller(Tree(assets), gameRoot).install()
        assertEquals("重拷后第二轮命中字节数相同 ⇒ 跳过", 0, second.copiedFiles)
        assertEquals(0L, target.length())
    }

    @Test
    fun `0 字节资产在目标缺失时照常落盘`() {
        val assets = temp.newFolder("assets")
        val gameRoot = File(temp.newFolder("files"), "eratw")
        asset(assets, "sound/marker", ByteArray(0))

        val result = GameAssetInstaller(Tree(assets), gameRoot).install()

        assertEquals(1, result.copiedFiles)
        assertTrue("空文件也要存在", File(gameRoot, "sound/marker").isFile)
        assertEquals(0L, File(gameRoot, "sound/marker").length())
    }

    /**
     * 「0 字节也是已知长度」只覆盖 STORED 条目：压缩条目 `openFd` 抛异常 ⇒ 长度是 null
     * （`AndroidAssetTree.kt:37-42`），判据退回「目标存在即已装」。所以一个非空的残留目标
     * 在这一路**不会**被重拷 —— 这是刻意不退化成解压比大小的那条口径（`GameAssetInstallerTest`
     * 的「压缩条目拿不到大小时目标存在即跳过」），0 字节规则不许把它改掉。
     */
    @Test
    fun `压缩条目的零字节资产仍按存在性跳过`() {
        val assets = temp.newFolder("assets")
        val gameRoot = File(temp.newFolder("files"), "eratw")
        asset(assets, "sound/marker", ByteArray(0))
        val target = File(gameRoot, "sound/marker").apply {
            parentFile?.mkdirs()
            writeText("stale")
        }

        val result = GameAssetInstaller(Tree(assets, sizesKnown = false), gameRoot).install()

        assertEquals("长度拿不到 ⇒ 不比大小", 0, result.copiedFiles)
        assertEquals("残留原样保留（不为比大小而解压）", 5L, target.length())
    }

    /**
     * 一轮规划里每条资产**最多问一次** `fileSize`：`AndroidAssetTree.fileSize` 对压缩条目
     * 走的是「openFd 抛 FileNotFoundException」那条路（填栈成本），3,971 条语料 × 2 次
     * 是每次启动都付的白给开销 —— 修复前 `plan`/`walkTree` 各问两遍。
     */
    @Test
    fun `一轮规划里每条资产的长度只问一次`() {
        val assets = temp.newFolder("assets")
        val gameRoot = File(temp.newFolder("files"), "eratw")
        listOf("CSV/a.csv", "CSV/b.csv", "resources/x.png", "sound/marker").forEach {
            asset(assets, it, ByteArray(3))
        }
        val tree = CountingTree(assets)

        // 第一轮：目标全缺；第二轮：目标全在位（这才是每次启动都会走的那条路）。
        for (round in 1..2) {
            tree.probes.clear()
            val result = GameAssetInstaller(tree, gameRoot).install()
            assertEquals("第 $round 轮的落盘数", if (round == 1) 4 else 0, result.copiedFiles)
            assertTrue("第 $round 轮一条都没问长度 ⇒ 规划根本没跑", tree.probes.isNotEmpty())
            tree.probes.forEach { (path, count) ->
                assertTrue("$path 被问了 $count 次长度", count <= 1)
            }
        }
    }

    // ------------------------------------------------------------------
    // 3. 宏菜单的 console != null 闸
    // ------------------------------------------------------------------

    /**
     * `MainWindow.cs:1567`：`console != null && !console.IsInProcess && Config.UseKeyMacro`
     * 才读 `KeyMacro.GetMacro` 并写框。修复前 [KeyMacroController.prefillFromMenu] 只把
     * `inProcess` 交给判定，没有控制台时仍会覆盖输入框。
     */
    @Test
    fun `没有控制台时菜单预填不读宏也不动框`() {
        val scheme = KeyMacroTable.NameScheme.SIMPLIFIED_CHINESE
        val table = KeyMacroTable(scheme)
        table.loadLines(listOf(table.nameOf(0, 0) + "菜单宏"))
        val controller = KeyMacroController(InputSession(macroTable = table))
        controller.session.box.assign("旧内容")

        controller.session.consolePresent = false
        val blocked = controller.prefillFromMenu(fkey = 0)

        assertTrue("console == null ⇒ 整条不响应（MainWindow.cs:1567）", blocked is MacroKeys.Decision.Ignored)
        assertEquals("框保持原样", "旧内容", controller.session.box.text)

        controller.session.consolePresent = true
        controller.session.inProcess = true
        assertTrue(
            "console.IsInProcess 同样挡住菜单（同一条 :1567）",
            controller.prefillFromMenu(fkey = 0) is MacroKeys.Decision.Ignored,
        )
        assertEquals("旧内容", controller.session.box.text)

        controller.session.inProcess = false
        assertTrue(
            "守卫都放开后才是 Prefill（:1570-1574 的 macro.Length > 0 已满足）",
            controller.prefillFromMenu(fkey = 0) is MacroKeys.Decision.Prefill,
        )
        assertEquals("菜单宏", controller.session.box.text)
    }

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    private fun asset(root: File, rel: String, bytes: ByteArray) {
        File(root, rel).apply {
            parentFile?.mkdirs()
            writeBytes(bytes)
        }
    }

    /**
     * 临时目录当 assets 根：`fileSize` 返回真实长度（等价 STORED 条目，0 字节也返回 0）；
     * [sizesKnown] = false 时一律返回 null，等价 APK 里的 DEFLATE 条目
     * （`AndroidAssetTree.kt:37-42`：压缩条目 `openFd` 抛 ⇒ null）。
     */
    private open class Tree(
        private val root: File,
        private val sizesKnown: Boolean = true,
    ) : GameAssetTree {

        override fun openFile(path: String): InputStream? {
            val file = File(root, path)
            return if (file.isFile) file.inputStream() else null
        }

        override fun listDir(path: String): List<String> {
            val dir = File(root, path)
            return if (dir.isDirectory) dir.list().orEmpty().sorted() else emptyList()
        }

        override fun fileSize(path: String): Long? {
            if (!sizesKnown) return null
            val file = File(root, path)
            return if (file.isFile) file.length() else null
        }
    }

    /** 记 `fileSize` 探针次数的 Tree（每轮规划清空计数）。 */
    private class CountingTree(root: File) : Tree(root) {
        val probes = HashMap<String, Int>()

        override fun fileSize(path: String): Long? {
            probes[path] = (probes[path] ?: 0) + 1
            return super.fileSize(path)
        }
    }
}
