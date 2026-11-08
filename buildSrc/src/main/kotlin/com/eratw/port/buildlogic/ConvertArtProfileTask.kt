package com.eratw.port.buildlogic

import java.io.File
import java.net.URLClassLoader
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * T2-63：ART Profile（`.prof`）→ `baseline-prof.txt` 转换器。
 * （原 app/build.gradle.kts 的 ad-hoc `ConvertArtProfileTask` 任务类化 —— 逻辑逐行同源搬运，
 *  不改任何构建语义。）
 *
 * 为什么自己写这个任务：契约要求「跑四条真实游玩路径生成 profile」，而**采集**与**转换**
 * 是两件事。采集只能上真机（`adb shell am profile start --sampling 100 -p <包名>`），
 * 产出的 `.prof` 是 ART 自己的二进制格式（含 dex 校验和、方法索引），人读不了；AGP 只在
 * **构建期**把「人可读的 baseline-prof.txt」编成 `assets/dexopt/baseline.prof`，没有反向工具。
 *
 * 转换靠 `profgen`——它没有公开的 Maven 坐标，但**完整地打在 AGP 自己的 jar 里**
 * （`com.android.tools.profgen`，108 个类，AGP 8.8.2 实测）。因此不引入任何新依赖
 * （调用方式见 convert() 里的反射说明）：
 *   - `DexDataKt.Apk(File, String)`        读 APK 里的 classes*.dex（拿方法表与校验和）
 *   - `ArtProfileKt.ArtProfile(File)`      读 ART 二进制 profile
 *   - `ProfileDumperKt.dumpProfile(...)`   按 dex 方法表还原成 `HSPL...;->m()V` 文本
 *
 * loader 发现（与脚本内原版唯一的适配点）：原版与 AGP 同处 app 脚本的类路径，
 * `Class.forName` 一步命中；搬进 buildSrc 后任务类不天然持有主构建的 buildscript
 * classloader，profgen 不一定可见。这里按「buildSrc 自身 → 线程上下文 → 主构建
 * buildscript」的顺序探测，全部失手再兜底把 buildscript classpath 拼成 URLClassLoader
 * —— 命中哪个 loader 只是发现路径差异，反射出的 profgen 类与方法与原版完全一致。
 *
 * ⚠️ 调用方必须传**生成该 profile 的同一个 APK**：ArtProfile 里记的是 dex 校验和，
 * 换包（哪怕只改了 KB 级代码）都会让还原出来的方法表错位——profgen 会按校验和拒绝，
 * 这就是我们要的行为（宁可报错，不可静默产出错表）。
 *
 * 用法（真机采集完把 .prof 拉回本机后）：
 *   ./gradlew --configure-on-demand :app:convertArtProfile -PeratwArtProfile=<拉回的 .prof>
 *   （可加 -PeratwArtApk=<同一 APK> / -PeratwArtOut=<出口>；注册与参数接线仍在 app 脚本里）
 * 缺省输出到 `app/src/main/baseline-prof.txt`（AGP 会把该文件编进 assets/dexopt/baseline.prof）。
 */
abstract class ConvertArtProfileTask : DefaultTask() {

    /** ART 采样 profile（`am profile stop` 之后从设备拉回来的那个文件）。 */
    @get:InputFile
    abstract val profileFile: RegularFileProperty

    /** 生成该 profile 的**同一个** APK（提供 dex 方法表）。 */
    @get:InputFile
    abstract val apkFile: RegularFileProperty

    /** 输出的可读 profile；AGP 只认 `src/main/baseline-prof.txt` 这个位置。 */
    @get:OutputFile
    abstract val outFile: RegularFileProperty

    @TaskAction
    fun convert() {
        // 为什么用反射而不是直接 new：Gradle 给构建逻辑的**编译** classpath 是收窄过的
        // `gradle-api`（2026-10-27 实测：直接写 `com.android.tools.profgen.DexDataKt` 报
        // Unresolved reference），但**运行**期 profgen 一定在——AGP 的 jar 就在主构建的
        // buildscript 类路径上。所以按类 KDoc 里的候选链拿 loader，再按「名字 + 参数个数 +
        // 首参类型」定位那几个 Kotlin 文件门面（`*Kt`）上的静态方法。
        val loader: ClassLoader = run {
            val probe = "com.android.tools.profgen.DexDataKt"
            val candidates = mutableListOf<ClassLoader>()
            this.javaClass.classLoader?.let { candidates.add(it) }
            Thread.currentThread().contextClassLoader?.let { candidates.add(it) }
            try {
                candidates.add(project.buildscript.classLoader)
            } catch (_: Throwable) {
                // ScriptHandler 一定有 classLoader，这条保险分支理论上去不到
            }
            for (candidate in candidates) {
                try {
                    candidate.loadClass(probe)
                    return@run candidate
                } catch (_: Throwable) {
                    // 换下一个候选
                }
            }
            // 兜底：`plugins {}` 解析出来的 AGP 不挂在前三类 loader 上时，
            // 显式把 buildscript classpath 拼成一个 URLClassLoader 再试一次。
            val cp = project.buildscript.configurations.getByName("classpath").files
            URLClassLoader(
                cp.map { it.toURI().toURL() }.toTypedArray(),
                ClassLoader.getSystemClassLoader(),
            )
        }
        fun find(owner: String, name: String, firstParam: Class<*>, arity: Int): java.lang.reflect.Method {
            val cls = loader.loadClass(owner)
            return cls.methods.firstOrNull {
                it.name == name &&
                    it.parameterCount == arity &&
                    firstParam.isAssignableFrom(it.parameterTypes[0])
            } ?: throw GradleException(
                "profgen 里找不到 $owner.$name(首参 $firstParam, 共 $arity 个参数)；" +
                    "AGP 版本升级后 ABI 可能变了——照 BOARD 报红，别静默跳过",
            )
        }
        // 1) 读 APK 的 dex 方法表（profile 里只存方法索引，必须靠它还原名字）
        val apk = find("com.android.tools.profgen.DexDataKt", "Apk", File::class.java, 2)
            .invoke(null, apkFile.get().asFile, "app")
        val dexes = apk!!.javaClass.getMethod("getDexes").invoke(apk) as List<*>
        logger.lifecycle("convertArtProfile: APK 含 ${dexes.size} 个 dex")
        // 2) 读 ART 二进制 profile
        val art = find("com.android.tools.profgen.ArtProfileKt", "ArtProfile", File::class.java, 1)
            .invoke(null, profileFile.get().asFile)!!
        val data = art.javaClass.getMethod("getProfileData").invoke(art) as Map<*, *>
        val names = data.keys.map { it!!.javaClass.getMethod("getName").invoke(it) as String }
        logger.lifecycle("convertArtProfile: profile 覆盖 dex $names")
        // ObfuscationMap 在 profgen 的签名里是**非空**参数（传 null 会被 Kotlin 的入参检查当场抛
        // NullPointerException: Parameter specified as non-null is null）。本工程 release 不开 R8，
        // 没有映射表，给一个空映射即可（等价于 Kotlin 侧的 `ObfuscationMap.empty`）。
        val emptyObf = loader.loadClass("com.android.tools.profgen.ObfuscationMap")
            .getConstructor(List::class.java)
            .newInstance(emptyList<Any>())
        // 3) 还原成 HSP 规则文本；末位 `false` = 不按 R8 映射重写（release 未开 minify，名字即 dex 名）
        outFile.get().asFile.parentFile?.mkdirs()
        val dumper = find("com.android.tools.profgen.ProfileDumperKt", "dumpProfile", File::class.java, 5)
        try {
            dumper.invoke(null, outFile.get().asFile, art, apk, emptyObf, false)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            // 反射会把 profgen 自己的异常包一层；不拆开就只剩一句 InvocationTargetException，
            // 排障时完全看不出是「参数为 null」「dex 校验和对不上」还是别的。
            val cause = e.targetException
            throw GradleException(
                "convertArtProfile: profgen dumpProfile 失败（${cause.javaClass.name}: ${cause.message}）",
                cause,
            )
        }
        val rules = outFile.get().asFile.readLines().count { it.isNotBlank() && !it.startsWith("#") }
        if (rules == 0) {
            throw GradleException(
                "convertArtProfile: 还原出 0 条规则（${profileFile.get().asFile}）——" +
                    "要么 profile 是空的（采样期间没跑到代码），要么 APK 与 profile 不同源" +
                    "（dex 校验和对不上，profgen 会把方法表丢掉）",
            )
        }
        logger.lifecycle("convertArtProfile: 写出 ${outFile.get().asFile} —— $rules 条规则")
    }
}
