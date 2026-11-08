package com.eratw.port.render.canvas

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import com.eratw.port.core.font.FontAdvanceTable
import com.eratw.port.core.font.FontSidecarCatalog
import com.eratw.port.core.font.FontSidecarReader
import com.eratw.port.render.font.BundledFontFamilies
import com.eratw.port.render.glyph.CellMetrics
import com.eratw.port.render.glyph.FontWidthRuler
import com.eratw.port.render.glyph.TextMetrics
import java.io.IOException

/**
 * 真机侧的**字体感知排版尺**装配口（AA 画 / 老虎机字符画错位修复的最后一环）。
 *
 * ## 为什么必须有这一层
 *
 * [FontWidthRuler] 默认接 `TextMetrics.classpath()`：那是 `:render` 这个纯 JVM 模块
 * 用 `ClassLoader.getResourceAsStream("fonts/...")` 读 `render/src/main/resources/fonts` 的口，
 * 只服务 JVM 单测与桌面工具。**Android 不把 classpath resources 打进 APK** ——
 * `render-canvas/build.gradle.kts` 的 `syncRenderFonts` 是把同一批文件拷进
 * `assets/fonts/`，真机 Typeface 也是 `Typeface.createFromAsset(context.assets, family.asset)`。
 * 若生产装配不换读取口，[FontWidthRuler] 在真机上会一路拿不到表 ⇒ 全部退化成静态
 * CP932 网格（[FontWidthRuler.compute] 的确定性退化分支），单测绿而屏上照旧错位。
 *
 * ## 读法：懒加载 + 按族名反查文件
 *
 * 不**预解析** 7 张表：每张表数千行，装配期在 UI 线程一次性 parse 会明显拖慢首帧。
 * 改成给 [FontSidecarCatalog] 挂一个 `AssetManager` 读取口，由 `chainFor` 按需 `load`：
 *
 * 1. 装配期只 `assets.list("fonts")` 一次，再逐个文件**只读前几行**取 `#family` 指令
 *    （§3 格式：`FontAdvanceTable.parse`），据此算 `FontSidecarCatalog.slugOf(family)`；
 * 2. 建 `slug -> 实际 asset 路径` 的映射。之所以不能直接按文件名猜：霞鹜文楷子集因
 *    OFL-1.1 的 RFN 条款在 `build_font_pack.py` 里改过 name 表，族名是
 *    `Eratw WenKai Mono`（slug `eratw-wenkai-mono`），文件却仍叫
 *    `lxgw-wenkai-mono-advance.txt` —— 按文件名查会静默漏掉这一族；
 * 3. 读取口把 `catalog.sidecarPath(family)` 给出的 `fonts/<slug>-advance.txt`
 *    折回真实文件，`FontAdvanceTable.parse` 出来的表由 catalog 按 `#family` 名缓存。
 *
 * 缺表 / 缺行 / 读失败一律返回 null 或交给 catalog 记原因，最终仍由
 * [FontWidthRuler.compute] 回落静态网格：任何资产缺失都只退化，绝不让一条打印语句把游戏打死。
 *
 * ## 与绘制侧「缺字回落」同构
 *
 * `FontWidthRuler.compute` 逐跳问 `requested → Sarasa → Unifont`，与
 * [CanvasConsoleView.effectiveFamily] 的 `Paint.hasGlyph` 链同一顺序；
 * 因此「这一字形实际由哪一族画出」与「按哪一族的 advance 推进」不会分家。
 */
internal object AndroidFontWidthRuler {

    private const val TAG = "EratwFontRuler"
    private const val SIDECAR_DIR = "fonts"
    private const val FAMILY_DIRECTIVE = "#family "

    /**
     * 建一只真机尺；[cellMetrics] 必须与 [CanvasConsoleView.applyMetrics] 的生效度量同批更新
     * （换字号时 `fontRuler.cellMetrics = next`，否则推进量与绘制不同尺）。
     */
    fun create(context: Context, cellMetrics: CellMetrics): FontWidthRuler {
        val assets = context.assets
        val catalog = FontSidecarCatalog(defaultFamily = BundledFontFamilies.DEFAULT_FAMILY)
        val slugToAsset = indexSidecars(assets)
        catalog.reader = FontSidecarReader { path ->
            val slug = slugOfRequestedPath(path) ?: return@FontSidecarReader null
            val assetPath = slugToAsset[slug] ?: return@FontSidecarReader null
            readAsset(assets, assetPath)
        }
        return FontWidthRuler(TextMetrics(catalog, cellMetrics), cellMetrics)
    }

    /** `fonts/<slug>-advance.txt` -> `<slug>`；不是 catalog 生成的路径就拒绝（越界守卫）。 */
    private fun slugOfRequestedPath(path: String): String? {
        val prefix = "$SIDECAR_DIR/"
        if (!path.startsWith(prefix)) return null
        val entry = path.removePrefix(prefix)
        if (entry.isEmpty() || entry.contains('/') || entry.contains('\\')) return null
        if (!entry.endsWith(FontAdvanceTable.SIDECAR_SUFFIX)) return null
        return entry.removeSuffix(FontAdvanceTable.SIDECAR_SUFFIX)
    }

    /**
     * 扫一遍 assets 里的 `*-advance.txt`，按文件内 `#family` 指令算 slug 建索引。
     * 只读前 [DIRECTIVE_SCAN_LINES] 行，不解析数据段 —— 装配期的成本是 7 次小读。
     */
    private fun indexSidecars(assets: AssetManager): Map<String, String> {
        val names = try {
            assets.list(SIDECAR_DIR)
        } catch (e: IOException) {
            Log.w(TAG, "assets/fonts 不可列举，字体尺按缺表退化：${e.message}")
            null
        }.orEmpty()
        val out = HashMap<String, String>(names.size * 2)
        for (name in names) {
            if (!name.endsWith(FontAdvanceTable.SIDECAR_SUFFIX)) continue
            val assetPath = "$SIDECAR_DIR/$name"
            val family = readFamilyDirective(assets, assetPath)
            if (family.isNullOrEmpty()) {
                Log.w(TAG, "sidecar 缺 #family 指令，跳过：$assetPath")
                continue
            }
            out[FontSidecarCatalog.slugOf(family)] = assetPath
            // 文件名口径也登记一份：族名被改过（RFN/Rename）时 catalog 按族名 slug 问的是改名后的串，
            // 而某些旧产物的文件名仍用旧名；两个键指同一份表，读数不冲突。
            out.putIfAbsent(name.removeSuffix(FontAdvanceTable.SIDECAR_SUFFIX), assetPath)
        }
        return out
    }

    /** 只取 `#family` 指令；读不到 / 文件缺失返回 null（由调用方按缺表退化）。 */
    private fun readFamilyDirective(assets: AssetManager, path: String): String? = try {
        assets.open(path).bufferedReader(Charsets.UTF_8).use { reader ->
            var scanned = 0
            while (scanned < DIRECTIVE_SCAN_LINES) {
                val line = reader.readLine() ?: break
                scanned++
                if (line.startsWith(FAMILY_DIRECTIVE)) {
                    return@use line.removePrefix(FAMILY_DIRECTIVE).trim().takeIf { it.isNotEmpty() }
                }
            }
            null
        }
    } catch (e: IOException) {
        Log.w(TAG, "sidecar 读取失败：$path（${e.message}）")
        null
    }

    private fun readAsset(assets: AssetManager, path: String): String? = try {
        assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (e: IOException) {
        Log.w(TAG, "sidecar 缺失：$path（${e.message}）")
        null
    }

    /** `#family` 恒在文件头几行（§3 指令段），不必扫数据段。 */
    private const val DIRECTIVE_SCAN_LINES = 8
}
