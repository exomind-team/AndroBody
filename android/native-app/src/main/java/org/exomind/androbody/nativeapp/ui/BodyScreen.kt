package org.exomind.androbody.nativeapp.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.exomind.androbody.nativeapp.AppGraph
import org.exomind.androbody.nativeapp.config.*
import org.exomind.androbody.nativeapp.core.*
import org.exomind.androbody.nativeapp.diagnostics.CapabilityFeasibility
import org.exomind.androbody.nativeapp.diagnostics.EvidenceKind
import org.exomind.androbody.nativeapp.diagnostics.RuntimeLogLevel
import org.exomind.androbody.nativeapp.events.NotificationAccess
import org.exomind.androbody.nativeapp.events.NotificationRecord
import org.exomind.androbody.nativeapp.events.NoticeListener
import org.exomind.androbody.nativeapp.body.AudioRecordingArtifact
import org.exomind.androbody.nativeapp.body.AudioRecordingController
import org.exomind.androbody.nativeapp.body.AudioRecordingStore
import java.util.UUID

@Composable fun BodyScreen(graph: AppGraph, notice: (String) -> Unit, ask: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val overlay by OverlayService.active.collectAsStateWithLifecycle()
    val exchange by graph.documents.state.collectAsStateWithLifecycle()
    val preferences by graph.preferences.state.collectAsStateWithLifecycle()
    val preferencesReady by graph.preferences.initialized.collectAsStateWithLifecycle()
    val notifications by graph.notificationInbox.items.collectAsStateWithLifecycle()
    val notificationListenerConnected by NoticeListener.connected.collectAsStateWithLifecycle()
    val audioState by graph.audioRecording.state.collectAsStateWithLifecycle()
    val audioItems by graph.audioRecordings.items.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var notificationAccessEnabled by remember { mutableStateOf(NotificationAccess.isEnabled(context)) }
    var microphonePermissionGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    var selectedNotification by remember { mutableStateOf<NotificationRecord?>(null) }
    var audioBusy by remember { mutableStateOf(false) }
    var audioDelete by remember { mutableStateOf<AudioRecordingArtifact?>(null) }
    var audioExportId by remember { mutableStateOf<String?>(null) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationAccessEnabled = NotificationAccess.isEnabled(context)
                microphonePermissionGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                if (notificationAccessEnabled) NoticeListener.requestRefreshActive()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var permissionMenuFor by remember { mutableStateOf<String?>(null) }
    var files by remember { mutableStateOf<List<org.json.JSONObject>>(emptyList()) }
    var preview by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var editorPath by remember { mutableStateOf("") }
    var editorText by remember { mutableStateOf("") }
    var editorExpectedSha by remember { mutableStateOf<String?>(null) }
    var deleteFile by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var proof by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    val loadFiles: () -> Unit = { scope.launch {
        runCatching { withContext(Dispatchers.IO) { graph.workspace.list().getJSONArray("files").objects() } }
            .onSuccess { files = it }.onFailure { notice("工作区读取失败，文件未被修改") }
    }; Unit }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val transferId = UUID.randomUUID().toString()
        graph.runtimeLog.record("workspace", if (uri == null) "saf_import_cancelled" else "saf_import_requested",
            transferId, "native", RuntimeLogLevel.INFO, if (uri == null) EvidenceKind.PLATFORM_RESPONSE else EvidenceKind.ACTUAL_ATTEMPT,
            if (uri == null) CapabilityFeasibility.UNKNOWN else CapabilityFeasibility.NOT_ASSESSED,
            mapOf("picker" to "OpenDocument", "mimeScope" to "text/json"))
        scope.launch {
            runCatching { graph.documents.import(uri) }
                .onSuccess { path ->
                    if (path != null) {
                        graph.runtimeLog.record("workspace", "saf_import_finished", transferId, "native",
                            RuntimeLogLevel.INFO, EvidenceKind.RESULT, CapabilityFeasibility.COMPLETED,
                            mapOf("artifactPath" to path, "artifactType" to "UTF-8 text"))
                        notice("已导入副本：$path"); loadFiles()
                    }
                }
                .onFailure { failure ->
                    graph.runtimeLog.record("workspace", "saf_import_failed", transferId, "native",
                        RuntimeLogLevel.ERROR, EvidenceKind.RESULT, CapabilityFeasibility.FAILED,
                        mapOf("errorCode" to (failure as? BodyFailure)?.code.orEmpty(), "exceptionClass" to failure.javaClass.name))
                    notice((failure as? BodyFailure)?.safeDetail ?: "导入失败，未修改原文件")
                }
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val transferId = UUID.randomUUID().toString()
        graph.runtimeLog.record("workspace", if (uri == null) "saf_export_cancelled" else "saf_export_selected",
            transferId, "native", RuntimeLogLevel.INFO,
            if (uri == null) EvidenceKind.PLATFORM_RESPONSE else EvidenceKind.ACTUAL_ATTEMPT,
            if (uri == null) CapabilityFeasibility.UNKNOWN else CapabilityFeasibility.NOT_ASSESSED,
            mapOf("picker" to "CreateDocument", "mimeType" to "text/plain"))
        scope.launch {
            graph.documents.export(uri)
            graph.runtimeLog.record("workspace", "saf_export_state", transferId, "native", RuntimeLogLevel.INFO,
                EvidenceKind.RESULT, CapabilityFeasibility.UNKNOWN,
                mapOf("state" to graph.documents.state.value.take(240), "targetSelected" to (uri != null).toString()))
        }
    }
    val notificationGrant = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        graph.runtimeLog.record("permissions", "post_notifications_permission_result", origin = "native",
            level = if (it) RuntimeLogLevel.INFO else RuntimeLogLevel.WARN, evidenceKind = EvidenceKind.PLATFORM_RESPONSE,
            feasibility = if (it) CapabilityFeasibility.AVAILABLE else CapabilityFeasibility.NEEDS_PERMISSION,
            fields = mapOf("permission" to Manifest.permission.POST_NOTIFICATIONS, "granted" to it.toString()))
        notice(if (it) "通知发布授权已取得" else "通知发布未获授权")
    }
    val microphoneGrant = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        microphonePermissionGranted = it
        graph.runtimeLog.record("permissions", "record_audio_permission_result", origin = "native",
            level = if (it) RuntimeLogLevel.INFO else RuntimeLogLevel.WARN,
            evidenceKind = EvidenceKind.PLATFORM_RESPONSE,
            feasibility = if (it) CapabilityFeasibility.AVAILABLE else CapabilityFeasibility.NEEDS_PERMISSION,
            fields = mapOf("permission" to Manifest.permission.RECORD_AUDIO, "granted" to it.toString()))
        notice(if (it) "麦克风授权已取得" else "麦克风未获授权")
    }
    val audioExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(AudioRecordingStore.MIME_TYPE)) { uri ->
        val artifactId = audioExportId
        audioExportId = null
        if (uri != null && artifactId != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { graph.audioRecordings.copyTo(artifactId, uri) } }
                .onSuccess { bytes -> notice("录音已导出并核对：$bytes bytes") }
                .onFailure { failure -> notice((failure as? BodyFailure)?.safeDetail ?: "录音导出失败；本机原文件保留") }
        }
    }
    LaunchedEffect(Unit) { loadFiles() }
    fun updatePermissionPolicy(change: (AppPreferences) -> AppPreferences) {
        scope.launch {
            runCatching { graph.preferences.update(change) }
                .onFailure { notice("工具权限未保存；原有策略仍生效") }
        }
    }
    LazyColumn(modifier = Modifier.testTag("body-list"), contentPadding = PaddingValues(LayoutTokens.pagePadding),
        verticalArrangement = Arrangement.spacedBy(LayoutTokens.sectionGap)) {
        item {
            SectionTitle("助手工具权限", "一次设置后，后续调用按这里执行；每项仍可单独改为询问或拒绝。")
            NativeApprovalMode.entries.forEach { mode ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    RadioButton(modifier = Modifier.testTag("native-approval-" + mode.name),
                        selected = preferences.nativeApprovalMode == mode, onClick = {
                        updatePermissionPolicy { current ->
                            val choices = if (mode == NativeApprovalMode.ALLOW_ALL)
                                current.nativeCapabilityChoices.filterValues { it != NativeCapabilityChoice.ASK }
                            else current.nativeCapabilityChoices
                            current.copy(nativeApprovalMode = mode, nativeCapabilityChoices = choices)
                        }
                    })
                    Column(Modifier.weight(1f)) {
                        Text(approvalModeTitle(mode), style = MaterialTheme.typography.bodyMedium)
                        Text(approvalModeDescription(mode), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Text("此策略只控制原生助手的工具请求；不会授予 Android 系统权限，也不改变网关共享范围。逐项设置优先于全局默认。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            SectionTitle("Android 能力", "能力已实现不代表当前设备、系统授权或物理效果已验证。")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    if (!working) scope.launch {
                        working = true
                        proof = try { withContext(Dispatchers.IO) { graph.broker.invoke(Invocation(requestId = UUID.randomUUID().toString(), callId = "user-device-check",
                            principal = Principal("native:user", Origin.USER), capability = "am_battery_snapshot", arguments = org.json.JSONObject())).toJson().toString(2) } }
                        catch (_: Exception) { "未取得回执；请查看记录与设备状态" }
                        finally { working = false }
                    }
                }, enabled = !working) { Text("本机电量探测") }
                TextButton(onClick = { ask("采集两秒加速度，保存原始数据到工作区并读回；没有传感器时明确报告。") }) { Text("交给助手采样") }
            }
            proof?.let { SelectableText(it, code = true) }
        }
        items(graph.registry.capabilities, key = { it.name }) { capability ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(capability.title, style = MaterialTheme.typography.titleSmall)
                        Text(capability.name + " · " + capabilityChoiceLabel(
                            capability, preferences.nativeCapabilityChoices[capability.name], preferences.nativeApprovalMode),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { expanded = if (expanded == capability.name) null else capability.name }) { Text(if (expanded == capability.name) "收起" else "详情") }
                }
                TextButton(onClick = { permissionMenuFor = capability.name },
                    modifier = Modifier.testTag("native-capability-" + capability.name)) { Text("修改此项权限") }
                DropdownMenu(expanded = permissionMenuFor == capability.name, onDismissRequest = { permissionMenuFor = null }) {
                    fun choose(value: NativeCapabilityChoice?) {
                        updatePermissionPolicy { current ->
                            val next = current.nativeCapabilityChoices.toMutableMap()
                            if (value == null) next.remove(capability.name) else next[capability.name] = value
                            current.copy(nativeCapabilityChoices = next)
                        }
                        permissionMenuFor = null
                    }
                    DropdownMenuItem(text = { Text("跟随全局默认") }, onClick = { choose(null) })
                    DropdownMenuItem(text = { Text("每次询问") }, onClick = { choose(NativeCapabilityChoice.ASK) })
                    DropdownMenuItem(text = { Text("始终允许") }, onClick = { choose(NativeCapabilityChoice.ALLOW) })
                    DropdownMenuItem(text = { Text("拒绝") }, onClick = { choose(NativeCapabilityChoice.DENY) })
                }
                if (expanded == capability.name) {
                    SelectableText(capability.description)
                    SelectableText(capability.parameters.toString(2), code = true)
                    Text(if (capability.gatewayEligible) "可在网关页单独共享给已认证客户端。" else "当前只供原生模式，网关不能请求。", style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
        }
        item {
            SectionTitle("系统入口")
            ToggleRow("悬浮助手", "点开查看最新状态与回复；可拖动、收起或关闭，只显示本应用会话，不读取其他应用屏幕。", overlay, { enabled ->
                try {
                    if (!enabled) context.stopService(Intent(context, OverlayService::class.java))
                    else if (!Settings.canDrawOverlays(context)) {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                        notice("请在系统授权后返回，再启用悬浮入口")
                    } else context.startForegroundService(Intent(context, OverlayService::class.java))
                } catch (_: Exception) { notice("系统未启动悬浮入口；请检查当前授权与前台状态") }
            }, testTag = "overlay-toggle")
            if (Build.VERSION.SDK_INT >= 33) TextButton(onClick = { notificationGrant.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("申请发布本应用通知") }
        }
        item {
            SectionTitle("录音", "音频保存在本机私有目录；Agent 只得到文件路径、时长和校验摘要，不会自动发送音频给模型。单次最多 60 分钟。")
            Text("麦克风：${if (microphonePermissionGranted) "已授权" else "未授权"} · 录音：${audioState.phaseLabel()}",
                modifier = Modifier.testTag("audio-recording-status"), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            audioState.errorCode?.let { Text("状态待核对：$it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!microphonePermissionGranted) {
                    OutlinedButton(onClick = { microphoneGrant.launch(Manifest.permission.RECORD_AUDIO) },
                        modifier = Modifier.testTag("audio-request-permission")) { Text("授权麦克风") }
                    TextButton(onClick = {
                        runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
                            .onFailure { notice("无法打开应用权限设置") }
                    }) { Text("权限设置") }
                } else if (audioState.phase in setOf("starting", "recording", "stopping", "unknown")) {
                    OutlinedButton(enabled = !audioBusy, onClick = { scope.launch {
                        audioBusy = true
                        runCatching { graph.audioRecording.stop() }
                            .onSuccess { result -> notice(if (result.outcome == Outcome.COMPLETED) "录音已停止并核对文件" else result.evidence) }
                            .onFailure { failure -> notice((failure as? BodyFailure)?.safeDetail ?: "停止请求未确认；请查看录音状态与追踪") }
                        audioBusy = false
                    } }, modifier = Modifier.testTag("audio-record-stop")) {
                        Text(if (audioState.phase == "stopping") "正在停止" else if (audioState.phase == "unknown") "停止并核对" else "停止录音")
                    }
                } else {
                    OutlinedButton(enabled = !audioBusy && preferencesReady && audioState.phase !in setOf("starting", "stopping", "unknown"),
                        onClick = { scope.launch {
                            audioBusy = true
                            runCatching { graph.audioRecording.start(AudioRecordingController.DEFAULT_MAX_DURATION_SECONDS) }
                                .onSuccess { result -> notice(if (result.outcome == Outcome.COMPLETED) "录音已开始；最长 30 分钟" else result.evidence) }
                                .onFailure { failure -> notice((failure as? BodyFailure)?.safeDetail ?: "录音启动失败；请查看权限与运行追踪") }
                            audioBusy = false
                        } }, modifier = Modifier.testTag("audio-record-start")) { Text("开始录音") }
                }
            }
        }
        if (audioItems.isEmpty()) item { Text("尚无录音产物", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(audioItems, key = { it.id }) { artifact ->
            Column(Modifier.fillMaxWidth().testTag("audio-artifact-${artifact.id}"),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(artifact.relativePath, style = MaterialTheme.typography.bodyMedium)
                Text("${artifact.status} · ${artifact.measuredDurationMs} ms · ${artifact.bytes} bytes" +
                    (artifact.sha256?.let { " · SHA-256 ${it.take(12)}" } ?: ""),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (artifact.status == AudioRecordingStore.STATUS_COMPLETE || artifact.sha256 != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (artifact.status == AudioRecordingStore.STATUS_COMPLETE)
                            TextButton(onClick = { audioExportId = artifact.id; audioExport.launch("${artifact.id}.m4a") },
                                modifier = Modifier.testTag("audio-export-${artifact.id}")) { Text("导出") }
                        if (artifact.sha256 != null && artifact.status != AudioRecordingStore.STATUS_RECORDING)
                            TextButton(onClick = { audioDelete = artifact }, modifier = Modifier.testTag("audio-delete-${artifact.id}")) { Text("删除") }
                    }
                }
            }
        }
        item {
            SectionTitle("接收通知", "读取活动通知并本地加密保留最近 100 条；不会自动发送给模型。授权前已撤下的通知无法补读。")
            Text("系统授权：${if (notificationAccessEnabled) "已开启" else "未开启"} · 监听服务：${if (notificationListenerConnected) "已连接" else "未连接"} · 已收录 ${notifications.size} 条",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                    .onFailure { notice("无法打开通知访问设置") } }) { Text(if (notificationAccessEnabled) "管理权限" else "开启读取权限") }
                TextButton(onClick = { scope.launch {
                    notificationAccessEnabled = NotificationAccess.isEnabled(context)
                    if (!notificationAccessEnabled) notice("先在 Android 系统中启用通知访问")
                    else if (!notificationListenerConnected || !NoticeListener.refreshActiveAwait()) notice("系统已授权，但监听尚未连接；稍后刷新")
                    else notice("已刷新活动通知")
                } }, modifier = Modifier.testTag("notification-refresh")) { Text("刷新") }
            }
        }
        if (notifications.isEmpty()) item { Text("尚未收到可查看的通知", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(notifications.takeLast(20).asReversed(), key = { it.id }) { notification ->
            TextButton(onClick = { selectedNotification = notification }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth()) {
                    Text(notification.title.ifBlank { notification.packageName }, style = MaterialTheme.typography.titleSmall)
                    if (notification.body.isNotBlank()) Text(notification.body.take(140), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
                    Text("${notification.packageName} · ${readableTime(notification.postedAt)} · ${if (notification.active) "仍在通知栏" else "已结束"}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            SectionTitle("工作区文件", "新版本私有目录；旧 DSH 数据未删除，也未自动导入。最多显示 100 项。")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    editorPath = "notes/${UUID.randomUUID()}.md"; editorText = ""; editorExpectedSha = null; editorOpen = true
                }, modifier = Modifier.testTag("workspace-create")) { Text("新建文本") }
                OutlinedButton(onClick = { import.launch(arrayOf("text/*", "application/json")) }) { Text("导入文本副本") }
                TextButton(onClick = loadFiles) { Text("刷新") }
            }
            Text(exchange, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(files, key = { it.getString("path") }) { file ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) { Text(file.getString("path")); Text("${file.getLong("bytes")} bytes", style = MaterialTheme.typography.labelSmall) }
                TextButton(onClick = { scope.launch {
                    runCatching { withContext(Dispatchers.IO) { graph.workspace.read(file.getString("path")) } }.onSuccess { preview = it }
                        .onFailure { notice("文件读取失败或超出范围") }
                } }) { Text("打开") }
                TextButton(onClick = { scope.launch {
                    runCatching { withContext(Dispatchers.IO) { graph.workspace.read(file.getString("path")) } }
                        .onSuccess { content ->
                            editorPath = content.getString("path"); editorText = content.getString("text")
                            editorExpectedSha = content.getString("sha256"); editorOpen = true
                        }.onFailure { notice("文件读取失败；原文件未修改") }
                } }, modifier = Modifier.testTag("workspace-edit")) { Text("编辑") }
                TextButton(onClick = { scope.launch {
                    runCatching { graph.documents.prepare(file.getString("path")) }.onSuccess { export.launch(it) }
                        .onFailure { notice("无法准备导出，或已有未完成导出") }
                } }) { Text("导出") }
            }
        }
        item { if (files.isEmpty()) Text("暂无文件。可以导入，或让助手创建经确认的文本产物。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    preview?.let { file -> Dialog(onDismissRequest = { preview = null },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(LayoutTokens.pagePadding),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text(file.getString("path"), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = {
                        editorPath = file.getString("path"); editorText = file.getString("text")
                        editorExpectedSha = file.getString("sha256"); preview = null; editorOpen = true
                    }, modifier = Modifier.testTag("workspace-preview-edit")) { Text("编辑") }
                    TextButton(onClick = { deleteFile = file }, modifier = Modifier.testTag("workspace-preview-delete")) { Text("删除") }
                    TextButton(onClick = { preview = null }) { Text("关闭") }
                }
                HorizontalDivider()
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { SelectableText(file.getString("text")) }
                    item { SelectableText("SHA-256：${file.getString("sha256")}", code = true) }
                }
            }
        }
    } }
    selectedNotification?.let { notification -> AlertDialog(modifier = Modifier.testTag("notification-detail-dialog"),
        onDismissRequest = { selectedNotification = null },
        title = { Text(notification.title.ifBlank { notification.packageName }) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(notification.packageName, style = MaterialTheme.typography.labelSmall)
            SelectableText(notification.body.ifBlank { "（无正文）" })
            Text("${readableTime(notification.postedAt)} · ${if (notification.active) "仍在通知栏" else "已结束"}", style = MaterialTheme.typography.labelSmall)
        } },
        confirmButton = { TextButton(onClick = { selectedNotification = null }) { Text("关闭") } }) }
    audioDelete?.let { artifact -> AlertDialog(onDismissRequest = { audioDelete = null }, title = { Text("删除录音产物？") },
        text = { Text("${artifact.relativePath} 将按当前 SHA-256 核对后从本机删除。") },
        confirmButton = { TextButton(onClick = { scope.launch {
            runCatching { withContext(Dispatchers.IO) { graph.audioRecordings.delete(artifact.id, artifact.sha256.orEmpty()) } }
                .onSuccess { audioDelete = null; notice("录音已删除并核对") }
                .onFailure { notice((it as? BodyFailure)?.safeDetail ?: "录音删除结果未确认") }
        } }, modifier = Modifier.testTag("audio-confirm-delete")) { Text("删除") } },
        dismissButton = { TextButton(onClick = { audioDelete = null }) { Text("取消") } }) }
    if (editorOpen) Dialog(onDismissRequest = { editorOpen = false },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(LayoutTokens.pagePadding),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = editorPath, onValueChange = { editorPath = it.take(240) },
                        label = { Text("工作区相对路径") }, singleLine = true,
                        modifier = Modifier.weight(1f).testTag("workspace-editor-path"))
                    TextButton(onClick = { editorOpen = false }) { Text("取消") }
                }
                Text("仅保存 UTF-8 文本；编辑已有文件时会核对打开时的 SHA-256，冲突时不会覆盖。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(value = editorText, onValueChange = { editorText = it },
                    modifier = Modifier.weight(1f).fillMaxWidth().testTag("workspace-editor"),
                    placeholder = { Text("输入文本…") }, maxLines = Int.MAX_VALUE)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(onClick = { scope.launch {
                        val path = editorPath
                        val text = editorText
                        val expectedSha = editorExpectedSha
                        runCatching {
                            withContext(Dispatchers.IO) { graph.workspace.write(path, text, expectedSha) }
                            val verified = withContext(Dispatchers.IO) { graph.workspace.read(path) }
                            check(verified.getString("text") == text) { "保存后读回不一致" }
                            verified
                        }.onSuccess { verified ->
                            loadFiles(); preview = verified; editorOpen = false; notice("文件已保存并读回核对")
                        }.onFailure { failure -> notice((failure as? BodyFailure)?.safeDetail ?: "保存或读回失败；原文件保留") }
                    } }, modifier = Modifier.testTag("workspace-save")) { Text("保存并核对") }
                }
            }
        }
    }
    deleteFile?.let { file -> AlertDialog(onDismissRequest = { deleteFile = null }, title = { Text("删除工作区文件？") },
        text = { Text("${file.getString("path")} 将按当前 SHA-256 核对后删除。") },
        confirmButton = { TextButton(onClick = { scope.launch {
            runCatching { withContext(Dispatchers.IO) { graph.workspace.delete(file.getString("path"), file.getString("sha256")) } }
                .onSuccess {
                    val remaining = withContext(Dispatchers.IO) { graph.workspace.list().getJSONArray("files").objects() }
                    check(remaining.none { it.optString("path") == file.optString("path") }) { "删除后列表仍包含此文件" }
                    files = remaining; deleteFile = null; preview = null; notice("文件已删除并核对")
                }.onFailure { notice((it as? BodyFailure)?.safeDetail ?: "删除结果未确认；请刷新核对") }
        } }, modifier = Modifier.testTag("workspace-confirm-delete")) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleteFile = null }) { Text("取消") } }) }
}

private fun org.exomind.androbody.nativeapp.body.AudioRecordingRuntimeState.phaseLabel(): String = when (phase) {
    "idle" -> "就绪"
    "starting" -> "正在启动"
    "recording" -> "正在录音"
    "stopping" -> "正在停止"
    "complete" -> "已完成"
    "failed" -> "失败"
    "unknown" -> "待核对"
    else -> phase
}

private fun approvalModeTitle(mode: NativeApprovalMode): String = when (mode) {
    NativeApprovalMode.ASK_SENSITIVE -> "按能力默认询问"
    NativeApprovalMode.ALLOW_READS -> "自动允许只读能力"
    NativeApprovalMode.ALLOW_ALL -> "本机助手允许全部"
}

private fun approvalModeDescription(mode: NativeApprovalMode): String = when (mode) {
    NativeApprovalMode.ASK_SENSITIVE -> "需要逐次确认的能力保持询问；其他能力沿用默认规则。"
    NativeApprovalMode.ALLOW_READS -> "只读能力免除额外确认；其他能力沿用逐项策略和原有确认要求。"
    NativeApprovalMode.ALLOW_ALL -> "清除逐项询问例外，保留逐项允许/拒绝；Android 系统授权仍单独控制。"
}

private fun capabilityChoiceLabel(
    capability: Capability,
    choice: NativeCapabilityChoice?,
    globalMode: NativeApprovalMode,
): String = when (choice) {
    NativeCapabilityChoice.ASK -> "每次询问"
    NativeCapabilityChoice.ALLOW -> "始终允许"
    NativeCapabilityChoice.DENY -> "拒绝"
    null -> when (globalMode) {
        NativeApprovalMode.ASK_SENSITIVE -> if (capability.needsConfirmation) "跟随全局 · 询问" else "跟随全局 · 无需询问"
        NativeApprovalMode.ALLOW_READS -> if (capability.effect in setOf(Effect.READ_DEVICE, Effect.READ_WORKSPACE))
            "跟随全局 · 自动允许只读" else if (capability.needsConfirmation) "跟随全局 · 询问" else "跟随全局 · 无需询问"
        NativeApprovalMode.ALLOW_ALL -> "跟随全局 · 允许全部"
    }
}
