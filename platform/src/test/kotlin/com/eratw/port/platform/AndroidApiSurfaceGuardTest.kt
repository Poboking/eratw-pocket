package com.eratw.port.platform

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * platform 主源 Android-API 面的哨兵闸（CONTRACT-ARCH1 §2 apiacguard 路）。
 *
 * ## 这道闸存在的理由
 *
 * platform 主源里直接 `import android.` 的实现类（音频/图片/输入/存储/字形五族）在纯 JVM
 * 单测里零覆盖：android.jar 是桩（见 `platform/build.gradle.kts` 的
 * `unitTests.isReturnDefaultValues`，调用即返回缺省值），platform 也没有 Robolectric 或
 * androidTest instrumented 源集补位。这是**已知取舍**，不是疏漏：各类的可测判定都抽成了
 * 伴生纯逻辑类（AudioFocusSession、GlyphMaskMath、SoundFormatPlan、TouchGestureController、
 * KeyMacroController、GameAssetInstaller、GameStorage.selectRoot 等），由纯 JVM 单测钉死；
 * Android 调用本体归真机人工验收（各类 KDoc 以 TODO(verify) 登记）。
 *
 * 问题在流程：这个取舍此前没有机器可查的登记，后来者加第 10 个 Android-API 类不会触发
 * 任何闸。本测试把允许清单固化成代码常量并双向守卫：
 *
 * - 实测扫描结果超出清单 ⇒ 红（失败信息给登记指引）；
 * - 清单里的文件已不存在、或已不再 `import android.` ⇒ 也红（防清单腐烂）；
 * - 主源 kotlin 目录定位不到 ⇒ 直接 fail 带诊断（静默跳过 = 闸失效，禁止）。
 *
 * ## 新 Android-API 类的登记指引（与超清单失败信息同文）
 *
 * 新 Android-API 类必须：a) 抽纯逻辑伴生类 b) 在本清单登记理由 c) 平台面登记 instrumented
 * 验证通道。三步缺一不可，禁止为过闸直接把文件名塞进 [allowlist]。
 *
 * ## 扫描口径
 *
 * 扫描定位到的主源 kotlin 目录下全部 `.kt` 文件，凡存在**行首（允许前导空白）`import
 * android.` 前缀**的文件计入实测集合；集合成员用相对主源 kotlin 根、正斜杠统一的相对路径
 * 表示（清单同口径）。行内出现「import android.」字样（如 KDoc 里引用）不算：KDoc 行
 * trimStart 后以 `*` 或字母开头的注释符号在前，不满足行首前缀。
 *
 * 本测试自身纯 JVM（java.nio.file + JUnit4，无新依赖），与 platform 现有单测同一档。
 */
class AndroidApiSurfaceGuardTest {

    /**
     * 允许清单：platform 主源允许 `import android.` 的文件全集（2026-10-24 架构审查盘点，
     * CONTRACT-ARCH1 §5 九类；每项上方一行理由：为何必须碰 Android；纯逻辑伴生在哪；
     * 平台调用本体靠哪道闸验证）。
     *
     * 「本体验证通道」的统一现状：platform 无 androidTest/Robolectric，本体 = 有 SDK 机器的
     * 编译闸（`platform:testDebugUnitTest` 编译桩面 + `app:assembleDebug` 接线编译）+ 真机
     * 人工验收（KDoc TODO(verify) 登记）。
     */
    private val allowlist: Set<String> = setOf(
        // AndroidAudioFocusRequester：AudioManager 焦点申请（API 21/26 两路）平台专属、无 JVM 替代；纯逻辑在 AudioFocusSession（AudioFocusSessionTest 11 例钉状态机）；调用本体归真机人工验收（KDoc TODO(verify) 登记）。
        "com/eratw/port/platform/audio/AndroidAudioFocusRequester.kt",
        // MediaPlayerChannelDecoder：MediaPlayer 解码流与 PlaybackParams 变速是平台唯一音频解码通道；纯逻辑在 SoundFormatPlan（SoundFormatPlanTest 6 例钉判定表）；播放/变速本体归真机（KDoc TODO(verify) 登记）。
        "com/eratw/port/platform/audio/MediaPlayerChannelDecoder.kt",
        // BitmapImageDecoder：BitmapFactory 补有损 webp 解码缺口（core 的 WebpDecoder 不做 VP8 位流，原版走系统解码，spec Utils.cs:511-516）；换算是 getPixels 直落 GraphicsBuffer 的薄封装、无独立伴生类，纯 JVM 侧对应 core 的 PngDecoder/WebpDecoder（语料闸生效）；解码本体归真机（桩下 decodeFile 恒 null）。
        "com/eratw/port/platform/image/BitmapImageDecoder.kt",
        // GameImageFiles：BitmapFactory 按资源名兜底文件解码；候选名扫描与并发索引留在本类，由 GameImageFilesTest 2 例在 android.jar 桩下纯 JVM 钉住（isReturnDefaultValues）；解码本体归真机。
        "com/eratw/port/platform/image/GameImageFiles.kt",
        // ResourceCsvHost：resources 下 CSV 资源的图片头探测只能走 BitmapFactory.inJustDecodeBounds（core 侧无 JPEG/有损 webp 头部解析，原版 AppContents.cs:273-283 直接 LoadImage 取 Width/Height），解码本体复用 ImageDecoderHost 与 GCREATEFROMFILE 同通道；纯逻辑（CSV 后缀判定 + 根相对路径换算）已抽进 internal companion（isCsvName / relativeOf），由 ResourceCsvHostTest 6 例纯 JVM 钉死（含 Windows 反斜杠与尾分隔符两种形态）；桩下 decodeByteArray 恒返回 outWidth/outHeight=0 ⇒ imageSize 恒 null，头部读数本体归真机人工验收（KDoc TODO(verify)）。
        "com/eratw/port/platform/image/ResourceCsvHost.kt",
        // SpriteBitmapSource：Bitmap/LruCache/SystemClock 上屏取图口平台专属；纯逻辑在 companion packDestSize（SpriteBitmapSourcePackTest 3 例）与 render 的 SpriteAnimeClock/Timeline；取图本体归真机。
        "com/eratw/port/platform/image/SpriteBitmapSource.kt",
        // AndroidInputAdapter：KeyEvent/MotionEvent 事件源只在 Android 存在；规则全在 TouchGestureController 与 KeyMacroController（两文件测试合计 22 例纯 JVM）；事件本体归真机触控验收。
        "com/eratw/port/platform/input/AndroidInputAdapter.kt",
        // AndroidAssetTree：AssetManager 是 APK assets 唯一读取口；解包逻辑在 GameAssetInstaller（GameAssetInstallerTest 13 例用临时目录顶替 GameAssetTree 接口）；AssetManager 三条老脾气口径归真机首启解包。
        "com/eratw/port/platform/storage/AndroidAssetTree.kt",
        // GameStorage：Context 的 filesDir/外部根是数据根唯一来源；根选择在 selectRoot/candidateRoots 纯函数（GameStorageRootChoiceTest 6 例不建 Context）；真机根解析归真机。
        "com/eratw/port/platform/storage/GameStorage.kt",
        // AndroidGlyphMaskHost：Paint/Bitmap/Canvas.drawText 栅格是字形掩码唯一来源；换算全在 GlyphMaskMath（AndroidGlyphMaskHostTest 11 例）；真栅格读数归有 SDK 闸与真机（CONTRACT-M11-E 判据④）。
        "com/eratw/port/platform/text/AndroidGlyphMaskHost.kt",
        // AndroidTextMeasure：分段量宽的 Typeface 选族 + Paint.measureText 是 Android 唯一字宽来源（core 侧只有等宽近似）；纯换算（styleMask→字体样式位、charSpace→像素、像素→格、字体族回落）在伴生对象 TextMeasureMath（AndroidTextMeasureTest 纯 JVM 钉死，不建 Context/Paint）；Paint/Typeface 本体归真机验收（KDoc TODO(verify) 登记）。
        "com/eratw/port/platform/text/AndroidTextMeasure.kt",
    )

    @Test
    fun `主源目录定位成功且扫到 Kotlin 源`() {
        val mainKotlin = locateMainKotlinDir()
        assertTrue(
            "定位到的主源根下应有 $PLATFORM_PACKAGE_DIR 包：$mainKotlin",
            Files.isDirectory(mainKotlin.resolve(PLATFORM_PACKAGE_DIR)),
        )
        val ktFiles = countKtFiles(mainKotlin)
        assertTrue(
            "主源根应能扫到不少于清单条目数的 .kt 文件（实际 $ktFiles，清单 ${allowlist.size} 条）：$mainKotlin",
            ktFiles >= allowlist.size,
        )
    }

    @Test
    fun `主源 import android 的文件不得超出登记清单`() {
        val mainKotlin = locateMainKotlinDir()
        val actual = scanAndroidImportFiles(mainKotlin)
        val extra = actual - allowlist
        assertTrue(
            buildString {
                appendLine("发现未登记的 Android-API 面：以下主源文件含 import android. 前缀，但不在允许清单。")
                appendLine("扫描基准（主源 kotlin 根）：$mainKotlin")
                extra.forEach { appendLine("  - $it") }
                appendLine()
                appendLine("登记指引：新 Android-API 类必须：a) 抽纯逻辑伴生类 b) 在本清单登记理由 c) 平台面登记 instrumented 验证通道。")
                appendLine("  a) 参照 AudioFocusSession、GlyphMaskMath、SoundFormatPlan、GameAssetInstaller：可测判定全部抽进纯 JVM 伴生类，用 platform 现有档位的单测钉死；")
                appendLine("  b) 在 AndroidApiSurfaceGuardTest.allowlist 逐字登记「为何必须碰 Android / 纯逻辑伴生在哪 / 平台调用本体靠哪道闸验证」；")
                appendLine("  c) platform 现无 androidTest：先在契约登记 instrumented 或真机验收通道，再合流。")
                append("禁止为过闸直接把文件名塞进清单。")
            },
            extra.isEmpty(),
        )
    }

    @Test
    fun `登记清单不得腐烂`() {
        val mainKotlin = locateMainKotlinDir()
        val missing = allowlist.filter { !Files.exists(mainKotlin.resolve(it)) }
        val noLongerAndroid = allowlist
            .filter { it !in missing }
            .filterNot { hasAndroidImport(mainKotlin.resolve(it).toFile()) }
        assertTrue(
            buildString {
                if (missing.isNotEmpty()) {
                    appendLine("清单腐烂：以下条目对应的文件已不存在（挪走或删除后未清理清单）：")
                    missing.forEach { appendLine("  - $it") }
                }
                if (noLongerAndroid.isNotEmpty()) {
                    appendLine("清单腐烂：以下文件已不再 import android.（Android 依赖解掉后未清理清单）：")
                    noLongerAndroid.forEach { appendLine("  - $it") }
                }
                append("请从 AndroidApiSurfaceGuardTest.allowlist 删除对应条目及其理由注释，清单只留真实存在的 Android-API 面。")
            },
            missing.isEmpty() && noLongerAndroid.isEmpty(),
        )
    }

    /**
     * 主源 kotlin 目录定位：从 workingDir 向上逐级、再向下有限层，逐目录尝试
     * [MAIN_KOTLIN_MARKERS] 两种形态。任何命中都要过 [isPlatformMainKotlin] 的
     * `$PLATFORM_PACKAGE_DIR` 包校验，防止误认别其他模块的同名目录。
     * 全部落空时抛 AssertionError 带完整诊断（定位失败必须红，静默跳过 = 闸失效）。
     */
    private fun locateMainKotlinDir(): Path {
        val workingDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
        val tried = mutableListOf<String>()

        // 1) 向上逐级（含 workingDir 本层）：workingDir = platform 模块目录或仓库根 eratw-android 时在这里命中
        var dir: Path? = workingDir
        while (dir != null) {
            for (marker in MAIN_KOTLIN_MARKERS) {
                val candidate = dir.resolve(marker)
                tried += candidate.toString()
                if (isPlatformMainKotlin(candidate)) return candidate
            }
            dir = dir.parent
        }

        // 2) 向下有限层：workingDir 被设到 worktree 根（eratw-android 在下一层）等非默认布局
        var frontier = listOf(workingDir.toFile())
        repeat(MAX_DOWN_DEPTH) {
            val next = mutableListOf<File>()
            for (parent in frontier) {
                val children = parent.listFiles { f -> f.isDirectory && f.name !in SKIP_DIR_NAMES }
                    ?: emptyArray()
                for (child in children) {
                    for (marker in MAIN_KOTLIN_MARKERS) {
                        val candidate = child.toPath().resolve(marker)
                        tried += candidate.toString()
                        if (isPlatformMainKotlin(candidate)) return candidate
                    }
                    next += child
                }
            }
            frontier = next
        }

        val shown = tried.takeLast(SHOWN_TRIED_LIMIT)
        throw AssertionError(
            """
            找不到 platform 主源 kotlin 目录，Android-API 面扫描无法进行（定位失败必须红，静默跳过 = 闸失效）。

            workingDir = $workingDir
            探测形态：对每个探测目录尝试 ${MAIN_KOTLIN_MARKERS.joinToString(" 与 ")}，
            并要求其下存在 $PLATFORM_PACKAGE_DIR 包（防误认别其他模块的同名目录）。
            探测范围：从 workingDir 向上逐级到文件系统根，再向下最多 $MAX_DOWN_DEPTH 层（跳过 ${SKIP_DIR_NAMES.sorted()}）。
            最近尝试的候选（尾部 ${shown.size} 条，共 ${tried.size} 条）：
            ${shown.joinToString("\n") { "  $it" }}

            若 platform 模块挪位、包根变化或 Gradle 测试 workingDir 又变，请更新本类的
            MAIN_KOTLIN_MARKERS 与 PLATFORM_PACKAGE_DIR 探测形态；不要放宽为静默跳过。
            """.trimIndent(),
        )
    }

    /** 候选目录是否是 platform 主源 kotlin 根：目录存在，且其下有 platform 唯一根包。 */
    private fun isPlatformMainKotlin(dir: Path): Boolean =
        Files.isDirectory(dir) && Files.isDirectory(dir.resolve(PLATFORM_PACKAGE_DIR))

    /** 扫描主源下全部 `.kt` 文件，返回含行首 `import android.` 的文件（相对主源根、正斜杠）。 */
    private fun scanAndroidImportFiles(mainKotlin: Path): Set<String> {
        val hits = sortedSetOf<String>()
        // walkTopDown 是纯 Kotlin 序列、无需关闭；主源树无符号链接，不存在循环遍历风险
        mainKotlin.toFile().walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .forEach { file ->
                if (hasAndroidImport(file)) {
                    hits += mainKotlin.relativize(file.toPath()).toString().replace('\\', '/')
                }
            }
        return hits
    }

    /** 行首（允许前导空白）`import android.` 前缀判定；KDoc/注释里的字样不满足行首，不算。 */
    private fun hasAndroidImport(file: File): Boolean =
        file.useLines { lines ->
            lines.any { it.trimStart().startsWith(ANDROID_IMPORT_PREFIX) }
        }

    private fun countKtFiles(mainKotlin: Path): Int =
        mainKotlin.toFile().walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .count()

    private companion object {
        /**
         * 两种布局形态：workingDir = 仓库根 eratw-android（或 worktree 根）时命中
         * 「platform/src/main/kotlin」；workingDir = platform 模块目录时命中「src/main/kotlin」。
         */
        private val MAIN_KOTLIN_MARKERS = listOf("platform/src/main/kotlin", "src/main/kotlin")

        /** platform 主源唯一根包：所有 platform main 源都在其下，用它防误认别其他模块的同名目录。 */
        private const val PLATFORM_PACKAGE_DIR = "com/eratw/port/platform"

        /** 行首 import 前缀（允许前导空白，trimStart 后比对）。 */
        private const val ANDROID_IMPORT_PREFIX = "import android."

        /** 向下探测层数上限：覆盖 workingDir = worktree 根（eratw-android 在下一层）的布局。 */
        private const val MAX_DOWN_DEPTH = 2

        /** 向下探测跳过的目录名：构建产物、VCS/IDE 元数据与源集内部不会藏另一个主源根。 */
        private val SKIP_DIR_NAMES = setOf("build", ".gradle", ".git", ".idea", "node_modules", "src")

        /** 定位失败诊断信息里最多展示的候选路径条数（全量可能上千条，只给尾部现场）。 */
        private const val SHOWN_TRIED_LIMIT = 24
    }
}
