package io.github.ayatinene.sonycompanion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Shared with the controller; false unless the user opts in. */
internal const val SONY_SLEEP_ON_DISCONNECT = "sonySleepOnDisconnect"

internal const val SONY_SLEEP_TITLE = "断开时让相机休眠（实验性）"
internal const val SONY_SLEEP_DESCRIPTION =
    "默认关闭。仅在原文件连接空闲时手动断开才尝试；相机可能退出“发送到电脑”并休眠，再次连接可能需在机身操作。"

/** Settings row whose whole surface toggles [checked]. The text column takes the remaining width so
 * a long [title] wraps instead of squeezing the switch. State is hoisted for preview/test. */
@Composable
internal fun SettingRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(2.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null, modifier = Modifier.testTag("$tag-switch"))
    }
}

/** RAW-section toggle for the experimental camera-sleep-on-disconnect behaviour. */
@Composable
internal fun SonySleepSetting(
    checked: Boolean,
    modifier: Modifier = Modifier,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingRow(
        title = SONY_SLEEP_TITLE,
        description = SONY_SLEEP_DESCRIPTION,
        checked = checked,
        onCheckedChange = onCheckedChange,
        tag = "sony-sleep-on-disconnect",
        modifier = modifier,
    )
}
