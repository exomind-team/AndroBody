package org.exomind.androbody.nativeapp.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.exomind.androbody.nativeapp.AppGraph
import org.exomind.androbody.nativeapp.diagnostics.TraceEvent
import org.exomind.androbody.nativeapp.diagnostics.exportDiagnostics
import org.exomind.androbody.nativeapp.diagnostics.summarizeTrace

@Composable fun NetworkDiagnosticsScreen(graph: AppGraph, notice: (String) -> Unit) {
    val events by graph.networkDiagnostics.events.collectAsStateWithLifecycle()
    val health by graph.networkDiagnostics.health.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var captureDialog by remember { mutableStateOf(false) }
    var exportContentDialog by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var includeContent by remember { mutableStateOf(false) }
    var options by rememberSaveable { mutableStateOf(false) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) { exporting = false; notice("已取消导出") }
        else scope.launch {
            try {
                val exported = exportDiagnostics(context.contentResolver, uri, graph.networkDiagnostics, includeContent)
                notice(when (exported.status) {
                    "VERIFIED" -> "诊断已写入并独立读回校验；不包含 API Key。"
                    "WRITTEN_UNVERIFIED" -> "诊断写入已返回，但无法确认读回一致；请检查导出文档。"
                    else -> "诊断导出结果未知；未自动重复写入，请核对目标文档。"
                })
            } catch (_: Exception) { notice("导出未完成，目标文档可能部分写入；没有自动重试") }
            finally { exporting = false; includeContent = false }
        }
    }
    val transportEvents = remember(events) { events.filter { it.subsystem == "transport" } }
    val groups = remember(transportEvents) { transportEvents.groupBy { it.traceId }.entries.toList().asReversed() }
    LazyColumn(contentPadding = PaddingValues(LayoutTokens.pagePadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionTitle("模型请求诊断", "选择请求查看依据，或导出同一份现场。默认隐藏消息正文，导出前仍需审查错误摘要。")
            health?.let { InfoPanel("部分应用事件未落盘", it, error = true) }
            graph.endpointCache.warning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = {
                val text = groups.joinToString("\n\n") { summarizeTrace(it.value).readable() }
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("AndroBody network summary", text))
                notice("已复制诊断摘要；包含服务地址和模型连接信息")
            }, enabled = groups.isNotEmpty()) { Text("一键复制摘要") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { exporting = true; includeContent = false; exporter.launch("andromorph-network-${System.currentTimeMillis()}.json") },
                    enabled = !exporting, modifier = Modifier.testTag("diagnostics-export")) { Text("导出诊断") }
                TextButton(onClick = { options = !options }) { Text(if (options) "收起诊断选项" else "诊断选项") }
            }
            if (options) Column {
            Text("基础地址与实际端点分别记录；HTTP 成功、协议有效、设备任务完成分别核验。正文捕获可能含私人资料。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { captureDialog = true }) { Text("捕获下一次正文") }
            TextButton(onClick = { exportContentDialog = true }, enabled = !exporting) { Text("含已授权正文的导出…") }
            TextButton(onClick = { scope.launch {
                try { withContext(Dispatchers.IO) { graph.networkDiagnostics.clearContentCapture() }; notice("正文捕获已取消并清除，元数据记录保留") }
                catch (_: Exception) { notice("未能确认正文缓存清除，请查看诊断存储状态") }
            } }) { Text("取消并清除正文捕获") }
            }
        }
        if (groups.isEmpty()) item { Text("尚无模型传输记录。保存设置不会伪造网络日志。") }
        items(groups, key = { it.key }) { group ->
            val rows = group.value.sortedBy { it.sequence }
            val summary = summarizeTrace(rows)
            val ended = rows.lastOrNull { it.stage in setOf("request_completed", "request_failed", "request_cancelled") }
            val start = rows.firstOrNull { it.stage == "request_start" }
            val actualRoute = rows.lastOrNull { it.stage == "request_headers_end" }?.fields?.get("url")
            val plannedRoute = rows.lastOrNull { it.stage == "attempt_start" }?.fields?.get("url")
            val route = if (actualRoute != null) "实际Request：$actualRoute"
                else "计划端点（尚无发送证据）：${plannedRoute ?: "尚未解析"}"
            OutlinedCard(onClick = { selected = group.key }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(summary.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (ended?.stage == "request_failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    Text("${start?.fields?.get("protocol").orEmpty()} · ${rows.first().origin}", style = MaterialTheme.typography.labelMedium)
                    Text(route, style = MaterialTheme.typography.bodySmall)
                    summary.stopReason?.let { Text("停止依据：$it", style = MaterialTheme.typography.bodySmall) }
                    Text("${readableTime(rows.first().atMillis)} · ${rows.maxOf { it.attempt }} 次尝试 · ${rows.last().elapsedMicros / 1000} ms", style = MaterialTheme.typography.labelSmall)
                    Text(group.key, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
    selected?.let { traceId ->
        val rows = transportEvents.filter { it.traceId == traceId }.sortedBy { it.sequence }
        AlertDialog(onDismissRequest = { selected = null }, title = { Text("请求时间线") },
            text = { Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SelectableText("诊断 $traceId\n任务 ${rows.firstOrNull()?.requestId.orEmpty()}\n最多显示最近 ${org.exomind.androbody.nativeapp.diagnostics.DiagnosticStore.MAX_MEMORY_EVENTS} 个事件；导出含构建与设备信息。")
                rows.forEach { row ->
                    Text("#${row.sequence} · ${row.elapsedMicros / 1000}ms · 路径${row.attempt} · ${row.stage}", style = MaterialTheme.typography.labelLarge)
                    SelectableText(row.fields.entries.joinToString("\n") { "${it.key}: ${it.value}" }, code = true)
                    HorizontalDivider()
                }
            } }, confirmButton = { TextButton(onClick = { selected = null }) { Text("关闭") } })
    }
    if (captureDialog) AlertDialog(onDismissRequest = { captureDialog = false }, title = { Text("仅捕获下一次本机/测试请求正文") }, text = {
        Text("仅下一次内置 Agent 或连接测试生效，不捕获网关客户端或后台通知。正文可能包含私人资料，将限量加密保存、10分钟内可单独导出；密钥和敏感头仍脱敏。只在排障时启用，不自动发送给开发者。")
    }, confirmButton = { TextButton(onClick = { graph.networkDiagnostics.armContentCapture(); captureDialog = false; notice("已等待下一次本机请求；可在此取消") }) { Text("本次允许") } },
        dismissButton = { TextButton(onClick = { graph.networkDiagnostics.disarmContentCapture(); captureDialog = false }) { Text("取消捕获") } })
    if (exportContentDialog) AlertDialog(onDismissRequest = { exportContentDialog = false }, title = { Text("确认导出正文") }, text = {
        Text("有效期内的已授权正文将与诊断一起导出，可能包含私人内容。请只交给可信的本地调试者；过期或没有捕获则不会附加正文。")
    }, confirmButton = { TextButton(onClick = { exportContentDialog = false; exporting = true; includeContent = true; exporter.launch("andromorph-network-private-${System.currentTimeMillis()}.json") }) { Text("选择导出位置") } },
        dismissButton = { TextButton(onClick = { exportContentDialog = false }) { Text("取消") } })
}
