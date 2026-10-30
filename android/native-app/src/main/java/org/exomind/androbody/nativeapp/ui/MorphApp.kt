package org.exomind.androbody.nativeapp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.exomind.androbody.nativeapp.AppGraph
import org.exomind.androbody.nativeapp.BuildConfig
import org.exomind.androbody.nativeapp.config.NativeApprovalMode
import org.exomind.androbody.nativeapp.config.NativeCapabilityChoice
import org.exomind.androbody.nativeapp.core.Origin
import org.exomind.androbody.nativeapp.config.ModelProtocol

private enum class Page(val label: String, val icon: ImageVector) {
    ASSISTANT("助手", Icons.Default.Home), BODY("身体", Icons.Default.Info), GATEWAY("网关", Icons.Default.Share),
    RECORDS("记录", Icons.AutoMirrored.Filled.List), SETTINGS("设置", Icons.Default.Settings)
}
@Composable fun MorphApp(graph: AppGraph, incoming: String, acknowledgeShare: () -> Unit) {
    val input = remember { InputContext() }
    CompositionLocalProvider(LocalInputContext provides input) { MorphContent(graph, incoming, acknowledgeShare) }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun MorphContent(graph: AppGraph, incoming: String, acknowledgeShare: () -> Unit) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    var commandPanel by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    var draft by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    val modelDrafts = remember { ModelProtocol.entries.associateWith { ModelDraft() } }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val approvals by graph.approvals.pending.collectAsStateWithLifecycle()
    val native by graph.native.state.collectAsStateWithLifecycle()
    val preferences by graph.preferences.state.collectAsStateWithLifecycle()
    val preferencesReady by graph.preferences.initialized.collectAsStateWithLifecycle()
    val preferencesLoadError by graph.preferences.loadError.collectAsStateWithLifecycle()
    val savedPages = rememberSaveableStateHolder()
    val editContext = LocalInputContext.current
    val hasHardwareKeyboard = remember(LocalConfiguration.current) { hasExternalHardwareKeyboard() }
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    LaunchedEffect(selected) { editContext.editing = false }
    val notice: (String) -> Unit = { message -> scope.launch { snack.showSnackbar(message) }; Unit }
    LaunchedEffect(incoming) {
        if (incoming.isNotBlank()) {
            draft = TextFieldValue(if (draft.text.isBlank()) incoming else draft.text + "\n\n" + incoming)
            selected = Page.ASSISTANT.ordinal; acknowledgeShare()
            snack.showSnackbar("分享内容已加入草稿，尚未发送给模型")
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyUp || editContext.editing) false
        else when {
            event.key == Key.F1 && !event.isCtrlPressed && !event.isAltPressed -> { help = true; true }
            event.isCtrlPressed && !event.isAltPressed && event.key == Key.K -> { commandPanel = true; true }
            event.isCtrlPressed && !event.isAltPressed && !event.isShiftPressed && event.key in listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five) -> {
                selected = listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five).indexOf(event.key); true
            }
            else -> false
        }
    }) {
        val wide = maxWidth >= LayoutTokens.railBreakpoint
        Scaffold(topBar = {
            Column {
            TopAppBar(title = {
                Column { Text("AndroBody", style = MaterialTheme.typography.titleLarge)
                    Text("${Page.entries[selected].label} · ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }, actions = {
                IconButton(onClick = { commandPanel = true }) { Icon(Icons.Default.Menu, contentDescription = "打开操作面板") }
            })
            if (!preferencesReady) {
                if (preferencesLoadError == null) {
                    Text("正在读取本机设置", modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LinearProgressIndicator(Modifier.fillMaxWidth().testTag("preferences-loading"))
                } else Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("本机设置读取失败 · $preferencesLoadError", modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { graph.preferences.retryLoad() }, modifier = Modifier.testTag("preferences-retry")) {
                        Text("重试")
                    }
                }
            }
            }
        }, bottomBar = {
            if (!wide && !imeVisible) NavigationBar { Page.entries.forEachIndexed { index, page ->
                NavigationBarItem(modifier = Modifier.testTag("nav-${page.name}"), selected = index == selected, onClick = { selected = index }, icon = { Icon(page.icon, page.label) }, label = { Text(page.label) })
            } }
        }, snackbarHost = { SnackbarHost(snack) }) { padding ->
            Row(Modifier.padding(padding).fillMaxSize()) {
                if (wide) NavigationRail { Page.entries.forEachIndexed { index, page ->
                    NavigationRailItem(modifier = Modifier.testTag("nav-${page.name}"), selected = index == selected, onClick = { selected = index }, icon = { Icon(page.icon, page.label) }, label = { Text(page.label) })
                } }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    savedPages.SaveableStateProvider(selected) {
                        when (Page.entries[selected]) {
                            Page.ASSISTANT -> AssistantScreen(graph, draft, { draft = it }, notice) { selected = Page.SETTINGS.ordinal }
                            Page.BODY -> BodyScreen(graph, notice) { prompt -> draft = TextFieldValue(prompt); selected = Page.ASSISTANT.ordinal }
                            Page.GATEWAY -> GatewayScreen(graph, notice)
                            Page.RECORDS -> RecordsScreen(graph, notice)
                            Page.SETTINGS -> SettingsScreen(graph, modelDrafts, notice)
                        }
                    }
                }
            }
        }
    }
    if (commandPanel) AlertDialog(onDismissRequest = { commandPanel = false }, title = { Text("操作面板") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Page.entries.forEachIndexed { index, page -> TextButton(onClick = { selected = index; commandPanel = false }, modifier = Modifier.fillMaxWidth()) { Text("${page.label}   Ctrl+${index + 1}") } }
            HorizontalDivider()
            TextButton(onClick = { help = true; commandPanel = false }) { Text("键盘与可访问性帮助") }
            TextButton(onClick = { graph.native.cancel(); commandPanel = false }, enabled = native.running) { Text("停止当前原生任务") }
        } }, confirmButton = { TextButton(onClick = { commandPanel = false }) { Text("关闭") } })
    if (help) AlertDialog(onDismissRequest = { help = false }, title = { Text(if (hasHardwareKeyboard) "键盘操作" else "输入与辅助") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (hasHardwareKeyboard) {
                val sendHint = if (preferences.composerSendMode == org.exomind.androbody.nativeapp.config.ComposerSendMode.ENTER)
                    "Enter：发送；Shift+Enter：换行" else "Ctrl+Enter：发送；Enter：换行"
                SelectableText("$sendHint\nTab / Shift+Tab：移动焦点\nEnter / Space：激活聚焦按钮\nCtrl+1…5：非编辑状态下切换主页面\nCtrl+K：非编辑状态下打开操作面板\nF1：本帮助\nEsc / 系统返回：关闭当前弹层\n\n中文输入法组合中的回车不提交。粘贴、选择、撤销沿用系统输入行为。")
            } else SelectableText("使用发送按钮提交任务；换行、候选、粘贴和撤销沿用系统输入法。系统返回可关闭当前弹层。悬浮入口可通过通知或应用图标唤起。")
        }
    }, confirmButton = { TextButton(onClick = { help = false }) { Text("关闭") } })
    approvals.firstOrNull()?.let { pending ->
        AlertDialog(onDismissRequest = { graph.approvals.answer(pending.invocation.id, false) },
            title = { Text("确认：${pending.capability.title}") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("仅本次调用有效，超时自动拒绝。取消不会撤销已经发生的设备效果。")
                if (pending.invocation.principal.origin == Origin.NATIVE)
                    Text("仅作用于本机助手。允许全部会清除逐项询问例外，保留逐项拒绝；Android 系统授权与网关共享范围仍单独控制。")
                if (pending.capability.name == "am_tts_speak") Text("选定系统 TTS 引擎可能联网；此许可也允许将下列文字交给该引擎。")
                Text("请求方：${pending.invocation.principal.id}")
                SelectableText(pending.invocation.arguments.toString(2), code = true)
            } },
            confirmButton = { Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                Button(onClick = { graph.approvals.answer(pending.invocation.id, true) }) { Text("本次允许") }
                if (pending.invocation.principal.origin == Origin.NATIVE) {
                    TextButton(onClick = {
                        scope.launch {
                            runCatching {
                                graph.preferences.update { current ->
                                    current.copy(nativeCapabilityChoices =
                                        current.nativeCapabilityChoices + (pending.capability.name to NativeCapabilityChoice.ALLOW))
                                }
                            }.onSuccess { graph.approvals.answer(pending.invocation.id, true) }
                                .onFailure { graph.approvals.answer(pending.invocation.id, false); notice("权限设置未保存；本次调用已拒绝") }
                        }
                    }) { Text("始终允许此能力") }
                    TextButton(onClick = {
                        scope.launch {
                            runCatching {
                                graph.preferences.update { current ->
                                    current.copy(nativeApprovalMode = NativeApprovalMode.ALLOW_ALL,
                                        nativeCapabilityChoices = current.nativeCapabilityChoices.filterValues {
                                            it != NativeCapabilityChoice.ASK
                                        })
                                }
                            }.onSuccess { graph.approvals.answer(pending.invocation.id, true) }
                                .onFailure { graph.approvals.answer(pending.invocation.id, false); notice("权限设置未保存；本次调用已拒绝") }
                        }
                    }) { Text("允许全部本机工具") }
                }
                TextButton(onClick = { graph.approvals.answer(pending.invocation.id, false) }) { Text("拒绝") }
            } },
            dismissButton = { })
    }
}
