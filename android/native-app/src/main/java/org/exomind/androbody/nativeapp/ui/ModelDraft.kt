package org.exomind.androbody.nativeapp.ui

import androidx.compose.runtime.*
import org.exomind.androbody.nativeapp.config.ModelSettings
import org.exomind.androbody.nativeapp.model.endpoint.EndpointMode

/** In-memory edit buffer. Credentials never enter rememberSaveable, a Bundle or a model prompt. */
class ModelDraft {
    var endpointMode by mutableStateOf(EndpointMode.AUTO)
    var loaded by mutableStateOf(false)
    var baseUrl by mutableStateOf("")
    var key by mutableStateOf("")
    var model by mutableStateOf("")
    var maxTokens by mutableStateOf("")
    var localHttp by mutableStateOf(false)
    var hasStoredKey by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    fun load(value: ModelSettings?) {
        if (loaded) return
        if (value != null) {
            endpointMode = value.endpointMode
            baseUrl = value.baseUrl; model = value.model; maxTokens = value.maxTokens.takeIf { it > 0 }?.toString().orEmpty()
            localHttp = value.allowInsecureLocal; hasStoredKey = true
        }
        loaded = true
    }
}
