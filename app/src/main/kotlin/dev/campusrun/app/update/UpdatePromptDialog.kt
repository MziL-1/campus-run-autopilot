package dev.campusrun.app.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.campusrun.app.home.UpdatePrompt

/**
 * The non-blocking update dialog (spec G4/R5) — moved OUT of `HomeScreen` so
 * [dev.campusrun.autopilot.MainActivity] can host it above **any** screen, including
 * `LoginScreen` (an outdated build must prompt before login, fix: auth-state-independent
 * prompt). Behavior is unchanged: dismissible (知道了 / tap-outside — the app keeps
 * working without updating) and the first `apkMirrors` URL is shown + copyable.
 *
 * Rendered from exactly one place (MainActivity's content, outside the `token == null`
 * branch) driven by the shared [UpdatePromptState] — that single wiring is the
 * double-prompt guard.
 */
@Composable
fun UpdatePromptDialog(
    prompt: UpdatePrompt,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("发现新版本") },
        text = {
            Column {
                Text(prompt.message)
                prompt.downloadUrl?.let { url ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                prompt.downloadUrl?.let { url ->
                    TextButton(onClick = { clipboard.setText(AnnotatedString(url)) }) {
                        Text("复制链接")
                    }
                }
                TextButton(onClick = onDismiss) { Text("知道了") }
            }
        },
    )
}
