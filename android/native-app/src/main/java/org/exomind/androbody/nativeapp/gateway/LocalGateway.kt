package org.exomind.androbody.nativeapp.gateway

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import org.exomind.androbody.nativeapp.config.*
import org.exomind.androbody.nativeapp.core.*
import org.exomind.androbody.nativeapp.model.*
import org.exomind.androbody.nativeapp.diagnostics.RequestContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Explicitly loopback-only. Listening on LAN is not a hidden toggle in this release. */
data class GatewayState(val status: String = "stopped", val activeRequests: Int = 0, val handledRequests: Long = 0,
    val detail: String = "尚未启动", val startedAt: Long? = null, val observedInputTokens: Long = 0, val observedOutputTokens: Long = 0)
class LocalGateway(
    private val engine: ToolTurnEngine,
    private val profiles: ProfileStore,
    private val preferences: Preferences,
    private val credentials: ClientCredential,
    private val modelHttp: ModelHttpClient,
) {
    private val mutable = MutableStateFlow(GatewayState())
    val state: StateFlow<GatewayState> = mutable
    @Volatile private var listener: ServerSocket? = null
    @Volatile private var owner: String? = null
    private val clients = ConcurrentHashMap<Socket, Job>()
    private val slots = Semaphore(2)

    suspend fun serve(ownerId: String): Unit = coroutineScope {
        val server = synchronized(this@LocalGateway) {
            check(listener == null) { "网关正在释放前一实例" }
            ServerSocket().also {
                listener = it; owner = ownerId
                mutable.value = GatewayState(status = "starting", detail = "正在绑定本机端口")
            }
        }
        try {
            val token = credentials.token()
            server.reuseAddress = true
            server.bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 8)
            mutable.value = GatewayState(status = "running", detail = "仅本机 · 已鉴权 · 有界续轮", startedAt = System.currentTimeMillis())
            while (isActive && !server.isClosed) {
                val client = withContext(Dispatchers.IO) { server.accept() }
                if (!slots.tryAcquire()) {
                    client.use { HttpWriter(it.getOutputStream()).json(503, OpenAiWire.error("BUSY", "网关同时最多处理两个请求").toString()) }
                    continue
                }
                val job = launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                    mutable.update { it.copy(activeRequests = it.activeRequests + 1) }
                    try { client.use { withTimeout(150_000) { handle(client, token) } } }
                    finally {
                        clients.remove(client); slots.release()
                        mutable.update { it.copy(activeRequests = (it.activeRequests - 1).coerceAtLeast(0), handledRequests = it.handledRequests + 1) }
                    }
                }
                clients[client] = job; job.start()
            }
        } catch (failure: CancellationException) { throw failure }
        catch (_: Exception) {
            if (!server.isClosed) mutable.update { it.copy(status = "failed", detail = "网关启动或监听失败；可能端口被占用") }
        } finally {
            server.close(); cancelClients()
            synchronized(this@LocalGateway) {
                if (listener === server) {
                    listener = null; owner = null
                    if (mutable.value.status != "failed") mutable.update { it.copy(status = "stopped", activeRequests = 0, detail = "网关已停止") }
                }
            }
        }
    }
    @Synchronized fun stop(ownerId: String? = null) {
        if (ownerId != null && owner != ownerId) return
        mutable.update { it.copy(status = "stopping", detail = "正在取消请求并释放端口") }
        val server = listener
        server?.close(); cancelClients()
        if (server == null) mutable.update { it.copy(status = "stopped", detail = "网关已停止") }
    }
    @Synchronized fun reportHostFailure(ownerId: String) {
        if (owner != null && owner != ownerId) return
        mutable.update { it.copy(status = "failed", detail = "网关宿主未能完成启动或释放，请核对当前端口状态") }
    }
    fun cancelClients() { clients.forEach { (socket, job) -> job.cancel(); runCatching { socket.close() } } }

    private suspend fun handle(socket: Socket, token: String) {
        socket.soTimeout = 20_000
        val input = socket.getInputStream().buffered()
        val writer = HttpWriter(socket.getOutputStream())
        val requestId = UUID.randomUUID().toString()
        var protocol: ModelProtocol = ModelProtocol.OPENAI
        try {
            val head = HttpFraming.head(input)
            protocol = if (head.path.startsWith("/v1/messages")) ModelProtocol.ANTHROPIC else ModelProtocol.OPENAI
            // This probe deliberately exposes only static listener/protocol facts and no credentials,
            // routes or model names. It lets clients distinguish "nothing is listening" from auth/route errors.
            if (head.method == "GET" && head.path == "/health") {
                writer.json(200, jsonObject("status" to "running", "runtime" to "kotlin-native", "network" to "loopback-only",
                    "protocols" to JSONArray(listOf("anthropic-messages", "openai-chat-completions")), "streamMode" to "buffered-final").toString()); return
            }
            val provided = listOfNotNull(head.headers["x-api-key"], head.headers["authorization"]?.removePrefix("Bearer "))
            if (provided.isEmpty() || provided.any { !MessageDigest.isEqual(it.toByteArray(), token.toByteArray()) })
                throw WireFailure(401, "UNAUTHENTICATED", "请使用本机生成的网关客户端令牌，不是上游 API Key")
            if (head.headers["expect"] != null) writer.interim()
            if (head.method == "GET" && head.path == "/v1/models") {
                val models = ModelProtocol.entries.mapNotNull { profiles.read(it)?.model }.distinct()
                writer.json(200, jsonObject("object" to "list", "data" to JSONArray(models.map {
                    jsonObject("id" to it, "object" to "model", "created" to 0, "owned_by" to "configured-upstream")
                })).toString()); return
            }
            if (head.path == "/v1/responses") throw WireFailure(501, "RESPONSES_NOT_IMPLEMENTED", "本版不是 OpenAI Responses 适配器；请勿宣称支持 Codex Responses")
            if (head.method != "POST" || head.path !in setOf("/v1/messages", "/v1/messages/count_tokens", "/v1/chat/completions"))
                throw WireFailure(404, "ENDPOINT_NOT_FOUND", "此端点未实现")
            val body = try { JSONObject(HttpFraming.body(input, head.length)) }
                catch (_: Exception) { throw WireFailure(400, "INVALID_JSON", "请求不是完整 JSON 对象") }
            val settings = profiles.read(protocol) ?: throw WireFailure(503, "ROUTE_NOT_CONFIGURED", "请先在手机保存对应协议的模型配置")
            val principal = Principal("local-client:${preferences.state.value.gatewayEpoch}", Origin.GATEWAY)
            val modelWire = wire(protocol)
            val version = head.headers["anthropic-version"]?.takeIf { Regex("\\d{4}-\\d{2}-\\d{2}").matches(it) } ?: "2023-06-01"
            val beta = head.headers["anthropic-beta"]
            if (head.path.endsWith("/count_tokens")) {
                val prepared = engine.prepare(body, modelWire, principal, settings, forTokenCount = true).apply {
                    remove("stream"); remove("max_tokens"); remove("max_completion_tokens")
                }
                val result = modelHttp.request(settings, prepared, countTokens = true, anthropicVersion = version, beta = beta, context = RequestContext(requestId, "gateway"))
                writer.json(200, result.toString()); return
            }
            val stream = body.optBoolean("stream", false)
            val result = if (stream) coroutineScope {
                writer.startSse(requestId)
                val heartbeat = launch(Dispatchers.IO) {
                    while (isActive) { delay(5000); writer.frame(": andromorph waiting; buffered-final\n\n") }
                }
                try {
                    engine.run(body, modelWire, principal, settings, requestId) { request -> modelHttp.request(settings, request, anthropicVersion = version, beta = beta, context = RequestContext(requestId, "gateway")) }
                } finally { heartbeat.cancelAndJoin() }
            } else engine.run(body, modelWire, principal, settings, requestId) { request -> modelHttp.request(settings, request, anthropicVersion = version, beta = beta, context = RequestContext(requestId, "gateway")) }
            mutable.update { it.copy(
                observedInputTokens = it.observedInputTokens + (result.usageTotals["input_tokens"] ?: result.usageTotals["prompt_tokens"] ?: 0),
                observedOutputTokens = it.observedOutputTokens + (result.usageTotals["output_tokens"] ?: result.usageTotals["completion_tokens"] ?: 0)) }
            if (stream) {
                val frames = SseEncoder.frames(protocol, result.response, body.optJSONObject("stream_options")?.optBoolean("include_usage", false) == true)
                frames.forEach(writer::frame)
            } else writer.json(200, result.response.toString(), mapOf("X-AndroMorph-Request-Id" to requestId,
                "X-AndroMorph-Upstream-Rounds" to result.rounds.toString(), "X-AndroMorph-Receipt-Count" to result.receiptIds.size.toString()))
        } catch (failure: CancellationException) { throw failure }
        catch (failure: Exception) {
            val error = failure as? WireFailure ?: WireFailure(502, "LOCAL_GATEWAY_FAILURE", "本机处理失败；未转发原始异常或凭据")
            val payload = gatewayErrorPayload(wire(protocol), error, requestId)
            val message = payload.toString()
            runCatching {
                if (writer.streaming) writer.frame(if (protocol == ModelProtocol.ANTHROPIC) "event: error\ndata: $message\n\n" else "data: $message\n\n")
                else if (!writer.started) writer.json(error.status, message, mapOf(
                    "X-AndroMorph-Request-Id" to requestId,
                    "X-AndroMorph-Error-Source" to payload.getJSONObject("error").getString("source"),
                    "X-AndroMorph-Error-Code" to error.code,
                ))
            }
        }
    }
    companion object { const val PORT = 8787 }
}

internal fun gatewayErrorPayload(wire: ModelWire, failure: WireFailure, requestId: String): JSONObject {
    val source = if (failure.code.startsWith("UPSTREAM_")) "upstream" else "local-gateway"
    val payload = wire.error(failure.code, failure.safeDetail)
    val detail = payload.optJSONObject("error") ?: JSONObject().also { payload.put("error", it) }
    detail.put("code", failure.code).put("source", source).put("request_id", requestId)
    if (wire.protocol == ModelProtocol.ANTHROPIC) {
        detail.put("type", if (source == "upstream" || failure.status >= 500) "api_error" else "invalid_request_error")
        payload.put("type", "error")
    }
    payload.put("request_id", requestId)
    return payload
}
