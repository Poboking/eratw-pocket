package com.eratw.port.android

import com.eratw.port.core.entity.RealCharaSlotProjector
import com.eratw.port.core.entity.RelationProjectionSynchronizer
import com.eratw.port.core.entity.RelationshipSchema
import com.eratw.port.core.entity.VirtualCharaNoRegistry
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.mod.ModDeclarationAdapter
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.save.SidecarBinding
import com.eratw.port.core.save.SidecarSaveBundle
import com.eratw.port.core.save.SidecarSaveWiring
import com.eratw.port.core.vars.CharacterTemplate
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modapi.ArchetypeDecl
import com.eratw.port.modapi.ComponentDecl
import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.FieldDecl
import com.eratw.port.modapi.FieldType
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private const val PACKAGE_ID: String = "assembly.roster"
private const val ARCHETYPE: String = "PERSON"

private val MANIFEST: ModManifest = ModManifest(
    packageId = PACKAGE_ID,
    components = listOf(
        ComponentDecl(name = "roster", fields = listOf(FieldDecl("hp", FieldType.INT))),
    ),
    archetypes = listOf(ArchetypeDecl(ARCHETYPE, listOf("roster"))),
)

/**
 * RT2 车次表 F 车 / MR-2b-2 的**真引擎纵向切片**（era-arch-v61u.md §23.2 F2 / §23.4 F 行 /
 * §23.5 第 11 条）。与 E 车 `RosterProjectionWireTest` 的区别：那一组手工拼装
 * `ModRuntimeHost` 并显式传 `projector`，量不出「正式会话里到底有没有人接线」；
 * 本组一律走**生产 `ModRuntimeAssembly.attach` + 生产 `defaultHost`**（不传 `newHost`），
 * 装置全部是真的：真 `ManifestLoader.load` 读磁盘 manifest 字节 → 真
 * `DeclarationRegistry.freeze` 快照 → 真 `EntityKernel` → 真 `VariableStore`（三行原生角色）
 * → 真 `RealCharaSlotProjector` → 真 `EraMod`。没有 fake 自证。
 *
 * 判据：F2 调用序列 `project → rosterGeneration → charIndexOfChecked → unproject` 在
 * 正式会话里第一次可达；虚拟 NO 经 `VariableStore.resolveCharaPos` 解析到**当前**投射行；
 * 存档侧车抓到与真桥同一份注册表；退场后虚拟 NO 立刻悬空且报错仍带脚本原值。
 *
 * 反向证明：把 `defaultHost` 的 `projector = inputs.projector` 退回不传真桥 ⇒
 * `context.roster` 四入口全部 fail-closed，本文件两例红；把 resolver 注入退化成不注入
 * 或恒 `{-1}` 的 `rowOf` ⇒ 虚拟 NO 解析两例红。恢复源码后复绿。
 */
class RosterProjectionAssemblyTest {

    /** onLoad 里经 mod-api 的 `entities.create` 建一个真实体，并留下 context 供 facade 调用。 */
    private class ProbeMod : EraMod {
        lateinit var context: ModContext
        // EntityId 是 inline value class，不能用 lateinit；沿用仓库既有 EntityMod 的列表形状。
        val created = mutableListOf<EntityId>()

        override fun onLoad(context: ModContext) {
            this.context = context
            created += context.entities.create(ARCHETYPE)
        }

        override fun onEnable() = Unit
        override fun onDisable() = Unit
        override fun onUnload() = Unit
    }

    private class Fixture(relSchemas: List<RelationshipSchema> = emptyList()) {
        val logs = mutableListOf<String>()
        val root: Path = Files.createTempDirectory("rt2-f-slice").also {
            Files.createDirectories(it.resolve("mods").resolve("rt2"))
        }
        val store = VariableStore()
        val probe = ProbeMod()
        val session: ModRuntimeSession

        init {
            Files.write(
                root.resolve("mods").resolve("rt2").resolve("assembly_roster.manifest"),
                ManifestCodec.encode(
                    ModDeclarationAdapter.toDescriptor(MANIFEST).copy(relationshipSchemas = relSchemas),
                ),
            )
            repeat(3) { i ->
                val no = (i + 1).toLong()
                store.registerTemplate(CharacterTemplate(no = no, name = "角色$no", csvNo = no))
                store.addCharacter(no)
            }
            session = checkNotNull(
                ModRuntimeAssembly.attach(
                    gameRoot = root.toFile(),
                    store = store,
                    nativeRegistry = NativeMethodRegistry(),
                    onVmThread = { true },
                    enabled = true,
                    logSink = { line -> logs += line },
                    modProviders = listOf(PACKAGE_ID to probe),
                ),
            ) { "生产缝必须产出运行时；logs=$logs" }
            check(logs.any { it == "mod.install pkg=$PACKAGE_ID active=true" }) {
                "生产注册表 + 磁盘 manifest 必须让探针真装成功；logs=$logs"
            }
            assertTrue(
                "生产缝的投射桥必须是真实现（不是空投射器许口）",
                session.charaSlotProjector is RealCharaSlotProjector,
            )
        }

        val roster get() = probe.context.roster
        val rows: Int get() = store.characters.count
        val child: EntityId get() = probe.created.single()
    }

    /** 悬空虚拟 NO 必须走既有越界路径，且文案第 3 实参 = 脚本看到的原值。 */
    private fun assertOorWithRawValue(store: VariableStore, raw: Long, expectedInMessage: String) {
        var message: String? = null
        try {
            store.readInt("CFLAG", longArrayOf(raw, 0L))
            fail("悬空虚拟 NO $raw 必须走既有越界路径")
        } catch (expected: CodeEE) {
            message = expected.message
        }
        assertTrue(
            "越界文案第 3 实参必须是脚本原值 $expectedInMessage，实际：$message",
            message.orEmpty().contains(expectedInMessage),
        )
    }

    @Test
    fun `生产缝真引擎纵向切片 投射读世代校验虚拟NO解析存档段退场全链跑通`() {
        val f = Fixture()
        assertEquals("装置先铺 3 行原生角色", 3, f.rows)
        assertNotNull("生产缝必须给出虚拟 NO 注册表", f.session.virtualNoRegistry)

        // ---- F2 调用序列：正式会话里第一次可达 ----
        val generation = f.roster.rosterGeneration
        val row = f.roster.project(f.child)
        assertEquals("ADDVOIDCHARA 语义：伪角色尾部追加在末行", 3, row)
        assertEquals("登场后角色行数 +1", 4, f.rows)
        assertEquals("尾部追加不动既有行号，不递增世代", generation, f.roster.rosterGeneration)
        assertEquals("同世代现换 CHARA 命中", row, f.roster.charIndexOfChecked(f.child, generation))
        assertEquals("伪造的未来世代必须拒绝", -1, f.roster.charIndexOfChecked(f.child, generation + 1))

        // ---- 虚拟 NO：真桥分配、写进 NO 列、经 store 解析到当前投射行 ----
        val vno = checkNotNull(f.session.virtualNoRegistry.virtualNoOf(f.child.raw)) {
            "project 必须给实体分配虚拟 NO"
        }
        assertEquals("NO 列存虚拟 NO", vno.toLong(), f.store.readInt("NO", longArrayOf(row.toLong())))
        f.store.writeInt("CFLAG", longArrayOf(row.toLong(), 0L), 777L)
        assertEquals(
            "虚拟 NO 必须经 VariableStore.resolveCharaPos 解析到当前投射行",
            777L,
            f.store.readInt("CFLAG", longArrayOf(vno.toLong(), 0L)),
        )
        assertEquals("相邻原生行不受影响", 0L, f.store.readInt("CFLAG", longArrayOf(0L, 0L)))
        assertOorWithRawValue(f.store, 50_000L, "50000")

        // ---- 存档面：sidecar 带的是真桥那一份注册表，不是恒空许口 ----
        val wiring = checkNotNull(SidecarSaveWiring.of(f.store)) { "开态必须装 sidecar 接线" }
        val bytes = wiring.port.captureRt2Bytes(
            SidecarBinding(uniqueCode = 1L, scriptVersion = 1L, saveTextHash = 0),
            emptyList(),
            emptyList(),
        )
        val bundle = SidecarSaveBundle.decode(bytes)
        val restored = VirtualCharaNoRegistry()
        restored.mergeSnapshot(bundle.virtualNoSnapshot)
        assertEquals(
            "SaveService.virtualNoRegistry 必须与真桥同一实例（解出的身份映射要命中该实体）",
            f.child.raw,
            restored.entityOf(vno),
        )

        // ---- 退场：行数 -1、世代递增、过期世代拒绝 ----
        assertTrue("退场必须成功", f.roster.unproject(f.child))
        assertEquals("退场后角色行数 -1", 3, f.rows)
        assertEquals("删行递增世代", generation + 1, f.roster.rosterGeneration)
        assertEquals("过期世代返回 -1", -1, f.roster.charIndexOfChecked(f.child, generation))
        f.session.shutdown()
    }

    @Test
    fun `resolver接的是真桥活映射 退场后虚拟NO立刻悬空且文案含原值`() {
        val f = Fixture()
        val row = f.roster.project(f.child)
        val vno = checkNotNull(f.session.virtualNoRegistry.virtualNoOf(f.child.raw)) {
            "project 必须给实体分配虚拟 NO"
        }
        f.store.writeInt("CFLAG", longArrayOf(row.toLong(), 0L), 555L)
        assertEquals(
            "投射期内解析命中",
            555L,
            f.store.readInt("CFLAG", longArrayOf(vno.toLong(), 0L)),
        )

        assertTrue("退场必须成功", f.roster.unproject(f.child))
        assertOorWithRawValue(f.store, vno.toLong(), "$vno")
        f.session.shutdown()
    }

    // ------------------------------------------------------------------
    // M5-3：上下场 + SORTCHARA + 活动期变更闸（生产装配序，蓝图 :324 判据）
    // ------------------------------------------------------------------

    /** 无主人公（SORTCHARA 的 fixMaster 语义会把 0 号位钉住；装置不测钉住）。 */
    private fun unpinMaster(store: VariableStore) {
        store.writeInt("MASTER", longArrayOf(0), -1L)
    }

    @Test
    fun `生产装配 五进二出换人_SORTCHARA重投射零串号_活动期闸生产缝钉死`() {
        val f = Fixture()
        // ---- 装配级：活动期变更闸生产缝必接（反向证明 = 摘 attach 里的 bindActivityGate 行）----
        val realBridge = checkNotNull(f.session.charaSlotProjector as? RealCharaSlotProjector) {
            "生产缝的投射桥必须是真实现"
        }
        assertTrue("生产装配点必须恒接活动期变更闸", realBridge.activityGateBound)
        assertTrue(
            "声明事务无关系类型 = 跳过 + FINE 取证；logs=$f.logs",
            f.logs.any { it == "mod.attach relations kind=0 skip(FINE) 纯 Base 旁路保持" },
        )

        // ---- 5 进：行 3..7 尾部追加 ----
        val extras = List(4) { f.probe.context.entities.create(ARCHETYPE) }
        val ids = f.probe.created.toList() + extras
        assertEquals("装置需 5 个扩展实体", 5, ids.size)
        ids.forEachIndexed { i, e -> assertEquals("实体 #$i 追加在第 ${3 + i} 行", 3 + i, f.roster.project(e)) }
        assertEquals("5 进后 8 行", 8, f.rows)
        for (row in 3..7) f.store.writeInt("CFLAG", longArrayOf(row.toLong(), 0L), (row + 100).toLong())

        // ---- 2 出：退场 ids[1]（行 4）与 ids[3]（行 6），行左移收缩 ----
        assertTrue("退场 ids[1] 必须成功", f.roster.unproject(ids[1]))
        assertTrue("退场 ids[3] 必须成功", f.roster.unproject(ids[3]))
        assertEquals("2 出后 6 行", 6, f.rows)

        // ---- SORTCHARA（生产钩子路：store 行重排 → 五接点钩子平移映射）----
        unpinMaster(f.store)
        for (row in 0..2) f.store.writeInt("CFLAG", longArrayOf(row.toLong(), 0L), (30 - row * 10).toLong())
        // 存活投射行键：ids[0]=25（行 3）、ids[2]=35（行 4）、ids[4]=15（行 5）。
        f.store.writeInt("CFLAG", longArrayOf(3L, 0L), 25L)
        f.store.writeInt("CFLAG", longArrayOf(4L, 0L), 35L)
        f.store.writeInt("CFLAG", longArrayOf(5L, 0L), 15L)
        f.store.sortCharactersByInt("CFLAG", longArrayOf(0))
        // 键 [30,20,10,25,35,15] 升序稳定 → 行 0=10(原生角色3)、1=15(ids[4])、2=20(角色2)、
        // 3=25(ids[0])、4=30(角色1)、5=35(ids[2])。
        val afterSort = mapOf(1 to ids[4], 3 to ids[0], 5 to ids[2])
        for ((row, e) in afterSort) {
            assertEquals("SORTCHARA 后实体行号", row, f.roster.charIndexOfChecked(e, f.roster.rosterGeneration))
            assertEquals("双向映射互逆，零串号", e.raw, f.session.charaSlotProjector.entityAt(row))
        }
        for (idx in intArrayOf(1, 3)) {
            assertEquals("退场实体不复活", -1, f.roster.charIndexOfChecked(ids[idx], f.roster.rosterGeneration))
        }

        // ---- 重 project：换人（把 ids[1] 重新登场）零串号 ----
        val newRow = f.roster.project(ids[1])
        assertEquals("重投射追加在末行", 6, newRow)
        val vno = checkNotNull(f.session.virtualNoRegistry.virtualNoOf(ids[1].raw))
        assertEquals("NO 列 = 该实体的虚拟号", vno.toLong(), f.store.readInt("NO", longArrayOf(newRow.toLong())))
        assertEquals("重投射后世代校验命中", newRow, f.roster.charIndexOfChecked(ids[1], f.roster.rosterGeneration))
        assertEquals("重投射后 7 行", 7, f.rows)
        f.session.shutdown()
    }

    @Test
    fun `生产缝活动调用栈内_project被拒_页边界放行`() {
        val f = Fixture()
        // 活动调用栈（变量层帧活动 = 生产 probe 判据，与 VM 执行中的嵌套/事件/输入流程同判据面）：
        f.store.enterFunction("GATEPROBE")
        val blocked = assertThrows(IllegalStateException::class.java) {
            f.roster.project(f.child)
        }
        assertTrue("拒文案含活动调用栈语义：${blocked.message}", blocked.message!!.contains("活动调用栈"))
        assertEquals("拒绝不得建行", 3, f.rows)
        // 页边界（VM 完全让出）：帧离场后放行。
        f.store.leaveFunction()
        assertEquals("页边界放行", 3, f.roster.project(f.child))
        f.session.shutdown()
    }

    @Test
    fun `关系投影_kind恰一_attach_退场回写不干净上日志面`() {
        val f = Fixture(relSchemas = listOf(RelationshipSchema("LIKES")))
        assertTrue(
            "声明事务恰一个关系类型 = attach；logs=$f.logs",
            f.logs.any { it == "mod.attach relations kind=LIKES attached" },
        )
        val realBridge = checkNotNull(f.session.charaSlotProjector as? RealCharaSlotProjector)
        val sync = checkNotNull(realBridge.relationSynchronizerOrNull) { "恰一 kind 必须接线同步器" }

        val e1 = f.probe.context.entities.create(ARCHETYPE)
        val row1 = f.roster.project(e1)
        // 原生互动直改 e1 行 → 原生行 0（无锚实体，列键 = 其 NO=1）的格：退场差分发现
        // 改动，但对端无锚 ⇒ NO_ANCHOR 进 unwritten，isClean 为假（不静默丢）。
        f.store.writeInt("RELATION", longArrayOf(row1.toLong(), 0L), 555L)
        assertTrue("退场必须成功", f.roster.unproject(e1))
        val report = checkNotNull(sync.lastReport) { "退场必须产出回写报告" }
        assertFalse("NO_ANCHOR 改动没写进图 = 不干净", report.isClean)
        assertEquals("只有一条写不进图的改动", 1, report.unwritten.size)
        assertEquals(
            "原因 = 对端原生行无锚实体",
            RelationProjectionSynchronizer.RelationWriteBackReport.Reason.NO_ANCHOR,
            report.unwritten.single().reason,
        )

        // ---- lastReport.isClean 诊断面接日志消费（页边界节拍）----
        f.session.onTurnBoundary()
        assertTrue(
            "页边界必须把不干净的退场回写上日志面；logs=$f.logs",
            f.logs.any { it.startsWith("mod.rt2 relation writeback dirty applied=0 unwritten=1") },
        )
        f.session.onTurnBoundary()
        assertEquals(
            "同一份报告按引用幂等，只报一次",
            1,
            f.logs.count { it.startsWith("mod.rt2 relation writeback dirty") },
        )
        f.session.shutdown()
    }

    @Test
    fun `关系投影_kind多个_跳过FINE取证不拒载`() {
        val f = Fixture(relSchemas = listOf(RelationshipSchema("LIKES"), RelationshipSchema("HATES")))
        assertTrue(
            "多 kind = 跳过 + FINE 取证；logs=$f.logs",
            f.logs.any { it.startsWith("mod.attach relations kinds=2 skip(FINE) 取证=LIKES,HATES") },
        )
        val realBridge = f.session.charaSlotProjector as RealCharaSlotProjector
        assertEquals("多 kind 不接线，纯 Base 旁路保持", null, realBridge.relationSynchronizerOrNull)
        // 不拒载：装载链照常走完，实体系统可用。
        assertEquals("不拒载：投射照常", 3, f.roster.project(f.child))
        f.session.shutdown()
    }
}
