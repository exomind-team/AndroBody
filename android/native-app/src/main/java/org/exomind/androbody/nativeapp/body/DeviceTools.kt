package org.exomind.androbody.nativeapp.body

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.exomind.androbody.nativeapp.R
import org.exomind.androbody.nativeapp.core.*
import org.json.JSONArray

fun deviceTools(context: Context): List<BodyTool> = listOf(
    SimpleTool(Capability(name = "am_battery_snapshot", title = "电池状态", description = "Observe current battery and charging state. Missing properties are null, never invented zero.", parameters = objectSchema(), effect = Effect.READ_DEVICE, gatewayEligible = true)) {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val rawLevel = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val manager = context.getSystemService(BatteryManager::class.java)
        ToolResult.completed(jsonObject("levelPercent" to if (rawLevel >= 0 && scale > 0) 100.0 * rawLevel / scale else null,
            "charging" to manager.isCharging, "observedAtEpochMs" to System.currentTimeMillis(), "scope" to "this Android device"),
            "Android ACTION_BATTERY_CHANGED 与 BatteryManager；不是历史趋势或耗电归因")
    },
    SimpleTool(Capability(name = "am_device_info", title = "运行身份", description = "Read Android API level, ABI and this app UID. No hardware serial, contacts or account identifiers.", parameters = objectSchema(), effect = Effect.READ_DEVICE, gatewayEligible = true)) {
        ToolResult.completed(jsonObject("apiLevel" to Build.VERSION.SDK_INT, "abis" to JSONArray(Build.SUPPORTED_ABIS.toList()),
            "appUid" to android.os.Process.myUid(), "packageName" to context.packageName, "runtime" to "Kotlin native; no DSH/Node/PRoot"),
            "本进程 UID 与公开设备 API；不代表 Root 或 ADB 身份")
    },
    SimpleTool(Capability(name = "am_network_snapshot", title = "网络状态", description = "Read active network transports and Android validation state; no SSID, scans, packet capture or remote probes.", parameters = objectSchema(), effect = Effect.READ_DEVICE, gatewayEligible = true)) {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val caps = manager.getNetworkCapabilities(manager.activeNetwork)
        val transports = listOf(NetworkCapabilities.TRANSPORT_WIFI to "wifi", NetworkCapabilities.TRANSPORT_CELLULAR to "cellular",
            NetworkCapabilities.TRANSPORT_ETHERNET to "ethernet", NetworkCapabilities.TRANSPORT_VPN to "vpn")
            .filter { caps?.hasTransport(it.first) == true }.map { it.second }
        ToolResult.completed(jsonObject("active" to (caps != null), "transports" to JSONArray(transports),
            "systemValidated" to caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)),
            "ConnectivityManager 当前观察；不证明所配置模型端点可达")
    },
    SimpleTool(Capability(name = "am_notification_post", title = "发布测试通知", description = "Post one notification owned by this app. Does not read other apps' notifications or prove the user saw it.",
        parameters = objectSchema(jsonObject("title" to textField("Title", 80), "text" to textField("Body", 500)), listOf("title", "text")),
        effect = Effect.USER_VISIBLE, gatewayEligible = false, needsConfirmation = true)) { invocation ->
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            throw BodyFailure("NEEDS_GRANT", "请在身体页授予本应用通知发布权限")
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) throw BodyFailure("NEEDS_GRANT", "系统已禁用本应用通知")
        platformCall("NotificationManager.createNotificationChannel") {
            manager.createNotificationChannel(NotificationChannel("body-actions", "Agent 主动通知", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val id = invocation.id.hashCode() and Int.MAX_VALUE
        platformCall("NotificationManager.notify") {
            manager.notify(id, NotificationCompat.Builder(context, "body-actions").setSmallIcon(R.drawable.ic_morph)
                .setContentTitle(invocation.arguments.stringRequired("title", 80)).setContentText(invocation.arguments.stringRequired("text", 500))
                .setAutoCancel(true).build())
        }
        val observed = platformCall("NotificationManager.activeNotifications") { manager.activeNotifications.any { it.id == id } }
        ToolResult(if (observed) Outcome.COMPLETED else Outcome.SUBMITTED, jsonObject("notificationId" to id, "activeObserved" to observed),
            "本应用通知已提交；activeNotifications 可核对存在，不证明用户阅读")
    },
)
