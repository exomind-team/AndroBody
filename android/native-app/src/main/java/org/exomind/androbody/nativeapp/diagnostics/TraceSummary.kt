package org.exomind.androbody.nativeapp.diagnostics

import org.json.JSONObject

/** Deterministic projection shared by the screen, clipboard and handoff file. No inferred send events. */
data class TraceSummary(val traceId: String, val requestId: String, val status: String,
    val title: String, val actualUrl: String?, val plannedUrl: String?, val attempts: Int,
    val stopReason: String?, val httpStatus: String?, val missingStart: Boolean) {
    fun readable(): String = buildString {
        appendLine(title)
        appendLine(if (actualUrl == null) "计划端点（缺少发送证据）：${plannedUrl ?: "未记录"}" else "实际 Request：$actualUrl")
        appendLine("尝试 $attempts 次 · HTTP ${httpStatus ?: "未记录"}")
        stopReason?.let { appendLine("停止依据：$it") }
        if (missingStart) appendLine("开头事件缺失，记录可能已轮转；不补造经过。")
        append("任务 $requestId\n诊断 $traceId")
    }
    fun json(): JSONObject = JSONObject().put("traceId", traceId).put("requestId", requestId)
        .put("status", status).put("actualUrl", actualUrl ?: JSONObject.NULL)
        .put("plannedUrl", plannedUrl ?: JSONObject.NULL).put("attempts", attempts)
        .put("stopReason", stopReason ?: JSONObject.NULL).put("httpStatus", httpStatus ?: JSONObject.NULL)
        .put("missingStart", missingStart).put("summary", readable())
}

fun summarizeTrace(events: List<TraceEvent>): TraceSummary {
    require(events.isNotEmpty() && events.map { it.traceId }.distinct().size == 1)
    val rows = events.sortedBy { it.sequence }
    val ended = rows.lastOrNull { it.stage in setOf("request_completed", "request_failed", "request_cancelled") }
    val failed = rows.lastOrNull { it.stage == "resolution_decision" && it.fields["decision"] == "STOP" }
    val reason = failed?.fields?.get("failure") ?: ended?.fields?.get("kind") ?: ended?.fields?.get("code")
    val title = when (ended?.stage) {
        "request_completed" -> "连接与协议有效 · 设备任务另查回执"
        "request_cancelled" -> "已取消请求 · 上游最终结果可能未知"
        "request_failed" -> when (reason) {
            "AUTHENTICATION", "ACCESS_DENIED" -> "认证或权限被拒绝 · 检查配置"
            "MODEL_NOT_FOUND" -> "上游未接受模型标识 · 未更换模型"
            "INVALID_RESPONSE" -> "收到响应，但模型协议未成立"
            "NETWORK", "TIMEOUT", "UPSTREAM_REQUEST_BUDGET" -> "连接中断或超时 · 上游结果未知"
            else -> "连接未完成 · 查看依据或导出诊断"
        }
        else -> "进行中或记录不完整 · 结果未确认"
    }
    return TraceSummary(rows.first().traceId, rows.first().requestId,
        ended?.stage ?: "incomplete", title,
        rows.lastOrNull { it.stage == "request_headers_end" }?.fields?.get("url"),
        rows.lastOrNull { it.stage == "attempt_start" }?.fields?.get("url"),
        rows.maxOf { it.attempt }, reason,
        rows.lastOrNull { it.stage == "response_headers_end" }?.fields?.get("httpStatus"),
        rows.none { it.stage == "request_start" })
}
