package org.exomind.androbody.nativeapp.diagnostics

/** Defense in depth. Do not feed free-form bodies to metadata logs in the first place. */
object DiagnosticRedaction {
    private val keyPattern = Regex("(?i)\\b(?:sk|pk|rk)[-_][a-z0-9_-]{6,}")
    private val bearerPattern = Regex("(?i)(Bearer\\s+)[^\\s\"'<>]+")
    private val namedSecret = Regex("(?i)((?:api[_-]?key|access[_-]?token|refresh[_-]?token|token|secret|password|authorization)[\"']?\\s*[=:]\\s*[\"']?)[^\\s,;\"'<>}]+")
    fun text(value: String, knownSecrets: List<String> = emptyList(), limit: Int = 2048): String {
        var output = value
        knownSecrets.filter { it.isNotEmpty() }.sortedByDescending { it.length }.forEach { output = output.replace(it, "<redacted>") }
        output = output.replace(keyPattern, "<redacted>").replace(bearerPattern, "$1<redacted>")
            .replace(namedSecret, "$1<redacted>")
        output = output.map { if (it.isISOControl() && it !in listOf('\n', '\t')) ' ' else it }.joinToString("")
        return if (output.length <= limit) output else output.take(limit) + "…<truncated>"
    }
    fun sensitiveHeader(name: String): Boolean {
        val lower = name.lowercase()
        return lower in setOf("authorization", "proxy-authorization", "cookie", "set-cookie") ||
            listOf("key", "token", "secret", "signature", "auth").any { it in lower }
    }
    fun headers(values: Map<String, String>, secrets: List<String>): Map<String, String> = values.mapValues { (name, value) ->
        if (sensitiveHeader(name)) "<present-redacted>" else if (name.lowercase() in setOf("content-type", "accept", "content-length",
                "anthropic-version", "anthropic-beta", "user-agent", "host", "x-request-id", "request-id", "retry-after", "server", "date"))
            text(value, secrets, 512) else "<present>"
    }
    fun url(raw: String, secrets: List<String> = emptyList()): String {
        val beforeFragment = raw.substringBefore('#')
        val parts = beforeFragment.split('?', limit = 2)
        val base = parts[0].replace(Regex("(https?://)[^/@]+@"), "$1<redacted>@")
        val query = if (parts.size == 2) "?" + parts[1].split('&').joinToString("&") { it.substringBefore('=') + "=<redacted>" } else ""
        return text(base + query, secrets, 4096)
    }
}
