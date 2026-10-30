package org.exomind.androbody.nativeapp

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import android.view.Menu
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import org.exomind.androbody.nativeapp.config.ComposerSendMode
import org.exomind.androbody.nativeapp.diagnostics.EvidenceKind
import org.exomind.androbody.nativeapp.diagnostics.RuntimeLogLevel
import org.exomind.androbody.nativeapp.migration.LegacyApplication
import org.exomind.androbody.nativeapp.migration.PackageMigrationSummary
import org.exomind.androbody.nativeapp.ui.MorphApp
import org.exomind.androbody.nativeapp.ui.MorphTheme

class MainActivity : ComponentActivity() {
    private val sharedText = MutableStateFlow("")
    private var attachedGraph: AppGraph? = null
    private var graphVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        acceptShare(intent)
        val app = application as MorphApplication
        val restoredState = savedInstanceState != null
        setContent {
            val packageMigration by app.packageMigration.collectAsStateWithLifecycle()
            val initialization by app.graphInitialization.collectAsStateWithLifecycle()
            val incoming by sharedText.collectAsStateWithLifecycle()
            when (val migration = packageMigration) {
                is PackageMigrationStartup.Available -> MorphTheme("system") {
                    PackageMigrationScreen(migration.source, importing = false, errorCode = null,
                        onImport = { app.importLegacyData(migration.source) },
                        onSkip = { app.skipLegacyData(migration.source) })
                }
                is PackageMigrationStartup.Importing -> MorphTheme("system") {
                    PackageMigrationScreen(migration.source, importing = true, errorCode = null, onImport = {}, onSkip = {})
                }
                is PackageMigrationStartup.Failed -> MorphTheme("system") {
                    PackageMigrationScreen(migration.source, importing = false, errorCode = migration.errorCode,
                        onImport = { app.importLegacyData(migration.source) },
                        onSkip = { app.skipLegacyData(migration.source) })
                }
                is PackageMigrationStartup.Completed -> MorphTheme("system") {
                    PackageMigrationCompleteScreen(migration.summary, migration.errorCode) {
                        app.continueAfterMigration(migration.summary)
                    }
                }
                PackageMigrationStartup.NotRequired -> when (val current = initialization) {
                    AppGraphInitialization.Loading -> MorphTheme("system") { GraphLoadingScreen() }
                    is AppGraphInitialization.Failed -> MorphTheme("system") {
                        GraphFailedScreen(current.errorCode, app::retryGraphInitialization)
                    }
                    is AppGraphInitialization.Ready -> {
                        val graph = current.graph
                        val preferences by graph.preferences.state.collectAsStateWithLifecycle()
                        SideEffect { attachGraph(graph, restoredState) }
                        LaunchedEffect(graph, incoming) {
                            if (incoming.isNotBlank()) graph.runtimeLog.record("sharing", "text_share_received",
                                origin = "native", level = RuntimeLogLevel.INFO, evidenceKind = EvidenceKind.RESULT,
                                fields = mapOf("mimeType" to "text/plain", "textCharacters" to incoming.length.toString()))
                        }
                        MorphTheme(preferences.theme) { MorphApp(graph, incoming) { sharedText.value = "" } }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        attachedGraph?.runtimeLog?.record("lifecycle", "activity_new_intent", origin = "native",
            level = RuntimeLogLevel.INFO, evidenceKind = EvidenceKind.PLATFORM_RESPONSE,
            fields = mapOf("activity" to "MainActivity", "action" to intent.action.orEmpty(), "mimeType" to intent.type.orEmpty()))
        acceptShare(intent)
    }

    private fun acceptShare(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            sharedText.value = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty().take(32_768)
            intent.removeExtra(Intent.EXTRA_TEXT) // Loading a draft never dispatches a model request.
        }
    }

    private fun attachGraph(graph: AppGraph, restoredState: Boolean) {
        if (attachedGraph !== graph) {
            attachedGraph = graph
            graph.runtimeLog.record("lifecycle", "activity_created", origin = "native",
                level = RuntimeLogLevel.INFO, evidenceKind = EvidenceKind.RESULT,
                fields = mapOf("activity" to "MainActivity", "restoredState" to restoredState.toString()))
        }
        updateGraphVisibility(graph, lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }

    override fun onStart() {
        super.onStart()
        (application as MorphApplication).graphIfInitialized()?.let { updateGraphVisibility(it, true) }
    }

    override fun onStop() {
        attachedGraph?.let { updateGraphVisibility(it, false) }
        super.onStop()
    }

    private fun updateGraphVisibility(graph: AppGraph, visible: Boolean) {
        graph.uiVisible = visible
        if (graphVisible == visible) return
        graphVisible = visible
        graph.runtimeLog.record("lifecycle", if (visible) "activity_started" else "activity_stopped",
            origin = "native", level = RuntimeLogLevel.DEBUG, evidenceKind = EvidenceKind.RESULT,
            fields = mapOf("activity" to "MainActivity"))
    }

    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>, menu: Menu?, deviceId: Int) {
        val sendMode = (application as MorphApplication).graphIfInitialized()?.preferences?.state?.value?.composerSendMode
            ?: ComposerSendMode.CTRL_ENTER
        val shortcuts = buildList {
            if (sendMode == ComposerSendMode.CTRL_ENTER)
                add(KeyboardShortcutInfo("发送（输入法组合时不提交）", KeyEvent.KEYCODE_ENTER, KeyEvent.META_CTRL_ON))
            else {
                add(KeyboardShortcutInfo("发送（输入法组合时不提交）", KeyEvent.KEYCODE_ENTER, 0))
                add(KeyboardShortcutInfo("换行", KeyEvent.KEYCODE_ENTER, KeyEvent.META_SHIFT_ON))
            }
            addAll(listOf(
                KeyboardShortcutInfo("操作面板", KeyEvent.KEYCODE_K, KeyEvent.META_CTRL_ON),
                KeyboardShortcutInfo("助手", KeyEvent.KEYCODE_1, KeyEvent.META_CTRL_ON),
                KeyboardShortcutInfo("身体", KeyEvent.KEYCODE_2, KeyEvent.META_CTRL_ON),
                KeyboardShortcutInfo("网关", KeyEvent.KEYCODE_3, KeyEvent.META_CTRL_ON),
                KeyboardShortcutInfo("记录", KeyEvent.KEYCODE_4, KeyEvent.META_CTRL_ON),
                KeyboardShortcutInfo("设置", KeyEvent.KEYCODE_5, KeyEvent.META_CTRL_ON),
            ))
        }
        data.add(KeyboardShortcutGroup("AndroBody", shortcuts))
        super.onProvideKeyboardShortcuts(data, menu, deviceId)
    }
}

@androidx.compose.runtime.Composable
private fun GraphLoadingScreen() {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            CircularProgressIndicator()
            Text("正在读取本机状态", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@androidx.compose.runtime.Composable
private fun GraphFailedScreen(errorCode: String, retry: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Text("本机状态未能载入", style = MaterialTheme.typography.titleMedium)
            Text(errorCode, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
            Button(onClick = retry, modifier = Modifier.padding(top = 12.dp)) { Text("重试") }
        }
    }
}

@androidx.compose.runtime.Composable
private fun PackageMigrationScreen(
    source: LegacyApplication,
    importing: Boolean,
    errorCode: String?,
    onImport: () -> Unit,
    onSkip: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Text("接续旧版本数据", style = MaterialTheme.typography.headlineSmall)
            Text("发现本机旧安装 · ${source.versionName}", Modifier.padding(top = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("可将会话、模型与网关配置（含凭据）、回执与运行记录、本机留存的通知和诊断捕获、工作区文件、录音及悬浮位置迁移到 AndroBody。",
                Modifier.padding(top = 20.dp), style = MaterialTheme.typography.bodyLarge)
            Text("传输只在本机同签名应用间进行；新安装会重新加密，并校验会话与文件读回。旧应用和原数据会保留。",
                Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("通知访问、麦克风、悬浮窗等 Android 权限不会迁移，需要在新应用重新授权。",
                Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (errorCode != null) {
                Text("迁移未完成 · $errorCode · 旧数据仍保留", Modifier.padding(top = 16.dp),
                    color = MaterialTheme.colorScheme.error)
            }
            if (importing) {
                CircularProgressIndicator(Modifier.padding(top = 24.dp))
                Text("正在校验并搬移本机数据", Modifier.padding(top = 10.dp))
            } else {
                Button(onClick = onImport, modifier = Modifier.padding(top = 22.dp)) {
                    Text(if (errorCode == null) "迁移到 AndroBody" else "重试迁移")
                }
                androidx.compose.material3.TextButton(onClick = onSkip, modifier = Modifier.padding(top = 4.dp)) {
                    Text("跳过并继续使用新安装")
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun PackageMigrationCompleteScreen(
    summary: PackageMigrationSummary,
    errorCode: String?,
    onContinue: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("本机数据已迁移", style = MaterialTheme.typography.headlineSmall)
            Text("逐项校验与加密读回通过", Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.primary)
            Text("会话 ${summary.sessions} · 加密记录 ${summary.encryptedRecords} · 工作区文件 ${summary.workspaceFiles} · 录音 ${summary.recordings} · 诊断文件 ${summary.diagnosticFiles}",
                Modifier.padding(top = 20.dp), style = MaterialTheme.typography.bodyLarge)
            Text("旧应用与原数据仍保留。通知访问、麦克风、悬浮窗等权限需在 AndroBody 中重新授权。",
                Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (errorCode != null) Text("迁移结果已保存；继续标记失败 · $errorCode", Modifier.padding(top = 12.dp),
                color = MaterialTheme.colorScheme.error)
            Button(onClick = onContinue, modifier = Modifier.padding(top = 24.dp)) { Text("进入 AndroBody") }
        }
    }
}
