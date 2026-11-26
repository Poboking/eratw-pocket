package com.eratw.port.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val DEX_META_MAX_COMPILED_SURFACE: Int = PACK_META_MAX_COMPILED_SURFACE

private val DEX_SENTINEL_BLOCK_COMMENT: Regex = Regex("/\\*[\\s\\S]*?\\*/")
private val DEX_SENTINEL_LINE_COMMENT: Regex = Regex("(?m)//.*$")

/**
 * ADR-013 S5：dex 生产装载面的定向用例（MODDX4-DEX C4 缺口清偿波）。
 *
 * - [parseDexPackageMeta]：包内 manifest.json（D1）的解析口径——缺字段 / 坏值 / 损坏逐项回落，
 *   未知字段忽略，compiledSurface 坏值只丢本字段（与 eratw-pack.json 解析同口径）；
 * - **生产工厂源码哨兵**：`DexClassLoader` 真构造在 JVM 单测里跑不出结果（android.jar 桩 +
 *   真机行为），工厂本体（[AndroidDexLoaderFactory]）与 forContext 缝的逐位哨兵在
 *   AndroidDexLoaderFactoryTest，本文件钉「全仓生产构造点唯一」与生产接线的总闸单路读数；
 * - **装配接线源码哨兵**：attach 的 dexCarriers 缝缺省 null、dex 载体供给先于 install、
 *   install 并装双 provider、归因行走 ModManageDiagnostics 的 canonical 形状。
 */
class ModFinDexLoaderFactoryTest {

    // ------------------------------------------------------------------
    // parseDexPackageMeta
    // ------------------------------------------------------------------

    @Test
    fun `完整元数据四字段全解析`() {
        val meta = parseDexPackageMeta(
            ("{\"packageId\":\"eratw.demo\",\"version\":\"2.1\",\"compiledSurface\":1," +
                "\"entrypoint\":\"com.example.Main\"}").toByteArray(Charsets.UTF_8),
        )
        assertEquals("eratw.demo", meta.packageId)
        assertEquals("2.1", meta.version)
        assertEquals(1, meta.compiledSurface)
        assertEquals("com.example.Main", meta.entrypoint)
    }

    @Test
    fun `条目缺失与JSON损坏都回落全null`() {
        assertEquals(DexPackageMeta(null, null, null, null), parseDexPackageMeta(null))
        assertEquals(
            DexPackageMeta(null, null, null, null),
            parseDexPackageMeta("not json".toByteArray(Charsets.UTF_8)),
        )
        assertEquals(
            DexPackageMeta(null, null, null, null),
            parseDexPackageMeta(byteArrayOf()),
        )
    }

    @Test
    fun `空对象四字段各自为null_不拒整份`() {
        val meta = parseDexPackageMeta("{}".toByteArray(Charsets.UTF_8))
        assertNull(meta.packageId)
        assertNull(meta.version)
        assertNull(meta.compiledSurface)
        assertNull(meta.entrypoint)
    }

    @Test
    fun `compiledSurface坏值只丢本字段不连坐`() {
        for (bad in listOf(-1, DEX_META_MAX_COMPILED_SURFACE + 1)) {
            val meta = parseDexPackageMeta(
                ("{\"packageId\":\"eratw.demo\",\"compiledSurface\":$bad," +
                    "\"entrypoint\":\"com.example.Main\"}").toByteArray(Charsets.UTF_8),
            )
            assertNull("surface=$bad 应按坏值回落 null", meta.compiledSurface)
            assertEquals("eratw.demo", meta.packageId)
            assertEquals("com.example.Main", meta.entrypoint)
        }
        val nonNumber = parseDexPackageMeta(
            ("{\"packageId\":\"eratw.demo\",\"compiledSurface\":\"1\"}").toByteArray(Charsets.UTF_8),
        )
        assertNull(nonNumber.compiledSurface)
        assertEquals("eratw.demo", nonNumber.packageId)
    }

    @Test
    fun `包名形态非法回落null_调用方据此fail-closed`() {
        val meta = parseDexPackageMeta(
            ("{\"packageId\":\"../evil\",\"entrypoint\":\"com.example.Main\"}")
                .toByteArray(Charsets.UTF_8),
        )
        assertNull(meta.packageId)
        assertEquals("com.example.Main", meta.entrypoint)
    }

    @Test
    fun `入口类空白视为未声明`() {
        val meta = parseDexPackageMeta(
            ("{\"packageId\":\"eratw.demo\",\"entrypoint\":\"   \"}").toByteArray(Charsets.UTF_8),
        )
        assertNull(meta.entrypoint)
    }

    @Test
    fun `未知字段忽略_D1范围外的键不拒包`() {
        val meta = parseDexPackageMeta(
            ("{\"packageId\":\"eratw.demo\",\"entrypoint\":\"com.example.Main\"," +
                "\"permissions\":[\"storage\"],\"requires\":[\"eratw.family\"]," +
                "\"apiVersion\":13,\"minApi\":2,\"future\":{\"x\":1}}").toByteArray(Charsets.UTF_8),
        )
        assertEquals("eratw.demo", meta.packageId)
        assertEquals("com.example.Main", meta.entrypoint)
    }

    // ------------------------------------------------------------------
    // 生产工厂与装配接线：源码哨兵（真机行为见 AndroidDexLoaderFactory KDoc 的 📱 挂账）
    // ------------------------------------------------------------------

    private fun sourceOf(name: String): String {
        val candidates = listOf(
            File("src/main/kotlin/com/eratw/port/android/$name"),
            File("../app/src/main/kotlin/com/eratw/port/android/$name"),
            File("app/src/main/kotlin/com/eratw/port/android/$name"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 $name（试了 ${candidates.joinToString { it.path }}）")
        // 仓里是 CRLF 工作树：多行锚点先归一（ModRuntimeAssemblyWireTest 同款口径）。
        return file.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }

    private fun codeOnly(text: String): String =
        text.replace(DEX_SENTINEL_BLOCK_COMMENT, "").replace(DEX_SENTINEL_LINE_COMMENT, "")

    @Test
    fun `全仓的DexClassLoader生产构造点必须只有AndroidDexLoaderFactory一处`() {
        val factoryCode = codeOnly(sourceOf("AndroidDexLoaderFactory.kt"))
        val carrierCode = codeOnly(sourceOf("ModDexCarrierSource.kt"))
        assertEquals(
            "生产构造点必须只在 AndroidDexLoaderFactory.kt（D1 唯一构造点）",
            1,
            Regex("DexClassLoader\\(").findAll(factoryCode).count(),
        )
        assertEquals(
            "载体供给核心不得自行构造类加载器（只经 DexLoaderFactory 缝）",
            0,
            Regex("DexClassLoader\\(").findAll(carrierCode).count(),
        )
        assertTrue(
            "工厂的 D1 参数序：产物文件 + codeCacheDir + 空优化库搜索路径 + AppClassLoader",
            factoryCode.contains("artifactFile.absolutePath") &&
                factoryCode.contains("context.codeCacheDir.absolutePath") &&
                factoryCode.contains("context.classLoader"),
        )
    }

    @Test
    fun `生产接线读总闸用UiPrefs单路且读一次`() {
        val source = sourceOf("ModDexCarrierSource.kt")
        assertTrue(
            "总闸读数必须走 UiPrefs.modDexLoaderEnabled 单路",
            source.contains("UiPrefs.modDexLoaderEnabled(context)"),
        )
        assertTrue(
            "总闸读数只许出现一次（构造期会话快照；装载期不再碰偏好表）",
            Regex("UiPrefs\\.modDexLoaderEnabled\\(").findAll(source).count() == 1,
        )
    }

    @Test
    fun `装配缝缺省null且装配段在install之前并装`() {
        val source = sourceOf("ModRuntimeAssembly.kt")
        assertTrue(
            "attach 必须有 dexCarriers 缝参数且缺省 null（未接线 = 整段旁路）",
            source.contains("dexCarriers: DexCarrierProvision? = null"),
        )
        val seamAt = source.indexOf("val dexProviders = dexCarriers?.carriers(gameRoot, logSink).orEmpty()")
        val installAt = source.indexOf("val report = host.install(modProviders + dexProviders)")
        assertTrue("装配段必须有 dex 载体供给行", seamAt >= 0)
        assertTrue(
            "install 必须并装 modProviders + dexProviders",
            source.contains("val report = host.install(modProviders + dexProviders)"),
        )
        assertTrue("dex 载体供给必须发生在 install 之前", seamAt < installAt)
    }

    @Test
    fun `归因行走ModManageDiagnostics的canonical形状`() {
        val source = sourceOf("ModDexCarrierSource.kt")
        assertTrue(
            "归因行前缀必须与 ModManageDiagnostics 的 INSTALL_PREFIX 同形",
            source.contains("mod.install pkg="),
        )
        assertTrue(
            "归因行失败段必须与 ModManageDiagnostics 的 active=false failure= 同形",
            source.contains(" active=false failure="),
        )
        assertTrue(
            "S7 生产取证必须走 ErrorReportCollector 既有诊断通道（LOAD_WARNING 同 GameHost 先例）",
            source.contains("DiagnosticEventLog.DiagnosticKind.LOAD_WARNING"),
        )
    }
}
