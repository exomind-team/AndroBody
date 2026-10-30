package org.exomind.androbody.nativeapp.body

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.exomind.androbody.nativeapp.core.*
import org.exomind.androbody.nativeapp.diagnostics.CapabilityFeasibility
import org.exomind.androbody.nativeapp.diagnostics.EvidenceKind
import org.exomind.androbody.nativeapp.diagnostics.RuntimeEventLogger
import org.exomind.androbody.nativeapp.diagnostics.RuntimeLogLevel
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

class SensorTool(context: Context, private val workspace: Workspace, private val runtimeLog: RuntimeEventLogger? = null) : BodyTool {
    private val manager = context.getSystemService(SensorManager::class.java)
    override val capability = Capability(name = "am_sensor_snapshot", title = "有限传感器采样", description = "Collect 1–10 seconds of accelerometer samples, stop registration, then save JSON to the private workspace. Hardware may be absent.",
        parameters = objectSchema(jsonObject("seconds" to jsonObject("type" to "integer", "minimum" to 1, "maximum" to 10)), listOf("seconds")), effect = Effect.WRITE_WORKSPACE, gatewayEligible = true)
    override suspend fun execute(invocation: Invocation): ToolResult {
        val sensor = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            ?: run {
                runtimeLog?.record("sensor", "sensor_preflight_unavailable", invocation.requestId, "native",
                    RuntimeLogLevel.WARN, EvidenceKind.PREFLIGHT, CapabilityFeasibility.UNAVAILABLE,
                    mapOf("sensorType" to "accelerometer", "reason" to "default_sensor_missing"))
                return ToolResult.unsupported("当前设备没有可用加速度计")
            }
        val seconds = invocation.arguments.getInt("seconds")
        runtimeLog?.record("sensor", "sensor_sample_requested", invocation.requestId, "native",
            RuntimeLogLevel.INFO, EvidenceKind.AGENT_REQUEST, CapabilityFeasibility.NOT_ASSESSED,
            mapOf("sensorType" to "accelerometer", "requestedSeconds" to seconds.toString()))
        val samples = withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine<JSONArray> { continuation ->
                val handler = Handler(Looper.getMainLooper())
                val data = JSONArray()
                var lastTimestamp = 0L
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        if (!continuation.isActive || data.length() >= MAX_SAMPLES || event.timestamp - lastTimestamp < 40_000_000L) return
                        lastTimestamp = event.timestamp
                        data.put(jsonObject("elapsedRealtimeNanos" to event.timestamp,
                            "values" to JSONArray(event.values.map { if (it.isFinite()) it.toDouble() else JSONObject.NULL }), "accuracy" to event.accuracy))
                    }
                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }
                val finish = Runnable {
                    manager.unregisterListener(listener)
                    runtimeLog?.record("sensor", "sensor_sample_window_ended", invocation.requestId, "native",
                        RuntimeLogLevel.INFO, EvidenceKind.PLATFORM_RESPONSE, CapabilityFeasibility.AVAILABLE,
                        mapOf("sensorType" to "accelerometer", "sampleCount" to data.length().toString()))
                    if (continuation.isActive) continuation.resume(data)
                }
                if (!continuation.isActive) return@suspendCancellableCoroutine
                val registered = platformCall("SensorManager.registerListener") {
                    manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI, handler)
                }
                if (!registered) {
                    runtimeLog?.record("sensor", "sensor_registration_rejected", invocation.requestId, "native",
                        RuntimeLogLevel.WARN, EvidenceKind.PLATFORM_RESPONSE, CapabilityFeasibility.UNAVAILABLE,
                        mapOf("sensorType" to "accelerometer", "platformResponse" to "registerListener_returned_false"))
                    if (continuation.isActive) continuation.resume(JSONArray())
                    return@suspendCancellableCoroutine
                }
                runtimeLog?.record("sensor", "sensor_listener_registered", invocation.requestId, "native",
                    RuntimeLogLevel.INFO, EvidenceKind.PLATFORM_RESPONSE, CapabilityFeasibility.AVAILABLE,
                    mapOf("sensorType" to "accelerometer", "samplingDelay" to "SENSOR_DELAY_UI"))
                handler.postDelayed(finish, seconds * 1000L)
                continuation.invokeOnCancellation {
                    handler.removeCallbacks(finish); manager.unregisterListener(listener)
                    runtimeLog?.record("sensor", "sensor_listener_cancelled", invocation.requestId, "native",
                        RuntimeLogLevel.WARN, EvidenceKind.RECOVERY, CapabilityFeasibility.UNKNOWN,
                        mapOf("sensorType" to "accelerometer", "samplesObserved" to data.length().toString()))
                }
            }
        }
        if (samples.length() == 0) {
            runtimeLog?.record("sensor", "sensor_sample_empty", invocation.requestId, "native",
                RuntimeLogLevel.WARN, EvidenceKind.RESULT, CapabilityFeasibility.UNAVAILABLE,
                mapOf("sensorType" to "accelerometer", "requestedSeconds" to seconds.toString()))
            return ToolResult.unsupported("未获得样本；可能缺硬件或当前执行条件不允许。没有生成伪零值")
        }
        val record = jsonObject("sensorType" to "accelerometer", "unit" to "m/s²", "sensorName" to sensor.name,
            "requestedSeconds" to seconds, "sampleLimit" to MAX_SAMPLES, "observedAtEpochMs" to System.currentTimeMillis(), "samples" to samples)
        val file = workspace.write("sensors/${invocation.id}.json", record.toString())
        runtimeLog?.record("sensor", "sensor_artifact_verified", invocation.requestId, "native",
            RuntimeLogLevel.INFO, EvidenceKind.RESULT, CapabilityFeasibility.COMPLETED,
            mapOf("sensorType" to "accelerometer", "sampleCount" to samples.length().toString(),
                "artifactPath" to file.optString("path"),
                "artifactBytes" to file.optString("bytes"),
                "artifactSha256" to file.optString("sha256")))
        return ToolResult.completed(jsonObject("sampleCount" to samples.length(), "artifact" to file),
            "SensorManager 有界回调；结束时注销 listener，文件重新读回并核对摘要。未验证物理运动真值")
    }
    companion object { const val MAX_SAMPLES = 250 }
}
