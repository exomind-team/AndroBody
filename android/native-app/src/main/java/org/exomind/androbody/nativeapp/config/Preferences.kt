package org.exomind.androbody.nativeapp.config

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import org.exomind.androbody.nativeapp.storage.EncryptedStore
import org.json.JSONObject

enum class NativeApprovalMode { ASK_SENSITIVE, ALLOW_READS, ALLOW_ALL }
enum class NativeCapabilityChoice { ASK, ALLOW, DENY }
enum class ComposerSendMode { CTRL_ENTER, ENTER }

/** Nonsensitive preferences are separate from credentials and task evidence. */
data class AppPreferences(
    val protocol: ModelProtocol = ModelProtocol.ANTHROPIC,
    val theme: String = "system",
    val clipboardImport: Boolean = false,
    val gatewayScopes: Set<String> = emptySet(),
    val gatewayEpoch: Long = 0,
    val triggersEnabled: Boolean = false,
    val triggerPackages: Set<String> = emptySet(),
    val triggerKeyword: String = "",
    val triggerCloudConsent: Boolean = false,
    val nativeApprovalMode: NativeApprovalMode = NativeApprovalMode.ASK_SENSITIVE,
    val nativeCapabilityChoices: Map<String, NativeCapabilityChoice> = emptyMap(),
    val composerSendMode: ComposerSendMode = ComposerSendMode.CTRL_ENTER,
)
class Preferences(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        appContext.getSharedPreferences("native-preferences-v1", Context.MODE_PRIVATE)
    }
    private val mutable = MutableStateFlow(AppPreferences())
    val state: StateFlow<AppPreferences> = mutable
    private val mutableInitialized = MutableStateFlow(false)
    val initialized: StateFlow<Boolean> = mutableInitialized
    private val mutableLoadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = mutableLoadError
    private val loadLock = Any()
    @Volatile private var loading = true
    @Volatile private var loadCompletion = CompletableDeferred<Unit>()

    init { startLoad(loadCompletion) }

    private fun startLoad(completion: CompletableDeferred<Unit>) {
        scope.launch {
            try {
                mutable.value = load()
                mutableLoadError.value = null
                mutableInitialized.value = true
                completion.complete(Unit)
            } catch (cancelled: CancellationException) {
                completion.cancel(cancelled)
                throw cancelled
            } catch (failure: Exception) {
                mutableLoadError.value = failure.javaClass.simpleName
                completion.completeExceptionally(failure)
            } finally {
                synchronized(loadLock) { if (loadCompletion === completion) loading = false }
            }
        }
    }

    fun retryLoad() {
        val next = synchronized(loadLock) {
            if (mutableInitialized.value || loading) return
            loading = true
            CompletableDeferred<Unit>().also { loadCompletion = it }
        }
        startLoad(next)
    }

    suspend fun awaitReady(): AppPreferences {
        loadCompletion.await()
        return mutable.value
    }

    private fun load() = AppPreferences(
        protocol = runCatching { ModelProtocol.valueOf(prefs.getString("protocol", "ANTHROPIC")!!) }.getOrDefault(ModelProtocol.ANTHROPIC),
        theme = prefs.getString("theme", "system") ?: "system", clipboardImport = prefs.getBoolean("clipboard", false),
        gatewayScopes = prefs.getStringSet("scopes", emptySet())!!.toSet(), gatewayEpoch = prefs.getLong("epoch", 0),
        triggersEnabled = prefs.getBoolean("triggers", false), triggerPackages = prefs.getStringSet("packages", emptySet())!!.toSet(),
        triggerKeyword = prefs.getString("keyword", "") ?: "", triggerCloudConsent = prefs.getBoolean("cloudConsent", false),
        nativeApprovalMode = runCatching {
            NativeApprovalMode.valueOf(prefs.getString("nativeApprovalMode", NativeApprovalMode.ASK_SENSITIVE.name)!!)
        }.getOrDefault(NativeApprovalMode.ASK_SENSITIVE),
        nativeCapabilityChoices = readNativeCapabilityChoices(),
        composerSendMode = runCatching {
            ComposerSendMode.valueOf(prefs.getString("composerSendMode", ComposerSendMode.CTRL_ENTER.name)!!)
        }.getOrDefault(ComposerSendMode.CTRL_ENTER),
    )
    private fun readNativeCapabilityChoices(): Map<String, NativeCapabilityChoice> = runCatching {
        val json = JSONObject(prefs.getString("nativeCapabilityChoices", "{}") ?: "{}")
        json.keys().asSequence().mapNotNull { key ->
            runCatching { key to NativeCapabilityChoice.valueOf(json.getString(key)) }.getOrNull()
        }.toMap()
    }.getOrDefault(emptyMap())
    suspend fun update(change: (AppPreferences) -> AppPreferences) {
        awaitReady()
        withContext(Dispatchers.IO) {
        synchronized(this@Preferences) {
            val next = change(mutable.value)
            check(prefs.edit().putString("protocol", next.protocol.name).putString("theme", next.theme)
                .putBoolean("clipboard", next.clipboardImport).putStringSet("scopes", next.gatewayScopes)
                .putLong("epoch", next.gatewayEpoch).putBoolean("triggers", next.triggersEnabled)
                .putStringSet("packages", next.triggerPackages).putString("keyword", next.triggerKeyword)
                .putBoolean("cloudConsent", next.triggerCloudConsent)
                .putString("nativeApprovalMode", next.nativeApprovalMode.name)
                .putString("nativeCapabilityChoices", JSONObject().apply {
                    next.nativeCapabilityChoices.forEach { (name, choice) -> put(name, choice.name) }
                }.toString()).putString("composerSendMode", next.composerSendMode.name).commit()) { "设置未保存" }
            mutable.value = next
        }
        }
    }
}

class ProfileStore(context: Context) {
    private val store = EncryptedStore(context, "model-routes")
    private var stored: JSONObject? = null
    private var loaded = false
    @Synchronized fun read(protocol: ModelProtocol): ModelSettings? {
        if (!loaded) { stored = store.read() ?: JSONObject(); loaded = true }
        return stored!!.optJSONObject(protocol.name)?.let(ModelSettings::fromJson)
    }
    @Synchronized fun save(settings: ModelSettings) {
        settings.validate()
        if (!loaded) { stored = store.read() ?: JSONObject(); loaded = true }
        val next = JSONObject(stored.toString()).put(settings.protocol.name, settings.json())
        store.write(next); stored = next
    }
}

class ClientCredential(context: Context) {
    private val store = EncryptedStore(context, "gateway-client")
    @Synchronized fun token(): String {
        val existing = store.read()
        if (existing != null) return existing.getString("token")
        return rotate()
    }
    @Synchronized fun rotate(): String {
        val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
        val token = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        store.write(JSONObject().put("token", token))
        return token
    }
}
