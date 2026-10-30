package org.exomind.androbody.nativeapp.config

/** Pure, local extraction. A proposal never saves, sends, probes or reads a clipboard. */
class ImportProposal(
    val endpoints: List<String>,
    val keys: List<String>,
    val models: List<String>,
    val suggestedProtocol: ModelProtocol?,
    val repairs: List<String>,
) {
    val ambiguous get() = endpoints.size > 1 || keys.size > 1 || models.size > 1
    override fun toString() = "ImportProposal(endpoints=${endpoints.size}, keys=<redacted>, ambiguous=$ambiguous)"
}

object ConfigImport {
    const val MAX_INPUT = 16_384
    private val url = Regex("https?://[^\\s\\\"'<>，,；;]+", RegexOption.IGNORE_CASE)
    private val standardKey = Regex("(?<![A-Za-z0-9_-])sk-[A-Za-z0-9_-]{4,4090}(?![A-Za-z0-9_-])")
    private val namedKey = Regex("(?i)(?:ANTHROPIC_API_KEY|ANTHROPIC_AUTH_TOKEN|OPENAI_API_KEY|api[_-]?key|密钥)\\s*[\\\"']?\\s*[:=：]\\s*[\\\"']?([^\\s\\\"',，;；]+)")
    private val model = Regex("(?i)(?:ANTHROPIC_MODEL|OPENAI_MODEL|model|模型)\\s*[\\\"']?\\s*[:=：]\\s*[\\\"']?([A-Za-z0-9_./:-]+)")
    private val knownStandaloneModel = Regex(
        "(?i)^(?:o[1-9](?:[-_.:/][a-z0-9][a-z0-9.-]*)?|(?:minimax|claude|gpt|deepseek|kimi|moonshot|gemini|gemma|llama|mistral|doubao|ernie|hunyuan|command-r|nova|grok)[-_.:/][a-z0-9][a-z0-9.-]*|qwen[0-9][a-z0-9.-]*|glm[-_.:/]?[0-9][a-z0-9.-]*|yi[-_.:/][a-z0-9][a-z0-9.-]*)$"
    )
    fun parse(raw: String): ImportProposal {
        require(raw.length <= MAX_INPUT) { "导入文本超过 16 Ki 字符，请缩小范围" }
        val repairs = mutableListOf<String>()
        val repaired = Regex("(?i)https?//").replace(raw) {
            repairs += "已提出补全 URL 冒号的修正，保存前请确认"
            it.value.replace("//", "://")
        }
        val endpoints = url.findAll(repaired).map { it.value.trimEnd(')', ']', '}', '.', '。') }.distinct().toList()
        val keys = (standardKey.findAll(repaired).map { it.value } + namedKey.findAll(repaired).map { it.groupValues[1] }).distinct().toList()
        val labelledModels = model.findAll(repaired).map { it.groupValues[1] }
        val looseModels = repaired.lineSequence().map(String::trim)
            .map { it.trim('"', ',', '，', ';', '；', '.', '。', '(', ')', '[', ']', '{', '}') }
            .map { it.removeSurrounding(Char(96).toString()) }
            .filter(::looksLikeKnownModelId)
        val models = (labelledModels + looseModels).filter { it.isNotBlank() }.distinct().toList()
        val protocol = when {
            endpoints.any { it.endsWith("/messages") || it.contains("anthropic", ignoreCase = true) } || keys.any { it.startsWith("sk-ant-") } -> ModelProtocol.ANTHROPIC
            endpoints.any { it.endsWith("/chat/completions") } -> ModelProtocol.OPENAI
            else -> null
        }
        return ImportProposal(endpoints, keys, models, protocol, repairs.distinct())
    }
    /** A standalone model identifier is useful only for recognized families; arbitrary words stay unassigned. */
    private fun looksLikeKnownModelId(value: String): Boolean {
        if (value.length !in 3..120 || value.any(Char::isWhitespace)) return false
        if (value.startsWith("http", ignoreCase = true) || value.contains('@')) return false
        if (standardKey.matches(value)) return false
        return knownStandaloneModel.matches(value)
    }
    fun masked(value: String): String = if (value.length <= 8) "••••••••" else "${value.take(3)}••••${value.takeLast(4)}"
}
