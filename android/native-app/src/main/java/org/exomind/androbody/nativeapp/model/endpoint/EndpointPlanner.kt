package org.exomind.androbody.nativeapp.model.endpoint

import org.exomind.androbody.nativeapp.config.ModelProtocol
import java.net.URI
import java.util.Locale

/** AUTO changes path spelling only. It never changes origin, model, protocol or credentials. */
enum class EndpointMode { AUTO, EXACT }
enum class ModelOperation { GENERATE, COUNT_TOKENS, LIST_MODELS }
enum class CandidateReason { EXPLICIT_ENDPOINT, STANDARD_VERSION, UNVERSIONED, PROVIDER_CONVENTION }
data class EndpointInput(val protocol: ModelProtocol, val address: String,
    val allowInsecureLocal: Boolean = false, val mode: EndpointMode = EndpointMode.AUTO)
data class EndpointCandidate(val url: String, val reason: CandidateReason)
data class EndpointPlan(val configuredAddress: String, val candidates: List<EndpointCandidate>,
    val operation: ModelOperation, val rulesVersion: String = EndpointPlanner.VERSION)

/** Pure planning, shared by preview, production transport and tests. No network probe on save. */
object EndpointPlanner {
    const val VERSION = "endpoint-v2-2026-10-28"
    const val MAX_CANDIDATES = 4
    private val terminalVersion = Regex("v[0-9]+(?:beta[0-9]*)?", RegexOption.IGNORE_CASE)

    fun plan(input: EndpointInput, operation: ModelOperation = ModelOperation.GENERATE): EndpointPlan {
        require(operation != ModelOperation.COUNT_TOKENS || input.protocol == ModelProtocol.ANTHROPIC) {
            "COUNT_TOKENS_PROTOCOL: 此入口仅支持 Anthropic count_tokens"
        }
        val parsed = try { URI(input.address.trim()) } catch (_: Exception) {
            throw IllegalArgumentException("INVALID_URL: 地址必须是完整的 HTTP(S) URL")
        }
        val scheme = parsed.scheme?.lowercase(Locale.ROOT)
        val host = parsed.host?.lowercase(Locale.ROOT)?.removePrefix("[")?.removeSuffix("]")
        require(scheme in setOf("https", "http") && !host.isNullOrBlank()) { "INVALID_URL: 地址需要协议和主机" }
        require(parsed.rawUserInfo == null && parsed.rawQuery == null && parsed.rawFragment == null) {
            "URL_COMPONENTS: 地址不能携带用户名、密码、查询参数或片段；版本/测试标记使用协议设置"
        }
        require(parsed.port == -1 || parsed.port in 1..65535) { "INVALID_PORT: 端口超出范围" }
        require(scheme == "https" || (input.allowInsecureLocal && localHost(host))) {
            "INSECURE_ORIGIN: HTTP 仅允许显式授权的回环/私网 IP；其他目的地使用 HTTPS"
        }
        require(!(parsed.port == 8787 && host in setOf("localhost", "127.0.0.1", "::1"))) {
            "RECURSIVE_GATEWAY: 上游不能是本机网关"
        }
        val rawPath = parsed.rawPath.orEmpty().trimEnd('/')
        require(rawPath.none { it == '\\' || it.isISOControl() } &&
            !Regex("(?i)%2f|%5c|%00").containsMatchIn(rawPath) &&
            rawPath.split('/').none { it.replace(Regex("(?i)%2e"), ".") in setOf(".", "..") }) {
            "AMBIGUOUS_PATH: 不接受编码分隔符或路径穿越；请填写明确端点"
        }
        require(!Regex("/v1/v1(?:/|$)", RegexOption.IGNORE_CASE).containsMatchIn(rawPath)) {
            "DUPLICATE_VERSION: 检测到重复 /v1/v1，请确认基础地址"
        }
        require(!rawPath.endsWith("/responses")) { "PROTOCOL_ENDPOINT_MISMATCH: 当前未实现 Responses" }
        val hasAnthropicEndpoint = rawPath.endsWith("/messages") || rawPath.endsWith("/messages/count_tokens")
        val hasOpenAiEndpoint = rawPath.endsWith("/chat/completions")
        require(!(input.protocol == ModelProtocol.OPENAI && hasAnthropicEndpoint) &&
            !(input.protocol == ModelProtocol.ANTHROPIC && hasOpenAiEndpoint)) {
            "PROTOCOL_ENDPOINT_MISMATCH: 完整端点与所选协议不同；不会自动切换协议"
        }
        val generationSuffix = if (input.protocol == ModelProtocol.ANTHROPIC) "/messages" else "/chat/completions"
        val operationSuffix = when (operation) {
            ModelOperation.COUNT_TOKENS -> "/messages/count_tokens"
            ModelOperation.LIST_MODELS -> "/models"
            ModelOperation.GENERATE -> generationSuffix
        }
        val hasModelsEndpoint = rawPath.endsWith("/models")
        val explicit = hasAnthropicEndpoint || hasOpenAiEndpoint
        val base = when {
            rawPath.endsWith("/messages/count_tokens") -> rawPath.removeSuffix("/messages/count_tokens")
            hasModelsEndpoint -> rawPath.removeSuffix("/models")
            explicit -> rawPath.removeSuffix(generationSuffix)
            else -> rawPath
        }
        val origin = "$scheme://${parsed.rawAuthority.lowercase(Locale.ROOT)}"
        val paths = linkedMapOf<String, CandidateReason>()
        fun add(prefix: String, reason: CandidateReason) { paths.putIfAbsent(prefix + operationSuffix, reason) }
        fun standard(prefix: String) {
            if (terminalVersion.matches(prefix.substringAfterLast('/'))) {
                add(prefix, CandidateReason.STANDARD_VERSION)
                // Only v1 has a common unversioned alias. Never guess v2→v1.
                if (prefix.endsWith("/v1")) add(prefix.removeSuffix("/v1"), CandidateReason.UNVERSIONED)
            } else {
                add("$prefix/v1", CandidateReason.STANDARD_VERSION)
                add(prefix, CandidateReason.UNVERSIONED)
            }
        }
        if (explicit || (operation == ModelOperation.LIST_MODELS &&
                (hasModelsEndpoint || input.mode == EndpointMode.EXACT)))
            add(base, CandidateReason.EXPLICIT_ENDPOINT)
        if (input.mode == EndpointMode.EXACT) {
            require(explicit || operation == ModelOperation.LIST_MODELS) {
                "EXACT_REQUIRES_ENDPOINT: 精确模式需要完整 /messages 或 /chat/completions 端点"
            }
        } else {
            // Official DeepSeek documentation names /anthropic as the *base* URL.
            // Exact provider matching only: never add this prefix to arbitrary tenant routes.
            if (!explicit && base.isEmpty() && host == "api.deepseek.com" && input.protocol == ModelProtocol.ANTHROPIC) {
                add("/anthropic/v1", CandidateReason.PROVIDER_CONVENTION)
                add("/anthropic", CandidateReason.PROVIDER_CONVENTION)
            }
            standard(base)
        }
        return EndpointPlan(input.address.trim(), paths.entries.take(MAX_CANDIDATES).map {
            EndpointCandidate(URI(origin + it.key).toASCIIString(), it.value)
        }, operation)
    }

    private fun localHost(host: String): Boolean {
        if (host in setOf("localhost", "127.0.0.1", "::1")) return true
        val octets = host.split('.').map { it.toIntOrNull() }
        if (octets.size != 4 || octets.any { it == null || it !in 0..255 }) return false
        return octets[0] == 10 || (octets[0] == 192 && octets[1] == 168) ||
            (octets[0] == 172 && octets[1]!! in 16..31)
    }
}
