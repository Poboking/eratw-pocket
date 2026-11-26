package com.eratw.port.android

import com.eratw.port.core.code.SymbolCatalog
import com.eratw.port.core.vars.VariableStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Lane CO（task-86）审计 A 面：`mod.chain.catalog` 槽的写入 / 读取 / 清除点与跨会话复用。
 *
 * 审计对象（生产链）：`GameHost` 每会话新建 `GameSession` → `installCatalogSlot(fresh.store)`
 * → `ModRuntimeAssembly.attach(..., catalog = modChain?.catalog)`；attach 内唯一读点是
 * `host.knownEventKeys = store.aux.peek<SymbolCatalog>(MOD_LINK_CATALOG_SLOT)`。
 *
 * 结论性断言：
 * - 首会话：槽内与本次链接产物 **同一实例**（`assertSame`），attach 前后不变；
 * - `RuntimeAux.slot` 是 create-if-absent ⇒ 同一 store 二次写 **静默保留首值**（陈旧风险），
 *   且生产同 store 二次 attach 会先被 `charaSlotProjector` 的 require 拒掉（复用不可达）；
 * - `ModuleSession.shutdown()` 不清该槽（无单槽删除 API，靠会话 store 整体丢弃）。
 */
class ModChainCatalogSlotLifecycleAuditTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `证据表_写读清除三触点与宿主时序`() {
        val chain = sourceOf("ModChainAssembly.kt")
        assertTrue(
            "生产写侧：installCatalogSlot 的实做必须是 create-if-absent 的 slot",
            chain.contains("fun installCatalogSlot(store: VariableStore)") &&
                chain.contains("store.aux.slot(MOD_LINK_CATALOG_SLOT) { catalog }"),
        )

        val assembly = sourceOf("ModRuntimeAssembly.kt")
        assertTrue(
            "读取点唯一：attach 内 host.knownEventKeys 读该槽（事件名存在性闸）",
            // 槽读口与喂给 knownEventKeys 的那一段分开钉：上游把一行拆成
            // 「val linkCatalog = store.aux.peek<SymbolCatalog>(MOD_LINK_CATALOG_SLOT)」
            // +「host.knownEventKeys = linkCatalog?.events?.keys.orEmpty()」两行，
            // 读缝仍是同一个槽，判据按两段分别钉（比旧的一行式判据更严）。
            assembly.contains(
                "val linkCatalog = store.aux.peek<SymbolCatalog>(MOD_LINK_CATALOG_SLOT)",
            ) &&
                assembly.contains("host.knownEventKeys = linkCatalog?.events?.keys.orEmpty()"),
        )
        assertTrue(
            "同 store 复用被拒的闸：charaSlotProjector 的 require",
            assembly.contains("同一 VariableStore 重复装配真投射桥"),
        )
        val shutdownStart = assembly.indexOf("fun shutdown(")
        val shutdownEnd = assembly.indexOf("mod.shutdown 完成 mods=")
        assertTrue("shutdown 函数必须找得到", shutdownStart > 0 && shutdownEnd > shutdownStart)
        assertFalse(
            "清除点：shutdown 不触该槽（无删除 API，靠会话 store 整体丢弃）",
            assembly.substring(shutdownStart, shutdownEnd).contains("MOD_LINK_CATALOG_SLOT"),
        )

        val host = sourceOf("GameHost.kt")
        val write = host.indexOf("modChainOutcome?.installCatalogSlot(fresh.store)")
        val attachAt = host.indexOf("modRuntimeSession = ModRuntimeAssembly.attach(")
        assertTrue(
            "宿主时序：写槽在 attach 之前（write=$write, attach=$attachAt）",
            write > 0 && attachAt > write,
        )
    }

    @Test
    fun `a_首次_attach_后槽内_catalog_与本次链接产物同一实例`() {
        val root = tmp.newFolder("fresh")
        writeChainFixtures(root)
        val out = outcome(root)
        assertTrue(
            "前提：base 事件函数必须进 catalog.events（否则闸无事件名可校）",
            out.catalog.events.containsKey(GOOD_EVENT),
        )

        val game = session(root, out.plan)
        val store = game.store
        try {
            // 生产写侧（GameHost.kt:1310 同形：会话 store 建好后写入）。
            out.installCatalogSlot(store)
            assertSame(
                "写侧必须挂装配产物同一实例（共享引用，不重算）",
                out.catalog,
                store.aux.peek<SymbolCatalog>(MOD_LINK_CATALOG_SLOT),
            )

            val logs = mutableListOf<String>()
            val probeLog = mutableListOf<String>()
            val modSession = checkNotNull(
                attach(
                    root,
                    store,
                    logs,
                    listOf(
                        GOOD_PKG to probeMod(GOOD_EVENT, probeLog),
                        TYPO_PKG to probeMod(TYPO_EVENT, probeLog),
                    ),
                    out.catalog,
                ),
            ) { "开态 attach 必须产出运行时；logs=$logs" }
            try {
                assertTrue(
                    "正确事件名的探针照常启用；logs=$logs",
                    logs.any { it == "mod.install pkg=$GOOD_PKG active=true" },
                )
                assertTrue(
                    "拼错事件名的探针被闸拒（active=false + IAE）；logs=$logs",
                    logs.any {
                        it.startsWith("mod.install pkg=$TYPO_PKG active=false") &&
                            it.contains("IllegalArgumentException")
                    },
                )
                assertSame(
                    "attach 只读不写：槽内仍是本次链接产物同一实例",
                    out.catalog,
                    store.aux.peek<SymbolCatalog>(MOD_LINK_CATALOG_SLOT),
                )
            } finally {
                modSession.shutdown()
            }
        } finally {
            game.close()
        }
    }

    @Test
    fun `b_同一_store_二次写槽静默保留首值且二次_attach_被拒`() {
        val root = tmp.newFolder("second")
        writeChainFixtures(root)
        val first = outcome(root)
        val store = VariableStore()
        first.installCatalogSlot(store)
        assertSame(first.catalog, store.aux.peek<SymbolCatalog>(MOD_LINK_CATALOG_SLOT))

        val second = outcome(root)
        assertNotSame("两次装配产物必须是不同快照实例", first.catalog, second.catalog)
        second.installCatalogSlot(store)
        assertSame(
            "RuntimeAux.slot 是 create-if-absent：二次写静默保留首值（陈旧风险）",
            first.catalog,
            store.aux.peek<SymbolCatalog>(MOD_LINK_CATALOG_SLOT),
        )
        assertNotSame(
            "第二次装配的 catalog 没有进槽",
            second.catalog,
            store.aux.peek<SymbolCatalog>(MOD_LINK_CATALOG_SLOT),
        )

        // 真做第一次 attach（装配桥落 store.charaSlotProjector），再试同 store 第二次 attach。
        val firstSession = checkNotNull(attach(root, store, mutableListOf())) {
            "第一次 attach 必须成功（生产窗内 store 只能装配一次）"
        }
        try {
            var thrown: IllegalArgumentException? = null
            try {
                attach(root, store, mutableListOf())
            } catch (e: IllegalArgumentException) {
                thrown = e
            }
            assertNotNull("生产同 store 二次 attach 必须被 require 拒绝（复用不可达）", thrown)
            assertTrue(
                "拒因必须是 charaSlotProjector 的 require；message=${thrown?.message}",
                thrown?.message.orEmpty().contains("同一 VariableStore 重复装配真投射桥"),
            )
        } finally {
            firstSession.shutdown()
        }
    }

    @Test
    fun `反证_不写槽时_typo_事件名探针不被目录闸拦下`() {
        val root = tmp.newFolder("reverse")
        writeChainFixtures(root)
        // 装配真产物存在，但故意不调 installCatalogSlot（撤掉 A 面写侧）。
        outcome(root)
        val store = VariableStore()
        assertNull(
            "反向证明前提：不写槽时槽必须缺席",
            store.aux.peek<SymbolCatalog>(MOD_LINK_CATALOG_SLOT),
        )

        val logs = mutableListOf<String>()
        val probeLog = mutableListOf<String>()
        val modSession = checkNotNull(
            attach(root, store, logs, listOf(TYPO_PKG to probeMod(TYPO_EVENT, probeLog))),
        ) { "开态 attach 必须产出运行时；logs=$logs" }
        try {
            assertTrue(
                "槽缺席 ⇒ knownEventKeys 空集 ⇒ 闸不校验 ⇒ typo 探针被放行；logs=$logs",
                logs.any { it == "mod.install pkg=$TYPO_PKG active=true" },
            )
        } finally {
            modSession.shutdown()
        }
    }
}

