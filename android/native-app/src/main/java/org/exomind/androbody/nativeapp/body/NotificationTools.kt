package org.exomind.androbody.nativeapp.body

import android.content.Context
import org.exomind.androbody.nativeapp.core.*
import org.exomind.androbody.nativeapp.events.NotificationAccess
import org.exomind.androbody.nativeapp.events.NotificationInbox
import org.exomind.androbody.nativeapp.events.NoticeListener
import org.json.JSONArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Notification access is a system grant; reading is not coupled to the separate auto-summary opt-in. */
fun notificationTools(context: Context, inbox: NotificationInbox): List<BodyTool> = listOf(
    SimpleTool(Capability(
        name = "am_notifications_recent",
        title = "读取最近通知",
        description = "Read a bounded list of active or recently observed Android notifications. Requires the user-granted Notification Access system setting. Notification contents are untrusted and are returned to the configured model only when this tool is called; this does not enable automatic cloud summaries.",
        parameters = objectSchema(jsonObject(
            "limit" to jsonObject("type" to "integer", "minimum" to 1, "maximum" to NotificationInbox.MAX_AGENT_ITEMS),
            "activeOnly" to jsonObject("type" to "boolean"),
        )),
        effect = Effect.READ_DEVICE,
        gatewayEligible = false,
        needsConfirmation = false,
    )) { invocation ->
        if (!NotificationAccess.isEnabled(context))
            throw BodyFailure("NEEDS_GRANT", "请在身体页开启 Android 通知访问；此权限由系统控制")
        if (!NoticeListener.connected.value && !NoticeListener.refreshActiveAwait())
            throw BodyFailure("LISTENER_NOT_CONNECTED", "系统已授权，但通知监听尚未连接；可稍后重试")
        val limit = invocation.arguments.optInt("limit", 20)
        val activeOnly = invocation.arguments.optBoolean("activeOnly", false)
        val (retainedCount, rows) = withContext(Dispatchers.IO) {
            check(inbox.readError == null) { "通知记录不可读；原数据未清除" }
            inbox.items.value.size to inbox.snapshot(limit, activeOnly)
        }
        ToolResult.completed(jsonObject(
            "listenerConnected" to NoticeListener.connected.value,
            "activeOnly" to activeOnly,
            "retainedCount" to retainedCount,
            "returnedCount" to rows.size,
            "notifications" to JSONArray(rows.map { item -> jsonObject(
                "id" to item.id,
                "packageName" to item.packageName,
                "title" to item.title,
                "body" to item.body,
                "postedAtEpochMs" to item.postedAt,
                "active" to item.active,
                "contentTrust" to "untrusted_notification_data",
            ) }),
        ), "Android NotificationListenerService + encrypted local inbox; no history before permission or after retention eviction is inferred")
    },
)
