package com.eratw.port.platform.storage

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `mods/` 存储层的出口判据（契约 §2/L4）。
 *
 * 全部跑在纯 JVM 上：不建 `Context`、不碰 `AssetManager`（本仓无 Robolectric，
 * 同 `GameStorageRootChoiceTest` 的做法）。临时目录用 JUnit4 的 [TemporaryFolder]
 * —— 本模块 testImplementation 只有 `junit:junit:4.13.2`，JUnit5 的 `@TempDir`
 * 不可用，且 `build.gradle.kts` 不在本路文件面内。
 *
 * 覆盖：① 扫描过滤与确定性排序 ② 拒绝面（超大 / 空 / 非法名 / 路径穿越，断言文案）
 * ③ 原子性（中途失败不留半截、临时文件清理） ④ 幂等（重复 save / remove 不存在）。
 */
class ModStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun mods(): File = temp.newFolder("mods")

    private fun store(): ModStore = ModStore(mods())

    /** 造一个内容非空的 `.ERB`。 */
    private fun File.erb(name: String, content: String = "PRINTFORM 口上\n"): File =
        File(this, name).apply {
            parentFile?.mkdirs()
            writeText(content)
        }

    private fun names(dir: File): List<String> =
        (dir.listFiles() ?: emptyArray<File>()).map { it.name }.sorted()

    // ---- ① 扫描 --------------------------------------------------------------

    @Test
    fun `扫描只返回 ERB 且顺序确定`() {
        val dir = mods()
        dir.erb("b.ERB")
        dir.erb("A.ERB")
        dir.erb("c.erb") // 扩展名大小写不敏感
        File(dir, "note.txt").writeText("不是 mod")
        File(dir, "sub").mkdirs() // 子目录
        File(dir, "trap.ERB").mkdirs() // 名字像 mod 的目录
        File(dir, ".hidden.ERB").writeText("隐藏文件")

        val scan = ModStore(dir).scan()

        assertEquals(
            "只认 .ERB（大小写不敏感），跳过 txt / 子目录 / 隐藏文件",
            listOf("A.ERB", "b.ERB", "c.erb"),
            scan.entries.map { it.name },
        )
        assertTrue("非 mod 候选不该进拒绝面", scan.rejections.isEmpty())
        assertTrue("同机同输入同输出", scan.entries.all { it.sizeBytes > 0 && it.lastModifiedMillis > 0 })
    }

    @Test
    fun `扫描结果对同一次输入两次调用逐项相同`() {
        val dir = mods()
        dir.erb("z.ERB")
        dir.erb("M.ERB")
        dir.erb("a.ERB")
        val store = ModStore(dir)

        assertEquals(store.scan().entries, store.scan().entries)
        assertEquals(listOf("a.ERB", "M.ERB", "z.ERB"), store.scan().entries.map { it.name })
    }

    @Test
    fun `mods 目录不存在时扫描为空不抛`() {
        val scan = ModStore(File(temp.root, "mods")).scan()

        assertTrue(scan.entries.isEmpty())
        assertTrue(scan.rejections.isEmpty())
    }

    @Test
    fun `mods 路径是普通文件时扫描明确抛错`() {
        val notDir = temp.newFile("mods")
        try {
            ModStore(notDir).scan()
            fail("mods 是文件时必须报错，不能静默返回空骗 UI")
        } catch (e: IOException) {
            assertTrue("文案要点名路径：${e.message}", e.message!!.contains("不是目录"))
        }
    }

    // ---- ② 拒绝面 ------------------------------------------------------------

    @Test
    fun `超大文件被拒绝且原因带字节数与上限`() {
        val dir = mods()
        RandomAccessFile(File(dir, "huge.ERB"), "rw").use { it.setLength(ModStore.MAX_MOD_BYTES + 1) }

        val scan = ModStore(dir).scan()

        assertTrue("超大文件不能进 entries", scan.entries.isEmpty())
        assertEquals(1, scan.rejections.size)
        val rejection = scan.rejections.single()
        assertEquals("huge.ERB", rejection.name)
        assertEquals(
            "文件过大：${ModStore.MAX_MOD_BYTES + 1} 字节，超过上限 ${ModStore.MAX_MOD_BYTES} 字节",
            rejection.reason,
        )
    }

    @Test
    fun `空文件被拒绝且原因固定`() {
        val dir = mods()
        File(dir, "empty.ERB").writeText("")

        val scan = ModStore(dir).scan()

        assertTrue(scan.entries.isEmpty())
        assertEquals("empty.ERB", scan.rejections.single().name)
        assertEquals("空文件（0 字节）", scan.rejections.single().reason)
    }

    @Test
    fun `非法文件名逐条给出可上屏原因`() {
        assertEquals("文件名为空", ModStore.validateName(""))
        assertEquals("文件名为空", ModStore.validateName("   "))
        assertEquals("文件名是绝对路径：/sdcard/evil.ERB", ModStore.validateName("/sdcard/evil.ERB"))
        assertEquals("文件名是绝对路径：C:\\evil.ERB", ModStore.validateName("C:\\evil.ERB"))
        assertEquals("文件名含路径穿越（..）：../evil.ERB", ModStore.validateName("../evil.ERB"))
        assertEquals("文件名含路径穿越（..）：..\\evil.ERB", ModStore.validateName("..\\evil.ERB"))
        assertEquals("文件名含路径穿越（..）：v1..2.ERB", ModStore.validateName("v1..2.ERB"))
        assertEquals("文件名含路径分隔符（/ 或 \\）：sub/evil.ERB", ModStore.validateName("sub/evil.ERB"))
        assertEquals("文件名含路径分隔符（/ 或 \\）：sub\\evil.ERB", ModStore.validateName("sub\\evil.ERB"))
        assertEquals("文件名含非法字符：bad|name.ERB", ModStore.validateName("bad|name.ERB"))
        assertEquals("文件名含非法字符：bad:name.ERB", ModStore.validateName("bad:name.ERB"))
        assertEquals(
            "文件名过长：204 字符，超过上限 ${ModStore.MAX_NAME_CHARS} 字符",
            ModStore.validateName("a".repeat(200) + ".ERB"),
        )
        assertEquals("文件名首尾含空白： evil.ERB", ModStore.validateName(" evil.ERB"))
        assertEquals("扩展名不是 .ERB/.ERH/.zip：notes.txt", ModStore.validateName("notes.txt"))
    }

    @Test
    fun `合法文件名通过校验`() {
        assertNull(ModStore.validateName("口上.ERB"))
        assertNull(ModStore.validateName("mod_01.erb"))
        assertNull(ModStore.validateName("a b.ERB"))
    }

    /**
     * 导入面白名单放宽（2026-11，zip/ERH 导入路）：`.ERH` 头文件与口上 zip 整包
     * 都要能过 [ModStore.validateName]（SAF 读到的字节与导入链落盘留档都走这道闸）。
     * 大小写不敏感口径与 `.ERB` 一致；扫描面（[ModStore.scan]）仍只认 `.ERB`。
     */
    @Test
    fun `白名单放行 ERH 与 ZIP 扩展名`() {
        assertNull(ModStore.validateName("HEADER.ERH"))
        assertNull(ModStore.validateName("header.erh"))
        assertNull(ModStore.validateName("口上パッチ.zip"))
        assertNull(ModStore.validateName("MOD.ZIP"))
        assertNull(ModStore.validateName("mod.Zip"))
        assertEquals(
            "裸扩展名（无主名）仍拒：zip 同口径",
            "扩展名不是 .ERB/.ERH/.zip：.zip",
            ModStore.validateName(".zip"),
        )
        assertTrue(ModStore.hasModExtension("a.ERB"))
        assertTrue(ModStore.hasModExtension("a.erh"))
        assertTrue(ModStore.hasModExtension("a.zip"))
        assertFalse(ModStore.hasModExtension("a.ERBX"))
        assertFalse(ModStore.hasModExtension("a.txt"))
        assertFalse("zip 只认尾段整词", ModStore.hasModExtension("a.zipx"))
        assertTrue(ModStore.hasErhExtension("a.ERH"))
        assertFalse(ModStore.hasErhExtension("a.ERB"))
    }

    @Test
    fun `remove 拒绝路径穿越且不动目录外文件`() {
        val dir = mods()
        val outside = File(temp.root, "outside.ERB").apply { writeText("目录外的文件") }
        val store = ModStore(dir)

        try {
            store.remove("../outside.ERB")
            fail("路径穿越必须明确拒绝")
        } catch (e: IllegalArgumentException) {
            assertEquals("文件名含路径穿越（..）：../outside.ERB", e.message)
        }
        assertTrue("拒绝后目录外文件必须完好", outside.isFile)

        try {
            store.remove("/sdcard/outside.ERB")
            fail("绝对路径必须明确拒绝")
        } catch (e: IllegalArgumentException) {
            assertEquals("文件名是绝对路径：/sdcard/outside.ERB", e.message)
        }

        try {
            store.remove("sub/outside.ERB")
            fail("含分隔符必须明确拒绝")
        } catch (e: IllegalArgumentException) {
            assertEquals("文件名含路径分隔符（/ 或 \\）：sub/outside.ERB", e.message)
        }
    }

    @Test
    fun `remove 只删 ERB`() {
        val dir = mods()
        val notes = File(dir, "notes.txt").apply { writeText("留着") }

        try {
            ModStore(dir).remove("notes.txt")
            fail("非 mod 扩展名必须明确拒绝")
        } catch (e: IllegalArgumentException) {
            assertEquals("扩展名不是 .ERB/.ERH/.zip：notes.txt", e.message)
        }
        assertTrue(notes.isFile)
    }

    // ---- ③ 原子性 ------------------------------------------------------------

    @Test
    fun `落盘中途失败不留半截文件且清理临时文件`() {
        val dir = mods()
        val store = ModStore(dir)

        try {
            store.save("half.ERB", FailingStream(failAfter = 4096))
            fail("注入的读失败必须冒泡")
        } catch (e: IOException) {
            assertTrue("文案要能定位注入点：${e.message}", e.message!!.contains("注入失败"))
        }

        assertEquals("mods 里不许出现半截文件或残留 .tmp", emptyList<String>(), names(dir))
        assertTrue(store.scan().entries.isEmpty())
    }

    @Test
    fun `目标目录不可用时 save 抛错且不产生任何文件`() {
        val notDir = temp.newFile("mods").apply { writeText("我是文件不是目录") }
        val store = ModStore(notDir)

        try {
            store.save("a.ERB", "PRINTFORM x\n".toByteArray())
            fail("目录建不出来必须抛 IOException")
        } catch (e: IOException) {
            assertTrue("文案要点名 mods 目录：${e.message}", e.message!!.contains("无法创建 mods 目录"))
        }

        assertEquals(listOf("mods"), names(temp.root))
    }

    @Test
    fun `流式写入超限时中止并清理临时文件`() {
        val dir = mods()

        try {
            ModStore(dir).save("huge.ERB", EndlessStream())
            fail("超限必须在写的过程中中止")
        } catch (e: IOException) {
            assertTrue("文案要带上限：${e.message}", e.message!!.contains("超过上限 ${ModStore.MAX_MOD_BYTES} 字节"))
        }

        assertEquals("超限中止后不许留半截文件", emptyList<String>(), names(dir))
    }

    // ---- ④ 幂等 --------------------------------------------------------------

    @Test
    fun `重复 save 同内容不产生第二份`() {
        val dir = mods()
        val store = ModStore(dir)
        val bytes = "PRINTFORM 口上\n".toByteArray()

        val first = store.save("same.ERB", bytes)
        val second = store.save("same.ERB", bytes)

        assertEquals("same.ERB", first.name)
        assertEquals("same.ERB", second.name)
        assertEquals(listOf("same.ERB"), names(dir))
        assertEquals(bytes.size.toLong(), second.sizeBytes)
        assertTrue("落盘内容必须逐字节一致", File(dir, "same.ERB").readBytes().contentEquals(bytes))
        assertEquals(1, store.scan().entries.size)
    }

    @Test
    fun `save 覆盖旧内容且不留下旧字节`() {
        val dir = mods()
        val store = ModStore(dir)
        store.save("m.ERB", "第一版内容，比较长一些".toByteArray())
        store.save("m.ERB", "第二版".toByteArray())

        assertEquals(listOf("m.ERB"), names(dir))
        assertEquals("第二版", File(dir, "m.ERB").readText())
    }

    @Test
    fun `remove 不存在的名字幂等不抛`() {
        val store = store()

        assertFalse(store.remove("ghost.ERB"))
        assertFalse("第二次同样不抛", store.remove("ghost.ERB"))
    }

    @Test
    fun `save 后 remove 真删掉`() {
        val dir = mods()
        val store = ModStore(dir)
        store.save("del.ERB", "x".toByteArray())

        assertTrue(store.remove("del.ERB"))
        assertFalse("再删一次是幂等的 false", store.remove("del.ERB"))
        assertEquals(emptyList<String>(), names(dir))
    }

    @Test
    fun `save 拒绝空内容且不建文件`() {
        val dir = mods()

        try {
            ModStore(dir).save("empty.ERB", ByteArray(0))
            fail("空内容必须拒绝")
        } catch (e: IllegalArgumentException) {
            assertEquals("空文件（0 字节）", e.message)
        }
        assertEquals(emptyList<String>(), names(dir))
    }

    @Test
    fun `save 拒绝非法名字`() {
        val dir = mods()

        try {
            ModStore(dir).save("../evil.ERB", "x".toByteArray())
            fail("路径穿越必须拒绝")
        } catch (e: IllegalArgumentException) {
            assertEquals("文件名含路径穿越（..）：../evil.ERB", e.message)
        }
        assertEquals(emptyList<String>(), names(dir))
    }

    @Test
    fun `save 自动建 mods 目录`() {
        val missing = File(temp.root, "mods")
        assertFalse(missing.exists())

        ModStore(missing).save("a.ERB", "PRINTFORM x\n".toByteArray())

        assertTrue(missing.isDirectory)
        assertEquals(listOf("a.ERB"), names(missing))
    }

    @Test
    fun `mods 目录复用游戏根口径`() {
        val gameRoot = File(temp.root, "eratw")
        assertEquals(File(gameRoot, "mods"), ModStore.modsDirFor(gameRoot))
        assertEquals("mods", ModStore.MODS_DIR_NAME)
    }

    @Test
    fun `扩展名判定大小写不敏感且要求主名`() {
        assertTrue(ModStore.hasErbExtension("a.ERB"))
        assertTrue(ModStore.hasErbExtension("a.erb"))
        assertTrue(ModStore.hasErbExtension("a.ErB"))
        assertFalse(ModStore.hasErbExtension(".ERB"))
        assertFalse(ModStore.hasErbExtension("a.ERBX"))
        assertFalse(ModStore.hasErbExtension("a.txt"))
        assertNotNull(ModStore.validateName("a.ERBX"))
    }

    // ---- 注入用替身 -----------------------------------------------------------

    /** 读到 [failAfter] 字节后抛 [IOException]，模拟落盘中途中止（磁盘满 / 源流断开）。 */
    private class FailingStream(private val failAfter: Int) : InputStream() {
        private var emitted = 0

        override fun read(): Int = throw IOException("注入失败：单字节读不该被调用")

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (emitted >= failAfter) throw IOException("注入失败：模拟落盘中途中止")
            val n = minOf(len, failAfter - emitted)
            for (i in 0 until n) b[off + i] = 'x'.code.toByte()
            emitted += n
            return n
        }
    }

    /** 永远读不完的流：验证上限是「边写边守」而不是先写完再拒。 */
    private class EndlessStream : InputStream() {
        override fun read(): Int = 'x'.code

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            for (i in 0 until len) b[off + i] = 'x'.code.toByte()
            return len
        }
    }
}
