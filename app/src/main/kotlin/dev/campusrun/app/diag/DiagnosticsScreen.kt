package dev.campusrun.app.diag

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.campusrun.app.run.RecentResults
import dev.campusrun.core.diag.DiagnosticExport
import dev.campusrun.core.diag.RedactedLog
import java.nio.file.Path

/**
 * One-tap diagnostic export (brief Step 3, spec §6.3 反馈回路): calls Task 11's
 * [DiagnosticExport] over the app's [RedactedLog], the config version and the recent-run
 * summary ([RecentResults]) into the app-specific files directory, then **shows the
 * produced absolute path** so the user can hand the file back (社群/私聊).
 */
@Composable
fun DiagnosticsScreen(
    outDir: Path,
    log: RedactedLog,
    configVersion: Int,
    onBack: () -> Unit,
) {
    var exporting by remember { mutableStateOf(false) }
    var exportedPath by remember { mutableStateOf<String?>(null) }
    var exportError by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("返回") }
            Text("诊断导出", style = MaterialTheme.typography.headlineSmall)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "导出内容：脱敏日志（不含手机号 / uid / token）、配置版本、最近提交结果摘要，" +
                "打包为本地 zip 文件。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                exporting = true
                exportedPath = null
                exportError = null
                // File I/O off the main thread; Compose snapshot state is thread-safe.
                Thread(
                    {
                        val result = runCatching {
                            DiagnosticExport.export(log, configVersion, RecentResults.lines(), outDir)
                        }
                        result.fold(
                            onSuccess = { path -> exportedPath = path.toString() },
                            onFailure = { e -> exportError = e.message ?: e::class.java.simpleName },
                        )
                        exporting = false
                    },
                    "diag-export",
                ).apply { isDaemon = true }.start()
            },
            enabled = !exporting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (exporting) {
                CircularProgressIndicator(
                    modifier = Modifier.height(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(8.dp))
                Text("导出中…")
            } else {
                Text("导出诊断包")
            }
        }
        Spacer(Modifier.height(16.dp))
        exportedPath?.let { path ->
            Text("已导出", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            // Long path: selectable so the user can copy it out for sharing.
            SelectionContainer {
                Text(path, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "可将该文件发送给开发者（文件不含账号信息）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        exportError?.let { message ->
            Text(
                "导出失败：$message",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
