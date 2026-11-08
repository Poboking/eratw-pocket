package com.eratw.port.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [BundleInventoryStamp] 的钉子：缓存行的格式与**全等才命中**的失效语义。
 *
 * 缓存只影响首启的 `expected` 清点，宁可失配（回退整树扫描），也不能让一行脏数据
 * 命成别的版本的读数；所以这里把「什么时候必须返回 null」逐条钉死。
 */
class BundleInventoryStampTest {

    private companion object {
        /** 真机随包量级的一个点：3,971 条 / 679.9 MiB 附近。 */
        const val REAL_ENTRIES = 3_971
        const val REAL_BYTES = 713_052_089L

        /** 正常 stamp 的形状（构建标识 + 安装时间）。 */
        const val STAMP = "a1b2c3d:1730000000000"
    }

    /** 编码形态逐字固定：换格式必须同时换版本号。 */
    @Test
    fun encode_has_exact_v1_shape() {
        assertEquals("v1\ta1b2c3d:1730000000000\t3971\t713052089",
            BundleInventoryStamp.encode(STAMP, REAL_ENTRIES, REAL_BYTES))
    }

    /** 正常往返：同一 stamp 能解回同一对读数。 */
    @Test
    fun round_trip_returns_same_numbers() {
        val text = BundleInventoryStamp.encode(STAMP, REAL_ENTRIES, REAL_BYTES)
        val decoded = requireNotNull(BundleInventoryStamp.decode(text, STAMP)) { "同一 stamp 应能解回" }
        assertEquals("entries", REAL_ENTRIES, decoded.first)
        assertEquals("bytes", REAL_BYTES, decoded.second)
    }

    /** 0 是合法读数（空语料 / 未随包数据的安装），不能被当成「没缓存」。 */
    @Test
    fun zero_values_are_valid() {
        val text = BundleInventoryStamp.encode(STAMP, 0, 0L)
        assertEquals(0 to 0L, BundleInventoryStamp.decode(text, STAMP))
    }

    /** 空 stamp 也是合法的失效键（构建标识与安装时间都拿不到时的退化形态）。 */
    @Test
    fun empty_stamp_round_trips() {
        val text = BundleInventoryStamp.encode("", 1, 2L)
        assertEquals("v1\t\t1\t2", text)
        assertEquals(1 to 2L, BundleInventoryStamp.decode(text, ""))
    }

    /** stamp 不匹配 = 换包/重装：必须失配，哪怕其它字段完全一样。 */
    @Test
    fun stamp_mismatch_returns_null() {
        val text = BundleInventoryStamp.encode(STAMP, REAL_ENTRIES, REAL_BYTES)
        assertNull("别的安装时间", BundleInventoryStamp.decode(text, "a1b2c3d:1730000000001"))
        assertNull("别的构建标识", BundleInventoryStamp.decode(text, "fffffff:1730000000000"))
        assertNull("空 stamp 不得命中非空 stamp 的行", BundleInventoryStamp.decode(text, ""))
        assertNull("大小写不同也不命中", BundleInventoryStamp.decode(text, STAMP.uppercase()))
    }

    /** 版本不匹配（将来升级格式/旧缓存）：失配回退整树扫描。 */
    @Test
    fun version_mismatch_returns_null() {
        assertNull("v2 行不能被 v1 读回", BundleInventoryStamp.decode("v2\t$STAMP\t1\t2", STAMP))
        assertNull("缺版本段", BundleInventoryStamp.decode("\t$STAMP\t1\t2", STAMP))
        assertNull("任意前缀", BundleInventoryStamp.decode("x1\t$STAMP\t1\t2", STAMP))
    }

    /** 字段数不对（少字段 = 截断，多字段 = 混入分隔符）一律 null。 */
    @Test
    fun field_count_mismatch_returns_null() {
        assertNull("少一个字段", BundleInventoryStamp.decode("v1\t$STAMP\t1", STAMP))
        assertNull("只有版本", BundleInventoryStamp.decode("v1", STAMP))
        assertNull("多一个字段", BundleInventoryStamp.decode("v1\t$STAMP\t1\t2\t3", STAMP))
        assertNull("空串", BundleInventoryStamp.decode("", STAMP))
    }

    /** 负数读数没有意义：按脏缓存处理。 */
    @Test
    fun negative_values_return_null() {
        assertNull("负数条目", BundleInventoryStamp.decode("v1\t$STAMP\t-1\t2", STAMP))
        assertNull("负数字节", BundleInventoryStamp.decode("v1\t$STAMP\t1\t-2", STAMP))
        assertNull("两者皆负", BundleInventoryStamp.decode("v1\t$STAMP\t-1\t-2", STAMP))
    }

    /** 非数字与溢出：解析必须失败，不能截断成另一个数。 */
    @Test
    fun non_numeric_values_return_null() {
        assertNull("条目不是数字", BundleInventoryStamp.decode("v1\t$STAMP\tabc\t2", STAMP))
        assertNull("字节不是数字", BundleInventoryStamp.decode("v1\t$STAMP\t1\tx", STAMP))
        assertNull("小数", BundleInventoryStamp.decode("v1\t$STAMP\t1.5\t2", STAMP))
        assertNull("条目 Int 溢出", BundleInventoryStamp.decode("v1\t$STAMP\t2147483648\t2", STAMP))
        assertNull("字节 Long 溢出", BundleInventoryStamp.decode("v1\t$STAMP\t1\t9223372036854775808", STAMP))
        assertNull("空数字段", BundleInventoryStamp.decode("v1\t$STAMP\t\t2", STAMP))
    }

    /** 前缀之后多出空白/换行（手改、被别的工具补了行尾）：严格失配，不猜。 */
    @Test
    fun trailing_noise_returns_null() {
        val text = BundleInventoryStamp.encode(STAMP, 1, 2L)
        assertNull("行尾换行", BundleInventoryStamp.decode("$text\n", STAMP))
        assertNull("行首空白", BundleInventoryStamp.decode(" $text", STAMP))
    }

    /**
     * stamp 含制表符/换行：encode 先替换成下划线，字段数恒为 4；
     * decode 按原样比较，所以那样的 stamp 解不回来 —— 视为未命中（回退整树扫描）。
     */
    @Test
    fun stamp_with_separators_is_sanitized_and_never_round_trips_raw() {
        val text = BundleInventoryStamp.encode("a\tb\nc", 7, 8L)
        assertEquals("v1\ta_b_c\t7\t8", text)
        assertNull("原样 stamp 必须失配", BundleInventoryStamp.decode(text, "a\tb\nc"))
        assertEquals("替换后的 stamp 能命中", 7 to 8L, BundleInventoryStamp.decode(text, "a_b_c"))
    }

    /** 行内多出一个制表符（旧实现/手改的形态）不能让后段被错读成 stamp。 */
    @Test
    fun raw_tab_in_file_is_rejected() {
        assertNull(BundleInventoryStamp.decode("v1\ta\tb\t1\t2", "a"))
        assertNull(BundleInventoryStamp.decode("v1\ta\tb\t1\t2", "a_b"))
    }
}
