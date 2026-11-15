package com.eratw.port.android

import com.eratw.port.core.proc.EngineStamp
import com.eratw.port.core.proc.EngineStampRead
import com.eratw.port.core.proc.RuntimeCompatibilityProfile
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ENGC-2：app 侧印章读取壳的三态（解析本体在 `:core-vm` 的 `EngineStampTest`）。
 *
 * 这里只钉「字节进来之后往哪一态走」：缺失 ⇒ Missing、合法 ⇒ 缺省档、读失败 ⇒ Malformed
 * （**不许**把 IO 失败吞成「没有印章」）。判据与 PLAN §2.4 的运行时行逐条对应。
 * app 模块单测走 JUnit 4（与 `ShellFailureCopyTest` 同口径），故 `is` 判定手写而不吃 `kotlin.test.assertIs`。
 */
class EngineProfileStampTest {

    private val valid =
        """{"schema": 1, "profile": "snake_4981", "engine": "snake", "version": "4.981"}"""

    private fun opener(text: String): (String) -> InputStream? =
        { ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)) }

    @Test
    fun `缺失返回 Missing`() {
        assertEquals(EngineStampRead.Missing, EngineProfileStamp.read { null })
    }

    @Test
    fun `合法印章装配成缺省档`() {
        val read = EngineProfileStamp.read(opener(valid))
        assertTrue("期望 Present，实际 $read", read is EngineStampRead.Present)
        assertEquals(RuntimeCompatibilityProfile.LEGACY_ERATW_4981, (read as EngineStampRead.Present).profile)
    }

    @Test
    fun `非法印章保留原文`() {
        val text = """{"schema": 2, "profile": "snake_4981", "engine": "snake", "version": "4.981"}"""
        val read = EngineProfileStamp.read(opener(text))
        assertTrue("期望 Malformed，实际 $read", read is EngineStampRead.Malformed)
        assertEquals(text, (read as EngineStampRead.Malformed).raw)
    }

    @Test
    fun `读失败转 Malformed 而不是没有印章`() {
        val read = EngineProfileStamp.read { throw IOException("boom") }
        assertTrue("期望 Malformed，实际 $read", read is EngineStampRead.Malformed)
        val malformed = read as EngineStampRead.Malformed
        assertTrue(malformed.reason, malformed.reason.contains("asset read failed"))
        assertTrue(malformed.reason, malformed.reason.contains("boom"))
    }

    @Test
    fun `只按固定文件名读`() {
        var seen: String? = null
        EngineProfileStamp.read { name ->
            seen = name
            null
        }
        assertEquals(EngineStamp.ASSET_NAME, seen)
    }
}
