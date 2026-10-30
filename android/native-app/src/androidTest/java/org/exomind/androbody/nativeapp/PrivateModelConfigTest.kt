package org.exomind.androbody.nativeapp

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.exomind.androbody.nativeapp.config.ModelSettings
import org.exomind.androbody.nativeapp.diagnostics.RequestContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Test APK only. Config enters app-private storage through adb run-as stdin, never an exported component. */
class PrivateModelConfigTest {
    @Test fun loadPrivateConfigurationAndOptionallyProbeRealModel() = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val arguments=InstrumentationRegistry.getArguments()
        val context=instrumentation.targetContext
        check(context.packageName.endsWith(".verification")) { "Dedicated verification applicationId required" }
        assumeTrue("Run only through explicit --real-config opt-in",arguments.getString("allowRealModel")=="true")
        val file=File(context.filesDir,"qa/model-config.json")
        require(file.isFile && file.length() in 1..16_384)
        val input=try{JSONObject(file.readText())}finally{check(file.delete()){ "Config plaintext cleanup failed" }}
        check(input.optBoolean("allowModelCall",false)){"Real model consent missing"}
        val settings=ModelSettings.fromJson(input.getJSONObject("model"))
        context.graph.profiles.save(settings)
        context.graph.preferences.update{it.copy(protocol=settings.protocol,triggersEnabled=false,triggerCloudConsent=false,gatewayScopes=emptySet())}
        val reread=context.graph.profiles.read(settings.protocol)!!
        assertEquals(settings.baseUrl,reread.baseUrl)
        assertTrue("Encrypted credential roundtrip mismatch", java.security.MessageDigest.isEqual(settings.apiKey.toByteArray(),reread.apiKey.toByteArray()))
        val request=JSONObject().put("model",settings.model).put("max_tokens",128).put("stream",false)
            .put("messages",JSONArray().put(JSONObject().put("role","user").put("content","Connection test: reply OK.")))
        val outcome=JSONObject().put("requestedModel",settings.model).put("realModel",true)
        try {
            val reply=withContext(Dispatchers.IO){context.graph.http.requestWithEvidence(settings,request,context=RequestContext(origin="diagnostic"))}
            outcome.put("status","PASS").put("traceId",reply.traceId).put("endpoint",reply.endpoint)
                .put("attempts",reply.attempts).put("responseModel",reply.body.optString("model","not_reported"))
        } catch(failure:Exception){outcome.put("status","FAIL").put("failureClass",failure.javaClass.name);throw failure}
        finally {
            val directory=File(context.filesDir,"qa/evidence").apply{mkdirs()}
            File(directory,"real-model-result.json").writeText(outcome.toString(2))
            File(directory,"real-model-diagnostics.json").writeText(context.graph.networkDiagnostics.export().toString(2))
        }
    }
}
