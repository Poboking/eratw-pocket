package com.eratw.port.android

import com.eratw.port.core.policy.DisabledErbPatchCatalog
import com.eratw.port.core.policy.ErbPatchPolicy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 补丁策略目录（PLUGIN-POLICY，2026-11-07；第二轮：ERB 补丁整体禁载）的接线钉。
 *
 * 权威面已下沉到引擎侧 [DisabledErbPatchCatalog] / [ErbPatchPolicy]，本钉守住四条消费链不断：
 * 1. 目录内容 ↔ 4.981 语料：id、可达 CALLSHARP 名（`ERB/DLC/AI对话补丁/对话调用.erb:35-36`；
 *    `:54` 的 9 参变体被 SKIPSTART 包住不编译，ErbLoader.cs:163-166）与**整体禁载路径前缀**
 *    三者对齐，且通用判定对脚本命中、对 ERH / 目录外不误伤；
 * 2. [GameHost.wireCorpusNativeMethods] 消费引擎侧全目录打运行期缺席位（纵深防御），
 *    且在装载趟裁决（prefillSharpLinks）**之前**——三段严格序不动；
 * 3. 设置页 Mod 档「补丁兼容性」卡遍历全目录、id→三语文案键全档齐备（漏映射 =
 *    [PluginPolicyCatalog.displayName] 直接炸，本钉先在 JVM 面抓住）；
 * 4. app 的 [PluginPolicyCatalog] 只是引擎数据的只读视图，不许再造第二份目录。
 *
 * 编译期「整体禁载」的**行为**判据在 erbcc 的 `ErbPatchExclusionTest`（真编译两遍对拍），
 * 不在 app 面用源码 grep 冒充。
 *
 * 源码哨兵手法与 [GameHostSharpWireTest] 同族（app 单测面无 Robolectric，实例化不了
 * GameHost/Compose，代码形状骗不了 grep）。
 */
class PluginPolicyCatalogTest {

    private fun sourceOf(name: String): String {
        val candidates = listOf(
            File("src/main/kotlin/com/eratw/port/android/$name"),
            File("../app/src/main/kotlin/com/eratw/port/android/$name"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 $name（试了 ${candidates.joinToString { it.path }}）")
        return file.readText(Charsets.UTF_8)
    }

    @Test
    fun `目录与 4_981 语料可达 CALLSHARP 名对齐`() {
        assertEquals("目录 id 清单", listOf("ai-chat"), PluginPolicyCatalog.all.map { it.id })
        assertEquals(
            "AI对话补丁可达名 = 对话调用.erb:35-36 两处（:54 变体不编译）",
            setOf("ModelSet", "Chat"),
            PluginPolicyCatalog.all.flatMap { it.nativeMethodNames }.toSet(),
        )
        assertEquals(
            "目录路径规则 = 4.981 AI对话补丁目录（相对 ERB 根，目录边界用 '/' 收尾）",
            listOf(listOf("DLC/AI对话补丁/")),
            PluginPolicyCatalog.all.map { it.erbPathPrefixes },
        )
        assertTrue(
            "引擎侧通用判定必须命中补丁脚本（整体禁载的作用面）",
            ErbPatchPolicy.isExcluded("DLC/AI对话补丁/对话调用.erb"),
        )
        assertFalse(
            "ERH 变量头必须保留（主语料 5 处静态引用，删了全量编译判红）",
            ErbPatchPolicy.isExcluded("DLC/AI对话补丁/AI返回参数.ERH"),
        )
        assertFalse(
            "目录边界不得误伤同名前缀的其它目录",
            ErbPatchPolicy.isExcluded("DLC/AI对话补丁2/别的.ERB"),
        )
        assertFalse(
            "目录外脚本不得被误禁",
            ErbPatchPolicy.isExcluded("SHOP関連/OPTION_COM_FILTER.ERB"),
        )
    }

    @Test
    fun `app 目录只是引擎侧数据的只读视图`() {
        assertSame(
            "app 不许再造第二份目录（erbcc 编译期与运行期必须共一张表）",
            DisabledErbPatchCatalog.all,
            PluginPolicyCatalog.all,
        )
        assertEquals(
            "运行期缺席位名单直接取引擎策略",
            DisabledErbPatchCatalog.all.flatMap { it.nativeMethodNames },
            ErbPatchPolicy.absentNativeMethodNames(),
        )
    }

    @Test
    fun `GameHost 消费全目录打缺席位 且先于装载趟裁决`() {
        val src = sourceOf("GameHost.kt")
        val wireAt = src.indexOf("wireCorpusNativeMethods(fresh)")
        val prefillAt = src.indexOf("prefillSharpLinks(fresh.store.aux")
        assertTrue("装配段必须有 wireCorpusNativeMethods(fresh)（三段序第一段）", wireAt >= 0)
        assertTrue("装配段必须有 prefillSharpLinks(fresh.store.aux …)（三段序第二段）", prefillAt >= 0)
        assertTrue(
            "缺席位必须在装载趟裁决之前定型（wire 调用点先于 prefillSharpLinks 扫描）",
            wireAt < prefillAt,
        )
        // 函数声明在文件前部、调用点在装配段（文件尾部），源码 index 顺序不代表执行序：
        // 这里改为切出 wireCorpusNativeMethods 的函数体单独验「消费全目录 + 打缺席位」。
        val declAt = src.indexOf("private fun wireCorpusNativeMethods(")
        assertTrue("GameHost 必须声明 wireCorpusNativeMethods", declAt >= 0)
        val nextKDoc = src.indexOf("\n    /**", declAt)
        val body = src.substring(declAt, if (nextKDoc > declAt) nextKDoc else src.length)
        assertTrue(
            "装配位必须消费引擎侧全目录（ErbPatchPolicy.absentNativeMethodNames()）",
            body.contains("ErbPatchPolicy.absentNativeMethodNames()"),
        )
        assertTrue(
            "装配位必须把目录内名字打进策略缺席位（registry.markPolicyAbsent）",
            body.contains("registry.markPolicyAbsent(absent)"),
        )
        assertTrue(
            "装配位不得回炉造桩（本版不往注册表放任何名字）",
            !body.contains("registry.register("),
        )
    }

    @Test
    fun `设置页卡片遍历全目录 且三语展示名全档齐备`() {
        val src = sourceOf("SettingsActivity.kt")
        assertTrue("Mod 档必须有补丁兼容性卡（PluginPolicyCard）", src.contains("PluginPolicyCard(text = text)"))
        assertTrue("卡片必须遍历全目录（PluginPolicyCatalog.all.forEach）", src.contains("PluginPolicyCatalog.all.forEach"))
        assertTrue(
            "卡片必须按设置页语言取域文案表（PluginPolicyCopy.of(text.lang)）",
            src.contains("PluginPolicyCopy.of(text.lang)"),
        )
        for (lang in SettingsLanguage.entries) {
            val copy = PluginPolicyCopy.of(lang)
            PluginPolicyCatalog.all.forEach { plugin ->
                val name = PluginPolicyCatalog.displayName(plugin.id, copy)
                assertTrue("$lang 档 ${plugin.id} 展示名为空", name.isNotBlank())
                assertTrue("$lang 档 ${plugin.id} 展示名不得回退裸 id", name != plugin.id)
            }
            assertTrue("$lang 档禁用状态文案为空", copy.disabled.isNotBlank())
            assertTrue("$lang 档卡片标题为空", copy.title.isNotBlank())
            assertTrue("$lang 档卡片说明为空", copy.intro.isNotBlank())
        }
    }
}
