package org.exomind.androbody.nativeapp.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Process
import android.os.SystemClock
import org.exomind.androbody.nativeapp.BuildConfig

/** A timestamped OS observation, not a causal diagnosis. No SSID, BSSID, contacts or hardware ID. */
fun androidTransportContext(context: Context): Map<String, String> {
    val fields = linkedMapOf("androidApi" to Build.VERSION.SDK_INT.toString(),
        "package" to context.packageName, "build" to BuildConfig.VERSION_NAME,
        "sourceRevision" to BuildConfig.SOURCE_REVISION, "pid" to Process.myPid().toString(),
        "elapsedRealtimeMs" to SystemClock.elapsedRealtime().toString(), "observedAt" to System.currentTimeMillis().toString())
    try {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork
        val capabilities = network?.let(manager::getNetworkCapabilities)
        fields["activeNetworkObserved"] = (network != null).toString()
        fields["transportTypes"] = if (capabilities == null) "UNKNOWN" else listOf(
            NetworkCapabilities.TRANSPORT_WIFI to "WIFI", NetworkCapabilities.TRANSPORT_CELLULAR to "CELLULAR",
            NetworkCapabilities.TRANSPORT_VPN to "VPN", NetworkCapabilities.TRANSPORT_ETHERNET to "ETHERNET"
        ).filter { capabilities.hasTransport(it.first) }.joinToString(",") { it.second }.ifEmpty { "OTHER" }
        fields["osValidated"] = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)?.toString() ?: "UNKNOWN"
        fields["scope"] = "OS observation at request start/export; does not prove this API route works or that a VPN caused failure"
    } catch (failure: Exception) { fields["networkObservation"] = "UNKNOWN:${failure.javaClass.simpleName}" }
    return fields
}
