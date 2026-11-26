package com.eratw.port.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内层清单解析口径（[innerManifestFieldsOf]）与漂移日志文案（[packMetaDriftLogLine]）的稳定面。
 *
 * 日志文案是对外承诺（排障 grep / 断言关键子串），故这里用**全文相等**锁格式，不做松断言。
 */
class ModPackMetaDriftLogTest {

    @Test
    fun `坏 JSON——解析为 null`() {
        assertNull(
            "坏档早已在身份校验阶段判 STORAGE，解析面只做防御性返回 null",
            innerManifestFieldsOf("{not json".toByteArray(Charsets.UTF_8)),
        )
    }

    @Test
    fun `顶层非对象——解析为 null`() {
        assertNull(innerManifestFieldsOf("[1,2]".toByteArray(Charsets.UTF_8)))
        assertNull(innerManifestFieldsOf(null))
    }

    @Test
    fun `键类型不符或缺失——对应字段为 null`() {
        val fields = innerManifestFieldsOf(
            """{"version":123,"compiledSurface":"20","minApi":-2}""".toByteArray(Charsets.UTF_8),
        )
        assertTrue("顶层是对象 ⇒ 字段集合存在", fields != null)
        assertNull("version 必须是字符串", fields!!.version)
        assertNull("compiledSurface 必须是整数", fields.compiledSurface)
        assertNull("负数 minApi 视为未声明", fields.minApi)
    }

    @Test
    fun `日志文案固定格式——含严重度、字段名与两侧值`() {
        val drift = PackMetaDrift(
            field = "compiledSurface",
            severity = PackMetaDriftSeverity.ERROR,
            kind = PackMetaDriftKind.CONFLICT,
            packMetaValue = "18",
            innerValue = "20",
        )
        assertEquals(
            "格式是排障 grep 的承诺，改动必须同步本断言",
            "mod.import 元数据双源漂移 severity=ERROR field=compiledSurface " +
                "kind=CONFLICT eratw-pack=18 内层manifest=20",
            packMetaDriftLogLine(drift),
        )
    }

    @Test
    fun `缺失侧日志写未声明占位`() {
        val drift = PackMetaDrift(
            field = "minApi",
            severity = PackMetaDriftSeverity.ERROR,
            kind = PackMetaDriftKind.INNER_ONLY,
            packMetaValue = null,
            innerValue = "3",
        )
        val line = packMetaDriftLogLine(drift)
        assertTrue("包侧未声明显式写占位，排障一眼可辨", line.contains("eratw-pack=<未声明>"))
        assertTrue(line.contains("kind=INNER_ONLY"))
    }

    @Test
    fun `声明值含控制字符——压成单行`() {
        val drift = PackMetaDrift(
            field = "version",
            severity = PackMetaDriftSeverity.WARN,
            kind = PackMetaDriftKind.CONFLICT,
            packMetaValue = "1.0",
            innerValue = "2.0\n3.0",
        )
        val line = packMetaDriftLogLine(drift)
        assertFalse("恶意清单不得用换行污染 logcat 行结构", line.contains("\n"))
        assertTrue(line.contains("内层manifest=2.0?3.0"))
    }
}
