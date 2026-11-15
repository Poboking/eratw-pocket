package com.eratw.port.platform.save

import com.eratw.port.core.data.save.SaveData
import com.eratw.port.core.data.save.SaveFormat
import com.eratw.port.core.fn.SaveFileKind
import com.eratw.port.core.save.SaveConfig
import com.eratw.port.core.save.SidecarSaveBundle
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * RT2 C 车：导出/导入一并认 `.rt2` 侧车的判据（CONTRACT-RT2-C §5.4，
 * 含 `SaveImport.kt` 的 `.endsWith(".sav")` 拒收闸放行 `.sav.rt2`）。
 *
 * MODGC2 裁定 1 把「复制相位」的配对判据钉在这里：导出只收**完整逻辑单元**（本体不在包 ⇒
 * 侧车进 `Skipped`），导入侧车必须「同槽本体在盘 or 同批本体已判可落位」（`planBatch` 两趟，
 * 与选择顺序无关），否则 `Reject`。
 *
 * `.rt2` 字节用真实容器编码（CRC 自洽），损坏样本走「尾部截断」而不是手拼假字节。
 */
class SaveSidecarImportExportTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val config = SaveConfig.ERATW

    private fun root(): File = temp.newFolder("game")

    private fun put(root: File, rel: String, bytes: ByteArray): File {
        val file = File(root, rel)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return file
    }

    private fun rt2Bytes(slot: Int): ByteArray = SidecarSaveBundle(
        binding = com.eratw.port.core.save.SidecarBinding(1L, 2L, slot),
        registrySnapshot = ByteArray(0),
        entityGraphPayload = ByteArray(0),
        chunks = listOf(
            com.eratw.port.core.save.PackageChunk(
                "school.mod",
                com.eratw.port.core.mod.ModStorageSectionCodec.encode(
                    listOf(
                        com.eratw.port.core.mod.ModStorageSection(
                            "school.mod",
                            mapOf("gold" to com.eratw.port.modapi.ModValue.StrValue("100")),
                        ),
                    ),
                ),
            ),
        ),
    ).encode()

    private fun corruptedRt2(): ByteArray = rt2Bytes(0).copyOfRange(0, rt2Bytes(0).size - 1)

    /** 真 `.sav` 本体字节（判读要过头探测，`byteArrayOf(1)` 那种假字节会被判坏档）。 */
    private fun savBytes(text: String = "槽本体"): ByteArray =
        SaveFormat.write(SaveData(SaveFileKind.NORMAL, 1L, 2L, text, compressed = true))

    // -----------------------------------------------------------------
    // 导出：.sav 与 .sav.rt2 成对收集
    // -----------------------------------------------------------------

    @Test
    fun `导出收集槽档与其 rt2 侧车及全局侧车`() {
        val root = root()
        put(root, "sav/save03.sav", byteArrayOf(1))
        put(root, "sav/save03.sav.rt2", rt2Bytes(3))
        put(root, "sav/global.sav", byteArrayOf(2))
        put(root, "sav/global.sav.rt2", rt2Bytes(0))
        put(root, "sav/moddata.rt2", rt2Bytes(0)) // 非 .sav.rt2 形状，不收

        val bundle = SaveExport.collect(root, config)

        assertEquals(
            listOf("sav/global.sav", "sav/global.sav.rt2", "sav/save03.sav", "sav/save03.sav.rt2"),
            bundle.entries.map { it.name },
        )
        assertTrue(bundle.skipped.isEmpty())
    }

    @Test
    fun `导出 zip 里侧车字节逐字节原样`() {
        val root = root()
        put(root, "sav/save00.sav", savBytes())
        val sidecar = put(root, "sav/save00.sav.rt2", rt2Bytes(0))
        val bundle = SaveExport.collect(root, config)

        val out = ByteArrayOutputStream()
        val result = SaveExport.writeZip(out, bundle.entries)
        assertTrue(result.ok)

        val read = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                read[entry.name] = zip.readBytes()
            }
        }
        assertTrue(read.getValue("sav/save00.sav.rt2").contentEquals(rt2Bytes(0)))
    }

    // -----------------------------------------------------------------
    // 导入：拒收闸放行 .sav.rt2，落位路径 = 槽本体路径 + .rt2
    // -----------------------------------------------------------------

    private fun planOf(
        fileName: String,
        bytes: ByteArray,
        occupied: (String) -> ByteArray? = { null },
        bodyPresent: (String) -> Boolean = { true },
    ): SaveImport.Plan =
        SaveImport.plan(fileName, bytes, SaveImport.Context(config, null, 20, occupied, bodyPresent))

    private fun batchOf(
        items: List<SaveImport.Item>,
        occupied: (String) -> ByteArray? = { null },
    ): List<SaveImport.Plan> = SaveImport.planBatch(items, SaveImport.Context(config, null, 20, occupied))

    @Test
    fun `导入认 sav-rt2 并落位到同槽侧车路径`() {
        val plan = planOf("save03.sav.rt2", rt2Bytes(3)) as SaveImport.Place
        assertEquals("sav/save03.sav.rt2", plan.targetRel)
        assertEquals("存档位 3", plan.slotLabel)
        assertFalse(plan.occupied)
    }

    @Test
    fun `导入认 global-rt2 并落位到全局侧车路径`() {
        val plan = planOf("global.sav.rt2", rt2Bytes(0)) as SaveImport.Place
        assertEquals("sav/global.sav.rt2", plan.targetRel)
        assertEquals("全局存档", plan.slotLabel)
    }

    @Test
    fun `导入损坏的 rt2 按坏档拒绝`() {
        val plan = planOf("save03.sav.rt2", corruptedRt2())
        assertEquals(SaveDataProbeExpect.mesCorrupted, (plan as SaveImport.Reject).reason)
    }

    @Test
    fun `导入非 sav-rt2 后缀仍然拒收`() {
        assertEquals(
            "只能导入 .sav 存档文件",
            (planOf("moddata.rt2", rt2Bytes(0)) as SaveImport.Reject).reason,
        )
    }

    @Test
    fun `导入往返 - commit 落位后目标格读得回同字节`() {
        val root = root()
        val bytes = rt2Bytes(7)
        val plan = planOf("save07.sav.rt2", bytes) as SaveImport.Place
        assertTrue(SaveImport.commit(root, plan.targetRel, bytes))
        assertTrue(
            File(root, plan.targetRel).readBytes().contentEquals(bytes),
        )
        // 同名侧车本体 save07.sav 不受影响（.rt2 落位不动 .sav）。
        assertFalse(File(root, "sav/save07.sav").exists())
    }

    @Test
    fun `目标格已有侧车时按占用报且读得出备注`() {
        val bytes = rt2Bytes(3)
        val plan = planOf(
            "save03.sav.rt2",
            bytes,
            occupied = { rel -> if (rel == "sav/save03.sav.rt2") bytes else null },
        )
        assertTrue((plan as SaveImport.Place).occupied)
        assertEquals("Mod 扩展存档（.rt2 侧车）", plan.occupiedMes)
    }

    @Test
    fun `导入孤儿侧车 - 缺同槽本体一律拒收且不替玩家造本体`() {
        // MODGC2 裁定 1：侧车不单独落位。判据 = 同槽本体「在盘 or 本批已判可落位」，
        // 两者都不成立 ⇒ Reject（半套侧车读档时会配到别的本体上）。
        val bytes = rt2Bytes(9)
        val orphan = planOf("save09.sav.rt2", bytes, bodyPresent = { false })
        assertEquals(SaveImport.REASON_SIDECAR_WITHOUT_BODY, (orphan as SaveImport.Reject).reason)

        // 本体在盘 ⇒ 同一份侧车可落位（不必把本体也再选一遍）。
        val paired = planOf("save09.sav.rt2", bytes, bodyPresent = { it == "sav/save09.sav" })
        assertEquals("sav/save09.sav.rt2", (paired as SaveImport.Place).targetRel)
        val gameRoot = root()
        assertTrue(SaveImport.commit(gameRoot, paired.targetRel, bytes))
        assertTrue(File(gameRoot, "sav/save09.sav.rt2").readBytes().contentEquals(bytes))
        assertFalse("导入侧车不得顺手造出本体 .sav", File(gameRoot, "sav/save09.sav").exists())
    }

    // -----------------------------------------------------------------
    // MODGC2 裁定 1：整批配对（顺序无关）
    // -----------------------------------------------------------------

    @Test
    fun `planBatch - 本体与侧车同批时与选择顺序无关`() {
        val body = SaveImport.Item("save03.sav", savBytes())
        val sidecar = SaveImport.Item("save03.sav.rt2", rt2Bytes(3))

        val forward = batchOf(listOf(body, sidecar))
        val backward = batchOf(listOf(sidecar, body))

        assertEquals(
            listOf("sav/save03.sav", "sav/save03.sav.rt2"),
            forward.map { (it as SaveImport.Place).targetRel },
        )
        assertEquals(
            "侧车先选中也必须等到同批本体判可落位",
            listOf("sav/save03.sav.rt2", "sav/save03.sav"),
            backward.map { (it as SaveImport.Place).targetRel },
        )
    }

    @Test
    fun `planBatch - 批内本体被判坏档时其侧车同拒（不留半套）`() {
        val brokenBody = SaveImport.Item("save03.sav", byteArrayOf(1, 2, 3))
        val sidecar = SaveImport.Item("save03.sav.rt2", rt2Bytes(3))

        val plans = batchOf(listOf(sidecar, brokenBody))

        assertTrue("坏档本体照样拒收", plans[1] is SaveImport.Reject)
        assertEquals(
            "本体没进来 ⇒ 侧车不许单独落位",
            SaveImport.REASON_SIDECAR_WITHOUT_BODY,
            (plans[0] as SaveImport.Reject).reason,
        )
    }

    @Test
    fun `planBatch - 本体已在盘时单选的侧车可落位`() {
        val sidecar = SaveImport.Item("global.sav.rt2", rt2Bytes(0))
        val plans = batchOf(listOf(sidecar)) { rel ->
            if (rel == "sav/global.sav") byteArrayOf(9) else null
        }
        assertEquals("sav/global.sav.rt2", (plans.single() as SaveImport.Place).targetRel)
    }

    @Test
    fun `planBatch - 批内本体被占用也仍算可落位（只是需要确认覆盖）`() {
        val body = SaveImport.Item("save03.sav", savBytes())
        val sidecar = SaveImport.Item("save03.sav.rt2", rt2Bytes(3))
        val occupiedBody = savBytes("旧的槽三")

        val plans = batchOf(listOf(body, sidecar)) { rel ->
            if (rel == "sav/save03.sav") occupiedBody else null
        }

        val bodyPlan = plans[0] as SaveImport.Place
        val sidecarPlan = plans[1] as SaveImport.Place
        assertTrue("同槽已有本体 ⇒ 需要玩家确认覆盖", bodyPlan.occupied)
        assertEquals("配对判据与占用无关", "sav/save03.sav.rt2", sidecarPlan.targetRel)
        // 同一单元的成员归到同一个 key（设置页据此整单元确认）。
        assertEquals(
            SaveImport.bodyTargetOf(bodyPlan.targetRel),
            SaveImport.bodyTargetOf(sidecarPlan.targetRel),
        )
    }

    // -----------------------------------------------------------------
    // MODGC2 裁定 1：孤儿侧车在导出收集面进 Skipped（复制出相位）
    // -----------------------------------------------------------------

    @Test
    fun `孤儿侧车不随包 - 本体缺失的侧车进 Skipped 并给理由`() {
        // 历史遗留形态（DELDATA 遗留 / 迁移随迁残留）：只有 .rt2、没有 .sav。
        // 一个只有侧车的包导到别的设备上谁也读不了（导入面同样拒收），收它只是搬孤儿。
        val root = root()
        put(root, "sav/save05.sav.rt2", rt2Bytes(5))
        put(root, "global.sav.rt2", rt2Bytes(0))

        val bundle = SaveExport.collect(root, config)

        assertTrue("本体缺失 ⇒ 一件都不进包", bundle.entries.isEmpty())
        assertEquals(
            "生效目录先扫、兜底目录随后（与本体同一顺序）",
            listOf("save05.sav.rt2", "global.sav.rt2"),
            bundle.skipped.map { it.name },
        )
        assertTrue(
            "理由必须是配对判据那一句（设置页按它出三语）",
            bundle.skipped.all { it.reason == SaveImport.REASON_SIDECAR_WITHOUT_BODY },
        )
    }

    @Test
    fun `事务残件不随包 - journal 与两成员残件不在导出收集面`() {
        val root = root()
        put(root, "sav/save00.sav", byteArrayOf(1))
        for (name in listOf(
            "save00.sav.journal",
            "save00.sav.part",
            "save00.sav.bak",
            "save00.sav.rt2.part",
            "save00.sav.rt2.bak",
        )) {
            put(root, "sav/$name", byteArrayOf(9))
        }

        val bundle = SaveExport.collect(root, config)

        assertEquals(
            "收集面只有本体与 .rt2 侧车；配对事务残件一个都不收",
            listOf("sav/save00.sav"),
            bundle.entries.map { it.name },
        )
        assertTrue(bundle.skipped.isEmpty())
    }

    @Test
    fun `兜底目录孤儿侧车与生效目录本体在包内同槽配对`() {
        // 迁移随迁失败的残留形态：本体在生效目录（sav），侧车留在兜底目录（根）。
        // 收集面两目录都扫、按文件名先到先得 ⇒ 包内同槽两名各归其位（跨目录补回配对）。
        val root = root()
        put(root, "sav/save03.sav", byteArrayOf(1))
        put(root, "save03.sav.rt2", rt2Bytes(3))

        val bundle = SaveExport.collect(root, config)

        assertEquals(
            "兜底目录的侧车按原名进包，与生效目录的本体同槽",
            listOf("sav/save03.sav", "sav/save03.sav.rt2"),
            bundle.entries.map { it.name },
        )
    }

    /** 复用既有 MES 常量的取值（导入侧文案与游戏内 CHKDATA 同源）。 */
    private object SaveDataProbeExpect {
        val mesCorrupted: String =
            com.eratw.port.core.save.SaveDataProbe.MES_CORRUPTED
    }
}
