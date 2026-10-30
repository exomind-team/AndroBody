package org.exomind.androbody.nativeapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.exomind.androbody.nativeapp.model.ContextBudgetStatus
import org.exomind.androbody.nativeapp.model.ModelHttpClient
import org.exomind.androbody.nativeapp.storage.EncryptedStore

@Composable
internal fun ContextBudgetDialog(status: ContextBudgetStatus, onDismiss: () -> Unit) {
    AlertDialog(
        modifier = Modifier.testTag("context-budget-dialog"),
        onDismissRequest = onDismiss,
        title = { Text("上下文用量") },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MetricLine("模型窗口", "未知 · 尚无可核验的窗口元数据")

                MetricLine("请求正文", status.requestBytes?.let {
                    "${formatBytes(it.toLong())} / ${formatBytes(ModelHttpClient.MAX_REQUEST_BYTES.toLong())}"
                } ?: "尚无请求")
                status.requestBytes?.let { bytes ->
                    LinearProgressIndicator(
                        progress = { (bytes.toFloat() / ModelHttpClient.MAX_REQUEST_BYTES).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                val estimate = status.tokenEstimate
                MetricLine("Token 粗估", estimate?.let {
                    "${it.minimum}–${it.maximum} · 低置信估算"
                } ?: "尚无请求估算")
                val usage = status.providerInputUsage
                MetricLine("服务商实报", usage?.let {
                    "${it.tokens} input tokens · ${it.field}"
                } ?: "本会话尚无")
                if (usage != null && status.providerModel != null) {
                    Text("${status.providerProtocol.orEmpty()} · ${status.providerModel}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                MetricLine("加密会话", status.encryptedSessionBytes?.let {
                    "${formatBytes(it)} / ${formatBytes(EncryptedStore.MAX_RECORD_BYTES.toLong())}"
                } ?: "尚无已保存记录")
                status.encryptedSessionBytes?.let { bytes ->
                    LinearProgressIndicator(
                        progress = { (bytes.toFloat() / EncryptedStore.MAX_RECORD_BYTES).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Text("历史不会静默裁剪。超过实际请求或加密记录上限时，会明确报错并保留原记录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun MetricLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024L * 1024L -> "${(bytes + 512) / 1024} KiB"
    else -> "${(bytes + 512 * 1024) / (1024L * 1024L)} MiB"
}
