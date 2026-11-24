package com.eratw.port.android

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * CONTRACT-BOOTUNPACK 的**跨模块源码面哨兵**（core-vm / platform / app）。
 *
 * 三处根修必须同时在线，缺一处就退回「每轮冷启全量重写约 919 MiB」：
 * ① core 的逐文件判据表把更新档与让位档并列；② platform 的装载相位算让位档并把
 * 所有 `forceReplace || needsCopy` 判式收敛到 `stepNeeded`；③ app 删掉上层整轮短路、
 * 把盘上版本传下去，并把同一个版本标记的三条写入路径统一到只升不降。
 *
 * 按「生效代码行」比对，注释/KDoc 里的同名字符串不算数（承 11-15 反向证明抓出的
 * `contains(...)` 会被注释行骗绿那一条）。找不到源码目录直接判红，不许判成跳过。
 */
class BootUnpackWireTest {

    @Test
    fun `core 判据表把更新档与让位档放在同一张表`() {
        val lines = effectiveLines(sourceOf("core-vm/src/main/kotlin/com/eratw/port/core/update/UpdatePolicy.kt"))
        assertTrue("判据表函数掉了", lines.any { it.startsWith("fun bundledAssetNeedsCopy(") })
        assertTrue(
            "让位档必须只在目标已在位时跳过（反向证明：撤掉这一支行为级用例应红）",
            lines.any { it.startsWith("defersToDisk -> !targetPresent") },
        )
        assertTrue("更新档必须优先于让位档", lines.any { it.startsWith("forceReplace -> true") })
    }

    @Test
    fun `装载相位算让位档并逐文件收敛到唯一裁决口`() {
        val rel = "platform/src/main/kotlin/com/eratw/port/platform/storage/GameAssetInstaller.kt"
        val lines = effectiveLines(sourceOf(rel))
        assertTrue(
            "构造参数必须带盘上版本且缺省 -1（老调用点零行为）",
            lines.any { it.startsWith("private val diskDataVersion: Long = -1L,") },
        )
        assertTrue("让位档开关掉了", lines.any { it.startsWith("private val defersToNewerDisk: Boolean =") })
        val deferCall = "UpdatePolicy.unpackSuppressedByNewerDiskData(diskDataVersion, recordDataVersion)"
        assertTrue(
            "让位与否必须走 core 纯函数，不许在本类另写一份比大小",
            lines.any { it.startsWith(deferCall) },
        )
        assertTrue("逐文件唯一裁决口掉了", lines.any { it.startsWith("private fun stepNeeded(") })
        assertTrue(
            "stepNeeded 必须把判据交给 core 那张表",
            lines.any { it.startsWith("return UpdatePolicy.bundledAssetNeedsCopy(") },
        )
        assertTrue(
            "plan 里不许再散着写 forceReplace || needsCopy（配置那条「只拷一次」除外）",
            lines.none { it.startsWith("needed = forceReplace ||") },
        )
    }

    @Test
    fun `宿主不再用旁路闸整轮跳过解包只把版本事实传下去`() {
        val lines = effectiveLines(sourceOf("app/src/main/kotlin/com/eratw/port/android/LoadPipeline.kt"))
        assertTrue(
            "整轮解包必须照跑（配置补齐 / 迁移 / RT2 / 摘要维护不许被一起跳过）",
            lines.none { it.startsWith("val report = if (suppressUnpack)") },
        )
        assertTrue("report 恒非空，闭环段不许再判空", lines.none { it.startsWith("if (report != null)") })
        assertTrue(
            "盘上版本必须传给装载相位",
            lines.any { it.startsWith("diskDataVersion = diskDataVersion,") },
        )
    }

    @Test
    fun `版本标记三条写入路径统一到只升不降的唯一入口`() {
        val lines = effectiveLines(sourceOf("app/src/main/kotlin/com/eratw/port/android/UpdateManager.kt"))
        assertTrue("唯一写入口掉了", lines.any { it.startsWith("private fun advanceDataVersion(") })
        assertTrue(
            "只升不降的判据掉了（反向证明：撤掉它 23 会被拨回 22）",
            lines.any { it.startsWith("if (installedDataVersion(gameRoot) >= version) return") },
        )
        assertTrue(
            "随包解包前那一拍要经唯一入口",
            lines.any { it.startsWith("advanceDataVersion(gameRoot, bundledVersion)") },
        )
        assertTrue(
            "随包解包收尾那一拍要经唯一入口",
            lines.any { it.startsWith("advanceDataVersion(gameRoot, version)") },
        )
        assertTrue(
            "数据包应用成功那一拍要经唯一入口",
            lines.any { it.startsWith("advanceDataVersion(gameRoot, staged.version)") },
        )
    }

    /** 只留生效代码行：注释、KDoc 正文里的同名串不许把哨兵判绿。 */
    private fun effectiveLines(text: String): List<String> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }
            .toList()

    /** 兼容「从 app 模块跑」与「从仓库根跑」两种工作目录；core-vm 在公开分仓的同级引擎仓。 */
    private fun sourceOf(rel: String): String {
        val candidates = listOf(
            File("../$rel"),
            File(rel),
            File("../../Kotera/$rel"),
        )
        val found = candidates.firstOrNull { it.isFile }
            ?: error("找不到源码 $rel（工作目录 = ${File(".").absolutePath}）")
        return found.readText()
    }
}
