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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.exomind.androbody.nativeapp.AppGraph
import org.exomind.androbody.nativeapp.diagnostics.*

@Composable fun RuntimeConsoleScreen(graph: AppGraph, notice: (String) -> Unit) {
    val recentEvents by graph.networkDiagnostics.events.collectAsStateWithLifecycle()
    var retainedEvents by remember(graph.networkDiagnostics) { mutableStateOf<List<TraceEvent>?>(null) }
    LaunchedEffect(graph.networkDiagnostics) {
        retainedEvents = withContext(Dispatchers.IO) { graph.networkDiagnostics.retainedForConsole() }
    }
    val events = remember(retainedEvents, recentEvents) {
        val merged = linkedMapOf<String, TraceEvent>()
        retainedEvents.orEmpty().forEach { merged[it.uniqueKey()] = it }
        recentEvents.forEach { merged[it.uniqueKey()] = it }
        merged.values.sortedBy { it.atMillis }.takeLast(DiagnosticStore.MAX_CONSOLE_EVENTS)
    }
    val health by graph.networkDiagnostics.health.collectAsStateWithLifecycle()
    val lastExport by graph.networkDiagnostics.lastExport.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val input = LocalInputContext.current
    var minimumLevel by rememberSaveable { mutableStateOf<RuntimeLogLevel?>(null) }
    var subsystem by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var levelMenu by remember { mutableStateOf(false) }
    var subsystemMenu by remember { mutableStateOf(false) }
    var scopeInfo by rememberSaveable { mutableStateOf(false) }
    var selectedEvent by remember { mutableStateOf<TraceEvent?>(null) }
    var exporting by remember { mutableStateOf(false) }
    val filter = RuntimeLogFilter(minimumLevel, subsystem, search = query)
    val visible = remember(events, filter) { events.filter(filter::matches).asReversed() }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) {
            graph.runtimeLog.record("diagnostics", "runtime_export_cancelled", level = RuntimeLogLevel.INFO,
                evidenceKind = EvidenceKind.AGENT_REQUEST, feasibility = CapabilityFeasibility.UNKNOWN)
            exporting = false; notice("已取消运行日志导出")
        }
        else scope.launch {
            try {
                val result = exportRuntimeDiagnostics(context.contentResolver, uri, graph.networkDiagnostics, filter)
                graph.runtimeLog.record("diagnostics", "runtime_export_finished", level = RuntimeLogLevel.INFO,
                    evidenceKind = EvidenceKind.RESULT, feasibility = when (result.status) {
                        "VERIFIED" -> CapabilityFeasibility.COMPLETED
                        "WRITTEN_UNVERIFIED" -> CapabilityFeasibility.UNKNOWN
                        else -> CapabilityFeasibility.UNKNOWN
                    }, fields = mapOf("status" to result.status, "bytes" to result.bytes.toString(),
                        "sha256" to result.sha256, "eventCount" to visible.size.toString()))
                notice(when (result.status) {
                    "VERIFIED" -> "运行记录已保存并独立读回校验（${result.bytes} 字节）。"
                    "WRITTEN_UNVERIFIED" -> "日志写入已返回，但无法确认读回一致；请检查目标文件。"
                    else -> "日志导出结果未知；未自动重试，请核对目标文件。"
                })
            } catch (failure: Exception) {
                graph.runtimeLog.record("diagnostics", "runtime_export_failed", level = RuntimeLogLevel.ERROR,
                    evidenceKind = EvidenceKind.RESULT, feasibility = CapabilityFeasibility.FAILED,
                    fields = mapOf("exceptionClass" to failure.javaClass.name))
                notice("导出未完成或读回失败；没有自动重试")
            }
            finally { exporting = false }
        }
    }
    val subsystems = remember(events) { events.map { it.subsystem }.distinct().sorted() }

    LazyColumn(
        modifier = Modifier.testTag("runtime-console"),
        contentPadding = PaddingValues(LayoutTokens.pagePadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    SectionTitle("应用运行控制台", "AndroBody 事件 · 内容默认脱敏")
                    Text("当前进程 ${graph.runtimeLog.processRunId.take(8)} · " +
                        (if (retainedEvents == null) "正在读取轮转记录" else "保留 ${events.size} 条") + " · 筛选 ${visible.size}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { scopeInfo = true }) { Text("范围") }
            }
            health?.let { InfoPanel("部分事件未能持久保存", it, error = true) }
            lastExport?.let { Text("最近导出：${it.status} · ${it.bytes} bytes · ${readableTime(it.atMillis)}", style = MaterialTheme.typography.labelSmall) }
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it.take(120) },
                singleLine = true,
                label = { Text("搜索事件、request ID 或能力名") },
                modifier = Modifier.fillMaxWidth().testTag("runtime-console-search")
                    .onFocusChanged { input.editing = it.isFocused },
            )
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    TextButton(onClick = { levelMenu = true }, modifier = Modifier.fillMaxWidth().testTag("runtime-console-level")) {
                        Text(minimumLevel?.let { "≥ ${it.name}" } ?: "全部级别", maxLines = 1)
                    }
                    DropdownMenu(expanded = levelMenu, onDismissRequest = { levelMenu = false }) {
                        DropdownMenuItem(text = { Text("全部级别") }, onClick = { minimumLevel = null; levelMenu = false })
                        RuntimeLogLevel.entries.forEach { level ->
                            DropdownMenuItem(text = { Text(level.name) }, onClick = { minimumLevel = level; levelMenu = false })
                        }
                    }
                }
                Box(Modifier.weight(1f)) {
                    TextButton(onClick = { subsystemMenu = true }, modifier = Modifier.fillMaxWidth().testTag("runtime-console-subsystem")) {
                        Text(subsystem ?: "全部子系统", maxLines = 1)
                    }
                    DropdownMenu(expanded = subsystemMenu, onDismissRequest = { subsystemMenu = false }) {
                        DropdownMenuItem(text = { Text("全部子系统") }, onClick = { subsystem = null; subsystemMenu = false })
                        subsystems.forEach { name ->
                            DropdownMenuItem(text = { Text(name) }, onClick = { subsystem = name; subsystemMenu = false })
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    val text = visible.joinToString("\n") { it.readableLine() }
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("AndroBody runtime trace", text))
                    graph.runtimeLog.record("diagnostics", "runtime_events_copied", level = RuntimeLogLevel.INFO,
                        evidenceKind = EvidenceKind.RESULT, fields = mapOf("eventCount" to visible.size.toString(),
                            "filterLevel" to (minimumLevel?.name ?: "ALL"), "filterSubsystem" to (subsystem ?: "ALL")))
                    notice("已复制 ${visible.size} 条脱敏运行事件")
                }, enabled = visible.isNotEmpty(), modifier = Modifier.testTag("runtime-console-copy")) { Text("复制") }
                OutlinedButton(onClick = {
                    exporting = true
                    graph.runtimeLog.record("diagnostics", "runtime_export_requested", level = RuntimeLogLevel.INFO,
                        evidenceKind = EvidenceKind.AGENT_REQUEST, feasibility = CapabilityFeasibility.NEEDS_USER_ACTION,
                        fields = mapOf("eventCount" to visible.size.toString(),
                            "filterLevel" to (minimumLevel?.name ?: "ALL"), "filterSubsystem" to (subsystem ?: "ALL")))
                    exporter.launch("andromorph-runtime-${System.currentTimeMillis()}.json")
                }, enabled = !exporting, modifier = Modifier.testTag("runtime-console-export")) { Text("导出") }
            }
            Text("导出当前筛选的保留事件；系统选择保存位置。", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (visible.isEmpty()) item {
            Text("没有符合条件的事件。尚无记录时，不代表所有能力都已验证。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(visible, key = { it.uniqueKey() }) { event ->
            RuntimeEventCard(event, graph.runtimeLog.processRunId, onClick = { selectedEvent = event })
        }
    }

    selectedEvent?.let { event ->
        AlertDialog(
            onDismissRequest = { selectedEvent = null },
            title = { Text(event.stage) },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SelectableText(event.readableDetails(), code = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("AndroBody runtime event", event.readableDetails()))
                    selectedEvent = null; notice("已复制单条运行事件")
                }) { Text("复制事件") }
            },
            dismissButton = { TextButton(onClick = { selectedEvent = null }) { Text("关闭") } },
        )
    }
    if (scopeInfo) AlertDialog(
        onDismissRequest = { scopeInfo = false },
        title = { Text("运行记录范围") },
        text = { SelectableText("控制台记录 AndroBody 自身的 Agent、工具、网络、权限预检和服务事件，不读取 Android 系统或其他 App 的完整 Logcat。默认不记录提示词、密钥、通知正文或音频；API 请求正文另有单次授权的独立捕获。日志按容量轮转，导出只含当前保留的数据。") },
        confirmButton = { TextButton(onClick = { scopeInfo = false }) { Text("关闭") } },
    )
}

@Composable private fun RuntimeEventCard(event: TraceEvent, currentProcessRunId: String, onClick: () -> Unit) {
    val level = RuntimeLogLevel.parse(event.level)
    val error = level.priority >= RuntimeLogLevel.ERROR.priority || event.feasibility == CapabilityFeasibility.BLOCKED_OBSERVED.name
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag("runtime-event-${event.sequence}")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(event.stage, style = MaterialTheme.typography.titleSmall,
                    color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                Text(level.name, style = MaterialTheme.typography.labelSmall,
                    color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("${event.subsystem} · ${event.origin} · ${readableTime(event.atMillis)}", Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall, maxLines = 1)
                Text(when {
                    event.processRunId == null -> "旧记录"
                    event.processRunId == currentProcessRunId -> "本进程"
                    else -> "前次进程"
                }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            EventStateLabel(event)
            (event.fields["capability"] ?: event.fields["requestedCapability"])?.let { capability ->
                Text(capability, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Text("request ${event.requestId.takeLast(12)} · ${event.traceId.takeLast(12)}", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun EventStateLabel(event: TraceEvent) {
    val evidence = event.evidenceKind?.let(::evidenceLabel)
    val feasibility = event.feasibility?.let(::feasibilityLabel)
    if (evidence == null && feasibility == null) return
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        evidence?.let { RuntimeStateTag(it) }
        feasibility?.let { RuntimeStateTag(it) }
    }
}

@Composable private fun RuntimeStateTag(label: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
        Text(label, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
    }
}

private fun TraceEvent.uniqueKey(): String = "$processRunId|$traceId|$sequence|$atMillis|$stage"
private fun TraceEvent.readableLine(): String =
    "${atMillis} ${level} ${subsystem}/${stage} evidence=${evidenceKind ?: "-"} feasibility=${feasibility ?: "-"} request=${requestId} ${fields}"
private fun TraceEvent.readableDetails(): String = buildString {
    appendLine("级别：$level    子系统：$subsystem")
    appendLine("证据：${evidenceKind ?: "未分类"}    可行性：${feasibility ?: "未评估"}")
    appendLine("来源：$origin    时间：${readableTime(atMillis)}    耗时：${elapsedMicros / 1000} ms")
    appendLine("进程：${processRunId ?: "旧记录"}")
    appendLine("request：$requestId\ntrace：$traceId\nattempt：$attempt")
    fields.toSortedMap().forEach { (key, value) -> appendLine("$key: $value") }
}

private fun evidenceLabel(value: String): String = when (value) {
    EvidenceKind.THEORETICAL.name -> "理论候选"
    EvidenceKind.STATIC_CANDIDATE.name -> "静态候选"
    EvidenceKind.IMPLEMENTATION.name -> "已注册实现"
    EvidenceKind.PREFLIGHT.name -> "权限/可行性预检"
    EvidenceKind.AGENT_REQUEST.name -> "Agent 请求"
    EvidenceKind.ACTUAL_ATTEMPT.name -> "真实调用"
    EvidenceKind.PLATFORM_RESPONSE.name -> "平台响应"
    EvidenceKind.RESULT.name -> "结果回执"
    EvidenceKind.RECOVERY.name -> "恢复记录"
    else -> value
}

private fun feasibilityLabel(value: String): String = when (value) {
    CapabilityFeasibility.NOT_ASSESSED.name -> "尚未评估"
    CapabilityFeasibility.NOT_TESTED.name -> "尚未尝试"
    CapabilityFeasibility.NEEDS_PERMISSION.name -> "需要系统权限"
    CapabilityFeasibility.NEEDS_USER_ACTION.name -> "等待用户操作"
    CapabilityFeasibility.POLICY_DENIED.name -> "被工具策略拒绝"
    CapabilityFeasibility.USER_DENIED.name -> "用户拒绝本次"
    CapabilityFeasibility.BLOCKER_CANDIDATE.name -> "候选阻碍·未验证"
    CapabilityFeasibility.BLOCKED_OBSERVED.name -> "实测受阻"
    CapabilityFeasibility.NOT_IMPLEMENTED.name -> "没有注册适配器"
    CapabilityFeasibility.UNAVAILABLE.name -> "当前不可用"
    CapabilityFeasibility.SUBMITTED.name -> "已提交"
    CapabilityFeasibility.COMPLETED.name -> "已完成"
    CapabilityFeasibility.FAILED.name -> "执行失败"
    CapabilityFeasibility.UNKNOWN.name -> "结果未知"
    CapabilityFeasibility.AVAILABLE.name -> "预检可用"
    CapabilityFeasibility.IMPLEMENTED.name -> "实现存在"
    else -> value
}
