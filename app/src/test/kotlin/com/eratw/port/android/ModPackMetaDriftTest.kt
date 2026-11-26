package com.eratw.port.android

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 双源元数据漂移检测（[packMetaDriftOf]）的出口判据：字段集合、严重度分档、形态分类、稳定顺序。
 *
 * 纯 JVM：直接构造 [PackMeta] / [InnerManifestFields] / [PackRootFields]，不碰 Android 类；
 * 只有 [packRootFieldsOf] 的坏档用例才造 zip 字节（写死假 JSON，**不依赖** `pack-mod` 真实产物：
 * 包根写侧本代不输出 `entrypoint` / `apiVersion`，内层单源正是要锁的预期形态）。
 */
class ModPackMetaDriftTest {

    @Test
    fun `两侧完全一致——零漂移`() {
        val meta = PackMeta(
            PKG,
            V1,
            V1,
            ignored = null,
            evidence = PackMetaEvidence(compiledSurface = SURFACE_ENGINE),
            minApi = MIN_API_OLD,
        )
        val fields = InnerManifestFields(V1, SURFACE_ENGINE, MIN_API_OLD, ENTRY, API_OLD)
        val packRoot = PackRootFields(ENTRY, API_OLD)
        assertTrue("两侧五字段全同不得上报", packMetaDriftOf(meta, fields, packRoot).isEmpty())
    }

    @Test
    fun `compiledSurface 冲突——ERROR 级 CONFLICT`() {
        val meta = PackMeta(
            PKG,
            V1,
            V1,
            ignored = null,
            evidence = PackMetaEvidence(compiledSurface = SURFACE_ENGINE),
        )
        val fields = InnerManifestFields(V1, SURFACE_CODE, null)
        assertEquals(
            "闸判据 surface 与真跑代码的 surface 各说各话必须按错误级上报",
            listOf(
                PackMetaDrift(
                    field = "compiledSurface",
                    severity = PackMetaDriftSeverity.ERROR,
                    kind = PackMetaDriftKind.CONFLICT,
                    packMetaValue = SURFACE_ENGINE.toString(),
                    innerValue = SURFACE_CODE.toString(),
                ),
            ),
            packMetaDriftOf(meta, fields, PackRootFields()),
        )
    }

    @Test
    fun `minApi 冲突——ERROR 级 CONFLICT`() {
        val meta = PackMeta(
            PKG,
            V1,
            V1,
            ignored = null,
            evidence = PackMetaEvidence(compiledSurface = SURFACE_ENGINE),
            minApi = MIN_API_OLD,
        )
        val fields = InnerManifestFields(V1, SURFACE_ENGINE, MIN_API_NEW)
        assertEquals(
            "装配期引擎下限与代码清单自述不一致按错误级上报",
            listOf(
                PackMetaDrift(
                    field = "minApi",
                    severity = PackMetaDriftSeverity.ERROR,
                    kind = PackMetaDriftKind.CONFLICT,
                    packMetaValue = MIN_API_OLD.toString(),
                    innerValue = MIN_API_NEW.toString(),
                ),
            ),
            packMetaDriftOf(meta, fields, PackRootFields()),
        )
    }

    @Test
    fun `entrypoint 冲突——ERROR 级 CONFLICT`() {
        val meta = PackMeta(PKG, V1, V1, ignored = null)
        val fields = InnerManifestFields(null, null, null, ENTRY, null)
        assertEquals(
            "装配期 loadClass 的入口与真跑代码自述不同 = 装不起来，按错误级上报",
            listOf(
                PackMetaDrift(
                    field = "entrypoint",
                    severity = PackMetaDriftSeverity.ERROR,
                    kind = PackMetaDriftKind.CONFLICT,
                    packMetaValue = OTHER_ENTRY,
                    innerValue = ENTRY,
                ),
            ),
            packMetaDriftOf(meta, fields, PackRootFields(OTHER_ENTRY, null)),
        )
    }

    @Test
    fun `version 冲突——WARN 级 CONFLICT`() {
        val meta = PackMeta(
            PKG,
            V1,
            V1,
            ignored = null,
            evidence = PackMetaEvidence(compiledSurface = SURFACE_ENGINE),
        )
        val fields = InnerManifestFields(V2, SURFACE_ENGINE, null)
        assertEquals(
            "版本号只影响读数，不一致按信息级上报",
            listOf(
                PackMetaDrift(
                    field = "version",
                    severity = PackMetaDriftSeverity.WARN,
                    kind = PackMetaDriftKind.CONFLICT,
                    packMetaValue = V1,
                    innerValue = V2,
                ),
            ),
            packMetaDriftOf(meta, fields, PackRootFields()),
        )
    }

    @Test
    fun `apiVersion 冲突——WARN 级 CONFLICT`() {
        val meta = PackMeta(PKG, V1, V1, ignored = null)
        val fields = InnerManifestFields(null, null, null, null, API_NEW)
        assertEquals(
            "契约版本两边各说各话多半只是作者声明滞后，按信息级上报",
            listOf(
                PackMetaDrift(
                    field = "apiVersion",
                    severity = PackMetaDriftSeverity.WARN,
                    kind = PackMetaDriftKind.CONFLICT,
                    packMetaValue = API_OLD.toString(),
                    innerValue = API_NEW.toString(),
                ),
            ),
            packMetaDriftOf(meta, fields, PackRootFields(apiVersion = API_OLD)),
        )
    }

    @Test
    fun `多字段同时漂移——按 ABI 先稳定排序`() {
        val meta = PackMeta(
            PKG,
            V1,
            V1,
            ignored = null,
            evidence = PackMetaEvidence(compiledSurface = SURFACE_ENGINE),
            minApi = MIN_API_OLD,
        )
        val fields = InnerManifestFields(V2, SURFACE_CODE, MIN_API_NEW, ENTRY, API_NEW)
        val drifts = packMetaDriftOf(meta, fields, PackRootFields(OTHER_ENTRY, API_OLD))
        assertEquals("五个字段各报一条", 5, drifts.size)
        assertEquals(
            "顺序必须稳定：ABI/入口字段在前（compiledSurface → minApi → entrypoint → version → apiVersion）",
            listOf("compiledSurface", "minApi", "entrypoint", "version", "apiVersion"),
            drifts.map { it.field },
        )
        assertEquals(
            "CONFLICT 严重度只由字段类决定（入口 ERROR、信息类 WARN）",
            listOf(
                PackMetaDriftSeverity.ERROR,
                PackMetaDriftSeverity.ERROR,
                PackMetaDriftSeverity.ERROR,
                PackMetaDriftSeverity.WARN,
                PackMetaDriftSeverity.WARN,
            ),
            drifts.map { it.severity },
        )
    }

    @Test
    fun `内层未声明字段、包侧有值——不报`() {
        val meta = PackMeta(
            PKG,
            V1,
            V1,
            ignored = null,
            evidence = PackMetaEvidence(compiledSurface = SURFACE_ENGINE),
            minApi = MIN_API_OLD,
        )
        val fields = InnerManifestFields(version = null, compiledSurface = null, minApi = null)
        assertTrue(
            "包侧有值、代码清单沉默不构成矛盾（只取证的检测面不刷噪声）",
            packMetaDriftOf(meta, fields, PackRootFields(ENTRY, API_OLD)).isEmpty(),
        )
    }

    @Test
    fun `内层声明 ABI 值、包侧未声明——INNER_ONLY 且 ERROR`() {
        val meta = PackMeta(PKG, V1, V1, ignored = null)
        val fields = InnerManifestFields(null, SURFACE_CODE, null)
        assertEquals(
            "代码清单自述 surface 而闸读不到，属静默错判风险",
            listOf(
                PackMetaDrift(
                    field = "compiledSurface",
                    severity = PackMetaDriftSeverity.ERROR,
                    kind = PackMetaDriftKind.INNER_ONLY,
                    packMetaValue = null,
                    innerValue = SURFACE_CODE.toString(),
                ),
            ),
            packMetaDriftOf(meta, fields, PackRootFields()),
        )
    }

    @Test
    fun `内层声明 version、包侧未采信——INNER_ONLY 且 WARN`() {
        val meta = PackMeta(PKG, declaredVersion = null, version = "1", ignored = PackMetaIgnore.MISSING)
        val fields = InnerManifestFields(V2, null, null)
        assertEquals(
            "元数据缺失时内层自述版本属信息级单边漂移",
            listOf(
                PackMetaDrift(
                    field = "version",
                    severity = PackMetaDriftSeverity.WARN,
                    kind = PackMetaDriftKind.INNER_ONLY,
                    packMetaValue = null,
                    innerValue = V2,
                ),
            ),
            packMetaDriftOf(meta, fields, PackRootFields()),
        )
    }

    @Test
    fun `内层声明 entrypoint、包根未声明——INNER_ONLY 且 INFO`() {
        val meta = PackMeta(PKG, V1, V1, ignored = null)
        val fields = InnerManifestFields(null, null, null, ENTRY, null)
        assertEquals(
            "包根写侧本代不输出 entrypoint：内层单源属预期形态，记 INFO 留事实而不当缺陷报",
            listOf(
                PackMetaDrift(
                    field = "entrypoint",
                    severity = PackMetaDriftSeverity.INFO,
                    kind = PackMetaDriftKind.INNER_ONLY,
                    packMetaValue = null,
                    innerValue = ENTRY,
                ),
            ),
            packMetaDriftOf(meta, fields, PackRootFields()),
        )
    }

    @Test
    fun `内层声明 apiVersion、包根未声明——INNER_ONLY 且 INFO`() {
        val meta = PackMeta(PKG, V1, V1, ignored = null)
        val fields = InnerManifestFields(null, null, null, null, API_NEW)
        assertEquals(
            "包根写侧本代不输出 apiVersion：内层单源属预期形态，记 INFO 留事实而不当缺陷报",
            listOf(
                PackMetaDrift(
                    field = "apiVersion",
                    severity = PackMetaDriftSeverity.INFO,
                    kind = PackMetaDriftKind.INNER_ONLY,
                    packMetaValue = null,
                    innerValue = API_NEW.toString(),
                ),
            ),
            packMetaDriftOf(meta, fields, PackRootFields()),
        )
    }

    @Test
    fun `内层版本空串与 minApi 0——按未声明处理`() {
        val meta = PackMeta(
            PKG,
            V1,
            V1,
            ignored = null,
            evidence = PackMetaEvidence(compiledSurface = SURFACE_ENGINE),
        )
        val fields = InnerManifestFields("", SURFACE_ENGINE, 0, "   ", 0)
        assertTrue(
            "空串 / 0 / 纯空白都是「本侧未声明」口径，不产生漂移",
            packMetaDriftOf(meta, fields, PackRootFields()).isEmpty(),
        )
    }

    @Test
    fun `packageId 不同不参与对账`() {
        val meta = PackMeta(
            "kojo-otherpkg",
            V1,
            V1,
            ignored = null,
            evidence = PackMetaEvidence(compiledSurface = SURFACE_ENGINE),
        )
        val fields = InnerManifestFields(V1, SURFACE_ENGINE, null)
        val drifts = packMetaDriftOf(meta, fields, PackRootFields())
        assertTrue("身份一致性由导入链 IDENTITY 分档单独判，不重复上报", drifts.isEmpty())
        assertTrue(
            "口径锁：packageId 不在对账面（[InnerManifestFields] 无该字段），任何字段都不得是它",
            drifts.none { it.field == "packageId" },
        )
    }

    @Test
    fun `坏内层 JSON——解析返回 null 且不上抛`() {
        assertNull("坏 JSON 属身份阶段已拒收的形态，取证面只防御性返回 null", innerManifestFieldsOf("{oops".toByteArray()))
        assertNull("顶层非对象（数组）同样按不可解析处理", innerManifestFieldsOf("[]".toByteArray()))
        assertNull("缺字节（无内层清单）返回 null", innerManifestFieldsOf(null))
    }

    @Test
    fun `内层清单解析——entrypoint 去空白、apiVersion 取正数`() {
        val bytes = """
            {"version":"2.0","compiledSurface":20,"minApi":4,"entrypoint":"  demo.Entry  ","apiVersion":2}
        """.trimIndent().toByteArray()
        val fields = innerManifestFieldsOf(bytes)!!
        assertEquals("版本号原样取", V2, fields.version)
        assertEquals("ABI surface 取整", SURFACE_CODE, fields.compiledSurface)
        assertEquals("引擎下限取正数", MIN_API_NEW, fields.minApi)
        assertEquals("入口类去首尾空白", ENTRY, fields.entrypoint)
        assertEquals("契约版本取正数", API_NEW, fields.apiVersion)
    }

    @Test
    fun `包根解析——坏 zip 坏 JSON 未知 schema 一律空且不上抛`() {
        assertTrue(
            "没有任何包：全空",
            packRootFieldsOf(emptyList()).let { it.entrypoint == null && it.apiVersion == null },
        )
        assertTrue(
            "非 zip 字节：取证面不抛，只当没有元数据",
            packRootFieldsOf(listOf(byteArrayOf(1, 2, 3, 4))).let { it.entrypoint == null && it.apiVersion == null },
        )
        assertTrue(
            "坏 JSON：全空",
            packRootFieldsOf(listOf(zipText(PACK_META_ENTRY_NAME, "{oops"))).let {
                it.entrypoint == null && it.apiVersion == null
            },
        )
        assertTrue(
            "不认识的 schema：整份不采信（不猜未来格式）",
            packRootFieldsOf(
                listOf(zipText(PACK_META_ENTRY_NAME, """{"schema":2,"entrypoint":"demo.Entry"}""")),
            ).let { it.entrypoint == null && it.apiVersion == null },
        )
    }

    @Test
    fun `包根解析——取第一个带元数据的包且只认 schema 1`() {
        val packRoot = "{\"schema\":1,\"packageId\":\"$PKG\",\"version\":\"1.0\"," +
            "\"entrypoint\":\"  demo.Entry  \",\"apiVersion\":2}"
        val parsed = packRootFieldsOf(
            listOf(
                byteArrayOf(1, 2, 3), // 坏 zip：跳过，继续看下一个
                zipText(PACK_META_ENTRY_NAME, packRoot),
                zipText(PACK_META_ENTRY_NAME, """{"schema":1,"entrypoint":"ignored.Other"}"""),
            ),
        )
        assertEquals("取第一个可解析的包根：入口去空白", ENTRY, parsed.entrypoint)
        assertEquals("契约版本取正数", API_NEW, parsed.apiVersion)
    }

    private companion object {
        const val PKG = "kojo-1a2b3c4d"
        const val V1 = "1.0"
        const val V2 = "2.0"
        const val SURFACE_ENGINE = 18
        const val SURFACE_CODE = 20
        const val MIN_API_OLD = 3
        const val MIN_API_NEW = 4
        const val ENTRY = "demo.Entry"
        const val OTHER_ENTRY = "demo.OtherEntry"
        const val API_OLD = 1
        const val API_NEW = 2

        /** 单条目 zip（条目名 = 包根固定名，内容 = 假 JSON 文本）。 */
        fun zipText(entryName: String, text: String): ByteArray {
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry(entryName))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
            return out.toByteArray()
        }
    }
}
