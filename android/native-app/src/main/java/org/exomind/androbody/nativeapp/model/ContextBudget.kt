package org.exomind.androbody.nativeapp.model

import org.json.JSONObject

/** Broad tokenizer-free range; never presented as provider-measured usage. */
data class TokenEstimateRange(val minimum: Long, val maximum: Long) {
    init { require(minimum >= 0 && maximum >= minimum) }
}

/** Exact serialized request size plus an explicitly low-confidence input-token estimate. */
data class PreparedRequestBudget(
    val serializedBytes: Int,
    val tokenEstimate: TokenEstimateRange,
)

data class ReportedInputUsage(val tokens: Long, val field: String) {
    init { require(tokens >= 0 && field.isNotBlank()) }
}

data class ContextBudgetStatus(
    val requestBytes: Int? = null,
    val tokenEstimate: TokenEstimateRange? = null,
    val providerInputUsage: ReportedInputUsage? = null,
    val providerModel: String? = null,
    val providerProtocol: String? = null,
    val encryptedSessionBytes: Long? = null,
) {
    fun json(): JSONObject = JSONObject().apply {
        requestBytes?.let { put("requestBytes", it) }
        tokenEstimate?.let { put("tokenEstimateMin", it.minimum).put("tokenEstimateMax", it.maximum) }
        providerInputUsage?.let { put("providerInputTokens", it.tokens).put("providerInputField", it.field) }
        providerModel?.let { put("providerModel", it) }
        providerProtocol?.let { put("providerProtocol", it) }
    }

    companion object {
        fun fromJson(value: JSONObject?): ContextBudgetStatus {
            if (value == null) return ContextBudgetStatus()
            val minimum = value.optLong("tokenEstimateMin", -1)
            val maximum = value.optLong("tokenEstimateMax", -1)
            val estimate = if (minimum >= 0 && maximum >= minimum) TokenEstimateRange(minimum, maximum) else null
            val tokens = value.optLong("providerInputTokens", -1)
            val field = value.optString("providerInputField").takeIf(String::isNotBlank)
            val usage = if (tokens >= 0 && field != null) ReportedInputUsage(tokens, field) else null
            return ContextBudgetStatus(
                requestBytes = value.optInt("requestBytes", -1).takeIf { it >= 0 },
                tokenEstimate = estimate,
                providerInputUsage = usage,
                providerModel = value.optString("providerModel").takeIf(String::isNotBlank),
                providerProtocol = value.optString("providerProtocol").takeIf(String::isNotBlank),
            )
        }
    }
}

object ContextBudgetEstimator {
    /**
     * This is only a range for UI planning. It is not a tokenizer, window measurement, or provider
     * count. ASCII text is estimated at 1 token per 4–2 characters; non-ASCII code points at 1–2.
     */
    fun estimate(text: String): TokenEstimateRange {
        var ascii = 0L
        var nonAscii = 0L
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            index += Character.charCount(codePoint)
            if (codePoint <= 0x7f) {
                if (!Character.isWhitespace(codePoint)) ascii++
            } else if (!Character.isWhitespace(codePoint)) {
                nonAscii++
            }
        }
        val minimum = (ascii + 3) / 4 + nonAscii
        val maximum = (ascii + 1) / 2 + nonAscii * 2
        return TokenEstimateRange(minimum, maximum)
    }

    fun preparedRequest(serializedJson: String): PreparedRequestBudget = PreparedRequestBudget(
        serializedBytes = serializedJson.toByteArray(Charsets.UTF_8).size,
        tokenEstimate = estimate(serializedJson),
    )
}
