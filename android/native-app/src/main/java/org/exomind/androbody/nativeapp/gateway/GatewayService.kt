package org.exomind.androbody.nativeapp.gateway

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.exomind.androbody.nativeapp.MainActivity
import org.exomind.androbody.nativeapp.R
import org.exomind.androbody.nativeapp.graph
import org.exomind.androbody.nativeapp.diagnostics.CapabilityFeasibility
import org.exomind.androbody.nativeapp.diagnostics.EvidenceKind
import org.exomind.androbody.nativeapp.diagnostics.RuntimeLogLevel

class GatewayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serving: Job? = null
    private val ownerId = java.util.UUID.randomUUID().toString()
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        graph.runtimeLog.record("gateway", if (intent?.action == STOP) "service_stop_requested" else "service_start_requested",
            origin = "gateway", level = RuntimeLogLevel.INFO, evidenceKind = EvidenceKind.ACTUAL_ATTEMPT,
            feasibility = CapabilityFeasibility.NOT_ASSESSED, fields = mapOf("startId" to startId.toString()))
        if (intent?.action == STOP) {
            graph.gateway.stop()
            graph.runtimeLog.record("gateway", "service_stop_submitted", origin = "gateway",
                level = RuntimeLogLevel.INFO, evidenceKind = EvidenceKind.RESULT,
                feasibility = CapabilityFeasibility.SUBMITTED, fields = mapOf("ownerId" to ownerId))
            stopSelf(); return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "本机网关服务", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 0, Intent(this, GatewayService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_morph).setContentTitle("AndroBody 本机网关")
            .setContentText("127.0.0.1:${LocalGateway.PORT} · 点击查看状态").setContentIntent(open).setOngoing(true)
            .addAction(0, "停止", stop).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(101, notification)
        graph.runtimeLog.record("gateway", "foreground_service_started", origin = "gateway",
            level = RuntimeLogLevel.INFO, evidenceKind = EvidenceKind.PLATFORM_RESPONSE,
            feasibility = CapabilityFeasibility.AVAILABLE, fields = mapOf("ownerId" to ownerId, "startId" to startId.toString()))
        if (serving?.isActive != true) serving = scope.launch {
            try { graph.gateway.serve(ownerId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                graph.runtimeLog.record("gateway", "listener_failed", origin = "gateway",
                    level = RuntimeLogLevel.ERROR, evidenceKind = EvidenceKind.RESULT,
                    feasibility = CapabilityFeasibility.FAILED,
                    fields = mapOf("ownerId" to ownerId, "exceptionClass" to failure.javaClass.name))
                graph.gateway.reportHostFailure(ownerId)
            }
            finally {
                graph.runtimeLog.record("gateway", "listener_finished", origin = "gateway",
                    level = RuntimeLogLevel.INFO, evidenceKind = EvidenceKind.RECOVERY,
                    feasibility = CapabilityFeasibility.UNKNOWN, fields = mapOf("ownerId" to ownerId))
                withContext(NonCancellable + Dispatchers.Main) { stopSelf(startId) }
            }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        graph.runtimeLog.record("gateway", "service_destroyed", origin = "gateway",
            level = RuntimeLogLevel.INFO, evidenceKind = EvidenceKind.RESULT,
            feasibility = CapabilityFeasibility.UNKNOWN, fields = mapOf("ownerId" to ownerId))
        graph.gateway.stop(ownerId); serving?.cancel(); scope.cancel(); super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object { const val STOP = "org.exomind.andromorph.native.STOP_GATEWAY"; private const val CHANNEL = "native-gateway" }
}
