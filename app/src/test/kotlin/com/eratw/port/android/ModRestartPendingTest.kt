package com.eratw.port.android

import android.content.SharedPreferences
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ModDX-4 A+（热重载通道 A+ 增量：mod 已变更标记 + 返回游戏重启提示）的三件钉子：
 *
 * 1. **标记本体**（[UiPrefs.setModRestartPending] / [UiPrefs.consumeModRestartPending]）：
 *    置位 → 读一次即清；未置位读不到；「全部重置」不清（`KEEP_ON_RESET` 成员 —— 恢复默认
 *    不改 mod 盘面，标记的提示义务不因重置消失；键登记契约见 UiPrefsResetTest 的
 *    allPrefKeys / keptStateKeys 双名单）。
 * 2. **提示触发判据**（[MainActivity.modRestartPromptNeeded]，companion 纯函数）：
 *    从设置页返回、标记在位、且玩家没按过「保存并重启游戏」才问。
 * 3. **接线哨兵**（源码面口径，照 `QolGateWireTest`）：`MainActivity` / `SettingsActivity`
 *    挂在 Android 运行时上，app 的 JVM 单测面实例化不了 —— 删掉任一条置位点 / 消费点 /
 *    触发分支，本类当场红；恢复源码（sha 一致）即复绿。这是本波判据 5 的反向证明。
 *
 * 取消路径的裁定（提示后清除标记，不保留至生效）写在 [UiPrefs.consumeModRestartPending]
 * 的 KDoc；存储形态裁定（UiPrefs 新布尔键，不走 EXTRA_RESTART_GAME）写在
 * `UiPrefs.KEY_MOD_RESTART_PENDING` 的 KDoc，此处不复读。
 */
class ModRestartPendingTest {

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

    /** 判据 1（置位）+ 清除：一次性标记读一次即清，未置位读不到。 */
    @Test
    fun `标记置位后读一次即清`() {
        val sp = FakeSharedPreferences()
        assertFalse("没改过 mod 面不该弹询问", UiPrefs.consumeModRestartPending(sp))

        UiPrefs.setModRestartPending(sp)
        assertTrue("置位后键必须在表上", sp.contains("mod_restart_pending"))
        assertTrue("置位后第一次读必须命中", UiPrefs.consumeModRestartPending(sp))
        assertFalse("一次性标记读完必须清掉", UiPrefs.consumeModRestartPending(sp))
        assertFalse(sp.contains("mod_restart_pending"))
    }

    /** 判据 2 的取消路径裁定（提示后清除）：拒绝重开后同一标记不得再弹。 */
    @Test
    fun `取消路径不再追问`() {
        val sp = FakeSharedPreferences()
        UiPrefs.setModRestartPending(sp)
        // 返回游戏：消费命中 = 弹询问；玩家点「下次启动生效」拒绝重开（取消路径）。
        assertTrue(UiPrefs.consumeModRestartPending(sp))
        // 拒绝不做补偿置位：下一次返回不得再问（消费发生在读这一拍）。盘面变更仍在
        // （`.off` / `.erbm` 落盘），下次冷启必生效 —— 不追问的代价换不骚扰。
        assertFalse("拒绝后同一标记不得再弹", UiPrefs.consumeModRestartPending(sp))
        // 再置位（玩家又改了一处 mod）才重新有提示义务。
        UiPrefs.setModRestartPending(sp)
        assertTrue(UiPrefs.consumeModRestartPending(sp))
    }

    /** 「全部重置」不清 mod 变更标记（`KEEP_ON_RESET` 成员的行为级钉子）。 */
    @Test
    fun `全部重置保留 mod 变更标记`() {
        val sp = FakeSharedPreferences()
        UiPrefs.setModRestartPending(sp)
        UiPrefs.resetAll(sp)
        assertTrue(
            "恢复默认不改 mod 盘面，标记的提示义务不因重置消失",
            UiPrefs.consumeModRestartPending(sp),
        )
    }

    /** 判据 2 的触发条件（companion 纯函数四象限）。 */
    @Test
    fun `提示触发条件四象限`() {
        // 在位 + 从设置页返回 + 没按过「保存并重启游戏」= 问。
        assertTrue(
            MainActivity.modRestartPromptNeeded(fromSettings = true, wantsRestart = false, modChangedPending = true),
        )
        // 不是从设置页回来不问（消费点只在 onActivityResult，防御位照判据本体收口）。
        assertFalse(
            MainActivity.modRestartPromptNeeded(fromSettings = false, wantsRestart = false, modChangedPending = true),
        )
        // 标记不在位不问（没改过 mod 面 / 已经消费掉）。
        assertFalse(
            MainActivity.modRestartPromptNeeded(fromSettings = true, wantsRestart = false, modChangedPending = false),
        )
        // 玩家已按「保存并重启游戏」不问：restartGame 的新会话装配自然带上盘上的 mod 变更。
        assertFalse(
            MainActivity.modRestartPromptNeeded(fromSettings = true, wantsRestart = true, modChangedPending = true),
        )
    }

    /**
     * 反向证明（判据 5）：接线哨兵 —— 消费点、触发分支、弹窗链与两个置位点全部在位。
     * 摘掉任一条（提示触发摘除）本用例当场红；恢复源码（sha 一致）即复绿。
     * 弹窗定义若留在源码而调用点被摘，本哨兵钉住的三条调用面会缺 `confirmModChangedRestart()`
     * 与触发分支两锚；反向漏网（只留定义不留调用）由 detekt `UnusedPrivateMember` 兜住。
     */
    @Test
    fun `接线哨兵消费点触发分支与两个置位点在位`() {
        val main = sourceOf("MainActivity.kt")
        assertTrue(
            "返回游戏必须消费 mod 变更标记（摘掉 = 提示永远不触发）",
            main.contains("UiPrefs.consumeModRestartPending(this)"),
        )
        assertTrue(
            "消费后必须接触发分支（modRestartPromptNeeded 判据本体）",
            main.contains("if (modRestartPromptNeeded(fromSettings, wantsRestart, modChangedPending))"),
        )
        assertTrue(
            "触发后必须走 confirmModChangedRestart 询问（复用既有 restartGame 弹窗形态与链）",
            main.contains("confirmModChangedRestart()"),
        )

        val settings = sourceOf("SettingsActivity.kt")
        assertTrue(
            "per-mod 启停写成功（marker 落盘）必须置变更标记",
            settings.contains("UiPrefs.setModRestartPending(context)"),
        )
        assertTrue(
            "导入成功（单包 / 批量都走 postModImportEvent）必须置变更标记",
            settings.contains("UiPrefs.setModRestartPending(this)"),
        )
        assertTrue(
            "导入成功的分类判据必须在位（单包 Succeeded + 批量成功项数 > 0）",
            settings.contains("if (event is ModImportEvent.ImportSucceeded ||") &&
                settings.contains("succeededCount > 0"),
        )
    }

    /** 源码面哨兵的取源口（照 QolGateWireTest：CRLF 工作树先归一再找锚）。 */
    private fun sourceOf(name: String): String {
        val candidates = listOf(
            File("src/main/kotlin/com/eratw/port/android/$name"),
            File("../app/src/main/kotlin/com/eratw/port/android/$name"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 $name（试了 ${candidates.joinToString { it.path }}）")
        return file.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }
}
