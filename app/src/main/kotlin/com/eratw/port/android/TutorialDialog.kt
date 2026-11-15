package com.eratw.port.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.eratw.port.android.ui.EratwDialogWindowWire
import com.eratw.port.android.ui.EratwPixelFont

/**
 * 首启教程与关于页「重新查阅」共用的弹窗**外壳**。
 *
 * 这里管三件事：开一只全窗口的 `Dialog`、把弹窗窗口装配成 edge-to-edge（窗口几何统一
 * 收口在 [EratwDialogWindowWire]，教程与 `EratwDialogCard` 共用同一份装配）、避让系统栏
 * 安全区。版式、配色、文案标记解析全在 [TutorialBook] —— 那份不引用任何 Android API，
 * `tools` 侧的离线预览器能直接渲染同一份代码出 PNG，改版式不必等真机。
 *
 * ## 为什么外壳必须管窗口几何（2026-11-12 玩家反馈「教程在某些手机上，右侧会截断」）
 * Compose `Dialog` 是独立窗口，它的刘海语义此前从未声明 —— `layoutInDisplayCutoutMode`
 * 交给平台缺省（MODE_DEFAULT），行为随 ROM 分叉。部分机型（沉浸式全屏 + 挖孔屏，横屏
 * 挖孔在侧边）会把窗口 letterbox 到挖孔安全区，而 Compose ui 1.7.x 的 Dialog 用
 * `Configuration.screenWidthDp` 量内容（上游 b/275732345，1.8.0-alpha08 才修；本仓
 * composeBom 2024.12.01 = ui 1.7.x），与被 letterbox 的窗口可视宽不一致 ⇒
 * `fillMaxSize()` 的面板按整屏宽测量、溢出可视区被裁 —— 横屏即「右侧截断」，竖屏即
 * 「底部截断」（上游 b/290502769 同症，Samsung One UI 上尤甚）。
 *
 * 修法是把窗口语义**显式声明**：延伸进挖孔区 + 窗口边界钉满 MATCH_PARENT，让「Compose
 * 度量宽 = 窗口可视宽」成为我们声明的性质而不是 ROM 的运气；挖孔让出的部分由内容侧
 * `safeDrawingPadding()` 避让 —— 面板在任何机型上都完整可见，窄一点优于截断。这是窗口
 * 装配层的根修，不给 [TutorialBook] 的排版本体塞任何宽度补丁（那份要保持零 Android
 * 依赖，预览器还要用它）。
 *
 * 关闭路径（点 × / 点遮罩 / 末页「开始游戏」）统一走 [onDismiss]，由调用侧决定是否落
 * 「已读」标记（首启落、关于页重阅不落）。
 *
 * @param immersive 弹窗窗口要不要跟着藏系统栏。游戏侧（MainActivity，沉浸式全屏）用
 *   缺省 true —— 窗口延伸后系统栏若浮起会盖在面板上；设置页（SettingsActivity，系统栏
 *   可见）必须传 false —— 那边的系统栏是页面语义的一部分，只做几何装配。
 */
@Composable
fun TutorialDialog(
    copy: TutorialCopy,
    onDismiss: () -> Unit,
    immersive: Boolean = true,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            // insets 全量下发内容：`safeDrawingPadding` 才拿得到挖孔/系统栏的真实 inset，
            // 窗口也才被允许延伸进挖孔区。R+ 走窗口级 API；P- 由 WindowCompat 落成
            // decorView 的 LAYOUT_* flags，同样生效。
            decorFitsSystemWindows = false,
        ),
    ) {
        EratwDialogWindowWire(immersive = immersive)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
        ) {
            TutorialBook(
                copy = copy,
                fontFamily = EratwPixelFont.family,
                onDismiss = onDismiss,
            )
        }
    }
}
