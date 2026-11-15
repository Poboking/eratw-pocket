package com.eratw.port.android

import com.eratw.port.core.entity.RealCharaSlotProjector
import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.mod.ModDeclarationAdapter
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.vars.CharacterTemplate
import com.eratw.port.core.vars.DimSpec
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modapi.ArchetypeDecl
import com.eratw.port.modapi.CharaRouteDecl
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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val PACKAGE_ID: String = "assembly.routes"
private const val ARCHETYPE: String = "PERSON"

/**
 * 用户角色变量（attach 终审读 `def` 的前提）：生产 = 语料装载相位 `#DIM CHARADATA` 同路，
 * 装置经 `declareUserVar` 先声明后 attach。M_MEMO 是 STR（占位 INT 终审修正的靶），
 * M_TINY dims=2（dims 越界终审的靶）。
 */
private val MOOD_SPEC = DimSpec("M_MOOD", isStr = false, dims = intArrayOf(8), isCharaData = true)
private val MEMO_SPEC = DimSpec("M_MEMO", isStr = true, dims = intArrayOf(4), isCharaData = true)
private val TINY_SPEC = DimSpec("M_TINY", isStr = false, dims = intArrayOf(2), isCharaData = true)

private fun manifestWith(routes: List<CharaRouteDecl>): ModManifest = ModManifest(
    packageId = PACKAGE_ID,
    components = listOf(
        ComponentDecl(name = "route", fields = listOf(FieldDecl("hp", FieldType.INT))),
    ),
    archetypes = listOf(ArchetypeDecl(ARCHETYPE, listOf("route"))),
    charaRoutes = routes,
)

/**
 * M5-4 刀 2 的生产装配级用例（CONTRACT-RT2-M54IMPL2 判据 1/2）：与
 * `RosterProjectionAssemblyTest` 同装置形状——一律走生产 [ModRuntimeAssembly.attach] +
 * 生产 `defaultHost`，真磁盘 manifest 带路由 → 真 freeze 派生 → 真 attach 终审 → 真
 * 投射器第五参。没有 fake 自证。
 *
 * 判据映射：
 * - 判据 1（有路由 ⇒ fields 非空）：pour 方向行为证明——内核镜像字段值出现在活行格子上；
 * - 判据 2（attach 终审两类 fail-closed + STR 占位重造）：未注册 / 维度越界整包拒点名 +
 *   STR 变量 pour 方向真值往返（不修正即静默空串，反向证明的靶）；
 * - 判据 1（无路由 ⇒ 空表缺省路径）：路由域空取证一行 + 镜像字段未注册（内核面上无）。
 */
class CharaFieldRouteAssemblyTest {

    /** onLoad 建一个真实体并留 context（与 RosterProjectionAssemblyTest 同形）。 */
    private class ProbeMod : EraMod {
        lateinit var context: ModContext
        val created = mutableListOf<EntityId>()

        override fun onLoad(context: ModContext) {
            this.context = context
            created += context.entities.create(ARCHETYPE)
        }

        override fun onEnable() = Unit
        override fun onDisable() = Unit
        override fun onUnload() = Unit
    }

    private class Fixture(routes: List<CharaRouteDecl>) {
        val logs = mutableListOf<String>()
        val root: Path = Files.createTempDirectory("rt2-m54d2").also {
            Files.createDirectories(it.resolve("mods").resolve("rt2"))
        }
        val store = VariableStore()
        val probe = ProbeMod()
        val session: ModRuntimeSession

        init {
            // 用户变量先声明（生产序：语料装载相位先于会话装配；attach 终审读 def 的前提）。
            store.declareUserVar(MOOD_SPEC)
            store.declareUserVar(MEMO_SPEC)
            store.declareUserVar(TINY_SPEC)
            Files.write(
                root.resolve("mods").resolve("rt2").resolve("assembly_routes.manifest"),
                ManifestCodec.encode(ModDeclarationAdapter.toDescriptor(manifestWith(routes))),
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
                "探针必须真装成功；logs=$logs"
            }
        }

        val roster get() = probe.context.roster
        val entities get() = probe.context.entities
        val child: EntityId get() = probe.created.single()
    }

    @Test
    fun `有路由_构造点第五参非空_pour方向内核镜像字段注水活行`() {
        val f = Fixture(
            routes = listOf(
                CharaRouteDecl("CFLAG", listOf(0, 1)),
                CharaRouteDecl("M_MOOD", listOf(0, 3)),
                CharaRouteDecl("M_MEMO", listOf(1)),
            ),
        )
        // 供给取证：5 条逐格行（CFLAG 2 + M_MOOD 2 + M_MEMO 1），M_MEMO 触发占位修正。
        assertTrue(
            "有路由必须 fields=attached；logs=${f.logs}",
            f.logs.any { it == "mod.attach charaRoutes routes=5 fields=attached mirror=type-corrected" },
        )
        assertTrue(
            "STR 用户变量占位必须以 def 重造行并留痕；logs=${f.logs}",
            f.logs.any {
                it == "mod.attach charaRoutes 类型终审修正 pkg=$PACKAGE_ID field=M_MEMO:1 占位INT→STR（以 def 重造行）"
            },
        )
        assertTrue(
            "生产装配的投射桥必须是真实现",
            f.session.charaSlotProjector is RealCharaSlotProjector,
        )

        // ---- pour 方向：内核镜像字段 → 活行格子（fields 非空的行为证明）----
        assertTrue(f.entities.setInt(f.child, "CFLAG:0", 42L))
        assertTrue(f.entities.setInt(f.child, "CFLAG:1", 7L))
        assertTrue(f.entities.setInt(f.child, "M_MOOD:3", 99L))
        assertTrue(
            "STR 镜像字段必须经终审修正为 STR（未修正 = 内核类型闸拒写）",
            f.entities.setStr(f.child, "M_MEMO:1", "備忘"),
        )
        val row = f.roster.project(f.child)
        assertEquals("ADDVOIDCHARA 语义尾部追加", 3, row)
        assertEquals("CFLAG:0 注水", 42L, f.store.readInt("CFLAG", longArrayOf(row.toLong(), 0L)))
        assertEquals("CFLAG:1 注水", 7L, f.store.readInt("CFLAG", longArrayOf(row.toLong(), 1L)))
        assertEquals("M_MOOD:3 注水", 99L, f.store.readInt("M_MOOD", longArrayOf(row.toLong(), 3L)))
        assertEquals(
            "STR 用户变量 pour 方向必须真值往返（占位未修正 = 静默写空串，判据 2 点名的危害）",
            "備忘",
            f.store.readStr("M_MEMO", longArrayOf(row.toLong(), 1L)),
        )

        // ---- capture 方向：活行格子 → 内核镜像字段（双向镜像；存档脱水同路）----
        f.store.writeInt("CFLAG", longArrayOf(row.toLong(), 0L), 123L)
        f.store.writeStr("M_MEMO", longArrayOf(row.toLong(), 1L), "回写")
        assertTrue("退场必须成功", f.roster.unproject(f.child))
        assertEquals("CFLAG:0 脱水", 123L, f.entities.getInt(f.child, "CFLAG:0"))
        assertEquals("M_MEMO:1 脱水（capture 方向内核类型闸）", "回写", f.entities.getStr(f.child, "M_MEMO:1"))
        f.session.shutdown()
    }

    @Test
    fun `无路由_空表缺省路径_取证一行且镜像字段未注册`() {
        val f = Fixture(routes = emptyList())
        assertTrue(
            "无路由必须 FINE 取证一行；logs=${f.logs}",
            f.logs.any { it == "mod.attach charaRoutes routes=0 skip(FINE) fields 空表缺省路径保持" },
        )
        assertFalse(
            "无路由不得有任何修正或拒收取证",
            f.logs.any {
                it.startsWith("mod.attach charaRoutes 类型终审修正") ||
                    it.startsWith("mod.attach charaRoutes 终审拒收")
            },
        )
        // 缺省路径：内核面上根本没有镜像字段（freeze 未派生镜像 schema），装配照常。
        assertFalse(f.entities.setInt(f.child, "CFLAG:0", 1L))
        assertEquals("空表只建行：投射照常", 3, f.roster.project(f.child))
        f.session.shutdown()
    }

    @Test
    fun `attach终审_变量未注册_整包拒点名`() {
        val e = assertThrows(IllegalStateException::class.java) {
            Fixture(routes = listOf(CharaRouteDecl("M_GHOST", listOf(0))))
        }
        assertTrue(
            "整包拒文案必须点名变量：${e.message}",
            e.message!!.contains("M_GHOST"),
        )
    }

    @Test
    fun `attach终审_非角色变量_整包拒`() {
        // DAY 是全局 1 维整数（非角色变量）：freeze 侧显式格放行（family=null 无尺寸可查），
        // 角色性在此（attach 两段式第二段）整包拒。
        val e = assertThrows(IllegalStateException::class.java) {
            Fixture(routes = listOf(CharaRouteDecl("DAY", listOf(0))))
        }
        assertTrue(
            "整包拒文案必须点名非角色性：${e.message}",
            e.message!!.contains("不是角色变量"),
        )
    }

    @Test
    fun `attach终审_dims越界_整包拒点名`() {
        // M_TINY dims=2：freeze 对用户变量显式格不查尺寸（运行期才存在），格 5 在此终审拒。
        val e = assertThrows(IllegalStateException::class.java) {
            Fixture(routes = listOf(CharaRouteDecl("M_TINY", listOf(5))))
        }
        assertTrue(
            "整包拒文案必须点名越界格与运行期尺寸：${e.message}",
            e.message!!.contains("格 5") && e.message!!.contains("2"),
        )
    }
}
