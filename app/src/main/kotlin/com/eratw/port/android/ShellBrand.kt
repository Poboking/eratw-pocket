package com.eratw.port.android

import android.content.Context

/**
 * 壳层品牌配置的运行期读口（SHELL-PROFILE）。
 *
 * 值由 `pack_release.py --shell-profile` 经 Gradle `resValue` 烙进 resources.arsc，
 * 无配置时为 `eraTW` / 空覆盖 / `full`，与引入本机制前的字面量逐字一致。
 * 只给 app 宿主用：不进 core-vm，不参与引擎档判定，不碰存档与 Mod 语义。
 */
internal object ShellBrand {
    private const val DEFAULT_GAME_NAME = "eraTW"
    private const val MODE_BASIC = "basic"

    /** 配置文件指定的游戏名；基础教程用它替换所有 eraTW 语料专名。 */
    fun gameName(context: Context): String =
        context.getString(R.string.shell_game_name).ifBlank { DEFAULT_GAME_NAME }

    /** 加载页两版共用的标题覆盖；空串 = 沿用原三语表。 */
    fun loadingHeadline(context: Context): String =
        context.getString(R.string.shell_loading_headline)

    /** 加载页进度说明覆盖；空串 = 沿用原三语表。 */
    fun loadingDetail(context: Context): String =
        context.getString(R.string.shell_loading_detail)

    /** 第三方包默认走中性基础教程；eraTW 无配置时走原七页。 */
    fun useBasicTutorial(context: Context): Boolean =
        context.getString(R.string.shell_tutorial_mode) == MODE_BASIC
}
