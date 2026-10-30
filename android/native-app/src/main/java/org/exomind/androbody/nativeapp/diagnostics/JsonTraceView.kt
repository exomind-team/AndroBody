package org.exomind.androbody.nativeapp.diagnostics

import org.json.JSONArray
import org.json.JSONObject

/** Metadata mode preserves protocol structure, not private prompt/tool contents. */
object JsonTraceView {
    private val visibleStrings = setOf("model", "role", "type", "finish_reason", "stop_reason", "object")
    // Token *budgets* and usage counters are protocol facts, not credentials.
    private val secretKeys = setOf("authorization", "proxy-authorization", "cookie", "set-cookie",
        "api_key", "apikey", "x-api-key", "access_token", "refresh_token", "password", "secret", "token")
    private fun secretKey(key: String) = key.lowercase() in secretKeys
    private val opaqueSubtrees = setOf("input", "arguments", "parameters", "input_schema", "metadata", "content", "text")
    private val protocolScalars = setOf("max_tokens", "max_completion_tokens", "input_tokens", "output_tokens",
        "prompt_tokens", "completion_tokens", "total_tokens", "cached_tokens", "cache_read_input_tokens",
        "cache_creation_input_tokens", "stream", "index", "temperature", "top_p")
    fun structure(raw: ByteArray, secrets: List<String>): String = try {
        val parsed = JSONObject(String(raw, Charsets.UTF_8))
        project(parsed, "", secrets, 0).toString()
    } catch (_: Exception) { "<non-object body; bytes=${raw.size}; sha256=${sha256(raw)}>" }

    private fun project(value: Any?, key: String, secrets: List<String>, depth: Int): Any {
        if (value == null || value == JSONObject.NULL) return JSONObject.NULL
        if (depth > 14) return "<depth-limit>"
        if (secretKey(key)) return "<redacted>"
        if (key in opaqueSubtrees) return "<opaque; chars=${value.toString().length}>"
        return when (value) {
            is JSONObject -> JSONObject().apply { value.keys().asSequence().take(160).forEach { name ->
                put(DiagnosticRedaction.text(name, secrets, 120), project(value.opt(name), name, secrets, depth + 1))
            } }
            is JSONArray -> JSONArray().apply {
                for (i in 0 until minOf(value.length(), 40)) put(project(value.opt(i), key, secrets, depth + 1))
                if (value.length() > 40) put("<${value.length() - 40} more items>")
            }
            is String -> if (key in visibleStrings) DiagnosticRedaction.text(value, secrets, 256)
                else "<text; chars=${value.length}>"
            is Number, is Boolean -> if (key in protocolScalars) value else "<scalar>"
            else -> "<value>"
        }
    }

    /** No server message is trusted. Remove exact prompt echoes before retaining a short diagnostic. */
    fun errorMessage(error: JSONObject?, request: JSONObject, secrets: List<String>): String {
        val message = error?.optString("message").orEmpty()
        val privateValues = mutableListOf<String>()
        fun collect(value: Any?, key: String = "", depth: Int = 0) {
            if (depth > 20 || privateValues.size > 2048) return
            when (value) {
                is JSONObject -> value.keys().forEach { collect(value.opt(it), it, depth + 1) }
                is JSONArray -> for (i in 0 until value.length()) collect(value.opt(i), key, depth + 1)
                is String -> if (value.isNotEmpty() && key !in visibleStrings) privateValues += value
            }
        }
        collect(request)
        return DiagnosticRedaction.text(message, secrets + privateValues, 1024)
    }

    /** Explicit one-request content capture: still removes keys and credential-shaped strings. */
    fun capture(raw: ByteArray, secrets: List<String>): String {
        fun clean(value: Any?, depth: Int = 0): Any {
            if (depth > 24) return "<depth-limit>"
            return when (value) {
                is JSONObject -> JSONObject().apply { value.keys().forEach { key ->
                    put(DiagnosticRedaction.text(key, secrets, 256), if (secretKey(key)) "<redacted>" else clean(value.opt(key), depth + 1))
                } }
                is JSONArray -> JSONArray().apply { for (i in 0 until value.length()) put(clean(value.opt(i), depth + 1)) }
                is String -> DiagnosticRedaction.text(value, secrets, 65_536)
                null -> JSONObject.NULL
                else -> value
            }
        }
        val text = try { clean(JSONObject(String(raw, Charsets.UTF_8))).toString() }
            catch (_: Exception) { DiagnosticRedaction.text(String(raw, Charsets.UTF_8), secrets, 65_536) }
        return if (text.length > 65_536) text.take(65_536) + "\n<capture-truncated>" else text
    }
}
