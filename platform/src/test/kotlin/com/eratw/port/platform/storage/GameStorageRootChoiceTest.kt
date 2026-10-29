package com.eratw.port.platform.storage

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 真机数据根的**根选择**（M6-GAMEROOT）—— 三条路径全部用纯函数钉死，不建 `Context`。
 *
 * 判据来自 deviceboot 路实测的装载链：`GameHost` 只读 `<gameRoot>/erbc.v1/{program.erbc,program.meta}`，
 * 而旧判据只在外部根有 `emuera.config` 时才选外部根 ⇒ 任务口径「只推 `erbc.v1` 到外部 files 根」
 * 会去 `filesDir/eratw` 找产物（adb 推不进），屏上「缺少 …/program.erbc」。
 *
 * 1. 外部根有 `emuera.config` → 外部（原口径，防回归）；
 * 2. 外部根只有 `erbc.v1/program.erbc` → **也选外部**（本路新增）；
 * 3. 都没有 → 内部 `filesDir/eratw`；
 * 4. 候选产物目录外部在前：缺失文案并列两条，屏上直接能看出该往哪推。
 */
class GameStorageRootChoiceTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun externalWith(vararg files: String): File {
        val dir = temp.newFolder("external")
        for (rel in files) {
            File(dir, rel).apply {
                parentFile?.mkdirs()
                writeText("")
            }
        }
        return dir
    }

    private fun internalRoot(): File = File(temp.newFolder("files"), "eratw")

    @Test
    fun `外部根有 emuera_config 时选外部`() {
        val external = externalWith("emuera.config")
        val internal = internalRoot()

        assertSame(external, GameStorage.selectRoot(external, internal))
    }

    @Test
    fun `外部根只有 erbc_v1 产物时也选外部`() {
        // 只推产物的 adb 口径：没有 emuera.config，但 erbc.v1/program.erbc 在。
        val external = externalWith("erbc.v1/program.erbc")
        val internal = internalRoot()

        assertTrue(GameStorage.looksLikeGameRoot(external))
        assertSame("只推产物必须能命中外部根（旧判据在这里回落内部）", external, GameStorage.selectRoot(external, internal))
    }

    @Test
    fun `两条候选都没有时回落内部根`() {
        val external = externalWith("CSV/Chara.csv") // 有语料但没产物、没配置
        val internal = internalRoot()

        assertFalse(GameStorage.looksLikeGameRoot(external))
        assertSame(internal, GameStorage.selectRoot(external, internal))
    }

    @Test
    fun `外部存储不可用时只有内部根`() {
        val internal = internalRoot()
        assertEquals(listOf(internal), GameStorage.candidateRoots(null, internal))
        assertSame(internal, GameStorage.selectRoot(null, internal))
    }

    @Test
    fun `标志文件是目录时不算游戏根`() {
        val external = temp.newFolder("external")
        assertTrue(File(external, "emuera.config").mkdirs())
        assertTrue(File(external, "erbc.v1/program.erbc").mkdirs())

        assertFalse("同名的目录不是标志文件", GameStorage.looksLikeGameRoot(external))
    }

    @Test
    fun `候选产物目录外部在前内部在后`() {
        val external = externalWith("erbc.v1/program.erbc")
        val internal = internalRoot()

        assertEquals(
            listOf(File(external, "erbc.v1"), File(internal, "erbc.v1")),
            GameStorage.candidateRoots(external, internal).map { File(it, "erbc.v1") },
        )
    }
}
