package com.eratw.port.android

import android.os.Process
import java.io.File
import java.io.FileOutputStream

/**
 * 哨兵冻结现场的**跨进程落盘**（2026-11-20 ANR 取证缺口的补口）。
 *
 * ## 它补的是哪条缝
 *
 * 哨兵 [StallSentinel] 在冻结 1.5 s 后抓到过**完整的两条栈**（轨迹行 `frames=12` 就是证据），
 * 但现场只留在进程内存（[StallSentinel.lastScene]），轨迹那一行按体积纪律只带**栈顶 1 帧**
 * （`StallJudgment.trailPhase`）。于是「上次异常退出（ANR 被收）」的报告里永远只有栈顶：
 * 2026-11-20 vivo 那份报告 `ui@StringFactory.newStringFromBytes:-2` 是 native 帧，
 * **第 2 帧（调用者）就在被抓到的 12 帧里，却随进程一起死了** —— 端点 69 份报告
 * `[主线程冻结现场]` 覆盖 0 的根因不是哨兵没抓到，是抓到的东西没活过进程。
 *
 * 本文件把 [StallScene.lines]（≤ [StallJudgment.SCENE_MAX_LINES] 行，约 3 KB）在钉发那一刻
 * 原子写到盘上；下一次冷启动的报告侧据此把**上一进程**的完整栈贴进 `[补充现场]`。
 * 写/读/轮换/pid 认主全部照抄 `LastBreath`（ExitForensics.kt）的既有口径，不发明第三条路。
 *
 * ## 线程
 *
 * 写侧只由哨兵线程调（`pin` 回调里，30 s 冷却 ⇒ 最密 30 s 一发）；读侧在报告生成线程。
 * 文件是单写者覆盖写 + tmp/rename 原子，无锁。
 */
object StallSceneLog {

    /** `filesDir` 下的独立目录：不与 RunWatch 轨迹、LastBreath 心跳共用（互不轮换、互不覆盖）。 */
    const val DIR_NAME = "eratw-stall"

    const val FILE_NAME = "stall-scene.txt"
    const val PREV_FILE_NAME = "stall-scene-prev.txt"
    const val TMP_FILE_NAME = "stall-scene.tmp"

    /** 读侧体积上限：写侧由 [SCENE_MAX_LINES] 形状保证 < 8 KB，这里只防别人写坏的超长文件。 */
    private const val MAX_BYTES = 16 * 1024

    /** 首行键（自描述，报告侧/解析侧都按它认 pid 与墙钟时刻）。 */
    private const val PID_PREFIX = "pid="
    private const val WALL_PREFIX = "wall="

    /** 报告小节标题：与哨兵进程内那一节（`StallJudgment.SCENE_HEADER`）同形、来源不同。 */
    const val SCENE_FROM_DISK_HEADER = "[主线程冻结现场（上一进程遗留，哨兵抓栈落盘）]"

    /** 无留痕时报告侧的那一行（形状对齐 `LastBreath.NO_SNAPSHOT` 的口径）。 */
    const val NO_SNAPSHOT = "（上一进程没有遗留冻结现场：哨兵没抓到过，或已被更新的进程轮换掉）"

    /**
     * 写入一份现场（哨兵线程）。写前按 pid 认主把上一进程的遗留轮换进 prev（同
     * `LastBreath.rotatePreviousRun` 的理由：冷启补报与本哨兵两条路都碰这个文件，
     * 不轮换就会把要取证的那一份当场覆盖掉）。
     *
     * @param dir [DIR_NAME] 目录（不存在则建）
     * @param scene 哨兵刚抓好的现场
     * @param wallClockMs 写入墙钟（毫秒，`System.currentTimeMillis` 口径；测试注入）
     * @param pid 写入方进程号（测试注入）
     * @return true = 落盘成功；失败只返回 false，绝不抛（取证不许拖累哨兵线程）
     */
    fun write(
        dir: File,
        scene: StallScene,
        wallClockMs: Long = System.currentTimeMillis(),
        pid: Int = Process.myPid(),
    ): Boolean = runCatching {
        rotatePreviousRun(dir, pid)
        val head = "$PID_PREFIX$pid $WALL_PREFIX$wallClockMs atMs=${scene.atMs} uiFrozen=${scene.uiAgeMs}ms"
        writeText(dir, head + "\n" + scene.lines().joinToString("\n"))
    }.getOrDefault(false)

    /**
     * 上一进程的遗留现场：先看当前文件、再看 prev，pid 相同（本进程自己写的）一律不算。
     * 读到当前文件是别人进程的 ⇒ 返回之前顺手轮换进 prev（覆盖旧 prev），防止两连报告
     * 重复贴同一份。
     */
    fun readPrevious(dir: File, currentPid: Int): PreviousScene? {
        val current = read(dir, FILE_NAME, currentPid)
        if (current != null) {
            rotateToPrev(dir)
            return current
        }
        return read(dir, PREV_FILE_NAME, currentPid)
    }

    /**
     * 报告小节行：有留痕 ⇒ 标题 + 首行读数 + 栈正文；无 ⇒ [NO_SNAPSHOT] 一行。
     * 整段 [runCatching]：取证读数永远不许把报告路径拖崩（对齐 `LastBreath.reportLines`）。
     */
    fun reportLines(dir: File, currentPid: Int): List<String> = runCatching {
        val scene = readPrevious(dir, currentPid) ?: return listOf(NO_SNAPSHOT)
        listOf(SCENE_FROM_DISK_HEADER) + scene.reportLines
    }.getOrElse { listOf(NO_SNAPSHOT) }

    /** 上一进程遗留现场的读回值（不还原 [StallScene] 对象：报告只要行）。 */
    class PreviousScene internal constructor(
        val pid: Int,
        val wallClockMs: Long,
        /** 报告正文（首行读数 + 两条栈），已按写时的形状裁好。 */
        val reportLines: List<String>,
    )

    // ------------------------------------------------------------------
    // 内部：轮换与读写（形状照抄 ExitForensics 的 LastBreath 段）
    // ------------------------------------------------------------------

    private fun read(dir: File, name: String, currentPid: Int): PreviousScene? = runCatching {
        val file = File(dir, name)
        if (!file.isFile) return@runCatching null
        val text = readCapped(file) ?: return@runCatching null
        val first = text.lineSequence().firstOrNull() ?: return@runCatching null
        val pid = first.substringAfter(PID_PREFIX).substringBefore(' ').toIntOrNull() ?: return@runCatching null
        if (pid == currentPid) return@runCatching null
        val wall = first.substringAfter(WALL_PREFIX, "").substringBefore(' ').toLongOrNull() ?: -1L
        val body = text.lineSequence().drop(1).toList()
        if (body.isEmpty()) return@runCatching null
        PreviousScene(pid, wall, body)
    }.getOrNull()

    /** 本进程写新现场之前：把别的进程留下的当前文件轮换进 prev（覆盖旧 prev）。 */
    private fun rotatePreviousRun(dir: File, currentPid: Int) {
        if (read(dir, FILE_NAME, currentPid) != null) rotateToPrev(dir)
    }

    /** 读侧确认是别人进程的当前文件 → 轮换进 prev（覆盖旧 prev）。 */
    private fun rotateToPrev(dir: File) {
        val src = File(dir, FILE_NAME)
        val prev = File(dir, PREV_FILE_NAME)
        if (!prev.exists() || prev.delete()) {
            runCatching { if (!src.renameTo(prev)) src.delete() }
        }
    }

    private fun writeText(dir: File, text: String): Boolean {
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) return false
        val target = File(dir, FILE_NAME)
        val tmp = File(dir, TMP_FILE_NAME)
        val payload = text.toByteArray(Charsets.UTF_8)
        // 先原子写；失败走机型兼容路径（某些机 rename 到已存在目标会失败）：删旧重试一次
        // （窗口内没有目标文件，下一次写会补上；读侧读到「没有」比读到半截安全）。
        val ok = atomicPut(tmp, target, payload) || (target.delete() && atomicPut(tmp, target, payload))
        if (!ok) tmp.delete()
        return ok
    }

    /** tmp 写入 + 单次 rename 的原子替换；失败只返回 false（含写半截的 IO 异常）。 */
    private fun atomicPut(tmp: File, target: File, payload: ByteArray): Boolean = runCatching {
        FileOutputStream(tmp).use { out ->
            out.write(payload)
            out.flush()
            runCatching { out.fd.sync() }
        }
        tmp.renameTo(target)
    }.getOrDefault(false)

    private fun readCapped(file: File): String? = runCatching {
        if (file.length() > MAX_BYTES * 2) return@runCatching null
        val bytes = file.inputStream().use { input ->
            val buffer = ByteArray(MAX_BYTES)
            var read = 0
            while (read < MAX_BYTES) {
                val n = input.read(buffer, read, MAX_BYTES - read)
                if (n <= 0) break
                read += n
            }
            buffer.copyOf(read)
        }
        String(bytes, Charsets.UTF_8)
    }.getOrNull()
}
