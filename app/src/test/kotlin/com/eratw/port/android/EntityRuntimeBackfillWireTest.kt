package com.eratw.port.android

import com.eratw.port.core.entity.RelationshipSchema
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.fn.BuiltinEvalContext
import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.fn.entityRuntimeOf
import com.eratw.port.core.mod.ModDeclarationAdapter
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.proc.ConsoleBuiltinHost
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modapi.ArchetypeDecl
import com.eratw.port.modapi.ComponentDecl
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.FieldDecl
import com.eratw.port.modapi.FieldType
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private const val WIRE_PACKAGE_ID: String = "entrt.wire"
private const val WIRE_ARCH_PERSON: String = "PERSON"
private const val WIRE_ARCH_PET: String = "PET"

private val WIRE_MANIFEST: ModManifest = ModManifest(
    packageId = WIRE_PACKAGE_ID,
    components = listOf(
        ComponentDecl(name = "roster", fields = listOf(FieldDecl("hp", FieldType.INT))),
    ),
    archetypes = listOf(
        ArchetypeDecl(WIRE_ARCH_PERSON, listOf("roster")),
        ArchetypeDecl(WIRE_ARCH_PET, listOf("roster")),
    ),
)

/** 全链测试的最小求值语境基座（与 EntityMethodsTest 的 EmBaseCtx 同形）。 */
private class WireBaseCtx : EvalContext {
    override lateinit var variables: VariableReader
    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("WireBaseCtx 不应被直接调用：$name")
    override fun printSystemLine(message: String) = Unit
}

/** 值调用：Long/Int → IntV，String → StrV。 */
private fun wireCall(ctx: BuiltinEvalContext, name: String, vararg args: Any?): Value =
    ctx.callFunction(
        name,
        args.map {
            when (it) {
                is Long -> Value.IntV(it)
                is Int -> Value.IntV(it.toLong())
                is String -> Value.StrV(it)
                is Value -> it
                else -> throw IllegalArgumentException("$it")
            }
        },
    )

private fun wireIv(v: Value): Long = (v as Value.IntV).v

/**
 * W1-ENTRT 生产接线全链（CONTRACT-RT2-ENTRT 判据 1/2/5/6，真引擎纵向切片，
 * 装置形态照 RosterProjectionAssemblyTest：生产 `ModRuntimeAssembly.attach` +
 * 生产 `defaultHost` + 磁盘 manifest，没有 fake 自证）。
 *
 * 判据映射：
 * - 判据 1（开态回填）：attach 完成后生产宿主 [ConsoleBuiltinHost.entityRuntime] 与
 *   求值语境（AuxMacroHost 装饰器）都拿到同一份真装配，ENTITY_* 的 CodeEE 组
 *   （CREATE/SET_INT/GET_INT/ADD_REL/HAS_REL/QUERY_COUNT）改走真内核；
 * - 判据 2（时序边界）：宿主先于 attach 构造 ⇒ 回填前读数 null；旁路组按装配如实
 *   变化（MOD_IS_ACTIVE 真值、ENTITY_TO_CHARA 无投射 -1）；
 * - 判据 5（关态零变化）：enabled=false ⇒ 会话 null、槽缺席、未装配 CodeEE 文案逐位不变；
 * - 判据 6（真内核行为）：实体/关系数据落真 EntityKernel（真 freeze 声明快照）。
 */
class EntityRuntimeBackfillWireTest {

    /** 开态装置：真 manifest → 真 freeze → 真 attach → 真宿主 + 真内核。 */
    private class OpenFixture {
        val logs = mutableListOf<String>()
        val root: Path = Files.createTempDirectory("rt2-entrt-wire").also {
            Files.createDirectories(it.resolve("mods").resolve("rt2"))
        }
        val store = VariableStore()
        val probe = WireProbeMod()

        /** 生产宿主在 attach **之前**构造（复刻 GameSession 装配序），供时序边界断言。 */
        val consoleHost = ConsoleBuiltinHost(root, audioStore = store)
        val session: ModRuntimeSession

        init {
            assertNull("判据 2：attach 前（时序边界）宿主读数必须是 null", consoleHost.entityRuntime)
            Files.write(
                root.resolve("mods").resolve("rt2").resolve("entrt_wire.manifest"),
                ManifestCodec.encode(
                    ModDeclarationAdapter.toDescriptor(WIRE_MANIFEST)
                        .copy(relationshipSchemas = listOf(RelationshipSchema("PARENT"))),
                ),
            )
            session = checkNotNull(
                ModRuntimeAssembly.attach(
                    gameRoot = root.toFile(),
                    store = store,
                    nativeRegistry = NativeMethodRegistry(),
                    onVmThread = { true },
                    enabled = true,
                    logSink = { line -> logs += line },
                    modProviders = listOf(WIRE_PACKAGE_ID to probe),
                ),
            ) { "开态生产缝必须产出运行时；logs=$logs" }
            assertNotNull(
                "判据 1：attach 完成后生产宿主的 entityRuntime 必须是真装配",
                consoleHost.entityRuntime,
            )
            assertTrue(
                "宿主读数与槽真源必须同一实例（同一份装配产物，不出现第二套读数）",
                entityRuntimeOf(store.aux) === consoleHost.entityRuntime,
            )
            assertTrue(
                "回填必须留一行取证日志；logs=$logs",
                logs.any { it.startsWith("mod.attach entityRuntime") },
            )
        }

        /** 经生产宿主的求值语境（AuxMacroHost 装饰链同生产形态）。 */
        fun ctx(): BuiltinEvalContext {
            val base = WireBaseCtx()
            base.variables = store
            return BuiltinEvalContext(base, store, consoleHost)
        }
    }

    /** 探针 mod：只为让载体包走完 install（allocate 短 ID），语义由 ENTITY 函数面自证。 */
    private class WireProbeMod : EraMod {
        lateinit var context: ModContext
        override fun onLoad(context: ModContext) {
            this.context = context
        }
        override fun onEnable() = Unit
        override fun onDisable() = Unit
        override fun onUnload() = Unit
    }

    @Test
    fun `开态回填后ENTITY族改走真内核`() {
        val f = OpenFixture()
        val ctx = f.ctx()

        // ---- 判据 1：CodeEE 组改走真内核（CREATE / SET_INT / GET_INT 往返）----
        val person = wireIv(wireCall(ctx, "ENTITY_CREATE", WIRE_ARCH_PERSON))
        val pet = wireIv(wireCall(ctx, "ENTITY_CREATE", WIRE_ARCH_PET))
        assertTrue("真内核建实体必须成功", person > 0)
        assertTrue("真内核建实体必须成功", pet > 0)
        assertEquals("SET_INT 真写组件列", 1L, wireIv(wireCall(ctx, "ENTITY_SET_INT", person, "hp", 42)))
        assertEquals("GET_INT 同一内核读回", 42L, wireIv(wireCall(ctx, "ENTITY_GET_INT", person, "hp")))
        assertEquals("EXISTS 判活", 1L, wireIv(wireCall(ctx, "ENTITY_EXISTS", person)))

        // ---- 判据 6：QUERY_COUNT 原型过滤（两原型各一只，各自只数 1）----
        assertEquals("原型过滤 PERSON", 1L, wireIv(wireCall(ctx, "ENTITY_QUERY_COUNT", WIRE_ARCH_PERSON, 0)))
        assertEquals("原型过滤 PET", 1L, wireIv(wireCall(ctx, "ENTITY_QUERY_COUNT", WIRE_ARCH_PET, 0)))

        // ---- 判据 6：关系面（ADD_REL / HAS_REL，有向正向；无向反向问询在
        // EntityRuntimeSlotTest 的直建内核装置覆盖——投影同步器要求恰一个 kind 有向，
        // 故本 manifest 装置只能声明有向边）----
        assertEquals("声明 kind 落真内核", 1L, wireIv(wireCall(ctx, "ENTITY_ADD_REL", person, "PARENT", pet)))
        assertEquals("有向正向问询命中", 1L, wireIv(wireCall(ctx, "ENTITY_HAS_REL", person, "PARENT", pet)))

        // ---- 判据 2：旁路组随装配如实变化 ----
        assertEquals("MOD_IS_ACTIVE 真激活态", 1L, wireIv(wireCall(ctx, "MOD_IS_ACTIVE", WIRE_PACKAGE_ID)))
        assertEquals("未链接包如实 0", 0L, wireIv(wireCall(ctx, "MOD_IS_ACTIVE", "no.such.pkg")))
        assertEquals("真桥存在但未投射 ⇒ -1", -1L, wireIv(wireCall(ctx, "ENTITY_TO_CHARA", person)))
        assertEquals("原生行无投射实体 ⇒ NULL", 0L, wireIv(wireCall(ctx, "CHARA_TO_ENTITY", 0)))
        assertEquals("catalog 未注入（链接产物在禁触面）⇒ 旁路 0", 0L, wireIv(wireCall(ctx, "HAS_SUPER")))
    }

    @Test
    fun `关态零变化_槽缺席_未装配语义逐位不变`() {
        val logs = mutableListOf<String>()
        val root: Path = Files.createTempDirectory("rt2-entrt-off")
        val store = VariableStore()
        val consoleHost = ConsoleBuiltinHost(root, audioStore = store)

        val session = ModRuntimeAssembly.attach(
            gameRoot = root.toFile(),
            store = store,
            nativeRegistry = NativeMethodRegistry(),
            onVmThread = { true },
            enabled = false,
            logSink = { line -> logs += line },
        )
        assertNull("判据 5：关态总闸短路，会话必须为 null", session)
        assertNull("判据 5：关态槽缺席，宿主读数必须保持 null", consoleHost.entityRuntime)
        assertTrue("关态零对象零日志（总闸第一查短路）", logs.none { it.startsWith("mod.attach") })

        val base = WireBaseCtx()
        base.variables = store
        val ctx = BuiltinEvalContext(base, store, consoleHost)
        try {
            wireCall(ctx, "ENTITY_CREATE", WIRE_ARCH_PERSON)
            fail("关态 ENTITY_CREATE 必须抛未装配")
        } catch (expected: CodeEE) {
            assertEquals(
                "判据 2：未装配 CodeEE 文案逐位不变（EntityMethodsTest:275-309 同一口径）",
                "函数 ENTITY_CREATE：实体运行时未装配",
                expected.message,
            )
        }
        assertEquals("旁路组钉值：MOD_IS_ACTIVE ⇒ 0", 0L, wireIv(wireCall(ctx, "MOD_IS_ACTIVE", WIRE_PACKAGE_ID)))
        assertEquals("旁路组钉值：ENTITY_TO_CHARA ⇒ -1", -1L, wireIv(wireCall(ctx, "ENTITY_TO_CHARA", 1)))
    }

    @Test
    fun `槽真源与会话装配同源`() {
        val f = OpenFixture()
        // 读缝访问器本身就是契约面：填进槽的实例必须能经 entityRuntimeOf 读回，
        // 且 EntityRuntime 接口字段逐项非空（kernel/projector/registry/archetypes/modIsActive）。
        val rt = checkNotNull(entityRuntimeOf(f.store.aux)) { "开态槽必须已回填" }
        assertNotNull("判据 1：kernel = attach 产物", rt.kernel)
        assertNotNull("判据 1：projector = 真投射桥（RealCharaSlotProjector）", rt.projector)
        assertTrue(
            "投射桥必须与 session 持有的是同一实例",
            f.session.charaSlotProjector === rt.projector,
        )
        val declared = rt.archetypes(WIRE_ARCH_PERSON)
        assertNotNull("原型查表按 NamePolicy 归一命中（ERB 原文名直查）", declared)
        assertEquals("归属包 = 声明包", WIRE_PACKAGE_ID, declared?.packageId)
        assertEquals("modIsActive 真值", 1L, wireIv(wireCall(f.ctx(), "MOD_IS_ACTIVE", WIRE_PACKAGE_ID)))
    }
}
