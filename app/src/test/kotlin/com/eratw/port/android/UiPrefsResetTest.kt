package com.eratw.port.android

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「恢复默认设置」的壳层偏好侧钉子：`UiPrefs.resetAll` 用整表 clear，
 * 而不是逐键写回缺省 —— 两者的等价条件是**每个读取口都必须自带缺省回落**。
 * 这里把这两件事分别钉住，防止以后新增偏好键时漏给默认值参数，
 * 导致「恢复默认」把玩家推进一个既不是缺省也不是他选过的值。
 *
 * 口径照 `UiPrefsPortraitTest`：注入内存版 SharedPreferences 测试桩直测
 * internal 层，不走 Context 与 Android 实现。
 */
class UiPrefsResetTest {

    /** 内存版 SharedPreferences：apply()/commit() 同步落进 map，便于直接断言表内容。 */
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

        override fun edit(): SharedPreferences.Editor = Editor()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        private inner class Editor : SharedPreferences.Editor {
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

            override fun apply() = flush()

            private fun flush() {
                for (k in removals) if (k != null) map.remove(k)
                for ((k, v) in pending) if (k != null) map[k] = v
                pending.clear()
                removals.clear()
            }
        }
    }

    /** `UiPrefs` 当前登记的全部键：新增偏好键时请把键名补进这里，一起过一遍清空判据。 */
    private val allPrefKeys = listOf(
        "font_columns",
        "option_rail",
        "disable_bad_apple",
        "screen_fit",
        "live_reflow",
        "loading_style",
        "input_knob",
        "rail_button_size",
        "rail_layout",
        "rail_chips_per_row",
        "rail_hide_unnumbered",
        "rail_hidden_labels",
        "rail_pinned",
        "rail_history",
        "auto_update",
        "update_skipped_version_code",
        "settings_language",
        "orientation",
        "font_columns_portrait",
        "command_tray_open",
        "mod_runtime",
        "sprite_stage_collapsed",
        "rollout_bucket",
        "mirror_health",
        "update_failed_file_name",
        "update_failed_size_bytes",
        "update_failed_sha256",
        "update_failed_kind",
        "update_failed_assets_version",
        "player_stats_enabled",
        "player_stats_id",
        "player_stats_last_day",
        "tutorial_shown",
        "restore_restart_pending",
        // 2026-11-11 快捷宏：新偏好键必须登记进这张表，否则「恢复默认清掉每一个设置键」
        // 的契约对它只是碰巧成立（resetAll 走整表 clear），将来谁把它挪进 KEEP_ON_RESET
        // 或改成逐键清理，这条闸不会红。
        "quick_macros",
        // ModDX-4 A+：mod 变更一次性标记（与 restore_restart_pending 同族，但登记进了
        // KEEP_ON_RESET —— 恢复默认不改 mod 盘面，标记的提示义务不因重置消失）。
        "mod_restart_pending",
    )

    /**
     * 恢复默认**不清**的运行态键：不是设置，清掉会重抽灰度分桶、丢镜像健康与
     * 「重试失败下载」的凭证。与 `UiPrefs.KEEP_ON_RESET` 同名单（此处显式抄一份，
     * 哪边漏一个键用例就红）。
     */
    private val keptStateKeys = listOf(
        "rollout_bucket",
        "mirror_health",
        "update_failed_file_name",
        "update_failed_size_bytes",
        "update_failed_sha256",
        "update_failed_kind",
        "update_failed_assets_version",
        "player_stats_id",
        "player_stats_last_day",
        "tutorial_shown",
        "mod_restart_pending",
    )

    /** 把整张表写满非缺省值，模拟「玩家把每一项都调过一遍」。 */
    private fun FakeSharedPreferences.fillWithCustomValues(): FakeSharedPreferences {
        map["font_columns"] = 75
        map["option_rail"] = false
        map["disable_bad_apple"] = false
        map["screen_fit"] = false
        map["live_reflow"] = true
        map["loading_style"] = "modern"
        map["input_knob"] = 0.2f
        map["rail_button_size"] = "xlarge"
        map["rail_layout"] = "column"
        map["rail_chips_per_row"] = 5
        map["rail_hide_unnumbered"] = true
        map["rail_hidden_labels"] = setOf("[CONFIG]", "▽[＋] Ex_COM")
        map["rail_pinned"] = "400%5B400%5D|100"
        map["rail_history"] = "400%5B400%5D %E7%A7%BB%E5%8A%A8%5B400%5D\n100 %E7%9D%81%E5%BC%80%E7%9C%BC%E7%9D%9B"
        // 新格式：`repeat|值:名字:次数`（多条换行分条）。会话×5 一条，够验「重置后为空」。
        map["quick_macros"] = "1|31%3A%E4%BC%9A%E8%AF%9D%3A5"
        map["auto_update"] = false
        map["update_skipped_version_code"] = 42L
        map["settings_language"] = "ja"
        map["orientation"] = "portrait"
        map["font_columns_portrait"] = 90
        map["command_tray_open"] = true
        map["mod_runtime"] = true
        map["sprite_stage_collapsed"] = true
        map["rollout_bucket"] = 42
        map["mirror_health"] = "https://a.example/\t2\t1700000000000"
        map["update_failed_file_name"] = "assets-v17.zip"
        map["update_failed_size_bytes"] = 123456L
        map["update_failed_sha256"] = "deadbeef"
        map["update_failed_kind"] = "program"
        map["update_failed_assets_version"] = 17L
        map["player_stats_enabled"] = false
        map["player_stats_id"] = "a".repeat(64)
        map["player_stats_last_day"] = "2026-11-08"
        map["tutorial_shown"] = true
        map["restore_restart_pending"] = true
        map["mod_restart_pending"] = true
        return this
    }

    @Test
    fun `恢复默认只清设置键保留更新通道运行态`() {
        val sp = FakeSharedPreferences().fillWithCustomValues()
        assertEquals("用例自己先把表写满", allPrefKeys.size, sp.map.size)
        assertTrue("用例漏登记了键：${sp.map.keys - allPrefKeys.toSet()}", sp.map.keys.all { it in allPrefKeys })
        val keptBefore = keptStateKeys.associateWith { sp.map[it] }

        UiPrefs.resetAll(sp)

        assertEquals("只该留下运行态键，实际 ${sp.map}", keptStateKeys.toSet(), sp.map.keys)
        (allPrefKeys - keptStateKeys.toSet()).forEach {
            assertFalse("$it 没被清掉", sp.contains(it))
        }
        keptBefore.forEach { (key, value) ->
            assertEquals("$key 被误清或改值了", value, sp.map[key])
        }
    }

    @Test
    fun `清键后每个读取口都回落到出厂缺省`() {
        val sp = FakeSharedPreferences().fillWithCustomValues()
        UiPrefs.resetAll(sp)

        // 带 internal read 层的键：直接读层，钉住「缺键 = 缺省」
        assertEquals(UiPrefs.DEFAULT_ORIENTATION, UiPrefs.readOrientation(sp))
        assertEquals(UiPrefs.DEFAULT_FONT_COLUMNS_PORTRAIT, UiPrefs.readFontColumnsPortrait(sp))
        assertEquals(UiPrefs.DEFAULT_COMMAND_TRAY_OPEN, UiPrefs.readCommandTrayOpen(sp))
        assertEquals(UiPrefs.DEFAULT_SPRITE_STAGE_COLLAPSED, UiPrefs.readSpriteStageCollapsed(sp))
        assertFalse("LIVE-REFLOW 缺省关", UiPrefs.readLiveReflow(sp))
        // 这两条是运行态例外：恢复默认不该重抽灰度分桶、也不该清空镜像健康。
        assertEquals("灰度分桶是设备身份而非设置", 42, UiPrefs.readRolloutBucket(sp))
        assertEquals("镜像健康是运行态而非设置", 1, UiPrefs.readMirrorHealth(sp).size)
        // 匿名统计：开关是设置（回出厂缺省），编号与节流日是运行态（原样保留，否则会虚增玩家数）。
        assertEquals(UiPrefs.DEFAULT_PLAYER_STATS_ENABLED, UiPrefs.readPlayerStatsEnabled(sp))
        assertEquals("匿名编号不该被重抽", "a".repeat(64), UiPrefs.readPlayerStatsId(sp))
        assertEquals("上次心跳日不该被抹掉", "2026-11-08", UiPrefs.readPlayerStatsLastDay(sp))
        assertTrue("教程已读是引导完成态，恢复默认不该让老玩家再读一遍", UiPrefs.readTutorialShown(sp))

        // 其余键走 getter 的 fallback 形态：缺键时传入的默认值就是出厂缺省
        assertEquals(UiPrefs.DEFAULT_FONT_COLUMNS, sp.getInt("font_columns", UiPrefs.DEFAULT_FONT_COLUMNS))
        assertEquals(UiPrefs.DEFAULT_INPUT_KNOB, sp.getFloat("input_knob", UiPrefs.DEFAULT_INPUT_KNOB))
        assertEquals(UiPrefs.DEFAULT_OPTION_RAIL, sp.getBoolean("option_rail", UiPrefs.DEFAULT_OPTION_RAIL))
        assertEquals(
            UiPrefs.DEFAULT_DISABLE_BAD_APPLE,
            sp.getBoolean("disable_bad_apple", UiPrefs.DEFAULT_DISABLE_BAD_APPLE),
        )
        assertEquals(UiPrefs.DEFAULT_AUTO_UPDATE, sp.getBoolean("auto_update", UiPrefs.DEFAULT_AUTO_UPDATE))
        assertTrue(sp.getBoolean("screen_fit", true))
        assertEquals(0L, sp.getLong("update_skipped_version_code", 0L))

        // 枚举 / 语言：fromStored(null) 必须与 DEFAULT 同源
        assertEquals(SettingsLanguage.DEFAULT, SettingsLanguage.fromStored(sp.getString("settings_language", null)))
        assertEquals(
            UiPrefs.DEFAULT_RAIL_BUTTON_SIZE,
            UiPrefs.RailButtonSize.fromStored(sp.getString("rail_button_size", null)),
        )
        assertEquals(
            UiPrefs.DEFAULT_LOADING_STYLE,
            UiPrefs.LoadingStyle.fromStored(sp.getString("loading_style", null)),
        )
        // 右栏布局/每行枚数/过滤/置顶（2026-11-11）：同样缺键回落出厂缺省
        assertEquals(
            UiPrefs.DEFAULT_RAIL_LAYOUT,
            UiPrefs.RailLayoutMode.fromStored(sp.getString("rail_layout", null)),
        )
        assertEquals(
            UiPrefs.DEFAULT_RAIL_CHIPS_PER_ROW,
            sp.getInt("rail_chips_per_row", UiPrefs.DEFAULT_RAIL_CHIPS_PER_ROW)
                .coerceIn(UiPrefs.RAIL_CHIPS_PER_ROW_MIN, UiPrefs.RAIL_CHIPS_PER_ROW_MAX),
        )
        assertFalse(sp.getBoolean("rail_hide_unnumbered", false))
        assertTrue(
            "隐藏名单缺省空集",
            (sp.getStringSet("rail_hidden_labels", mutableSetOf()) ?: mutableSetOf()).isEmpty(),
        )
        assertTrue(
            "置顶列表缺省空表",
            RailOptionRules.decodeStringList(sp.getString("rail_pinned", null).orEmpty()).isEmpty(),
        )
        assertTrue(
            "历史候选缺省空表",
            RailOptionRules.decodeHistory(sp.getString("rail_history", null).orEmpty()).isEmpty(),
        )
        assertTrue(
            "快捷宏缺省空表",
            RailOptionRules.decodeQuickMacros(sp.getString("quick_macros", null).orEmpty()).isEmpty(),
        )
    }

    @Test
    fun `重复恢复不抛异常且结果一致`() {
        val sp = FakeSharedPreferences().fillWithCustomValues()
        UiPrefs.resetAll(sp)
        UiPrefs.resetAll(sp)
        assertEquals("重复恢复不该把保留键也弄丢", keptStateKeys.toSet(), sp.map.keys)
        assertEquals(UiPrefs.DEFAULT_ORIENTATION, UiPrefs.readOrientation(sp))
        assertEquals(42, UiPrefs.readRolloutBucket(sp))
    }

    @Test
    fun `恢复只动传入的那一份偏好表`() {
        val uiPrefs = FakeSharedPreferences().fillWithCustomValues()
        val other = FakeSharedPreferences().apply { map["player_save_hint"] = "keep me" }

        UiPrefs.resetAll(uiPrefs)

        assertFalse("设置键该清", uiPrefs.contains("font_columns"))
        assertEquals("运行态键该留", 42, uiPrefs.map["rollout_bucket"])
        assertEquals("别的文件不该被清", "keep me", other.map["player_save_hint"])
    }

    @Test
    fun `重启询问标记读一次即清`() {
        val sp = FakeSharedPreferences()
        assertFalse("没恢复过默认就不该弹询问", UiPrefs.consumeRestoreRestartPending(sp))

        UiPrefs.setRestoreRestartPending(sp)
        assertTrue(sp.contains("restore_restart_pending"))
        assertTrue(UiPrefs.consumeRestoreRestartPending(sp))
        assertFalse("一次性标记读完必须清掉", UiPrefs.consumeRestoreRestartPending(sp))
        assertFalse(sp.contains("restore_restart_pending"))
    }

    @Test
    fun `标记必须在清偏好之后置位否则会被一起抹掉`() {
        val sp = FakeSharedPreferences().fillWithCustomValues()
        UiPrefs.setRestoreRestartPending(sp)
        UiPrefs.resetAll(sp)
        assertFalse("先置位会被 clear 抹掉", sp.contains("restore_restart_pending"))

        UiPrefs.setRestoreRestartPending(sp)
        assertTrue("正确的顺序是 clear 之后才置位", UiPrefs.consumeRestoreRestartPending(sp))
    }
}
