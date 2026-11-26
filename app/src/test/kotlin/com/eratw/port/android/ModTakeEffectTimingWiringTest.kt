package com.eratw.port.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mod 管理区「生效时机一次性说明」（task-126 垂直切片）的**文案语义 + 源码接线**哨兵。
 *
 * 要钉住的是**三种操作三种时机**这一个事实，而不是某一句好听话：
 * - 启停 / 卸载 = 下次进入游戏生效 —— 装配期才读停用标记与载体名单
 *   （`ModEnablement.enabledOnly`，`ModRuntimeAssembly.kt:802`；`ModCarrierRegistry.providers`，:46）；
 * - 热重编 = 编完后重开一局生效 —— 重编只做磁盘原子替换（`ModRecompiler.recompile`，
 *   `ModRecompile.kt:87`），本会话已 attach 的容器不重载；
 * - per-mod 设置 = 保存后本局内下一拍生效 —— 写盘成功入队 `ModSettingsNotifyChannel`，
 *   VM 线程页边界 drain 到 `ModRuntimeAssembly.notifySettingsChanged`（:1525）；
 *   行为闭环另由 [ModSettingsHotTakeEffectEndToEndTest] 钉住，本类不重复测。
 *
 * 反例两个（都必须红）：把设置也写成「重开一局生效」；把说明写成「立即生效」。
 *
 * 源码钉与 `ModSettingsNotifyWireTest` 同族：`SettingsActivity` 挂在 Android 运行时上，JVM
 * 单测实例化不了，所以只钉「说明行真的插在 Mod 管理卡组合体里、没给卡加形参」。
 */
class ModTakeEffectTimingWiringTest {

    private fun sourceOf(name: String): String {
        val candidates = listOf(
            File("src/main/kotlin/com/eratw/port/android/$name"),
            File("../app/src/main/kotlin/com/eratw/port/android/$name"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 $name（试了 ${candidates.joinToString { it.path }}）")
        return file.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }

    private fun hintOf(lang: SettingsLanguage): String =
        SettingsStrings.of(lang).modManageTakeEffectHint

    @Test
    fun `中文说明点名三种操作各自的生效时机`() {
        val hint = hintOf(SettingsLanguage.ZH)
        for (marker in listOf("生效时机", "开关", "重新编译", "重开一局", "设置", "下一")) {
            assertTrue("中文说明缺「$marker」：三种时机没一次说清", hint.contains(marker))
        }
    }

    @Test
    fun `英文说明点名三种操作各自的生效时机`() {
        val hint = hintOf(SettingsLanguage.EN)
        for (marker in listOf("take effect", "toggling", "Recompile", "restart the game", "settings", "next turn")) {
            assertTrue("英文说明缺「$marker」：三种时机没一次说清", hint.contains(marker))
        }
    }

    @Test
    fun `日文说明点名三种操作各自的生效时机`() {
        val hint = hintOf(SettingsLanguage.JA)
        for (marker in listOf("反映タイミング", "オン・オフ", "再コンパイル", "再起動", "設定", "次のターン")) {
            assertTrue("日文说明缺「$marker」：三种时机没一次说清", hint.contains(marker))
        }
    }

    @Test
    fun `说明不承诺立即生效`() {
        // 设置只保证页边界热通知（下一个回合推进时兑现），启停/重编写的是重开一局；
        // 三语都不许出现「立即」类承诺，否则玩家会以为改完马上变。
        SettingsLanguage.entries.forEach { lang ->
            val hint = hintOf(lang)
            for (word in listOf("立即", "immediately", "instantly", "即時", "即効")) {
                assertFalse("${lang.stored} 说明承诺了「$word」", hint.contains(word))
            }
        }
    }

    @Test
    fun `per-mod 设置文案是本局下一拍生效而不是重开一局`() {
        val nextTurn = mapOf(
            SettingsLanguage.ZH to "下一",
            SettingsLanguage.EN to "next turn",
            SettingsLanguage.JA to "次のターン",
        )
        val restart = mapOf(
            SettingsLanguage.ZH to "重开一局",
            SettingsLanguage.EN to "Restart the game",
            SettingsLanguage.JA to "ゲーム再起動",
        )
        SettingsLanguage.entries.forEach { lang ->
            val strings = SettingsStrings.of(lang)
            val next = nextTurn.getValue(lang)
            val restartWord = restart.getValue(lang)
            assertTrue("${lang.stored} 设置说明没点名下一拍生效", strings.modSettingHint.contains(next))
            assertTrue("${lang.stored} 保存提示没点名下一拍生效", strings.modSettingSaved.contains(next))
            assertFalse("${lang.stored} 设置说明仍在要求重开一局", strings.modSettingHint.contains(restartWord))
            assertFalse("${lang.stored} 保存提示仍在要求重开一局", strings.modSettingSaved.contains(restartWord))
        }
    }

    @Test
    fun `启停与热重编的既有文案仍要求重开游戏`() {
        // 反例保护：修设置口径时别把「启停/重编要重开」一起改掉（那两路确实是装配期/磁盘生效）。
        val restart = mapOf(
            SettingsLanguage.ZH to "重开一局",
            SettingsLanguage.EN to "Restart the game",
            SettingsLanguage.JA to "再起動",
        )
        SettingsLanguage.entries.forEach { lang ->
            val strings = SettingsStrings.of(lang)
            val restartWord = restart.getValue(lang)
            assertTrue("${lang.stored} 启停说明丢了重开口径", strings.modManageHint.contains(restartWord))
            assertTrue(
                "${lang.stored} 重编成功提示丢了重开口径",
                strings.modManageRecompileDone("口上补丁").contains(restartWord),
            )
        }
    }

    @Test
    fun `说明行插在 Mod 管理卡内且卡签名没加形参`() {
        val src = sourceOf("SettingsActivity.kt")
        assertEquals("说明行只许在卡内渲染一次（不逐行重复）", 1, src.split("text.modManageTakeEffectHint").size - 1)
        val signature = src.substringAfter("private fun ModManageCard(").substringBefore(") {")
        assertFalse("说明行不该通过新形参传入（卡签名保持 5 参）", signature.contains("takeEffect"))
        assertEquals("ModManageCard 形参数量变了", 5, signature.count { it == ':' })
        val body = src.substringAfter("private fun ModManageCard(").substringBefore("private fun ModManageRow(")
        val versionAt = body.indexOf("text.modVersionEngine")
        val hintAt = body.indexOf("text.modManageTakeEffectHint")
        val expandAt = body.indexOf("text.modManageExpandHint")
        assertTrue("说明行必须渲染在卡内", hintAt >= 0)
        assertTrue("说明行要排在版本读数之后（玩家先看到引擎口径）", versionAt in 0 until hintAt)
        assertTrue("说明行要排在展开提示之前（不用展开就能看到）", hintAt < expandAt)
    }

    @Test
    fun `新增文案进表且三语互不重复无占位符残留`() {
        val all = SettingsLanguage.entries.map { SettingsStrings.of(it) }
        val texts = all.map { it.modManageTakeEffectHint }
        texts.forEach { assertTrue("说明有一档空文案", it.isNotEmpty()) }
        assertEquals("说明三语重复", 3, texts.distinct().size)
        texts.forEach {
            assertFalse("说明不该留占位符（它没有 fill 入口）", it.contains("{") || it.contains("}"))
        }
    }

    @Test
    fun `启停只在装配期读 marker`() {
        // 契约 ③：文案说「下次进入游戏生效」必须能落到装配期代码，否则只是嘴顺。
        val assembly = sourceOf("ModRuntimeAssembly.kt")
        assertTrue("装配期没走 enabledOnly 过滤，启停就不是装配期生效", assembly.contains("ModEnablement.enabledOnly("))
        assertTrue("attach 必须在建会话前按 enabled 早退", assembly.contains("if (!enabled) return null"))
        val registry = sourceOf("ModCarrierRegistry.kt")
        assertTrue(
            "provider 装配期必须读 disabledPackageIds 才能解释「本局不变」",
            registry.contains("ModEnablement.disabledPackageIds("),
        )
    }

    @Test
    fun `设置热生效由既有 CS 用例钉住`() {
        // 本切片不重复测热通知链路（Lane CS 的用例已证页边界兑现），这里只做存在性哨兵 + 入口形状。
        val lane = sourceOfTest("ModSettingsHotTakeEffectEndToEndTest.kt")
        assertTrue("CS 用例应钉住 notifySettingsChanged 链路", lane.contains("notifySettingsChanged"))
        val activity = sourceOf("SettingsActivity.kt")
        assertTrue("设置写盘成功后必须 enqueue 热通知", activity.contains("ModSettingsNotifyChannel.enqueue("))
    }

    private fun sourceOfTest(name: String): String {
        val candidates = listOf(
            File("src/test/kotlin/com/eratw/port/android/$name"),
            File("../app/src/test/kotlin/com/eratw/port/android/$name"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 $name（试了 ${candidates.joinToString { it.path }}）")
        return file.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }
}
