package org.exomind.androbody.nativeapp.ui

import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.exomind.androbody.nativeapp.AppGraph
import org.exomind.androbody.nativeapp.config.EndpointPolicy
import org.exomind.androbody.nativeapp.events.NoticeListener

@Composable fun RecordsScreen(graph: AppGraph, notice: (String) -> Unit) {
    var legacyLogs by rememberSaveable { mutableStateOf(false) }
    val legacyEntries by graph.networkLogs.entries.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("回执") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("摘要") })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("网络") })
            Tab(selected = tab == 3, onClick = { tab = 3 }, text = { Text("运行台") }, modifier = Modifier.testTag("records-tab-runtime"))
        }
        when (tab) {
            0 -> ReceiptList(graph)
            1 -> TriggerPanel(graph, notice)
            2 -> Column(Modifier.fillMaxSize()) {
                if (legacyEntries.isNotEmpty()) TextButton(onClick = { legacyLogs = !legacyLogs }) { Text(if (legacyLogs) "返回当前网络诊断" else "查看旧版日志") }
                if (legacyLogs) NetworkLogList(graph, notice) else NetworkDiagnosticsScreen(graph, notice)
            }
            else -> RuntimeConsoleScreen(graph, notice)
        }
    }
}
@Composable private fun NetworkLogList(graph: AppGraph, notice: (String) -> Unit) {
    val entries by graph.networkLogs.entries.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val logText = entries.asReversed().joinToString("\n") {
        "${readableTime(it.at)} ${it.status} ${it.httpStatus ?: "-"} ${it.protocol} ${it.model} ${it.durationMs}ms\n" +
            "${it.url}\nrequest=${it.requestId}\n${it.errorCode ?: ""}"
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(LayoutTokens.pagePadding), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                SectionTitle("网络日志", "旧版保留记录；缺少发送边界证据，不补造时间线。新请求请查看当前网络诊断。")
            }
            TextButton(onClick = {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("AndroBody network log", logText))
                notice("已复制脱敏网络日志")
            }, enabled = entries.isNotEmpty()) { Text("一键复制") }
        }
        LazyColumn(contentPadding = PaddingValues(LayoutTokens.pagePadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(entries.asReversed(), key = { it.id }) { entry ->
                InfoPanel("${entry.status} · ${entry.httpStatus ?: "-"} · ${entry.protocol}",
                    "${entry.url}\n模型 ${entry.model} · ${entry.durationMs}ms\n请求 ${entry.requestId}\n${entry.errorCode ?: ""}",
                    error = entry.status == "FAILED")
            }
            if (entries.isEmpty()) item { Text("暂无网络请求记录。") }
        }
    }
}
@Composable private fun ReceiptList(graph: AppGraph) {
    val entries by graph.receipts.entries.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    LazyColumn(contentPadding = PaddingValues(LayoutTokens.pagePadding), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { SectionTitle("动作不是一句“成功”", "最多保留 200 条审计元数据；不记录密钥、调用参数或通知全文。模型结论与工具回执分开。") }
        if (graph.receipts.loadError) item { InfoPanel("审计存储不可读", "原文件未清空，设备调用会停止登记并拒绝继续。交本地 Codex 排查，不要清数据绕过。", true) }
        items(entries.asReversed(), key = { it.id }) { entry ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) { Text(entry.tool, style = MaterialTheme.typography.titleSmall)
                        Text("${entry.status} · ${readableTime(entry.time)}", style = MaterialTheme.typography.labelSmall,
                            color = if (entry.status in setOf("FAILED", "DENIED", "UNKNOWN")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
                    TextButton(onClick = { selected = if (selected == entry.id) null else entry.id }) { Text(if (selected == entry.id) "收起" else "依据") }
                }
                Text(entry.evidence)
                if (selected == entry.id) SelectableText("调用者 ${entry.caller}\n请求 ${entry.requestId}\n回执 ${entry.id}", code = true)
                HorizontalDivider()
            }
        }
        if (entries.isEmpty()) item { Text("暂无设备动作。模型回答不是自动生成的设备回执。") }
    }
}
@Composable private fun TriggerPanel(graph: AppGraph, notice: (String) -> Unit) {
    val prefs by graph.preferences.state.collectAsStateWithLifecycle()
    val events by graph.triggerInbox.items.collectAsStateWithLifecycle()
    val connected by NoticeListener.connected.collectAsStateWithLifecycle()
    val captureError by NoticeListener.lastError.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val input = LocalInputContext.current
    var packages by rememberSaveable { mutableStateOf(prefs.triggerPackages.joinToString("\n")) }
    var keyword by rememberSaveable { mutableStateOf(prefs.triggerKeyword) }
    var consent by rememberSaveable { mutableStateOf(false) }
    var enableDialog by remember { mutableStateOf(false) }
    var destination by remember { mutableStateOf("未配置模型") }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(prefs.protocol) { runCatching { withContext(Dispatchers.IO) { graph.profiles.read(prefs.protocol)?.let { "${EndpointPolicy.endpoint(it)} · ${it.model}" } } }
        .onSuccess { destination = it ?: "未配置模型" } }
    suspend fun disable() {
        graph.preferences.update { it.copy(triggersEnabled = false, triggerCloudConsent = false) }
        WorkManager.getInstance(context).cancelAllWorkByTag(NoticeListener.WORK_TAG)
        withContext(Dispatchers.IO) { graph.triggerInbox.revokePending() }
    }
    LazyColumn(contentPadding = PaddingValues(LayoutTokens.pagePadding), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            SectionTitle("主动智能：有限通知摘要", "系统访问、来源范围、模型外发是三件不同的授权。只做摘要，不触发设备工具。")
            Text("监听连接：${if (connected) "已连接" else "未连接／未知"}；自动处理：${if (prefs.triggersEnabled) "启用" else "关闭"}")
            TextButton(onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                .onFailure { notice("无法打开系统通知访问设置") } }) { Text("系统通知访问") }
            captureError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            graph.triggerInbox.readError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        item {
            OutlinedTextField(value = packages, onValueChange = { packages = it }, label = { Text("允许的来源包名（每行一个）") }, maxLines = 6,
                modifier = Modifier.fillMaxWidth().onFocusChanged { input.editing = it.isFocused })
            OutlinedTextField(value = keyword, onValueChange = { keyword = it.take(120) }, label = { Text("包含关键词（可留空）") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().onFocusChanged { input.editing = it.isFocused })
            Text("单条最多标题 100、正文 1000 字符；队列最多 30 条，每小时最多十条，串行处理。来源应用的通知可能含敏感信息。", style = MaterialTheme.typography.bodySmall)
            ToggleRow("允许将限定片段交给模型", "目的地：$destination。事件触发后由系统择机执行，不保证即时或常驻。", consent, { consent = it })
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val sources = packages.split(Regex("[\\s,，;；]+" )).filter(String::isNotBlank).toSet()
                    if (sources.isEmpty() || sources.any { !Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+").matches(it) } || !consent)
                        error = "请填写明确包名，并确认限定内容交给已配置模型"
                    else { error = null; enableDialog = true }
                }) { Text("保存并启用") }
                OutlinedButton(onClick = { scope.launch { runCatching { disable() }.onSuccess { notice("已停止新摘要并撤销待处理任务") }.onFailure { notice("停止未全部完成，请检查队列") } } }, enabled = prefs.triggersEnabled) { Text("停止自动处理") }
            }
        }
        item { HorizontalDivider(); SectionTitle("事件收件箱", "已有结果不等于已完成通知所描述的业务。中断、失败不自动重试。") }
        items(events.asReversed(), key = { it.id }) { entry -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(entry.title.ifBlank { entry.packageName }, style = MaterialTheme.typography.titleSmall)
            Text("${entry.packageName} · ${entry.status} · ${readableTime(entry.createdAt)}", style = MaterialTheme.typography.labelSmall)
            if (entry.summary.isNotBlank()) SelectableText(entry.summary)
            TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { graph.triggerInbox.remove(entry.id) } } },
                enabled = entry.status !in setOf("PENDING", "RUNNING")) { Text("删除这条事件") }
            HorizontalDivider()
        } }
    }
    if (enableDialog) AlertDialog(onDismissRequest = { enableDialog = false }, title = { Text("确认主动摘要范围") }, text = {
        Column { SelectableText("来源：\n$packages\n\n模型：$destination")
            Text("每条匹配通知将按已显示的字数上限发送；通知原文不是新的指令。当前版本不执行通知中的命令，不读取聊天数据库，也不自动给外部 Harness 投递任务。") }
    }, confirmButton = { TextButton(onClick = { scope.launch {
        try {
            val route = withContext(Dispatchers.IO) { graph.profiles.read(prefs.protocol) }
            check(route != null)
            disable()
            val sources = packages.split(Regex("[\\s,，;；]+" )).filter(String::isNotBlank).toSet()
            graph.preferences.update { it.copy(triggersEnabled = true, triggerCloudConsent = true, triggerPackages = sources, triggerKeyword = keyword) }
            notice("限定自动摘要已启用；仍需系统监听连接有效")
        } catch (_: Exception) { error = "无法启用，请检查当前模型配置和事件存储" }
        enableDialog = false
    } }) { Text("允许并启用") } }, dismissButton = { TextButton(onClick = { enableDialog = false }) { Text("取消") } })
}
