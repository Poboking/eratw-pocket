package com.eratw.port.android

import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.mod.ModDataMigrationException
import com.eratw.port.core.mod.ModDataVersionDowngradeException
import com.eratw.port.core.mod.ModDeclarationAdapter
import com.eratw.port.core.mod.ModStorageSection
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.save.FileSystemOps
import com.eratw.port.core.save.RootedSaveFileIo
import com.eratw.port.core.save.SaveConfig
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modapi.ArchetypeDecl
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.ModApi
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.ModValue
import java.nio.file.Files
import java.nio.file.Path
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `EraMod.dataVersion` / `onMigrate` 的**真链路 e2e**（Lane CB / task-73）。
 *
 * 与 `core-vm` 的单元用例（`ModVersionNegotiationTest` 直接 new `ModRuntimeHost`）不同，
 * 这里走生产装配链：磁盘 manifest（`mods/rt2` 目录里的 manifest 快照）→ `ModRuntimeAssembly.attach`
 * → `FileSystemOps.saveGlobal` / `loadGlobal`（`global.sav` + `global.sav.rt2` 真落盘），
 * 断言点全部落在 mod 作者面（`ModContext.storage`）与玩家面（`mod.install` 行 /
 * `ModManageDiagnostics` / `ModVersionReadout`）：
 *
 * - a) 存档 v1 → 换 v2 载体 → 读档触发一次 `onMigrate(1, 2)` 真改写载荷、侧车落成 2，
 *   再存再读不再迁移；
 * - b) `onMigrate` 抛异常 ⇒ 只拒该 mod（其余 mod 照常迁移），侧车保持旧版本号、下次读档重试；
 * - c) 侧车版本 > 载体声明 ⇒ 既有 fail-closed 降级拒载，数据版本不被写回旧值、数据不删。
 *
 * 计时口径：全部断言都写「期望 vs 实际」，失败信息必须能看出差在哪一侧。
 */
class ModDataVersionMigrationE2ETest {

    private val logs: MutableList<String> = mutableListOf()

    private val zh = SettingsStrings.of(SettingsLanguage.ZH)

    @After
    fun tearDown() {
        ModManageDiagnostics.reset()
    }

    // -----------------------------------------------------------------
    // 夹具：游戏根 / manifest / 生产存档消费点 / 容器读数
    // -----------------------------------------------------------------

    private fun tempGameRoot(): Path {
        val root = Files.createTempDirectory("mod-dataversion-e2e")
        Files.createDirectories(root.resolve("mods").resolve("rt2"))
        return root
    }

    private fun writeManifest(root: Path, fileName: String, packageId: String) {
        val manifest = ModManifest(
            packageId = packageId,
            archetypes = listOf(ArchetypeDecl("$packageId.trait")),
        )
        val bytes = ManifestCodec.encode(ModDeclarationAdapter.toDescriptor(manifest))
        Files.write(root.resolve("mods").resolve("rt2").resolve(fileName), bytes)
    }

    private fun attach(
        root: Path,
        store: VariableStore,
        providers: List<Pair<String, EraMod>>,
    ): ModRuntimeSession =
        ModRuntimeAssembly.attach(
            gameRoot = root.toFile(),
            store = store,
            nativeRegistry = NativeMethodRegistry(),
            onVmThread = { true },
            enabled = true,
            logSink = { line -> logs += line },
            modProviders = providers,
        ) ?: error("开态装配必须返回会话")

    private fun freshStore(): VariableStore = VariableStore()

    /** eratw 实机二进制档口径的生产消费点（SAVEGLOBAL / LOADGLOBAL 真落盘）。 */
    private fun binaryOps(store: VariableStore, root: Path): FileSystemOps = FileSystemOps(
        store,
        RootedSaveFileIo(root),
        SaveConfig(systemSaveInBinary = true, zipSaveData = true),
    )

    private fun sectionOf(session: ModRuntimeSession, packageId: String): ModStorageSection =
        session.storage.sectionsSnapshot().firstOrNull { it.packageId == packageId }
            ?: error("容器里没有 $packageId 段：packages=${session.storage.packages()}")

    private fun dataVersionOf(session: ModRuntimeSession, packageId: String): Int =
        sectionOf(session, packageId).dataVersion

    private fun payloadOf(session: ModRuntimeSession, packageId: String): String? =
        (sectionOf(session, packageId).entries[PAYLOAD_KEY] as? ModValue.StrValue)?.value

    private fun installLines(): List<String> = logs.filter { it.startsWith("mod.install pkg=") }

    private fun canonicalFailureLine(packageId: String, failure: Throwable): String =
        "mod.install pkg=$packageId active=false failure=" +
            "${failure.javaClass.simpleName}: ${failure.message}"

    /**
     * 存档 v1 会话（declared=1，onLoad 写 `shape-v1`）→ 关会话。返回游戏根。
     *
     * @param payload 首会话 onLoad 落进 storage 的载荷；后续读档/迁移都基于它。
     */
    private fun savedV1Session(root: Path, payload: String = "shape-v1") {
        val store = freshStore()
        val session = attach(
            root,
            store,
            listOf("mod.a" to MigratingMod(declaredDataVersion = 1, onLoadPayload = payload)),
        )
        binaryOps(store, root).saveGlobal()
        session.shutdown()
    }

    // -----------------------------------------------------------------
    // 被测 mod 载体：只碰本包 storage，声明版本 + 迁移改写 + 可注入失败
    // -----------------------------------------------------------------

    private class MigratingMod(
        private val declaredDataVersion: Int,
        private val onLoadPayload: String,
        private val migrateThrows: Boolean = false,
    ) : EraMod {
        var context: ModContext? = null
        val migrations: MutableList<Pair<Int, Int>> = mutableListOf()

        /** 首轮 onLoad 时容器是否已有本包载荷（生产读档缝在首轮 onEnable 之后才恢复侧车）。 */
        var onLoadSawPayload: String? = null

        override val dataVersion: Int get() = declaredDataVersion

        override fun onLoad(context: ModContext) {
            this.context = context
            onLoadSawPayload = context.storage.get(PAYLOAD_KEY)
            context.storage.put(PAYLOAD_KEY, onLoadPayload)
        }

        override fun onEnable() = Unit

        override fun onDisable() = Unit

        override fun onUnload() = Unit

        override fun onMigrate(fromDataVersion: Int, toDataVersion: Int) {
            migrations += fromDataVersion to toDataVersion
            if (migrateThrows) throw IllegalStateException(INJECTED_FAILURE)
            val old = context!!.storage.get(PAYLOAD_KEY)
            context!!.storage.put(PAYLOAD_KEY, migratedPayload(fromDataVersion, toDataVersion, old))
        }
    }

    // -----------------------------------------------------------------
    // a) 升级读档：触发迁移 + 载荷真改写 + 侧车落成 2 + 再存再读不重复迁移
    // -----------------------------------------------------------------

    @Test
    fun `升级读档 - onMigrate 收到 1到2 且载荷按旧档值真改写`() {
        val root = tempGameRoot()
        writeManifest(root, "mod_a.manifest", "mod.a")
        savedV1Session(root)

        val v2 = MigratingMod(declaredDataVersion = 2, onLoadPayload = "fresh-shape-v2")
        val store2 = freshStore()
        val session2 = attach(root, store2, listOf("mod.a" to v2))
        assertNull(
            "生产读档缝：首轮 onLoad 发生在侧车恢复之前（期望 null，实际 ${v2.onLoadSawPayload}）",
            v2.onLoadSawPayload,
        )
        assertTrue("LOADGLOBAL 必须应用成功", binaryOps(store2, root).loadGlobal())

        assertEquals(
            "onMigrate 应按档内版本→声明版本调一次（期望 [1 to 2]，实际 ${v2.migrations}）",
            listOf(1 to 2),
            v2.migrations,
        )
        val migrated = v2.context!!.storage.get(PAYLOAD_KEY)
        assertEquals(
            "载荷必须按旧档值真改写（期望 migrated(1->2):shape-v1，实际 $migrated）",
            "migrated(1->2):shape-v1",
            migrated,
        )
        val version = dataVersionOf(session2, "mod.a")
        assertEquals("迁移成功后侧车版本应落成 2（实际 $version）", 2, version)
        session2.shutdown()
    }

    @Test
    fun `升级读档 - 迁移后侧车落成 2 且再存再读不重复迁移`() {
        val root = tempGameRoot()
        writeManifest(root, "mod_a.manifest", "mod.a")
        savedV1Session(root)

        val v2 = MigratingMod(declaredDataVersion = 2, onLoadPayload = "fresh-shape-v2")
        val store2 = freshStore()
        val session2 = attach(root, store2, listOf("mod.a" to v2))
        assertTrue("第二会话读档必须成功", binaryOps(store2, root).loadGlobal())
        assertEquals(
            "第二会话应迁移一次（期望 [1 to 2]，实际 ${v2.migrations}）",
            listOf(1 to 2),
            v2.migrations,
        )
        // 迁移结果必须随档再存一轮：侧车版本落成 2 才可能让第三会话不再迁移。
        binaryOps(store2, root).saveGlobal()
        session2.shutdown()

        val v2b = MigratingMod(declaredDataVersion = 2, onLoadPayload = "fresh-shape-v3")
        val store3 = freshStore()
        val session3 = attach(root, store3, listOf("mod.a" to v2b))
        assertTrue("第三会话读档必须成功", binaryOps(store3, root).loadGlobal())
        assertEquals(
            "侧车已是 2 ⇒ 不得再迁移（期望 []，实际 ${v2b.migrations}）",
            emptyList<Pair<Int, Int>>(),
            v2b.migrations,
        )
        val version = dataVersionOf(session3, "mod.a")
        assertEquals("再存再读后侧车版本应保持 2（实际 $version）", 2, version)
        val payload = v2b.context!!.storage.get(PAYLOAD_KEY)
        assertEquals(
            "迁移改写后的载荷必须随档保留（期望 migrated(1->2):shape-v1，实际 $payload）",
            "migrated(1->2):shape-v1",
            payload,
        )
        session3.shutdown()
    }

    // -----------------------------------------------------------------
    // b) 迁移抛异常：per-mod 拒载 / 侧车保持旧版本 / 下次读档重试
    // -----------------------------------------------------------------

    @Test
    fun `迁移抛异常 - 该 mod 降级拒载且侧车不被改成 2`() {
        val root = tempGameRoot()
        writeManifest(root, "mod_a.manifest", "mod.a")
        savedV1Session(root)

        val v2 = MigratingMod(declaredDataVersion = 2, onLoadPayload = "fresh", migrateThrows = true)
        val store2 = freshStore()
        val session2 = attach(root, store2, listOf("mod.a" to v2))
        assertTrue("迁移失败也应完成读档应用（失败只拒该 mod）", binaryOps(store2, root).loadGlobal())

        val failure = session2.host.failureOf("mod.a")
        assertNotNull("迁移失败必须记到该 mod 的 failure（实际 null）", failure)
        assertTrue(
            "失败类型应为 ModDataMigrationException（实际 ${failure!!::class.java.name}）",
            failure is ModDataMigrationException,
        )
        val reason = failure.message.orEmpty()
        assertTrue("失败消息应指明 v1→v2 失败（实际 $reason）", reason.contains("数据迁移 v1→v2 失败"))
        assertTrue("失败消息应带注入原因（实际 $reason）", reason.contains(INJECTED_FAILURE))
        assertFalse("失败 mod 必须被降级拒载，而不是半迁移态", session2.storage.isActive("mod.a"))
        val version = dataVersionOf(session2, "mod.a")
        assertEquals("失败不得把侧车版本写成 2（实际 $version）", 1, version)
        assertEquals("旧载荷必须原样留下（不删不改）", "shape-v1", payloadOf(session2, "mod.a"))
        assertTrue("旧段应退回孤儿（仍随档写回）", sectionOf(session2, "mod.a").orphan)
        session2.shutdown()
    }

    @Test
    fun `迁移抛异常 - 失败原因经 canonical 行进玩家可读读数`() {
        val root = tempGameRoot()
        writeManifest(root, "mod_a.manifest", "mod.a")
        savedV1Session(root)

        val v2 = MigratingMod(declaredDataVersion = 2, onLoadPayload = "fresh", migrateThrows = true)
        val store2 = freshStore()
        val session2 = attach(root, store2, listOf("mod.a" to v2))
        assertTrue("迁移失败也应完成读档应用", binaryOps(store2, root).loadGlobal())

        val failure = session2.host.failureOf("mod.a")!!
        val expected = canonicalFailureLine("mod.a", failure)
        assertEquals(
            "读档拒载必须打 canonical 失败行（期望含「$expected」；实际 install 行=${installLines()}）",
            expected,
            installLines().firstOrNull { it.contains("pkg=mod.a active=false") },
        )
        installLines().forEach(ModManageDiagnostics::record)
        assertEquals(
            "诊断面应读出该包失败原因（期望 ModDataMigrationException: …）",
            mapOf("mod.a" to "${failure.javaClass.simpleName}: ${failure.message}"),
            ModManageDiagnostics.snapshot(),
        )
        val rows = buildModVersionRows(
            packageIds = listOf("mod.a"),
            declaredApiVersions = mapOf("mod.a" to ModApi.VERSION),
            engineApiVersion = MOD_ENGINE_API_VERSION,
            failures = ModManageDiagnostics.snapshot(),
        )
        assertEquals(
            "玩家可读读数应显示迁移缺口 1→2（实际 ${rows.single().dataVersionGap}）",
            ModDataVersionGap(stored = 1, declared = 2),
            rows.single().dataVersionGap,
        )
        val line = modVersionLine(zh, rows.single())
        assertTrue("版本行应含「数据版本 v1→v2」（实际 $line）", line!!.contains("数据版本 v1→v2"))
        session2.shutdown()
    }

    @Test
    fun `迁移抛异常 - per mod 隔离 其余 mod 照常迁移`() {
        val root = tempGameRoot()
        writeManifest(root, "mod_a.manifest", "mod.a")
        writeManifest(root, "mod_b.manifest", "mod.b")

        val store1 = freshStore()
        val session1 = attach(
            root,
            store1,
            listOf(
                "mod.a" to MigratingMod(declaredDataVersion = 1, onLoadPayload = "shape-a-v1"),
                "mod.b" to MigratingMod(declaredDataVersion = 1, onLoadPayload = "shape-b-v1"),
            ),
        )
        binaryOps(store1, root).saveGlobal()
        session1.shutdown()

        val a2 = MigratingMod(declaredDataVersion = 2, onLoadPayload = "fresh-a", migrateThrows = true)
        val b2 = MigratingMod(declaredDataVersion = 2, onLoadPayload = "fresh-b")
        val store2 = freshStore()
        val session2 = attach(root, store2, listOf("mod.a" to a2, "mod.b" to b2))
        assertTrue("读档必须成功", binaryOps(store2, root).loadGlobal())

        assertFalse("失败的 mod.a 必须被拒载", session2.storage.isActive("mod.a"))
        assertEquals("mod.a 侧车保持 1", 1, dataVersionOf(session2, "mod.a"))
        assertTrue("mod.b 必须仍激活（per-mod 隔离）", session2.storage.isActive("mod.b"))
        assertEquals("mod.b 应迁移到 2", 2, dataVersionOf(session2, "mod.b"))
        assertEquals("mod.b 的迁移只调一次（期望 [1 to 2]，实际 ${b2.migrations}）", listOf(1 to 2), b2.migrations)
        val payloadB = b2.context!!.storage.get(PAYLOAD_KEY)
        assertEquals(
            "mod.b 载荷应按旧档值改写（期望 migrated(1->2):shape-b-v1，实际 $payloadB）",
            "migrated(1->2):shape-b-v1",
            payloadB,
        )
        session2.shutdown()
    }

    @Test
    fun `迁移抛异常 - 下次读档再调一次并成功落成 2`() {
        val root = tempGameRoot()
        writeManifest(root, "mod_a.manifest", "mod.a")
        savedV1Session(root)

        val failing = MigratingMod(declaredDataVersion = 2, onLoadPayload = "fresh", migrateThrows = true)
        val store2 = freshStore()
        val session2 = attach(root, store2, listOf("mod.a" to failing))
        assertTrue("第一次读档必须成功应用", binaryOps(store2, root).loadGlobal())
        assertEquals("失败后侧车保持 1", 1, dataVersionOf(session2, "mod.a"))
        // 契约：失败保持旧版本号 ⇒ 下次读档还会再调一次；这里把失败后的整档再存一轮。
        binaryOps(store2, root).saveGlobal()
        session2.shutdown()

        val healed = MigratingMod(declaredDataVersion = 2, onLoadPayload = "fresh")
        val store3 = freshStore()
        val session3 = attach(root, store3, listOf("mod.a" to healed))
        assertTrue("重试读档必须成功", binaryOps(store3, root).loadGlobal())
        assertEquals(
            "上次失败未推进版本 ⇒ 下次读档必须重试迁移（期望 [1 to 2]，实际 ${healed.migrations}）",
            listOf(1 to 2),
            healed.migrations,
        )
        assertEquals("重试成功后侧车落成 2", 2, dataVersionOf(session3, "mod.a"))
        val payload = healed.context!!.storage.get(PAYLOAD_KEY)
        assertEquals(
            "重试迁移应改写旧档载荷（期望 migrated(1->2):shape-v1，实际 $payload）",
            "migrated(1->2):shape-v1",
            payload,
        )
        session3.shutdown()
    }

    // -----------------------------------------------------------------
    // c) 侧车版本超前（用户换回旧版 mod）：fail-closed 降级拒载
    // -----------------------------------------------------------------

    private fun downgradeLoadedSession(root: Path): Pair<ModRuntimeSession, MigratingMod> {
        val store = freshStore()
        val mod = MigratingMod(declaredDataVersion = 1, onLoadPayload = "fresh-v1")
        val session = attach(root, store, listOf("mod.a" to mod))
        assertTrue("旧版载体读新档：读档本身必须成功应用", binaryOps(store, root).loadGlobal())
        return session to mod
    }

    @Test
    fun `侧车版本超前 - 旧版 mod 被拒绝装载且数据版本不降级`() {
        val root = tempGameRoot()
        writeManifest(root, "mod_a.manifest", "mod.a")

        val store1 = freshStore()
        val session1 = attach(
            root,
            store1,
            listOf("mod.a" to MigratingMod(declaredDataVersion = 2, onLoadPayload = "shape-v2")),
        )
        binaryOps(store1, root).saveGlobal()
        assertEquals("新档侧车版本应为 2", 2, dataVersionOf(session1, "mod.a"))
        session1.shutdown()

        val (session2, _) = downgradeLoadedSession(root)
        val failure = session2.host.failureOf("mod.a")
        assertNotNull("降级必须记到该 mod 的 failure（实际 null）", failure)
        assertTrue(
            "失败类型应为 ModDataVersionDowngradeException（实际 ${failure!!::class.java.name}）",
            failure is ModDataVersionDowngradeException,
        )
        val reason = failure.message.orEmpty()
        assertTrue("失败消息应点名两侧版本（实际 $reason）", reason.contains("存档记录 v2，mod 只声明到 v1"))
        assertFalse("旧版 mod 必须被拒载", session2.storage.isActive("mod.a"))
        val version = dataVersionOf(session2, "mod.a")
        assertEquals("侧车版本不得被降级写成 1（实际 $version）", 2, version)
        val payload = payloadOf(session2, "mod.a")
        assertEquals("新版 v2 载荷必须原样保留（实际 $payload）", "shape-v2", payload)
        session2.shutdown()
    }

    @Test
    fun `侧车版本超前 - 玩家可读版本读数显示 v2到v1`() {
        val root = tempGameRoot()
        writeManifest(root, "mod_a.manifest", "mod.a")
        val store1 = freshStore()
        val session1 = attach(
            root,
            store1,
            listOf("mod.a" to MigratingMod(declaredDataVersion = 2, onLoadPayload = "shape-v2")),
        )
        binaryOps(store1, root).saveGlobal()
        session1.shutdown()

        val (session2, _) = downgradeLoadedSession(root)
        val failure = session2.host.failureOf("mod.a")!!
        val expected = canonicalFailureLine("mod.a", failure)
        assertTrue(
            "降级拒载必须打 canonical 行（期望含「$expected」；实际 install 行=${installLines()}）",
            installLines().contains(expected),
        )
        installLines().forEach(ModManageDiagnostics::record)
        val rows = buildModVersionRows(
            packageIds = listOf("mod.a"),
            declaredApiVersions = mapOf("mod.a" to ModApi.VERSION),
            engineApiVersion = MOD_ENGINE_API_VERSION,
            failures = ModManageDiagnostics.snapshot(),
        )
        assertEquals(
            "玩家可读读数应显示缺口 2→1（实际 ${rows.single().dataVersionGap}）",
            ModDataVersionGap(stored = 2, declared = 1),
            rows.single().dataVersionGap,
        )
        val line = modVersionLine(zh, rows.single())
        assertTrue("版本行应含「数据版本 v2→v1」（实际 $line）", line!!.contains("数据版本 v2→v1"))
        session2.shutdown()
    }

    @Test
    fun `侧车版本超前 - 拒载后数据原样保留 再存再读仍拒`() {
        val root = tempGameRoot()
        writeManifest(root, "mod_a.manifest", "mod.a")
        val store1 = freshStore()
        val session1 = attach(
            root,
            store1,
            listOf("mod.a" to MigratingMod(declaredDataVersion = 2, onLoadPayload = "shape-v2")),
        )
        binaryOps(store1, root).saveGlobal()
        session1.shutdown()

        val store2 = freshStore()
        val session2 = attach(
            root,
            store2,
            listOf("mod.a" to MigratingMod(declaredDataVersion = 1, onLoadPayload = "fresh-v1")),
        )
        assertTrue("旧版载体读档应用成功", binaryOps(store2, root).loadGlobal())
        assertFalse("旧版 mod 读档被拒", session2.storage.isActive("mod.a"))
        // 拒载后整档再存读一轮：数据必须原样在侧车里（不删、不写坏），且仍被拒。
        binaryOps(store2, root).saveGlobal()
        session2.shutdown()

        val store3 = freshStore()
        val session3 = attach(
            root,
            store3,
            listOf("mod.a" to MigratingMod(declaredDataVersion = 1, onLoadPayload = "fresh-v1")),
        )
        assertTrue("第三会话读档应用成功", binaryOps(store3, root).loadGlobal())
        val failure = session3.host.failureOf("mod.a")
        assertTrue(
            "再读仍必须降级拒载（实际 ${failure?.javaClass?.name}: ${failure?.message}）",
            failure is ModDataVersionDowngradeException,
        )
        assertEquals("数据版本仍保持 2", 2, dataVersionOf(session3, "mod.a"))
        val payload = payloadOf(session3, "mod.a")
        assertEquals("v2 载荷必须原样保留（实际 $payload）", "shape-v2", payload)
        session3.shutdown()
    }

    // -----------------------------------------------------------------
    // 无记录 / 同版本：负向基线，钉住「不该迁移时一次都不调」
    // -----------------------------------------------------------------

    @Test
    fun `无侧车记录或版本相同 - 不调用 onMigrate`() {
        val root = tempGameRoot()
        writeManifest(root, "mod_a.manifest", "mod.a")

        val fresh = MigratingMod(declaredDataVersion = 3, onLoadPayload = "shape-v3")
        val store1 = freshStore()
        val session1 = attach(root, store1, listOf("mod.a" to fresh))
        assertEquals(
            "无侧车记录（新局）不得迁移（期望 []，实际 ${fresh.migrations}）",
            emptyList<Pair<Int, Int>>(),
            fresh.migrations,
        )
        assertEquals("无记录时直接落 declared", 3, dataVersionOf(session1, "mod.a"))
        binaryOps(store1, root).saveGlobal()
        session1.shutdown()

        val same = MigratingMod(declaredDataVersion = 3, onLoadPayload = "fresh-shape-v3")
        val store2 = freshStore()
        val session2 = attach(root, store2, listOf("mod.a" to same))
        assertTrue("同版本读档必须成功", binaryOps(store2, root).loadGlobal())
        assertEquals(
            "版本相同不得迁移（期望 []，实际 ${same.migrations}）",
            emptyList<Pair<Int, Int>>(),
            same.migrations,
        )
        assertEquals("版本相同保持 3", 3, dataVersionOf(session2, "mod.a"))
        val payload = same.context!!.storage.get(PAYLOAD_KEY)
        assertEquals("读档后载荷应是首会话写的 shape-v3（实际 $payload）", "shape-v3", payload)
        session2.shutdown()
    }

    private companion object {
        /** 本包唯一的载荷键：迁移读写它来证明「真读到旧档、真改写」。 */
        const val PAYLOAD_KEY: String = "shape"

        /** 注入失败消息：测试与断言共用同一字面量，避免「断言没真命中失败路径」。 */
        const val INJECTED_FAILURE: String = "injected migration failure"

        /** 迁移载荷=「迁移前旧值」的函数式改写：能证明 onMigrate 真读到了档内数据。 */
        fun migratedPayload(from: Int, to: Int, old: String?): String = "migrated($from->$to):$old"
    }
}
