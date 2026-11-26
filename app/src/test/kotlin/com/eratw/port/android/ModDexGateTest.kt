package com.eratw.port.android

import android.content.SharedPreferences
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 路 B（dex 包）ABI 双闸的判定面（ADR-013 D2 闸 2）+ 信任确认标记（D3）+ 一键总闸缺省（D5，
 * 切片 S2/S3）。
 *
 * ## 覆盖的语义
 *
 * 1. `verdict` 三态：未声明 ⇒ `AllowedUndeclared`；声明值 ≤ 引擎 API ⇒ `Allowed`；
 *    声明值 > 引擎 API ⇒ `Rejected`（理由里两个版本号都在）；
 * 2. 判据是**单调增**的：surface 只会往后加，所以「更旧」永远放行，没有「查兼容表再拒」的第二真源
 *    ——compat 表只补拒绝文案，表里缺行 / 有坏行 / 整表为空，判定结果逐条不变；
 * 3. compat 表解析：注释 / 空行跳过、坏行计数但不抛、重复 surface 后值覆盖；
 * 4. 信任标记：`<rt2>/<packageId>.trusted` 存在即信任，包名形态闸复用 `ModEnablement`（不越界）；
 * 5. 一键总闸：缺省 **关**（`DEFAULT_MOD_DEX_LOADER = false`）、键名独立、读写回环；
 * 6. `eratw-pack.json` 的可选 `compiledSurface`：合法值原样解析；缺席 / 坏值只丢本字段，不连坐
 *    version / minApi（口径与 `minApi` / `resources` 相反，ADR-013 D2）。
 *
 * 只测纯函数与偏好层；S4 的 dex 装载接线不在此文件。
 */
class ModDexGateTest {

    // ------------------------------------------------------------------
    // 1. verdict 三态
    // ------------------------------------------------------------------

    @Test
    fun `未声明compiledSurface——老包按未声明放行`() {
        val verdict = ModDexGate.verdict(metaOf(compiledSurface = null), engineApiVersion = 17)

        assertEquals(ModDexGate.Verdict.AllowedUndeclared, verdict)
        assertTrue("未声明 = 老包向后兼容", verdict.isAllowed)
    }

    @Test
    fun `声明值不高过引擎API——相等与更旧都放行`() {
        for (surface in listOf(0, 5, 17)) {
            val verdict = ModDexGate.verdict(metaOf(compiledSurface = surface), engineApiVersion = 17)
            assertEquals("surface $surface ≤ 引擎 17 应放行", ModDexGate.Verdict.Allowed, verdict)
            assertTrue(verdict.isAllowed)
        }
    }

    @Test
    fun `声明值高过引擎API——拒绝且理由含两个版本号`() {
        val verdict = ModDexGate.verdict(metaOf(compiledSurface = 18), engineApiVersion = 17)

        assertTrue("太新的包不许装", verdict is ModDexGate.Verdict.Rejected)
        assertFalse(verdict.isAllowed)
        val reason = (verdict as ModDexGate.Verdict.Rejected).reason
        assertTrue("理由里要同时出现包编译期 surface 与当前引擎 API：$reason", reason.contains("18"))
        assertTrue("理由里要同时出现包编译期 surface 与当前引擎 API：$reason", reason.contains("17"))
        assertTrue("要能上诊断日志看懂处置方向：$reason", reason.contains("需要更新引擎"))
    }

    @Test
    fun `引擎API为负——非法入参拒绝而不是猜`() {
        val thrown = runCatching {
            ModDexGate.verdict(metaOf(compiledSurface = 1), engineApiVersion = -1)
        }.exceptionOrNull()

        assertTrue("引擎 API 版本必须非负", thrown is IllegalArgumentException)
    }

    // ------------------------------------------------------------------
    // 2. compat 表只是信息、不是第二真源
    // ------------------------------------------------------------------

    @Test
    fun `拒绝理由带compat提示——表里登记该surface时补一句但不改判定`() {
        val compat = ModDexGate.loadCompatTable("# surface\tminEngineApi\n18\t19\n")
        val withTable = ModDexGate.verdict(metaOf(compiledSurface = 18), engineApiVersion = 17, compat)
        val withoutTable = ModDexGate.verdict(metaOf(compiledSurface = 18), engineApiVersion = 17)

        assertTrue(withTable is ModDexGate.Verdict.Rejected)
        assertTrue(withoutTable is ModDexGate.Verdict.Rejected)
        val hinted = (withTable as ModDexGate.Verdict.Rejected).reason
        val plain = (withoutTable as ModDexGate.Verdict.Rejected).reason
        assertTrue("缺少降级提示：$hinted", hinted.contains("自引擎 v19 起提供"))
        assertTrue("提示必须追加在主判据之后：$hinted", hinted.startsWith(plain))
    }

    @Test
    fun `compat表坏行或缺行——判定结果逐条与空表一致`() {
        val broken = ModDexGate.loadCompatTable("18\nnot-a-number\t3\n-1\t2\n")
        val empty = ModDexGate.SurfaceCompat.EMPTY

        for ((surface, engine) in listOf(null to 17, 5 to 17, 17 to 17, 18 to 17, 18 to 19)) {
            assertEquals(
                "surface $surface / engine $engine 的判定不该被坏表改写",
                ModDexGate.verdict(metaOf(compiledSurface = surface), engine, empty),
                ModDexGate.verdict(metaOf(compiledSurface = surface), engine, broken),
            )
        }
    }

    @Test
    fun `compat表解析——注释空行跳过且读数正确`() {
        val compat = ModDexGate.loadCompatTable(
            """
            # 表头也是注释

            16	15
             18 	19
            """.trimIndent(),
        )

        assertFalse(compat.isEmpty)
        assertEquals(15, compat.minEngineApiFor(16))
        assertEquals(19, compat.minEngineApiFor(18))
        assertNull("未登记 ⇒ null，不猜", compat.minEngineApiFor(17))
        assertEquals("合法表没有坏行", 0, compat.malformedLineCount)
    }

    @Test
    fun `compat表坏行——列数不对与非数字与负值都计数且不抛`() {
        val text = buildString {
            append("16\t15\n")
            append("17\n")           // 少一列
            append("18\t19\t20\n")   // 多一列
            append("x\t15\n")        // surface 非数字
            append("18\ty\n")        // minEngineApi 非数字
            append("18\t-1\n")       // minEngineApi 负值
            append("-1\t15\n")       // surface 负值
            append("${PACK_META_MAX_COMPILED_SURFACE + 1}\t15\n") // surface 超上限
        }
        val compat = ModDexGate.loadCompatTable(text)

        assertEquals("坏行只计数、不影响判定", 7, compat.malformedLineCount)
        assertEquals("唯一合法的行照常入表", 15, compat.minEngineApiFor(16))
        assertNull("坏行不留残值", compat.minEngineApiFor(18))
    }

    @Test
    fun `compat表重复surface——后值覆盖前值`() {
        val compat = ModDexGate.loadCompatTable("18\t19\n18\t20\n")

        assertEquals("重复是笔误，后写的生效", 20, compat.minEngineApiFor(18))
        assertEquals(0, compat.malformedLineCount)
    }

    @Test
    fun `空compat文本——空表且没有坏行`() {
        val compat = ModDexGate.loadCompatTable("   \n\n# nothing\n")

        assertTrue(compat.isEmpty)
        assertEquals(0, compat.malformedLineCount)
        assertEquals(ModDexGate.SurfaceCompat.EMPTY.isEmpty, compat.isEmpty)
    }

    // ------------------------------------------------------------------
    // 3. 信任确认标记（D3）
    // ------------------------------------------------------------------

    @Test
    fun `信任标记名——包名加trusted后缀`() {
        assertEquals("eratw.family.trusted", ModDexGate.trustMarkerName("eratw.family"))
        assertEquals("kojo-Ab_9-x.y.trusted", ModDexGate.trustMarkerName("kojo-Ab_9-x.y"))
    }

    @Test
    fun `非法包名——拒绝拼路径且不越出rt2`() {
        val rt2 = tempDir()
        try {
            for (bad in listOf("", " ", "../evil", "a/b", "a\\b", ".hidden", "-lead")) {
                val thrown = runCatching { ModDexGate.trustMarkerName(bad) }.exceptionOrNull()
                assertTrue("包名「$bad」应被拒绝而不是拼成路径", thrown is IllegalArgumentException)
                val trusted = runCatching { ModDexGate.isTrusted(rt2, bad) }.exceptionOrNull()
                assertTrue("isTrusted 走同一形态闸：$bad", trusted is IllegalArgumentException)
            }
            assertEquals(
                "名字里不可能有分隔符 ⇒ 拼进 rt2 不会越界",
                rt2,
                File(rt2, ModDexGate.trustMarkerName("eratw.family")).parentFile,
            )
        } finally {
            rt2.deleteRecursively()
        }
    }

    @Test
    fun `信任标记存在即信任——缺失与是目录都不算`() {
        val rt2 = tempDir()
        try {
            assertFalse("没有标记 = 未确认", ModDexGate.isTrusted(rt2, "eratw.family"))

            File(rt2, "eratw.family.trusted").writeText("")
            assertTrue("标记存在 = 玩家已显式确认", ModDexGate.isTrusted(rt2, "eratw.family"))

            File(rt2, "eratw.other.trusted").mkdirs()
            assertFalse("是目录不是标记文件", ModDexGate.isTrusted(rt2, "eratw.other"))
        } finally {
            rt2.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // 4. 一键总闸缺省（D5）
    // ------------------------------------------------------------------

    @Test
    fun `总闸缺省是关且键名独立`() {
        val sp = FakeSharedPreferences()

        assertFalse("高风险面缺省必须关", UiPrefs.DEFAULT_MOD_DEX_LOADER)
        assertEquals("mod.dex.loader", UiPrefs.KEY_MOD_DEX_LOADER)
        assertFalse("从来没写过 ⇒ 关", UiPrefs.readModDexLoader(sp))
    }

    @Test
    fun `总闸写读回环`() {
        val sp = FakeSharedPreferences()

        UiPrefs.writeModDexLoader(sp, true)
        assertTrue(UiPrefs.readModDexLoader(sp))
        assertTrue("写的是自己的键，不是 mod_runtime", sp.map.containsKey(UiPrefs.KEY_MOD_DEX_LOADER))
        assertFalse("两闸分开拨", sp.map.containsKey("mod_runtime"))

        UiPrefs.writeModDexLoader(sp, false)
        assertFalse(UiPrefs.readModDexLoader(sp))
    }

    // ------------------------------------------------------------------
    // 5. 包元数据的 compiledSurface 字段
    // ------------------------------------------------------------------

    @Test
    fun `合法compiledSurface——原样解析且其余字段照常采信`() {
        val meta = metaOf(payload = metaJson(version = "2.0", minApiJson = "15", surfaceJson = "18"))

        assertEquals(18, meta.compiledSurface)
        assertEquals("版本照常采信", "2.0", meta.version)
        assertEquals("minApi 照常采信", 15, meta.minApi)
        assertNull("合法字段不拖累整份元数据", meta.ignored)
        assertTrue(meta.trusted)
    }

    @Test
    fun `缺compiledSurface——老包按未声明解析且其余字段不变`() {
        val meta = metaOf(payload = metaJson(version = "2.0", minApiJson = "15"))

        assertNull("缺字段 = 未声明", meta.compiledSurface)
        assertEquals("2.0", meta.version)
        assertEquals(15, meta.minApi)
        assertNull(meta.ignored)
    }

    @Test
    fun `坏compiledSurface——只丢本字段不连坐元数据`() {
        val badValues = listOf(
            "-1", // 负数
            "${PACK_META_MAX_COMPILED_SURFACE + 1}", // 超上限
            "\"18\"", // 字符串
            "1.5", // 非整数
            "null", // JSON null
            "{\"v\":18}", // 对象
        )
        for (bad in badValues) {
            val meta = metaOf(payload = metaJson(version = "2.0", minApiJson = "15", surfaceJson = bad))

            assertNull("坏值不采信：$bad", meta.compiledSurface)
            assertEquals("坏一个字段不该连累版本：$bad", "2.0", meta.version)
            assertEquals("坏一个字段不该连累 minApi：$bad", 15, meta.minApi)
            assertNull("整份元数据仍采信：$bad", meta.ignored)
        }
    }

    @Test
    fun `compiledSurface上限边界——上限内采信上限外忽略`() {
        assertEquals(
            "上限本身合法",
            PACK_META_MAX_COMPILED_SURFACE,
            metaOf(payload = metaJson(surfaceJson = "$PACK_META_MAX_COMPILED_SURFACE")).compiledSurface,
        )
        assertNull(
            "超上限按坏值忽略",
            metaOf(payload = metaJson(surfaceJson = "${PACK_META_MAX_COMPILED_SURFACE + 1}")).compiledSurface,
        )
    }

    @Test
    fun `compiledSurface坏值与minApi坏值——minApi仍按整份坏档忽略`() {
        val meta = metaOf(payload = metaJson(version = "2.0", minApiJson = "-1", surfaceJson = "18"))

        assertEquals("minApi 口径不变：出现就必须非负", PackMetaIgnore.MIN_API, meta.ignored)
        assertNull("整份未采信 ⇒ surface 也无处可依", meta.compiledSurface)
        assertEquals("1", meta.version)
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private fun tempDir(): File = Files.createTempDirectory("mod-dex-gate").toFile()

    private fun metaOf(compiledSurface: Int?): PackMeta =
        PackMeta(
            packageId = MODULE_PACKAGE_ID,
            declaredVersion = "2.0",
            version = "2.0",
            ignored = null,
            evidence = PackMetaEvidence(compiledSurface = compiledSurface),
        )

    private fun metaOf(payload: String): PackMeta = parsePackMeta(payload.toByteArray(Charsets.UTF_8))

    /** 与 pack-mod 同款固定键序 JSON；可选项以已经拼好的 JSON 字面量注入（非字符串类型用例用）。 */
    private fun metaJson(
        version: String = "2.0",
        minApiJson: String? = null,
        surfaceJson: String? = null,
    ): String = buildString {
        append("{\"schema\":1,\"packageId\":")
        append("\"" + MODULE_PACKAGE_ID + "\"")
        append(",\"version\":\"" + version + "\"")
        if (minApiJson != null) append(",\"minApi\":").append(minApiJson)
        if (surfaceJson != null) append(",\"compiledSurface\":").append(surfaceJson)
        append('}')
    }

    private class FakeSharedPreferences : SharedPreferences {
        val map = HashMap<String, Any?>()

        override fun getAll(): MutableMap<String, *> = map

        override fun getString(key: String?, defValue: String?): String? =
            map[key] as? String ?: defValue

        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            defValues

        override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            map[key] as? Boolean ?: defValue

        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        private inner class FakeEditor : SharedPreferences.Editor {
            private val pending = HashMap<String?, Any?>()
            private val removals = HashSet<String?>()

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                pending[key] = value
                return this
            }

            override fun putStringSet(
                key: String?,
                values: MutableSet<String>?,
            ): SharedPreferences.Editor {
                pending[key] = values
                return this
            }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                pending[key] = value
                return this
            }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                pending[key] = value
                return this
            }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                pending[key] = value
                return this
            }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                pending[key] = value
                return this
            }

            override fun remove(key: String?): SharedPreferences.Editor {
                removals.add(key)
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                pending.clear()
                removals.clear()
                map.clear()
                return this
            }

            override fun commit(): Boolean {
                flush()
                return true
            }

            override fun apply() = flush()

            private fun flush() {
                for (k in removals) if (k != null) map.remove(k)
                for ((k, v) in pending) if (k != null) map[k] = v
                pending.clear()
                removals.clear()
            }
        }
    }

    private companion object {
        private const val MODULE_PACKAGE_ID = "eratw.packmeta"
    }
}
