package com.eratw.port.platform.storage

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * 真机侧的数据根：可写数据落应用私有目录，只读语料从 APK assets 读。
 *
 * 桌面上 core-vm 的启动器直接吃一个目录 `Path`（`ERATW_GAMEDIR`），
 * APK 内的 assets 不是文件系统路径，所以真机启动接线必须先由本类把 assets
 * 解包到 [root] 再交给 `RootedBuiltinHost`（M4-SKEL 建立路径契约，
 * 解包本体见 [GameAssetInstaller]，接线在 `GameHost.ensureAssetsInstalled`）。
 *
 * 放在 `filesDir` 而不是外部存储：无需任何存储权限，卸载即清理，
 * 与 Android 10+ 的分区存储（scoped storage）兼容。
 *
 * ## 根选择（M6-GAMEROOT）
 *
 * 外部根（[externalRoot]，`Android/data/<包名>/files/eratw/`）只要**看起来像游戏根**就优先：
 * 有原版标志文件 [GAME_MARKER]（`emuera.config`），**或者**有编译产物 [ARTIFACT_MARKER]
 * （`erbc.v1/program.erbc`）。后者是 adb 调试的真实口径 —— 只推产物、
 * 不推整份游戏目录时，旧判据会回落 `filesDir`，产物自然找不到。
 * 标志**只认单条产物**：分片形态（`erbc.v1/program.parts`）是 APK assets 与解包器之间的
 * 约定，装载侧从不读分片（`app/.../GameHost.kt:202`、`:371` 只 `isFile` 判 `program.erbc`），
 * 所以推进外部根也必须先拼成单条。
 * 选根结果本进程内只算一次（见 [gameRoot]），与原版 `Program.SetDirPaths` 同口径。
 *
 * 两条候选（外部 / 内部）都失败时，宿主文案要并列两处产物目录，见
 * [candidateArtifactDirs]。判定与候选序都是 [selectRoot] / [candidateRoots] 两个纯函数，
 * 平台单测不建 `Context` 也能钉住。
 */
class GameStorage(context: Context) {

    private val appContext: Context = context.applicationContext

    /** 语料 / 存档的可写根（应用私有目录，无需权限）。 */
    val root: File = File(appContext.filesDir, ROOT_DIR_NAME)

    /**
     * adb 可写的外部根 —— `Android/data/<包名>/files/eratw/`（应用专属外部目录，无需任何权限）。
     *
     * 真机/模拟器调试时把游戏目录 push 到这里（`adb push <游戏目录>/. <此路径>/`）即可，
     * 不必处理 scoped storage；[getExternalFilesDir] 返回 null（外部存储不可用）时本属性为 null。
     */
    val externalRoot: File? =
        appContext.getExternalFilesDir(null)?.let { File(it, ROOT_DIR_NAME) }

    /**
     * 运行时游戏根：外部根里已有 [GAME_MARKER]（`emuera.config`，原版 `Program.ExeDir` 的标志文件）
     * 或 [ARTIFACT_MARKER]（`erbc.v1/program.erbc`）就用外部根，否则用内部根。
     *
     * 这条选择规则只决定「数据放哪」，不改变 `RootedBuiltinHost` 的相对路径口径
     * （`resources/`、`CSV/`、`sav/`、`sound/` 仍然都相对游戏根）。
     *
     * **本进程内只算一次**（[chosenRoot]）：原版 `Program.SetDirPaths` 在 `Main` 里跑一次
     * 就把 `ExeDir`/`CsvDir`/`ErbDir` 定死（`Program.cs:78`、`:202-226`、`:234`），此后
     * 没有任何一处重算。逐次现算会留下一个 dev 通道专属的事故形态：首启解包要跑几分钟
     * （`GameHost.kt:259` 落 A 根），这期间 adb 推进 `program.erbc` 到 B 根，随后
     * `GameHost.kt:225` 再问一次就拿到 B ⇒ 装载侧对着只有产物没有侧车/语料的 B 报「产物缺失」，
     * 而刚解好的 A 整份闲置。缓存后两侧必然同根，重启应用才换根。
     */
    fun gameRoot(): File = chosenRoot

    /** 选根结果（首次 [gameRoot] 时定下，之后不再变）。`by lazy` 的同步语义 = 多线程首问只落一次。 */
    private val chosenRoot: File by lazy {
        val selected = selectRoot(externalRoot, root)
        if (selected == root) ensureRoot() else selected
    }

    /** 候选游戏根，**外部优先**（adb 可直推）；外部存储不可用时只有内部根。 */
    fun candidateRoots(): List<File> = candidateRoots(externalRoot, root)

    /**
     * 候选游戏根下的产物目录（[dirName] = `GameHost.ARTIFACT_DIR`），外部优先。
     * 产物缺失文案用这组路径并列两条候选，屏上直接能看出该往哪推。
     */
    fun candidateArtifactDirs(dirName: String): List<File> =
        candidateRoots().map { File(it, dirName) }

    /** 可随时丢弃的缓存（字形缓存、解包中间产物）。 */
    val cache: File = File(appContext.cacheDir, ROOT_DIR_NAME)

    /** 建根目录并返回（幂等）。 */
    fun ensureRoot(): File {
        if (!root.isDirectory && !root.mkdirs() && !root.isDirectory) {
            throw IOException("无法创建数据根：${root.absolutePath}")
        }
        return root
    }

    /** 以只读流打开 APK 内的资源；不存在返回 null（调用方按「缺文件」处理）。 */
    fun openAsset(path: String): InputStream? =
        try {
            appContext.assets.open(path)
        } catch (_: IOException) {
            null
        }

    /**
     * APK assets 树的首启解包视图（[GameAssetInstaller] 的输入）。
     *
     * 每次调用新建一个薄包装：解包器只持有 `AssetManager`，不带 `Context`，
     * 所以纯 JVM 单测不需要 Android 环境（见 `GameAssetInstallerTest`）。
     */
    fun assetTree(): GameAssetTree = AndroidAssetTree(appContext.assets)

    companion object {
        private const val ROOT_DIR_NAME = "eratw"

        /** 游戏根标志文件：原版 `Program.ExeDir + "emuera.config"`（ConfigData.cs:33）。 */
        private const val GAME_MARKER = "emuera.config"

        /**
         * 产物标志：`adb push program.erbc` 后外部根即为「已装备」状态，
         * 与 [GAME_MARKER] 等权（见类头的根选择）。
         */
        private const val ARTIFACT_MARKER = "erbc.v1/program.erbc"

        /** 纯函数选根：外部根像游戏根就用外部，否则内部。 */
        fun selectRoot(external: File?, internalRoot: File): File =
            if (external != null && looksLikeGameRoot(external)) external else internalRoot

        /** 外部根是否「像游戏根」：原版标志文件或编译产物任一存在即可。 */
        fun looksLikeGameRoot(dir: File): Boolean =
            File(dir, GAME_MARKER).isFile || File(dir, ARTIFACT_MARKER).isFile

        /** 候选游戏根（外部优先，其后内部）；纯函数版，供选择逻辑与单测共用。 */
        fun candidateRoots(external: File?, internalRoot: File): List<File> =
            if (external == null) listOf(internalRoot) else listOf(external, internalRoot)
    }
}
