package com.eratw.port.buildlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 引擎档印章轻校验纯函数（`parseEngineStamp`）的定向单测：只断言「四字段可解析」
 * 这一条 staging 侧契约判据（CONTRACT-ENGCOMPAT §8.0.4 / §8.3）。
 * staging 不内置档位表 —— profile/engine/version 合法值不在本文件断言。
 */
class EngineStampParseTest {

    @Test
    fun 合法印章_四字段齐_解析成功() {
        val fields = parseEngineStamp(
            "{ \"schema\": 1, \"profile\": \"v24_1824\", \"engine\": \"v24\", \"version\": \"1824+v24\" }",
        )
        assertEquals(EngineStampFields(1L, "v24_1824", "v24", "1824+v24"), fields)
    }

    @Test
    fun 字段序任意_宽松空白_解析成功() {
        val fields = parseEngineStamp(
            "{\"version\": \"4.981\",  \"schema\":1,\"engine\" : \"snake\" , \"profile\":\"snake_4981\"}",
        )
        assertEquals(EngineStampFields(1L, "snake_4981", "snake", "4.981"), fields)
    }

    @Test
    fun 缺version字段_判null() {
        val text = "{ \"schema\": 1, \"profile\": \"v24_1824\", \"engine\": \"v24\" }"
        assertNull("缺 version 必须判 null（任务失败带原样内容的前提）", parseEngineStamp(text))
    }

    @Test
    fun 缺profile字段_判null() {
        val text = "{ \"schema\": 1, \"engine\": \"v24\", \"version\": \"1824+v24\" }"
        assertNull(parseEngineStamp(text))
    }

    @Test
    fun 缺schema字段_判null() {
        val text = "{ \"profile\": \"v24_1824\", \"engine\": \"v24\", \"version\": \"1824+v24\" }"
        assertNull(parseEngineStamp(text))
    }

    @Test
    fun schema非数字_判null() {
        val text = "{ \"schema\": \"1\", \"profile\": \"v24_1824\", \"engine\": \"v24\", \"version\": \"1824+v24\" }"
        assertNull(parseEngineStamp(text))
    }

    @Test
    fun 纯垃圾文本_判null() {
        assertNull(parseEngineStamp("not a json stamp at all"))
    }
}
