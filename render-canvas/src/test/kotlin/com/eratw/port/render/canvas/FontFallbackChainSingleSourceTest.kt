package com.eratw.port.render.canvas

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `CanvasConsoleView.effectiveFamily` 的缺字回落**单源**闸（契约 §3.4）。
 *
 * ## 为什么是源码形状闸
 *
 * `CanvasConsoleView` 是 Android `View`：本模块只有纯 JVM 单测（无 Robolectric），实例化不了，
 * 而「绘制侧的链是哪里来的」是代码形状问题。口径沿用本目录既有 `CanvasContentInsetSingleSourceTest`：
 * 剥掉注释后按函数体断言。
 *
 * ## 钉的四件事
 *
 * 1. `effectiveFamily` 走 `GlyphFallback.resolve(`（与排版尺同源，链只由它组装）；
 * 2. 全文件**不含** `BundledFontFamilies.fallbacksFor(`（手拼第二份链就是本闸要防的回归）；
 * 3. API 23 守卫 `Build.VERSION.SDK_INT < Build.VERSION_CODES.M` 仍在（minSdk 21 的整链退化行为）；
 * 4. `hasGlyph(` 仍在（绘制侧自己的判据 —— 与度量侧的 sidecar 有行不同源，这是已登记的能力源差异）。
 *
 * 真机视觉（缺字落到兜底族后的字形与推进量对齐）仍是 `TODO(verify)`：本机 `adb devices` 为空，
 * 本夹具不冒充真机读数。
 */
class FontFallbackChainSingleSourceTest {

    private val src: String by lazy {
        Path.of("").toAbsolutePath()
            .resolve("src/main/kotlin/com/eratw/port/render/canvas/CanvasConsoleView.kt")
            .readText()
    }

    /**
     * 剥掉 `//` 行注释与块注释，**保留换行**（行号与源文件一一对应）。
     *
     * 必须剥：KDoc 里会提到 `BundledFontFamilies.fallbacksFor` 这类旧写法与函数名，
     * 不剥会让闸把散文当代码。写法照同目录 [CanvasContentInsetSingleSourceTest] 的
     * `stripComments` 复制一份（本模块测试源集没有共享 helper，不为一个纯函数新增跨类依赖）。
     */
    private fun stripComments(raw: String): String {
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '/' && i + 1 < raw.length) {
                val next = raw[i + 1]
                if (next == '/') {
                    while (i < raw.length && raw[i] != '\n') i++
                    continue
                }
                if (next == '*') {
                    i += 2
                    while (i + 1 < raw.length && !(raw[i] == '*' && raw[i + 1] == '/')) {
                        if (raw[i] == '\n') sb.append('\n')
                        i++
                    }
                    i += 2
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private val code: String by lazy { stripComments(src) }

    /** 取某个 `fun xxx(` 的函数体（按大括号配平；与既有 AuditTest 同一把尺）。 */
    private fun bodyOf(signature: String): String {
        val start = code.indexOf(signature)
        assertTrue(start >= 0, "代码里找不到 $signature")
        val open = code.indexOf('{', start)
        assertTrue(open >= 0, "$signature 后没有函数体")
        var depth = 0
        var i = open
        while (i < code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(open + 1, i)
                }
                else -> {}
            }
            i++
        }
        error("$signature 的函数体没有配平")
    }

    @Test
    fun `绘制侧的缺字回落与排版尺同源且只有一份链`() {
        val body = bodyOf("private fun effectiveFamily(")
        assertTrue(
            body.contains("GlyphFallback.resolve("),
            "effectiveFamily 必须走 GlyphFallback.resolve（链只由 GlyphFallback 组装）",
        )
        assertTrue(
            !body.contains("BundledFontFamilies.fallbacksFor("),
            "effectiveFamily 不许再手拼回落链（listOf/for 循环遍历 fallbacksFor）",
        )
        assertTrue(
            !code.contains("BundledFontFamilies.fallbacksFor("),
            "CanvasConsoleView 全文件都不许直接调 BundledFontFamilies.fallbacksFor（只许读 GlyphFallback）",
        )
        assertTrue(
            body.contains("Build.VERSION.SDK_INT < Build.VERSION_CODES.M"),
            "API 23 守卫必须留在 effectiveFamily 里（minSdk 21 整链退化为请求族 + 系统回退）",
        )
        assertTrue(
            code.contains("hasGlyph("),
            "绘制侧判据仍是 Paint.hasGlyph（能力源差异，未与 sidecar 有行统一）",
        )
    }
}
