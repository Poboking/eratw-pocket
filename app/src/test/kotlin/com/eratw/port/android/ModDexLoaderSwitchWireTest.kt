package com.eratw.port.android

import android.content.SharedPreferences
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 路 B dex 装载总闸的**玩家面开关接线哨兵**（task-191）。
 *
 * 背景：闸本体（[UiPrefs.modDexLoaderEnabled] + internal 读写层）早已落码，但没有任何生产写点
 * ⇒ 路 B 缺省关且玩家无 UI 可开。本类钉住「开关真的接到玩家面 / 开启必须先过二次确认 /
 * 取消零写点 / 写盘失败不静默 / 同页读数即时刷新」这几件事，删掉任一条当场红。
 *
 * 不测 compose 行为（`SettingsActivity` 挂在 Android 运行时上，JVM 单测实例化不了），
 * 所以分两半：① 纯函数与偏好读写层做行为断言；② 源码形状钉住接线点（照
 * [ModRuntimeSwitchWiringTest] 的既有范式）。
 */
class ModDexLoaderSwitchWireTest {

    private fun sourceOf(name: String): String {
        val candidates = listOf(
            File("src/main/kotlin/com/eratw/port/android/$name"),
            File("../app/src/main/kotlin/com/eratw/port/android/$name"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 $name（试了 ${candidates.joinToString { it.path }}）")
        return file.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }

    /** 只截「第三方代码包概览」卡本体：确认宿主与开关行都在这一块里。 */
    private fun cardSource(): String =
        sourceOf("SettingsActivity.kt")
            .substringAfter("private fun ModCodeOverviewCard(")
            .substringBefore("/** 单个代码包读数")

    @Test
    fun `缺省仍是关且卡内读的是总闸`() {
        assertFalse("总闸缺省必须仍是关", UiPrefs.DEFAULT_MOD_DEX_LOADER)
        val card = cardSource()
        assertTrue(
            "卡内必须经 UiPrefs 读总闸（不许另起键）",
            card.contains("UiPrefs.modDexLoaderEnabled(context)"),
        )
        assertFalse(
            "开关只写偏好，不在本会话内发装配请求（装配发生在下次进入游戏）",
            card.contains("AndroidDexLoaderFactory") || card.contains("ModRuntimeHost"),
        )
    }

    @Test
    fun `开启必须先过二次确认且关方向直接落盘`() {
        assertEquals(
            "开启方向必须先弹确认",
            ModDexLoaderToggleAction.ASK_CONFIRM,
            modDexLoaderToggleAction(requested = true, confirmed = null),
        )
        assertEquals(
            "关方向直接落盘（降权限动作不再拦一道）",
            ModDexLoaderToggleAction.WRITE,
            modDexLoaderToggleAction(requested = false, confirmed = null),
        )
        assertEquals(
            "确认后写开",
            ModDexLoaderToggleAction.WRITE,
            modDexLoaderToggleAction(requested = true, confirmed = true),
        )
    }

    @Test
    fun `取消确认不写盘`() {
        assertEquals(
            "玩家取消确认必须零写点",
            ModDexLoaderToggleAction.IGNORE,
            modDexLoaderToggleAction(requested = true, confirmed = false),
        )
        val card = cardSource()
        val dismissAt = card.indexOf("onDismiss = { confirmEnable = false }")
        assertTrue("确认宿主必须接 onDismiss = 只清 flag", dismissAt > 0)
        val confirmAt = card.indexOf("onConfirm = {", startIndex = dismissAt)
        assertTrue("确认宿主必须接 onConfirm", confirmAt > dismissAt)
        assertFalse(
            "取消路径（onDismiss 到 onConfirm 之间）不许出现写点",
            card.substring(dismissAt, confirmAt).contains("writeGate("),
        )
        assertTrue(
            "确认路径必须写盘",
            card.substring(confirmAt).contains("writeGate(true)"),
        )
    }

    @Test
    fun `写读回环读出刚写进去的值`() {
        val sp = FakeSharedPreferences()
        assertTrue("首次写 true 必须真正落盘", UiPrefs.persistModDexLoader(sp, true))
        assertTrue("写后读回必须为 true", UiPrefs.readModDexLoader(sp))
        assertTrue("再写 false 也必须真正落盘", UiPrefs.persistModDexLoader(sp, false))
        assertFalse("写后读回必须为 false", UiPrefs.readModDexLoader(sp))
    }

    @Test
    fun `写盘没生效时读回校验给假`() {
        val sp = FakeSharedPreferences(dropWrites = true)
        assertFalse(
            "写入被丢弃时读回校验必须为 false（UI 据此给红字）",
            UiPrefs.persistModDexLoader(sp, true),
        )
        assertFalse("坏桩里盘上始终是缺省关", UiPrefs.readModDexLoader(sp))
    }

    @Test
    fun `写盘失败给玩家红字而非静默`() {
        val card = cardSource()
        assertTrue(
            "写盘点必须用带读回校验的 setModDexLoader",
            card.contains("UiPrefs.setModDexLoader(context, enabled)"),
        )
        assertTrue("失败时必须有红字分支", card.contains("if (writeFailed) {"))
        assertTrue(
            "红字必须取真文案（不许内联字面量）",
            card.contains("text.modCodeLoaderWriteFailed"),
        )
        assertTrue(
            "红字必须 Warn 色（字阶走 token，与全壳小字同拍）",
            card.contains(
                "Text(text.modCodeLoaderWriteFailed, color = UiColors.Warn, style = ShellType.Caption)",
            ),
        )
    }

    @Test
    fun `总闸翻转后同页重扫`() {
        val card = cardSource()
        assertTrue(
            "overview 的 remember 必须带上本卡自己的 gateRevision",
            card.contains("remember(context, trustRevision, gateRevision)"),
        )
        assertTrue("写盘成功后必须递增局部 revision", card.contains("gateRevision += 1"))
        assertTrue("开关行必须接到总闸读数", card.contains("checked = overview.dexLoaderEnabled"))
    }

    @Test
    fun `卡片与设置屏形参零新增`() {
        val settings = sourceOf("SettingsActivity.kt")
        assertTrue(
            "卡形参必须仍是 text + trustRevision 两个（刷新走卡内 revision）",
            Regex("""private fun ModCodeOverviewCard\(\s*text: SettingsStrings,\s*trustRevision: Int,?\s*\)""")
                .containsMatchIn(settings),
        )
        assertTrue(
            "屏内调用点必须保持原样（SettingsScreen 形参零新增）",
            settings.contains("ModCodeOverviewCard(text = text, trustRevision = modTrustRevision)"),
        )
        assertFalse("不许把总闸状态提成 SettingsScreen 形参", settings.contains("modDexLoaderEnabled: Boolean"))
    }

    /**
     * SharedPreferences 桩：默认真落盘；[dropWrites] = true 时 `apply()` 什么都不做，用来模拟
     * 「写盘没生效」——全仓写盘一律 `apply()`（UI 线程不许 `commit()`），没有返回值可判。
     */
    private class FakeSharedPreferences(private val dropWrites: Boolean = false) : SharedPreferences {
        private val map = HashMap<String, Any?>()

        override fun getAll(): MutableMap<String, *> = map

        override fun getString(key: String?, defValue: String?): String? =
            map[key] as? String ?: defValue

        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            defValues

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
                pending[key] = value
                return this
            }

            override fun putStringSet(
                key: String?,
                values: MutableSet<String>?,
            ): SharedPreferences.Editor {
                pending[key] = values
                return this
            }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                pending[key] = value
                return this
            }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                pending[key] = value
                return this
            }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                pending[key] = value
                return this
            }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                pending[key] = value
                return this
            }

            override fun remove(key: String?): SharedPreferences.Editor {
                removals.add(key)
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                pending.clear()
                removals.clear()
                map.clear()
                return this
            }

            override fun commit(): Boolean {
                flush()
                return true
            }

            override fun apply() {
                if (!dropWrites) flush()
            }

            private fun flush() {
                for (k in removals) if (k != null) map.remove(k)
                for ((k, v) in pending) if (k != null) map[k] = v
                pending.clear()
                removals.clear()
            }
        }
    }
}
