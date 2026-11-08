package com.eratw.port.android.dbg

import android.app.Activity
import android.os.Bundle

/**
 * DBG 装置的进程钉活动（全透明、零内容；仅 debug 包的 androidTest 用）。
 *
 * ## 为什么存在：Cached App Freezer 冻死 instrumentation 装载（2026-10-25 vivo 实锤）
 *
 * `am instrument` 起的目标进程没有前台组件，Cached App Freezer 把它归入
 * `/dev/freezer/frozen`（`freezer.state=FROZEN`、全组 364 任务）——装载 erbc 的 mmap
 * 缺页在冻结组里永久 D 态、0 CPU。实测两条弱手段都无效：AOSP 的
 * `cached_apps_freezer_enabled=false` device_config（vivo 不理会）、运行中 Service
 * （dumpsys 可见 ServiceRecord、进程照样 FROZEN——vivo 连 SERVICE 级都冻）。
 * **可见 Activity = PROCESS_STATE_TOP = 任何 ROM 的 freezer 都不碰**，这是唯一强保证。
 *
 * 全透明（Theme.Translucent.NoTitleBar）+ 零内容：不触发生意逻辑、不加载体积、
 * 玩家侧无人知晓。用法：测试开头 `instrumentation.startActivitySync(intent)` 拿实例，
 * finally 里 `finish()`——TOP 状态贯穿整个测试，跑完即退。
 */
class DevKeepAliveActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        // 零内容：透明主题下 nothing to draw，只为把进程顶到 TOP
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
    }

    companion object {
        /** 用 **startActivity（fire-and-forget）** 起：startActivitySync 会等主线程 idle，
         *  而等待窗口里进程还没到 TOP、可能被 freezer 冻住 → 45s 超时（真机实测）。 */
        fun start(context: android.content.Context) {
            context.startActivity(
                android.content.Intent(context, DevKeepAliveActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }

        fun stop() {
            instance?.finish()
            instance = null
        }

        @Volatile
        private var instance: DevKeepAliveActivity? = null
    }
}
