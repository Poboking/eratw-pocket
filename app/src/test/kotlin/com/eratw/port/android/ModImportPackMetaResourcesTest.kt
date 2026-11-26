package com.eratw.port.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 包级元数据（`eratw-pack.json`）的旁挂资源目录名字段：解析与形态校验（ADR-014 D2/D4，切片 S2）。
 *
 * ## 覆盖的语义
 *
 * 1. 老包（无 `resources` 键）⇒ `PackMeta.resources == null`，其余字段读数与今天逐项相等；
 * 2. 合法单段目录名（含中文）⇒ 原样读出，且不影响 version / minApi / packageId 的采信；
 * 3. 形态非法（空串 / 多段 / `.` / `..` / 含 `..` / 反斜杠 / 冒号 / 控制字符 / 超 64 字符 /
 *    非字符串）⇒ 整份元数据按坏档忽略（既有 ignored 路径：version 回落、导入照常、不崩）；
 * 4. `resources` 与 `packageId` 互不影响：同时存在时各自读数正确；一方非法不篡改另一方的
 *    取证读数（自述包名仍留作取证，语义照 `PackMetaIgnore.MIN_API` 先例）。
 *
 * 只测纯函数 [parsePackMeta]；zip 端到端链路由 `ModImportPackMetaEndToEndTest` 覆盖。
 */
class ModImportPackMetaResourcesTest {

    // ------------------------------------------------------------------
    // 1. 老包：无该字段 ⇒ null，其余读数逐项不变
    // ------------------------------------------------------------------

    @Test
    fun `无resources字段——resources为null且其余字段读数与今天逐项相等`() {
        val meta = metaOf(metaJson(version = "2.0", minApiJson = "15"))

        assertNull("缺字段 = 不带旁挂资源（老包常态）", meta.resources)
        assertEquals("自述包名照旧", MODULE_PACKAGE_ID, meta.packageId)
        assertEquals("作者声明的版本原文照旧", "2.0", meta.declaredVersion)
        assertEquals("采信版本照旧", "2.0", meta.version)
        assertEquals("引擎下限照旧", 15, meta.minApi)
        assertNull("采信", meta.ignored)
        assertTrue(meta.trusted)
    }

    @Test
    fun `没有元数据条目——resources同为null且仍是既有MISSING回落`() {
        val missing = parsePackMeta(null)

        assertNull(missing.resources)
        assertEquals(PackMetaIgnore.MISSING, missing.ignored)
        assertEquals("1", missing.version)
        assertFalse(missing.trusted)
    }

    // ------------------------------------------------------------------
    // 2. 合法名：原样读出，且不干扰其余字段
    // ------------------------------------------------------------------

    @Test
    fun `合法resources——my_pack与中文名原样读出且其余字段照常采信`() {
        for (name in listOf("my_pack", "我的图")) {
            val meta = metaOf(
                metaJson(version = "2.0", minApiJson = "15", resourcesJson = name.asJsonString()),
            )

            assertEquals(name, meta.resources)
            assertEquals("版本照常采信", "2.0", meta.version)
            assertEquals("minApi 照常采信", 15, meta.minApi)
            assertNull("合法字段不拖累整份元数据", meta.ignored)
            assertTrue(meta.trusted)
        }
    }

    // ------------------------------------------------------------------
    // 3. 非法名：整份元数据按坏档忽略（既有 ignored 路径）
    // ------------------------------------------------------------------

    @Test
    fun `空串resources——整份元数据按坏档忽略`() {
        assertIgnoredAsResources(metaJson(resourcesJson = "".asJsonString()))
    }

    @Test
    fun `多段resources——斜杠与反斜杠都按坏档忽略`() {
        assertIgnoredAsResources(metaJson(resourcesJson = "a/b".asJsonString()))
        assertIgnoredAsResources(metaJson(resourcesJson = "a\\b".asJsonString()))
    }

    @Test
    fun `点段resources——点与点点与含双点都按坏档忽略`() {
        assertIgnoredAsResources(metaJson(resourcesJson = ".".asJsonString()))
        assertIgnoredAsResources(metaJson(resourcesJson = "..".asJsonString()))
        assertIgnoredAsResources(metaJson(resourcesJson = "a..b".asJsonString()))
    }

    @Test
    fun `字符集resources——冒号与DEL与控制字符都按坏档忽略`() {
        assertIgnoredAsResources(metaJson(resourcesJson = "a:b".asJsonString()))
        // JSON 转义 \u007F / \u0001 由解析器还原成真控制字符 ⇒ 单段目录名判据拦下。
        assertIgnoredAsResources(metaJson(resourcesJson = "\"a\\u007Fb\""))
        assertIgnoredAsResources(metaJson(resourcesJson = "\"a\\u0001b\""))
    }

    @Test
    fun `超长resources——64字符合法65字符按坏档忽略`() {
        val boundary = "a".repeat(64)
        assertEquals(
            "长度上限边界内的名字照常读出",
            boundary,
            metaOf(metaJson(resourcesJson = boundary.asJsonString())).resources,
        )

        assertIgnoredAsResources(metaJson(resourcesJson = "a".repeat(65).asJsonString()))
    }

    @Test
    fun `非字符串resources——数字与对象与数组都按坏档忽略`() {
        assertIgnoredAsResources(metaJson(resourcesJson = "16"))
        assertIgnoredAsResources(metaJson(resourcesJson = "{\"dir\":\"x\"}"))
        assertIgnoredAsResources(metaJson(resourcesJson = "[\"x\"]"))
    }

    // ------------------------------------------------------------------
    // 4. 与 packageId 互不影响（取证读数不被对方篡改）
    // ------------------------------------------------------------------

    @Test
    fun `resources与packageId同时存在——两个字段各自读数正确`() {
        val meta = metaOf(metaJson(version = "3.1", resourcesJson = "my_pack".asJsonString()))

        assertEquals("my_pack", meta.resources)
        assertEquals(MODULE_PACKAGE_ID, meta.packageId)
        assertEquals("3.1", meta.version)
        assertTrue(meta.trusted)
    }

    @Test
    fun `resources非法不篡改packageId读数——仍按坏档忽略并保留取证`() {
        val meta = metaOf(metaJson(version = "3.1", resourcesJson = "a/b".asJsonString()))

        assertEquals(PackMetaIgnore.RESOURCES, meta.ignored)
        assertNull("非法目录名不采信", meta.resources)
        assertEquals("版本回落", "1", meta.version)
        assertEquals("自述包名仍留作取证", MODULE_PACKAGE_ID, meta.packageId)
        assertEquals("声明的版本原文仍留作取证", "3.1", meta.declaredVersion)
    }

    @Test
    fun `packageId非法不影响resources采信——目录名照读且元数据整体采信`() {
        val meta = metaOf(
            metaJson(
                version = "3.1",
                packageIdJson = "not a package id".asJsonString(),
                resourcesJson = "my_pack".asJsonString(),
            ),
        )

        assertNull("非法自述包名不采信", meta.packageId)
        assertEquals("resources 与 packageId 判据互不影响", "my_pack", meta.resources)
        assertNull("packageId 非法不拖累整份元数据（既有口径）", meta.ignored)
        assertTrue(meta.trusted)
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    /** 断言整份元数据因 `resources` 形态非法而按坏档忽略。 */
    private fun assertIgnoredAsResources(payload: String) {
        val meta = metaOf(payload)
        assertEquals("应判 RESOURCES：$payload", PackMetaIgnore.RESOURCES, meta.ignored)
        assertNull("坏档不采信目录名", meta.resources)
        assertEquals("版本一并回落", "1", meta.version)
        assertFalse(meta.trusted)
    }

    private fun metaOf(payload: String): PackMeta = parsePackMeta(payload.toByteArray(Charsets.UTF_8))

    /** 与 pack-mod 同款固定键序 JSON；可选项以已经拼好的 JSON 字面量注入（非字符串类型用例用）。 */
    private fun metaJson(
        version: String = "2.0",
        packageIdJson: String = MODULE_PACKAGE_ID.asJsonString(),
        minApiJson: String? = null,
        resourcesJson: String? = null,
    ): String = buildString {
        append("{\"schema\":1,\"packageId\":")
        append(packageIdJson)
        append(",\"version\":")
        append(version.asJsonString())
        if (minApiJson != null) append(",\"minApi\":").append(minApiJson)
        if (resourcesJson != null) append(",\"resources\":").append(resourcesJson)
        append('}')
    }

    /** 普通字符串 → JSON 字符串字面量（控制字符用调用方手写 `\uXXXX` 转义，见字符集用例）。 */
    private fun String.asJsonString(): String = "\"" + this + "\""

    private companion object {
        private const val MODULE_PACKAGE_ID = "eratw.packmeta"
    }
}
