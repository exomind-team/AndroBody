package org.exomind.androbody.nativeapp

import android.app.Application
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import androidx.work.Configuration
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import org.exomind.androbody.nativeapp.body.*
import org.exomind.androbody.nativeapp.events.NotificationInbox
import org.exomind.androbody.nativeapp.config.*
import org.exomind.androbody.nativeapp.core.*
import org.exomind.androbody.nativeapp.gateway.*
import org.exomind.androbody.nativeapp.model.*
import org.exomind.androbody.nativeapp.runtime.*
import org.exomind.androbody.nativeapp.storage.*
import org.exomind.androbody.nativeapp.events.*
import org.exomind.androbody.nativeapp.diagnostics.*
import org.exomind.androbody.nativeapp.migration.LegacyApplication
import org.exomind.androbody.nativeapp.migration.PackageDataMigration
import org.exomind.androbody.nativeapp.migration.PackageMigrationFailure
import org.exomind.androbody.nativeapp.migration.PackageMigrationSummary

sealed interface AppGraphInitialization {
    data object Loading : AppGraphInitialization
    data class Ready(val graph: AppGraph) : AppGraphInitialization
    data class Failed(val errorCode: String) : AppGraphInitialization
}

sealed interface PackageMigrationStartup {
    data object NotRequired : PackageMigrationStartup
    data class Available(val source: LegacyApplication) : PackageMigrationStartup
    data class Importing(val source: LegacyApplication) : PackageMigrationStartup
    data class Failed(val source: LegacyApplication, val errorCode: String) : PackageMigrationStartup
    data class Completed(val summary: PackageMigrationSummary, val errorCode: String? = null) : PackageMigrationStartup
}

class MorphApplication : Application(), Configuration.Provider {
    private val graphDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) { AppGraph(this) }
    val graph: AppGraph get() = graphDelegate.value
    private val initializationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableGraphInitialization = MutableStateFlow<AppGraphInitialization>(AppGraphInitialization.Loading)
    val graphInitialization: StateFlow<AppGraphInitialization> = mutableGraphInitialization
    private val mutablePackageMigration = MutableStateFlow<PackageMigrationStartup>(PackageMigrationStartup.NotRequired)
    val packageMigration: StateFlow<PackageMigrationStartup> = mutablePackageMigration
    @Volatile private var crashRecorderInstalled = false

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.FLAVOR == "androbody") {
            val source = PackageDataMigration.installedLegacySource(this)
            try {
                PackageDataMigration.recoverInterruptedImport(this)
            } catch (failure: Exception) {
                if (source != null) {
                    mutablePackageMigration.value = PackageMigrationStartup.Failed(source,
                        (failure as? PackageMigrationFailure)?.code ?: "MIGRATION_RECOVERY_FAILED")
                    return
                }
            }
            val completion = PackageDataMigration.pendingCompletion(this)
            if (completion != null) {
                mutablePackageMigration.value = PackageMigrationStartup.Completed(completion)
                return
            }
            if (source != null && !PackageDataMigration.decisionMade(this)) {
                mutablePackageMigration.value = PackageMigrationStartup.Available(source)
                return
            }
        }
        startGraphInitialization()
    }

    fun importLegacyData(source: LegacyApplication) {
        val current = mutablePackageMigration.value
        if (current !is PackageMigrationStartup.Available && current !is PackageMigrationStartup.Failed) return
        mutablePackageMigration.value = PackageMigrationStartup.Importing(source)
        initializationScope.launch {
            try {
                val summary = PackageDataMigration.import(this@MorphApplication, source)
                mutablePackageMigration.value = PackageMigrationStartup.Completed(summary)
            } catch (failure: Exception) {
                mutablePackageMigration.value = PackageMigrationStartup.Failed(source,
                    (failure as? PackageMigrationFailure)?.code ?: "MIGRATION_FAILED")
            }
        }
    }

    fun continueAfterMigration(summary: PackageMigrationSummary) {
        if (mutablePackageMigration.value !is PackageMigrationStartup.Completed) return
        runCatching { PackageDataMigration.acknowledgeCompletion(this) }
            .onSuccess {
                mutablePackageMigration.value = PackageMigrationStartup.NotRequired
                startGraphInitialization()
            }
            .onFailure { failure ->
                mutablePackageMigration.value = PackageMigrationStartup.Completed(summary,
                    (failure as? PackageMigrationFailure)?.code ?: "MIGRATION_DECISION_NOT_SAVED")
            }
    }

    fun skipLegacyData(source: LegacyApplication) {
        val current = mutablePackageMigration.value
        if (current !is PackageMigrationStartup.Available && current !is PackageMigrationStartup.Failed) return
        runCatching {
            PackageDataMigration.recoverInterruptedImport(this)
            PackageDataMigration.skip(this)
        }
            .onSuccess {
                mutablePackageMigration.value = PackageMigrationStartup.NotRequired
                startGraphInitialization()
            }
            .onFailure { failure ->
                mutablePackageMigration.value = PackageMigrationStartup.Failed(source,
                    (failure as? PackageMigrationFailure)?.code ?: "MIGRATION_DECISION_NOT_SAVED")
            }
    }

    fun graphIfInitialized(): AppGraph? = if (graphDelegate.isInitialized()) graphDelegate.value else null

    fun retryGraphInitialization() {
        if (mutableGraphInitialization.value is AppGraphInitialization.Loading) return
        startGraphInitialization()
    }

    suspend fun awaitGraph(): AppGraph = when (val state = graphInitialization.first { it !is AppGraphInitialization.Loading }) {
        is AppGraphInitialization.Ready -> state.graph
        is AppGraphInitialization.Failed -> throw IllegalStateException("AppGraph initialization failed: ${state.errorCode}")
        AppGraphInitialization.Loading -> error("Unreachable graph loading state")
    }

    private fun startGraphInitialization() {
        mutableGraphInitialization.value = AppGraphInitialization.Loading
        val startedAtNanos = System.nanoTime()
        initializationScope.launch {
            try {
                val appGraph = graph
                if (!appGraph.preferences.initialized.value) appGraph.preferences.retryLoad()
                appGraph.preferences.awaitReady()
                recordPreviousProcessExit(appGraph.runtimeLog)
                appGraph.runtimeLog.record("lifecycle", "app_graph_ready", level = RuntimeLogLevel.INFO,
                    evidenceKind = EvidenceKind.PLATFORM_RESPONSE,
                    fields = mapOf("preferencesLoaded" to "true",
                        "elapsedMs" to ((System.nanoTime() - startedAtNanos) / 1_000_000L).toString()))
                appGraph.runtimeLog.record("lifecycle", "process_started", level = RuntimeLogLevel.INFO,
                    evidenceKind = EvidenceKind.RESULT,
                    fields = mapOf("processName" to applicationInfo.processName, "processId" to Process.myPid().toString()))
                installCrashRecorder(appGraph.runtimeLog)
                mutableGraphInitialization.value = AppGraphInitialization.Ready(appGraph)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableGraphInitialization.value = AppGraphInitialization.Failed(failure.javaClass.simpleName)
            }
        }
    }

    private fun installCrashRecorder(logger: RuntimeEventLogger) {
        if (crashRecorderInstalled) return
        crashRecorderInstalled = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
            logger.recordUncaught(thread.name, failure)
            previous?.uncaughtException(thread, failure) ?: run {
                Process.killProcess(Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }

    private fun recordPreviousProcessExit(logger: RuntimeEventLogger) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            logger.record("lifecycle", "previous_exit_info_unavailable", level = RuntimeLogLevel.DEBUG,
                evidenceKind = EvidenceKind.PREFLIGHT,
                fields = mapOf("minimumApi" to Build.VERSION_CODES.R.toString(), "currentApi" to Build.VERSION.SDK_INT.toString()))
            return
        }
        try {
            val manager = getSystemService(ActivityManager::class.java)
            val lastExit = manager.getHistoricalProcessExitReasons(packageName, 0, 5)
                .maxByOrNull { it.timestamp } ?: return
            val preferences = getSharedPreferences(PROCESS_PREFS, Context.MODE_PRIVATE)
            val lastRecordedAt = preferences.getLong(LAST_EXIT_AT, 0L)
            if (lastExit.timestamp <= lastRecordedAt) return
            preferences.edit().putLong(LAST_EXIT_AT, lastExit.timestamp).apply()
            val level = if (lastExit.reason in setOf(ApplicationExitInfo.REASON_CRASH,
                    ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR)) RuntimeLogLevel.ERROR
                else RuntimeLogLevel.INFO
            logger.record("lifecycle", "previous_process_exit_observed", level = level,
                evidenceKind = EvidenceKind.RECOVERY,
                fields = mapOf("exitAtMillis" to lastExit.timestamp.toString(),
                    "reasonCode" to lastExit.reason.toString(), "statusCode" to lastExit.status.toString(),
                    "processName" to (lastExit.processName ?: "unknown"), "pid" to lastExit.pid.toString(),
                    "importance" to lastExit.importance.toString()))
        } catch (failure: Exception) {
            logger.record("lifecycle", "previous_process_exit_query_failed", level = RuntimeLogLevel.WARN,
                evidenceKind = EvidenceKind.PREFLIGHT,
                fields = mapOf("exceptionClass" to failure.javaClass.name))
        }
    }

    companion object {
        private const val PROCESS_PREFS = "runtime-console-state"
        private const val LAST_EXIT_AT = "last-exit-at"
    }
}
val Context.graph: AppGraph get() = (applicationContext as MorphApplication).graph

/** Composition root: only this layer knows both runtimes. Executors get narrow dependencies. */
class AppGraph(private val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val preferences = Preferences(context)
    val profiles = ProfileStore(context)
    val credentials = ClientCredential(context)
    val receipts = ReceiptStore(context)
    val networkLogs = NetworkLogStore(context)
    val approvals = ApprovalQueue()
    val workspace = Workspace(context)
    val documents = DocumentExchange(context, workspace)
    val notificationInbox = NotificationInbox(context)
    val networkDiagnostics = DiagnosticStore(context)
    val runtimeLog = RuntimeEventLogger(networkDiagnostics, networkDiagnostics.processRunId)
    val endpointCache = PersistentEndpointCache(context)
    val http = ModelHttpClient(traces = networkDiagnostics, cache = endpointCache, diagnostics = networkDiagnostics)
    @Volatile var uiVisible: Boolean = false
    val audioRecordings = AudioRecordingStore(context)
    val audioRecording = AudioRecordingController(context, audioRecordings, runtimeLog)
    private val tools: List<BodyTool> = deviceTools(context) + workspaceTools(workspace) +
        notificationTools(context, notificationInbox) + audioRecordingTools(audioRecording, audioRecordings) { uiVisible } + listOf(
        SensorTool(context, workspace, runtimeLog), SpeechTool(context, runtimeLog),
        SimpleTool(Capability(name = "am_runtime_trace_read", title = "读取运行追踪", description = "Read up to 50 bounded AndroBody runtime metadata events by request or subsystem. Distinguishes theory, preflight, actual attempts and results; no prompt, API key, notification body or audio content. A remote model sees returned metadata as part of this tool result.",
            parameters = objectSchema(jsonObject("requestId" to textField("Optional task/request ID", 64),
                "subsystem" to textField("Optional subsystem filter", 48),
                "limit" to jsonObject("type" to "integer", "minimum" to 1, "maximum" to 50))),
            effect = Effect.READ_DEVICE, gatewayEligible = false, needsConfirmation = true)) { invocation ->
            val data = networkDiagnostics.recentForAgent(
                invocation.arguments.optString("requestId").takeIf(String::isNotBlank),
                invocation.arguments.optString("subsystem").takeIf(String::isNotBlank),
                invocation.arguments.optInt("limit", 20))
            ToolResult.completed(data, "AndroBody app-owned, redacted trace metadata; trace status is not proof of external business completion")
        },
        SimpleTool(Capability(name = "am_receipts_recent", title = "查看操作回执", description = "Read retained audit metadata; not all device history. No secrets or notification bodies.", parameters = objectSchema(), effect = Effect.READ_DEVICE, gatewayEligible = false)) {
            ToolResult.completed(receipts.snapshot(), "本机执行记录，最多保留 200 条；未测试状态不转为 PASS")
        },
        SimpleTool(Capability(name = "am_gateway_status", title = "查看网关状态", description = "Read local gateway lifecycle and request counters, never credentials.", parameters = objectSchema(), effect = Effect.READ_DEVICE, gatewayEligible = true)) {
            val value = gateway.state.value
            ToolResult.completed(jsonObject("status" to value.status, "activeRequests" to value.activeRequests,
                "handledRequests" to value.handledRequests, "port" to LocalGateway.PORT, "binding" to "127.0.0.1"), "本机网关所有者状态；不证明客户端兼容测试通过")
        },
        SimpleTool(Capability(name = "am_gateway_set_enabled", title = "控制本机网关", description = "Request start or stop of the loopback gateway. Native agent only, requires a visible app, and may require user confirmation under the native tool policy. Cannot change keys, grants or upstream routes.",
            parameters = objectSchema(jsonObject("enabled" to jsonObject("type" to "boolean")), listOf("enabled")), effect = Effect.CONTROL, gatewayEligible = false, needsConfirmation = true)) {
            if (!uiVisible) throw BodyFailure("FOREGROUND_REQUIRED", "请回到本应用完成网关控制")
            val enabled = it.arguments.getBoolean("enabled")
            setGateway(enabled)
            ToolResult(Outcome.SUBMITTED, jsonObject("requestedEnabled" to enabled), "已向宿主提交；请用 am_gateway_status 核对实际状态")
        },
    )
    val registry: ToolRegistry = ToolRegistry(tools)
    private val policy = object : PermissionPolicy {
        override fun denial(principal: Principal, capability: Capability): String? = when {
            principal.origin == Origin.EVENT -> "通知触发器不具有设备工具执行权"
            !preferences.initialized.value && principal.origin in setOf(Origin.NATIVE, Origin.GATEWAY) -> "本机设置仍在加载；没有执行工具"
            principal.origin == Origin.NATIVE -> NativeApprovalRules.denial(principal, capability, preferences.state.value)
            principal.origin == Origin.GATEWAY && principal.id != "local-client:${preferences.state.value.gatewayEpoch}" -> "客户端身份已撤销"
            principal.origin == Origin.GATEWAY && !capability.gatewayEligible -> "此能力不对网关开放"
            principal.origin == Origin.GATEWAY && capability.name !in preferences.state.value.gatewayScopes -> "此能力尚未共享或已撤销"
            else -> null
        }
        override fun requiresApproval(principal: Principal, capability: Capability): Boolean =
            NativeApprovalRules.requiresApproval(principal, capability, preferences.state.value)
    }
    val broker: CapabilityBroker = CapabilityBroker(registry, policy, approvals, receipts, runtimeLog = runtimeLog)
    val engine: ToolTurnEngine = ToolTurnEngine(broker)
    val gateway: LocalGateway = LocalGateway(engine, profiles, preferences, credentials, http)
    val nativeSessions = NativeSessionStore(context)
    val native: NativeAgent = NativeAgent(context, scope, engine, profiles, preferences, http, runtimeLog, nativeSessions)
    val triggerInbox = TriggerInbox(context)
    val eventExecution = kotlinx.coroutines.sync.Mutex()

    fun setGateway(enabled: Boolean) {
        val intent = Intent(context, GatewayService::class.java)
        if (enabled) {
            if (!preferences.initialized.value) throw BodyFailure("PREFERENCES_LOADING", "本机设置仍在加载；网关未启动")
            if (!uiVisible) throw BodyFailure("FOREGROUND_REQUIRED", "请在前台启动网关")
            context.startForegroundService(intent)
        } else {
            gateway.stop(); context.stopService(intent)
        }
    }
    suspend fun rotateGatewayCredential() {
        setGateway(false)
        preferences.update { it.copy(gatewayEpoch = it.gatewayEpoch + 1) }
        credentials.rotate()
        approvals.denyAll()
    }
    suspend fun setGatewayGrant(name: String, enabled: Boolean) {
        require(registry.find(name)?.capability?.gatewayEligible == true)
        preferences.update { it.copy(gatewayScopes = if (enabled) it.gatewayScopes + name else it.gatewayScopes - name) }
        if (!enabled) gateway.cancelClients()
    }
}
