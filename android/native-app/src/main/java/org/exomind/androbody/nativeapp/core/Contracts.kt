package org.exomind.androbody.nativeapp.core

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Protocol-neutral ownership; a model can never select its caller identity. */
enum class Origin { NATIVE, GATEWAY, EVENT, USER }
data class Principal(val id: String, val origin: Origin)
enum class Effect { READ_DEVICE, READ_WORKSPACE, WRITE_WORKSPACE, USER_VISIBLE, CONTROL }
enum class Outcome { COMPLETED, SUBMITTED, DENIED, UNSUPPORTED, CANCELLED, UNKNOWN, FAILED }

data class Capability(
    val name: String,
    val title: String,
    val description: String,
    val parameters: JSONObject,
    val effect: Effect,
    val gatewayEligible: Boolean,
    val needsConfirmation: Boolean = false,
)

data class Invocation(
    val requestId: String,
    val callId: String,
    val principal: Principal,
    val capability: String,
    val arguments: JSONObject,
    val id: String = UUID.randomUUID().toString(),
)

data class Receipt(
    val invocationId: String,
    val requestId: String,
    val capability: String,
    val outcome: Outcome,
    val observedAt: Long,
    val data: JSONObject = JSONObject(),
    val evidence: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("invocationId", invocationId).put("requestId", requestId)
        .put("capability", capability).put("status", outcome.name.lowercase())
        .put("observedAtEpochMs", observedAt).put("data", data).put("evidence", evidence)
}

data class ToolResult(val outcome: Outcome, val data: JSONObject, val evidence: String) {
    companion object {
        fun completed(data: JSONObject, evidence: String) = ToolResult(Outcome.COMPLETED, data, evidence)
        fun unsupported(reason: String) = ToolResult(Outcome.UNSUPPORTED, JSONObject(), reason)
    }
}

class BodyFailure(val code: String, val safeDetail: String) : Exception(code)
class WireFailure(val status: Int, val code: String, val safeDetail: String) : Exception(code)

/** Only adapters may use this after a real Android/target API throws at this named call boundary. */
class PlatformCallDenied(
    val api: String,
    val reasonCode: String,
    cause: Throwable,
) : Exception("Platform denied $api ($reasonCode)", cause)

inline fun <T> platformCall(api: String, call: () -> T): T = try {
    call()
} catch (failure: SecurityException) {
    throw PlatformCallDenied(api, "ANDROID_SECURITY_EXCEPTION", failure)
}

interface BodyTool {
    val capability: Capability
    suspend fun execute(invocation: Invocation): ToolResult
}

interface PermissionPolicy {
    /** Must inspect current permission/grant state, never a model-supplied boolean. */
    fun denial(principal: Principal, capability: Capability): String?
    /** Persistent approval preferences are local to the native assistant by default. */
    fun requiresApproval(principal: Principal, capability: Capability): Boolean = capability.needsConfirmation
}
fun interface ApprovalPort { suspend fun approve(invocation: Invocation, capability: Capability): Boolean }
interface ReceiptJournal {
    fun begin(invocation: Invocation)
    fun finish(invocation: Invocation, receipt: Receipt)
}

class ToolRegistry(tools: List<BodyTool>) {
    private val toolsByName = tools.associateBy { it.capability.name }
    init {
        require(toolsByName.size == tools.size) { "Duplicate capability" }
        require(tools.all { it.capability.name.matches(Regex("am_[a-z0-9_]+")) })
    }
    val capabilities: List<Capability> get() = toolsByName.values.map { it.capability }
    fun find(name: String): BodyTool? = toolsByName[name]
}

fun jsonObject(vararg fields: Pair<String, Any?>): JSONObject = JSONObject().apply {
    fields.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
}
fun objectSchema(properties: JSONObject = JSONObject(), required: List<String> = emptyList()): JSONObject =
    jsonObject("type" to "object", "properties" to properties, "required" to JSONArray(required), "additionalProperties" to false)
fun textField(description: String, maxLength: Int): JSONObject =
    jsonObject("type" to "string", "description" to description, "maxLength" to maxLength)
fun JSONObject.stringRequired(name: String, max: Int): String {
    val value = opt(name) as? String ?: throw BodyFailure("INVALID_ARGUMENT", "缺少文本字段：$name")
    if (value.length > max) throw BodyFailure("INVALID_ARGUMENT", "字段过长：$name")
    return value
}
fun JSONObject.copyJson() = JSONObject(toString())
fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
