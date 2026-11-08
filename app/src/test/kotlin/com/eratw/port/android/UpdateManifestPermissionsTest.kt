package com.eratw.port.android

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 自动更新那条链的 **manifest 权限面**（2026-11-03）。
 *
 * 起因是玩家反馈「我用 wifi，提示更新还会说不是 wifi」。根因不在判据代码里：
 * `UpdateManager.isUnmetered` 走的 `ConnectivityManager.getActiveNetwork()` 与
 * `getActiveNetworkInfo()`，官方 apidoc 都写明 Requires `ACCESS_NETWORK_STATE`，
 * 而 app 的 manifest 从来没有这一行 —— 真机上这两个调用拿不到默认网络，
 * `isUnmetered` 只能交「判不了」，`UpdateUi` 的两处缺省又一致往蜂窝倒
 * （弹窗正文「当前未连接 Wi-Fi」＋二次确认「当前为移动数据网络」），
 * 于是连着 Wi-Fi 的玩家被说成蜂窝。形状与本仓 2026-10-27（缺 INTERNET）同一条：
 * **少声明一行权限 = 功能在真机上静默失效，而编译与出包体检全绿。**
 *
 * 这里钉三件事，全是纯 JVM 读文本：
 * 1. [更新链需要的权限一项都不能少] —— 把 src manifest 的 `uses-permission` 读成集合再点名，
 *    不用子串（`FOREGROUND_SERVICE` 是 `FOREGROUND_SERVICE_DATA_SYNC` 的前缀，子串断言会假绿）；
 * 2. [用了网络状态 API 就必须声明这条权限] —— 判据与权限**耦起来**：只要 app main 源码里还在调
 *    「要这条权限才给用」的那几个入口，manifest 就得有它。第 1 条只管今天的清单，
 *    这一条防的是「以后再加一处网络状态调用却没人补权限」；
 * 3. [空默认网络不许喂进 getNetworkCapabilities] —— `getNetworkCapabilities(null)` 抛的是 NPE
 *    而不是返回 null，可空的默认网络必须先接住再交一个干净的 false。
 *
 * 与 `devtools/pack_release.py` 的合并后 manifest 体检是两面：那边抓「合并进包的最终形状」，
 * 这边抓「我们自己的声明」，且这边跑在定向单测闸里，不必等到出包才发现。
 */
class UpdateManifestPermissionsTest {

    // ------------------------------------------------------------------
    // 1. 权限点名清单
    // ------------------------------------------------------------------

    @Test
    fun `更新链需要的权限一项都不能少`() {
        val declared = declaredPermissions()
        for (needed in REQUIRED_PERMISSIONS) {
            assertTrue("manifest 缺 $needed（已声明：${declared.sorted().joinToString()}）", declared.contains(needed))
        }
    }

    // ------------------------------------------------------------------
    // 2. 判据与权限耦合
    // ------------------------------------------------------------------

    @Test
    fun `用了网络状态 API 就必须声明这条权限`() {
        val offenders = mainSourceFiles()
            .filter { file -> NETWORK_STATE_APIS.any { token -> codeLines(file).contains(token) } }
            .map { it.name }
        if (offenders.isEmpty()) return
        assertTrue(
            "这些文件在调「要 ACCESS_NETWORK_STATE 才给用」的 ConnectivityManager 入口（$offenders），" +
                "但 manifest 没声明该权限：真机上判不出网络类型，连着 Wi-Fi 的玩家会被提示成蜂窝。" +
                " 修法：在 app/src/main/AndroidManifest.xml 加一行 " +
                "<uses-permission android:name=\"android.permission.ACCESS_NETWORK_STATE\"/>",
            declaredPermissions().contains(ACCESS_NETWORK_STATE),
        )
    }

    // ------------------------------------------------------------------
    // 3. 空默认网络的接法
    // ------------------------------------------------------------------

    @Test
    fun `空默认网络不许喂进 getNetworkCapabilities`() {
        val text = codeLines(File(packageDir(), "UpdateManager.kt"))
        val offenders = DIRECT_NULLABLE_FEEDS.filter { text.contains(it) }
        assertTrue(
            "getActiveNetwork() 在没有默认网络（或被系统挡掉）时返回 null，而 getNetworkCapabilities(null) " +
                "抛 NPE 而不是返回 null —— 可空的默认网络要先接到局部变量再判空。" +
                "发现直接喂进去的写法：$offenders",
            offenders.isEmpty(),
        )
    }

    // ------------------------------------------------------------------
    // 取文本：工作目录随 gradle 版本/入口变，候选路径都试（同 StrictModeGuardTest）
    // ------------------------------------------------------------------

    private val moduleRootCandidates = listOf(
        File("."),
        File("../app"),
        File("app"),
    )

    /** 已存在的模块根（`app/`），三个候选按 gradle 入口不同各试一次。 */
    private fun moduleRoot(): File = moduleRootCandidates.firstOrNull { File(it, "src/main/kotlin").isDirectory }
        ?: error("找不到 app/src/main/kotlin（试了 ${moduleRootCandidates.joinToString { it.path }}）")

    private fun packageDir(): File = File(moduleRoot(), "src/main/kotlin/com/eratw/port/android")

    private fun mainSourceFiles(): List<File> =
        File(moduleRoot(), "src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /** 取代码行：KDoc 与行注释不参与（讨论某条 API 时不许把判式带红）。 */
    private fun codeLines(file: File): String {
        require(file.isFile) { "找不到 ${file.path}" }
        return file.readText(Charsets.UTF_8).lines().filterNot { line ->
            val t = line.trim()
            t.startsWith("*") || t.startsWith("/*") || t.startsWith("//")
        }.joinToString("\n")
    }

    /** src manifest 里全部 `uses-permission` 的短名（去掉 `android.permission.` 前缀）。 */
    private fun declaredPermissions(): Set<String> {
        val manifest = File(moduleRoot(), "src/main/AndroidManifest.xml")
        require(manifest.isFile) { "找不到 ${manifest.path}" }
        return USES_PERMISSION.findAll(manifest.readText(Charsets.UTF_8))
            .map { it.groupValues[1].removePrefix("android.permission.") }
            .toSet()
    }

    companion object {
        private val USES_PERMISSION = Regex("""<uses-permission\s+android:name="([^"]+)""")

        private const val ACCESS_NETWORK_STATE = "ACCESS_NETWORK_STATE"

        /** 更新＋上报链跑起来缺一不可的权限（每条都有真机静默失效的前科或风险）。 */
        private val REQUIRED_PERMISSIONS = listOf(
            "INTERNET",
            ACCESS_NETWORK_STATE,
            "REQUEST_INSTALL_PACKAGES",
            "FOREGROUND_SERVICE",
            "FOREGROUND_SERVICE_DATA_SYNC",
            "POST_NOTIFICATIONS",
        )

        /** apidoc 写明 Requires ACCESS_NETWORK_STATE 的入口，按源码里的实际写法点名。 */
        private val NETWORK_STATE_APIS = listOf(
            "activeNetwork",
            "activeNetworkInfo",
            "getNetworkCapabilities(",
        )

        /** 把可空的默认网络直接当实参的写法（0.2.36 及之前就是这么写的，交不出干净的 false）。 */
        private val DIRECT_NULLABLE_FEEDS = listOf(
            "getNetworkCapabilities(manager.activeNetwork",
            "getNetworkCapabilities(this.activeNetwork",
        )
    }
}
