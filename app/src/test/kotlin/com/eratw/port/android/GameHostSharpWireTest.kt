package com.eratw.port.android

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CSHARP-WIRE C1/C3 的 **app 侧接线**源码哨兵（2026-11-06）。
 *
 * `GameHost` 挂在 Android 运行时上（app 的 JVM 单测面没有 Robolectric，实例化不了），
 * 与 [GameHostMeasureWireTest] 同族用**源码面**钉：装配序只存在于 `assembleSession` 的
 * 装配段里，代码形状骗不了 grep。
 *
 * 钉住三件事（断链任意一环，CALLSHARP 的装载期警告面就退化回首执行裁决 ——
 * 「该行不执行也见警告」的原版相位，ParserMediator.cs:143-158，就复刻不到了）：
 * 1. 装配段内三段严格序：`wireCorpusNativeMethods`（注册终表）→ `prefillSharpLinks`
 *    （装载趟裁决）→ 两条语句必须在其中间没有任何脚本执行缝；
 * 2. 预填吃的注册表/语句池与会话同一份（`fresh.store.aux` / `fresh.runtime.program`）；
 * 3. 警告走既有日志通道统一上屏 + 回填会话读数面（`recordSharpLoadWarnings`）。
 */
class GameHostSharpWireTest {

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
    fun `GameHost 装配段保持 注册终表再装载趟裁决 的严格序`() {
        val src = sourceOf("GameHost.kt")
        val wireAt = src.indexOf("wireCorpusNativeMethods(fresh)")
        val prefillAt = src.indexOf("prefillSharpLinks(fresh.store.aux")
        val driveAt = src.indexOf("return fresh")
        assertTrue("装配段必须有 wireCorpusNativeMethods(fresh)（C1 第一段：注册终表）", wireAt >= 0)
        assertTrue("prefillSharpLinks 必须在注册之后（C1：本地方法表在装载趟扫描时已可用）", prefillAt > wireAt)
        assertTrue("装配返回必须在预填之后（裁决先于任何脚本语句）", driveAt > prefillAt)
    }

    @Test
    fun `预填吃会话的注册表与运行期语句池并回填会话读数面`() {
        val src = sourceOf("GameHost.kt")
        assertTrue(
            "预填的 aux 必须是会话自己的 store.aux（注册表/SharpLinkTable 都挂它）",
            src.contains("prefillSharpLinks(fresh.store.aux, fresh.runtime.program)"),
        )
        assertTrue(
            "警告必须回填会话读数面（sharpLoadWarnings，取证/诊断页的读取口）",
            src.contains("fresh.recordSharpLoadWarnings(sharpWarnings)"),
        )
        assertTrue(
            "未命中警告必须走既有日志通道统一上屏（Log.w，原版 warningList 装载完一起显示的相位）",
            src.contains("for (w in sharpWarnings) Log.w(TAG"),
        )
    }
}
