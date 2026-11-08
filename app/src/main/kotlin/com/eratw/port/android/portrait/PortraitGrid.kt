package com.eratw.port.android.portrait

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.eratw.port.core.host.ConsoleOption

/**
 * 竖屏三区壳的**纯函数层**：断点分级、形态判据、立绘宽度常量。
 *
 * 全部是纯函数（无 Android API、无状态、无正则），供 PortraitShell / PortraitHud /
 * CommandTray / SpriteStage（P2 的 Composable）在装配与重组期直接调用；
 * 单测在 `PortraitGridTest` 把断点表、判据与常量表逐值钉死。
 *
 * 规格出处（唯一权威，冲突以此为准）：
 *   * 断点表 —— `docs/archive/竖屏布局设计稿.md` §5.1（§291-295 行的表）；
 *   * 形态判据 —— 同稿 §3.5（§129-141 行的判据代码块，原样落地，3xx 判定优先于 size 判定）；
 *   * 立绘宽度 —— 同稿 §3.6.3（§200-207 行的常量表，when(count)，禁比例计算）。
 */

/** 断点档（§5.1）。`s` = 窄手机，`m` = 常规手机，`l` = 大屏手机/小平板，`xl` = 平板/展开态。 */
enum class SizeClass { S, M, L, XL }

/** 指令盘形态（§3.5）。只决定壳层摆法，不决定数据源，也绝不提交任何指令（§0-11/§0-12）。 */
enum class TrayForm { MENU, EXPLORE, INTERACT, MULTI }

/**
 * 屏宽 → 断点档（§5.1）。
 *
 * 输入是竖屏正文区**屏宽 dp**（`BoxWithConstraints.maxWidth` 的 dp 值；与设置页响应式
 * `settingsLayoutFor` 同一套口径）。边界按表逐格钉死：
 * s < 380 ≤ m < 520 ≤ l < 640 ≤ xl。
 *
 * 注意这里的「屏宽」是壳层分区约束（宿主容器），不是 window 视口 —— §0-14 禁视口单位
 * 指的是不要拿 `LocalConfiguration.screenHeightDp` 之类当布局基准，断点输入本身来自
 * `BoxWithConstraints`，与宿主容器一致。
 */
fun sizeClass(widthDp: Float): SizeClass = when {
    widthDp < 380f -> SizeClass.S
    widthDp < 520f -> SizeClass.M
    widthDp < 640f -> SizeClass.L
    else -> SizeClass.XL
}

/** HUD 数值条每行条数（§5.1 第 3 列）：s/m 档 3 条，l/xl 档 6 条。 */
fun hudCols(size: SizeClass): Int = if (size == SizeClass.S || size == SizeClass.M) 3 else 6

/** 指令盘按钮列数（§5.1 第 4 列）：s 档 2 列，m/l 档 3 列，xl 档 4 列。 */
fun cmdCols(size: SizeClass): Int = when (size) {
    SizeClass.S -> 2
    SizeClass.M, SizeClass.L -> 3
    SizeClass.XL -> 4
}

/** 状态详情抽屉里 Palam 网格的列数（§5.1 第 5 列）：s 档 2 列，m 档 3 列，l/xl 档 4 列。 */
fun palamCols(size: SizeClass): Int = when (size) {
    SizeClass.S -> 2
    SizeClass.M -> 3
    SizeClass.L, SizeClass.XL -> 4
}

/**
 * 从候选 [ConsoleOption.label] 里剥出屏上印的 `[数字]` 编号（§3.5 括注：
 * 「ConsoleOption 没有 number 字段时在壳层剥一次」）。
 *
 * 规则：
 *   * 取**最后一个** `[纯数字]` 形态（§4.3 的口径是「候选 label 不含 [数字] 尾缀」，
 *     语料的指令按钮编号印在末尾，如 `移动[400]`；前缀形态 `[100] - 睁开眼睛` 只有一处
 *     编号，同样命中）；
 *   * 只认半角数字 —— 全角数字不是编号（附录 A 负样例同源口径）；
 *   * 剥不出（无编号 / 括号里不是纯数字 / 数字溢出 Int）→ null = 系统类按钮。
 *
 * **只读屏面字，不参与提交**：提交永远走 [ConsoleOption.submitValue]
 * （按钮对象自己的 value，原版允许标签 `[27]`、提交 `227`，见该类 KDoc）。
 */
fun optionNumberOf(label: String): Int? {
    var i = label.length - 1
    while (i >= 0) {
        if (label[i] == ']') {
            var j = i - 1
            while (j >= 0 && label[j] in '0'..'9') j--
            // j < i-1 ⇒ 至少一位数字；label[j] == '[' ⇒ 括号闭合成立
            if (j < i - 1 && j >= 0 && label[j] == '[') {
                return label.substring(j + 1, i).toIntOrNull()
            }
        }
        i--
    }
    return null
}

/**
 * 指令盘形态判据（§3.5 判据代码块原样落地）。
 *
 * [charCount] 是从屏行解析出的**合格角色块数**（[HudLineParser.parseHud] 的
 * `HudSnapshot.charCount`，解析不出传 0）。判据顺序是铁律：**先判 3xx** —— 交互页候选
 * 也可能超过 16 枚，但含 3xx 编号永远优先归 INTERACT/MULTI（§3.5「顺序先判 3xx」）；
 * `802`（变更对象）只作角色块解析失败时的存在性兜底判据，**禁拼提交值**（§10-13）。
 *
 * 边界摆法（§10-8，可接受、不加判据）：8~16 钮的存档页、无 3xx 的训练页都落 EXPLORE。
 */
fun trayFormOf(options: List<ConsoleOption>, charCount: Int): TrayForm {
    val numbers = options.map { optionNumberOf(it.label) }
    return when {
        numbers.any { it != null && it in 300..399 } ->
            if (charCount >= 2 || numbers.any { it == 802 }) TrayForm.MULTI else TrayForm.INTERACT
        options.size >= 16 -> TrayForm.MENU
        else -> TrayForm.EXPLORE
    }
}

/**
 * 立绘舞台里单张立绘的宽度常量（§3.6.3 表，`when(count)` 常量表，**禁比例计算**）。
 *
 * 数值 = 原型实测宽度（屏宽 390dp 下的自然值）。只跟在场人数走，**不跟断点走**
 * （§5.1 括注：避免两套变量打架）。count=1 的「190dp 或 60% 屏宽取小」里的 60% 一半
 * 在调用侧用宿主容器宽度做 min，这里只出常量 190dp。
 *
 * [count] ≤ 0 → 0dp（没有立绘，舞台整条不显示）；count > 10 理论上不发生
 * （原版立绘上限 10，§0-13），仍按 7~10 档的 76dp 横滑，不隐藏任何在场者。
 */
fun spriteWidthFor(count: Int): Dp = when {
    count <= 0 -> 0.dp
    count == 1 -> 190.dp
    count == 2 -> 138.dp
    count == 3 -> 108.dp
    count == 4 -> 92.dp
    count <= 6 -> 84.dp
    else -> 76.dp
}
