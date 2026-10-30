package org.exomind.androbody.nativeapp.events

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.exomind.androbody.nativeapp.graph
import org.exomind.androbody.nativeapp.MorphApplication
import org.exomind.androbody.nativeapp.diagnostics.CapabilityFeasibility
import org.exomind.androbody.nativeapp.diagnostics.EvidenceKind
import org.exomind.androbody.nativeapp.diagnostics.RuntimeLogLevel

/** No default all-app collection. System notification access and cloud use are separate grants. */
class NoticeListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onListenerConnected() {
        current = this
        connected.value = true
        scope.launch(Dispatchers.IO) {
            val app = applicationContext.graph
            app.runtimeLog.record("notifications", "listener_connected", origin = "system",
                level = RuntimeLogLevel.INFO, evidenceKind = EvidenceKind.PLATFORM_RESPONSE,
                feasibility = CapabilityFeasibility.AVAILABLE)
            refreshActiveSnapshot()
        }
    }
    override fun onListenerDisconnected() {
        connected.value = false
        if (current === this) current = null
        scope.launch(Dispatchers.IO) {
            val app = applicationContext.graph
            app.runtimeLog.record("notifications", "listener_disconnected", origin = "system",
                level = RuntimeLogLevel.WARN, evidenceKind = EvidenceKind.PLATFORM_RESPONSE,
                feasibility = CapabilityFeasibility.NEEDS_USER_ACTION)
            if (!connected.value) WorkManager.getInstance(applicationContext).cancelAllWorkByTag(WORK_TAG)
        }
    }
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName == packageName) return
        val record = runCatching { toRecord(sbn, active = true) }.getOrNull() ?: return
        scope.launch(Dispatchers.IO) {
            val app = applicationContext.graph
            app.runtimeLog.record("notifications", "notification_posted_observed", requestId = record.id,
                origin = "system", level = RuntimeLogLevel.INFO, evidenceKind = EvidenceKind.PLATFORM_RESPONSE,
                feasibility = CapabilityFeasibility.AVAILABLE,
                fields = mapOf("sourcePackageHash" to sha256(record.packageName).take(16),
                    "postedAtEpochMs" to record.postedAt.toString()))
            runCatching { app.notificationInbox.observe(record) }
                .onSuccess {
                    app.runtimeLog.record("notifications", "notification_inbox_persisted", requestId = record.id,
                        origin = "system", level = RuntimeLogLevel.INFO, evidenceKind = EvidenceKind.RESULT,
                        feasibility = CapabilityFeasibility.COMPLETED,
                        fields = mapOf("sourcePackageHash" to sha256(record.packageName).take(16),
                            "active" to record.active.toString()))
                }
                .onFailure { failure -> app.runtimeLog.record("notifications", "notification_inbox_write_failed",
                    origin = "system", level = RuntimeLogLevel.ERROR, evidenceKind = EvidenceKind.RESULT,
                    feasibility = CapabilityFeasibility.FAILED, fields = mapOf("exceptionClass" to failure.javaClass.name)) }
            try {
                val rules = app.preferences.awaitReady()
                if (!rules.triggersEnabled || !rules.triggerCloudConsent || sbn.packageName !in rules.triggerPackages) return@launch
                val title = sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().take(100)
                val body = sbn.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty().take(1000)
                if (rules.triggerKeyword.isNotBlank() && !(title + "\n" + body).contains(rules.triggerKeyword, ignoreCase = true)) return@launch
                val current = app.preferences.awaitReady()
                if (!current.triggersEnabled || !current.triggerCloudConsent || sbn.packageName !in current.triggerPackages ||
                    current.triggerKeyword != rules.triggerKeyword) return@launch
                val route = app.profiles.read(current.protocol) ?: return@launch
                val item = app.triggerInbox.add(sbn.packageName, sbn.key, title, body, route.routeFingerprint()) ?: return@launch
                app.runtimeLog.record("notifications", "allowed_notification_queued", requestId = item.id,
                    origin = "system", level = RuntimeLogLevel.INFO, evidenceKind = EvidenceKind.ACTUAL_ATTEMPT,
                    feasibility = CapabilityFeasibility.SUBMITTED,
                    fields = mapOf("sourcePackage" to sbn.packageName, "notificationKeyHash" to sha256(sbn.key)))
                val work = OneTimeWorkRequestBuilder<NoticeSummaryWorker>()
                    .setInputData(workDataOf("eventId" to item.id))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .addTag(WORK_TAG).build()
                WorkManager.getInstance(applicationContext).enqueueUniqueWork("notice-${item.id}", ExistingWorkPolicy.KEEP, work)
            } catch (failure: Exception) {
                lastError.value = "通知未入队：请检查模型与本地事件存储；没有重试外发"
                app.runtimeLog.record("notifications", "allowed_notification_queue_failed", origin = "system",
                    level = RuntimeLogLevel.ERROR, evidenceKind = EvidenceKind.RESULT,
                    feasibility = CapabilityFeasibility.FAILED, fields = mapOf("exceptionClass" to failure.javaClass.name))
            }
        }
    }
    override fun onNotificationRemoved(
        sbn: StatusBarNotification?,
        rankingMap: NotificationListenerService.RankingMap?,
        reason: Int,
    ) {
        if (sbn != null) {
            val id = notificationId(sbn)
            scope.launch(Dispatchers.IO) { runCatching { graph.notificationInbox.setActive(id, false) } }
        }
        super.onNotificationRemoved(sbn, rankingMap, reason)
    }

    private fun refreshActiveSnapshot(): Boolean = runCatching {
        val app = applicationContext.graph
        val active = activeNotifications.orEmpty().map { toRecord(it, active = true) }
        app.notificationInbox.replaceActive(active)
        app.runtimeLog.record("notifications", "active_snapshot_read", origin = "system",
            level = RuntimeLogLevel.INFO, evidenceKind = EvidenceKind.PLATFORM_RESPONSE,
            feasibility = CapabilityFeasibility.COMPLETED, fields = mapOf("count" to active.size.toString()))
        true
    }.onFailure { failure ->
        (applicationContext as? MorphApplication)?.graphIfInitialized()?.runtimeLog?.record("notifications", "active_snapshot_failed", origin = "system",
            level = RuntimeLogLevel.WARN, evidenceKind = EvidenceKind.RESULT,
            feasibility = CapabilityFeasibility.UNKNOWN, fields = mapOf("exceptionClass" to failure.javaClass.name))
    }.getOrDefault(false)

    private fun toRecord(sbn: StatusBarNotification, active: Boolean): NotificationRecord {
        val extras = sbn.notification.extras
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        val body = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: lines?.joinToString("\n") { it.toString() }.orEmpty()
        val title = extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        return NotificationRecord(notificationId(sbn), sbn.packageName, title, body, sbn.postTime, active)
    }

    private fun notificationId(sbn: StatusBarNotification): String = sha256(sbn.key).take(32)

    override fun onDestroy() {
        if (current === this) current = null
        connected.value = false
        (applicationContext as? MorphApplication)?.graphIfInitialized()?.runtimeLog?.record("notifications", "listener_service_destroyed", origin = "system",
            level = RuntimeLogLevel.INFO, evidenceKind = EvidenceKind.RECOVERY,
            feasibility = CapabilityFeasibility.UNKNOWN)
        scope.cancel(); super.onDestroy()
    }
    companion object {
        const val WORK_TAG = "andromorph-native-notices"
        val connected = MutableStateFlow(false)
        val lastError = MutableStateFlow<String?>(null)
        @Volatile private var current: NoticeListener? = null

        fun requestRefreshActive(): Boolean {
            val listener = current ?: return false
            listener.scope.launch(Dispatchers.IO) { listener.refreshActiveSnapshot() }
            return true
        }

        suspend fun refreshActiveAwait(): Boolean {
            val listener = current ?: return false
            return withContext(Dispatchers.IO) { listener.refreshActiveSnapshot() }
        }
    }
}
