package com.eratw.port.platform.diag

/**
 * 宿主诊断开关的纯逻辑（不引用 Android API，可直接在纯 JVM 单测里钉住）。
 *
 * 语句池直读对账（`GameHost.auditStatementPoolAt`）会额外调两次
 * `ProgramLoader.loadMapped`：每次都是一遍完整解码，并各留一份 `Program`（core 目前
 * 没有 `Program` 级 close 入口）与一条只读 `FileChannel`（惰性语句池把段 3 的通道
 * 接了过去）。release 包启动正处在「上一段垃圾 + 本段常驻」最紧的堆窗口，所以这条
 * 诊断只允许 debuggable 包执行 —— 与装载埋点同一口径（`ApplicationInfo.FLAG_DEBUGGABLE`，
 * 见 `GameHost.isDebuggable`）。
 *
 * 单独抽成纯函数是为了可测：`GameHost` 本身要 Android `Context` 才能构造，没法在
 * 纯 JVM 单测里钉「release 不跑审计」这条判据。
 */
object HostDiagnostics {

    /**
     * 语句池审计是否执行。
     *
     * @param debuggable `applicationInfo.flags` 是否带 `FLAG_DEBUGGABLE`
     * @return debug 包 true（照常对账）；release 包恒 false（不额外解码、不落盘）
     */
    fun statementPoolAuditEnabled(debuggable: Boolean): Boolean = debuggable
}
