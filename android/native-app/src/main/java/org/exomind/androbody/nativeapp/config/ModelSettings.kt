package org.exomind.androbody.nativeapp.config

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import org.exomind.androbody.nativeapp.model.endpoint.*



/** Never a data class: default toString/copy logging must not expose credentials. */
class ModelSettings(
    val protocol: ModelProtocol,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    /** 0 means no user-selected cap; providers are continued across per-response length limits. */
    val maxTokens: Int = 0,
    val allowInsecureLocal: Boolean = false,
    val endpointMode: EndpointMode = EndpointMode.AUTO,
) {
    fun validate(): ModelSettings {
        validateConnectionFields()
        require(model.isNotBlank() && model.length <= 200 && model.none { it.isISOControl() }) { "请输入模型名称" }
        EndpointPolicy.endpoint(this)
        return this
    }
    fun validateForModelCatalog(): ModelSettings {
        validateConnectionFields()
        EndpointPolicy.modelListPlan(this)
        return this
    }
    private fun validateConnectionFields() {
        require(apiKey.isNotBlank() && apiKey.length <= 4096 && apiKey.none { it == '\r' || it == '\n' }) { "请输入有效密钥" }
        require(maxTokens == 0 || maxTokens in 64..8192) { "留空使用连续输出；自定义单次上限为 64–8192 token" }
    }
    override fun toString(): String = "ModelSettings(protocol=$protocol, credentials=<redacted>)"
    fun json(): JSONObject = JSONObject().put("protocol", protocol.name).put("baseUrl", baseUrl)
        .put("apiKey", apiKey).put("model", model).put("maxTokens", maxTokens).put("allowInsecureLocal", allowInsecureLocal).put("endpointMode", endpointMode.name)
    companion object {
        fun fromJson(value: JSONObject) = ModelSettings(protocol = ModelProtocol.valueOf(value.getString("protocol")),
            baseUrl = value.getString("baseUrl"), apiKey = value.getString("apiKey"), model = value.getString("model"),
            maxTokens = value.optInt("maxTokens", 0), allowInsecureLocal = value.optBoolean("allowInsecureLocal", false),
            endpointMode = EndpointMode.valueOf(value.optString("endpointMode", "AUTO"))).validate()
    }
}

object EndpointPolicy {
    /** Blank input may reuse a credential only for the same explicitly configured destination.
     * 📌【2026-10-28】Path and mode changes also require a new key; planning equivalence is not consent.
     */
    fun submission(protocol: ModelProtocol, baseUrl: String, inputKey: String, model: String,
        maxTokens: Int, allowInsecureLocal: Boolean, endpointMode: EndpointMode,
        saved: ModelSettings?): ModelSettings {
        val address = baseUrl.trim()
        val sameDestination = saved != null && saved.protocol == protocol &&
            saved.baseUrl.trim().toHttpUrlOrNull() == address.toHttpUrlOrNull() &&
            saved.endpointMode == endpointMode && saved.allowInsecureLocal == allowInsecureLocal
        require(inputKey.isNotBlank() || sameDestination) { "目的地或连接规则已改变，请重新输入密钥" }
        return ModelSettings(protocol, address, inputKey.ifBlank { saved?.apiKey.orEmpty() },
            model.trim(), maxTokens, allowInsecureLocal, endpointMode).validate()
    }

    fun plan(settings: ModelSettings, countTokens: Boolean = false): EndpointPlan = EndpointPlanner.plan(
        EndpointInput(settings.protocol, settings.baseUrl, settings.allowInsecureLocal, settings.endpointMode),
        if (countTokens) ModelOperation.COUNT_TOKENS else ModelOperation.GENERATE)

    fun modelListPlan(settings: ModelSettings): EndpointPlan = EndpointPlanner.plan(
        EndpointInput(settings.protocol, settings.baseUrl, settings.allowInsecureLocal, settings.endpointMode),
        ModelOperation.LIST_MODELS)

    /** Preview only. The selected endpoint is recorded by ModelHttpClient after actual protocol validation. */
    fun endpoint(settings: ModelSettings, countTokens: Boolean = false): HttpUrl =
        requireNotNull(plan(settings, countTokens).candidates.first().url.toHttpUrlOrNull())
}
