package com.eratw.port.android

import com.eratw.port.core.code.SymbolCatalog
import com.eratw.port.core.mod.FunctionHookWiring
import com.eratw.port.core.mod.ModAudioWiring
import com.eratw.port.core.mod.ModContextImpl
import com.eratw.port.core.mod.ModEventWiring
import com.eratw.port.core.vars.ModCsvLoadContext
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.EventBucket
import com.eratw.port.modapi.EventResult
import com.eratw.port.modapi.ModContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Lane（task-89）收口清点闸：`mod.` 前缀 aux 槽的 teardown inventory。
 *
 * ## 为什么是常驻闸
 * 「新加一个 mod 面就忘一次收口」是系统性问题（CL 波 `retiredSlots`、CO 波 `mod.events.trigger`）。
 * 本闸把「源码里真实存在的槽」与「收口结论」硬绑定，而不是手写一份会腐烂的清单：
 * - **真源枚举**：扫全 main 源集的 `const val … = "mod.…"` 定义，且该常量**真被当 aux 键**
 *   传给 `store.aux.slot/peek`（`mod.` 命名空间里还住着 SharedPreferences 键，如
 *   `UiPrefs.KEY_MOD_DEX_LOADER = "mod.dex.loader"`，那不是槽）；
 * - **断言表**：收口后必须「已清 / 不可达」的槽；
 * - **ALLOWLIST**：无删除 API 等清晰口，每条写理由 + 源码 `文件:行号` 证据；
 * - **反向闸**：源码新增槽而两表都没登记 ⇒ 红；两表里的陈旧登记（源码已无）同样红；
 * - **真装配**：真会话跑 `shutdown()`，逐槽 + `ModRuntimeHost` 读出面断言可观测状态。
 *
 * ## ALLOWLIST 契约
 * 进 ALLOWLIST 的槽判据只有两种，且必须能指到源码行：① 无删除 API（`RuntimeAux.slot`
 * create-if-absent），随会话 store 整体丢弃；② 有出口但生产不可达。登记理由一旦过期
 * （比如缺口接线落地）用例就会红 —— 本闸初版把 `mod.events.trigger` 登记为「引擎缺口」，
 * 组长随后把收口接进 `ModEventWiring.uninstall`（`detachModTriggerTarget`），本版据此把该槽
 * 移入断言表并断言「target 已摘 + 公共面与 raw 面都 fail-closed」。
 *
 * 语料无关：夹具只写 mods 容器 + erbc 签名段，不读游戏语料。
 */
class ModSessionTeardownInventoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `a_真源枚举_命中登记表六槽且无日志前缀假阳性`() {
        val scanned = scanRealMainAuxSlots()
        assertEquals(
            "源码里 mod. 前缀 aux 槽与登记表不一致（新增槽未登记 / 陈旧登记都在此红）",
            EXPECTED_SLOTS.sorted(),
            scanned.keys.sorted(),
        )
        assertFalse(
            "mod.install pkg= 这类日志前缀不是 aux 槽：值必须整串匹配 mod.<小写分词>",
            scanned.keys.any { it.contains(' ') },
        )
        for ((slot, evidence) in scanned) {
            assertTrue("$slot 必须有 const 定义位置证据，实际=$evidence", evidence.isNotEmpty())
        }
    }

    @Test
    fun `b_反向闸_合成源码新增槽未登记即红_陈旧登记也红`() {
        val synthetic = mapOf(
            CORE_MOD_DIR + "SyntheticWiring.kt" to
                "object SyntheticWiring {\n    const val AUX_SLOT: String = \"mod.brand.new\"\n" +
                "\n    fun read(aux: RuntimeAux) = aux.peek<Any>(AUX_SLOT)\n}\n",
            CORE_MOD_DIR + "LogPrefix.kt" to
                "object LogPrefix {\n    const val INSTALL_PREFIX = \"mod.install pkg=\"\n}\n",
        )
        val scanned = enumerateModAuxSlots(synthetic)
        assertEquals(
            "合成源码只该枚举出新增槽（日志前缀含空格，必须被正则排除）",
            setOf("mod.brand.new"),
            scanned.keys,
        )
        assertEquals(
            "新增槽没进断言表/ALLOWLIST ⇒ 差集非空（这就是反向闸的正控）",
            listOf("mod.brand.new"),
            unregisteredSourceSlots(scanned.keys),
        )
        assertEquals(
            "源码里已消失的登记项同样要报（防登记表腐烂）",
            (EXPECTED_SLOTS - scanned.keys).sorted(),
            missingSourceSlots(scanned.keys).sorted(),
        )
    }

    @Test
    fun `c_源码证据_shutdown_只解绑三接线且不触_ALLOWLIST_槽`() {
        val assembly = realMainSource("app/src/main/kotlin/com/eratw/port/android/ModRuntimeAssembly.kt")
        val start = assembly.indexOf("fun shutdown(")
        val end = assembly.indexOf("mod.shutdown 完成 mods=")
        assertTrue("shutdown 段定位失败（start=$start end=$end）", start in 0 until end)
        val teardown = assembly.substring(start, end)
        for (call in listOf(
            "FunctionHookWiring.uninstall(store, host.hookPipeline)",
            "ModEventWiring.uninstall(store, host.eventHandlers)",
            "ModAudioWiring.uninstall(store)",
        )) {
            assertTrue("断言表槽的收口调用必须真在 shutdown 段里：$call", teardown.contains(call))
        }
        // 断言表里「经 ModEventWiring.uninstall 间接收口」的 trigger 槽：收口点在 core，
        // shutdown 段只该出现 ModEventWiring（不该出现 ModEventTriggerWiring/符号名）。
        assertTrue(
            "trigger 槽的收口应经 ModEventWiring.uninstall 间接完成（shutdown 段不该直接点名它）",
            !teardown.contains("ModEventTriggerWiring"),
        )
        for (symbol in listOf("ModCsvLoadContext", "MOD_LINK_CATALOG_SLOT")) {
            assertFalse(
                "ALLOWLIST 槽 $symbol 不该在 shutdown 段出现（若出现 = 登记理由过期，应移入断言表）",
                teardown.contains(symbol),
            )
        }
    }

    @Test
    fun `d_源码证据_三个_wiring_uninstall_真的置空引用`() {
        assertTrue(
            "FunctionHookWiring：uninstall 必须置 pipeline=null（含判实例同一性）",
            realMainSource(CORE_MOD_DIR + "FunctionHookWiring.kt")
                .contains("if (handle.pipeline === pipeline) handle.pipeline = null"),
        )
        val eventWiring = realMainSource(CORE_MOD_DIR + "ModEventWiring.kt")
        assertTrue(
            "ModEventWiring：uninstall 必须置 registry=null",
            eventWiring.contains("handle.registry = null"),
        )
        assertTrue(
            "ModEventWiring：uninstall 必须撤销触发桥（否则公共面 trigger 不再 fail-closed）",
            eventWiring.contains("registry.adoptTriggerBridge(null)"),
        )
        val audioWiring = realMainSource(CORE_MOD_DIR + "ModAudioWiring.kt")
        assertTrue("ModAudioWiring：uninstall 必须置 sink=null", audioWiring.contains("handle.sink = null"))
        assertTrue(
            "ModAudioWiring：uninstall 必须置 resources=null",
            audioWiring.contains("handle.resources = null"),
        )
        assertTrue(
            "ModEventWiring：uninstall 必须间接收口主动触发槽（detachModTriggerTarget）",
            eventWiring.contains("detachModTriggerTarget(store)"),
        )
        val triggerWiring = realMainSource(CORE_MOD_DIR + "ModEventTriggerWiring.kt")
        assertTrue(
            "ModEventTriggerWiring：文件级收口入口必须存在（object 成员额度已满，故放文件级）",
            triggerWiring.contains("internal fun detachModTriggerTarget(store: VariableStore)"),
        )
        assertTrue(
            "ModEventTriggerWiring：收口入口必须真摘 target",
            triggerWiring.contains("handle.target = null"),
        )
        assertTrue(
            "ModEventTriggerWiring：收口入口必须清延迟队列（跨会话补派不可能）",
            triggerWiring.contains("handle.deferred.clear()"),
        )
    }

    @Test
    fun `e_真装配_shutdown_后断言表槽全部解绑`() {
        val root = tmp.newFolder("teardown-slots")
        writeChainFixtures(root)
        val out = outcome(root)
        val game = session(root, out.plan)
        try {
            out.installCatalogSlot(game.store)
            val logs = mutableListOf<String>()
            val hits = mutableListOf<String>()
            val modSession = checkNotNull(
                attach(root, game.store, logs, listOf(GOOD_PKG to teardownProbeMod(hits)), out.catalog),
            ) { "开态 attach 必须产出运行时；logs=$logs" }
            val store = game.store
            assertNotNull("对照：收口前 hook 接线已装", auxField(store, FUNCTIONHOOKS_SLOT, "pipeline"))
            assertTrue("对照：收口前事件注册表有处理器", ModEventWiring.hasHandlers(store, GOOD_EVENT))
            assertNotNull("对照：收口前音频汇已装（bindAudioSink 在 attach 内）", ModAudioWiring.sinkOf(store))

            modSession.shutdown()

            assertNull(
                "mod.functionhooks：按实例同一性置 Handle.pipeline=null",
                auxField(store, FUNCTIONHOOKS_SLOT, "pipeline"),
            )
            assertNull(
                "mod.events.handlers：置 Handle.registry=null",
                auxField(store, EVENTS_HANDLERS_SLOT, "registry"),
            )
            assertFalse("mod.events.handlers：公共查询 hasHandlers=false", ModEventWiring.hasHandlers(store, GOOD_EVENT))
            assertNull("mod.audio.sink：sink 已解绑", ModAudioWiring.sinkOf(store))
            assertNull("mod.audio.sink：resources 已解绑", auxField(store, AUDIO_SINK_SLOT, "resources"))
            assertFalse(
                "mod.audio.sink：解绑后晚到 play 显式 false（不静默吞、不 NPE）",
                ModAudioWiring.play(store, GOOD_PKG, "late.ogg", false, FULL_VOLUME),
            )
            assertTrue("探针在收口后不该再收到派发", hits.isEmpty())
        } finally {
            game.close()
        }
    }

    @Test
    fun `f_真装配_trigger_槽已解绑_公共面与_raw_面都_fail_closed`() {
        val root = tmp.newFolder("teardown-trigger")
        writeChainFixtures(root)
        val out = outcome(root)
        val game = session(root, out.plan)
        try {
            out.installCatalogSlot(game.store)
            val logs = mutableListOf<String>()
            val hits = mutableListOf<String>()
            val modSession = checkNotNull(
                attach(root, game.store, logs, listOf(GOOD_PKG to teardownProbeMod(hits)), out.catalog),
            ) { "开态 attach 必须产出运行时；logs=$logs" }
            val store = game.store
            assertNotNull("对照：收口前 trigger 槽 target 在场", triggerTargetOf(store))

            modSession.shutdown()

            assertNull(
                "mod.events.trigger：target 已摘（收口经 ModEventWiring.uninstall → detachModTriggerTarget，" +
                    "四槽收口对称；若回归成残留，本断言红）",
                triggerTargetOf(store),
            )
            val context = modSession.host.contextOf(GOOD_PKG) as ModContextImpl
            var closed: UnsupportedOperationException? = null
            try {
                context.events.trigger(GOOD_EVENT)
            } catch (e: UnsupportedOperationException) {
                closed = e
            }
            assertNotNull("公共面触发桥已撤销 ⇒ fail-closed（UOE）", closed)
            assertTrue("公共面不得再派发进已收口会话（探针无日志）", hits.isEmpty())
        } finally {
            game.close()
        }
    }

    @Test
    fun `g_真装配_shutdown_后_host_读出面逐项出闸`() {
        val root = tmp.newFolder("teardown-host")
        writeChainFixtures(root)
        val out = outcome(root)
        val game = session(root, out.plan)
        try {
            out.installCatalogSlot(game.store)
            val logs = mutableListOf<String>()
            val hits = mutableListOf<String>()
            val modSession = checkNotNull(
                attach(root, game.store, logs, listOf(GOOD_PKG to teardownProbeMod(hits)), out.catalog),
            ) { "开态 attach 必须产出运行时；logs=$logs" }
            val host = modSession.host
            assertTrue("对照：收口前 services 有发布", host.services.publishedTypes().isNotEmpty())
            assertFalse("对照：收口前 strings 非空", host.strings.isEmpty())

            modSession.shutdown()

            assertTrue("services：按包 revoke 后无任何已发布类型", host.services.publishedTypes().isEmpty())
            assertNull("services：按接口类型查找 ⇒ null", host.services.find(String::class.java))
            assertTrue("services：owner 表已清空", host.services.owners(String::class.java).isEmpty())
            assertTrue("strings：按包 clear 后整体空", host.strings.isEmpty())
            assertEquals("strings：包计数归零", 0, host.strings.packageCount())
            assertFalse("strings：已声明键不可再解析（has=false）", host.strings.has(GOOD_PKG, PROBE_STRING_KEY))
            assertFalse("eventHandlers：收口后 hasHandlers=false", host.eventHandlers.hasHandlers(GOOD_EVENT))
            assertFalse("hookPipeline：收口后无 hook 槽", host.hookPipeline.hasHooks(GOOD_EVENT))
            assertEquals("hookPipeline：retired 槽计数归零（CL 修复口径）", 0, host.hookPipeline.retiredSlotCount)
        } finally {
            game.close()
        }
    }

    @Test
    fun `h_真装配_收口后_会话自有读出面保留_且_csv_catalog_按_ALLOWLIST`() {
        val root = tmp.newFolder("teardown-session")
        writeChainFixtures(root)
        val out = outcome(root)
        val game = session(root, out.plan)
        try {
            out.installCatalogSlot(game.store)
            val logs = mutableListOf<String>()
            val hits = mutableListOf<String>()
            val modSession = checkNotNull(
                attach(root, game.store, logs, listOf(GOOD_PKG to teardownProbeMod(hits)), out.catalog),
            ) { "开态 attach 必须产出运行时；logs=$logs" }
            val store = game.store

            modSession.shutdown()

            assertSame(
                "catalog 槽：无删除 API，收口不清（随会话 store 整体丢弃）",
                out.catalog,
                store.aux.peek<SymbolCatalog>(MOD_LINK_CATALOG_SLOT),
            )
            assertNull(
                "csv.load 槽：本夹具未跑装载相位（attach 回落自装载），无删除 API",
                store.aux.peek<Any>(ModCsvLoadContext.AUX_SLOT),
            )
            val host = modSession.host
            assertNotNull("timing：会话自有账本只上报不重置（随会话丢弃）", host.timing)
            assertNotNull("hookPipeline.timing：解绑只置 pipeline 字段，账本引用保留", host.hookPipeline.timing)
            assertNotNull("resources：会话自持资源存根不用清（随会话丢弃）", host.resources)
            assertNotNull("audioSink：host 字段≠槽，收口只解绑槽（生产播放已 false）", host.audioSink)
            assertTrue("installedContexts：host 不增删条目表，contexts 随会话整体丢弃", host.installedContexts.isNotEmpty())
        } finally {
            game.close()
        }
    }

    /** 在 onLoad 里同时占用三条已接线通道（事件 / services / strings）的探针 mod。 */
    private class TeardownProbeMod(private val hits: MutableList<String>) : EraMod {
        override fun onLoad(context: ModContext) {
            context.events.register(GOOD_EVENT, EventBucket.NORMAL) {
                hits += "probe-event:$GOOD_EVENT"
                EventResult.CONTINUE
            }
            context.strings.declare(PROBE_STRING_KEY, "你好", "hello", "こんにちは")
            context.services.publish(String::class.java, "probe-service", context.packageId)
        }

        override fun onEnable() = Unit

        override fun onDisable() = Unit

        override fun onUnload() = Unit
    }

    private fun teardownProbeMod(hits: MutableList<String>): EraMod = TeardownProbeMod(hits)

    /** 反射读 aux 槽实例上的字段：`peek` 返回的 Handle 是 core-vm internal 类型。 */
    private fun auxField(store: VariableStore, slot: String, field: String): Any? {
        val handle = store.aux.peek<Any>(slot) ?: return null
        return handle.javaClass.getDeclaredField(field).apply { isAccessible = true }.get(handle)
    }

    /** 真源枚举：扫全 main 源集的 `const val … = "mod.…"` 定义（不手写清单）。 */
    private fun scanRealMainAuxSlots(): Map<String, List<String>> {
        val sources = LinkedHashMap<String, String>()
        // 公开分仓后模块分居两仓：引擎侧（core-vm / mod-* / tools）在同级 Kotera，
        // 壳层侧（app / platform / render-canvas）在本仓；真源扫描两仓都要扫到。
        // 键仍是「仓根相对路径」（core-vm/... 与 app/...），与分仓前的单体形态同口径。
        listOf(findEngineRoot(), findShellRoot()).forEach { root ->
            root.listFiles().orEmpty()
                .filter { it.isDirectory }
                .sortedBy { it.name }
                .forEach { module ->
                    val mainKotlin = File(module, "src/main/kotlin")
                    if (!mainKotlin.isDirectory) return@forEach
                    mainKotlin.walkTopDown()
                        .filter { it.isFile && it.extension == "kt" }
                        .sortedBy { it.path }
                        .forEach { file ->
                            sources[file.relativeTo(root).invariantSeparatorsPath] =
                                file.readText(Charsets.UTF_8)
                        }
                }
        }
        check(sources.isNotEmpty()) { "真源扫描为空（cwd=${File(".").absolutePath}）" }
        return enumerateModAuxSlots(sources)
    }

    /**
     * 纯函数：从「相对路径 → 源码文本」里枚举槽名与其定义位置（`路径:行号`）。
     *
     * 判据分两段：① `const val … = "mod.…"` 定义；② 该常量标识符在全源集里**真被当 aux 键**传给
     * `store.aux.slot/peek`。② 是必要的——`mod.` 命名空间里还住着 SharedPreferences 键
     * （`UiPrefs.KEY_MOD_DEX_LOADER = "mod.dex.loader"`，只用于 `sp.getBoolean/putBoolean`），
     * 只按 ① 收会把它误报成「未登记的新槽」。
     */
    private fun enumerateModAuxSlots(sources: Map<String, String>): Map<String, List<String>> {
        val defined = LinkedHashMap<String, MutableList<String>>()
        val declaredIds = LinkedHashMap<String, MutableList<String>>()
        for ((path, text) in sources) {
            for (match in AUX_SLOT_DEFINITION.findAll(text)) {
                val lineNumber = definitionLineNumber(text, match) ?: continue
                val id = match.groupValues[1]
                val slot = match.groupValues[2]
                defined.getOrPut(slot) { mutableListOf() } += "$path:$lineNumber"
                declaredIds.getOrPut(slot) { mutableListOf() } += id
            }
        }
        val auxKeys = AUX_KEY_CONSUMPTION
            .findAll(sources.values.joinToString("\n"))
            .map { it.groupValues[1] }
            .toSet()
        return defined
            .filterKeys { slot -> declaredIds[slot].orEmpty().any { it in auxKeys } }
            .mapValues { it.value.toList() }
    }

    /** 定义所在行号；行首是注释（`//` / `*` / 块注释开头）则返回 null（不算真定义）。 */
    private fun definitionLineNumber(text: String, match: MatchResult): Int? {
        val lineStart = text.lastIndexOf('\n', match.range.first).let { if (it < 0) 0 else it + 1 }
        val lineEnd = text.indexOf('\n', match.range.first).let { if (it < 0) text.length else it }
        val line = text.substring(lineStart, lineEnd).trim()
        if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) return null
        return text.substring(0, match.range.first).count { it == '\n' } + 1
    }

    private fun unregisteredSourceSlots(sourceSlots: Set<String>): List<String> =
        sourceSlots.filterNot { it in EXPECTED_SLOTS }.sorted()

    private fun missingSourceSlots(sourceSlots: Set<String>): List<String> =
        EXPECTED_SLOTS.filterNot { it in sourceSlots }.sorted()

    /** 真源按「仓根相对路径」取：壳层模块在本仓、引擎模块在同级 Kotera，两边都试。 */
    private fun realMainSource(relativePath: String): String =
        listOf(findShellRoot(), findEngineRoot())
            .map { File(it, relativePath) }
            .firstOrNull { it.isFile }
            ?.readText(Charsets.UTF_8)
            ?.replace("\r\n", "\n")
            ?: error("找不到真源 $relativePath（cwd=${File(".").absolutePath}）")

    /**
     * 引擎仓根（公开分仓后 core-vm 在同级引擎仓 Kotera，即 composite build 的 ../Kotera）。
     */
    private fun findEngineRoot(): File {
        val candidates = listOf(File("../../Kotera"), File(".."), File("."), File("../.."))
            .map { it.absoluteFile.normalize() }
        return candidates.firstOrNull { File(it, "core-vm/src/main/kotlin").isDirectory }
            ?: error("找不到引擎仓根（core-vm/src/main/kotlin）（cwd=${File(".").absolutePath}）")
    }

    /** 壳层仓根（本仓；app/src/main/kotlin 在这边）。 */
    private fun findShellRoot(): File {
        val candidates = listOf(File(".."), File("."), File("../..")).map { it.absoluteFile.normalize() }
        return candidates.firstOrNull { File(it, "app/src/main/kotlin").isDirectory }
            ?: error("找不到壳层仓根（app/src/main/kotlin）（cwd=${File(".").absolutePath}）")
    }

    private companion object {
        private const val FULL_VOLUME = 1.0f
        private const val FUNCTIONHOOKS_SLOT = "mod.functionhooks"
        private const val EVENTS_HANDLERS_SLOT = "mod.events.handlers"
        private const val AUDIO_SINK_SLOT = "mod.audio.sink"
        private const val MOD_LINK_CATALOG_SLOT = "mod.chain.catalog"
        private const val PROBE_STRING_KEY = "probe.hello"
        private const val CORE_MOD_DIR = "core-vm/src/main/kotlin/com/eratw/port/core/mod/"

        /** 真源枚举①：值必须整串是 `mod.<小写分词>`（排除 `mod.install pkg=` 日志前缀）。 */
        private val AUX_SLOT_DEFINITION = Regex(
            "const\\s+val\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*(?::\\s*String\\s*)?=\\s*\"(mod\\.[a-z][a-z0-9_.]*)\"",
        )

        /**
         * 真源枚举②：常量标识符被当 aux 键传进 `RuntimeAux.slot/peek`。`mod.` 前缀还共享给
         * SharedPreferences 键，只有真进了 aux 的常量才算槽。
         */
        private val AUX_KEY_CONSUMPTION = Regex(
            "aux\\s*\\.\\s*(?:slot|peek)\\s*(?:<[^>\\n]*>)?\\s*\\(\\s*([A-Za-z_][A-Za-z0-9_]*)\\b",
        )

        /**
         * 收口后必须「已清 / 不可达」的槽 → 收口动作与源码证据。
         * 真装配用例逐条断言的正是这里的四个槽。
         */
        private val ASSERTED_SLOTS: Map<String, String> = linkedMapOf(
            FUNCTIONHOOKS_SLOT to
                "FunctionHookWiring.uninstall(store, host.hookPipeline)：按实例同一性置 Handle.pipeline=null" +
                "（core-vm/src/main/kotlin/com/eratw/port/core/mod/FunctionHookWiring.kt:71-74）",
            EVENTS_HANDLERS_SLOT to
                "ModEventWiring.uninstall(store, host.eventHandlers)：置 registry=null + adoptTriggerBridge(null)" +
                "（core-vm/src/main/kotlin/com/eratw/port/core/mod/ModEventWiring.kt:80-87）",
            AUDIO_SINK_SLOT to
                "ModAudioWiring.uninstall(store)：停声后置 sink/resources=null，晚到 play 返回 false" +
                "（core-vm/src/main/kotlin/com/eratw/port/core/mod/ModAudioWiring.kt:71-77）",
            "mod.events.trigger" to
                "同一次事件面收口里间接摘除：ModEventWiring.uninstall → detachModTriggerTarget" +
                "（core-vm/src/main/kotlin/com/eratw/port/core/mod/ModEventWiring.kt:80-95 与 " +
                "ModEventTriggerWiring.kt 的文件级 detachModTriggerTarget：无条件置 target=null + 清延迟队列）。" +
                "目标实例由 ProcessRuntime 装配期挂上（core-vm/src/main/kotlin/com/eratw/port/core/proc/" +
                "ProcessRuntime.kt:4816），只有引擎自己持有引用 ⇒ 收口按会话级前提无条件解绑。",
        )

        /**
         * 收口不清也安全的槽 → 理由 + 源码 `文件:行号` 证据。
         * 判据：无删除 API（`RuntimeAux.slot` create-if-absent），随会话 store 整体丢弃。
         */
        private val ALLOWLIST: Map<String, String> = linkedMapOf(
            "mod.csv.load" to
                "装载相位单源上下文：ModCsvLoadContext.install 用 store.aux.slot create-if-absent" +
                "（core-vm/src/main/kotlin/com/eratw/port/core/vars/ModCsvLoadContext.kt:35/:38/:41-42），" +
                "无 uninstall/删槽 API；attach 只读消费（app/src/main/kotlin/com/eratw/port/android/" +
                "ModRuntimeAssembly.kt:634 起 of(store)），收口后无生产读点 ⇒ 随会话 store 丢弃。",
            MOD_LINK_CATALOG_SLOT to
                "链接产物随链走：写侧 ModChainAssembly.installCatalogSlot（app/src/main/kotlin/com/eratw/port/" +
                "android/ModChainAssembly.kt:73-77），唯一读点 ModRuntimeAssembly.kt:461；无删除 API，" +
                "随会话 store 整体丢弃（既有对照：app/src/test/kotlin/com/eratw/port/android/" +
                "ModChainCatalogSlotLifecycleAuditTest.kt:54-60）。",
        )

        private val EXPECTED_SLOTS: Set<String> = ASSERTED_SLOTS.keys + ALLOWLIST.keys
    }
}
