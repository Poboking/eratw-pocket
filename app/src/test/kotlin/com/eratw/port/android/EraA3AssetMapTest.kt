package com.eratw.port.android

import com.eratw.port.core.mod.EraA3AssetOverlay
import com.eratw.port.core.mod.ModDx3AssetMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * ERA-A3 二期 A：侧车编解码往返、坏档 fail-closed、LinkOrder 后者胜语义。
 *
 * 全部走 [ModDx3AssetMap] / [EraA3AssetOverlay] 的公开面（core 纯 JVM，app 测试直调），
 * 与契约 §4 的 `--tests "*AssetMap*"` 对齐。
 */
class EraA3AssetMapTest {

    private fun entry(kind: ModDx3AssetMap.Kind, official: String, mod: String) =
        ModDx3AssetMap.Entry(kind, official, mod)

    @Test
    fun `侧车编码解码往返逐字节不变且稳定序`() {
        val entries = listOf(
            entry(ModDx3AssetMap.Kind.BGM, "gamebgm/x.mp3", "sound/custom.mp3"),
            entry(ModDx3AssetMap.Kind.SPRITE, "CHARA_1", "resources/chara/a.png"),
            entry(ModDx3AssetMap.Kind.SPRITE, "BG_1", "resources/背景图/b.png"),
        )
        val text = ModDx3AssetMap.encode("kojo-abc12345", entries)
        val decoded = ModDx3AssetMap.decode(text)

        assertTrue("往返必须采信：${decoded.diagnostics}", decoded.ok)
        assertEquals("kojo-abc12345", decoded.packageId)
        // 稳定序：SPRITE 先（按 kind 序），同类别按官方名；BGM 后。再编一次逐字节相同。
        assertEquals(text, ModDx3AssetMap.encode(decoded.packageId!!, decoded.entries))
        assertEquals(3, decoded.entries.size)
        assertEquals("BG_1", decoded.entries[0].officialName)
        assertEquals("CHARA_1", decoded.entries[1].officialName)
        assertEquals(ModDx3AssetMap.Kind.BGM, decoded.entries[2].kind)
    }

    @Test
    fun `坏侧车一律 fail-closed 空表不部分采信`() {
        val good = entry(ModDx3AssetMap.Kind.SPRITE, "A_1", "resources/a.png")
        val cases = listOf(
            "ERATW-ASSETMAP/1\nkojo-abc12345\n0\n", // 合法空表基线（对照组）
            "WRONG-MAGIC\nkojo-abc12345\n1\nSPRITE\tA_1\tresources/a.png\n",
            "ERATW-ASSETMAP/1\nkojo-abc12345\n2\nSPRITE\tA_1\tresources/a.png\n", // 条目数不符
            "ERATW-ASSETMAP/1\nkojo-abc12345\n1\nSPRITE\t../evil\tresources/a.png\n", // 非法官方名
            "ERATW-ASSETMAP/1\nkojo-abc12345\n1\nSPRITE\tA_1\t..\\evil\n", // 反斜杠非法
            "ERATW-ASSETMAP/1\nkojo-abc12345\n2\nSPRITE\tA_1\ta.png\nSPRITE\tA_1\tb.png\n", // 重复声明
            "ERATW-ASSETMAP/1\nkojo-abc12345\n1\nUNKNOWN\tA_1\ta.png\n", // 未知 kind
        )
        assertEquals("对照组应采信空表", 0, ModDx3AssetMap.decode(cases[0]).entries.size)
        assertTrue("对照组必须 ok", ModDx3AssetMap.decode(cases[0]).ok)
        for (i in 1 until cases.size) {
            val r = ModDx3AssetMap.decode(cases[i])
            assertFalse("坏档 #$i 必须 ok=false", r.ok)
            assertTrue("坏档 #$i 必须空表（不部分采信）", r.entries.isEmpty())
            assertTrue("坏档 #$i 必须带诊断（不静默吞错）", r.diagnostics.isNotEmpty())
        }
        // 合法单条仍能采信（确认上面的 bad 不是「全盘拒」的假绿）
        val ok = ModDx3AssetMap.decode(ModDx3AssetMap.encode("kojo-abc12345", listOf(good)))
        assertTrue(ok.ok)
        assertEquals(1, ok.entries.size)
    }

    @Test
    fun `资源名合法性严格拒绝穿越与盘符`() {
        assertTrue(ModDx3AssetMap.isValidName("resources/背景图/a.png"))
        assertTrue(ModDx3AssetMap.isValidName("A_1"))
        for (bad in listOf("", "/a", "a/", "a//b", "a/../b", "a\\b", "C:/x", "..", ".", "a/./b")) {
            assertFalse("[$bad] 应判非法", ModDx3AssetMap.isValidName(bad))
        }
    }

    @Test
    fun `逐包聚合按 LinkOrder 后者胜并跳过坏侧车`() {
        val a = entry(ModDx3AssetMap.Kind.SPRITE, "A_1", "resources/from_a.png")
        val b = entry(ModDx3AssetMap.Kind.SPRITE, "A_1", "resources/from_b.png")
        val sidecarA = ModDx3AssetMap.decode(ModDx3AssetMap.encode("kojo-a", listOf(a)))
        val sidecarB = ModDx3AssetMap.decode(ModDx3AssetMap.encode("kojo-b", listOf(b)))
        val broken = ModDx3AssetMap.Sidecar("kojo-bad", emptyList(), false, listOf("损坏"))
        val read: (String) -> ModDx3AssetMap.Sidecar = { id ->
            when (id) {
                "kojo-a" -> sidecarA
                "kojo-b" -> sidecarB
                "kojo-bad" -> broken
                else -> ModDx3AssetMap.Sidecar(id, emptyList(), true, emptyList())
            }
        }

        // 链接序 A,B ⇒ B 后链接、后者胜
        val ab = EraA3AssetOverlay.collect(listOf("kojo-a", "kojo-b"), read)
        assertEquals("resources/from_b.png", ab.sprite("A_1")?.modName)
        // 链接序 B,A ⇒ A 后链接、后者胜（不是「配置靠前优先」）
        val ba = EraA3AssetOverlay.collect(listOf("kojo-b", "kojo-a"), read)
        assertEquals("resources/from_a.png", ba.sprite("A_1")?.modName)
        // 坏侧车包跳过、不部分采信
        val withBroken = EraA3AssetOverlay.collect(listOf("kojo-a", "kojo-bad"), read)
        assertEquals("resources/from_a.png", withBroken.sprite("A_1")?.modName)
        // 空表 = 映射关掉：sprite / sound 都查不到
        assertTrue(EraA3AssetOverlay.AssetMap.EMPTY.sprite("A_1") == null)
        assertTrue(EraA3AssetOverlay.AssetMap.EMPTY.sound("gamebgm/x.mp3") == null)
    }

    @Test
    fun `BGM 名大小写回落命中覆盖`() {
        val e = entry(ModDx3AssetMap.Kind.BGM, "GameBGM/Bad.MP3", "sound/custom.mp3")
        val map = EraA3AssetOverlay.collect(listOf("kojo-x")) {
            ModDx3AssetMap.decode(ModDx3AssetMap.encode("kojo-x", listOf(e)))
        }
        assertEquals("sound/custom.mp3", map.sound("gamebgm/bad.mp3")?.modName)
        assertEquals("sound/custom.mp3", map.sound("GameBGM/Bad.MP3")?.modName)
    }

    @Test
    fun `read 从 rt2 目录读侧车 缺档为空坏档 WARN`() {
        val dir = Files.createTempDirectory("a3-assetmap").toFile()
        val warns = ArrayList<String>()
        // 缺档 = 空表、不告警（正常包可能无覆盖声明）
        val missing = ModDx3AssetMap.read(dir, "kojo-none", warns::add)
        assertTrue(missing.ok)
        assertTrue(missing.entries.isEmpty())
        assertTrue(warns.isEmpty())
        // 坏档 = ok=false + WARN 落痕（不静默吞错）
        File(dir, "kojo-bad${ModDx3AssetMap.SIDECAR_EXTENSION}").writeText("garbage", Charsets.UTF_8)
        val bad = ModDx3AssetMap.read(dir, "kojo-bad", warns::add)
        assertFalse(bad.ok)
        assertTrue(bad.entries.isEmpty())
        assertEquals(1, warns.size)
        // 包名不符 = 坏档（防错包侧车）
        File(dir, "kojo-x${ModDx3AssetMap.SIDECAR_EXTENSION}").writeText(
            ModDx3AssetMap.encode("kojo-y", listOf(entry(ModDx3AssetMap.Kind.SPRITE, "A", "a.png"))),
            Charsets.UTF_8,
        )
        assertFalse(ModDx3AssetMap.read(dir, "kojo-x", warns::add).ok)
        assertTrue(warns.size >= 2)
    }
}
