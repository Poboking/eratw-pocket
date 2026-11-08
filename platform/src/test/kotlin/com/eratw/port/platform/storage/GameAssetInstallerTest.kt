package com.eratw.port.platform.storage

import com.eratw.port.core.loader.BootMetadata
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 首启解包器（M8-ASSETSINSTALL）—— APK assets 树用临时目录顶替，纯 JVM 跑，不建 `Context`。
 *
 * 钉的是 [GameAssetInstaller] 的六条对外契约：
 * 1. assets 里没有 `erbc.v1` 时**一个文件都不碰**（不能凭空造目录，否则 `GameStorage` 的根选择会误判）；
 * 2. 单条 `program.erbc` 与侧车一起落盘，并按侧车声明的 `program-sha256` 核对；
 * 3. 分片按 `program.parts` 的**行序**拼接（不是文件名字典序）；
 * 4. SHA 不配对：清中间文件、抛可读错误，**旧产物保留**（上一版配对可降级）；
 * 5. 配对事务：program/meta 任一步失败，整对回滚到上一版（不留「新 program + 旧 meta」）；
 *    回滚本身出岔子时按**收集式**逐条尽力恢复，未恢复项计数并点名（`回滚收集式` 一组用例）；
 * 6. 幂等：第二次全跳过，目标被截断则重拷；`emuera.config` 永不覆盖。
 *
 * 第 10 例拿 core 自己的 [BootMetadata] 编码器当 oracle：解包器读的「第 3 行头部」
 * 与侧车格式必须同源，core 换写法时这条会先红。
 */
class GameAssetInstallerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var assetsRoot: File
    private lateinit var gameRoot: File

    @org.junit.Before
    fun setUp() {
        assetsRoot = temp.newFolder("assets")
        gameRoot = File(temp.newFolder("files"), "eratw")
    }

    // ------------------------------------------------------------------
    // 用例
    // ------------------------------------------------------------------

    @Test
    fun `assets 里没有产物时一个文件都不碰`() {
        val result = install()

        assertEquals(0, result.copiedFiles)
        assertEquals(0, result.copiedBytes)
        assertFalse("不能凭空造产物目录", File(gameRoot, "erbc.v1").exists())
    }

    @Test
    fun `分片清单为空时报可读错误`() {
        asset("erbc.v1/program.parts", "\n  \n".toByteArray())

        val e = assertThrows(AssetInstallException::class.java) { install() }

        assertTrue("要指名是清单的问题：${e.message}", e.message!!.contains("空清单"))
        assertFalse(File(gameRoot, "erbc.v1/program.erbc").exists())
    }

    @Test
    fun `单条产物与侧车一起落盘并核对 sha`() {
        val program = bytes(300_000, seed = 1)
        asset("erbc.v1/program.erbc", program)
        asset("erbc.v1/program.meta", meta(sha256(program)).toByteArray())

        val result = install()

        assertEquals(2, result.copiedFiles)
        assertTrue("侧车声明了 program-sha256 就必须核对通过", result.programSha256Verified)
        assertArrayEquals(program, File(gameRoot, "erbc.v1/program.erbc").readBytes())
        assertFalse("中间文件不能留", File(gameRoot, "erbc.v1/program.erbc.tmp").exists())
    }

    @Test
    fun `分片按清单行序拼接而不是文件名字典序`() {
        // 名字字典序是 partA/partB/partZ，清单行序是 Z/A/B —— 目标必须是 Z+A+B。
        val z = bytes(1_000, seed = 7)
        val a = bytes(2_000, seed = 11)
        val b = bytes(500, seed = 13)
        asset("erbc.v1/program.partZ.erbc", z)
        asset("erbc.v1/program.partA.erbc", a)
        asset("erbc.v1/program.partB.erbc", b)
        val manifest = listOf("program.partZ.erbc" to z, "program.partA.erbc" to a, "program.partB.erbc" to b)
            .joinToString(separator = "\n", postfix = "\n") { "${it.first}\t${it.second.size}" }
        asset("erbc.v1/program.parts", manifest.toByteArray())
        asset("erbc.v1/program.meta", meta(sha256(z + a + b)).toByteArray())

        val progress = mutableListOf<GameAssetInstaller.Progress>()
        val result = install(progress = progress)

        assertTrue(result.programSha256Verified)
        assertArrayEquals(z + a + b, File(gameRoot, "erbc.v1/program.erbc").readBytes())
        assertEquals(
            listOf("erbc.v1/program.partZ.erbc", "erbc.v1/program.partA.erbc", "erbc.v1/program.partB.erbc"),
            progress.take(3).map { it.label },
        )
        assertEquals(4, progress.last().total)
    }

    @Test
    fun `sha 不配对时清中间文件抛可读错误且旧产物保留`() {
        val program = bytes(50_000, seed = 3)
        asset("erbc.v1/program.erbc", program)
        // 声明的是一份完全不同的哈希（配对错，打包期就该挡住，这里兜住「推错一组」）。
        asset("erbc.v1/program.meta", meta("0".repeat(64)).toByteArray())

        val e = assertThrows(AssetInstallException::class.java) { install() }

        assertTrue("要点名不配对：${e.message}", e.message!!.contains("不配对"))
        assertFalse("首次安装本无旧产物，坏文件不能凭 rename 留盘", File(gameRoot, "erbc.v1/program.erbc").exists())
        assertFalse(File(gameRoot, "erbc.v1/program.erbc.tmp").exists())
        assertFalse("回滚后不得残留备份", File(gameRoot, "erbc.v1/program.erbc.bak").exists())
        assertFalse("配对没通过，侧车也不该落地", File(gameRoot, "erbc.v1/program.meta").exists())
    }

    /**
     * 2026-10-22 审查修正的钉子：**旧产物是「上一版配对」，SHA 失败不许删它**。
     * 上一版实现 `target.delete()` 删的正是旧产物（rename 尚未执行），注释还说
     * 「删掉刚落的目标」——把可降级状态破坏掉。
     */
    @Test
    fun `sha 不配对时上一版配对保留`() {
        // 第一版装好（配对成立）。
        val v1 = bytes(30_000, seed = 41)
        asset("erbc.v1/program.erbc", v1)
        asset("erbc.v1/program.meta", meta(sha256(v1)).toByteArray())
        install()

        // 第二版资产自相矛盾（program 与侧车声明不符）→ 触发整对重装 → SHA 失败。
        val v2 = bytes(60_000, seed = 43)
        asset("erbc.v1/program.erbc", v2)
        asset("erbc.v1/program.meta", meta("0".repeat(64)).toByteArray())

        val e = assertThrows(AssetInstallException::class.java) { install() }
        assertTrue("要点名不配对：${e.message}", e.message!!.contains("不配对"))

        // 盘上回到 v1 配对：program 与侧车都是旧版，无任何中间/备份残留。
        assertArrayEquals(v1, File(gameRoot, "erbc.v1/program.erbc").readBytes())
        assertEquals(sha256(v1), BootMetadata.parse(File(gameRoot, "erbc.v1/program.meta").readText()).programSha256)
        assertFalse(File(gameRoot, "erbc.v1/program.erbc.tmp").exists())
        assertFalse(File(gameRoot, "erbc.v1/program.erbc.bak").exists())
        assertFalse(File(gameRoot, "erbc.v1/program.meta.bak").exists())
    }

    /**
     * 配对事务的另一半：中途失败（本例 meta 资产「存在但打不开」）必须整对回滚。
     * 上一版实现没有事务，会留下「新 program + 旧 meta」；若新旧 meta 字节数恰好相同，
     * 下一轮字节相符双双跳过，错配就永久化。
     */
    @Test
    fun `中途失败回滚到上一版配对`() {
        val v1 = bytes(30_000, seed = 51)
        asset("erbc.v1/program.erbc", v1)
        asset("erbc.v1/program.meta", meta(sha256(v1)).toByteArray())
        install()

        // 第二版配对正确，但 meta 资产打不开（fileSize 拿得到、openFile 抛不了）——
        // 模拟「拷贝进行到一半 IO 断掉」的事故形态。
        val v2 = bytes(70_000, seed = 53)
        asset("erbc.v1/program.erbc", v2)
        asset("erbc.v1/program.meta", meta(sha256(v2)).toByteArray())
        val broken = FailOpenTree(DirAssetTree(assetsRoot), failPath = "erbc.v1/program.meta")

        val e = assertThrows(AssetInstallException::class.java) { install(broken) }
        assertTrue("要点名缺资产：${e.message}", e.message!!.contains("program.meta"))

        // 整对回到 v1：不许出现「新 program + 旧 meta」。
        assertArrayEquals(v1, File(gameRoot, "erbc.v1/program.erbc").readBytes())
        assertEquals(sha256(v1), BootMetadata.parse(File(gameRoot, "erbc.v1/program.meta").readText()).programSha256)
        assertFalse(File(gameRoot, "erbc.v1/program.erbc.tmp").exists())
        assertFalse(File(gameRoot, "erbc.v1/program.meta.tmp").exists())
        assertFalse(File(gameRoot, "erbc.v1/program.erbc.bak").exists())
        assertFalse(File(gameRoot, "erbc.v1/program.meta.bak").exists())
    }

    /**
     * 回滚是**收集式**的（W5-misc 第 2 条之二）：一条恢复失败不许终止剩余恢复。
     *
     * 旧写法在恢复循环里对第一条 `renameTo` 失败直接 `throw`，于是剩下所有 `.bak` 永远留在
     * 原地 —— 真实事故形态（磁盘满 / fd 耗尽）下 program 恢复成功、meta 没恢复，盘上就是
     * 「新 program + 旧 meta」这类半套配对，而下一轮解包的兜底只认单个 target，屏上看不到
     * 是谁没回来。判据三条：
     * 1. 能恢复的**全都**恢复（本例 2 份备份坏 1 份，另一份必须回到目标名）；
     * 2. 未恢复项**逐条**写进异常文案（点名绝对路径 + `1/2` 计数）；
     * 3. 原始失败仍是 cause 链上的那一个（屏上先要看得见的还是「这一轮为什么失败」）。
     *
     * 为什么走注入口：纯 JVM 临时目录里 `renameTo` **不会**失败（同卷改名成功率高到造不出
     * false），真机路径传的是缺省实现。注入参数 `restore` 只为这一条判据存在。
     */
    @Test
    fun `回滚收集式：一条失败不带走其余备份`() {
        val dir = temp.newFolder("rollback")
        val goodTarget = File(dir, "program.erbc")
        val goodBak = File(dir, "program.erbc.bak").apply { writeText("旧 program") }
        val badTarget = File(dir, "program.meta")
        val badBak = File(dir, "program.meta.bak").apply { writeText("旧 meta") }
        // 本轮已 rename 就位的新产物（回滚第一步要把它让位）
        goodTarget.writeText("新 program")
        badTarget.writeText("新 meta")
        val cause = IllegalStateException("拷贝进行到一半 IO 断掉")

        val thrown = GameAssetInstaller(DirAssetTree(assetsRoot), gameRoot).rollbackPairTargets(
            LinkedHashMap<File, File>().apply {
                put(goodTarget, goodBak)
                put(badTarget, badBak)
            },
            cause,
            restore = { bak, target ->
                // 只有 meta 那一条恢复失败（磁盘满那一档的形状），program 照常改名
                if (bak == badBak) false else bak.renameTo(target)
            },
        )

        assertArrayEquals(
            "第一条恢复失败绝不能带走其余备份（半套配对就是这条 bug 的产物）",
            "旧 program".toByteArray(),
            goodTarget.readBytes(),
        )
        assertFalse("恢复成功的备份应当改名走，不留 .bak", goodBak.exists())
        assertTrue("失败的备份留在原地，由下一轮开头的恢复逻辑兜底", badBak.isFile)
        assertTrue(
            "有未恢复项时必须包成 AssetInstallException（实际 ${thrown.javaClass.simpleName}）",
            thrown is AssetInstallException,
        )
        val msg = thrown.message!!
        assertTrue("要点名未恢复的那一条：$msg", msg.contains(badBak.absolutePath))
        assertTrue("要写出未恢复计数：$msg", msg.contains("1/2"))
        assertTrue("原始失败要留在文案里：$msg", msg.contains("IO 断掉"))
        assertSame("cause 链必须是原始失败本身（首个失败），不是新造的异常", cause, thrown.cause)
    }

    /** 回滚干净 ⇒ 原样交回原始失败：不许给一条本来可读的失败再套一层壳。 */
    @Test
    fun `回滚干净时抛的还是原始失败`() {
        val dir = temp.newFolder("rollback-clean")
        val target = File(dir, "program.erbc")
        val bak = File(dir, "program.erbc.bak").apply { writeText("旧") }
        target.writeText("新")
        val cause = IllegalStateException("原始失败")

        val thrown = GameAssetInstaller(DirAssetTree(assetsRoot), gameRoot)
            .rollbackPairTargets(linkedMapOf(target to bak), cause)

        assertSame(cause, thrown)
        assertArrayEquals("旧".toByteArray(), target.readBytes())
        assertFalse(bak.exists())
    }

    /** 没有备份（本轮还没 rename 就失败）⇒ 原样交回，且不许碰盘上的目标文件。 */
    @Test
    fun `没有备份时回滚是 no-op`() {
        val dir = temp.newFolder("rollback-empty")
        val target = File(dir, "program.erbc").apply { writeText("上一版") }
        val cause = IllegalStateException("规划阶段就失败")
        val thrown = GameAssetInstaller(DirAssetTree(assetsRoot), gameRoot)
            .rollbackPairTargets(emptyMap(), cause)
        assertSame(cause, thrown)
        assertArrayEquals("上一版".toByteArray(), target.readBytes())
    }

    /** 备份自己没了（被人删 / 上一轮之后手抖清过）不算「未恢复」：没东西可恢复。 */
    @Test
    fun `备份缺失不计进未恢复项`() {
        val dir = temp.newFolder("rollback-missing-bak")
        val target = File(dir, "program.erbc")
        val bak = File(dir, "program.erbc.bak") // 故意不建
        val cause = IllegalStateException("原始失败")
        val thrown = GameAssetInstaller(DirAssetTree(assetsRoot), gameRoot)
            .rollbackPairTargets(linkedMapOf(target to bak), cause, restore = { _, _ -> false })
        assertSame("没有 .bak 就没有未恢复项，不该造出回滚异常", cause, thrown)
    }

    @Test
    fun `第二次全跳过而目标被截断后重拷`() {
        val program = bytes(40_000, seed = 5)
        asset("erbc.v1/program.erbc", program)
        asset("erbc.v1/program.meta", meta(sha256(program)).toByteArray())

        assertEquals(2, install().copiedFiles)

        val second = install()
        assertEquals("已在位就不该再写", 0, second.copiedFiles)
        assertEquals(2, second.skippedFiles)
        assertEquals(0L, second.copiedBytes)
        assertFalse("没有拷贝就没有可核对的产物", second.programSha256Verified)

        // 截断 = 上一轮被打断的残留：字节数对不上就重拷，侧车随 program 一起换。
        File(gameRoot, "erbc.v1/program.erbc").writeBytes(program.copyOf(10))
        val third = install()
        assertEquals(2, third.copiedFiles)
        assertTrue(third.programSha256Verified)
        assertArrayEquals(program, File(gameRoot, "erbc.v1/program.erbc").readBytes())
    }

    @Test
    fun `用户改过的 emuera_config 不被覆盖`() {
        asset("emuera.config", "ConfigVersion=1\nUseKeyMacro=YES\n".toByteArray())
        val userConfig = File(gameRoot, "emuera.config").apply {
            gameRoot.mkdirs()
            writeText("ConfigVersion=1\nUseKeyMacro=NO\n") // 与资产内容不同、长度也不同
        }

        val result = install()

        assertEquals("配置只拷一次", 0, result.copiedFiles)
        assertEquals("ConfigVersion=1\nUseKeyMacro=NO\n", userConfig.readText())
    }

    /**
     * 缺键补齐的主钉（2026-10-31 玩家报告 ×5 的根因）：0.2.0 时代装的 gameRoot 没有
     * `VALID EXTENSIONS FOR LOADTEXT AND SAVETEXT:xml,txt,json`（语料 `emuera.config:73`），
     * 升级 app 后 4.980 语料的 `LOADTEXT("plugins/tw_csv_chs.xml")` 在白名单缺 `xml` 时
     * 读回空串、开局中止。修复 = 升级路径上把「资产有、盘上缺」的键追加进旧配置。
     */
    @Test
    fun `升级时旧配置缺的新键被补齐且已有键不被改值`() {
        // 第一版：带 BOM 的旧配置（老包没有白名单行）。
        asset("emuera.config", "\uFEFFConfigVersion=1\nUseKeyMacro=YES\n".toByteArray())
        install()
        val config = File(gameRoot, "emuera.config")

        // 第二版资产：同一键改了值 + 新增了白名单行（模拟「新包新增键」）。
        asset(
            "emuera.config",
            "\uFEFFConfigVersion=1\nUseKeyMacro=NO\nVALID EXTENSIONS FOR LOADTEXT AND SAVETEXT:xml,txt,json\n"
                .toByteArray(),
        )
        val second = install()

        assertEquals("升级轮不该重拷任何文件（配置存在即跳过）", 0, second.copiedFiles)
        assertEquals(
            "新键要补进来，已有键的玩家值不能被资产覆盖",
            "\uFEFFConfigVersion=1\nUseKeyMacro=YES\nVALID EXTENSIONS FOR LOADTEXT AND SAVETEXT:xml,txt,json\n",
            config.readText(),
        )
        assertFalse("补齐后不许残留中间文件", File(gameRoot, "emuera.config.tmp").exists())

        // 幂等：第三轮不再追加第二份。
        install()
        assertEquals(
            "重复启动不许把同一键追加两遍",
            1,
            config.readText().lines().count { it.startsWith("VALID EXTENSIONS FOR LOADTEXT AND SAVETEXT") },
        )
    }

    @Test
    fun `盘上配置已有键时资产同键行不追加`() {
        asset("emuera.config", "UseKeyMacro=NO\n".toByteArray())
        val config = File(gameRoot, "emuera.config").apply {
            gameRoot.mkdirs()
            writeText("UseKeyMacro=YES\n")
        }

        install()

        assertEquals("已有键（哪怕值不同）不许追加第二份", "UseKeyMacro=YES\n", config.readText())
    }

    /** 注释行、无冒号行、空行都不算键；只有注释行与无冒号行的资产 ⇒ 无可补 ⇒ 零写入。 */
    @Test
    fun `注释行与无分隔符行不参与缺键补齐`() {
        asset("emuera.config", "; 注释\nConfigVersion\n".toByteArray())
        val config = File(gameRoot, "emuera.config").apply {
            gameRoot.mkdirs()
            writeText("UseKeyMacro=YES")
        }

        install()

        assertEquals("没有缺失键就不许写盘（含行尾也不许动）", "UseKeyMacro=YES", config.readText())
    }

    /** 非 UTF-8 的盘上配置（原版回落 shift_jis 的日文另存）不重写 —— 重写等于替玩家换码页。 */
    @Test
    fun `非 UTF-8 配置整份跳过不重写`() {
        // 「フウント」的 windows-31j 字节（0x83 0x74 0x83 0x87 0x83 0x93 0x83 0x67），严格 UTF-8 解不开。
        val sjis = byteArrayOf(
            0x83.toByte(), 0x74.toByte(),
            0x83.toByte(), 0x87.toByte(),
            0x83.toByte(), 0x93.toByte(),
            0x83.toByte(), 0x67.toByte(),
        )
        asset("emuera.config", "UseKeyMacro=YES\n".toByteArray())
        val config = File(gameRoot, "emuera.config").apply {
            gameRoot.mkdirs()
            writeBytes(sjis)
        }

        install()

        assertArrayEquals("非 UTF-8 配置一个字节都不许动", sjis, config.readBytes())
    }

    /** 盘上配置不以换行结尾且缺键：补齐前先补一个换行，新键行不得黏在旧值行尾。 */
    @Test
    fun `无换行结尾的配置补键前先补行尾换行`() {
        asset(
            "emuera.config",
            "ConfigVersion=1\nVALID EXTENSIONS FOR LOADTEXT AND SAVETEXT:xml,txt,json\n".toByteArray(),
        )
        val config = File(gameRoot, "emuera.config").apply {
            gameRoot.mkdirs()
            writeText("UseKeyMacro=YES") // 无换行结尾
        }

        install()

        assertEquals(
            "新键行必须从新行开始",
            "UseKeyMacro=YES\nVALID EXTENSIONS FOR LOADTEXT AND SAVETEXT:xml,txt,json\n",
            config.readText(),
        )
    }

    @Test
    fun `CSV resources sound plugins 四个目录递归落盘`() {
        val chara = bytes(1_200, seed = 17)
        val nested = bytes(300, seed = 19)
        val image = bytes(4_096, seed = 23)
        val sound = bytes(8_192, seed = 29)
        val pluginXml = "<map><p><k>a</k><v>b</v></p></map>".toByteArray()
        asset("CSV/Chara.csv", chara)
        asset("CSV/sub/Nested.csv", nested)
        asset("resources/img/title.png", image)
        asset("sound/bgm.ogg", sound)
        asset("plugins/tw_csv_chs.xml", pluginXml)

        val result = install()

        assertEquals(5, result.copiedFiles)
        assertArrayEquals(chara, File(gameRoot, "CSV/Chara.csv").readBytes())
        assertArrayEquals(nested, File(gameRoot, "CSV/sub/Nested.csv").readBytes())
        assertArrayEquals(image, File(gameRoot, "resources/img/title.png").readBytes())
        assertArrayEquals(sound, File(gameRoot, "sound/bgm.ogg").readBytes())
        assertArrayEquals(pluginXml, File(gameRoot, "plugins/tw_csv_chs.xml").readBytes())
    }

    /**
     * `plugins/` 是**运行期**读的，不是随包装饰：`ERB/SYSTEM.ERB:96-97` 的
     * `MAP_FROMXML CSV_MAP,LOADTEXT("plugins/tw_csv_chs.xml")` 在 `@INIT_NG_OR_LOAD`
     * 里每局跑一遍。少了它 `LOADTEXT` 得空串，`MAP_FROMXML` 在位置 0 抛
     * 「XML 解析错误」，开局直接中止（2026-10-22 真机 4.980 实证）。
     *
     * `bad/` 同理是**运行期**读的：`ERB/TITLE.ERB:123-158` 的 `@BADAPPLE` 每帧
     * `LOADTEXT("bad/bad_apple{count}.txt")`（1..6573）。少了它 DEMO 空帧全速循环，
     * 真机上首页被清空 + 高频抖动（2026-10-22 DevTools 实证空帧行）。
     *
     * `ERB/` 只随包 ERD 数据表（缺表时用户变量第 3 个下标不能用字符串指定，
     * 运行期直接 CodeEE，2026-11-03 真机 4.981 新游戏实证），不随 ERB 源码。
     */
    @Test
    fun `DATA_DIRS 覆盖运行期读的 plugins 目录`() {
        assertTrue(
            "DATA_DIRS 缺 plugins/：首启不会解包 MAP_FROMXML 要读的 XML",
            GameAssetInstaller.DATA_DIRS.contains("plugins"),
        )
        assertTrue(
            "DATA_DIRS 缺 bad/：首启不会解包 BADAPPLE 要读的文本帧",
            GameAssetInstaller.DATA_DIRS.contains("bad"),
        )
        assertEquals(
            "CSV/resources/sound/plugins/bad/ERB 六类语料目录不许被顺手删掉",
            listOf("CSV", "resources", "sound", "plugins", "bad", "ERB"),
            GameAssetInstaller.DATA_DIRS,
        )
    }

    @Test
    fun `压缩条目拿不到大小时目标存在即跳过`() {
        asset("CSV/Chara.csv", bytes(700, seed = 31))
        val tree = DirAssetTree(assetsRoot, hideSizes = true)

        assertEquals(1, install(tree).copiedFiles)
        assertEquals("压缩条目不能为了比大小再解压一遍", 0, install(tree).copiedFiles)
        assertEquals(700L, File(gameRoot, "CSV/Chara.csv").length())
    }

    @Test
    fun `压缩条目目标被截成 0 字节后重拷`() {
        val csv = bytes(700, seed = 41)
        asset("CSV/Chara.csv", csv)
        val tree = DirAssetTree(assetsRoot, hideSizes = true)

        assertEquals(1, install(tree).copiedFiles)
        // 解包途中被杀留下的空壳：文件在、长度 0。压缩条目拿不到资产长度，
        // 旧判据「存在即已装」会让这个空壳永久留存 —— 2026-10-31 真机 8 份
        // `XML_DOCUMENT … 缺少根元素` 的现场就是 LOADTEXT 读到了这种壳。
        val target = File(gameRoot, "CSV/Chara.csv")
        target.writeBytes(ByteArray(0))

        assertEquals("0 字节目标必须重拷", 1, install(tree).copiedFiles)
        assertArrayEquals(csv, target.readBytes())
    }

    @Test
    fun `零字节 STORED 标记每次都不重拷`() {
        // sound/ 下 18 个零字节 bgm 标记是真·空文件，且是 STORED（fileSize 报 0）：
        // 「0 字节就重拷」这条只对压缩条目成立，不能顺手把它们拖成每次启动都写一遍。
        asset("sound/bgm01.ogg", ByteArray(0))

        assertEquals(1, install().copiedFiles)
        assertEquals("零字节是合法长度，不是残缺", 0, install().copiedFiles)
        assertEquals(0L, File(gameRoot, "sound/bgm01.ogg").length())
    }

    @Test
    fun `侧车头部解析与 core 编码器同源`() {
        val program = bytes(4_096, seed = 37)
        val sha = sha256(program)
        val metaText = meta(sha)
        // oracle：core 自己的读侧解析出同一个声明；且头部就落在前 4 KiB 内（解包器只读这么多）。
        assertEquals(sha, BootMetadata.parse(metaText).programSha256)
        assertTrue(metaText.indexOf("program-sha256") in 0 until GameAssetInstaller.HEADER_BYTES)
        asset("erbc.v1/program.erbc", program)
        asset("erbc.v1/program.meta", metaText.toByteArray())

        val result = install()

        assertTrue("解包器读到的声明必须与 core 逐字相同", result.programSha256Verified)
    }

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    private fun install(
        tree: GameAssetTree = DirAssetTree(assetsRoot),
        progress: MutableList<GameAssetInstaller.Progress>? = null,
    ): GameAssetInstaller.Result =
        GameAssetInstaller(tree, gameRoot).install { progress?.add(it) }

    private fun asset(rel: String, bytes: ByteArray) {
        File(assetsRoot, rel).apply {
            parentFile?.mkdirs()
            writeBytes(bytes)
        }
    }

    /** 侧车文本：用 core 的编码器写，头部两行（engine / program-sha256）由它保证。 */
    private fun meta(programSha: String?): String =
        BootMetadata().withHeader(programSha256 = programSha).encodeText()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }

    /** 可复现的伪随机字节（不用 kotlin.random：core 层禁用同一套 API，测试也保持一致口径）。 */
    private fun bytes(size: Int, seed: Int): ByteArray =
        ByteArray(size) { ((it * 31 + seed * 7) and 0xff).toByte() }

    /**
     * 临时目录当 assets 根：路径按 `/` 分，[fileSize] 取真实文件长度（等价 STORED 条目）。
     * [hideSizes] 模拟压缩条目（`AssetManager.openFd` 必抛 → 拿不到长度）。
     */
    private class DirAssetTree(
        private val root: File,
        private val hideSizes: Boolean = false,
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
            if (hideSizes) return null
            val file = File(root, path)
            return if (file.isFile) file.length() else null
        }
    }

    /**
     * 「[fileSize] 拿得到、[openFile] 打不开」——模拟真机上资产条目存在但流打开失败的
     * 事故形态（zip 损坏 / fd 耗尽）。注意 [GameAssetInstaller] 的存在性判定先看
     * [fileSize]，所以这里必须两个方法口径不一致才能触发「拷贝进行中缺资产」。
     */
    private class FailOpenTree(
        private val inner: GameAssetTree,
        private val failPath: String,
    ) : GameAssetTree {

        override fun openFile(path: String): InputStream? =
            if (path == failPath) null else inner.openFile(path)

        override fun listDir(path: String): List<String> = inner.listDir(path)

        override fun fileSize(path: String): Long? = inner.fileSize(path)
    }
}
