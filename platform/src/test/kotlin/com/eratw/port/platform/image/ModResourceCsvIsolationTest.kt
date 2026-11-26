package com.eratw.port.platform.image

import com.eratw.port.core.host.ImageDecoderHost
import com.eratw.port.core.image.PngImage
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * ADR-014 第 ⑤ 条：mod 旁挂树与「全局 CSV 资源名表」的隔离。
 *
 * 生产落点 = `gameRoot/resources/mod/<dir>/…`（导入端 `ModImportMediaAssets.kt` 的
 * `RESOURCE_ROOT` :49 与 commit :159-170）。全局 CSV 名表来自
 * [ResourceCsvHost.listCsvFiles]（:41-51）：扫 `gameRoot/resources` 整棵树、只收 `.csv`
 * （[ResourceCsvHost.isCsvName] :377）。所以隔离的正确形状是「非 csv 文件根本不进表」，
 * 而不是「按 mod 子树排除」。
 *
 * 纯 JVM 可测：`android.jar` 桩只影响 `imageSize`（[ResourceCsvHostTest] 类头已说明），
 * `listCsvFiles` 只做目录遍历 + 字符串换算。
 */
class ModResourceCsvIsolationTest {

    private object NoopDecoder : ImageDecoderHost {
        override fun decode(path: String, bytes: ByteArray): PngImage? = null
    }

    private fun root(vararg files: Pair<String, String>): File {
        val dir = Files.createTempDirectory("resconsume-csv").toFile()
        for ((rel, text) in files) {
            val f = File(dir, rel)
            f.parentFile?.mkdirs()
            f.writeText(text)
        }
        return dir
    }

    @Test
    fun `resources_mod 下的非 csv 资源不进全局 CSV 名表`() {
        val gameRoot = root(
            "resources/csv/chara.csv" to "id,name,0\n",
            "resources/mod/testmod/hello.txt" to "mod-hello\n",
            "resources/mod/testmod/pic.png" to "not-a-real-png",
            "sound/mod/testmod/se.mp3" to "ID3",
        )
        val listed = ResourceCsvHost(gameRoot, NoopDecoder).listCsvFiles()
        assertEquals(listOf("resources/csv/chara.csv"), listed)
        assertFalse("mod 树里的 txt/png 不得混进 CSV 名表", listed.any { it.startsWith("resources/mod/") })
    }

    /**
     * 反证：隔离靠 `isCsvName` 扩展名过滤，不靠子树排除 —— 往 mod 树里放一个 `.csv`
     * 会被扫进表（表里出现 `resources/mod/…csv`）。这条红-对照说明「非 csv 不撞表」
     * 的真实机制；同时导入端 `RESOURCE_EXTENSIONS`（`ModImportMediaAssets.kt:458` =
     * png/jpg/jpeg/webp/txt，**不含 csv**）保证这种文件进不了 mod 树，两侧合起来才成立。
     */
    @Test
    fun `反证 隔离机制是 csv 扩展名过滤而不是子树排除`() {
        val gameRoot = root(
            "resources/mod/testmod/ambiguous.csv" to "id,name\n",
        )
        val listed = ResourceCsvHost(gameRoot, NoopDecoder).listCsvFiles()
        assertEquals(listOf("resources/mod/testmod/ambiguous.csv"), listed)
    }
}
