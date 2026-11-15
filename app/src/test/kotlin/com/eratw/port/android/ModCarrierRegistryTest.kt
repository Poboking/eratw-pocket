package com.eratw.port.android

import com.eratw.port.modfamily.FamilyDeclarations
import com.eratw.port.modlivingworld.CharacterDeclarations
import com.eratw.port.modlivingworld.EventDeclarations
import com.eratw.port.modlivingworld.MemoryDeclarations as LwMemoryDeclarations
import com.eratw.port.modlivingworld.RelationshipDeclarations
import com.eratw.port.modmemory.MemoryDeclarations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * RT2-B② Kotlin mod **载体注册表**（`ModCarrierRegistry`）的定向钉。
 *
 * 三层：① 注册表行为（名单、packageId 一致性、逐会话新实例）；② GameHost 接线钉
 * （惰性取：总闸关不碰注册表——「关态一个对象都不建」的 B1 判据延伸到载体实例）；
 * ③ ModRuntimeAssembly KDoc 收账钉（「生产为空表」的旧口径不许复活）。
 * 源码钉口径照 `BusyIndicatorWiringTest`。
 */
class ModCarrierRegistryTest {

    private fun sourceOf(name: String): String {
        val candidates = listOf(
            File("src/main/kotlin/com/eratw/port/android/$name"),
            File("../app/src/main/kotlin/com/eratw/port/android/$name"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 $name（试了 ${candidates.joinToString { it.path }}）")
        return file.readText()
    }

    // ---- 注册表行为 ----

    @Test
    fun `名单非空且 packageId 与载体声明常量一致`() {
        val providers = ModCarrierRegistry.providers()
        assertTrue("注册表不许是空表（空表 = B② 等于没落）", providers.isNotEmpty())
        for ((packageId, mod) in providers) {
            assertTrue("packageId 不许空白：$packageId", packageId.isNotBlank())
            assertTrue("载体实例不许为 null（Pair 第二位）", mod != null)
        }
        assertTrue(
            "eratw.family 载体必须在册（:mod-family 的 F1 载体是第一个登记项）",
            providers.any { it.first == FamilyDeclarations.PACKAGE_ID },
        )
        assertTrue(
            "eratw.memory 载体必须在册（:mod-memory 的 M9-1 记忆 Mod 参考实现）",
            providers.any { it.first == MemoryDeclarations.PACKAGE_ID },
        )
        // M10-1：Living World Demo 四载体（eratw.lw.*，全部定义在 :mod-livingworld 一个模块内）。
        assertTrue(
            "eratw.lw.character 载体必须在册（:mod-livingworld 的角色 Mod）",
            providers.any { it.first == CharacterDeclarations.PACKAGE_ID },
        )
        assertTrue(
            "eratw.lw.relationship 载体必须在册（:mod-livingworld 的关系 Mod）",
            providers.any { it.first == RelationshipDeclarations.PACKAGE_ID },
        )
        assertTrue(
            "eratw.lw.memory 载体必须在册（:mod-livingworld 的 Living World 记忆 Mod）",
            providers.any { it.first == LwMemoryDeclarations.PACKAGE_ID },
        )
        assertTrue(
            "eratw.lw.event 载体必须在册（:mod-livingworld 的社交事件 Mod）",
            providers.any { it.first == EventDeclarations.PACKAGE_ID },
        )
    }

    @Test
    fun `逐次调用取新实例 EraMod 不跨会话共享`() {
        val a = ModCarrierRegistry.providers()
        val b = ModCarrierRegistry.providers()
        assertEquals("名单内容逐项一致（静态点名，不是探测）", a.map { it.first }, b.map { it.first })
        for (i in a.indices) {
            assertNotSame("载体实例必须逐会话新建：${a[i].first}", a[i].second, b[i].second)
        }
    }

    // ---- GameHost 接线钉 ----

    @Test
    fun `装配点惰性取注册表 总闸关不建载体对象`() {
        val host = sourceOf("GameHost.kt")
        assertTrue(
            "attach 调用点必须经注册表取载体，且用 if (modRuntime) 惰性守门" +
                "（关态一个对象都不建的 B1 判据延伸到载体实例）",
            host.contains("modProviders = if (modRuntime) ModCarrierRegistry.providers() else emptyList(),"),
        )
    }

    @Test
    fun `注册表不许自己摸总闸或偏好（读数单路纪律）`() {
        val registry = sourceOf("ModCarrierRegistry.kt")
        assertTrue(
            "注册表是纯点名表，不许读 UiPrefs（总闸读点只有 MainActivity 一处，B① 口径）",
            !registry.contains("UiPrefs."),
        )
    }

    // ---- 旧口径收账钉 ----

    @Test
    fun `装配文件不许再写 生产为空表 的旧口径`() {
        val assembly = sourceOf("ModRuntimeAssembly.kt")
        assertTrue(
            "B② 已接线，ModRuntimeAssembly 的 KDoc 不许再写「本波生产为空表 / 供应商注册属需组长接线」",
            !assembly.contains("本波为空表") && !assembly.contains("供应商注册属需组长接线"),
        )
    }
}
