package com.eratw.port.android

import com.eratw.port.core.patch.BoolSettingDeclaration
import com.eratw.port.core.patch.EnumSettingDeclaration
import com.eratw.port.core.patch.IntSettingDeclaration
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.patch.ManifestFormatException
import com.eratw.port.core.patch.ModuleDescriptor
import com.eratw.port.core.patch.StrSettingDeclaration
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * per-mod 设置的设备侧持久化（task-4 Lane D）：`mods/rt2/<packageId>.settings`。
 *
 * 钉四件事（每条对应契约硬约束）：
 * 1. **往返保真**：写进去的 `key → value` 文本（含中文、空串）读回来逐字节一致；
 * 2. **坏文件不炸启动**：缺文件 / 头不对 / 行格式坏 / hex 坏 / UTF-8 坏 / 重复键 / 超大
 *    ⇒ 整份当「没有已保存值」返回空表（所有键回默认），**不抛**；
 * 3. **原子写**：正式文件只经临时文件 + 改名出现，写失败不留半截正式文件；键排序落盘
 *    ⇒ 同一份 Map 两次写出的字节相同；
 * 4. **快照面**：`snapshotsFor` 从 manifest 的 `settings` 段取声明、从 `.settings` 取已
 *    持久化值，按包产出快照；停用包不产；坏 manifest 照 attach 口径整抛。
 *
 * 反向证明在交付消息记录：把 [ModSettingsStore.read] 里的 `parse` 换成 `throw` ⇒ 坏文件
 * 用例立红；把 `write` 的原子替换退化成直接覆盖 ⇒ 「写一半」负控用例立红（见交付）。
 */
class ModSettingsStoreTest {

    private fun tempRoot(prefix: String): File = Files.createTempDirectory(prefix).toFile()

    private fun rt2Of(root: File): File = ModEnablement.rt2Dir(root).also { it.mkdirs() }

    private fun writeManifest(rt2: File, descriptor: ModuleDescriptor) {
        File(rt2, descriptor.packageId + ModRuntimeAssembly.MANIFEST_EXTENSION)
            .writeBytes(ManifestCodec.encode(descriptor))
    }

    private fun settingsManifest(packageId: String): ModuleDescriptor = ModuleDescriptor(
        packageId = packageId,
        displayName = "带设置项的包",
        settings = listOf(
            IntSettingDeclaration("cfg.volume", default = 5L, min = 0L, max = 10L, title = "音量"),
            BoolSettingDeclaration("cfg.auto", default = true, title = "自动"),
            StrSettingDeclaration("cfg.label", default = "默认标签", title = "标签"),
            EnumSettingDeclaration(
                "cfg.mode",
                default = "b",
                candidates = listOf("a", "b", "c"),
                title = "模式",
            ),
        ),
    )

    // ---------------- 判据 1：往返保真 ----------------

    @Test
    fun `写入后读回含中文与空串且逐字节往返`() {
        val root = tempRoot("rt2-settings-roundtrip")
        try {
            val rt2 = rt2Of(root)
            val values = linkedMapOf(
                "cfg.label" to "改过的标签",
                "cfg.empty" to "",
                "cfg.mode" to "随机的说明",
                "cfg.volume" to "8",
            )
            assertTrue("首写必须成功", ModSettingsStore.write(rt2, "mod.a", values))
            assertEquals(values, ModSettingsStore.read(rt2, "mod.a"))
            // 键排序落盘 ⇒ 盘上字节 = encode(values)（golden 可依赖）。
            val file = ModSettingsStore.settingsFile(rt2, "mod.a")
            assertEquals(
                ModSettingsCodec.encode(values),
                String(file.readBytes(), StandardCharsets.UTF_8),
            )
            val first = file.readBytes()
            assertTrue(ModSettingsStore.saveValues(rt2, "mod.a", mapOf("cfg.volume" to "9")))
            assertEquals("9", ModSettingsStore.read(rt2, "mod.a")["cfg.volume"])
            assertEquals("合并写入不得丢其它键", "改过的标签", ModSettingsStore.read(rt2, "mod.a")["cfg.label"])
            val after = file.readBytes()
            assertTrue("覆盖写后文件必须仍在", after.isNotEmpty())
            assertTrue("整档必须以 LF 收口", String(after, StandardCharsets.UTF_8).endsWith("\n"))
            assertFalse("值变了盘上字节必须变", first.contentEquals(after))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `编码确定性不受插入序影响`() {
        val a = linkedMapOf("b.key" to "2", "a.key" to "1")
        val b = linkedMapOf("a.key" to "1", "b.key" to "2")
        assertEquals(ModSettingsCodec.encode(a), ModSettingsCodec.encode(b))
        assertEquals(ModSettingsCodec.encode(a), ModSettingsCodec.encode(a))
    }

    // ---------------- 判据 2：坏文件一律空表（不抛） ----------------

    @Test
    fun `坏文件逐类读成空表且不抛`() {
        val root = tempRoot("rt2-settings-corrupt")
        val rt2 = rt2Of(root)
        try {
            val file = ModSettingsStore.settingsFile(rt2, "mod.a")
            val cases = linkedMapOf(
                "头不对" to "这不是设置文件\n",
                "没有收口 LF" to "RT2SETTINGS/1\n61623d31",
                "零行" to "",
                "hex 奇数长度" to "RT2SETTINGS/1\n61623d3\n",
                "hex 非法字符" to "RT2SETTINGS/1\nzz=31\n",
                "重复键" to "RT2SETTINGS/1\n61623d31\n61623d32\n",
                "非法 UTF-8" to "RT2SETTINGS/1\nff=31\n",
                "行内没有等号" to "RT2SETTINGS/1\n616231\n",
                "键为空" to "RT2SETTINGS/1\n=31\n",
            )
            for ((name, text) in cases) {
                file.writeText(text, StandardCharsets.UTF_8)
                assertEquals("坏文件（$name）必须读成空表", emptyMap<String, String>(), ModSettingsStore.read(rt2, "mod.a"))
            }
            // 缺失文件同口径。
            file.delete()
            assertEquals(emptyMap<String, String>(), ModSettingsStore.read(rt2, "mod.a"))
            // 超大文件（> 1MB）也走空表，不进解析。
            file.writeBytes(ByteArray((1 shl 20) + 1) { 'A'.code.toByte() })
            assertEquals(emptyMap<String, String>(), ModSettingsStore.read(rt2, "mod.a"))
        } finally {
            root.deleteRecursively()
        }
    }

    // ---------------- 判据 3：原子写 ----------------

    @Test
    fun `写后不留临时文件且失败不留半截正式文件`() {
        val root = tempRoot("rt2-settings-atomic")
        val rt2 = rt2Of(root)
        try {
            assertTrue(ModSettingsStore.write(rt2, "mod.a", mapOf("k" to "v")))
            val leftovers = rt2.listFiles().orEmpty().filter { it.name.startsWith(".") }
            assertTrue("原子写完成后不得留临时文件：${leftovers.map { it.name }}", leftovers.isEmpty())
            // 目录位置被普通文件占住 ⇒ 写失败返回 false（不炸、不产生半截文件）。
            val blockedRoot = tempRoot("rt2-settings-blocked")
            try {
                val blocked = File(blockedRoot, "mods/rt2")
                checkNotNull(blocked.parentFile).mkdirs()
                blocked.writeText("not a dir")
                assertFalse("建目录失败必须返回 false", ModSettingsStore.write(blocked, "mod.a", mapOf("k" to "v")))
                assertTrue("失败不得落正式文件", ModSettingsStore.read(blocked, "mod.a").isEmpty())
            } finally {
                blockedRoot.deleteRecursively()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `包名含路径分隔符或为空直接拒载`() {
        val root = tempRoot("rt2-settings-path")
        try {
            val rt2 = rt2Of(root)
            for (bad in listOf("", " ", "../escape", "a/b", "a\\b", ".", "..")) {
                try {
                    ModSettingsStore.settingsFile(rt2, bad)
                    fail("非法 packageId 必须拒载：$bad")
                } catch (expected: IllegalArgumentException) {
                    // 预期：唯一写盘入口挡住路径穿越。
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    // ---------------- 判据 4：快照面（attach 注入的数据源） ----------------

    @Test
    fun `快照带声明与持久值且跳过停用包`() {
        val root = tempRoot("rt2-settings-snapshots")
        try {
            val rt2 = rt2Of(root)
            writeManifest(rt2, settingsManifest("mod.a"))
            writeManifest(rt2, ModuleDescriptor(packageId = "mod.plain"))
            writeManifest(rt2, settingsManifest("mod.off"))
            assertTrue(ModSettingsStore.saveValues(rt2, "mod.a", mapOf("cfg.volume" to "8")))
            assertTrue(ModSettingsStore.saveValues(rt2, "mod.off", mapOf("cfg.volume" to "2")))
            assertTrue(ModEnablement.setEnabled(rt2, "mod.off", false))

            val snapshots = ModSettingsStore.snapshotsFor(root)

            assertEquals(setOf("mod.a", "mod.plain"), snapshots.keys)
            val a = snapshots.getValue("mod.a")
            assertEquals("mod.a", a.packageId)
            assertEquals(listOf("cfg.volume", "cfg.auto", "cfg.label", "cfg.mode"), a.declaredKeys)
            assertEquals("8", a.resolveText("cfg.volume"))
            assertEquals("未改的键回落声明默认值", "true", a.resolveText("cfg.auto"))
            // 无声明包也产快照（空声明）——作者读未声明键拿到的是「未声明」而不是「未装配」。
            val plain = snapshots.getValue("mod.plain")
            assertTrue(plain.declaredKeys.isEmpty())
            try {
                plain.resolveText("cfg.volume")
                fail("无声明包读任何键都必须拒")
            } catch (expected: IllegalArgumentException) {
                assertTrue("错误要点名包与键：${expected.message}", expected.message.orEmpty().contains("mod.plain"))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `改值后重新取快照读到新值`() {
        val root = tempRoot("rt2-settings-refresh")
        try {
            val rt2 = rt2Of(root)
            writeManifest(rt2, settingsManifest("mod.a"))
            assertEquals("5", ModSettingsStore.snapshotsFor(root).getValue("mod.a").resolveText("cfg.volume"))

            assertTrue(ModSettingsStore.saveValues(rt2, "mod.a", mapOf("cfg.volume" to "9")))
            assertEquals(
                "下一次 attach 读到新值（重新取快照即新值）",
                "9",
                ModSettingsStore.snapshotsFor(root).getValue("mod.a").resolveText("cfg.volume"),
            )
            // 坏值写得进文件但读侧回退默认（类型合法性归 core 快照）。
            assertTrue(ModSettingsStore.saveValues(rt2, "mod.a", mapOf("cfg.volume" to "99")))
            assertEquals("5", ModSettingsStore.snapshotsFor(root).getValue("mod.a").resolveText("cfg.volume"))
        } finally {
            root.deleteRecursively()
        }
    }

    // ---------------- 判据 5：恢复默认（整包删侧车） ----------------

    @Test
    fun `恢复默认删整包侧车且再读为空表`() {
        val root = tempRoot("rt2-settings-reset")
        try {
            val rt2 = rt2Of(root)
            assertTrue(ModSettingsStore.saveValues(rt2, "mod.a", mapOf("cfg.volume" to "8", "cfg.label" to "改过")))
            assertTrue(ModSettingsStore.saveValues(rt2, "mod.b", mapOf("cfg.volume" to "2")))
            val file = ModSettingsStore.settingsFile(rt2, "mod.a")
            assertTrue("前置：侧车必须在盘上", file.isFile)

            assertTrue("恢复默认必须成功", ModSettingsStore.resetToDefaults(rt2, "mod.a"))

            assertFalse("侧车必须整份删掉（不是清空后重写）", file.exists())
            assertTrue("删后读 = 没有已保存值（所有键回默认）", ModSettingsStore.read(rt2, "mod.a").isEmpty())
            assertEquals("别的包设置不受影响", "2", ModSettingsStore.read(rt2, "mod.b")["cfg.volume"])
            // 幂等：文件已缺失时再恢复仍成功且不抛（已经是默认态）。
            assertTrue(ModSettingsStore.resetToDefaults(rt2, "mod.a"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `恢复默认后快照回落声明默认值`() {
        val root = tempRoot("rt2-settings-reset-snapshot")
        try {
            val rt2 = rt2Of(root)
            writeManifest(rt2, settingsManifest("mod.a"))
            assertTrue(ModSettingsStore.saveValues(rt2, "mod.a", mapOf("cfg.volume" to "9")))
            assertEquals(
                "前置：改过的值进了快照",
                "9",
                ModSettingsStore.snapshotsFor(root).getValue("mod.a").resolveText("cfg.volume"),
            )

            assertTrue(ModSettingsStore.resetToDefaults(rt2, "mod.a"))

            val restored = ModSettingsStore.snapshotsFor(root).getValue("mod.a")
            assertTrue("恢复后持久值面为空", restored.persistedValues.isEmpty())
            assertEquals("声明键回落作者声明的默认值 5", "5", restored.resolveText("cfg.volume"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `坏manifest在快照面整抛与attach同口径`() {
        val root = tempRoot("rt2-settings-bad-manifest")
        try {
            val rt2 = rt2Of(root)
            File(rt2, "mod.broken" + ModRuntimeAssembly.MANIFEST_EXTENSION)
                .writeText("这不是 manifest", StandardCharsets.UTF_8)
            try {
                ModSettingsStore.snapshotsFor(root)
                fail("坏 manifest 必须整抛（attach 的口径：不半装、不静默跳过）")
            } catch (expected: ManifestFormatException) {
                // 预期：装载相位的显式拒绝。
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
