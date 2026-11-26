package com.eratw.port.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 卸载影响预览（[buildUninstallImpactLines]）的盘面 + 会话快照用例（task-199 收口）。
 *
 * 缺陷面：预览按**磁盘身份**读运行中会话的 storage 段，而 storage 段按**运行期身份**
 * （`.codeid` 侧车里作者声明的 id，`storage.activate/release` 同键）命名 ⇒ 双身份包永远读不到
 * 自己的段，玩家在卸载确认框里看到「—」。本组用例钉死三件事：
 *
 * 1. 会话快照只按**运行期身份**问（provider 收到的 id 清单逐字断言，磁盘身份键不得被读）；
 * 2. 侧车缺失（单身份包）时回落磁盘身份，既有行为逐字节不变；
 * 3. 侧车损坏时 storage 段显式「—」且**不问**会话快照（不猜 id），而代码产物行仍按盘面读得到。
 *
 * 只碰纯 JVM 文件系统与会话快照接线（[ModRuntimeOverviewEntry.install]，测试尾部认主摘除）。
 */
class ModUninstallImpactPreviewTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val zh = SettingsStrings.of(SettingsLanguage.ZH)

    @Test
    fun `双身份包 storage 段按运行期身份读取`() {
        val rt2 = temp.newFolder("preview-dual")
        writeDualIdentityPack(rt2)
        val requested = mutableListOf<List<String>>()
        withSnapshot(requested, storageOf = { id ->
            when (id) {
                DUAL_RUNTIME_ID -> ModStorageReadout(bytes = 2_048L, keys = 3)
                // 反证：磁盘身份键上放一个明显不同的段，读错了这里就会露馅。
                DUAL_DISK_ID -> ModStorageReadout(bytes = 9_999L, keys = 99)
                else -> null
            }
        }) {
            val save = lineOf(buildUninstallImpactLines(zh, rt2, DUAL_DISK_ID, dexLoaderEnabled = true))
            assertTrue("storage 段必须按运行期身份读到：$save", save.contains("2.0 KB"))
            assertTrue("storage 段键数必须来自运行期段：$save", save.contains("3"))
            assertEquals("会话快照只许按运行期身份问", listOf(listOf(DUAL_RUNTIME_ID)), requested)
        }
    }

    @Test
    fun `侧车缺失时 storage 段回落磁盘身份`() {
        val rt2 = temp.newFolder("preview-single")
        writeDualIdentityPack(rt2)
        assertTrue("前置：删侧车", File(rt2, ModCodeIdentity.fileName(DUAL_DISK_ID)).delete())
        val requested = mutableListOf<List<String>>()
        withSnapshot(requested, storageOf = { id ->
            if (id == DUAL_DISK_ID) ModStorageReadout(bytes = 1_024L, keys = 2) else null
        }) {
            val save = lineOf(buildUninstallImpactLines(zh, rt2, DUAL_DISK_ID, dexLoaderEnabled = true))
            assertTrue("缺侧车时按磁盘身份读段（既有行为不变）：$save", save.contains("1.0 KB"))
            assertTrue("缺侧车时段键数照实读：$save", save.contains("2"))
            assertEquals("缺侧车时回落身份 = 磁盘身份", listOf(listOf(DUAL_DISK_ID)), requested)
        }
    }

    @Test
    fun `侧车损坏时 storage 段显式不可判且不猜身份去问会话`() {
        val rt2 = temp.newFolder("preview-corrupt")
        writeDualIdentityPack(rt2)
        File(rt2, ModCodeIdentity.fileName(DUAL_DISK_ID)).writeText("not-a-codeid", Charsets.UTF_8)
        val requested = mutableListOf<List<String>>()
        withSnapshot(requested, storageOf = { ModStorageReadout(bytes = 2_048L, keys = 3) }) {
            val lines = buildUninstallImpactLines(zh, rt2, DUAL_DISK_ID, dexLoaderEnabled = true)
            assertEquals(
                "坏档 storage 段必须显式「—」",
                zh.modManageDetailLine(zh.modUninstallImpactSave, zh.modUninstallImpactMissing),
                lineOf(lines),
            )
            assertTrue("坏档不许猜 id 去问会话快照", requested.isEmpty())
            val code = lines.first { it.startsWith(zh.modUninstallImpactCode) }
            assertFalse("产物计数按盘面读得到，坏档不许把它显示成取不到", code.endsWith(zh.modUninstallImpactMissing))
        }
    }

    @Test
    fun `单身份包 storage 段读数逐字节不变`() {
        val rt2 = temp.newFolder("preview-plain")
        writeArtifact(rt2, SINGLE_ID)
        val requested = mutableListOf<List<String>>()
        withSnapshot(requested, storageOf = { id ->
            if (id == SINGLE_ID) ModStorageReadout(bytes = 2_048L, keys = 3) else null
        }) {
            val save = lineOf(buildUninstallImpactLines(zh, rt2, SINGLE_ID, dexLoaderEnabled = true))
            assertTrue("单身份包段照旧读到：$save", save.contains("2.0 KB"))
            assertTrue("单身份包段键数照旧：$save", save.contains("3"))
            assertEquals("单身份包问的就是磁盘身份", listOf(listOf(SINGLE_ID)), requested)
        }
    }

    /** 装一个假会话快照接线（key 口径由 [storageOf] 决定），测试尾部按 owner 认主摘除。 */
    private fun withSnapshot(
        requested: MutableList<List<String>>,
        storageOf: (String) -> ModStorageReadout?,
        block: () -> Unit,
    ) {
        val owner = Any()
        ModRuntimeOverviewEntry.install(owner) { packageIds ->
            requested += packageIds
            ModRuntimeData(
                activeIds = emptySet(),
                timingByPackage = emptyMap(),
                slowCountByPackage = emptyMap(),
                thresholdNanos = 0L,
                storageByPackage = packageIds.associateWith(storageOf),
            )
        }
        try {
            block()
        } finally {
            ModRuntimeOverviewEntry.uninstall(owner)
        }
    }

    private fun writeDualIdentityPack(rt2: File) {
        writeArtifact(rt2, DUAL_DISK_ID)
        ModCodeIdentity.write(rt2, DUAL_DISK_ID, DUAL_RUNTIME_ID)
    }

    private fun writeArtifact(rt2: File, packageId: String) {
        val dir = File(File(rt2, MOD_CODE_DIR_NAME), packageId)
        dir.mkdirs()
        File(dir, ADDRESS_KEY + MOD_CODE_ARTIFACT_EXTENSION).writeText("pack")
    }

    /** 取预览里的 storage 行；预览恒为「小标题 + 七行」，storage 行必在。 */
    private fun lineOf(lines: List<String>): String = lines.first { it.startsWith(zh.modUninstallImpactSave) }

    private companion object {
        const val DUAL_DISK_ID: String = "kojo-0badc0de"
        const val DUAL_RUNTIME_ID: String = "author.preview"
        const val SINGLE_ID: String = "plain.mod"
        const val ADDRESS_KEY: String = "0123456789abcdef"
    }
}
