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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Home (brief Step 3): the risk-control screen (spec §5.5-6/7) — this school's rules and
 * current progress from [HomeViewModel] (visible so users judge for themselves), the
 * first-run risk notice (incl. the ≤3-device red line, spec §5.5-3), navigation into the
 * one-shot run and the diagnostics export, and the spec §7 About surface carrying the
 * confirm-guarded logout action.
 *
 * The non-blocking update prompt raised by the startup `version.json` check is NOT
 * rendered here: it moved to the global layer (MainActivity hosts
 * [dev.campusrun.app.update.UpdatePromptDialog] outside the auth branch) so an outdated
 * build prompts on the LoginScreen too — one renderer, one shared state, no double dialog.
 */
@Composable
fun HomeScreen(
    vm: HomeViewModel,
    onRunClick: () -> Unit,
    onDiagnosticsClick: () -> Unit,
) {
    LaunchedEffect(Unit) { vm.refresh() }
    val state = vm.state

    // 退出登录 (spec §5.5-3): confirm first, then [HomeViewModel.logout] drops the
    // session — [MainActivity] observes `loggedOut` and returns to the login screen.
    var confirmLogout by remember { mutableStateOf(false) }
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("退出登录") },
            text = {
                Text("将清除本机保存的登录会话，下次需重新登录。设备标识不受影响。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmLogout = false
                        vm.logout()
                    },
                ) { Text("退出") }
            },
            dismissButton = {
                TextButton(onClick = { confirmLogout = false }) { Text("取消") }
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

        // 当前校园名 (brief item 1): from the read-only user/info leg — the user's OWN
        // school name (and optional nickname), on-device only. Blank until the profile
        // leg lands; blank-on-failure shows nothing rather than inventing a school.
        if (state.schoolName.isNotEmpty()) {
            Text(
                buildString {
                    append("当前校园：")
                    append(state.schoolName)
                    if (state.nickname.isNotEmpty()) append(" · ${state.nickname}")
                },
                style = MaterialTheme.typography.titleMedium,
            )
        }

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
                Spacer(Modifier.height(8.dp))
                // 设备占用红线 (spec §5.5-3): UI must state it, users must not multi-device login.
                Text(
                    "设备占用提示：本工具与官方 App 可能分别计入设备数（单账号上限 3 台），" +
                        "请勿在多台设备登录同一账号。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("本校规则", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                // 学校要求的里程 (brief item 2): its own prominent line above the detail
                // rows, so the requirement reads as a fact even when the card is scanned
                // past. Out-of-window policy (findings §3.5) reads 未公布, never a number.
                if (state.requirementLine.isNotEmpty()) {
                    Text(
                        state.requirementLine,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(8.dp))
                }
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
                    // 当前已完成的里程 (brief item 3): scope-labeled client-side sum of
                    // the fetched page — the API exposes no semester summary (§3.1 D).
                    state.cumulativeLine.isNotEmpty() -> {
                        Text(
                            state.cumulativeLine,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (state.latestLine.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(state.latestLine, style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    state.loading -> Text("加载中…", style = MaterialTheme.typography.bodyMedium)
                    else -> Text("暂无跑步记录", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        // 历史跑步记录 (brief item 4): the fetched page (up to 20 rows), objective fields
        // only — date · distance · duration, no 达标/未达标 verdict, because our
        // status-derived `valid` diverges from the official scoring in both directions.
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("历史跑步记录", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                when {
                    state.historyRows.isNotEmpty() -> Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        state.historyRows.forEach { row ->
                            Text(row, style = MaterialTheme.typography.bodyMedium)
                        }
                    }

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

        // 关于 / 合规声明 (spec §7): 上游 MIT 致谢 + 明示免费/无售后/不建用户数据.
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("关于本工具", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "协议实现移植 / 衍生自 com.zjwh.android_wh_physicalfitness（MIT License）：" +
                        "https://github.com/Inverx/com.zjwh.android_wh_physicalfitness",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "本工具免费分发：不收取任何费用、无售后、不建立用户数据；" +
                        "仅供学习与研究使用，使用风险自负。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                // 退出登录: small, right-aligned action inside the About card — keeps the
                // main screen focused while remaining one tap away.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { confirmLogout = true }) { Text("退出登录") }
                }
            }
        }
    }
}
