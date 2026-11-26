package com.eratw.port.android

import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.patch.ManifestFormatException
import com.eratw.port.core.patch.ModuleDescriptor
import com.eratw.port.core.vars.VariableStore
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * attach 期 Manifest **解码复用**（task-34，先量后改）的定向判据。
 *
 * 背景（实测基线，装置 = 本文件 `attach` 夹具、4 份 manifest）：裸 `attach` 里
 * `declarationSnapshotFor` 的回落装载与 `ModSettingsStore.snapshotsFor` 各解一遍同一批
 * manifest ⇒ **8 次真解析 / 9.45 ms**（计数来自 [ManifestCodec.probe]）。
 * 现在装配作用域内同一份字节只解析一次、其余命中复用 ⇒ 4 次真解析，其余（含后来新增的
 * ModDX4-B1 写授权段消费方）全部复用。判据写成「真解析数 == manifest 份数、其余全是复用」，
 * 不把复用调用写成定值：多一个消费方只该增加复用数。
 *
 * 语义零变化判据：复用实例 `ManifestCodec.encode` 回写字节与独立解码逐字节相同；
 * 作用域外行为逐位不变（每次调用都真解析）；attach 收口后下一趟重新解码（不跨会话缓存）；
 * 停用标记变更在同一作用域内照样重新扫盘；坏 manifest 在作用域内仍整抛。
 */
class ManifestDecodeScopeTest {

    private fun tempRoot(): Path {
        val root = Files.createTempDirectory("manifest-decode-scope")
        Files.createDirectories(root.resolve("mods").resolve("rt2"))
        return root
    }

    private fun manifestBytes(packageId: String, version: String = "1.0"): ByteArray =
        ManifestCodec.encode(ModuleDescriptor(packageId = packageId, version = version))

    private fun writeManifest(root: Path, packageId: String, version: String = "1.0") {
        val target = root.resolve("mods").resolve("rt2").resolve("$packageId.manifest")
        Files.write(target, manifestBytes(packageId, version))
    }

    private fun attach(root: Path): ModRuntimeSession? = ModRuntimeAssembly.attach(
        gameRoot = root.toFile(),
        store = VariableStore(),
        nativeRegistry = NativeMethodRegistry(),
        onVmThread = { true },
        enabled = true,
        logSink = {},
    )

    /** 挂解码探针跑一段，返回逐次读数（finally 摘探针，避免污染其它用例）。 */
    private fun withProbe(block: () -> Unit): List<ManifestCodec.DecodeProbe> {
        val probes = mutableListOf<ManifestCodec.DecodeProbe>()
        ManifestCodec.probe = { call -> probes += call }
        try {
            block()
        } finally {
            ManifestCodec.probe = null
        }
        return probes
    }

    @Test
    fun `attach 期同一份 manifest 只真解析一次其余命中复用`() {
        val root = tempRoot()
        try {
            repeat(4) { index -> writeManifest(root, "mod.scope$index") }
            val probes = withProbe { attach(root)?.shutdown() }
            val parsed = probes.count { it.parsed }
            val reused = probes.count { !it.parsed }
            val elapsedNanos = probes.sumOf { it.elapsedNanos }
            println(
                "manifest-decode-scope packages=4 decodeCalls=${probes.size} parsed=$parsed " +
                    "reused=$reused totalElapsedMs=${elapsedNanos / 1_000_000.0}",
            )
            assertEquals("每份 manifest 只真解析一次", 4, parsed)
            // 复用调用数 = 解码调用总数 − 真解析数：**不许把某个消费方写成定值**。attach 里同一批
            // manifest 现在的消费方有三个（装载相位 ManifestLoader / ModDX4-B1 写授权段
            // ModDx4WriteAssembly.grantsOf / 设置面 ModSettingsStore.snapshotsFor），再加消费方
            // 只会让复用数变大——那正是这个作用域存在的意义；一旦有人绕过作用域，parsed 会先红。
            assertEquals("除真解析外全部命中复用", probes.size - parsed, reused)
            assertTrue("复用处必须至少存在（否则作用域没生效）", reused >= 4)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `作用域内复用同一描述符且编码字节与独立解码等价`() {
        val bytes = manifestBytes("mod.equiv")
        val fresh = ManifestCodec.decode(bytes)
        ManifestCodec.openDecodeScope()
        try {
            val first = ManifestCodec.decode(bytes)
            val second = ManifestCodec.decode(bytes.copyOf())
            assertSame("同一份字节在作用域内必须复用同一描述符实例", first, second)
            assertArrayEquals(
                "复用实例回写字节必须与独立解码逐字节相同",
                ManifestCodec.encode(fresh),
                ManifestCodec.encode(first),
            )
            assertEquals("mod.equiv", first.packageId)
        } finally {
            ManifestCodec.closeDecodeScope()
        }
    }

    @Test
    fun `作用域外行为逐位不变每次调用都真解析`() {
        val bytes = manifestBytes("mod.plain")
        val probes = withProbe {
            ManifestCodec.decode(bytes)
            ManifestCodec.decode(bytes)
        }
        assertEquals("作用域外不得复用", 2, probes.count { it.parsed })
        assertTrue("收口后不再处于作用域", !ManifestCodec.isDecodeScopeOpen())
    }

    @Test
    fun `attach 收口后下一趟装配重新解码不复用上一趟`() {
        val root = tempRoot()
        try {
            writeManifest(root, "mod.reopen", version = "1.0")
            val first = withProbe { attach(root)?.shutdown() }
            assertEquals("首趟：1 次真解析 + 其余全部复用", 1, first.count { it.parsed })
            assertEquals(first.size - 1, first.count { !it.parsed })
            assertTrue("首趟必须有复用（作用域生效）", first.count { !it.parsed } >= 1)

            // 换字节后重开：作用域已在上一趟收口，绝不允许复用上一趟的旧描述符。
            writeManifest(root, "mod.reopen", version = "2.0")
            val second = withProbe { attach(root)?.shutdown() }
            assertEquals("第二趟必须重新解析（跨装配不得命中上一趟缓存）", 1, second.count { it.parsed })
            assertEquals("第二趟内部：1 次真解析 + 其余全部复用", second.size - 1, second.count { !it.parsed })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `停用标记变更后同一作用域内重新扫盘不读已停用包`() {
        val root = tempRoot()
        try {
            writeManifest(root, "mod.toggle")
            ManifestCodec.openDecodeScope()
            try {
                val before = ModSettingsStore.snapshotsFor(root.toFile())
                assertTrue("启用态必须读到声明", before.containsKey("mod.toggle"))
                assertTrue(ModEnablement.setEnabled(ModEnablement.rt2Dir(root.toFile()), "mod.toggle", false))
                val after = ModSettingsStore.snapshotsFor(root.toFile())
                assertTrue("停用后同一作用域内也必须重新扫盘、不再收该包", !after.containsKey("mod.toggle"))
            } finally {
                ManifestCodec.closeDecodeScope()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `坏 manifest 在作用域内仍然整抛且不污染后续解码`() {
        val goodBytes = manifestBytes("mod.good")
        val badBytes = goodBytes.copyOf(goodBytes.size - 3)
        ManifestCodec.openDecodeScope()
        try {
            assertThrows(ManifestFormatException::class.java) { ManifestCodec.decode(badBytes) }
            assertEquals("mod.good", ManifestCodec.decode(goodBytes).packageId)
            assertThrows("坏档不得因缓存被吞", ManifestFormatException::class.java) {
                ManifestCodec.decode(badBytes)
            }
        } finally {
            ManifestCodec.closeDecodeScope()
        }
    }
}
