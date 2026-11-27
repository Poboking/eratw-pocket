package com.eratw.port.android

import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.loader.ProgramLoader
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * base 产物借用缓存的**失效闸**：命中只认「同一份字节」。
 *
 * 这条缓存把会话装载期算出来的 `Program` / `BootMetadata` 借给导入链（省掉 mmap 1.5s +
 * 侧车解析 2.6s，本机实测）。它唯一可能造成的伤害是「换了包却拿旧产物编译」——那会产出
 * 一个引用不存在函数/变量的补丁，装载期炸成砖。所以判据必须是**文件身份**（大小 + mtime），
 * 这里逐条钉住：换大小 / 换 mtime / 未装载，一律不命中。
 *
 * ## 环境门
 * `ERATW_ERBC_DIR` 指向 fresh `program.erbc` + `program.meta`：需要**真对象**（两个类都是
 * final，没有 mock 库可用，也不该为一条缓存判据去伪造 Program）。戳记用的文件是临时小文件
 * ——缓存判据不读内容，只比大小/mtime，所以不必复制 364 MiB。
 */
class BaseArtifactCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun setUp() = BaseArtifactCache.clear()

    @After
    fun tearDown() = BaseArtifactCache.clear()

    @Test
    fun `同产物命中_换戳记即失效`() {
        val erbcDir = System.getenv("ERATW_ERBC_DIR")?.takeIf { it.isNotBlank() }?.let(::File)
        assumeTrue(
            "需要 ERATW_ERBC_DIR（真产物）",
            erbcDir != null &&
                File(erbcDir, "program.erbc").isFile &&
                File(erbcDir, "program.meta").isFile,
        )
        val program = ProgramLoader.loadMapped(File(erbcDir, "program.erbc").toPath())
        val metadata = BootMetadata.read(File(erbcDir, "program.meta").toPath())

        // 戳记文件：只提供「大小 + mtime」，内容无关。
        val programFile = tmp.newFile("program.erbc").apply { writeBytes(ByteArray(8)) }
        val metaFile = tmp.newFile("program.meta").apply { writeText("m") }

        assertNull("没装载过就不许命中", BaseArtifactCache.borrow(programFile, metaFile))

        BaseArtifactCache.publish(programFile, metaFile, program, metadata)
        val hit = BaseArtifactCache.borrow(programFile, metaFile)
        assertSame("命中的必须是同一个 Program 实例（借用的意义就在这里）", program, hit?.first)
        assertSame("命中的必须是同一个 BootMetadata 实例", metadata, hit?.second)

        // 同长度换内容 + 显式推 mtime：模拟「同尺寸换包」（只看大小的实现会漏掉这一例）。
        metaFile.writeText("n")
        check(metaFile.setLastModified(metaFile.lastModified() + 5_000L)) { "推不动 mtime，测试环境异常" }
        assertNull("侧车 mtime 变了就不许再借（否则拿旧产物编新补丁）", BaseArtifactCache.borrow(programFile, metaFile))

        BaseArtifactCache.publish(programFile, metaFile, program, metadata)
        programFile.writeBytes(ByteArray(32))
        assertNull("产物大小变了就不许再借", BaseArtifactCache.borrow(programFile, metaFile))

        BaseArtifactCache.publish(programFile, metaFile, program, metadata)
        BaseArtifactCache.clear()
        assertNull("显式清空后不许命中", BaseArtifactCache.borrow(programFile, metaFile))
    }
}
