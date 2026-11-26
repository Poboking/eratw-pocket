package com.eratw.port.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SHELL-PROFILE 构建期接线与加载页覆盖的钉子（源码级，防「参数传了没人用」）。 */
class ShellProfileWiringTest {

    private fun repoFile(relative: String): File =
        listOf(File("../$relative"), File(relative), File("../../$relative"))
            .firstOrNull { it.isFile }
            ?: error("找不到仓库文件 $relative（cwd=${System.getProperty("user.dir")}）")

    @Test
    fun `app 名称与五个品牌资源由 resValue 注入`() {
        val gradle = repoFile("app/build.gradle.kts").readText()
        assertTrue(gradle.contains("resValue(\"string\", \"app_name\", shellAppLabel)"))
        assertTrue(gradle.contains("resValue(\"string\", \"shell_game_name\", shellGameName)"))
        assertTrue(gradle.contains("resValue(\"string\", \"shell_loading_headline\", shellLoadingHeadline)"))
        assertTrue(gradle.contains("resValue(\"string\", \"shell_loading_detail\", shellLoadingDetail)"))
        assertTrue(gradle.contains("resValue(\"string\", \"shell_tutorial_mode\", shellTutorialMode)"))
        assertTrue("缺省 app 名必须仍是 eraTW", gradle.contains("?: \"eraTW\""))
        assertTrue("缺省教程必须仍是 full", gradle.contains("?: \"full\""))
        assertFalse(
            "strings.xml 不得再硬编码 app_name（duplicate resource）",
            repoFile("app/src/main/res/values/strings.xml").readText().contains("name=\"app_name\""),
        )
    }

    @Test
    fun `pack 把壳层配置投影到 Gradle 且无配置零干扰`() {
        val pack = repoFile("devtools/pack_release.py").readText()
        assertTrue(pack.contains("import shell_profile"))
        assertTrue(pack.contains("--shell-profile"))
        assertTrue(pack.contains("shell_profile.gradle_args(shell_config)"))
        val profilePy = repoFile("devtools/shell_profile.py").readText()
        assertTrue(profilePy.contains("if profile is None:"))
        assertTrue("第三方包必须带独立包名", profilePy.contains("eratwApplicationId"))
        assertTrue("独立包名必须在 Gradle 侧被消费",
            repoFile("app/build.gradle.kts").readText().contains("applicationId = appIdOverride"))
        assertTrue("第三方包版本线必须走账本", pack.contains("import game_versions"))
        assertTrue("账本抬号只能发生在出包成功之后", pack.contains("game_versions.record("))
        assertTrue(
            "CI 必须跑壳层配置闸",
            repoFile(".github/workflows/ci.yml").readText().contains("devtools/test_shell_profile.py"),
        )
        assertTrue(
            "CI 必须跑版本账本闸",
            repoFile(".github/workflows/ci.yml").readText().contains("devtools/test_game_versions.py"),
        )
    }

    @Test
    fun `教程与加载页生产入口都走配置`() {
        val main = repoFile("app/src/main/kotlin/com/eratw/port/android/MainActivity.kt").readText()
        assertTrue(main.contains("TutorialCopy.of(this@MainActivity, UiPrefs.settingsLanguage(this@MainActivity))"))
        val settings = repoFile("app/src/main/kotlin/com/eratw/port/android/SettingsActivity.kt").readText()
        assertTrue(settings.contains("TutorialCopy.of(LocalContext.current, language)"))
        val shellUi = repoFile("app/src/main/kotlin/com/eratw/port/android/ShellUi.kt").readText()
        assertEquals(
            "两版加载覆盖层都要走品牌取口",
            2,
            shellUi.split("PortraitShellCopy.ofLoading(context, UiPrefs.settingsLanguage(context))").size - 1,
        )
    }

    @Test
    fun `加载页覆盖只改三枚字段且空串回落`() {
        val base = PortraitShellCopy.of(SettingsLanguage.ZH)
        val same = PortraitShellCopy.applyLoadingBrand(base, "", "")
        assertEquals(base, same)
        val branded = PortraitShellCopy.applyLoadingBrand(base, "HEAD", "DETAIL")
        assertEquals("HEAD", branded.loadingPixelHeadline)
        assertEquals("HEAD", branded.loadingModernHeadline)
        assertEquals("DETAIL", branded.loadingPreparingData)
        assertEquals(
            base,
            branded.copy(
                loadingPixelHeadline = base.loadingPixelHeadline,
                loadingModernHeadline = base.loadingModernHeadline,
                loadingPreparingData = base.loadingPreparingData,
            ),
        )
    }

    @Test
    fun `RT2 manifest 体检接受现行三到八档`() {
        val pack = repoFile("devtools/pack_release.py").readText()
        assertTrue(pack.contains("RT2_MANIFEST_MAGICS"))
        assertTrue(pack.contains("range(3, 9)"))
        assertFalse("旧的单值 magic 判据必须撤掉", pack.contains("RT2_MANIFEST_MAGIC = "))
    }
}
