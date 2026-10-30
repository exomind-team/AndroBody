package org.exomind.androbody.nativeapp.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.exomind.androbody.nativeapp.AppGraph
import org.exomind.androbody.nativeapp.config.ComposerSendMode
import org.exomind.androbody.nativeapp.runtime.NativeState

internal fun canSubmitAssistantMessage(state: NativeState, prompt: String): Boolean =
    prompt.isNotBlank() && !state.running && !state.initializing && !state.sessionArchived

@OptIn(ExperimentalFoundationApi::class)
@Composable fun AssistantScreen(graph: AppGraph, draft: TextFieldValue, onDraft: (TextFieldValue) -> Unit,
    notice: (String) -> Unit, openSettings: () -> Unit) {
    val state by graph.native.state.collectAsStateWithLifecycle()
    val preferences by graph.preferences.state.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    var follow by rememberSaveable { mutableStateOf(true) }
    var clear by remember { mutableStateOf(false) }
    var showSessions by remember { mutableStateOf(false) }
    var showContextBudget by remember { mutableStateOf(false) }
    var renameSession by remember { mutableStateOf<org.exomind.androbody.nativeapp.runtime.NativeSessionInfo?>(null) }
    var deleteSession by remember { mutableStateOf<org.exomind.androbody.nativeapp.runtime.NativeSessionInfo?>(null) }
    var sessionTitleDraft by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val inputContext = LocalInputContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val hasHardwareKeyboard = remember(LocalConfiguration.current) { hasExternalHardwareKeyboard() }
    var requestMenuEntry by remember { mutableStateOf<String?>(null) }
    val send: () -> Unit = {
        if (canSubmitAssistantMessage(state, draft.text)) {
            graph.native.send(draft.text)
            onDraft(TextFieldValue()); follow = true
        }
    }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress to (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) }
            .distinctUntilChanged().collect { (scrolling, last) ->
                if (scrolling) follow = last >= list.layoutInfo.totalItemsCount - 1
            }
    }
    LaunchedEffect(state.entries.size) { if (follow && state.entries.isNotEmpty()) list.scrollToItem(state.entries.lastIndex) }
    LaunchedEffect(state.liveReply, state.phase) {
        if (follow && state.liveReply != null) {
            val last = if (state.entries.isEmpty()) 0 else state.entries.size
            list.scrollToItem(last)
        }
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = LayoutTokens.pagePadding, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(state.sessionTitle + if (state.sessionArchived) " · 已归档" else "", style = MaterialTheme.typography.titleSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(state.phase, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { showContextBudget = true }, modifier = Modifier.testTag("context-budget-open")) {
                    Icon(Icons.Default.Info, contentDescription = "查看上下文用量")
                    Spacer(Modifier.width(4.dp))
                    Text("${state.contextMessages} 条", style = MaterialTheme.typography.labelSmall)
                }
            }
            if (state.initializing) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { showSessions = true }, enabled = !state.initializing,
                    modifier = Modifier.testTag("session-manager-open")) { Text("会话") }
                TextButton(onClick = { graph.native.newSession() }, enabled = !state.running && !state.initializing) { Text("新会话") }
                TextButton(onClick = { clear = true }, enabled = !state.running && !state.initializing && state.entries.isNotEmpty()) { Text("清空") }
            }
        }
        if (state.entries.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(LayoutTokens.pagePadding),
                verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                Icon(Icons.Default.Home, contentDescription = null, modifier = Modifier.size(52.dp),
                    tint = MaterialTheme.colorScheme.primary)
                Text("本机助手", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onDraft(TextFieldValue("读取这部手机的电池与网络状态，只依据真实回执回答。")) }) {
                        Icon(Icons.Default.Info, contentDescription = null)
                        Spacer(Modifier.width(8.dp)); Text("设备状态")
                    }
                    OutlinedButton(onClick = { onDraft(TextFieldValue("列出你真正能调用的 Android 能力，说明权限与未知项。")) }) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = null)
                        Spacer(Modifier.width(8.dp)); Text("能力清单")
                    }
                }
                TextButton(onClick = openSettings) {
                    Icon(Icons.Default.Settings, contentDescription = null)
                    Spacer(Modifier.width(8.dp)); Text("模型连接")
                }
            }
        } else LazyColumn(state = list, modifier = Modifier.weight(1f), contentPadding = PaddingValues(LayoutTokens.pagePadding), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            items(state.entries, key = { it.id }) { entry ->
                val hasRequestDetails = entry.role == "assistant" && entry.requestId != null
                val messageActions = if (hasRequestDetails) Modifier
                    .combinedClickable(onClick = {}, onLongClick = { requestMenuEntry = entry.id })
                    .semantics {
                        customActions = listOf(
                            CustomAccessibilityAction("查看请求依据") {
                                notice("请求标识：${entry.requestId}；在记录页可核对工具回执")
                                true
                            },
                            CustomAccessibilityAction("复制回复") {
                                clipboard.setText(androidx.compose.ui.text.AnnotatedString(entry.text))
                                notice("已复制回复")
                                true
                            },
                        )
                    }
                else Modifier
                Box {
                    Column(messageActions, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${when (entry.role) { "user" -> "你"; "recovery" -> "只读核对"; else -> "助手" }} · ${readableTime(entry.at)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (entry.role == "user") Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                            Box(Modifier.padding(14.dp)) { SelectableText(entry.text) }
                        } else MarkdownText(entry.text, Modifier.fillMaxWidth())
                    }
                    if (hasRequestDetails) DropdownMenu(
                        expanded = requestMenuEntry == entry.id,
                        onDismissRequest = { requestMenuEntry = null },
                    ) {
                        DropdownMenuItem(text = { Text("请求依据") }, onClick = {
                            requestMenuEntry = null
                            notice("请求标识：${entry.requestId}；在记录页可核对工具回执")
                        })
                        DropdownMenuItem(text = { Text("复制回复") }, onClick = {
                            requestMenuEntry = null
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(entry.text))
                            notice("已复制回复")
                        })
                    }
                }
            }
            if (state.liveReply != null) item(key = "live-reply") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (state.running) "助手 · 正在生成" else "助手 · 部分回复",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.liveReply.isNullOrBlank()) {
                        if (state.running) LinearProgressIndicator(Modifier.fillMaxWidth())
                        else Text("没有可保留的部分文本。", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        MarkdownText(state.liveReply.orEmpty(), Modifier.fillMaxWidth())
                        if (!state.running) Text("这段输出未完成，也没有加入下一轮模型上下文。",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    if (!follow && state.entries.isNotEmpty()) TextButton(onClick = {
        follow = true
        scope.launch { list.scrollToItem(if (state.liveReply != null) state.entries.size else state.entries.lastIndex) }
    }, modifier = Modifier.fillMaxWidth()) { Text("回到最新内容") }
        state.error?.let { error ->
            Row(Modifier.fillMaxWidth().padding(horizontal = LayoutTokens.pagePadding), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
            }
        }
        if (state.interrupted) Row(Modifier.fillMaxWidth().padding(horizontal = LayoutTokens.pagePadding),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("待核对期间仍可发消息；发送会先限制为只读回执检查。", modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            if (!state.recoveryReviewed) TextButton(onClick = { graph.native.recoverInterrupted() }, enabled = !state.running) { Text("只读核对") }
            else TextButton(onClick = { graph.native.acknowledgeRecovery() }, enabled = !state.running) { Text("已查看，继续本会话") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = draft, onValueChange = onDraft, label = { Text("给原生助手的任务") }, maxLines = 6,
                modifier = Modifier.weight(1f).testTag("assistant-composer").onFocusChanged { inputContext.editing = it.isFocused }
                    .then(if (hasHardwareKeyboard) Modifier.submitOnShortcut(draft, preferences.composerSendMode, send) else Modifier),
                supportingText = if (hasHardwareKeyboard) ({
                    Text(if (preferences.composerSendMode == ComposerSendMode.ENTER) "Enter 发送 · Shift+Enter 换行"
                        else "Ctrl+Enter 发送 · Enter 换行")
                }) else null)
            if (state.running) OutlinedButton(onClick = { graph.native.cancel() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("停止") }
            else IconButton(onClick = send, enabled = canSubmitAssistantMessage(state, draft.text), modifier = Modifier.size(56.dp).testTag("assistant-send")) {
                Icon(Icons.AutoMirrored.Filled.Send, if (state.interrupted) "发送只读核对消息" else "发送任务")
            }
        }
    }
    if (showContextBudget) ContextBudgetDialog(state.contextBudget) { showContextBudget = false }
    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("清空会话上下文") },
        text = { Text("将清空当前会话的消息与模型上下文；其他会话、操作回执和运行日志保留。不会撤销已发生的设备动作。") },
        confirmButton = { TextButton(onClick = { graph.native.clearContext(); clear = false }) { Text("确认清空") } },
        dismissButton = { TextButton(onClick = { clear = false }) { Text("取消") } })
    if (showSessions) androidx.compose.ui.window.Dialog(onDismissRequest = { showSessions = false }) {
        Surface(Modifier.fillMaxWidth().heightIn(max = 640.dp), shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("会话", style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { graph.native.newSession(); showSessions = false },
                        enabled = !state.running && !state.initializing,
                        modifier = Modifier.testTag("session-create")) { Text("新建") }
                }
                androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.sessions, key = { it.id }) { session ->
                        Surface(shape = MaterialTheme.shapes.medium,
                            color = if (session.id == state.currentSessionId) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
                            Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(session.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    TextButton(onClick = { graph.native.switchSession(session.id); showSessions = false },
                                        enabled = !state.running && !state.initializing) { Text(if (session.id == state.currentSessionId) "当前" else "打开") }
                                }
                                Text(buildString {
                                    append(readableTime(session.updatedAt))
                                    if (session.archived) append(" · 已归档")
                                    val interrupted = session.id == state.currentSessionId && state.interrupted
                                    if (interrupted) append(" · 待核对")
                                }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    TextButton(onClick = { renameSession = session; sessionTitleDraft = session.title },
                                        enabled = !state.running && !state.initializing) { Text("改名") }
                                    TextButton(onClick = { graph.native.forkSession(session.id); showSessions = false },
                                        enabled = !state.running && !state.initializing) { Text("分支") }
                                    TextButton(onClick = { graph.native.archiveSession(session.id, !session.archived) },
                                        enabled = !state.running && !state.initializing) { Text(if (session.archived) "取消归档" else "归档") }
                                    TextButton(onClick = { deleteSession = session },
                                        enabled = !state.running && !state.initializing && state.sessions.size > 1) { Text("删除") }
                                }
                            }
                        }
                    }
                }
                TextButton(onClick = { showSessions = false }, modifier = Modifier.align(androidx.compose.ui.Alignment.End)) { Text("关闭") }
            }
        }
    }
    renameSession?.let { target -> AlertDialog(onDismissRequest = { renameSession = null }, title = { Text("重命名会话") },
        text = { OutlinedTextField(value = sessionTitleDraft, onValueChange = { sessionTitleDraft = it.take(80) }, singleLine = true,
            modifier = Modifier.testTag("session-title-input")) },
        confirmButton = { TextButton(onClick = { graph.native.renameSession(target.id, sessionTitleDraft); renameSession = null },
            modifier = Modifier.testTag("session-rename-save")) { Text("保存") } },
        dismissButton = { TextButton(onClick = { renameSession = null }) { Text("取消") } }) }
    deleteSession?.let { target -> AlertDialog(onDismissRequest = { deleteSession = null }, title = { Text("删除会话？") },
        text = { Text("“${target.title}”的加密本地记录将被删除。其他会话与运行回执不受影响。") },
        confirmButton = { TextButton(onClick = { graph.native.deleteSession(target.id); deleteSession = null }) { Text("删除会话") } },
        dismissButton = { TextButton(onClick = { deleteSession = null }) { Text("取消") } }) }
}
