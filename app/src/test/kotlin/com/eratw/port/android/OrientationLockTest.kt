package com.eratw.port.android

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 方向锁按钮的 `requestedOrientation` 回归锁样。
 *
 * 玩家反馈（2026-11-04）：手机开着系统旋转锁（竖屏）时，游戏内锁定一次再解除会被钉死在竖屏，
 * 只有去系统设置里转过屏幕才能恢复。根因是解锁写的是 `SCREEN_ORIENTATION_UNSPECIFIED`，
 * 等于把方向决定权交还给系统自动旋转偏好（系统锁竖屏 ⇒ 游戏也竖屏）；manifest 要的却是
 * `sensorLandscape`。这里锁死解锁必须是 `SENSOR_LANDSCAPE`，不许回退成 `UNSPECIFIED`。
 */
class OrientationLockTest {

    @Test
    fun locking_freezesCurrentOrientation() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LOCKED,
            MainActivity.orientationForLock(true, portraitMode = false),
        )
        // 竖屏局里锁定的语义不变：同样冻在当前方向，不解锁就不该回到偏好方向。
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LOCKED,
            MainActivity.orientationForLock(true, portraitMode = true),
        )
    }

    @Test
    fun unlocking_restoresManifestSensorLandscape() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            MainActivity.orientationForLock(false, portraitMode = false),
        )
    }

    @Test
    fun unlocking_inPortraitMode_restoresSensorPortrait() {
        // 竖屏局（设置页开了竖屏模式）解锁不许把人甩回横屏。
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
            MainActivity.orientationForLock(false, portraitMode = true),
        )
    }

    @Test
    fun unlocking_neverDefersToSystemAutoRotatePreference() {
        assertNotEquals(
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            MainActivity.orientationForLock(false, portraitMode = false),
        )
        assertNotEquals(
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            MainActivity.orientationForLock(false, portraitMode = true),
        )
    }
}
