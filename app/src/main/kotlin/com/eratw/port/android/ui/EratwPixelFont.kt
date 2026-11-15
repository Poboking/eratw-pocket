package com.eratw.port.android.ui

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.eratw.port.android.R

/**
 * 壳层像素字体的唯一出处 —— 与 [EratwInk] 同一个收口理由：同一套观感只许有一个定义。
 *
 * 字体是 **Ark Pixel 12px**（OFL-1.1，TakWolf/ark-pixel-font，许可证与随包说明见
 * `app/src/main/assets/licenses/ark-pixel-font-OFL.txt`），12px 点阵设计、等宽中文覆盖
 * zh_hans / 日文假名 / 拉丁。它和装载屏的像素风是同一族，教程这类「本体风」浮层直接复用，
 * 不再各自 `FontFamily(Font(R.font.ark_pixel))` 各建一份。
 *
 * 只给**观感向**的壳层用（装载屏、教程、像素风格的方块钮）。设置页正文仍走系统字体：
 * 长段落用点阵字读起来费眼，那条界线划在「一整屏说明」与「一块复古面板」之间。
 */
object EratwPixelFont {

    /** 全局唯一实例：[FontFamily] 每次新建都会让 Compose 重新解析字体表。 */
    val family: FontFamily = FontFamily(Font(R.font.ark_pixel))
}
