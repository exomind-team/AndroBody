package org.exomind.androbody.nativeapp.model

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.exomind.androbody.nativeapp.config.*
import org.exomind.androbody.nativeapp.core.WireFailure
import org.exomind.androbody.nativeapp.diagnostics.*
import org.exomind.androbody.nativeapp.model.endpoint.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ModelCatalogReply(val models: List<String>, val traceId: String, val endpoint: String, val attempts: Int)

data class ModelReply(val body: JSONObject, val traceId: String, val endpoint: String, val attempts: Int) {
    override fun toString() = "ModelReply(traceId=$traceId, endpoint=${DiagnosticRedaction.url(endpoint)}, attempts=$attempts, body=<private>)"
}

/** One transport for native, gateway, count_tokens and the explicit connection test. */
class ModelHttpClient(private val http: OkHttpClient = OkHttpClient.Builder()
    .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).callTimeout(95, TimeUnit.SECONDS).build(),
    private val traces: TraceSink = NoTrace,
    private val cache: EndpointCache = MemoryEndpointCache(),
    private val diagnostics: DiagnosticStore? = null,
    private val requestBudgetMs: Long = TOTAL_REQUEST_MS,
) {
    init { require(requestBudgetMs in 1..TOTAL_REQUEST_MS) }
    suspend fun request(settings: ModelSettings, body: JSONObject, countTokens: Boolean = false,
        anthropicVersion: String = "2023-06-01", beta: String? = null,
        context: RequestContext = RequestContext()): JSONObject =
        requestWithEvidence(settings, body, countTokens, anthropicVersion, beta, context).body

    /** Explicit, read-only GET. It never sends a prompt, changes a model, or saves a profile. */
    suspend fun listModels(settings: ModelSettings, context: RequestContext = RequestContext(origin = "model-catalog")): ModelCatalogReply {
        val trace = RequestTrace(traces, context)
        val secrets = listOf(settings.apiKey)
        val started = System.nanoTime()
        trace.event("catalog_start", mapOf("protocol" to settings.protocol.name,
            "configuredAddress" to DiagnosticRedaction.url(settings.baseUrl, secrets), "method" to "GET"))
        try {
            settings.validateForModelCatalog()
            val plan = EndpointPolicy.modelListPlan(settings)
            val cacheKey = sha256(("catalog:" + plan.rulesVersion + "\u0000" + settings.protocol.name + "\u0000" +
                settings.baseUrl.trim() + "\u0000" + settings.endpointMode + "\u0000" + settings.apiKey)
                .toByteArray(Charsets.UTF_8))
            val discovery = withTimeoutOrNull(requestBudgetMs) {
                EndpointDiscovery(cache).execute(plan, cacheKey, observe = { stage, fields ->
                    trace.event(stage, fields.mapValues { DiagnosticRedaction.text(it.value, secrets, 4096) })
                }) { candidate, attempt ->
                    currentCoroutineContext().ensureActive()
                    val remaining = requestBudgetMs - (System.nanoTime() - started) / 1_000_000L
                    if (remaining <= 0) AttemptResult.Failure(AttemptFailure(FailureKind.BUDGET_EXHAUSTED))
                    else performModelList(settings, candidate, attempt, remaining, trace, secrets)
                }
            } ?: throw WireFailure(504, "UPSTREAM_REQUEST_BUDGET", "模型目录探查时间预算耗尽；没有继续尝试。诊断 ${trace.id}")
            trace.event("catalog_completed", mapOf("endpoint" to DiagnosticRedaction.url(discovery.endpoint, secrets),
                "attempts" to discovery.attempts.toString(), "modelCount" to discovery.value.size.toString()))
            return ModelCatalogReply(discovery.value, trace.id, discovery.endpoint, discovery.attempts)
        } catch (cancelled: CancellationException) {
            trace.event("catalog_cancelled", mapOf("meaning" to "No prompt was sent; no automatic retry"))
            throw cancelled
        } catch (failure: DiscoveryFailure) {
            val last = failure.failures.lastOrNull()?.second ?: AttemptFailure(FailureKind.INVALID_RESPONSE)
            trace.event("catalog_failed", mapOf("kind" to last.kind.name, "attempts" to failure.failures.size.toString()))
            throw WireFailure(last.httpStatus?.takeIf { it in 400..599 } ?: 502, "CATALOG_${last.kind.name}",
                "${explain(last.kind)}；已尝试 ${failure.failures.size} 个同源路径。诊断 ${trace.id}")
        } catch (failure: WireFailure) {
            trace.event("catalog_failed", mapOf("code" to failure.code))
            throw failure
        } catch (failure: Exception) {
            trace.event("catalog_failed", mapOf("class" to failure.javaClass.name))
            if (failure is IllegalArgumentException) throw WireFailure(400, "MODEL_CONFIG_INVALID", "模型目录探查配置无效；诊断 ${trace.id}")
            throw WireFailure(500, "CATALOG_INTERNAL_FAILURE", "模型目录探查未完成；诊断 ${trace.id}")
        }
    }

    suspend fun requestWithEvidence(settings: ModelSettings, body: JSONObject, countTokens: Boolean = false,
        anthropicVersion: String = "2023-06-01", beta: String? = null,
        context: RequestContext = RequestContext(), onText: ((String) -> Unit)? = null): ModelReply {
        val trace = RequestTrace(traces, context)
        val secrets = listOf(settings.apiKey)
        val started = System.nanoTime()
        trace.event("request_start", mapOf("protocol" to settings.protocol.name,
            "configuredAddress" to DiagnosticRedaction.url(settings.baseUrl, secrets),
            "requestedModel" to DiagnosticRedaction.text(settings.model, secrets),
            "operation" to if (countTokens) "count_tokens" else if (body.optBoolean("stream", false)) "generate_stream" else "generate"))
        diagnostics?.let { trace.event("android_context", it.environmentFields()) }
        val captureEnabled = diagnostics?.beginCapture(trace.id, context.origin) == true
        try {
            settings.validate()
            require(anthropicVersion.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"))) { "Invalid Anthropic version header" }
            require(beta == null || (beta.length <= 1024 && beta.none { it.isISOControl() })) { "Invalid beta header" }
            val plan = EndpointPolicy.plan(settings, countTokens)
            val payload = body.toString().toByteArray(Charsets.UTF_8)
            if (payload.size > MAX_REQUEST_BYTES) throw WireFailure(413, "REQUEST_TOO_LARGE", "模型请求超过传输预算")
            val streaming = body.optBoolean("stream", false)
            if (streaming != (onText != null) || (streaming && countTokens))
                throw WireFailure(422, "STREAM_CALLBACK_REQUIRED", "流式响应只支持原生增量接收路径；未将 SSE 当作完整 JSON 解析")
            trace.event("payload_serialized", mapOf("bytes" to payload.size.toString(), "sha256" to sha256(payload),
                "structure" to JsonTraceView.structure(payload, secrets).take(12_000)))
            val cacheKey = sha256((plan.rulesVersion + "\u0000" + settings.protocol.name + "\u0000" +
                settings.baseUrl.trim() + "\u0000" + settings.endpointMode + "\u0000" + settings.model + "\u0000" + anthropicVersion + "\u0000" + beta.orEmpty() + "\u0000" + settings.apiKey + "\u0000" +
                plan.operation.name).toByteArray()) // internal only; never exported or logged
            val discovery = withTimeoutOrNull(requestBudgetMs) {
                EndpointDiscovery(cache).execute(plan, cacheKey, observe = { stage, fields ->
                    trace.event(stage, fields.mapValues { DiagnosticRedaction.text(it.value, secrets, 8192) })
                }) { candidate, attempt ->
                    currentCoroutineContext().ensureActive()
                    val remaining = requestBudgetMs - (System.nanoTime() - started) / 1_000_000L
                    if (remaining <= 0) AttemptResult.Failure(AttemptFailure(FailureKind.BUDGET_EXHAUSTED))
                    else perform(settings, candidate, attempt, payload, body, countTokens,
                        anthropicVersion, beta, remaining, trace, secrets, captureEnabled, onText)
                }
            }
            if (discovery == null) throw WireFailure(504, "UPSTREAM_REQUEST_BUDGET", "模型传输时间预算耗尽；没有继续尝试。诊断 ${trace.id}")
            trace.event("request_completed", mapOf("endpoint" to DiagnosticRedaction.url(discovery.endpoint, secrets),
                "attempts" to discovery.attempts.toString(), "cacheHit" to discovery.cacheHit.toString(),
                "responseModel" to DiagnosticRedaction.text(discovery.value.optString("model", "not_reported"), secrets),
                "meaning" to "HTTP and protocol validated; not Android task completion"))
            return ModelReply(discovery.value, trace.id, discovery.endpoint, discovery.attempts)
        } catch (cancelled: CancellationException) {
            trace.event("request_cancelled", mapOf("class" to cancelled.javaClass.name,
                "meaning" to "No automatic replay; already submitted upstream work may still have run"))
            throw cancelled
        } catch (failure: DiscoveryFailure) {
            val last = failure.failures.lastOrNull()?.second ?: AttemptFailure(FailureKind.INVALID_RESPONSE)
            trace.event("request_failed", mapOf("kind" to last.kind.name, "attempts" to failure.failures.size.toString()))
            throw WireFailure(last.httpStatus?.takeIf { it in 400..599 } ?: 502, "UPSTREAM_${last.kind.name}",
                "${explain(last.kind)}；已尝试 ${failure.failures.size} 个同源路径。诊断 ${trace.id}")
        } catch (failure: Exception) {
            trace.event("request_failed", mapOf("class" to failure.javaClass.name,
                "code" to ((failure as? WireFailure)?.code ?: "CONFIG_OR_INTERNAL"),
                "stack" to failure.stackTrace.take(12).joinToString("\n") { "${it.className}.${it.methodName}:${it.lineNumber}" }))
            if (failure is WireFailure) throw failure
            if (failure is IllegalArgumentException) throw WireFailure(400, "MODEL_CONFIG_INVALID", "模型配置或请求不合法；诊断 ${trace.id}")
            throw WireFailure(500, "MODEL_INTERNAL_FAILURE", "模型链内部错误；未归咎于密钥或端点，诊断 ${trace.id}")
        }
    }

    private suspend fun perform(settings: ModelSettings, candidate: EndpointCandidate, attempt: Int,
        payload: ByteArray, requestJson: JSONObject, countTokens: Boolean, anthropicVersion: String,
        beta: String?, remaining: Long, trace: RequestTrace, secrets: List<String>, captureEnabled: Boolean,
        onText: ((String) -> Unit)?): AttemptResult<JSONObject> {
        if (onText != null) return performStreaming(settings, candidate, attempt, payload, remaining, anthropicVersion,
            beta, trace, secrets, captureEnabled, onText)
        trace.event("attempt_start", mapOf("url" to DiagnosticRedaction.url(candidate.url, secrets), "reason" to candidate.reason.name,
            "method" to "POST", "remainingMs" to remaining.toString()), attempt)
        val builder = Request.Builder().url(candidate.url).header("Accept", "application/json")
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
        when (settings.protocol) {
            ModelProtocol.ANTHROPIC -> {
                builder.header("x-api-key", settings.apiKey).header("anthropic-version", anthropicVersion)
                if (beta != null) builder.header("anthropic-beta", beta)
            }
            ModelProtocol.OPENAI -> builder.header("Authorization", "Bearer ${settings.apiKey}")
        }
        val client = http.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .callTimeout(remaining, TimeUnit.MILLISECONDS).connectTimeout(minOf(remaining, 15_000L), TimeUnit.MILLISECONDS)
            .readTimeout(minOf(remaining, 90_000L), TimeUnit.MILLISECONDS)
            .eventListener(HttpTraceObserver(trace, attempt, secrets))
            .addNetworkInterceptor { chain ->
                val actual = chain.request()
                // A new interceptor cannot silently send credentials to a different route/origin.
                if (actual.url != builder.build().url) throw IOException("Request endpoint changed after resolution")
                val body = actual.body ?: throw IOException("Missing request body")
                if (body.isDuplex() || body.contentLength() !in 0L..MAX_REQUEST_BYTES.toLong()) throw IOException("Unsupported request-body ownership")
                val observed = ObservedRequestBody(body) { prefix, bytes, digest, complete ->
                    trace.event("request_body_observed", mapOf("bytes" to bytes.toString(), "sha256" to digest,
                        "complete" to complete.toString(), "matchesSerialized" to (complete && bytes == payload.size.toLong() && digest == sha256(payload)).toString(),
                        "boundary" to "OkHttp request-body sink; not server acknowledgement"), attempt)
                    if (captureEnabled) diagnostics?.recordCapture(trace.id, attempt, "request_body_prefix", prefix, secrets)
                }
                chain.proceed(actual.newBuilder().method(actual.method, observed).build())
            }.build()
        val raw = try { await(client.newCall(builder.build()), trace, attempt, secrets) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            val kind = when (failure) {
                is SSLException -> FailureKind.TLS
                is java.io.InterruptedIOException -> FailureKind.TIMEOUT
                is ResponseTooLarge -> FailureKind.RESPONSE_TOO_LARGE
                else -> FailureKind.NETWORK
            }
            trace.event("attempt_failed", mapOf("kind" to kind.name, "class" to failure.javaClass.name,
                "causeClasses" to generateSequence<Throwable>(failure) { it.cause }.take(8).joinToString(" -> ") { it.javaClass.name },
                "reason" to "No automatic generation replay after ambiguous transport outcome"), attempt)
            return AttemptResult.Failure(AttemptFailure(kind))
        }
        trace.event("response_body_read", mapOf("httpStatus" to raw.status.toString(), "bytes" to raw.body.size.toString(),
            "sha256" to sha256(raw.body), "truncated" to raw.truncated.toString(), "contentType" to raw.contentType), attempt)
        if (captureEnabled) diagnostics?.recordCapture(trace.id, attempt, "response_body", raw.body, secrets)
        val json = try { JSONObject(String(raw.body, Charsets.UTF_8)) } catch (_: Exception) { null }
        if (raw.status !in 200..299) {
            val error = json?.optJSONObject("error") ?: json
            val code = (error?.opt("code") as? String) ?: (error?.opt("type") as? String)
            // A cut-off error may hide a model/auth failure. Do not use it to justify another credentialed POST.
            val failure = if (raw.truncated) AttemptFailure(FailureKind.INCOMPLETE_ERROR, raw.status)
                else EndpointFallback.classify(raw.status, code, error?.optString("message") ?: String(raw.body, Charsets.UTF_8))
            trace.event("http_rejected", mapOf("kind" to failure.kind.name, "providerCode" to DiagnosticRedaction.text(code.orEmpty(), secrets, 128),
                "message" to JsonTraceView.errorMessage(error, requestJson, secrets),
                "bodyShape" to if (json == null) "non-json (${raw.body.size} bytes)" else JsonTraceView.structure(raw.body, secrets).take(8192)), attempt)
            return AttemptResult.Failure(failure)
        }
        val valid = json != null && json.optJSONObject("error") == null && when {
            countTokens -> json.opt("input_tokens") is Number && json.optLong("input_tokens", -1L) >= 0
            settings.protocol == ModelProtocol.ANTHROPIC -> json.optJSONArray("content") != null && json.optString("stop_reason").isNotBlank()
            else -> json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message") != null
        }
        trace.event("protocol_validated", mapOf("valid" to valid.toString(), "expected" to if (countTokens) "input_tokens:number" else settings.protocol.name,
            "responseShape" to JsonTraceView.structure(raw.body, secrets).take(8192)), attempt)
        return if (valid) AttemptResult.Success(requireNotNull(json)) else AttemptResult.Failure(AttemptFailure(FailureKind.INVALID_RESPONSE, raw.status))
    }

    private suspend fun performStreaming(settings: ModelSettings, candidate: EndpointCandidate, attempt: Int,
        payload: ByteArray, remaining: Long, anthropicVersion: String, beta: String?,
        trace: RequestTrace, secrets: List<String>, captureEnabled: Boolean,
        onText: (String) -> Unit): AttemptResult<JSONObject> {
        trace.event("attempt_start", mapOf("url" to DiagnosticRedaction.url(candidate.url, secrets),
            "reason" to candidate.reason.name, "method" to "POST", "streaming" to "true",
            "remainingMs" to remaining.toString()), attempt)
        val builder = Request.Builder().url(candidate.url).header("Accept", "text/event-stream")
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
        when (settings.protocol) {
            ModelProtocol.ANTHROPIC -> {
                builder.header("x-api-key", settings.apiKey).header("anthropic-version", anthropicVersion)
                if (beta != null) builder.header("anthropic-beta", beta)
            }
            ModelProtocol.OPENAI -> builder.header("Authorization", "Bearer " + settings.apiKey)
        }
        val client = http.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .callTimeout(remaining, TimeUnit.MILLISECONDS).connectTimeout(minOf(remaining, 15_000L), TimeUnit.MILLISECONDS)
            .readTimeout(minOf(remaining, 90_000L), TimeUnit.MILLISECONDS)
            .eventListener(HttpTraceObserver(trace, attempt, secrets))
            .addNetworkInterceptor { chain ->
                val actual = chain.request()
                if (actual.url != builder.build().url) throw IOException("Request endpoint changed after resolution")
                val body = actual.body ?: throw IOException("Missing request body")
                if (body.isDuplex() || body.contentLength() !in 0L..MAX_REQUEST_BYTES.toLong())
                    throw IOException("Unsupported request-body ownership")
                val observed = ObservedRequestBody(body) { prefix, bytes, digest, complete ->
                    trace.event("request_body_observed", mapOf("bytes" to bytes.toString(), "sha256" to digest,
                        "complete" to complete.toString(), "matchesSerialized" to
                            (complete && bytes == payload.size.toLong() && digest == sha256(payload)).toString(),
                        "boundary" to "OkHttp request-body sink; not server acknowledgement"), attempt)
                    if (captureEnabled) diagnostics?.recordCapture(trace.id, attempt, "request_body_prefix", prefix, secrets)
                }
                chain.proceed(actual.newBuilder().method(actual.method, observed).build())
            }.build()
        val raw = try { awaitStream(client.newCall(builder.build()), settings.protocol, trace, attempt, secrets, onText) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: WireFailure) {
            trace.event("stream_rejected", mapOf("code" to failure.code), attempt)
            return AttemptResult.Failure(AttemptFailure(FailureKind.INVALID_RESPONSE))
        }
        catch (failure: Exception) {
            val kind = when (failure) {
                is SSLException -> FailureKind.TLS
                is java.io.InterruptedIOException -> FailureKind.TIMEOUT
                is ResponseTooLarge -> FailureKind.RESPONSE_TOO_LARGE
                else -> FailureKind.NETWORK
            }
            trace.event("attempt_failed", mapOf("kind" to kind.name, "class" to failure.javaClass.name,
                "reason" to "Streaming stopped; generation was not automatically replayed"), attempt)
            return AttemptResult.Failure(AttemptFailure(kind))
        }
        trace.event("response_body_read", mapOf("httpStatus" to raw.status.toString(),
            "bytes" to raw.body.size.toString(), "sha256" to sha256(raw.body), "truncated" to raw.truncated.toString(),
            "contentType" to raw.contentType), attempt)
        if (captureEnabled) diagnostics?.recordCapture(trace.id, attempt, "response_body", raw.body, secrets)
        if (raw.status !in 200..299) {
            val json = try { JSONObject(String(raw.body, Charsets.UTF_8)) } catch (_: Exception) { null }
            val error = json?.optJSONObject("error") ?: json
            val code = (error?.opt("code") as? String) ?: (error?.opt("type") as? String)
            val failure = if (raw.truncated) AttemptFailure(FailureKind.INCOMPLETE_ERROR, raw.status)
                else EndpointFallback.classify(raw.status, code, error?.optString("message") ?: String(raw.body, Charsets.UTF_8))
            trace.event("http_rejected", mapOf("kind" to failure.kind.name,
                "providerCode" to DiagnosticRedaction.text(code.orEmpty(), secrets, 128),
                "message" to JsonTraceView.errorMessage(error, JSONObject(), secrets)), attempt)
            return AttemptResult.Failure(failure)
        }
        if (!raw.contentType.contains("text/event-stream", ignoreCase = true) || raw.truncated || raw.response == null) {
            trace.event("protocol_validated", mapOf("valid" to "false", "expected" to "text/event-stream with terminal event"), attempt)
            return AttemptResult.Failure(AttemptFailure(if (raw.truncated) FailureKind.RESPONSE_TOO_LARGE else FailureKind.INVALID_RESPONSE, raw.status))
        }
        trace.event("protocol_validated", mapOf("valid" to "true",
            "responseShape" to "SSE event sequence validated; event payload values omitted",
            "bytes" to raw.body.size.toString()), attempt)
        return AttemptResult.Success(raw.response)
    }

    private class RawStreamResponse(
        val status: Int, val body: ByteArray, val contentType: String,
        val truncated: Boolean, val response: JSONObject?,
    )

    private suspend fun awaitStream(call: Call, protocol: ModelProtocol, trace: RequestTrace,
        attempt: Int, secrets: List<String>, onText: (String) -> Unit): RawStreamResponse =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                trace.event("cancel_requested", attempt = attempt)
                call.cancel()
            }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            val responseBody = response.body ?: throw IOException("Missing response body")
                            val contentType = DiagnosticRedaction.text(response.header("Content-Type").orEmpty(), secrets, 256)
                            val output = ByteArrayOutputStream()
                            var truncated = false
                            var assembled: JSONObject? = null
                            val source = responseBody.source()
                            if (response.isSuccessful && contentType.contains("text/event-stream", ignoreCase = true)) {
                                if (responseBody.contentLength() > MAX_RESPONSE_BYTES) throw ResponseTooLarge()
                                val parser = SseModelAssembler(protocol, onText)
                                val line = ByteArrayOutputStream()
                                var eventBytes = 0
                                while (continuation.isActive && !parser.isComplete && !source.exhausted()) {
                                    val byte = source.readByte().toInt() and 0xff
                                    output.write(byte)
                                    if (output.size() > MAX_RESPONSE_BYTES) throw ResponseTooLarge()
                                    if (byte == 10) {
                                        var bytes = line.toByteArray()
                                        line.reset()
                                        if (bytes.isNotEmpty() && bytes.last() == 13.toByte()) bytes = bytes.copyOf(bytes.size - 1)
                                        val text = String(bytes, Charsets.UTF_8)
                                        if (text.isEmpty()) eventBytes = 0 else eventBytes += bytes.size
                                        if (eventBytes > MAX_SSE_EVENT_BYTES) throw ResponseTooLarge()
                                        parser.acceptLine(text)
                                        if (parser.isComplete) break
                                    } else {
                                        line.write(byte)
                                        if (line.size() > MAX_SSE_LINE_BYTES) throw ResponseTooLarge()
                                    }
                                }
                                if (!continuation.isActive) { call.cancel(); return }
                                if (line.size() > 0) parser.acceptLine(String(line.toByteArray(), Charsets.UTF_8))
                                assembled = parser.finish()
                            } else {
                                val limit = MAX_ERROR_BYTES
                                val bytes = ByteArray(8192)
                                while (continuation.isActive) {
                                    val count = source.read(bytes, 0, minOf(bytes.size, limit - output.size() + 1))
                                    if (count < 0) break
                                    val keep = minOf(count, limit - output.size())
                                    if (keep > 0) output.write(bytes, 0, keep)
                                    if (count > keep) { truncated = true; break }
                                }
                                if (!continuation.isActive) { call.cancel(); return }
                            }
                            val raw = RawStreamResponse(response.code, output.toByteArray(), contentType, truncated, assembled)
                            if (continuation.isActive) continuation.resume(raw)
                        } catch (failure: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(failure)
                        }
                    }
                }
            })
        }

    private suspend fun performModelList(settings: ModelSettings, candidate: EndpointCandidate, attempt: Int,
        remaining: Long, trace: RequestTrace, secrets: List<String>): AttemptResult<List<String>> {
        trace.event("attempt_start", mapOf("url" to DiagnosticRedaction.url(candidate.url, secrets),
            "reason" to candidate.reason.name, "method" to "GET", "remainingMs" to remaining.toString()), attempt)
        val builder = Request.Builder().url(candidate.url).header("Accept", "application/json").get()
        when (settings.protocol) {
            ModelProtocol.ANTHROPIC -> builder.header("x-api-key", settings.apiKey).header("anthropic-version", "2023-06-01")
            ModelProtocol.OPENAI -> builder.header("Authorization", "Bearer " + settings.apiKey)
        }
        val client = http.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .callTimeout(remaining, TimeUnit.MILLISECONDS).connectTimeout(minOf(remaining, 15_000L), TimeUnit.MILLISECONDS)
            .readTimeout(minOf(remaining, 30_000L), TimeUnit.MILLISECONDS)
            .eventListener(HttpTraceObserver(trace, attempt, secrets)).build()
        val raw = try { await(client.newCall(builder.build()), trace, attempt, secrets, MAX_CATALOG_BYTES) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            val kind = when (failure) {
                is SSLException -> FailureKind.TLS
                is java.io.InterruptedIOException -> FailureKind.TIMEOUT
                is ResponseTooLarge -> FailureKind.RESPONSE_TOO_LARGE
                else -> FailureKind.NETWORK
            }
            trace.event("attempt_failed", mapOf("kind" to kind.name, "class" to failure.javaClass.name,
                "reason" to "Read-only model listing; no prompt or generation request"), attempt)
            return AttemptResult.Failure(AttemptFailure(kind))
        }
        trace.event("catalog_response_read", mapOf("httpStatus" to raw.status.toString(),
            "bytes" to raw.body.size.toString(), "sha256" to sha256(raw.body), "truncated" to raw.truncated.toString()), attempt)
        val json = try { JSONObject(String(raw.body, Charsets.UTF_8)) } catch (_: Exception) { null }
        if (raw.status !in 200..299) {
            val error = json?.optJSONObject("error") ?: json
            val code = (error?.opt("code") as? String) ?: (error?.opt("type") as? String)
            val failure = when {
                raw.truncated -> AttemptFailure(FailureKind.INCOMPLETE_ERROR, raw.status)
                raw.status == 404 -> AttemptFailure(FailureKind.ROUTE_NOT_FOUND, raw.status, code)
                else -> EndpointFallback.classify(raw.status, code, error?.optString("message") ?: String(raw.body, Charsets.UTF_8))
            }
            trace.event("http_rejected", mapOf("kind" to failure.kind.name,
                "providerCode" to DiagnosticRedaction.text(code.orEmpty(), secrets, 128),
                "message" to JsonTraceView.errorMessage(error, JSONObject(), secrets)), attempt)
            return AttemptResult.Failure(failure)
        }
        val models = json?.let(::modelIds)?.filterNot { modelId ->
            secrets.any { secret -> secret.length >= 8 && modelId.contains(secret) }
        }
        trace.event("catalog_validated", mapOf("valid" to (models != null).toString(),
            "modelCount" to (models?.size ?: 0).toString()), attempt)
        return if (models != null) AttemptResult.Success(models)
            else AttemptResult.Failure(AttemptFailure(FailureKind.INVALID_RESPONSE, raw.status))
    }

    private fun modelIds(value: JSONObject): List<String>? {
        if (value.optJSONObject("error") != null) return null
        val entries = value.optJSONArray("data") ?: value.optJSONArray("models") ?: return null
        if (entries.length() > MAX_MODEL_IDS) return null
        val ids = (0 until entries.length()).map { index ->
            val item = entries.opt(index)
            val id = when (item) {
                is String -> item
                is JSONObject -> item.optString("id").ifBlank { item.optString("name") }
                else -> ""
            }
            if (id.length !in 1..MAX_MODEL_ID_LENGTH || id.any(Char::isISOControl)) return null
            id
        }
        return ids.distinct()
    }

    private class RawResponse(val status: Int, val body: ByteArray, val contentType: String, val truncated: Boolean)
    private class ResponseTooLarge : IOException("Response exceeds configured byte budget")
    private suspend fun await(call: Call, trace: RequestTrace, attempt: Int, secrets: List<String>,
        maxResponseBytes: Int = MAX_RESPONSE_BYTES): RawResponse = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { trace.event("cancel_requested", attempt = attempt); call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        val source = response.body ?: throw IOException("Missing response body")
                        val limit = if (response.isSuccessful) maxResponseBytes else MAX_ERROR_BYTES
                        if (response.isSuccessful && source.contentLength() > limit) throw ResponseTooLarge()
                        val output = ByteArrayOutputStream()
                        var truncated = false
                        source.byteStream().use { input ->
                            val chunk = ByteArray(8192)
                            while (true) {
                                if (!continuation.isActive) { call.cancel(); return }
                                val count = input.read(chunk, 0, minOf(chunk.size, limit - output.size() + 1))
                                if (count < 0) break
                                val keep = minOf(count, limit - output.size())
                                if (keep > 0) output.write(chunk, 0, keep)
                                if (count > keep) {
                                    if (response.isSuccessful) throw ResponseTooLarge()
                                    truncated = true; break
                                }
                            }
                        }
                        val raw = RawResponse(response.code, output.toByteArray(),
                            DiagnosticRedaction.text(response.header("Content-Type").orEmpty(), secrets, 256), truncated)
                        if (continuation.isActive) continuation.resume(raw)
                    } catch (failure: Exception) { if (continuation.isActive) continuation.resumeWithException(failure) }
                }
            }
        })
    }
    companion object {
        const val MAX_REQUEST_BYTES = 2 * 1024 * 1024
        const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
        const val MAX_CATALOG_BYTES = 512 * 1024
        const val MAX_SSE_LINE_BYTES = 64 * 1024
        const val MAX_SSE_EVENT_BYTES = 512 * 1024
        const val MAX_MODEL_IDS = 500
        const val MAX_MODEL_ID_LENGTH = 200
        const val MAX_ERROR_BYTES = 16 * 1024
        const val TOTAL_REQUEST_MS = 90_000L
        private fun explain(kind: FailureKind): String = when (kind) {
            FailureKind.ROUTE_NOT_FOUND, FailureKind.METHOD_NOT_ALLOWED -> "同源候选路径均未匹配；请查看实际请求地址和服务器回执"
            FailureKind.AUTHENTICATION, FailureKind.ACCESS_DENIED -> "鉴权或权限被拒绝；没有继续试发密钥"
            FailureKind.MODEL_NOT_FOUND -> "服务返回模型不存在；未擅自换模型"
            FailureKind.RATE_LIMIT -> "上游限流；未自动重复计费请求"
            FailureKind.BILLING -> "上游账户/计费限制"
            FailureKind.INCOMPLETE_ERROR -> "错误回执超过诊断预算，原因不完整；未据此继续试发"
            FailureKind.INVALID_RESPONSE -> "HTTP 成功但响应不是所选协议；没有误报连接成功"
            FailureKind.TLS -> "TLS 校验失败；没有关闭证书验证"
            FailureKind.TIMEOUT, FailureKind.NETWORK -> "网络或超时失败，服务端是否已处理未知；未自动重放"
            FailureKind.REDIRECT -> "上游返回重定向；未向另一个地址转发密钥"
            else -> "模型请求未完成，请查看分阶段诊断"
        }
    }
}
