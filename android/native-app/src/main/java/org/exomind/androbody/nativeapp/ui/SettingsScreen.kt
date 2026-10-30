package org.exomind.androbody.nativeapp.ui

import android.content.ClipboardManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkManager
import kotlinx.coroutines.*
import org.exomind.androbody.nativeapp.AppGraph
import org.exomind.androbody.nativeapp.config.*
import org.exomind.androbody.nativeapp.events.NoticeListener

@Composable fun SettingsScreen(graph: AppGraph, drafts: Map<ModelProtocol, ModelDraft>, notice: (String) -> Unit) {
    val preferences by graph.preferences.state.collectAsStateWithLifecycle()
    val native by graph.native.state.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableStateOf(preferences.protocol.name) }
    val protocol = ModelProtocol.valueOf(selected)
    val draft = drafts.getValue(protocol)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val input = LocalInputContext.current
    var showKey by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var importHint by remember { mutableStateOf<ImportProposal?>(null) }
    var confirm by remember { mutableStateOf<ModelSettings?>(null) }
    var saving by remember { mutableStateOf(false) }
    var copyHash by remember { mutableIntStateOf(0) }
    LaunchedEffect(protocol) {
        if (!draft.loaded) {
            runCatching { withContext(Dispatchers.IO) { graph.profiles.read(protocol) } }.onSuccess { draft.load(it) }
                .onFailure { draft.loaded = true; draft.error = "已保存配置无法读取，未覆盖原件" }
        }
    }
    // Only this foreground screen, and only after explicit opt-in. No background clipboard service.
    DisposableEffect(preferences.clipboardImport, lifecycle) {
        val manager = context.getSystemService(ClipboardManager::class.java)
        fun inspectClipboard() {
            if (!preferences.clipboardImport || !graph.uiVisible || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
            val clip = manager.primaryClip ?: return
            val text = if (clip.itemCount > 0) clip.getItemAt(0).text?.toString() else null
            if (text == null || text.length > ConfigImport.MAX_INPUT || text.hashCode() == copyHash) return
            copyHash = text.hashCode()
            val proposal = runCatching { ConfigImport.parse(text) }.getOrNull() ?: return
            if (proposal.endpoints.isNotEmpty() || proposal.keys.isNotEmpty()) importHint = proposal
        }
        val listener = ClipboardManager.OnPrimaryClipChangedListener { inspectClipboard() }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) inspectClipboard() }
        if (preferences.clipboardImport) { manager.addPrimaryClipChangedListener(listener); lifecycle.addObserver(observer); inspectClipboard() }
        onDispose { manager.removePrimaryClipChangedListener(listener); lifecycle.removeObserver(observer); input.editing = false }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(LayoutTokens.pagePadding), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        SectionTitle("模型连接", "只保存你明确配置的上游。保存不会发送探测请求，网关令牌与上游密钥分开。")
        ModelProtocol.entries.forEach { candidate ->
            Row { RadioButton(selected = protocol == candidate, onClick = { selected = candidate.name });
                TextButton(onClick = { selected = candidate.name }) { Text(candidate.label) } }
        }
        if (importHint != null) InfoPanel("剪贴板中发现配置", "仅本地识别，尚未应用。点击下方“检查识别结果”确认。")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { importing = true }) { Text(if (importHint == null) "智能导入" else "检查识别结果") }
            if (importHint != null) TextButton(onClick = { importHint = null }) { Text("忽略") }
        }
        OutlinedTextField(enabled = draft.loaded && !saving, value = draft.baseUrl, onValueChange = { draft.baseUrl = it }, label = { Text("API 基础地址或完整端点") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("model-address").onFocusChanged { input.editing = it.isFocused }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        OutlinedTextField(enabled = draft.loaded && !saving, value = draft.key, onValueChange = { draft.key = it }, label = { Text(if (draft.hasStoredKey) "API Key（留空保留已保存密钥）" else "API Key") },
            modifier = Modifier.fillMaxWidth().testTag("model-key").onFocusChanged { input.editing = it.isFocused }, singleLine = true,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "隐藏" else "显示") } })
        OutlinedTextField(enabled = draft.loaded && !saving, value = draft.model, onValueChange = { draft.model = it }, label = { Text("模型标识") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("model-name").onFocusChanged { input.editing = it.isFocused })
        OutlinedTextField(enabled = draft.loaded && !saving, value = draft.maxTokens, onValueChange = { draft.maxTokens = it.filter(Char::isDigit).take(5) }, label = { Text("单次输出上限（留空连续生成）") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth().onFocusChanged { input.editing = it.isFocused })
        Text("默认不设用户输出上限。达到上游单次上限时会自动续写；总过程仍受上下文、轮数、网络超时与上游限制。填写 64–8192 可改为每段上限。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ToggleRow("允许本地 HTTP 上游", "仅限显式私网或回环地址。凭据在 HTTP 链路上不加密；优先 HTTPS。", draft.localHttp, { draft.localHttp = it })
        ModelConnectionPanel(graph, protocol, draft, drafts, selectProtocol = { selected = it.name },
            enabled = draft.loaded && !native.running && !saving)
        draft.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = {
            scope.launch {
                try {
                    val old = withContext(Dispatchers.IO) { graph.profiles.read(protocol) }
                    val value = EndpointPolicy.submission(protocol = protocol, baseUrl = draft.baseUrl,
                        inputKey = draft.key, model = draft.model, maxTokens = draft.maxTokens.toIntOrNull() ?: 0,
                        allowInsecureLocal = draft.localHttp, endpointMode = draft.endpointMode, saved = old)
                    confirm = value; draft.error = null
                } catch (failure: Exception) {
                    draft.error = (failure as? IllegalArgumentException)?.message
                        ?: "请核对完整 URL、密钥、模型名与可选输出上限；本地 HTTP 需明确启用"
                }
            }
        }, enabled = draft.loaded && !native.running && !saving, modifier = Modifier.fillMaxWidth().testTag("model-save")) { Text("检查并保存连接") }
        if (native.running) Text("原生任务进行中。先停止并核对结果，再改变模型目的地。", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        SectionTitle("输入与显示")
        Text("外接键盘发送方式", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SendModeOption("Ctrl+Enter", preferences.composerSendMode == ComposerSendMode.CTRL_ENTER,
                Modifier.weight(1f), testTag = "send-mode-ctrl-enter") {
                scope.launch { graph.preferences.update { it.copy(composerSendMode = ComposerSendMode.CTRL_ENTER) } }
            }
            SendModeOption("Enter", preferences.composerSendMode == ComposerSendMode.ENTER,
                Modifier.weight(1f), testTag = "send-mode-enter") {
                scope.launch { graph.preferences.update { it.copy(composerSendMode = ComposerSendMode.ENTER) } }
            }
        }
        ToggleRow("前台剪贴板识别", "只在此配置页处于前台时读取纯文本；不会后台监听、自动保存或联网。", preferences.clipboardImport,
            { enabled -> scope.launch { graph.preferences.update { it.copy(clipboardImport = enabled) } } })
        listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (value, label) -> Row {
            RadioButton(selected = preferences.theme == value, onClick = { scope.launch { graph.preferences.update { it.copy(theme = value) } } })
            TextButton(onClick = { scope.launch { graph.preferences.update { it.copy(theme = value) } } }) { Text(label) }
        } }
        InfoPanel("数据与兼容边界", "本版 Kotlin 原生，不解包 DSH／Node／PRoot。旧数据仍留在原目录，新上下文不自动导入。网关仅监听本机，支持 Messages 与 Chat Completions 子集；没有任意插件安装、Root、无障碍或 MCP 服务。")
    }
    if (importing) ConfigImportDialog(importHint, protocol, { importing = false }) { selectedProtocol, url, key, model ->
        val target = drafts.getValue(selectedProtocol)
        scope.launch {
            if (!target.loaded) runCatching { withContext(Dispatchers.IO) { graph.profiles.read(selectedProtocol) } }.onSuccess { target.load(it) }
            url?.let { target.baseUrl = it }; key?.let { target.key = it }; model?.let { target.model = it }
            target.loaded = true; selected = selectedProtocol.name; importing = false; importHint = null
            notice("已应用到草稿；请核对目的地并保存")
        }
    }

    confirm?.let { value -> AlertDialog(onDismissRequest = { if (!saving) confirm = null }, title = { Text("确认模型目的地") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectableText("候选路径（保存不发请求）：\n${EndpointPolicy.plan(value).candidates.joinToString("\n") { it.url }}\n${value.model}\n${value.protocol.label}")
            Text("你发起的模型任务会把相关输入和工具结果交给此上游。保存会停止现有网关、关闭通知自动摘要，并开启新的原生上下文；旧阅读记录不删除。不会发送连通性测试。")
        }
    }, confirmButton = { TextButton(onClick = {
        if (!saving) { saving = true; scope.launch {
            try {
                graph.setGateway(false)
                WorkManager.getInstance(context).cancelAllWorkByTag(NoticeListener.WORK_TAG)
                withContext(Dispatchers.IO) { graph.triggerInbox.revokePending(); graph.profiles.save(value) }
                graph.preferences.update { it.copy(protocol = value.protocol, triggersEnabled = false, triggerCloudConsent = false) }
                graph.native.freshContext()
                draft.key = ""; draft.hasStoredKey = true; confirm = null
                notice("配置已保存；保存操作未发送请求。按需重新启动网关或授权通知摘要。")
            } catch (_: Exception) { draft.error = "保存未全部完成，请核对当前配置；未发送测试请求" }
            finally { saving = false }
        } }
    }, enabled = !saving) { Text(if (saving) "正在保存" else "保存") } },
        dismissButton = { TextButton(onClick = { confirm = null }, enabled = !saving) { Text("取消") } }) }
}

@Composable private fun SendModeOption(label: String, selected: Boolean, modifier: Modifier = Modifier,
    testTag: String? = null, onClick: () -> Unit) {
    Row(modifier.selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
        .then(if (testTag == null) Modifier else Modifier.testTag(testTag)),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
