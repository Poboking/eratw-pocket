package com.eratw.port.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OBS2-L4 packguard 的钉子：`app/build.gradle.kts` 里 channel-guard 要成的两件事 ——
 * **缺参数要大声**（配置期警告）与**包内要留不含机密的读数**（`eratw_report_channel_stamp`）。
 *
 * 病（组长 2026-11-05 取证）：`-PeratwReportEndpoint` / `-PeratwReportToken` /
 * `-PeratwUpdateMirrors` 被读成空串回退时构建零警告零失败，于是 17 份 `-dirty` 内测直发包
 * 在端点侧一个都不在（上报链当年就没接上）。`devtools/pack_release.py` 的 resValues/arsc 回读
 * 体检只护住走脚本那条路，手敲 `:app:assembleRelease` 无人管。
 *
 * 本文件钉的四格（逐格对应交付要求）：
 * 1. 读数形状封闭（[stamp_has_closed_grammar]）：只有 `v1;endpoint=…;token=…;mirrors=…` 这一种形状，
 *    取值只可能是 host 级读数或 `off` / `invalid` / `on`；
 * 2. 读数不含机密（[stamp_carries_no_secret] + [token_never_leaves_the_build_script]）：
 *    字符集白名单、`token` 段只允许 `on|off`、长度有界，且构建脚本里打日志/传属性的行
 *    都不许把令牌变量交出去；
 * 3. 两臂自证（[stamp_matches_parameters_seen_by_build] / [stamp_matches_generated_res_values]）：
 *    用例按构建期真实参数**独立复算**期望读数（同一套规格写第二遍），再与 BuildConfig、
 *    与生成的 resValues 落点逐字对账 ⇒ 不带参数跑一次、带参数跑同一条命令，两臂都必须绿；
 * 4. 闸的形状不许回退（[guard_warns_and_never_fails] / [guard_covers_all_three_channels]）：
 *    守卫区必须有 `logger.warn`、必须覆盖三个参数、必须一个 `throw` 都没有
 *    （`--offline` 与刻意内测要能照常出包，AGENTS 第 8 节点名允许）。
 *
 * 令牌的纪律（契约 §2 L4 第 2 条）：本文件不读 `local.report.properties`，也从没拿到令牌值 ——
 * gradle 只把「有没有带」传成布尔系统属性 `eratw.build.reportTokenPresent`；断言消息一律过
 * [mask]，脏值不会原样上屏。
 */
class ReportChannelBuildStampTest {

    private companion object {
        const val OFF = "off"
        const val INVALID = "invalid"

        /** host 级读数的合法形状（与构建脚本 channelHostPattern 同规格；253 = DNS 单名长度上限）。 */
        val HOST_SHAPE: Regex = Regex("[a-z0-9][a-z0-9.-]{0,252}")

        /** 整条读数的字符集白名单：小写字母/数字与 `.-;=+`，别一个都不许多。 */
        val ALLOWED_CHARS: Set<Char> = ('a'..'z').toSet() + ('0'..'9') + setOf('.', '-', ';', '=', '+')

        /**
         * 读数长度上限的算术：`v1;`(3) + `endpoint=`(9)+≤253 + `;token=`(7)+3 + `;mirrors=`(9)
         * + (4×253 + 3 个 `+` + `+n` + ≤3 位计数) ≈ 1,300 ⇒ 定长常量，不是会随输入变长的表。
         */
        const val STAMP_MAX_LENGTH = 1300

        /** 断言消息里回显读数的长度上限（消息不当泄密通道，也别刷满屏）。 */
        const val MASK_DISPLAY_LIMIT = 120

        /** 与构建脚本一致的镜像显示上限（超出只写 `+n<总数>`）。 */
        const val MIRROR_DISPLAY_CAP = 4

        /** 单测的工作目录可能是 app 模块、idea 根或仓库根，逐个试（沿用本仓源码面用例的手法）。 */
        val MODULE_ROOTS = listOf(File("."), File("../app"), File("app"))

        const val GUARD_BEGIN = "// ==== BEGIN channel-guard"
        const val GUARD_END = "// ==== END channel-guard"

        /** 构建期三条通道的原始读数注入点（令牌只有布尔形态，值从不过来）。 */
        val ENDPOINT_RAW: String = System.getProperty("eratw.build.reportEndpoint")
            ?: throw AssertionError("单测没拿到 eratw.build.reportEndpoint：app/build.gradle.kts 的 withType<Test> 接线被摘了？")
        val MIRRORS_RAW: String = System.getProperty("eratw.build.updateMirrors")
            ?: throw AssertionError("单测没拿到 eratw.build.updateMirrors：app/build.gradle.kts 的 withType<Test> 接线被摘了？")
        val TOKEN_PRESENT: String = System.getProperty("eratw.build.reportTokenPresent")
            ?: throw AssertionError("单测没拿到 eratw.build.reportTokenPresent：app/build.gradle.kts 的 withType<Test> 接线被摘了？")
    }

    // ------------------------------------------------------------------
    // 复算侧（与构建脚本同规格、各写一遍：两边对不上就红，闸要的就是这个）
    // ------------------------------------------------------------------

    private fun expectedHost(raw: String): String {
        val url = raw.trim()
        if (url.isEmpty()) return OFF
        val authority = url.substringAfter("://", url)
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
        val host = authority.substringAfterLast('@', authority).substringBefore(':').lowercase()
        return if (host.matches(HOST_SHAPE)) host else INVALID
    }

    private fun expectedMirrorHosts(raw: String): String {
        val hosts = raw.split('|')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { expectedHost(it) }
            .distinct()
        return when {
            hosts.isEmpty() -> OFF
            hosts.size <= MIRROR_DISPLAY_CAP -> hosts.joinToString("+")
            else -> hosts.take(MIRROR_DISPLAY_CAP).joinToString("+") + "+n" + hosts.size
        }
    }

    private fun expectedStamp(): String =
        "v1;endpoint=" + expectedHost(ENDPOINT_RAW) +
            ";token=" + (if (TOKEN_PRESENT == "false") OFF else "on") +
            ";mirrors=" + expectedMirrorHosts(MIRRORS_RAW)

    /** 只回显合法字符，其余打成 `?`：读数万一被写脏，断言消息不当泄密通道。 */
    private fun mask(value: String): String = buildString {
        value.forEach { append(if (it in ALLOWED_CHARS) it else '?') }
    }.take(MASK_DISPLAY_LIMIT)

    private fun findModuleFile(relative: String): File {
        val candidates = MODULE_ROOTS.map { File(it, relative) }
        return candidates.firstOrNull { it.isFile }
            ?: throw AssertionError("找不到 $relative（试过：${candidates.joinToString { it.path }}）")
    }

    private fun buildScriptText(): String = findModuleFile("build.gradle.kts").readText()

    /** 找到以 `head` 开头的那一行代码（去缩进比较）；找不到返回 null，由用例自己报缺哪一行。 */
    private fun findLineStartingWith(text: String, head: String): String? =
        text.lines().firstOrNull { it.trimStart().startsWith(head) }

    /** AGP 生成的 resValues 落点（debug 变体：跑本用例的 testDebugUnitTest 必然生成过它）。 */
    private fun resValuesText(): String =
        findModuleFile("build/generated/res/resValues/debug/values/gradleResValues.xml")
            .readText()

    private fun pickResString(xml: String, name: String): String? =
        Regex("<string name=\"$name\"[^>]*>([^<]*)</string>").find(xml)?.groupValues?.get(1)

    private fun guardRegion(text: String): String =
        text.substringAfter(GUARD_BEGIN).substringAfter("\n").substringBefore(GUARD_END)

    // ------------------------------------------------------------------
    // 1 读数形状封闭
    // ------------------------------------------------------------------

    @Test
    fun stamp_has_closed_grammar() {
        val stamp = BuildConfig.eratwReportChannelStamp
        val parts = stamp.split(';')
        assertEquals("读数必须是 v1 + 三段键值（实际形状：${mask(stamp)}）", 4, parts.size)
        assertEquals("v1", parts[0])
        assertTrue("第二段必须是 endpoint=：${mask(stamp)}", parts[1].startsWith("endpoint="))
        assertTrue("第三段必须是 token=：${mask(stamp)}", parts[2].startsWith("token="))
        assertTrue("第四段必须是 mirrors=：${mask(stamp)}", parts[3].startsWith("mirrors="))
        val endpointValue = parts[1].removePrefix("endpoint=")
        val mirrorsValue = parts[3].removePrefix("mirrors=")
        assertTrue(
            "endpoint 段只允许 host 级读数或 off/invalid：${mask(endpointValue)}",
            endpointValue == OFF || endpointValue == INVALID || endpointValue.matches(HOST_SHAPE),
        )
        mirrorsValue.split('+').filter { it.isNotEmpty() }.forEach {
            assertTrue(
                "mirrors 段只允许 host 级读数、off/invalid 与 +n<计数>：${mask(mirrorsValue)}",
                it == OFF || it == INVALID || it.matches(HOST_SHAPE) || it.matches(Regex("n\\d{1,3}")),
            )
        }
    }

    // ------------------------------------------------------------------
    // 2 读数不含机密
    // ------------------------------------------------------------------

    @Test
    fun stamp_carries_no_secret() {
        val stamp = BuildConfig.eratwReportChannelStamp
        assertTrue("读数只允许小写字母/数字与 .-;=+：${mask(stamp)}", stamp.all { it in ALLOWED_CHARS })
        assertFalse("读数里不许出现冒号（协议与端口都不该进来）：${mask(stamp)}", stamp.contains(':'))
        assertTrue("读数长度必须在写死的上限内：实际 ${stamp.length}，上限 $STAMP_MAX_LENGTH", stamp.length in 1..STAMP_MAX_LENGTH)
        val tokenPart = stamp.split(';').getOrNull(2).orEmpty()
        assertTrue(
            "token 段只允许有/无两态，别的取值就说明令牌进了产物：${mask(tokenPart)}",
            tokenPart == "token=on" || tokenPart == "token=$OFF",
        )
    }

    /**
     * 令牌只许待在两处：声明它的那一行，和那条既有 `eratw_report_token` resValue（运行期上传要用）。
     * 打日志的行、拼警告文案的行、传系统属性的行都只能引用它的「有/无」形态
     * （`isBlank()` / `isNotBlank()`），出现第四种用法就是往产物或屏上交底。
     */
    @Test
    fun token_never_leaves_the_build_script() {
        val tokenLines = buildScriptText().lines().filter { it.contains("reportTokenRaw") }
        val sanctioned = tokenLines.filter {
            it.contains("val reportTokenRaw") || it.contains("isBlank()") || it.contains("isNotBlank()") ||
                it.contains("resValue(\"string\", \"eratw_report_token\"")
        }
        val offenders = tokenLines - sanctioned.toSet()
        assertTrue(
            "令牌变量出现在了非交底位置（只允许声明 / 有-无判定 / 那条既有 resValue）：" +
                offenders.firstOrNull()?.trim() ?: "",
            offenders.isEmpty(),
        )
        assertTrue("既有那条 eratw_report_token 的 resValue 必须还在（运行期靠它上报）", tokenLines.isNotEmpty())
    }

    // ------------------------------------------------------------------
    // 3 两臂自证：读数 == 构建期真实参数（不带参数全 off；带参数变 host/on）
    // ------------------------------------------------------------------

    @Test
    fun stamp_matches_parameters_seen_by_build() {
        assertEquals(expectedStamp(), mask(BuildConfig.eratwReportChannelStamp))
    }

    @Test
    fun stamp_matches_generated_res_values() {
        val xml = resValuesText()
        val fromRes = pickResString(xml, "eratw_report_channel_stamp")
            ?: throw AssertionError(
                "resValues 里没有 eratw_report_channel_stamp：包内判读点（aapt2 dump resources 拿得到的那条）没了，" +
                    "「现成 APK 事后判当年带没带参数」重新变成做不到。",
            )
        assertEquals(expectedStamp(), mask(fromRes))
        assertEquals(
            "resValues（进 resources.arsc，判现成的包）与 BuildConfig（进 dex，取证面）两份落点必须逐字相同",
            mask(fromRes),
            mask(BuildConfig.eratwReportChannelStamp),
        )
        // 三条通道参数的既有 resValue 必须都还在：读数与它们对不上账时，判读会指错地方。
        listOf("eratw_report_endpoint", "eratw_report_token", "eratw_update_mirrors").forEach {
            assertTrue("resValues 少了既有条目 $it", xml.contains("<string name=\"$it\""))
        }
    }

    // ------------------------------------------------------------------
    // 4 闸的形状不许回退：大声、覆盖三参数、绝不硬失败
    // ------------------------------------------------------------------

    @Test
    fun guard_warns_and_never_fails() {
        val text = buildScriptText()
        assertTrue("app/build.gradle.kts 里找不到 channel-guard 守卫区（标记被改名或整块被删）", text.contains(GUARD_BEGIN))
        val region = guardRegion(text)
        // 以下断言全部点**代码里的形态**（带括号/带 python 前缀），注释里的同名说法不算：
        // 守卫区顶上有整段散文在讲 logger.warn 与 pack_release.py，只匹配裸名会连「代码被删、注释还留着」都判不出来。
        assertTrue("守卫区必须真的调用 logger.warn( （比 lifecycle 响，--quiet 之外都在屏上）", region.contains("logger.warn("))
        assertTrue("守卫区必须按「这次会不会出 release 包」收口（debug 构建天天喊等于没有警告）", region.contains("if (releaseBuildRequested)"))
        assertTrue("守卫区必须讲清怎么打开：-PeratwReportEndpoint", region.contains("-PeratwReportEndpoint"))
        assertTrue("守卫区必须讲清怎么打开：-PeratwReportToken", region.contains("-PeratwReportToken"))
        assertTrue("守卫区必须讲清怎么打开：-PeratwUpdateMirrors", region.contains("-PeratwUpdateMirrors"))
        assertTrue("守卫区必须点明唯一出包入口 python devtools/pack_release.py", region.contains("python devtools/pack_release.py"))
        assertTrue("守卫区必须把读数写进警告文案（屏上要能直接看到当年带没带）", region.contains("append(reportChannelStamp)"))
        assertTrue("守卫区必须声明这条不影响合法内测（不许让 --offline 出不了包）", region.contains("不会让构建失败"))
        assertFalse("守卫区出现了 throw —— 缺参数必须是警告：AGENTS 第 8 节点名允许 --offline 与刻意内测照常出包", region.contains("throw"))
    }

    @Test
    fun guard_covers_all_three_channels() {
        val text = buildScriptText()
        val region = guardRegion(text)
        assertTrue("找不到守卫区", region.isNotEmpty())
        // 三个参数各自都要被「空 = 关」判一次，少结一个就是重开一个静默洞。
        listOf("reportEndpointRaw", "reportTokenRaw", "updateMirrorsRaw").forEach {
            assertTrue("守卫区没判 $it 是否为空（那条通道又会静默关闭）", region.contains("$it.isBlank()"))
        }
        // 两条落点：resValue 是「现成 APK 判得出来」的那一条，BuildConfig 是取证面 + 纯 JVM 单测那条。
        val resValueLine = findLineStartingWith(text, "resValue(\"string\", \"eratw_report_channel_stamp\"")
        assertTrue(
            "读数没落 resValue（包内判读点，aapt2 dump resources 拿得到的那条）：实际=${resValueLine?.trim() ?: "没有这一行"}",
            resValueLine != null && resValueLine.contains("reportChannelStamp"),
        )
        val buildConfigLine = findLineStartingWith(text, "buildConfigField(\"String\", \"eratwReportChannelStamp\"")
        assertTrue(
            "读数没落 BuildConfig（取证面 + 纯 JVM 单测读得到的那条）：实际=${buildConfigLine?.trim() ?: "没有这一行"}",
            buildConfigLine != null && buildConfigLine.contains("reportChannelStamp"),
        )
    }
}
