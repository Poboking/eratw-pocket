package com.eratw.port.platform.storage

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * **块级差量**（P2-13，U8）的设备侧应用面。
 *
 * 钉的是这条通道唯一会赔大钱的地方：补丁拼错了**不会报错**，只会得到一份「版本号说自己是
 * 第 8 版、内容却差了几个块」的语料，装载侧对着立绘和 CSV 都看不出不对。所以判式全部围绕
 * 三件事：
 *
 * 1. **还原必须逐字节等于发布侧算的那一份**（第一节）。夹具吃的是 `devtools/emit_update.py`
 *    真跑出来的补丁（Base64 原样嵌在本文件末），不是 Kotlin 照着格式又写一遍 ——
 *    同错写法互证抓不住「两侧对同一个字节序理解不同」这类系统性错位。
 * 2. **分块边界**（第二节）：空文件 / 单块 / 恰整块 / 尾部不足一块。这四形是按块算法最容易
 *    差一个字节的地方，而差的那一节恰好在文件末尾，人眼看不出来。
 * 3. **坏补丁必判死**（第三、四节）：基线不符、结果摘要不符、op 条数不符、块号越界、
 *    未知标签、形状不合格 —— 全部走「抛 / 计入拒绝数」，没有一条允许「挑能用的继续装」。
 *
 * 第五节是**整单口径**：补丁读到末尾才发现不对时，盘上必须还是上一版的完整文件
 * （`.tmp` 被清掉、目标没被改名），删除表那一拍根本没执行，摘要也不写 ——
 * 这就是「回落全量而不是留下半份语料」在解包侧的落点，与现行文件级差量共用同一套回滚。
 *
 * 与 [DiffAssetApplyTest] 同口径：纯 JVM、临时目录当游戏根、不建 `Context`。
 * 「选块级还是选文件级还是选全量」不在本文件（那是 core 的 `UpdatePolicy`，见
 * `BlockDeltaPolicyTest`）。
 */
class BlockDeltaAssetTreeTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var gameRoot: File

    @Before
    fun setUp() {
        gameRoot = File(temp.newFolder("files"), "eratw")
    }

    // ------------------------------------------------------------------
    // 一、跨语言判式：发布侧 Python 产出的补丁，设备侧逐字节还原
    // ------------------------------------------------------------------

    @Test
    fun `发布侧产出的三种补丁都能逐字节还原，且 fileSize 报的是还原后的长度`() {
        disk("resources/a.bin", OLD)
        for (case in listOf(WHOLE_COPY, EXACT_BLOCK, BOUNDARY_INS)) {
            val zip = bundleOf(
                patchEntry("resources/a.bin", case.patch),
                ZipAssetTree.DELTA_LIST_NAME to ByteArray(0),
            )
            val tree = BlockDeltaAssetTree(ZipAssetTree(zip), gameRoot)
            assertEquals("${case.name}：形状闸不该有拒绝", 0, tree.rejectedEntryCount)
            assertTrue("${case.name}：这条路径要认成补丁目标", tree.isPatched("resources/a.bin"))
            assertEquals("${case.name}：fileSize 报还原后字节数",
                case.expected.size.toLong(), tree.fileSize("resources/a.bin"))
            val got = tree.openFile("resources/a.bin")!!.readBytes()
            assertArrayEquals("${case.name}：拼出来的必须与发布侧同一条目逐字节相等", case.expected, got)
        }
    }

    @Test
    fun `分块尺寸与 op 条数由补丁自己声明，读满与逐字节读两种取法同结果`() {
        disk("resources/a.bin", OLD)
        val tree = blockTree(
            bundleOf(patchEntry("resources/a.bin", BOUNDARY_INS.patch)))
        val buffered = tree.openFile("resources/a.bin")!!.readBytes()
        // 一条一条读：走的是 read() 单字节入口（状态机每步都要正确，不能只在大缓冲下对）。
        val one = ByteArrayOutputStream()
        tree.openFile("resources/a.bin")!!.use { input ->
            while (true) {
                val b = input.read()
                if (b < 0) break
                one.write(b)
            }
        }
        assertArrayEquals("单字节读与整块读必须给出同一份字节", buffered, one.toByteArray())
        assertEquals("夹具自己：中部插 2 字节要 4 条 op", 4, opCountOf(BOUNDARY_INS.patch))
    }

    @Test
    fun `盘上那份基线被换过就必须拒绝（apply 前验 sha256）`() {
        // 盘上同名但内容不是补丁假设的那一版：块号全部失去意义，拼出来是一份自洽的错语料。
        val shifted = OLD.copyOf()
        shifted[0] = ((shifted[0].toInt() + 1) and 0xFF).toByte()
        disk("resources/a.bin", shifted)
        val tree = blockTree(bundleOf(patchEntry("resources/a.bin", WHOLE_COPY.patch)))
        val boom = assertThrows(AssetInstallException::class.java) {
            tree.openFile("resources/a.bin")!!.readBytes()
        }
        assertTrue("文案要说清是基线不符：${boom.message}", boom.message!!.contains("基线"))
    }

    @Test
    fun `基线文件根本不在盘上时报可读的失败并点名哪一条`() {
        File(gameRoot, "resources").mkdirs()
        val tree = blockTree(bundleOf(patchEntry("resources/a.bin", WHOLE_COPY.patch)))
        val boom = assertThrows(AssetInstallException::class.java) {
            tree.openFile("resources/a.bin")!!.readBytes()
        }
        assertTrue("文案要点名是哪条：${boom.message}",
            boom.message!!.contains("旧文件不在盘上") && boom.message!!.contains("a.bin"))
    }

    // ------------------------------------------------------------------
    // 二、分块边界
    // ------------------------------------------------------------------

    @Test
    fun `空文件边界：零条 op 还原成零字节，不当成没这条资产`() {
        disk("resources/a.bin", OLD)
        val tree = blockTree(bundleOf(patchEntry("resources/a.bin", EMPTY_RESULT.patch)))
        assertEquals("声明的就是 0 字节", 0L, tree.fileSize("resources/a.bin"))
        assertArrayEquals(ByteArray(0), tree.openFile("resources/a.bin")!!.readBytes())
    }

    @Test
    fun `单块、恰整块、两块相接三种边界都逐字节对得上`() {
        disk("resources/a.bin", OLD)
        for (case in listOf(EXACT_BLOCK, COPY_THEN_COPY, SHORT_TAIL)) {
            val tree = blockTree(bundleOf(patchEntry("resources/a.bin", case.patch)))
            assertArrayEquals("${case.name}：块边界必须逐字节对得上", case.expected,
                tree.openFile("resources/a.bin")!!.readBytes())
        }
        assertEquals("恰整块：新文件正好一块", 4096, EXACT_BLOCK.expected.size)
        assertEquals("两块相接：8192", 8192, COPY_THEN_COPY.expected.size)
        assertEquals("短尾就是短尾：不补齐到整块", 4097, SHORT_TAIL.expected.size)
    }

    @Test
    fun `块长取自补丁自己，不是编译进 APK 的常量`() {
        // 8192 块长的补丁：同一份基线，op 只有一条 COPY0（第 0 块 = 前 8192 字节）。
        val new = OLD.copyOfRange(0, 8192)
        val patch = headerOf(sha256Hex(OLD), sha256Hex(new), new.size, 1, block = 8192) + copyOp(0)
        disk("resources/a.bin", OLD)
        val tree = blockTree(bundleOf(patchEntry("resources/a.bin", patch)))
        assertArrayEquals(new, tree.openFile("resources/a.bin")!!.readBytes())
    }

    // ------------------------------------------------------------------
    // 三、坏补丁（形状面）：进拒绝计数，调用方那条整包拒装闸因此成立
    // ------------------------------------------------------------------

    @Test
    fun `头部形状不合格的补丁计入拒绝数而不是静默少一条`() {
        disk("resources/a.bin", OLD)
        val body = latin1Of(WHOLE_COPY.patch)
        val badMagic = latin1("XBPD1\n" + body.substring(6))
        val badFmt = latin1(body.replace("fmt 1", "fmt 2"))
        val badSha = latin1(body.replace(sha256Hex(OLD), "zz".repeat(32)))
        // 把头部结尾的空行拆掉（Kotlin 的 String.replace 没有 count 重载，这里手工切一次）
        val blankAt = body.indexOf("\n\n")
        val noBlankLine = latin1(body.substring(0, blankAt) + "\n" + body.substring(blankAt + 2))
        val blockZero = latin1(body.replace("block 4096", "block 0"))
        for ((label, bytes) in listOf(
            "魔数" to badMagic, "fmt 版本" to badFmt, "摘要形状" to badSha,
            "头部没收在空行" to noBlankLine, "块长为 0" to blockZero,
        )) {
            val tree = blockTree(bundleOf(patchEntry("resources/a.bin", bytes)))
            assertEquals("$label 不合格的补丁必须进拒绝数", 1, tree.rejectedEntryCount)
            assertEquals("$label 不合格时本类自己也要报出这一条", 1, tree.ownRejectedCount)
            assertFalse("$label 不合格时这条路径不该当资产存在", tree.isPatched("resources/a.bin"))
            assertNull(tree.fileSize("resources/a.bin"))
        }
    }

    @Test
    fun `控制目录不出现在根一层，补丁目标却能被列出来`() {
        disk("resources/a.bin", OLD)
        val zip = bundleOf(
            patchEntry("resources/a.bin", EXACT_BLOCK.patch),
            "CSV/whole.txt" to "整条重发的那一条".toByteArray(),
        )
        val tree = blockTree(zip)
        assertFalse("update-blocks 是控制目录，不该让解包器当成资产目录走一遍",
            tree.listDir("").contains(BlockDeltaAssetTree.PATCH_ROOT_NAME))
        assertTrue("补丁目标要能在父目录里列出来，否则 plan() 根本看不见它",
            tree.listDir("resources").contains("a.bin"))
        assertTrue("非补丁路径照常透传", tree.listDir("CSV").contains("whole.txt"))
        assertArrayEquals("整条重发的那一条读起来与直接读 zip 相同",
            "整条重发的那一条".toByteArray(), tree.openFile("CSV/whole.txt")!!.readBytes())
        assertNull("不是补丁目标的路径仍然报「包里没有」", tree.openFile("CSV/nope.txt"))
    }

    @Test
    fun `补丁条目名越出游戏根或资产范围时计入拒绝数`() {
        // `update-blocks/../CSV/x` 归一后带 `..` 段 → safeEntryName 判死；
        // `update-blocks/font/x` 归一后合法但落在解包器管不着的范围 → 资产范围闸判死。
        assertEquals(1, blockTree(
            bundleOf(patchEntryRel("../CSV/x", WHOLE_COPY.patch))).rejectedEntryCount)
        assertEquals(1, blockTree(
            bundleOf(patchEntryRel("font/x.ttf", WHOLE_COPY.patch))).rejectedEntryCount)
        assertEquals("合法目标不该被误杀", 0, blockTree(
            bundleOf(patchEntryRel("CSV/x.txt", WHOLE_COPY.patch))).rejectedEntryCount)
    }

    // ------------------------------------------------------------------
    // 四、坏补丁：还原阶段的五条判死线
    // ------------------------------------------------------------------

    @Test
    fun `结果摘要不符必须判死（这就是坏差量回落全量的触发点）`() {
        disk("resources/a.bin", OLD)
        // 只把 result-sha256 换成**另一份合法十六进制**：op 流照拼、长度照对，只有内容不对。
        // 取 boundary_ins 这条是因为它的 base 与 result 两个摘要不同值，改得出「只改结果」。
        val real = sha256Hex(BOUNDARY_INS.expected)
        val tampered = latin1(latin1Of(BOUNDARY_INS.patch).replace(real, sha256Hex("别的字节".toByteArray())))
        val tree = blockTree(bundleOf(patchEntry("resources/a.bin", tampered)))
        val boom = assertThrows(AssetInstallException::class.java) {
            tree.openFile("resources/a.bin")!!.readBytes()
        }
        assertTrue("要说清是拼出来不对：${boom.message}", boom.message!!.contains("摘要不符"))
    }

    @Test
    fun `op 条数与声明不符必判死（少一条与被追加过都算）`() {
        disk("resources/a.bin", OLD)
        // 声明 3 条只发 2 条：读到末尾时条数对不上。
        val truncated = headerOf(sha256Hex(OLD), sha256Hex(OLD), OLD.size, 3) + copyOp(0) + copyOp(1)
        // 声明 1 条却挂了 2 条：多出来的那条是「补丁被人追加过」。
        val firstOnly = OLD.copyOfRange(0, 4096)
        val extra = headerOf(sha256Hex(OLD), sha256Hex(firstOnly), firstOnly.size, 1) +
            copyOp(0) + copyOp(1)
        for ((label, bytes) in listOf("少了" to truncated, "多了" to extra)) {
            val tree = blockTree(bundleOf(patchEntry("resources/a.bin", bytes)))
            val boom = assertThrows(AssetInstallException::class.java) {
                tree.openFile("resources/a.bin")!!.readBytes()
            }
            assertTrue("$label 的情况必须报 op 条数：${boom.message}",
                boom.message!!.contains("op 条数") || boom.message!!.contains("op 比声明的还多"))
        }
    }

    @Test
    fun `块号越界与未知 op 标签必判死，不静默截短`() {
        disk("resources/a.bin", OLD)
        val outOfRange = headerOf(sha256Hex(OLD), sha256Hex(OLD), 4096, 1) + copyOp(99)
        val treeOut = blockTree(bundleOf(patchEntry("resources/a.bin", outOfRange)))
        val boomOut = assertThrows(AssetInstallException::class.java) {
            treeOut.openFile("resources/a.bin")!!.readBytes()
        }
        assertTrue("越界块号要点名：${boomOut.message}", boomOut.message!!.contains("不存在的块"))

        val unknownTag = headerOf(sha256Hex(OLD), sha256Hex(OLD), 4096, 1) +
            byteArrayOf('X'.code.toByte(), 0, 0, 0, 1)
        val treeTag = blockTree(bundleOf(patchEntry("resources/a.bin", unknownTag)))
        val boomTag = assertThrows(AssetInstallException::class.java) {
            treeTag.openFile("resources/a.bin")!!.readBytes()
        }
        assertTrue("未知标签要报出来：${boomTag.message}", boomTag.message!!.contains("未知 op 标签"))
    }

    @Test
    fun `字面量段声明比实际长时报截断而不是少给字节`() {
        disk("resources/a.bin", OLD)
        val head = OLD.copyOfRange(0, 4096) + "12345".toByteArray()
        val runDeclaredButShort = byteArrayOf('R'.code.toByte(), 0, 0, 0, 10) + "12345".toByteArray()
        val cut = headerOf(sha256Hex(OLD), sha256Hex(head), head.size, 2) + copyOp(0) + runDeclaredButShort
        val tree = blockTree(bundleOf(patchEntry("resources/a.bin", cut)))
        val boom = assertThrows(AssetInstallException::class.java) {
            tree.openFile("resources/a.bin")!!.readBytes()
        }
        assertTrue("要报截断：${boom.message}", boom.message!!.contains("截断"))
    }

    @Test
    fun `拼出的字节数超过声明时当场判死，不给无限输出`() {
        disk("resources/a.bin", OLD)
        // 声明 result-size = 10，op 却要拷一整块：绝不能在调用方要多少就给多少。
        val over = headerOf(sha256Hex(OLD), "0".repeat(64), 10, 1) + copyOp(0)
        val tree = blockTree(bundleOf(patchEntry("resources/a.bin", over)))
        val stream = tree.openFile("resources/a.bin")!!
        val boom = assertThrows(AssetInstallException::class.java) { stream.readBytes() }
        assertTrue("要报超出声明：${boom.message}", boom.message!!.contains("超过声明"))
    }

    @Test
    fun `长度声明比实际拼出来的短时同样判死`() {
        disk("resources/a.bin", OLD)
        val short = headerOf(sha256Hex(OLD), sha256Hex(OLD), OLD.size + 1, 3) +
            copyOp(0) + copyOp(1) + copyOp(2)
        val tree = blockTree(bundleOf(patchEntry("resources/a.bin", short)))
        val boom = assertThrows(AssetInstallException::class.java) { tree.openFile("resources/a.bin")!!.readBytes() }
        assertTrue("要报长度不符：${boom.message}", boom.message!!.contains("长度不符"))
    }

    // ------------------------------------------------------------------
    // 五、整单口径：与解包器一起跑
    // ------------------------------------------------------------------

    @Test
    fun `块级档应用成功后盘上是新内容，删除表照常兑现，控制条目不落盘`() {
        // 先用**全量档**装一遍并记下第 7 版的摘要：块级档是差量档，没有上一份摘要就不写摘要
        // （半张表会让下次体检假报「一致」，这条口径是解包侧既有的，本路不动它）。
        val full = bundleOf(
            "resources/a.bin" to OLD,
            "resources/gone.bin" to "该删的旧立绘".toByteArray(),
            "plugins/mine.xml" to "玩家自己加的 mod 配置".toByteArray(),
        )
        GameAssetInstaller(ZipAssetTree(full), gameRoot, forceReplace = true, recordDataVersion = 7L).install()
        val seeded = GameAssetInstaller.readDataDigest(gameRoot)
        assertNotNullSeed(seeded)
        assertEquals(7L, seeded!!.version)

        val zip = bundleOf(
            patchEntry("resources/a.bin", BOUNDARY_INS.patch),
            ZipAssetTree.DELTA_LIST_NAME to "resources/gone.bin\n".toByteArray(),
        )
        val tree = blockTree(zip)
        assertTrue("块级包同样带删除表 ⇒ 仍是差量档", tree.isDeltaPackage)
        val result = GameAssetInstaller(tree, gameRoot, forceReplace = true, recordDataVersion = 8L).install()
        assertEquals("块级档只落这一条", 1, result.copiedFiles)
        assertEquals("删除表在新路径下仍生效", 1, result.deletedFiles)
        assertEquals("没有撞判据的行", 0, result.blockedDeletions)
        assertNull("摘要写得下去", result.recordProblem)

        assertArrayEquals("盘上是逐字节还原后的新内容", BOUNDARY_INS.expected,
            File(gameRoot, "resources/a.bin").readBytes())
        assertFalse("表内那条已消失", File(gameRoot, "resources/gone.bin").exists())
        assertTrue("表外的玩家文件一个字节都不动", File(gameRoot, "plugins/mine.xml").isFile)
        assertFalse("控制目录绝不落进游戏根", File(gameRoot, BlockDeltaAssetTree.PATCH_ROOT_NAME).exists())
        assertFalse("控制条目本身也不落盘", File(gameRoot, ZipAssetTree.DELTA_LIST_NAME).exists())

        val after = GameAssetInstaller.readDataDigest(gameRoot)!!
        assertEquals("摘要跟着升到第 8 版", 8L, after.version)
        assertEquals("还原后的字节按新摘要记账（下一轮差量的基线）",
            sha256Hex(BOUNDARY_INS.expected), after.entries["resources/a.bin"]!!.sha256Hex)
        assertEquals("摘要里的字节数是还原后的长度",
            BOUNDARY_INS.expected.size.toLong(), after.entries["resources/a.bin"]!!.bytes)
        assertFalse("删掉的一条从摘要里跟着走", after.entries.containsKey("resources/gone.bin"))
    }

    @Test
    fun `还原在流末尾才失败时盘上仍是上一版整份，不留半份语料`() {
        disk("resources/a.bin", OLD)
        disk("resources/gone.bin", "该删的旧立绘".toByteArray())
        val real = sha256Hex(BOUNDARY_INS.expected)
        val wrong = latin1(latin1Of(BOUNDARY_INS.patch).replace(real, sha256Hex("别的字节".toByteArray())))
        val zip = bundleOf(
            patchEntry("resources/a.bin", wrong),
            ZipAssetTree.DELTA_LIST_NAME to "resources/gone.bin\n".toByteArray(),
        )
        val tree = blockTree(zip)
        assertThrows(AssetInstallException::class.java) {
            GameAssetInstaller(tree, gameRoot, forceReplace = true, recordDataVersion = 8L).install()
        }
        assertArrayEquals("目标仍是上一版那份字节（新内容只在 .tmp 里，失败即弃）",
            OLD, File(gameRoot, "resources/a.bin").readBytes())
        assertTrue("删除表在拷贝之后那一拍：本轮没走到，旧文件必须还在",
            File(gameRoot, "resources/gone.bin").isFile)
        assertFalse("中间文件已被清掉",
            File(gameRoot, "resources/a.bin${GameAssetInstaller.TMP_SUFFIX}").exists())
        assertFalse("失败轮不写摘要（写了就等于对下一轮撒谎）",
            File(gameRoot, "update/${GameAssetInstaller.DATA_DIGEST_FILE}").exists())
    }

    @Test
    fun `全量包与文件级差量包穿过这层零回归`() {
        disk("resources/old.bin", "老内容".toByteArray())
        val fileDelta = bundleOf(
            "resources/new.bin" to "新内容".toByteArray(),
            ZipAssetTree.DELTA_LIST_NAME to "resources/old.bin\n".toByteArray(),
        )
        val tree = blockTree(fileDelta)
        assertEquals("没有 update-blocks 就没有任何补丁", 0, tree.patchCount)
        assertEquals("拒绝数也不因为这一层而增加", 0, tree.rejectedEntryCount)
        assertTrue("仍然是差量档（删除表照旧由内层报出）", tree.isDeltaPackage)
        assertEquals(listOf("resources/old.bin"), tree.deltaDeletions())
        val result = GameAssetInstaller(tree, gameRoot, forceReplace = true).install()
        assertEquals(1, result.copiedFiles)
        assertEquals(1, result.deletedFiles)
        assertArrayEquals("新内容落盘", "新内容".toByteArray(),
            File(gameRoot, "resources/new.bin").readBytes())
        assertFalse("旧内容删除", File(gameRoot, "resources/old.bin").exists())

        val plain = bundleOf("CSV/x.txt" to "全量包".toByteArray())
        val fullTree = blockTree(plain)
        assertFalse("全量档不是差量档", fullTree.isDeltaPackage)
        assertEquals(0, fullTree.patchCount)
        assertEquals("根一层照常（没有控制目录可藏）", listOf("CSV"), fullTree.listDir(""))
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private fun blockTree(zip: File): BlockDeltaAssetTree =
        BlockDeltaAssetTree(ZipAssetTree(zip), gameRoot)

    private fun assertNotNullSeed(seed: DataDigest?) {
        assertTrue("第 7 版的摘要必须先存在，否则本用例根本没在测新路径", seed != null)
    }

    /** 从补丁头部读 `op-count`（只用于钉住夹具自己的形状）。 */
    private fun opCountOf(patch: ByteArray): Int =
        Regex("op-count (\\d+)").find(latin1Of(patch))!!.groupValues[1].toInt()

    private fun latin1Of(bytes: ByteArray): String = String(bytes, Charsets.ISO_8859_1)

    private fun latin1(text: String): ByteArray = text.toByteArray(Charsets.ISO_8859_1)

    /** 一条「旧文件的第 i 块」引用（`C` + 4 字节大端块号），与发布侧 `to_bytes(4, "big")` 同序。 */
    private fun copyOp(index: Int): ByteArray =
        byteArrayOf('C'.code.toByte()) + bigEndian(index)

    private fun bigEndian(value: Int): ByteArray = byteArrayOf(
        (value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte(),
    )

    private fun headerOf(
        baseSha: String,
        resultSha: String,
        resultSize: Int,
        opCount: Int,
        block: Int = 4096,
    ): ByteArray = latin1(
        "EBPD1\nfmt 1\nblock $block\nbase-sha256 $baseSha\nresult-sha256 $resultSha\n" +
            "result-size $resultSize\nop-count $opCount\n\n",
    )

    private fun patchEntry(relPath: String, patch: ByteArray): Pair<String, ByteArray> =
        patchEntryRel(relPath, patch)

    private fun patchEntryRel(relPath: String, patch: ByteArray): Pair<String, ByteArray> =
        (BlockDeltaAssetTree.PATCH_DIR_PREFIX + relPath) to patch

    private fun disk(relPath: String, bytes: ByteArray) {
        val file = File(gameRoot, relPath)
        file.parentFile!!.mkdirs()
        file.writeBytes(bytes)
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val out = StringBuilder()
        for (b in digest) out.append(String.format("%02x", b))
        return out.toString()
    }

    private fun bundleOf(vararg entries: Pair<String, ByteArray>): File =
        zipTo(temp.newFile(), entries.toList())

    /** STORED 条目：与 [DiffAssetApplyTest] 同理由 —— 读侧要拿得到确定的解压后字节数。 */
    private fun zipTo(target: File, entries: List<Pair<String, ByteArray>>): File {
        ZipOutputStream(target.outputStream().buffered()).use { out ->
            for ((name, bytes) in entries) {
                val entry = ZipEntry(name)
                entry.method = ZipEntry.STORED
                entry.size = bytes.size.toLong()
                entry.compressedSize = bytes.size.toLong()
                entry.crc = CRC32().apply { update(bytes) }.value
                out.putNextEntry(entry)
                out.write(bytes)
            }
        }
        return target
    }

    private companion object {
        /** 一条夹具：补丁原文 + 它应当还原出来的那份字节。 */
        class Case(val name: String, val patch: ByteArray, val expected: ByteArray)

        /** 解码发布侧产出的 Base64 补丁（解法与 `emit_update.py` 的写出侧一一对应）。 */
        fun fixture(name: String, patchBase64: String, expected: ByteArray) =
            Case(name, Base64.getDecoder().decode(patchBase64), expected)

        /**
         * 基线文件（12 KiB）：确定性 LCG 造的**不可压**字节。
         *
         * 与发布侧同源 —— 生成侧（`devtools/emit_update.py`）用
         * `x = (x * 1103515245 + 12345) & 0xFFFFFFFF`、`byte = (x >> 16) & 0xFF`、
         * 种子 `0x1234ABCD` 造出同一份 OLD，再跑 `build_block_patch(OLD, 目标)` 得到下面每个
         * Base64（`empty_result` 与 `copy_then_copy` 两条是按同一格式手拼的边界形状，
         * 发布侧的 `split_block_patch` + `reassemble_block_patch` 也复核过）。
         * **改了 OLD 就得回发布侧重跑夹具**，否则第一节那条跨语言判式会红。
         */
        val OLD: ByteArray = ByteArray(12288).also { array ->
            var x = 0x1234ABCDL
            for (i in array.indices) {
                x = (x * 1103515245L + 12345L) and 0xFFFFFFFFL
                array[i] = ((x shr 16) and 0xFFL).toByte()
            }
        }

        val WHOLE_COPY = fixture(
            "whole_copy",
            "RUJQRDEKZm10IDEKYmxvY2sgNDA5NgpiYXNlLXNoYTI1NiAzMjU4OTgzYzBlNTIzMTBlODVmOGFlNTg1ZWI4NmJiMWJhYTU1" +
                "OGFjZjgzNGEwNjllYzllNTIzNjJlNTc0MDliCnJlc3VsdC1zaGEyNTYgMzI1ODk4M2MwZTUyMzEwZTg1ZjhhZTU4NWViODZi" +
                "YjFiYWE1NThhY2Y4MzRhMDY5ZWM5ZTUyMzYyZTU3NDA5YgpyZXN1bHQtc2l6ZSAxMjI4OApvcC1jb3VudCAzCgpDAAAAAEMA" +
                "AAABQwAAAAI=",
            OLD,
        )

        val EXACT_BLOCK = fixture(
            "exact_block",
            "RUJQRDEKZm10IDEKYmxvY2sgNDA5NgpiYXNlLXNoYTI1NiAzMjU4OTgzYzBlNTIzMTBlODVmOGFlNTg1ZWI4NmJiMWJhYTU1" +
                "OGFjZjgzNGEwNjllYzllNTIzNjJlNTc0MDliCnJlc3VsdC1zaGEyNTYgOTVhNWE1MDY0YjY3YjJiNDBiZmMxN2IwM2VkYzEw" +
                "MTkxMzNlY2EzZGEwNzExYTIzNTdkYTU4ZGVmMGFlNWEwMwpyZXN1bHQtc2l6ZSA0MDk2Cm9wLWNvdW50IDEKCkMAAAAA",
            OLD.copyOfRange(0, 4096),
        )

        val BOUNDARY_INS = fixture(
            "boundary_ins",
            "RUJQRDEKZm10IDEKYmxvY2sgNDA5NgpiYXNlLXNoYTI1NiAzMjU4OTgzYzBlNTIzMTBlODVmOGFlNTg1ZWI4NmJiMWJhYTU1" +
                "OGFjZjgzNGEwNjllYzllNTIzNjJlNTc0MDliCnJlc3VsdC1zaGEyNTYgODJlYTZhMGZjYTkzNTU4NGIxOTkzZTJhYzNlNzI1" +
                "OWViYzViYWZhODE5YmJkNjExYWQ2NDJmNWViYjc4Zjc2ZApyZXN1bHQtc2l6ZSAxMjI5MApvcC1jb3VudCA0CgpDAAAAAFIA" +
                "AAACQUJDAAAAAUMAAAAC",
            OLD.copyOfRange(0, 4096) + "AB".toByteArray() + OLD.copyOfRange(4096, OLD.size),
        )

        val SHORT_TAIL = fixture(
            "short_tail",
            "RUJQRDEKZm10IDEKYmxvY2sgNDA5NgpiYXNlLXNoYTI1NiAzMjU4OTgzYzBlNTIzMTBlODVmOGFlNTg1ZWI4NmJiMWJhYTU1" +
                "OGFjZjgzNGEwNjllYzllNTIzNjJlNTc0MDliCnJlc3VsdC1zaGEyNTYgMjczYmEzODYxZTJmMmUwZjdiYWVmZjc1NjlkMTI0" +
                "ZmNmOGQ5ZTcwODEwYTg0MGI3Y2RlNzJjNDZiYWRhYWFlYQpyZXN1bHQtc2l6ZSA0MDk3Cm9wLWNvdW50IDIKCkMAAAAAUgAA" +
                "AAFR",
            OLD.copyOfRange(0, 4096) + "Q".toByteArray(),
        )

        val EMPTY_RESULT = fixture(
            "empty_result",
            "RUJQRDEKZm10IDEKYmxvY2sgNDA5NgpiYXNlLXNoYTI1NiAzMjU4OTgzYzBlNTIzMTBlODVmOGFlNTg1ZWI4NmJiMWJhYTU1" +
                "OGFjZjgzNGEwNjllYzllNTIzNjJlNTc0MDliCnJlc3VsdC1zaGEyNTYgZTNiMGM0NDI5OGZjMWMxNDlhZmJmNGM4OTk2ZmI5" +
                "MjQyN2FlNDFlNDY0OWI5MzRjYTQ5NTk5MWI3ODUyYjg1NQpyZXN1bHQtc2l6ZSAwCm9wLWNvdW50IDAKCg==",
            ByteArray(0),
        )

        val COPY_THEN_COPY = fixture(
            "copy_then_copy",
            "RUJQRDEKZm10IDEKYmxvY2sgNDA5NgpiYXNlLXNoYTI1NiAzMjU4OTgzYzBlNTIzMTBlODVmOGFlNTg1ZWI4NmJiMWJhYTU1" +
                "OGFjZjgzNGEwNjllYzllNTIzNjJlNTc0MDliCnJlc3VsdC1zaGEyNTYgYzZmMjIyNDJlNDg2NzFmMDdlMjM0YjIzM2RkYmRj" +
                "N2Y4YTdlNzQ2MDY1NDIxMjRlNGU2MjdlMzEwMzUzNzMxYQpyZXN1bHQtc2l6ZSA4MTkyCm9wLWNvdW50IDIKCkMAAAAAQwAA" +
                "AAE=",
            OLD.copyOfRange(0, 8192),
        )
    }
}
