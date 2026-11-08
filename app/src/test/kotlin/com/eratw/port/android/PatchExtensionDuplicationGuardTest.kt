package com.eratw.port.android

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R7-D1 任务③的源码面守卫：扩展名判定的口径只许有一个权威——core-vm 装载器侧的
 * [ModPatchLoader.hasPatchExtension]（本波组长授权提为 public 的唯一可见性改动）；
 * app 模块 main 源码不允许再出现本地定义或 `.patch` 字面量判定（C5 曾复制过一份，
 * 本波已收口——这条用例钉住「以后有人再抄一份」会被测试当场抓住）。
 *
 * 判据形态（契约允许「或等价形态」）：
 * 1. app main 源码里没有 `fun hasPatchExtension` 的**定义**（引用不含 `fun ` 前缀，
 *    KDoc 的 `[hasPatchExtension]` 链接也不会误伤）；
 * 2. app main 源码里没有带引号的 `".patch"` 字符串字面量（合法引用形态是常量
 *    `ModPatchLoader.PATCH_EXTENSION`；注释里的反引号写法不带引号，不误伤）。
 */
class PatchExtensionDuplicationGuardTest {

    @Test
    fun `app 模块 main 源码零补丁扩展名判定复制`() {
        val root = appMainSourceDir()
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                val text = file.readText()
                buildList {
                    if (text.contains("fun hasPatchExtension")) {
                        add("${file.name}: hasPatchExtension 本地定义（判定权威在 core-vm ModPatchLoader）")
                    }
                    if (text.contains("\".patch\"")) {
                        add("${file.name}: .patch 字面量判定（引用 ModPatchLoader.PATCH_EXTENSION 常量）")
                    }
                }
            }
            .toList()
        assertTrue(
            "app 侧必须零复制（找到违例：$offenders）",
            offenders.isEmpty(),
        )
    }

    /**
     * app 模块 main 源码目录：Gradle 单测的工作目录按 AGP 默认是模块根（`app/`），
     * 兜一层仓库根形态（`app/` 相对路径），两处都不在就明确失败（不静默跳过）。
     */
    private fun appMainSourceDir(): File {
        val fromModule = File("src/main/kotlin")
        if (fromModule.isDirectory) return fromModule
        val fromRepoRoot = File("app/src/main/kotlin")
        if (fromRepoRoot.isDirectory) return fromRepoRoot
        throw IllegalStateException(
            "找不到 app main 源码目录（工作目录 = ${File(".").absolutePath}）——" +
                "守卫用例无法检查，视为红而不是跳过",
        )
    }
}