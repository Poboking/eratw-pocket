package com.eratw.port.android

import android.content.SharedPreferences
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RT2 Mod 运行时总闸（`mod_runtime`）的壳层偏好钉子 + 生产开闸读点接线钉。
 *
 * 缺省是**开**（2026-11-17 主人裁定「翻成 true，开箱可用」；RT2-GATE-OPEN 波）。
 * 关态仍必须保持「装配链一次都不进、磁盘都不碰、行为与无 mod 支持逐字节等价」这一判据
 * （关态由 `ModRuntimeAssemblyWireTest` 的反向证明①直测，不因缺省翻面而失效）。
 * 总闸已接玩家面（modswitch 波 `a6e7bff9f`：设置页开关 + 三语文案，接线形状由
 * `ModRuntimeSwitchWiringTest` 钉）；G2 波后开态走 `ModChainAssembly.assemble` 真装配链
 * （空表旁路 / 有容器真装配）。
 * 旧禁令「设置页不许出现开关」随合法化撤销，翻转成**存在钉**（摘除 = modswitch 波倒车）。
 *
 * 偏好口径照 `BusyIndicatorPrefTest`：注入内存版 SharedPreferences 桩直测 internal read/write 层；
 * 接线口径照 `BusyIndicatorWiringTest`：读 `MainActivity` / `GameHost` / `SettingsActivity`
 * 源码钉单路与越权面。
 */
class ModRuntimeGateTest {

    // ---- 内存版 SharedPreferences（照 BusyIndicatorPrefTest 的桩口径，独立一份避免跨类依赖）----

    private class FakeSharedPreferences : SharedPreferences {
        val map = HashMap<String, Any?>()

        override fun getAll(): MutableMap<String, *> = map
        override fun getString(key: String?, defValue: String?): String? =
            map[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
            @Suppress("UNCHECKED_CAST")
            return map[key] as? MutableSet<String> ?: defValues
        }
        override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            map[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor()
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        private inner class FakeEditor : SharedPreferences.Editor {
            private val pending = HashMap<String?, Any?>()
            private val removals = HashSet<String?>()

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                pending[key] = value; return this
            }
            override fun putStringSet(
                key: String?,
                values: MutableSet<String>?,
            ): SharedPreferences.Editor {
                pending[key] = values; return this
            }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                pending[key] = value; return this
            }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                pending[key] = value; return this
            }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                pending[key] = value; return this
            }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                pending[key] = value; return this
            }
            override fun remove(key: String?): SharedPreferences.Editor {
                removals.add(key); return this
            }
            override fun clear(): SharedPreferences.Editor {
                pending.clear(); removals.clear(); map.clear(); return this
            }
            override fun commit(): Boolean {
                flush(); return true
            }
            override fun apply() = flush()

            private fun flush() {
                for (k in removals) if (k != null) map.remove(k)
                for ((k, v) in pending) if (k != null) map[k] = v
                pending.clear(); removals.clear()
            }
        }
    }

    private fun sourceOf(name: String): String {
        val candidates = listOf(
            File("src/main/kotlin/com/eratw/port/android/$name"),
            File("../app/src/main/kotlin/com/eratw/port/android/$name"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 $name（试了 ${candidates.joinToString { it.path }}）")
        return file.readText()
    }

    // ---- 偏好层 ----

    @Test
    fun `缺省是开（开箱可用）`() {
        val sp = FakeSharedPreferences()
        assertTrue("缺省必须是 DEFAULT_MOD_RUNTIME（开）", UiPrefs.DEFAULT_MOD_RUNTIME)
        assertTrue("从来没写过 ⇒ 总闸开（随包 Mod 开箱装载）", UiPrefs.readModRuntime(sp))
    }

    @Test
    fun `写读往返逐值一致`() {
        val sp = FakeSharedPreferences()
        UiPrefs.writeModRuntime(sp, true)
        assertEquals(true, sp.map["mod_runtime"])
        assertTrue(UiPrefs.readModRuntime(sp))
        UiPrefs.writeModRuntime(sp, false)
        assertEquals(false, sp.map["mod_runtime"])
        assertFalse(UiPrefs.readModRuntime(sp))
    }

    @Test
    fun `键名逐字是 mod_runtime 且不碰邻居`() {
        val sp = FakeSharedPreferences()
        UiPrefs.writeEchoInput(sp, true)
        UiPrefs.writeDisableBadApple(sp, false)
        val before = sp.map.toMap()
        UiPrefs.writeModRuntime(sp, true)
        assertEquals("既有键一字不变", before, sp.map - "mod_runtime")
        assertEquals(setOf("mod_runtime"), sp.map.keys - before.keys)
    }

    @Test
    fun `resetAll 清掉总闸回到缺省开`() {
        val sp = FakeSharedPreferences()
        UiPrefs.writeModRuntime(sp, false)
        UiPrefs.resetAll(sp)
        assertTrue("clear 后读侧必须自带缺省开（恢复默认 = 回到开箱可用）", UiPrefs.readModRuntime(sp))
    }

    // ---- 接线层（生产开闸读点：单路与越权面）----

    @Test
    fun `读点只有一处 MainActivity UI 线程单路传入 宿主不许摸偏好表`() {
        val activity = sourceOf("MainActivity.kt")
        assertTrue(
            "GameHost 构造点必须显式传 modRuntime（值来自 UI 线程这一拍的 UiPrefs 读数）",
            activity.contains("modRuntime = UiPrefs.modRuntime(this),"),
        )
        val host = sourceOf("GameHost.kt")
        assertFalse(
            "VM 线程装配链不许直接读 SharedPreferences（照 disableBadApple/busyIndicator 先例）",
            host.contains("UiPrefs.modRuntime("),
        )
        assertTrue("宿主必须吃构造参数", host.contains("private val modRuntime: Boolean = false,"))
        // COMPAT-3P-MOD：透传给 attach 的是会话有效总闸 modActive（玩家总闸 ∧ 非第三方档），
        // 第三方档被强制旁路；构造参数与 MainActivity 传参原文仍保持原样（上面两条钉）。
        assertTrue(
            "装配段必须把会话有效总闸 modActive 透传给 attach",
            host.contains("private val modActive: Boolean = modRuntime && engineStamp !is EngineStampRead.Present") &&
                host.contains("enabled = modActive,"),
        )
    }

    @Test
    fun `设置页开关已接玩家面（modswitch 波合法化，摘除 = 倒车）`() {
        val settings = sourceOf("SettingsActivity.kt")
        assertTrue(
            "RT2 总闸已接玩家面（modswitch 波 a6e7bff9f）：设置页必须出现 mod_runtime 开关接线；" +
                "接线细节由 ModRuntimeSwitchWiringTest 钉",
            settings.contains("UiPrefs.modRuntime(") || settings.contains("UiPrefs.setModRuntime("),
        )
    }

    @Test
    fun `闸的缺省值在 KDoc 与常量里一致且 GameHost 不自带开启`() {
        val prefs = sourceOf("UiPrefs.kt")
        assertTrue("缺省常量必须存在且为 true", prefs.contains("const val DEFAULT_MOD_RUNTIME: Boolean = true"))
        val host = sourceOf("GameHost.kt")
        assertTrue(
            "GameHost 构造参数缺省必须仍是 false（测试哨兵：生产值由 MainActivity 单路传入）",
            host.contains("private val modRuntime: Boolean = false,"),
        )
    }

    @Test
    fun `诊断读数源只读且与设置页共用一处`() {
        val devtools = sourceOf("DevToolsHost.kt")
        assertTrue(
            "modgate 命令必须接诊断读数源",
            devtools.contains("\"modgate\" -> ModGateDiagnostics.report(view.context)"),
        )
        val settings = sourceOf("SettingsActivity.kt")
        assertTrue(
            "设置页诊断卡必须复用同一个读数源（不许第二套拼装），且与列表同一次扫描",
            settings.contains("ModGateDiagnostics.snapshot(context, modManageScan)"),
        )
        val diagnostics = sourceOf("ModGateDiagnostics.kt")
        assertTrue(
            "会话装配判定必须读 core 公开接缝 entityRuntimeOf(session.store.aux)",
            diagnostics.contains("entityRuntimeOf(session.store.aux)"),
        )
        assertFalse(
            "诊断面禁止反射读私有字段（读数源不得依赖 isMinifyEnabled=false）",
            diagnostics.contains("getDeclaredField") ||
                diagnostics.contains("modRuntimeSession") ||
                devtools.contains("modRuntimeSession"),
        )
        assertFalse(
            "读数源不许寄居在仅 debug 包的 DevToolsHost.kt 里（release 玩家面同源）",
            devtools.contains("object ModGateDiagnostics"),
        )
        assertFalse(
            "文件名/目录形状不许在 app 层另立第二份知识（不得出现 \\\".rt2\\\" / \\\"sav\\\" 字面量）",
            diagnostics.contains("\".rt2\"") || diagnostics.contains("\"sav\""),
        )
        assertTrue(
            "存档目录名必须取 core 的 SaveFolderMigration.SAV_FOLDER",
            diagnostics.contains("SaveFolderMigration.SAV_FOLDER"),
        )
        assertFalse(
            "读数源必须只读：不得出现偏好/启停的写面",
            diagnostics.contains("UiPrefs.set") ||
                diagnostics.contains("writeModRuntime") ||
                diagnostics.contains("ModEnablement.setEnabled"),
        )
    }
}
