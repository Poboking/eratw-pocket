package com.eratw.port.android

import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.mod.ModRuntimeHost
import com.eratw.port.core.mod.ModStorageStore
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.ModContext
import java.lang.IllegalAccessException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * task-106：把「[ModContext] 作者面表 ↔ 源码/编译产物」冻结成**双向差集闸**，并给出
 * 「装配漏注入 ⇒ 面变 FAIL_CLOSED」的反向证明。
 *
 * 闸的语义：
 * 1. `ModContext.kt` 新增 `val` 属性而 [ModContextFaceAuditFixture.AUTHOR_FACES] 没跟上 ⇒
 *    [faceDiff] 非空 ⇒ 红（新增作者面必须同步进表并补真装配探针）；
 * 2. 表里多写一个源码不存在的面 ⇒ 同样红；
 * 3. 真装配下 24 个 getter 全部可调（零 `UnsupportedOperationException`）是本审计的结论；
 * 4. 反向证明：把 `newHost` 换成缺 settings/resources/audio/timing/decisionPipeline 注入的
 *    裸宿主后，恰好这五面变 FAIL_CLOSED，而 strings/services 仍 WIRED——证明探针能抓住
 *    「attach 少写一行注入」的回归。
 */
class ModContextFaceGateTest {

    private val faces: Set<String> = ModContextFaceAuditFixture.AUTHOR_FACES.toSet()

    @Test
    fun `表与真源 ModContext_kt 双向差集为空`() {
        val source = ModContextFaceAuditFixture.sourceFaceNames()
        assertEquals("ModContext.kt 真源属性数", ModContextFaceAuditFixture.FACE_COUNT, source.size)
        assertEquals(
            "源码新增作者面必须同步进 AUTHOR_FACES 并补真装配探针",
            emptySet<String>(),
            faceDiff(source),
        )
    }

    @Test
    fun `表与编译产物 ModContext 接口 getter 双向差集为空`() {
        val compiled = ModContextFaceAuditFixture.compiledFaceNames()
        assertEquals("编译产物 getter 数", ModContextFaceAuditFixture.FACE_COUNT, compiled.size)
        assertEquals("表 ↔ 编译接口双向差集必须为空", emptySet<String>(), faceDiff(compiled))
    }

    @Test
    fun `真装配下 24 个作者面 getter 全部 WIRED 零 FAIL_CLOSED`() {
        val root = ModContextFaceAuditFixture.tempRoot()
        val broken = mutableMapOf<String, String>()
        val probe = ModContextFaceAuditFixture.probe(root) { context ->
            assertEquals("getter 数必须等于冻结表", ModContextFaceAuditFixture.FACE_COUNT, faceGetters().size)
            for (getter in faceGetters()) {
                try {
                    getter.invoke(context)
                } catch (failure: InvocationTargetException) {
                    broken[getter.name] = failure.cause?.toString().orEmpty()
                } catch (failure: IllegalAccessException) {
                    broken[getter.name] = failure.toString()
                }
            }
            assertTrue(
                "真装配下作者面不得抛（UOE=FAIL_CLOSED，即装配没接线）：$broken",
                broken.isEmpty(),
            )
        }
        assertNull("getter 探针自身不得抛：${probe.failure}", probe.failure)
        assertTrue("探针所在包不得装载失败", probe.loaded)
    }

    @Test
    fun `反向证明 裸宿主缺注入时 设置 资源 音频 耗时 行动 五面 FAIL_CLOSED`() {
        val root = ModContextFaceAuditFixture.tempRoot()
        var inputs: AssemblyInputs? = null
        val session = ModRuntimeAssembly.attach(
            gameRoot = root.toFile(),
            store = VariableStore(),
            nativeRegistry = NativeMethodRegistry(),
            onVmThread = { true },
            enabled = true,
            logSink = { },
            modProviders = emptyList(),
            newHost = { captured ->
                inputs = captured
                realLikeHost(captured)
            },
        )
        assertNotNull("探针夹具下真装配必须产出会话", session)
        session?.shutdown()
        val captured = inputs ?: error("attach 未调用 newHost")
        val closed = mutableMapOf<String, String>()
        var stringsWired = false
        var servicesWired = false
        val bare = bareHost(captured)
        bare.install(
            listOf(
                ModContextFaceAuditFixture.PKG to object : EraMod {
                    override fun onLoad(context: ModContext) {
                        recordClosed(closed, BARE_CLOSED_TEXT) { context.settings.keys }
                        recordClosed(closed, BARE_CLOSED_RESOURCES) { context.resources.keys() }
                        recordClosed(closed, BARE_CLOSED_AUDIO) { context.audio.stop() }
                        recordClosed(closed, BARE_CLOSED_TIMING) { context.timing.samples() }
                        recordClosed(closed, BARE_CLOSED_ACTIONS) {
                            context.actions.provide("audit.domain") { emptyList() }
                        }
                        stringsWired = runCatching { context.strings.language() }.isSuccess
                        servicesWired = runCatching { context.services.publishedTypes() }.isSuccess
                    }

                    override fun onEnable() = Unit

                    override fun onDisable() = Unit

                    override fun onUnload() = Unit
                },
            ),
        )
        assertEquals("裸宿主下应恰好这五面 FAIL_CLOSED（UOE）：$closed", BARE_CLOSED_FACES, closed.keys)
        assertTrue("strings 由缺省 registry 装配，裸宿主下仍应 WIRED", stringsWired)
        assertTrue("services 由缺省 registry 装配，裸宿主下仍应 WIRED", servicesWired)
    }

    @Test
    fun `反向证明 表漏掉一个作者面会被双向差集闸抓住`() {
        val missingAudio = faces - "audio"
        val diff = faceDiff(missingAudio)
        assertTrue("删掉 audio 后双向差集必须非空", diff.isNotEmpty())
        assertTrue("差集必须精确指向 audio：$diff", "audio" in diff)
        assertFalse("删掉 audio 后其余面不得误报", diff.size > 1)
    }

    private fun faceDiff(declared: Set<String>): Set<String> = (faces - declared) + (declared - faces)

    private fun faceGetters(): List<Method> = ModContext::class.java.declaredMethods
        .filter { method -> method.parameterCount == 0 && method.name.startsWith("get") }
        .toList()

    /** 与生产 `defaultHost` 同形（含全部注入），仅用于让 attach 正常出会话并捕获 [AssemblyInputs]。 */
    private fun realLikeHost(inputs: AssemblyInputs): ModRuntimeHost = ModRuntimeHost(
        snapshot = inputs.snapshot,
        entityKernel = inputs.entityKernel,
        nativeMethods = inputs.nativeMethods,
        logSink = inputs.logSink,
        runtime = inputs.runtime,
        shortIdOf = inputs.shortIdOf,
        intVarView = inputs.intVarView,
        strVarView = inputs.strVarView,
        hasIntVarView = inputs.hasIntVarView,
        hasStrVarView = inputs.hasStrVarView,
        decisionPipeline = inputs.decisionPipeline,
        storage = inputs.storage,
        projector = inputs.projector,
    )

    /** 裸宿主 = 生产 attach 里 :423 settings / :430 resources / :437 audioSink / :445 timing / :365 决策管线五处注入全缺。 */
    private fun bareHost(inputs: AssemblyInputs): ModRuntimeHost = ModRuntimeHost(
        snapshot = inputs.snapshot,
        entityKernel = inputs.entityKernel,
        nativeMethods = inputs.nativeMethods,
        logSink = inputs.logSink,
        runtime = inputs.runtime,
        shortIdOf = inputs.shortIdOf,
        intVarView = inputs.intVarView,
        strVarView = inputs.strVarView,
        hasIntVarView = inputs.hasIntVarView,
        hasStrVarView = inputs.hasStrVarView,
        storage = ModStorageStore(),
        projector = inputs.projector,
    )

    private fun recordClosed(closed: MutableMap<String, String>, face: String, call: () -> Unit) {
        try {
            call()
        } catch (failure: UnsupportedOperationException) {
            closed[face] = failure.message.orEmpty()
        }
    }

    private companion object {
        private const val BARE_CLOSED_TEXT = "settings"
        private const val BARE_CLOSED_RESOURCES = "resources"
        private const val BARE_CLOSED_AUDIO = "audio"
        private const val BARE_CLOSED_TIMING = "timing"
        private const val BARE_CLOSED_ACTIONS = "actions"
        private val BARE_CLOSED_FACES: Set<String> = setOf(
            BARE_CLOSED_TEXT,
            BARE_CLOSED_RESOURCES,
            BARE_CLOSED_AUDIO,
            BARE_CLOSED_TIMING,
            BARE_CLOSED_ACTIONS,
        )
    }
}
