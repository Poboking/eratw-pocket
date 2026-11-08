package com.eratw.port.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ExitForensics.isMemoryLimiterKill] 与 `REASON_OTHER` 归因分叉（2026-11-08 OOM-A 波，
 * CONTRACT-OOM-A §4）。
 *
 * 钉三件事：
 * ① 令牌匹配按官方原文 `MemoryLimiter:AnonSwap`（小写包含，Android 17 行为变更文档给的
 *    唯一识别口 —— MemoryLimiter 杀进程**无栈轨迹**，这一行就是全部取证）；
 * ② `verdict` 的 `REASON_OTHER` 分支：MemoryLimiter 优先于 OEM 管控判词，且 OEM / 空
 *    描述的旧文案**逐字不变**；
 * ③ [ExitForensics.isBenignExit]：MemoryLimiter 行**不进**非缺陷名单（它收进程 = 进程
 *    总量真的超了，是引擎内存治理信号，必须照报）；OEM 判词行为与现状一致。
 *
 * 纯 JVM（同 `ErrorReportExitForensicsTest` 的做法）；断言签名随 app 模块用 JUnit4
 * （消息在前），不是 core-vm 那套 kotlin.test。
 */
class ExitForensicsMemoryLimiterTest {

    private fun row(
        reasonCode: Int = ExitForensics.REASON_OTHER,
        description: String?,
    ) = ProcessExitRow(
        timestampMs = 1_700_000_000_000L,
        pid = 4321,
        reasonCode = reasonCode,
        status = 0,
        importance = 100,
        pssKb = 900_000L,
        rssKb = 1_200_000L,
        description = description,
    )

    // ---- ① 令牌匹配 ----

    @Test
    fun `官方原文与带前后缀的描述命中`() {
        assertTrue("官方原样", ExitForensics.isMemoryLimiterKill("MemoryLimiter:AnonSwap"))
        assertTrue(
            "系统可能带附加字段",
            ExitForensics.isMemoryLimiterKill("MemoryLimiter:AnonSwap usage=2048MB limit=1024MB"),
        )
        assertTrue("系统可能加括号", ExitForensics.isMemoryLimiterKill("[MemoryLimiter:AnonSwap]"))
        assertTrue("大小写不敏感（匹配前统一小写）", ExitForensics.isMemoryLimiterKill("memorylimiter:anonswap"))
    }

    @Test
    fun `空描述与其它管控串不命中`() {
        assertFalse("读不到 = 不命中", ExitForensics.isMemoryLimiterKill(null))
        assertFalse("空串 = 不命中", ExitForensics.isMemoryLimiterKill(""))
        assertFalse("clamp 后的空形态 = 不命中", ExitForensics.isMemoryLimiterKill("（无）"))
        assertFalse(
            "OEM 管控串不是 MemoryLimiter",
            ExitForensics.isMemoryLimiterKill("stop com.eratw.port.android due to o-stop(40)"),
        )
        assertFalse(
            "令牌逐字匹配，不含冒号变形 —— 防止将来出现同形不同源的串被误归因",
            ExitForensics.isMemoryLimiterKill("memory limiter anonswap"),
        )
    }

    // ---- ② verdict 分叉 ----

    @Test
    fun `REASON_OTHER 加 MemoryLimiter 描述给出独立判词`() {
        val text = ExitForensics.verdict(row(description = "MemoryLimiter:AnonSwap"))
        assertTrue("判词要点名 MemoryLimiter：$text", text.contains("内存限制器"))
        assertTrue("判词要点明「总量口径」与 Java 堆的区别：$text", text.contains("进程总量"))
    }

    @Test
    fun `REASON_OTHER 的 OEM 判词与空描述文案逐字不变`() {
        val oem = ExitForensics.verdict(row(description = "stop com.eratw.port.android due to o-stop(40)"))
        assertTrue("OEM 分支文案不动：$oem", oem.contains("非缺陷类退出"))
        val blank = ExitForensics.verdict(row(description = null))
        assertTrue("空描述分支文案不动：$blank", blank.contains("未给细分"))
    }

    // ---- ③ isBenignExit 分叉 ----

    @Test
    fun `MemoryLimiter 行不进非缺陷名单`() {
        val r = row(description = "MemoryLimiter:AnonSwap")
        assertFalse("必须照报 —— 这是引擎内存治理的信号", ExitForensics.isBenignExit(r))
    }

    @Test
    fun `OEM 判词与用户主动停止的 benign 行为保持现状`() {
        assertTrue(
            "OEM 管控行为不变",
            ExitForensics.isBenignExit(row(description = "stop com.eratw.port.android due to o-stop(40)")),
        )
        assertTrue(
            "用户主动停止行为不变",
            ExitForensics.isBenignExit(row(reasonCode = ExitForensics.REASON_USER_REQUESTED, description = null)),
        )
        assertFalse("REASON_OTHER 空描述维持照报", ExitForensics.isBenignExit(row(description = null)))
    }
}
