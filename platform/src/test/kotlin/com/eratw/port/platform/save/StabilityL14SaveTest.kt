package com.eratw.port.platform.save

import com.eratw.port.core.data.save.SaveData
import com.eratw.port.core.data.save.SaveFormat
import com.eratw.port.core.fn.SaveFileKind
import com.eratw.port.core.save.SaveConfig
import com.eratw.port.core.vars.GameBaseData
import java.io.File
import java.io.OutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * L14 稳定性：导入落位与导出打包的三条健壮化。
 *
 * 1. [SaveImport.commit] 的**根内约束**：`targetRel` 归一化后必须落在 `root` 内
 *    （`../x`、`a/../../x`、绝对路径一律 false，且根外不出现文件）；根内路径照常落盘、
 *    不留 `.part`。证伪：删掉 `startsWith` 判断，前三条用例会变绿为 true/文件出现在根外。
 * 2. 槽号解析溢出：`save<超过 Long 的位数>.sav` 必须判「存档位编号太大」，
 *    **不能**掉进「找空位」（否则玩家以为覆盖了 save12，实际写进 save00）。
 *    证伪：把 `toLongOrNull()` 改回「null 就当没槽号」，该用例会拿到 `Place` 而红。
 * 3. [SaveExport.writeZip] 只吞 [Exception]：`Error`（这里用 [OutOfMemoryError] 模拟）必须
 *    上抛，不能被伪装成「导出失败」。证伪：把 `catch (Exception)` 改回 `catch (Throwable)`，
 *    该用例会从「抛出」变成「返回 ok=false」而红。
 */
class StabilityL14SaveTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val eraConfig = SaveConfig.ERATW

    private val eraBase = GameBaseData(gameCode = 7153L, version = 0L, versionDefined = false, allowVersion = 12L)

    private fun slot(no: Long): ByteArray =
        SaveFormat.write(SaveData(SaveFileKind.NORMAL, 7153L, 0L, "第 ${no + 1} 日", compressed = true))

    // -----------------------------------------------------------------
    // commit：根内约束
    // -----------------------------------------------------------------

    @Test
    fun `commit 拒绝跳出游戏根的相对路径`() {
        val root = temp.newFolder("game")
        val outside = File(root.parentFile, "escaped-l14.sav")

        assertFalse(SaveImport.commit(root, "../escaped-l14.sav", byteArrayOf(1, 2, 3)))

        assertFalse("根外不得出现落盘结果", outside.exists())
    }

    @Test
    fun `commit 拒绝夹在中间的回跳路径`() {
        val root = temp.newFolder("game")

        assertFalse(SaveImport.commit(root, "sav/../../escaped-l14.sav", byteArrayOf(1, 2, 3)))

        assertFalse("回跳后仍在根外", File(root.parentFile, "escaped-l14.sav").exists())
    }

    @Test
    fun `commit 拒绝绝对路径`() {
        val root = temp.newFolder("game")
        val absolute = File(temp.root, "abs-l14.sav")

        assertFalse(SaveImport.commit(root, absolute.absolutePath, byteArrayOf(1)))

        assertFalse(absolute.exists())
    }

    @Test
    fun `根内相对路径照常落盘且不留 part`() {
        val root = temp.newFolder("game")
        val bytes = slot(3L)

        assertTrue(SaveImport.commit(root, "sav/save03.sav", bytes))

        assertArrayEquals(bytes, File(root, "sav/save03.sav").readBytes())
        assertEquals(
            listOf("save03.sav"),
            File(root, "sav").listFiles()!!.map { it.name }.sorted(),
        )
    }

    // -----------------------------------------------------------------
    // plan：槽号溢出
    // -----------------------------------------------------------------

    @Test
    fun `位数超过 Long 的槽号判为编号太大而不是找空位`() {
        var askedForFreeSlot = false

        val plan = SaveImport.plan(
            fileName = "save99999999999999999999.sav",
            bytes = slot(0L),
            config = eraConfig,
            gameBase = eraBase,
            slotCount = 20,
            occupied = {
                askedForFreeSlot = true
                null
            },
        )

        assertEquals(SaveImport.Reject("存档位编号太大"), plan)
        assertFalse("解析失败不能退化成「找空位」", askedForFreeSlot)
    }

    // -----------------------------------------------------------------
    // writeZip：Error 上抛
    // -----------------------------------------------------------------

    @Test
    fun `writeZip 不吞 Error`() {
        val root = temp.newFolder("game")
        val file = File(root, "save00.sav").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val oom = object : OutputStream() {
            override fun write(b: Int): Unit = throw OutOfMemoryError("模拟堆耗尽")
            override fun write(b: ByteArray, off: Int, len: Int): Unit = throw OutOfMemoryError("模拟堆耗尽")
        }

        assertThrows(OutOfMemoryError::class.java) {
            SaveExport.writeZip(oom, listOf(SaveExport.Entry("sav/save00.sav", file)))
        }
    }
}
