package com.eratw.port.platform.save

import com.eratw.port.core.data.csv.CsvLoader
import com.eratw.port.core.data.csv.GameBaseLoader
import com.eratw.port.core.data.save.SaveData
import com.eratw.port.core.data.save.SaveFormat
import com.eratw.port.core.fn.SaveFileKind
import com.eratw.port.core.save.SaveConfig
import com.eratw.port.core.vars.GameBaseData
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [SaveImport] 的判据 —— 只钉「落位决策 + 原子落盘」这两件纯 JVM 的事。
 *
 * 期望形状的出处：
 *   * 槽名与落位路径：`getSaveDataPath(int)` = `{SavDir}save{index:00}.sav`
 *     （spec: `VariableEvaluator.cs:2023-2032`，移植侧 [SaveConfig.normalPath]）；
 *     `UseSaveFolder` 决定 `SavDir` 带不带 `sav/`（spec: `Config.cs:471-481`）；
 *   * 全局档不吃下标：`getSaveDataPathG()` = `{SavDir}global.sav`
 *     （spec: `VariableEvaluator.cs:2018-2021`）；
 *   * 拒绝文案 = 原版 `CHKDATA` 的那三句（lang/emuera.zhs.xml:326-329，
 *     spec: `VariableEvaluator.cs:2196-2215` 的分支序）—— 设置页与游戏内必须同话；
 *   * 游戏码/版本两道闸：[GameBaseData] 的 `コード`/`バージョン`/`バージョン違い認める`
 *     （spec: `GameBase.cs:60-80`，本作实测 `コード,7153`、`バージョン,proto`、`0012`）。
 *
 * 全部用例自建临时目录与自造档字节（不碰语料、不硬编码机器路径）。
 */
class SaveImportTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** 本作实机口径：二进制 + 压缩 + 存档进 `sav/`（`emuera.config:51/60/74`）。 */
    private val eraConfig = SaveConfig.ERATW

    /** 本作 GAMEBASE 的生效三值（CSV/GAMEBASE.CSV:1/2/6）。 */
    private val eraBase = GameBaseData(gameCode = 7153L, version = 0L, versionDefined = false, allowVersion = 12L)

    private fun slot(no: Long, text: String = "第 3 日 博丽灵梦", code: Long = 7153L): ByteArray =
        SaveFormat.write(SaveData(SaveFileKind.NORMAL, code, 0L, text, compressed = true))

    private fun globalBytes(text: String = "全局"): ByteArray =
        SaveFormat.write(SaveData(SaveFileKind.GLOBAL, 7153L, 0L, text, compressed = true))

    /** 什么都没有的空盘。 */
    private val nothingOccupied: (String) -> ByteArray? = { null }

    private fun planOf(name: String, bytes: ByteArray, config: SaveConfig = eraConfig, base: GameBaseData? = eraBase, slotCount: Int = 20, occupied: (String) -> ByteArray? = nothingOccupied) =
        SaveImport.plan(name, bytes, config, base, slotCount, occupied)

    // -----------------------------------------------------------------
    // 落位：槽号从文件名认
    // -----------------------------------------------------------------

    @Test
    fun `标准槽名落到 sav 下同名格并带回备注`() {
        val plan = planOf("save03.sav", slot(3L, "第 7 日 雾雨魔理沙")) as SaveImport.Place
        assertEquals("sav/save03.sav", plan.targetRel)
        assertEquals("存档位 3", plan.slotLabel)
        assertEquals("第 7 日 雾雨魔理沙", plan.mes)
        assertFalse(plan.occupied)
        assertNull(plan.occupiedMes)
    }

    @Test
    fun `没补零与全大写的槽名认成同一格`() {
        // 原版读侧按 `%02d` 组名，玩家手改过的 `save7.sav` 也只会落在 save07 这一格。
        val plan = planOf("SAVE7.SAV", slot(7L)) as SaveImport.Place
        assertEquals("sav/save07.sav", plan.targetRel)
        assertEquals("存档位 7", plan.slotLabel)
    }

    @Test
    fun `全局档按文件名认落到 global 路径`() {
        val plan = planOf("global.sav", globalBytes()) as SaveImport.Place
        assertEquals("sav/global.sav", plan.targetRel)
        assertEquals("全局存档", plan.slotLabel)
    }

    @Test
    fun `不开 sav 文件夹时落在游戏根`() {
        // UseSaveFolder = NO ⇒ SavDir = ExeDir（spec: Config.cs:478-481）
        val plan = planOf("save05.sav", slot(5L), config = SaveConfig(systemSaveInBinary = true)) as SaveImport.Place
        assertEquals("save05.sav", plan.targetRel)
    }

    @Test
    fun `文件名认不出槽号时找第一个空位`() {
        val zeroTaken: (String) -> ByteArray? = { if (it == "sav/save00.sav") slot(0L) else null }
        val plan = planOf("我的存档.SAV", slot(9L), occupied = zeroTaken) as SaveImport.Place
        assertEquals("sav/save01.sav", plan.targetRel)
        assertEquals("存档位 1", plan.slotLabel)
    }

    @Test
    fun `槽位全满时拒收`() {
        val allTaken: (String) -> ByteArray? = { slot(0L) }
        val plan = planOf("我的存档.sav", slot(1L), slotCount = 20, occupied = allTaken)
        assertEquals("存档位已经满了，没有空位", (plan as SaveImport.Reject).reason)
    }

    // -----------------------------------------------------------------
    // 兜底：不收的东西给原话
    // -----------------------------------------------------------------

    @Test
    fun `只收扩展名是 sav 的文件`() {
        assertEquals("只能导入 .sav 存档文件", (planOf("save03.txt", slot(3L)) as SaveImport.Reject).reason)
        assertEquals("只能导入 .sav 存档文件", (planOf("攻略.txt", slot(3L)) as SaveImport.Reject).reason)
    }

    @Test
    fun `不是存档的字节用原版损坏文案拒收`() {
        val reason = (planOf("save04.sav", "这不是存档".toByteArray()) as SaveImport.Reject).reason
        assertEquals("存档数据已损坏", reason)
        assertEquals("存档数据已损坏", (planOf("save04.sav", ByteArray(0)) as SaveImport.Reject).reason)
    }

    @Test
    fun `超过体积上限的文件不收`() {
        val big = ByteArray(SaveImport.MAX_SAVE_BYTES + 1)
        val reason = (planOf("save06.sav", big) as SaveImport.Reject).reason
        assertTrue(reason, reason.contains("不像存档"))
    }

    @Test
    fun `类别对不上的档按损坏拒收`() {
        // 内容是 GLOBAL 却顶着 saveNN 的名字：与 checkBytes 的二进制类别分支同一判据
        // （spec: VariableEvaluator.cs:2196-2201）。
        val reason = (planOf("save02.sav", globalBytes()) as SaveImport.Reject).reason
        assertEquals("存档数据已损坏", reason)
    }

    // -----------------------------------------------------------------
    // GAMEBASE 两道闸：有闸必判，没闸不伪造
    // -----------------------------------------------------------------

    @Test
    fun `别家游戏的档被认出并给原版文案`() {
        val reason = (planOf("save08.sav", slot(8L, code = 999L)) as SaveImport.Reject).reason
        assertEquals("试图读取的存档属于另一个游戏", reason)
    }

    @Test
    fun `版本不同样给原版文案`() {
        val strict = GameBaseData(gameCode = 7153L, version = 2000L, versionDefined = true, allowVersion = 5000L)
        val bytes = SaveFormat.write(SaveData(SaveFileKind.NORMAL, 7153L, 1000L, "旧版档", compressed = true))
        assertEquals("存档版本不同", (planOf("save09.sav", bytes, base = strict) as SaveImport.Reject).reason)
    }

    @Test
    fun `读不到自己的 GAMEBASE 时不伪造别家游戏`() {
        // 没有闸门可比 ⇒ 只判容器与类别，判定权交回读档那一刻的引擎（LOADDATA 侧同一道 GameBaseGate）。
        val plan = planOf("save08.sav", slot(8L, code = 999L), base = null)
        assertEquals("sav/save08.sav", (plan as SaveImport.Place).targetRel)
    }

    // -----------------------------------------------------------------
    // 覆盖要确认，绝不静默吃掉玩家已有的档
    // -----------------------------------------------------------------

    @Test
    fun `目标格已有档时把它的备注一起交出`() {
        val taken: (String) -> ByteArray? = { if (it == "sav/save03.sav") slot(3L, "第 1 日 旧档") else null }
        val plan = planOf("save03.sav", slot(3L, "第 9 日 新档"), occupied = taken) as SaveImport.Place
        assertEquals("第 9 日 新档", plan.mes)
        assertTrue(plan.occupied)
        assertEquals("第 1 日 旧档", plan.occupiedMes)
    }

    @Test
    fun `目标格是坏档时仍报已占用但不给备注`() {
        // 存在性才是「要不要确认」的判据：那一格读不出备注，也还是玩家放进去的东西，
        // 不能静默盖掉（这一条就是这条闸存在的理由）。
        val broken: (String) -> ByteArray? = { if (it == "sav/save03.sav") "junk".toByteArray() else null }
        val plan = planOf("save03.sav", slot(3L), occupied = broken) as SaveImport.Place
        assertTrue(plan.occupied)
        assertNull(plan.occupiedMes)
    }

    @Test
    fun `目标格大得读不动时按占位处理且不抛`() {
        // 设置页的 occupied 回调对「超体积上限」交空数组（占位但不进堆）——
        // 空字节必须走同一条拒收支，不许把探测炸成异常。
        val overCap: (String) -> ByteArray? = { if (it == "sav/save03.sav") ByteArray(0) else null }
        val plan = planOf("save03.sav", slot(3L), occupied = overCap) as SaveImport.Place
        assertTrue(plan.occupied)
        assertNull(plan.occupiedMes)
    }

    // -----------------------------------------------------------------
    // 落盘
    // -----------------------------------------------------------------

    @Test
    fun `落盘自动建目录不留半成品并可覆盖`() {
        val root = temp.newFolder("game")
        val bytes = slot(3L, "第 3 日")
        assertTrue(SaveImport.commit(root, "sav/save03.sav", bytes))

        val dst = File(root, "sav/save03.sav")
        assertTrue(dst.isFile)
        assertArrayEqualsQuiet(bytes, dst.readBytes())
        // 同目录不能留下 .part 中间产物
        assertEquals(
            listOf("save03.sav"),
            File(root, "sav").listOrEmpty().sorted(),
        )

        // 覆盖是同路径换名（调用方已确认）
        val newer = slot(3L, "第 4 日")
        assertTrue(SaveImport.commit(root, "sav/save03.sav", newer))
        assertArrayEqualsQuiet(newer, dst.readBytes())
    }

    @Test
    fun `落盘失败返回 false 不抛`() {
        val root = temp.newFolder("ro")
        val dst = File(root, "sav")
        dst.writeText("我是文件不是目录")
        assertTrue(dst.isFile)
        // 目标是普通文件、父目录建不出来 ⇒ 换名必失败
        assertFalse(SaveImport.commit(root, "sav/save03.sav", slot(3L)))
    }

    // -----------------------------------------------------------------
    // 真语料门（不设 ERATW_GAMEDIR 时不生成，看着仍像全绿 —— 跑它要带 cleanTest）
    // -----------------------------------------------------------------

    @Test
    fun `PC 原产档在真 GAMEBASE 下判可落位并读得出备注`() {
        val gameDir = System.getenv("ERATW_GAMEDIR")?.trim()?.takeIf { it.isNotEmpty() }
        Assume.assumeTrue("ERATW_GAMEDIR 未设置：跳过真语料门", gameDir != null)
        // 上一条 assume 未命中就会抛出中断本用例，走到这里 gameDir 必非空。
        val root = File(gameDir!!)
        val pcSave = File(root, "sav/save00.sav")
        Assume.assumeTrue("语料里没有 sav/save00.sav：$pcSave", pcSave.isFile)

        // GAMEBASE 走设置页同一条装载路（CSV 目录按文件名大小写不敏感解析）。
        val csvDir = File(root, "CSV").toPath()
        assertNotNull("CSV/GameBase.csv 没解析到", CsvLoader.resolveFile(csvDir, "GameBase.csv"))
        val gameBase = GameBaseLoader.load(csvDir, mutableListOf())

        val bytes = pcSave.readBytes()
        val plan = SaveImport.plan("save00.sav", bytes, eraConfig, gameBase, 20, nothingOccupied)
        plan as SaveImport.Place
        // 落位必须与引擎读的那一格同名（PC 上叫什么，安卓上就在哪一格）
        assertEquals("sav/save00.sav", plan.targetRel)
        assertEquals("存档位 0", plan.slotLabel)
        assertFalse(plan.occupied)
        // 两道闸真的放行了这份 PC 档（否则上面会是 Reject：属于另一个游戏 / 版本不同）
        assertTrue("备注读不出来：${plan.mes}", plan.mes.isNotBlank())
    }

    private fun java.io.File.listOrEmpty(): List<String> = list()?.toList() ?: emptyList()

    private fun assertArrayEqualsQuiet(expected: ByteArray, actual: ByteArray) {
        assertEquals(expected.size, actual.size)
        assertTrue("字节不一致", expected.contentEquals(actual))
    }
}
