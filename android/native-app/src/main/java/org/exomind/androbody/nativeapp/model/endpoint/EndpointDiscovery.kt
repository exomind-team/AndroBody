package org.exomind.androbody.nativeapp.model.endpoint

import java.util.concurrent.ConcurrentHashMap

enum class FailureKind {
    ROUTE_NOT_FOUND, METHOD_NOT_ALLOWED, MODEL_NOT_FOUND, AUTHENTICATION, ACCESS_DENIED,
    RATE_LIMIT, BILLING, BAD_REQUEST, SERVER_ERROR, REDIRECT, INVALID_RESPONSE,
    NETWORK, TLS, TIMEOUT, RESPONSE_TOO_LARGE, INCOMPLETE_ERROR, BUDGET_EXHAUSTED
}
data class AttemptFailure(val kind: FailureKind, val httpStatus: Int? = null, val providerCode: String? = null)
sealed interface AttemptResult<out T> {
    data class Success<T>(val value: T) : AttemptResult<T>
    data class Failure(val failure: AttemptFailure) : AttemptResult<Nothing>
}
data class DiscoveryResult<T>(val value: T, val endpoint: String, val attempts: Int, val cacheHit: Boolean)
class DiscoveryFailure(val failures: List<Pair<String, AttemptFailure>>) : Exception("Endpoint discovery stopped: ${failures.lastOrNull()?.second?.kind}")
interface EndpointCache {
    fun read(key: String): String?
    fun remember(key: String, endpoint: String)
    fun forget(key: String)
}
class MemoryEndpointCache : EndpointCache {
    private val entries = ConcurrentHashMap<String, String>()
    override fun read(key: String) = entries[key]
    override fun remember(key: String, endpoint: String) { entries[key] = endpoint }
    override fun forget(key: String) { entries.remove(key) }
}

/** Route discovery is not a general request retry. 401/429/timeout/invalid JSON never fan out. */
object EndpointFallback {
    fun mayAdvance(failure: AttemptFailure): Boolean = failure.kind in setOf(
        FailureKind.ROUTE_NOT_FOUND, FailureKind.METHOD_NOT_ALLOWED)

    /** Raw untrusted error text is used only for classification, never returned as a public error. */
    fun classify(status: Int, providerCode: String?, errorText: String): AttemptFailure {
        val text = "${providerCode.orEmpty()} $errorText".lowercase()
        val kind = when {
            status in setOf(401, 403) || listOf("invalid_api_key", "authentication_error", "invalid api key", "unauthorized").any { it in text } ->
                if (status == 403) FailureKind.ACCESS_DENIED else FailureKind.AUTHENTICATION
            status == 402 -> FailureKind.BILLING
            status == 429 -> FailureKind.RATE_LIMIT
            status == 404 && (listOf("model_not_found", "invalid_model", "model does not exist", "model not found", "unknown model", "model_not_exist").any { it in text } ||
                ("model" in text && listOf("not found", "does not exist", "not exist", "not available", "not supported").any { it in text })) -> FailureKind.MODEL_NOT_FOUND
            status == 404 -> FailureKind.ROUTE_NOT_FOUND // a candidate mismatch, NOT proof that this provider lacks a route
            status == 405 -> FailureKind.METHOD_NOT_ALLOWED
            status in 300..399 -> FailureKind.REDIRECT
            status == 408 -> FailureKind.TIMEOUT
            status >= 500 -> FailureKind.SERVER_ERROR
            else -> FailureKind.BAD_REQUEST
        }
        return AttemptFailure(kind, status, providerCode)
    }
}

/** Contains only candidate sequencing; the HTTP adapter retains ownership of cancellation and payloads. */
class EndpointDiscovery(private val cache: EndpointCache) {
    suspend fun <T> execute(plan: EndpointPlan, cacheKey: String,
        observe: (String, Map<String, String>) -> Unit,
        attempt: suspend (EndpointCandidate, Int) -> AttemptResult<T>): DiscoveryResult<T> {
        val cached = cache.read(cacheKey)?.takeIf { saved -> plan.candidates.any { it.url == saved } }
        val ordered = plan.candidates.sortedBy { if (it.url == cached) 0 else 1 }
        val failures = mutableListOf<Pair<String, AttemptFailure>>()
        observe("resolution_plan", mapOf("rules" to plan.rulesVersion, "candidateCount" to ordered.size.toString(),
            "cacheHit" to (cached != null).toString(), "candidates" to ordered.joinToString("\n") { "${it.reason}: ${it.url}" }))
        for ((offset, candidate) in ordered.withIndex()) {
            when (val outcome = attempt(candidate, offset + 1)) {
                is AttemptResult.Success -> {
                    // Only protocol-validated success reaches here. A 200 HTML page is not a winner.
                    cache.remember(cacheKey, candidate.url)
                    observe("resolution_selected", mapOf("endpoint" to candidate.url, "attempts" to (offset + 1).toString()))
                    return DiscoveryResult(outcome.value, candidate.url, offset + 1, candidate.url == cached)
                }
                is AttemptResult.Failure -> {
                    failures += candidate.url to outcome.failure
                    val advance = EndpointFallback.mayAdvance(outcome.failure) && offset + 1 < ordered.size
                    if (candidate.url == cached && EndpointFallback.mayAdvance(outcome.failure)) cache.forget(cacheKey)
                    observe("resolution_decision", mapOf("endpoint" to candidate.url, "failure" to outcome.failure.kind.name,
                        "decision" to if (advance) "NEXT_SAME_ORIGIN_PATH" else "STOP"))
                    if (!advance) throw DiscoveryFailure(failures)
                }
            }
        }
        throw DiscoveryFailure(failures)
    }
}
