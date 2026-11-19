package dev.campusrun.app.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Home (brief Step 3): the risk-control screen (spec §5.5-6/7) — this school's rules and
 * current progress from [HomeViewModel] (visible so users judge for themselves), the
 * first-run risk notice, navigation into the one-shot run and the diagnostics export,
 * and the non-blocking update prompt raised by the startup `version.json` check.
 */
@Composable
fun HomeScreen(
    vm: HomeViewModel,
    onRunClick: () -> Unit,
    onDiagnosticsClick: () -> Unit,
) {
    LaunchedEffect(Unit) { vm.refresh() }
    val state = vm.state

    vm.updatePrompt?.let { prompt ->
        // Non-blocking (spec G4/R5): a dismissible dialog — the app keeps working without updating.
        AlertDialog(
            onDismissRequest = vm::dismissUpdatePrompt,
            title = { Text("发现新版本") },
            text = { Text(prompt) },
            confirmButton = {
                TextButton(onClick = vm::dismissUpdatePrompt) { Text("知道了") }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("校园跑", style = MaterialTheme.typography.headlineSmall)

        // 风险告知 (spec §5.5-7): T12 shipped no disclaimer, so the minimal first-run
        // notice lives here, permanently visible (no prefs seam for a dismiss flag yet).
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("风险提示", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "仅供学习与研究使用，使用风险自负。查实作弊将清除当次及累计里程、全校通报并" +
                        "额外扣减公里，累计多次本学期 0 分；违反校规 / 服务条款的后果由使用者承担。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("本校规则", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                when {
                    state.rules.isNotEmpty() -> state.rules.forEach { line ->
                        Text(line, style = MaterialTheme.typography.bodyMedium)
                    }

                    state.loading -> Text("加载中…", style = MaterialTheme.typography.bodyMedium)
                    state.error != null -> Text(
                        "规则加载失败，稍后下拉重试",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )

                    else -> Text("暂未取得本校规则", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("当前进度", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                when {
                    state.historyLine.isNotEmpty() ->
                        Text(state.historyLine, style = MaterialTheme.typography.bodyMedium)

                    state.loading -> Text("加载中…", style = MaterialTheme.typography.bodyMedium)
                    else -> Text("暂无跑步记录", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        state.error?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(onClick = onRunClick, modifier = Modifier.weight(1f)) { Text("一键跑") }
            OutlinedButton(onClick = onDiagnosticsClick, modifier = Modifier.weight(1f)) {
                Text("导出诊断")
            }
        }

        TextButton(onClick = { vm.refresh() }, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.loading) "刷新中…" else "刷新规则与进度")
        }
    }
}
