package com.eratw.port.android

import com.eratw.port.core.code.Program
import com.eratw.port.core.loader.BootMetadata
import java.io.File

/**
 * 进程内的 **base 产物装载缓存**（导入链性能波，2026-11-27）。
 *
 * ## 为什么需要它（实测读数，`ImportPerfProbeTest`）
 * 一次导入的 base 上下文是「mmap 解 364 MiB + 解析 58 MiB 侧车」：本机 JVM 上
 * `loadMapped` **1.6 s**、`BootMetadata.read` **2.9 s**，合计约占整链 33%。而玩家点导入
 * 时，游戏通常**已经在跑**（设置页是从游戏里进去的），这两个对象在 `GameHost` 装载期
 * 就构造过了 —— 旧实现却把它们扔掉、在导入线程里从头再算一遍。
 *
 * 本缓存把「会话装载期已经算出来的东西」按**文件身份**（路径 + 大小 + mtime）借给导入链：
 * 命中就零成本，不命中就照旧现读。语义上与「重新读一遍」逐字等价 —— 只要两份产物文件
 * 的（大小、mtime）没变，读出来的内容就必然相同；换了包（覆盖安装 / 玩家换 erbc）则
 * 戳记不符，缓存自动失效，绝不拿旧产物糊弄新导入。
 *
 * ## 为什么不缓存到磁盘
 * 侧车解析结果没有稳定的序列化契约（格式随编译器走），缓存到磁盘只会制造第二套格式。
 * 进程内借用不引入新契约：**唯一来源仍是 `program.erbc` / `program.meta`**。
 */
internal object BaseArtifactCache {

    /** 一份产物文件的身份戳（大小 + 修改时间；路径只用于自检）。 */
    private data class Stamp(val path: String, val size: Long, val modified: Long) {
        companion object {
            fun of(file: File): Stamp = Stamp(file.absolutePath, file.length(), file.lastModified())
        }
    }

    private class Entry(
        val programStamp: Stamp,
        val metaStamp: Stamp,
        val program: Program,
        val metadata: BootMetadata,
    )

    @Volatile
    private var entry: Entry? = null

    /**
     * 会话装载期回报（`GameHost` 在 `Program` / `BootMetadata` 都就绪后调用）。
     *
     * 只留**最后一局**的：换局/重开是新的 `GameHost`，后装者覆盖前装者，与
     * 「当前会话的产物就是唯一真相」一致。
     */
    fun publish(programFile: File, metaFile: File, program: Program, metadata: BootMetadata) {
        entry = Entry(Stamp.of(programFile), Stamp.of(metaFile), program, metadata)
    }

    /** 命中的会话产物；文件戳不符（换过包）或从没装载过 ⇒ null（调用方现读）。 */
    fun borrow(programFile: File, metaFile: File): Pair<Program, BootMetadata>? {
        val e = entry ?: return null
        // 两份戳都要对：只看 program 不够 —— 侧车（meta）换过而 erbc 没换的话，
        // 借出去的 BootMetadata 就是上一版包的读数。
        val matched = e.programStamp == Stamp.of(programFile) && e.metaStamp == Stamp.of(metaFile)
        return if (matched) e.program to e.metadata else null
    }

    /** 测试与「重启游戏」路径用：显式作废（生产只在 publish 覆盖时自然更新）。 */
    fun clear() {
        entry = null
    }
}
