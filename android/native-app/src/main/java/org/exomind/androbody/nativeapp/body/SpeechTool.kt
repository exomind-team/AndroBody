package org.exomind.androbody.nativeapp.body

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.exomind.androbody.nativeapp.core.*
import org.exomind.androbody.nativeapp.diagnostics.CapabilityFeasibility
import org.exomind.androbody.nativeapp.diagnostics.EvidenceKind
import org.exomind.androbody.nativeapp.diagnostics.RuntimeEventLogger
import org.exomind.androbody.nativeapp.diagnostics.RuntimeLogLevel
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

class SpeechTool(private val context: Context, private val runtimeLog: RuntimeEventLogger? = null) : BodyTool {
    override val capability = Capability(name = "am_tts_speak", title = "朗读文本", description = "Ask the selected Android TTS engine to speak. The engine may use network; the native tool policy controls whether each request asks first. Completion is an engine report, not proof of sound heard.",
        parameters = objectSchema(jsonObject("text" to textField("Text to speak", 2000)), listOf("text")), effect = Effect.USER_VISIBLE, gatewayEligible = false, needsConfirmation = true)
    override suspend fun execute(invocation: Invocation): ToolResult = withContext(Dispatchers.Main.immediate) {
        var engine: TextToSpeech? = null
        runtimeLog?.record("speech", "tts_initialization_requested", invocation.requestId, "native", RuntimeLogLevel.INFO,
            EvidenceKind.ACTUAL_ATTEMPT, CapabilityFeasibility.NOT_ASSESSED,
            mapOf("utteranceCharacters" to invocation.arguments.optString("text").length.toString()))
        try {
            val ready = withTimeout(8_000) {
                suspendCancellableCoroutine<Boolean> { continuation ->
                    engine = platformCall("TextToSpeech.initialize") { TextToSpeech(context) { status ->
                        runtimeLog?.record("speech", "tts_engine_initialized", invocation.requestId, "native",
                            if (status == TextToSpeech.SUCCESS) RuntimeLogLevel.INFO else RuntimeLogLevel.WARN,
                            EvidenceKind.PLATFORM_RESPONSE,
                            if (status == TextToSpeech.SUCCESS) CapabilityFeasibility.AVAILABLE else CapabilityFeasibility.UNAVAILABLE,
                            mapOf("initStatus" to status.toString()))
                        if (continuation.isActive) continuation.resume(status == TextToSpeech.SUCCESS)
                    } }
                }
            }
            val tts = engine ?: run {
                runtimeLog?.record("speech", "tts_engine_missing", invocation.requestId, "native", RuntimeLogLevel.WARN,
                    EvidenceKind.PREFLIGHT, CapabilityFeasibility.UNAVAILABLE)
                return@withContext ToolResult.unsupported("没有 TTS 引擎")
            }
            if (!ready) {
                runtimeLog?.record("speech", "tts_initialization_failed", invocation.requestId, "native", RuntimeLogLevel.WARN,
                    EvidenceKind.PLATFORM_RESPONSE, CapabilityFeasibility.UNAVAILABLE)
                return@withContext ToolResult.unsupported("TTS 初始化未成功")
            }
            val language = tts.setLanguage(Locale.getDefault())
            if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
                runtimeLog?.record("speech", "tts_language_unavailable", invocation.requestId, "native", RuntimeLogLevel.WARN,
                    EvidenceKind.PREFLIGHT, CapabilityFeasibility.UNAVAILABLE,
                    mapOf("localeLanguage" to Locale.getDefault().language, "languageStatus" to language.toString()))
                return@withContext ToolResult.unsupported("TTS 引擎缺少当前语言")
            }
            val result = withTimeout(45_000) {
                suspendCancellableCoroutine<Boolean> { continuation ->
                    val settled = AtomicBoolean(false)
                    fun settle(result: Boolean) {
                        if (settled.compareAndSet(false, true) && continuation.isActive) continuation.resume(result)
                    }
                    tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) {
                            if (id == invocation.id) runtimeLog?.record("speech", "tts_utterance_started", invocation.requestId,
                                "native", RuntimeLogLevel.INFO, EvidenceKind.PLATFORM_RESPONSE,
                                CapabilityFeasibility.SUBMITTED, mapOf("utteranceId" to invocation.id))
                        }
                        override fun onDone(id: String?) {
                            if (id == invocation.id) {
                                runtimeLog?.record("speech", "tts_engine_reported_done", invocation.requestId,
                                    "native", RuntimeLogLevel.INFO, EvidenceKind.RESULT, CapabilityFeasibility.COMPLETED,
                                    mapOf("utteranceId" to invocation.id, "soundHeard" to "NOT_OBSERVED"))
                                settle(true)
                            }
                        }
                        @Deprecated("Engine callback compatibility")
                        override fun onError(id: String?) {
                            if (id == invocation.id) {
                                runtimeLog?.record("speech", "tts_engine_reported_error", invocation.requestId,
                                    "native", RuntimeLogLevel.ERROR, EvidenceKind.RESULT, CapabilityFeasibility.FAILED,
                                    mapOf("utteranceId" to invocation.id))
                                settle(false)
                            }
                        }
                    })
                    continuation.invokeOnCancellation {
                        settled.set(true); tts.stop()
                        runtimeLog?.record("speech", "tts_stop_requested", invocation.requestId, "native",
                            RuntimeLogLevel.INFO, EvidenceKind.ACTUAL_ATTEMPT, CapabilityFeasibility.SUBMITTED,
                            mapOf("utteranceId" to invocation.id))
                    }
                    val accepted = platformCall("TextToSpeech.speak") {
                        tts.speak(invocation.arguments.stringRequired("text", 2000), TextToSpeech.QUEUE_FLUSH, null, invocation.id)
                    }
                    runtimeLog?.record("speech", "tts_speak_submitted", invocation.requestId, "native",
                        if (accepted == TextToSpeech.ERROR) RuntimeLogLevel.ERROR else RuntimeLogLevel.INFO,
                        EvidenceKind.PLATFORM_RESPONSE,
                        if (accepted == TextToSpeech.ERROR) CapabilityFeasibility.FAILED else CapabilityFeasibility.SUBMITTED,
                        mapOf("utteranceId" to invocation.id, "engineStatus" to accepted.toString()))
                    if (accepted == TextToSpeech.ERROR) settle(false)
                }
            }
            ToolResult(if (result) Outcome.COMPLETED else Outcome.FAILED,
                jsonObject("utteranceId" to invocation.id, "engineReportedDone" to result),
                "TTS 回调与本次 utterance 对齐；没有声学采集，不能证明物理可听")
        } finally {
            engine?.stop(); engine?.shutdown()
            runtimeLog?.record("speech", "tts_engine_released", invocation.requestId, "native", RuntimeLogLevel.DEBUG,
                EvidenceKind.RECOVERY, CapabilityFeasibility.UNKNOWN, mapOf("utteranceId" to invocation.id))
        }
    }
}
