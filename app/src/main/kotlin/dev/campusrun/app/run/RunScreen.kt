package dev.campusrun.app.run

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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

/**
 * One-shot run UI (brief Step 3): distance/time inputs (defaults are the T9 live-verified
 * 2030 m / 760 s), the run button, and the [RunState] outcome — Success shows the mileage,
 * Error shows the gate's first violation. Nothing is submitted until the button is tapped;
 * a refusal in [RunState.Error] means the validator stopped the pipeline before upload.
 */
@Composable
fun RunScreen(vm: RunViewModel, onBack: () -> Unit) {
    var distanceText by remember { mutableStateOf(DEFAULT_DISTANCE_M) }
    var durationText by remember { mutableStateOf(DEFAULT_DURATION_S) }
    val state = vm.state

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("返回") }
            Text("一键跑", style = MaterialTheme.typography.headlineSmall)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "提交前按本校规则自动校验（时段 / 配速 / 距离 / 点位），任一不通过即拒绝提交、不上传。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = distanceText,
            onValueChange = { distanceText = it },
            label = { Text("距离（米）") },
            singleLine = true,
            enabled = state !is RunState.Running,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = durationText,
            onValueChange = { durationText = it },
            label = { Text("时长（秒）") },
            singleLine = true,
            enabled = state !is RunState.Running,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                vm.startRun(
                    distanceM = distanceText.trim().toIntOrNull() ?: 0,
                    durationSec = durationText.trim().toIntOrNull() ?: 0,
                )
            },
            enabled = state !is RunState.Running,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state is RunState.Running) {
                CircularProgressIndicator(
                    modifier = Modifier.height(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(8.dp))
                Text("提交中…")
            } else {
                Text("开始一键跑")
            }
        }
        Spacer(Modifier.height(16.dp))
        when (state) {
            is RunState.Idle -> Text(
                "默认 2030 m / 760 s：距离 ≥ 2000 m 结算下限，配速落在本校窗口内。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            is RunState.Running -> Text(
                "正在同步服务器时间并校验规则…",
                style = MaterialTheme.typography.bodyMedium,
            )

            is RunState.Success -> Text(
                state.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )

            is RunState.Error -> Text(
                state.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** T9 live-verified defaults: ≥ the 2000 m floor, ≈374 s/km inside the 180–600 window. */
private const val DEFAULT_DISTANCE_M = "2030"
private const val DEFAULT_DURATION_S = "760"
