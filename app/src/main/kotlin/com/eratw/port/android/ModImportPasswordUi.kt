package com.eratw.port.android

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.eratw.port.android.ui.EratwFilledButton
import com.eratw.port.android.ui.EratwText
import com.eratw.port.android.ui.EratwTextTone
import com.eratw.port.android.ui.design.ShellField
import com.eratw.port.android.ui.design.ShellType

/**
 * 加密口上 zip 的**补救面**（输入密码 → 原样重跑导入）。
 *
 * 单独成文件的理由与 [modImportErrorReportText] 同款：`ModImportUi.kt` 的顶层函数数已顶在
 * detekt 文件级上限（再加一个就被闸红），而这条补救面自带「判据 + 输入框 + 按钮」三件，
 * 塞回原文件只会逼着放宽阈值。
 *
 * 判据（[needsImportPassword]）纯函数化：Compose 里判不了，单测逐态断言；
 * `ModImportFailureKind.PASSWORD` 是**玩家自己能修**的失败（去发布页拿密码），
 * 所以不能只给他一句报错。
 */

/**
 * 这一屏要不要出「输入解压密码」：单包失败 = kind PASSWORD；批导入 = 任一项是 PASSWORD。
 *
 * 批导入按「任一项」判：加密包混在多选里时同样给出补救口，玩家不必重新选一遍。
 */
internal fun needsImportPassword(state: ModImportUiState): Boolean = when (state) {
    is ModImportUiState.Failed -> state.kind == ModImportFailureKind.PASSWORD
    is ModImportUiState.BatchFinished -> state.outcome.items.any {
        (it.outcome as? ModImportOutcome.Failed)?.kind == ModImportFailureKind.PASSWORD
    }
    is ModImportUiState.Idle, is ModImportUiState.Importing, is ModImportUiState.Succeeded -> false
}

/**
 * 覆盖层里的密码补救段：需要时出「输入框 + 重试按钮」，不需要时**一个像素都不占**
 * （判据在纯函数 [needsImportPassword]，壳层只调这一处）。
 *
 * 输入框**不隐藏字符**：这类口令是口上作者公开在发布页的，看得见才改得对（手机上打错
 * 一个字符、又看不见，只会在「密码不对」里绕圈）。密码只在内存里过一手，不落 prefs、
 * 不写存档、不进错误报告。
 */
@Composable
internal fun PasswordRetrySection(
    state: ModImportUiState,
    copy: ModImportCopy,
    onRetry: (String) -> Unit,
) {
    if (!needsImportPassword(state)) return
    Spacer(modifier = Modifier.height(8.dp))
    var draft by remember { mutableStateOf("") }
    val password = draft.trim()
    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            singleLine = true,
            label = { EratwText(text = copy.zipPasswordFieldLabel, tone = EratwTextTone.NOTE) },
            textStyle = ShellType.Body,
            colors = ShellField.outlined(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(6.dp))
        EratwFilledButton(
            text = copy.zipPasswordRetryButton,
            onClick = { onRetry(password) },
            enabled = password.isNotEmpty(),
        )
    }
}
