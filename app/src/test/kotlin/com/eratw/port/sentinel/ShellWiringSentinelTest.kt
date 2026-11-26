package com.eratw.port.sentinel

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 壳层接线哨兵：`GameHost` 的装配段里 **mod attach 必须先于 prefillSharpLinks**（B3 段序不回退）。
 *
 * ## 为什么这道闸住在壳层仓
 *
 * 它审的是**壳层真源**（`app/src/main/kotlin/com/eratw/port/android/GameHost.kt`）。
 * 单体仓时代它寄身在引擎仓的 core-vm 测试集里（顺带读引擎侧代码），公开分仓后
 * 那道「读壳层源码」的跨仓依赖既脆又让引擎仓 CI 恒跳过。判据的**主语是壳层**，
 * 归壳层仓才自然：壳层真源在本地，引擎仓不在场也不影响这道闸成立。
 *
 * ## 判据
 *
 * 防回退：覆盖要在装载趟裁决前生效 —— `ModRuntimeAssembly.attach` 必须先于
 * `prefillSharpLinks`（B4 哨兵同款源码扫，读生产装配点真序）。否则常量名
 * CALLSHARP 行绑死宿主本体、覆盖永不生效。
 */
class CallSharpOverrideWiringSentinelTest {

    @Test
    fun `GameHost 装配段 mod attach 先于 prefillSharpLinks_防回退哨兵`() {
        val source = shellSource(GUARD_SOURCE).readText()
        val attachAt = source.indexOf("ModRuntimeAssembly.attach(")
        val prefillAt = source.indexOf("prefillSharpLinks(fresh.store.aux")
        assertTrue("两个装配点都必须存在", attachAt >= 0 && prefillAt >= 0)
        assertTrue(
            "B3 防回退：mod attach（覆盖进终表）必须先于 prefillSharpLinks（装载趟裁决），" +
                "否则常量名 CALLSHARP 行绑死宿主本体、覆盖永不生效（attach=$attachAt prefill=$prefillAt）",
            attachAt < prefillAt,
        )
    }

    private companion object {
        /** 待审真源：壳层宿主装配面（本仓内，仓根相对路径）。 */
        const val GUARD_SOURCE = "app/src/main/kotlin/com/eratw/port/android/GameHost.kt"
    }
}

/**
 * 壳层接线哨兵：`ModRuntimeAssembly.attach` 的灌源行必须含 **events 键**（B4 防回退）。
 *
 * ## 为什么这道闸住在壳层仓
 *
 * 同 [CallSharpOverrideWiringSentinelTest]：判据的主语是**壳层真源**
 * （`app/src/main/kotlin/com/eratw/port/android/ModRuntimeAssembly.kt`），
 * 归壳层仓后不再跨仓取源，引擎仓不在场也不影响它成立。
 *
 * ## 判据
 *
 * hook 目标键灌源是 `SymbolCatalog.bindings` 的键（全部非事件胜者）与 **events 键**
 * （语料事件名全集）的**并集** —— 只灌函数键的话，事件名拼错闸放行后 `hooks.register`
 * 仍绑不上。回退成 bindings 单源时本闸红。
 */
class HookEventKeyBindingWiringSentinelTest {

    @Test
    fun `生产装配面灌的是并集_防回退哨兵`() {
        val source = shellSource(GUARD_SOURCE).readText()
        assertTrue(
            "B4 防回退：hook 目标键灌源必须含事件键（bindings ∪ events 并集）",
            source.contains("linkCatalog?.events?.keys?.let { addAll(it) }"),
        )
    }

    private companion object {
        /** 待审真源：壳层装配面（本仓内，仓根相对路径）。 */
        const val GUARD_SOURCE = "app/src/main/kotlin/com/eratw/port/android/ModRuntimeAssembly.kt"
    }
}

/**
 * 取壳层仓内的源文件（仓根相对路径）。
 *
 * 工作目录是 app 模块根 ⇒ `../` 才是仓根。真源在**本仓**内（不开同级仓依赖），
 * 找不到即判红（不是假绿）：这两道闸的判据主语就是壳层自己。
 */
private fun shellSource(relative: String): File {
    val candidates = listOf(
        File("../$relative"),
        File(relative),
    )
    return candidates.firstOrNull { it.isFile }
        ?: error("定位不到壳层真源 $relative（试了 ${candidates.joinToString { it.path }}）：" +
            "cwd=${System.getProperty("user.dir")}")
}
