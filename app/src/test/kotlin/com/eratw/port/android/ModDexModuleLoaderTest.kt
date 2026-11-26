package com.eratw.port.android

import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.ModContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private const val TEST_PACKAGE_ID: String = "eratw.dexmodule"
private const val TEST_SHA256: String = "0123456789abcdef"

/**
 * ADR-013 S4c 前半「代码包 → EraMod 实例」的定向用例：五个失败面各自独立结果值、
 * entrypoint 形状闸（非法不 loadClass）、单行日志、失败不携带 ClassLoader 引用。
 */
class ModDexModuleLoaderTest {

    // ------------------------------------------------------------------
    // 成功面
    // ------------------------------------------------------------------

    @Test
    fun `合法入口类装出 EraMod 实例且携带原 ClassLoader`() {
        val lines = mutableListOf<String>()
        val loader = RecordingLoader(ModDexModuleLoaderTest::class.java.classLoader)
        val outcome = ModDexModuleLoader { lines += it }.load(loadedOutcome(loader), OkEraMod::class.java.name)
        val loaded = cast(outcome, ModuleLoadOutcome.Loaded::class.java)
        assertEquals(TEST_PACKAGE_ID, loaded.packageId)
        assertEquals(OkEraMod::class.java.name, loaded.entrypoint)
        assertTrue("实例必须是包内 EraMod 实现", loaded.mod is OkEraMod)
        assertSame("成功结果必须携带装载链给出的那个 ClassLoader", loader, loaded.loader)
        assertEquals(listOf(OkEraMod::class.java.name), loader.requested)
        assertEquals("一次装载恰好一行日志", 1, lines.size)
        assertTrue(lines.single().contains(TEST_PACKAGE_ID))
        assertTrue(lines.single().contains(OkEraMod::class.java.name))
        assertTrue(lines.single().contains("outcome=loaded"))
    }

    // ------------------------------------------------------------------
    // 失败面：五个独立结果值
    // ------------------------------------------------------------------

    @Test
    fun `类找不到收敛为 ClassNotFound 不向上抛`() {
        val loader = RecordingLoader(null)
        val outcome = ModDexModuleLoader().load(loadedOutcome(loader), "com.eratw.port.absent.NoSuchMod")
        val failed = cast(outcome, ModuleLoadOutcome.ClassNotFound::class.java)
        assertTrue("detail 要带真实异常类名：" + failed.detail, failed.detail.contains("ClassNotFoundException"))
        assertEquals("入口类名必须原样交给 loadClass", listOf("com.eratw.port.absent.NoSuchMod"), loader.requested)
    }

    @Test
    fun `无无参构造收敛为 NoDefaultConstructor`() {
        val loader = RecordingLoader(ModDexModuleLoaderTest::class.java.classLoader)
        val outcome = ModDexModuleLoader().load(loadedOutcome(loader), OnlyArgEraMod::class.java.name)
        val failed = cast(outcome, ModuleLoadOutcome.NoDefaultConstructor::class.java)
        assertTrue("detail 要带 NoSuchMethodException：" + failed.detail, failed.detail.contains("NoSuchMethodException"))
    }

    @Test
    fun `实例不是 EraMod 收敛为 NotAnEraMod`() {
        val loader = RecordingLoader(ModDexModuleLoaderTest::class.java.classLoader)
        val outcome = ModDexModuleLoader().load(loadedOutcome(loader), PlainMod::class.java.name)
        val failed = cast(outcome, ModuleLoadOutcome.NotAnEraMod::class.java)
        assertTrue("detail 要带实际类名：" + failed.detail, failed.detail.contains(PlainMod::class.java.name))
    }

    @Test
    fun `构造过程抛异常收敛为 ConstructorFailed 且 detail 带异常类名与 message`() {
        val loader = RecordingLoader(ModDexModuleLoaderTest::class.java.classLoader)
        val outcome = ModDexModuleLoader().load(loadedOutcome(loader), ConstructorBoom::class.java.name)
        val failed = cast(outcome, ModuleLoadOutcome.ConstructorFailed::class.java)
        assertTrue("detail 要带构造器真实异常类名：" + failed.detail, failed.detail.contains("IllegalStateException"))
        assertTrue("detail 要带异常 message：" + failed.detail, failed.detail.contains("boom"))
    }

    @Test
    fun `静态初始化失败收敛为 StaticInitializationFailed`() {
        val loader = RecordingLoader(ModDexModuleLoaderTest::class.java.classLoader)
        val outcome = ModDexModuleLoader().load(loadedOutcome(loader), StaticInitBoom::class.java.name)
        val failed = cast(outcome, ModuleLoadOutcome.StaticInitializationFailed::class.java)
        assertTrue(
            "detail 要带 ExceptionInInitializerError：" + failed.detail,
            failed.detail.contains("ExceptionInInitializerError"),
        )
    }

    // ------------------------------------------------------------------
    // entrypoint 形状闸
    // ------------------------------------------------------------------

    @Test
    fun `非法 entrypoint 直接拒掉且一次 loadClass 都不发`() {
        val invalidEntrypoints = listOf(
            "",
            " ",
            "com.example..Mod",
            "1com.example.Mod",
            ".com.example.Mod",
            "com.example.Mod ",
            "com.example.Mod/x",
        )
        val loader = RecordingLoader(ModDexModuleLoaderTest::class.java.classLoader)
        for (entrypoint in invalidEntrypoints) {
            val outcome = ModDexModuleLoader().load(loadedOutcome(loader), entrypoint)
            assertTrue(
                "entrypoint=<$entrypoint> 期望 InvalidEntrypoint，实际：$outcome",
                outcome is ModuleLoadOutcome.InvalidEntrypoint,
            )
        }
        assertTrue("非法 entrypoint 不许触发类加载", loader.requested.isEmpty())
    }

    @Test
    fun `非法 entrypoint 也只记一行日志`() {
        val lines = mutableListOf<String>()
        val loader = RecordingLoader(ModDexModuleLoaderTest::class.java.classLoader)
        ModDexModuleLoader { lines += it }.load(loadedOutcome(loader), "com.example..Mod")
        assertEquals("一次装载恰好一行日志", 1, lines.size)
        assertTrue(lines.single().contains(TEST_PACKAGE_ID))
        assertTrue(lines.single().contains("outcome=invalid-entrypoint"))
    }

    // ------------------------------------------------------------------
    // 日志面
    // ------------------------------------------------------------------

    @Test
    fun `失败日志单行且带异常类名与 message`() {
        val lines = mutableListOf<String>()
        val loader = RecordingLoader(ModDexModuleLoaderTest::class.java.classLoader)
        ModDexModuleLoader { lines += it }.load(loadedOutcome(loader), ConstructorBoom::class.java.name)
        assertEquals("一次装载恰好一行日志", 1, lines.size)
        val line = lines.single()
        assertTrue("日志要带包名：" + line, line.contains(TEST_PACKAGE_ID))
        assertTrue("日志要带 entrypoint：" + line, line.contains(ConstructorBoom::class.java.name))
        assertTrue("日志要带异常类名：" + line, line.contains("IllegalStateException"))
        assertTrue("日志要带异常 message：" + line, line.contains("boom"))
        assertEquals("日志里不许出现换行", line, line.replace("\n", ""))
    }

    @Test
    fun `日志 sink 抛异常不影响装载结果`() {
        val loader = RecordingLoader(ModDexModuleLoaderTest::class.java.classLoader)
        val outcome = ModDexModuleLoader({ throw IllegalStateException("log sink 崩了") })
            .load(loadedOutcome(loader), PlainMod::class.java.name)
        assertTrue("日志面异常不得改写结果，实际：$outcome", outcome is ModuleLoadOutcome.NotAnEraMod)
    }

    // ------------------------------------------------------------------
    // 失败面不泄漏 ClassLoader
    // ------------------------------------------------------------------

    @Test
    fun `失败结果类型都不携带 ClassLoader 字段`() {
        val failureTypes = listOf(
            ModuleLoadOutcome.InvalidEntrypoint::class.java,
            ModuleLoadOutcome.ClassNotFound::class.java,
            ModuleLoadOutcome.NoDefaultConstructor::class.java,
            ModuleLoadOutcome.NotAnEraMod::class.java,
            ModuleLoadOutcome.ConstructorFailed::class.java,
            ModuleLoadOutcome.StaticInitializationFailed::class.java,
        )
        for (type in failureTypes) {
            for (field in type.declaredFields) {
                assertFalse(
                    "失败结果 " + type.simpleName + " 不得携带 ClassLoader 字段 " + field.name,
                    ClassLoader::class.java.isAssignableFrom(field.type),
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private fun loadedOutcome(loader: ClassLoader): LoadOutcome.Loaded =
        LoadOutcome.Loaded(TEST_PACKAGE_ID, TEST_SHA256, loader)

    private fun <T : ModuleLoadOutcome> cast(outcome: ModuleLoadOutcome, type: Class<T>): T {
        assertTrue("期望 " + type.simpleName + "，实际：" + outcome, type.isInstance(outcome))
        return type.cast(outcome)
    }

    /** 记录 loadClass 名称后委托 parent；parent=null 即只剩 bootstrap 可见（造 ClassNotFound）。 */
    private class RecordingLoader(parent: ClassLoader?) : ClassLoader(parent) {
        val requested = mutableListOf<String>()

        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            requested += name
            return super.loadClass(name, resolve)
        }
    }

    /** 合法入口类：从测试 classpath 由 parent ClassLoader 载入的最小 EraMod 实现。 */
    class OkEraMod : EraMod {
        override fun onLoad(context: ModContext) = Unit

        override fun onEnable() = Unit

        override fun onDisable() = Unit

        override fun onUnload() = Unit
    }

    /** 只有带参构造，验证 NoDefaultConstructor。 */
    class OnlyArgEraMod(val value: Int) : EraMod {
        override fun onLoad(context: ModContext) = Unit

        override fun onEnable() = Unit

        override fun onDisable() = Unit

        override fun onUnload() = Unit
    }

    /** 无参构造但不是 EraMod，验证类型不符。 */
    class PlainMod

    /** 构造期抛异常，验证 ConstructorFailed。 */
    class ConstructorBoom {
        init {
            error("boom")
        }
    }

    /** 静态初始化抛异常（模拟包内 EraMod 实现静态块炸掉），验证 StaticInitializationFailed。 */
    class StaticInitBoom : EraMod {
        override fun onLoad(context: ModContext) = Unit

        override fun onEnable() = Unit

        override fun onDisable() = Unit

        override fun onUnload() = Unit

        companion object {
            init {
                error("static-boom")
            }
        }
    }
}
