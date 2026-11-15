package com.eratw.port.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 设置页的**窗口装配面**（2026-11-14 玩家反馈「设置页被锁死为横屏，竖着拿手机也转不过来」根治）。
 *
 * 根因不在设置页的布局判据里：2026-11-13 为「打开设置页返回后游戏重开回标题」给
 * SettingsActivity 挂上的 `Theme.Eratw.Settings`（`windowIsTranslucent=true`）是半透明窗口，
 * 框架不给这类窗口自己的方向域 —— 它按下面那只 Activity 的方向定屏，也就是 MainActivity
 * 在 onCreate 按 `UiPrefs.portraitMode` 写下的 SENSOR_LANDSCAPE / SENSOR_PORTRAIT。
 * 于是 manifest 上的 `android:screenOrientation="unspecified"` 形同虚设：游戏锁横屏时，
 * 设置页无论手机怎么拿都只能横屏。
 *
 * 那笔半透明改造的前提**已经作废**：同日 `f92a53de8` 把 VM 会话升到进程级（SessionVault），
 * `onDestroy` 只在 `isFinishing` 拆会话，系统销毁后下一实例 `reattachView` 接回同一局；
 * 进度不再依赖「MainActivity 在设置页盖着期间保持 visible」。修法因此是**撤掉**那条多余的
 * 窗口装配、恢复不透明全屏 Activity，而不是在设置页或 MainActivity 里加一层反向的方向请求
 * （半透明窗口的方向请求本就被框架忽略，API 26 那次更是直接抛
 * "Only fullscreen opaque activities can request orientation"）。
 *
 * 与本仓「少声明一行权限 = 功能在真机上静默失效」同一条失败形状：主题行加回去时编译全绿、
 * 运行时也不报错，只是设置页永远转不成竖屏。所以用纯 JVM 读文本钉死（口径同
 * `UpdateManifestPermissionsTest` / `SessionHandoverWireTest`）：
 * 1. SettingsActivity 必须声明 unspecified —— 方向跟随系统，竖屏单列、宽屏多列；
 * 2. 它解析到的主题链里不许有 windowIsTranslucent / windowIsFloating —— 不透明才有方向域；
 * 3. 游戏屏的方向锁仍在 onCreate —— 别为了「让设置页能竖屏」把游戏的方向策略一起拆掉；
 * 4. 进度安全的前提（SessionVault 接回 + 重建实例的快照初值）仍在 —— 这两条一撤，
 *    不透明设置页就把 2026-11-13 那笔玩家事故原样请回来。
 */
class SettingsWindowAssemblyTest {

    // ------------------------------------------------------------------
    // 资源文件定位（工作目录随调用方变：候选口径同 UpdateManifestPermissionsTest）
    // ------------------------------------------------------------------

    private val moduleRoot: File = arrayOf(
        File("."),
        File("../app"),
        File("app"),
    ).firstOrNull { File(it, "src/main/AndroidManifest.xml").isFile }
        ?: error("找不到 app 模块根（AndroidManifest.xml 不在任何候选目录）")

    private val manifest: File = File(moduleRoot, "src/main/AndroidManifest.xml")
    private val themes: File = File(moduleRoot, "src/main/res/values/themes.xml")
    private val srcDir: File = File(moduleRoot, "src/main/kotlin/com/eratw/port/android")

    private fun codeOf(name: String): String = File(srcDir, name).readText()

    // ------------------------------------------------------------------
    // 1. 方向域：声明跟随系统，且不许被锁成某个固定方向
    // ------------------------------------------------------------------

    @Test
    fun `设置页必须声明跟随系统方向`() {
        val declaration = activityDeclaration(manifest.readText(), SETTING_ACTIVITY)
        assertNotNull("manifest 里找不到 $SETTING_ACTIVITY 的声明", declaration)
        assertTrue(
            "$SETTING_ACTIVITY 缺 android:screenOrientation=\"unspecified\"：设置页要跟随系统转动" +
                "（竖屏单列、宽屏多列），锁成任一固定方向都会让玩家竖着拿手机也改不了竖屏。",
            declaration!!.contains("android:screenOrientation=\"unspecified\""),
        )
    }

    // ------------------------------------------------------------------
    // 2. 窗口装配：解析出的主题链必须不透明
    // ------------------------------------------------------------------

    @Test
    fun `设置页解析出的主题链不许半透明或浮动`() {
        val manifestText = manifest.readText()
        val themesText = themes.readText()
        val declaration = activityDeclaration(manifestText, SETTING_ACTIVITY)
        assertNotNull("manifest 里找不到 $SETTING_ACTIVITY 的声明", declaration)
        assertFalse(
            "$SETTING_ACTIVITY 又挂回了 Theme.Eratw.Settings：半透明窗口没有自己的方向域，" +
                "框架按下面 MainActivity 的 SENSOR_LANDSCAPE 定屏，设置页会被钉死横屏" +
                "（2026-11-14 玩家反馈）。因果全链见 manifest 的 SettingsActivity 节点。",
            declaration!!.contains("@style/Theme.Eratw.Settings"),
        )
        assertFalse(
            "themes.xml 里不该再定义 Theme.Eratw.Settings：进度安全已由 SessionVault 承担，" +
                "留着这条主题随时可能被顺手挂回去。",
            styleBody(themesText, "Theme.Eratw.Settings") != null,
        )
        // 没覆写主题时继承 application 的 Theme.Eratw；覆写过就沿 parent 链一路查上去。
        val own = Regex("""android:theme="@style/([\w.]+)"""").find(declaration)?.groupValues?.get(1)
        val themeName = own ?: Regex("""<application[^>]*android:theme="@style/([\w.]+)"[^>]*>""")
            .find(manifestText)?.groupValues?.get(1)
        assertNotNull("解析不出 $SETTING_ACTIVITY 的窗口主题（manifest / themes.xml 都被改坏了）", themeName)
        val chain = styleChain(themesText, themeName!!)
        assertTrue("主题链 $themeName 在 themes.xml 里找不到定义", chain.isNotBlank())
        assertFalse(
            "主题链 $themeName 含 windowIsTranslucent=true：半透明窗口会被框架按下面那只" +
                " Activity 的方向定屏，设置页的 unspecified 声明作废。",
            Regex("""<item\s+name="android:windowIsTranslucent">true</item>""").containsMatchIn(chain),
        )
        assertFalse(
            "主题链 $themeName 含 windowIsFloating=true：浮动/对话框窗口同样没有自己的方向域。",
            Regex("""<item\s+name="android:windowIsFloating">true</item>""").containsMatchIn(chain),
        )
    }

    // ------------------------------------------------------------------
    // 3. 游戏屏的方向策略不许被顺手拆掉
    // ------------------------------------------------------------------

    @Test
    fun `游戏屏仍按玩家偏好锁向`() {
        val main = codeOf("MainActivity.kt")
        assertTrue(
            "MainActivity.onCreate 缺按 portraitMode 写 requestedOrientation：设置页能竖屏" +
                "靠的是它自己的不透明窗口，不是把游戏屏的方向锁一起撤掉（缺省仍是横屏）。",
            main.contains("requestedOrientation = if (portraitPick)") &&
                main.contains("ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE"),
        )
    }

    // ------------------------------------------------------------------
    // 4. 不透明窗口的进度安全前提
    // ------------------------------------------------------------------

    @Test
    fun `会话接回链仍在 设置页不透明不等于丢进度`() {
        val main = codeOf("MainActivity.kt")
        assertTrue(
            "缺 SessionVault.takeoverIfAlive()：设置页不透明时 MainActivity 可能进 stopped 并被" +
                "系统销毁，没有接回链就是 2026-11-13 那笔「返回后跳回标题」原样复发。",
            main.contains("SessionVault.takeoverIfAlive()"),
        )
        assertTrue(
            "onDestroy 缺 isFinishing 分水岭：系统销毁必须只冻结不 close，会话才会留在槽里。",
            main.contains("if (isFinishing)") && main.contains("setBackgroundPaused(true)"),
        )
    }

    @Test
    fun `重建实例的进页快照以本实例装配值为初值`() {
        val main = codeOf("MainActivity.kt")
        assertTrue(
            "缺 settingsEntryPortraitMode = portraitNow：设置页盖着期间 MainActivity 若被销毁重建，" +
                "收到 onActivityResult 的新实例会拿缺省 false 误判「本次把竖屏模式改了」，" +
                "弹一次重开确认；玩家同意反而会拆掉刚接回的会话。",
            main.contains("settingsEntryPortraitMode = portraitNow"),
        )
        assertTrue(
            "进页快照的档位/留白初值也要跟本实例的装配值一致（同一条重建误判）。",
            main.contains("settingsEntryFontColumns = appliedFontColumns") &&
                main.contains("settingsEntryContentMargin = appliedContentMargin"),
        )
    }

    // ------------------------------------------------------------------
    // 剖析助手（纯文本，不引任何 Android 运行时）
    // ------------------------------------------------------------------

    /** manifest 里某个 Activity 的 `<activity …/>` 声明原文（跨行整体）。 */
    private fun activityDeclaration(text: String, name: String): String? {
        val marker = "android:name=\"$name\""
        val at = text.indexOf(marker)
        if (at < 0) return null
        val start = text.lastIndexOf("<activity", at)
        val end = text.indexOf("/>", at).takeIf { it >= 0 } ?: text.length
        return text.substring(start.coerceAtLeast(0), end)
    }

    /** themes.xml 里某个 style 的 `<style …>…</style>` 声明原文（跨行整体）。 */
    private fun styleBody(text: String, name: String): String? {
        val marker = "name=\"$name\""
        val at = text.indexOf(marker)
        if (at < 0) return null
        val start = text.lastIndexOf("<style", at)
        val end = text.indexOf("</style>", at).takeIf { it >= 0 } ?: text.length
        return text.substring(start.coerceAtLeast(0), end)
    }

    /** 沿 parent 把主题链拼起来：设置页没覆写主题时也要查到 application 那一层。 */
    private fun styleChain(themesText: String, start: String): String {
        val parts = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        var name: String? = start
        while (name != null && seen.add(name)) {
            val body = styleBody(themesText, name) ?: break
            parts.add(body)
            name = Regex("""parent="([\w.]+)"""").find(body)?.groupValues?.get(1)
        }
        return parts.joinToString("\n")
    }

    private companion object {
        const val SETTING_ACTIVITY = ".SettingsActivity"
    }
}
