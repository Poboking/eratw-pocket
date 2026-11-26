package com.eratw.port.android

import com.eratw.port.modapi.ModApi
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private const val TEST_PACKAGE_ID: String = "eratw.dexfactory"
private const val SHA_PREFIX_LENGTH: Int = 16
private const val ENGINE_API_VERSION: Int = ModApi.VERSION
private val PAYLOAD_BYTES: ByteArray = "fake-mod-dex-payload".toByteArray(Charsets.UTF_8)

private val BLOCK_COMMENT: Regex = Regex("/\\*[\\s\\S]*?\\*/")
private val LINE_COMMENT: Regex = Regex("(?m)//.*$")

/**
 * ADR-013 S4c 装配缝与 Android 工厂的 JVM 侧用例：
 * 1. [resolveDexLoaderFactory] 的四种组合（可数工厂构造次数）；
 * 2. [ModDexLoader] 在工厂缺席时的数据面收口；
 * 3. [AndroidDexLoaderFactory] 与 forContext 缝的源码哨兵（真机实装载在 JVM 不可测）。
 */
class AndroidDexLoaderFactoryTest {

    // ------------------------------------------------------------------
    // 装配缝：工厂选择
    // ------------------------------------------------------------------

    @Test
    fun `总闸关时工厂选择为 null 且生产工厂零构造`() {
        var productionCalls = 0
        val resolved = resolveDexLoaderFactory(enabled = false, injected = null) {
            productionCalls += 1
            FakeFactory()
        }
        assertNull("总闸关必须不装配工厂", resolved)
        assertEquals("总闸关时生产工厂构造次数必须为 0", 0, productionCalls)
    }

    @Test
    fun `总闸关时即便注入了工厂也不装配且生产工厂零构造`() {
        var productionCalls = 0
        val resolved = resolveDexLoaderFactory(enabled = false, injected = FakeFactory()) {
            productionCalls += 1
            FakeFactory()
        }
        assertNull("总闸关优先于注入面", resolved)
        assertEquals(0, productionCalls)
    }

    @Test
    fun `总闸开时注入工厂优先且生产工厂零构造`() {
        var productionCalls = 0
        val injected = FakeFactory()
        val resolved = resolveDexLoaderFactory(enabled = true, injected = injected) {
            productionCalls += 1
            FakeFactory()
        }
        assertSame("JVM 单测的 fake 必须能替换生产实现", injected, resolved)
        assertEquals("注入了工厂就不许再构造生产工厂", 0, productionCalls)
    }

    @Test
    fun `总闸开且未注入时用生产工厂`() {
        var productionCalls = 0
        val produced = FakeFactory()
        val resolved = resolveDexLoaderFactory(enabled = true, injected = null) {
            productionCalls += 1
            produced
        }
        assertSame(produced, resolved)
        assertEquals("生产工厂只在需要时构造一次", 1, productionCalls)
    }

    // ------------------------------------------------------------------
    // 工厂缺席时的数据面收口（forContext 判工厂时的兜底分支）
    // ------------------------------------------------------------------

    @Test
    fun `工厂缺席时装载链收敛为 LoaderFailed 而非抛异常`() {
        val rt2 = tempDir()
        trust(rt2)
        writeArtifact(rt2)
        val loader = ModDexLoader(
            codeStore = ModCodeStore(rt2),
            gate = ModDexGate::verdict,
            factory = null,
            logSink = {},
            dexLoaderEnabled = true,
        )
        val outcome = loader.load(TEST_PACKAGE_ID, metaOf(), ENGINE_API_VERSION)
        assertTrue("期望 LoaderFailed，实际：$outcome", outcome is LoadOutcome.LoaderFailed)
    }

    // ------------------------------------------------------------------
    // 源码哨兵（JVM 无法实例化真 DexClassLoader 的替代覆盖）
    // ------------------------------------------------------------------

    @Test
    fun `源码哨兵 Android 工厂只用 DexClassLoader 加 codeCacheDir 加 AppClassLoader`() {
        val code = codeOnly(sourceOf("AndroidDexLoaderFactory.kt"))
        assertTrue("必须引用 dalvik.system.DexClassLoader", code.contains("dalvik.system.DexClassLoader"))
        assertTrue("必须构造 DexClassLoader", code.contains("DexClassLoader("))
        assertTrue("优化产物目录必须是 context.codeCacheDir", code.contains("context.codeCacheDir"))
        assertTrue("parent 必须是 AppClassLoader（context.classLoader）", code.contains("context.classLoader"))
        assertFalse("不得自行拼 code store 路径", code.contains("codeDir("))
        assertFalse("不得引用 code store 目录常量", code.contains("MOD_CODE_DIR_NAME"))
        assertFalse("不得持有 code store 引用", code.contains("ModCodeStore"))
        assertFalse("不得自行拼 rt2 路径", code.contains("rt2"))
    }

    @Test
    fun `源码哨兵 forContext 先读总闸再惰性构造 Android 工厂且工厂参数可注入`() {
        val code = codeOnly(sourceOf("ModDexLoader.kt"))
        assertTrue("forContext 必须经 resolveDexLoaderFactory 装配", code.contains("resolveDexLoaderFactory("))
        assertTrue("forContext 的工厂参数必须可空可注入", code.contains("factory: DexLoaderFactory? = null"))
        val gateRead = code.indexOf("UiPrefs.modDexLoaderEnabled(context)")
        val factoryConstruction = code.indexOf("AndroidDexLoaderFactory(context)")
        assertTrue("forContext 必须先读总闸", gateRead >= 0)
        assertTrue("forContext 必须装配 Android 工厂", factoryConstruction >= 0)
        assertTrue("总闸读取必须在工厂构造之前", gateRead < factoryConstruction)
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private fun sourceOf(fileName: String): String {
        val candidates = listOf(
            File("src/main/kotlin/com/eratw/port/android", fileName),
            File("../app/src/main/kotlin/com/eratw/port/android", fileName),
            File("app/src/main/kotlin/com/eratw/port/android", fileName),
        )
        val file = candidates.firstOrNull { it.isFile }
        requireNotNull(file) { "找不到主源码 " + fileName + "，candidates=" + candidates }
        return file.readText()
    }

    private fun codeOnly(text: String): String =
        text.replace(BLOCK_COMMENT, "").replace(LINE_COMMENT, "")

    private fun tempDir(): File = Files.createTempDirectory("mod-dex-factory").toFile()

    private fun trust(rt2: File, packageId: String = TEST_PACKAGE_ID) {
        File(rt2, ModDexGate.trustMarkerName(packageId)).writeText("")
    }

    private fun writeArtifact(
        rt2: File,
        bytes: ByteArray = PAYLOAD_BYTES,
        packageId: String = TEST_PACKAGE_ID,
    ): File {
        val dir = File(File(rt2, MOD_CODE_DIR_NAME), packageId)
        dir.mkdirs()
        val file = File(dir, sha256Hex(bytes).take(SHA_PREFIX_LENGTH) + ".mod")
        file.writeBytes(bytes)
        return file
    }

    private fun metaOf(compiledSurface: Int? = null): PackMeta =
        PackMeta(
            packageId = TEST_PACKAGE_ID,
            declaredVersion = "2.0",
            version = "2.0",
            ignored = null,
            evidence = PackMetaEvidence(compiledSurface = compiledSurface),
        )

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(separator = "") { "%02x".format(it) }

    private class FakeFactory : DexLoaderFactory {
        override fun create(packageId: String, artifactFile: File): ClassLoader =
            ClassLoader.getSystemClassLoader()
    }
}
