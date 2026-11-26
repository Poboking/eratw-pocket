package com.eratw.port.android

import com.eratw.port.core.mod.ModDataMigrationException
import com.eratw.port.core.mod.ModDataVersionDowngradeException
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.patch.ModuleDescriptor
import com.eratw.port.modapi.ModApi
import com.eratw.port.modapi.registry.ModRegistry
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mod 版本读数（VS-1）的数据面：[ModVersionReadout] 的三条读数规则。
 *
 * 断言口径（不测像素）：**引擎版本只取一次**、**每行只显示该包自己声明的 API 版本**、
 * **数据版本缺口只在装载门真判过时出现**（拒载 / 迁移失败两条 canonical 句式），
 * 拿不到侧车记录或两侧相等时**不显示**（不猜、不填 0）。
 */
class ModVersionReadoutTest {

    private val zh = SettingsStrings.of(SettingsLanguage.ZH)

    private fun rowOf(
        packageId: String = "eratw.family",
        declaredApiVersion: Int? = ModApi.VERSION,
        dataVersionGap: ModDataVersionGap? = null,
    ): ModVersionRow = ModVersionRow(
        packageId = packageId,
        declaredApiVersion = declaredApiVersion,
        engineApiVersion = MOD_ENGINE_API_VERSION,
        dataVersionGap = dataVersionGap,
    )

    // -----------------------------------------------------------------
    // API 版本
    // -----------------------------------------------------------------

    @Test
    fun `缺 manifest 与未知 packageId 没有版本读数`() {
        val rows = buildModVersionRows(
            packageIds = listOf("eratw.datapack", "kojo-unknown"),
            declaredApiVersions = emptyMap(),
            engineApiVersion = MOD_ENGINE_API_VERSION,
            failures = emptyMap(),
        )

        assertEquals(2, rows.size)
        rows.forEach { row ->
            assertNull("没有作者面声明就不显示 API 版本：${row.packageId}", row.declaredApiVersion)
            assertFalse("没有声明不能算不兼容：${row.packageId}", row.incompatible)
            assertNull(modVersionLine(zh, row))
            assertNull(modVersionIncompatibleLine(zh, row))
        }
        assertNull(modVersionBlocksEnable(rows, "eratw.datapack"))
    }

    @Test
    fun `apiVersion 超前标不兼容 版本行让位给警告行`() {
        val declared = MOD_ENGINE_API_VERSION + 1
        val rows = buildModVersionRows(
            packageIds = listOf("eratw.family"),
            declaredApiVersions = mapOf("eratw.family" to declared),
            engineApiVersion = MOD_ENGINE_API_VERSION,
            failures = emptyMap(),
        )

        val row = rows.single()
        assertTrue(row.incompatible)
        // 警告行自带两个数字，不再叠一行同数字的读数。
        assertNull(modVersionLine(zh, row))
        assertEquals(
            "不兼容（需要 API v$declared，引擎 v${MOD_ENGINE_API_VERSION}）",
            modVersionIncompatibleLine(zh, row),
        )
        // 启用方向被挡、停用方向不受影响（marker 仍是唯一真源）。
        val blocked = modVersionBlocksEnable(rows, "eratw.family")
        assertNotNull(blocked)
        assertEquals(declared, blocked?.declaredApiVersion)
        assertNull(modVersionBlocksEnable(rows, "eratw.other"))
        assertEquals(
            "无法启用：这个 Mod 需要 API v$declared，引擎只到 v${MOD_ENGINE_API_VERSION}。",
            zh.modVersionEnableBlocked(declared, MOD_ENGINE_API_VERSION),
        )
    }

    @Test
    fun `apiVersion 相等不算不兼容`() {
        val row = rowOf(declaredApiVersion = MOD_ENGINE_API_VERSION)

        assertFalse(row.incompatible)
        assertEquals("API 版本 v${MOD_ENGINE_API_VERSION}", modVersionLine(zh, row))
        assertNull(modVersionIncompatibleLine(zh, row))
        assertNull(modVersionBlocksEnable(listOf(row), "eratw.family"))
        // 引擎读数与行读数同源：两条文案里的数字必须一致。
        assertEquals("引擎 mod API v${ModApi.VERSION}", zh.modVersionEngine(MOD_ENGINE_API_VERSION))
    }

    // -----------------------------------------------------------------
    // 数据版本缺口
    // -----------------------------------------------------------------

    @Test
    fun `侧车无记录时只显示 API 版本 不猜数据版本`() {
        val rows = buildModVersionRows(
            packageIds = listOf("eratw.family"),
            declaredApiVersions = mapOf("eratw.family" to ModApi.VERSION),
            engineApiVersion = MOD_ENGINE_API_VERSION,
            // 装配失败但不是版本门判的（没有两侧版本号）：数据版本缺口拿不到就不显示。
            failures = mapOf("eratw.family" to "java.lang.IllegalStateException: boom"),
        )

        val row = rows.single()
        assertNull(row.dataVersionGap)
        assertEquals("API 版本 v${ModApi.VERSION}", modVersionLine(zh, row))
        assertNull("丢弃了侧车记录的失败原因不该产出数据版本读数", dataVersionGapOf("mod.install active=false"))
    }

    @Test
    fun `数据版本相等不显示缺口`() {
        // 两侧相等不构成迁移 / 拒载：即使消息里带数字也不显示。
        val reason = "ModDataVersionDowngradeException: " +
            ModDataVersionDowngradeException("eratw.family", 2, 2).message
        assertNull(dataVersionGapOf(reason))
        val row = rowOf(dataVersionGap = dataVersionGapOf(reason))
        assertEquals("API 版本 v${ModApi.VERSION}", modVersionLine(zh, row))
    }

    @Test
    fun `数据版本降级拒载显示 vX 到 vY`() {
        val reason = "ModDataVersionDowngradeException: " +
            ModDataVersionDowngradeException("eratw.family", storedDataVersion = 3, declaredDataVersion = 1).message
        val gap = dataVersionGapOf(reason)

        assertEquals(ModDataVersionGap(stored = 3, declared = 1), gap)
        val rows = buildModVersionRows(
            packageIds = listOf("eratw.family"),
            declaredApiVersions = mapOf("eratw.family" to ModApi.VERSION),
            engineApiVersion = MOD_ENGINE_API_VERSION,
            failures = mapOf("eratw.family" to reason),
        )
        assertEquals(
            "API 版本 v$MOD_ENGINE_API_VERSION · 数据版本 v3→v1",
            modVersionLine(zh, rows.single()),
        )
        assertEquals("数据版本 v3→v1", zh.modVersionData(3, 1))
    }

    @Test
    fun `数据版本迁移失败显示 vX 到 vY`() {
        val reason = "ModDataMigrationException: " + ModDataMigrationException(
            packageId = "eratw.family",
            fromDataVersion = 0,
            toDataVersion = 2,
            cause = IllegalStateException("boom"),
        ).message

        assertEquals(ModDataVersionGap(stored = 0, declared = 2), dataVersionGapOf(reason))
        assertEquals(
            "API 版本 v$MOD_ENGINE_API_VERSION · 数据版本 v0→v2",
            modVersionLine(zh, rowOf(dataVersionGap = dataVersionGapOf(reason))),
        )
    }

    @Test
    fun `超长数值原样显示不截断`() {
        val max = Int.MAX_VALUE
        val row = rowOf(declaredApiVersion = max)
        assertEquals("不兼容（需要 API v$max，引擎 v$MOD_ENGINE_API_VERSION）", modVersionIncompatibleLine(zh, row))
        val gapRow = rowOf(dataVersionGap = ModDataVersionGap(stored = max, declared = 0))
        assertEquals(
            "API 版本 v$MOD_ENGINE_API_VERSION · 数据版本 v$max→v0",
            modVersionLine(zh, gapRow),
        )
    }

    // -----------------------------------------------------------------
    // 与盘面扫描串起来（缺 manifest = 扫不出来 = 没有行，不抛）
    // -----------------------------------------------------------------

    @Test
    fun `扫描结果配载体声明 逐包给读数`() {
        val root = Files.createTempDirectory("rt2-version-readout").toFile()
        try {
            val rt2 = ModEnablement.rt2Dir(root).also { it.mkdirs() }
            val descriptor = ModuleDescriptor(
                packageId = "eratw.family",
                version = "0.2",
                displayName = "家族系统",
            )
            File(rt2, "eratw.family.manifest").writeBytes(ManifestCodec.encode(descriptor))
            // 数据包没有 Kotlin 载体声明：扫描得到、但没有作者面 API 版本。
            File(rt2, "eratw.datapack.manifest").writeBytes(
                ManifestCodec.encode(ModuleDescriptor(packageId = "eratw.datapack", version = "1.0")),
            )

            val scan = ModManageScanner.scan(root)
            val rows = buildModVersionRows(
                packageIds = scan.mods.map { it.packageId },
                declaredApiVersions = mapOf("eratw.family" to ModApi.VERSION),
                engineApiVersion = MOD_ENGINE_API_VERSION,
                failures = emptyMap(),
            )

            assertEquals(listOf("eratw.datapack", "eratw.family"), rows.map { it.packageId }.sorted())
            val byId = rows.associateBy { it.packageId }
            assertNull("数据包不该凭空长出版本行", modVersionLine(zh, byId.getValue("eratw.datapack")))
            assertEquals(
                "API 版本 v${ModApi.VERSION}",
                modVersionLine(zh, byId.getValue("eratw.family")),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    /**
     * 生产读数通道（[ModCarrierRegistry.versionDeclarations]）：名单与 build-time 注册表逐位一致，
     * 且版本号是作者声明的 `apiVersion`（现网载体都取默认 = `ModApi.VERSION`）。
     *
     * 口径钉子：读数只读声明、不 `newMod()`——设置页在总闸关着时也会读这张表，而「关态一个对象
     * 都不建」的 B1 判据延伸到载体实例（见 [ModCarrierRegistry] 类 KDoc）。
     */
    @Test
    fun `载体声明表与注册表逐位一致`() {
        val declarations = ModCarrierRegistry.versionDeclarations()

        assertEquals(
            "读数名单必须与 build-time 注册表同源同序",
            ModRegistry.carriers.map { it.packageId },
            declarations.map { it.first },
        )
        declarations.forEach { (packageId, apiVersion) ->
            assertEquals("$packageId 的声明版本应取 ModApi.VERSION", ModApi.VERSION, apiVersion)
        }
    }
}
