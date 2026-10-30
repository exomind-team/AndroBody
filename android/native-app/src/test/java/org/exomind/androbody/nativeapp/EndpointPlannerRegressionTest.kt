package org.exomind.androbody.nativeapp

import org.exomind.androbody.nativeapp.config.*
import org.exomind.androbody.nativeapp.model.endpoint.*
import org.junit.Assert.*
import org.junit.Test

class EndpointPlannerRegressionTest {
    @Test fun everyCommonAnthropicInputPreservesProviderAndVersion() {
        val expected="https://api.deepseek.com/anthropic/v1/messages"
        for(input in listOf("https://api.deepseek.com","https://api.deepseek.com/anthropic","https://api.deepseek.com/anthropic/",
            "https://api.deepseek.com/anthropic/v1","https://api.deepseek.com/anthropic/v1/messages")) {
            val settings=ModelSettings(ModelProtocol.ANTHROPIC,input,"fixture","fixture")
            assertEquals(input,expected,EndpointPolicy.endpoint(settings).toString())
        }
    }
    @Test fun oldProfileWithoutModeMigratesToAuto() {
        val json=org.json.JSONObject().put("protocol","ANTHROPIC").put("baseUrl","https://api.deepseek.com/anthropic")
            .put("apiKey","fixture").put("model","fixture")
        assertEquals(EndpointMode.AUTO,ModelSettings.fromJson(json).endpointMode)
    }
    @Test fun malformedModesAreNotSilentlyDowngraded() {
        val json=ModelSettings(ModelProtocol.ANTHROPIC,"https://example.org","fixture","fixture").json().put("endpointMode","mystery")
        assertThrows(IllegalArgumentException::class.java){ModelSettings.fromJson(json)}
    }
    @Test fun modelListDiscoveryKeepsOriginAndHonorsExactMode() {
        val auto = ModelSettings(ModelProtocol.ANTHROPIC, "https://example.org/tenant/v1/messages", "fixture", "fixture")
        val candidates = EndpointPolicy.modelListPlan(auto).candidates
        assertEquals("https://example.org/tenant/v1/models", candidates.first().url)
        assertTrue(candidates.all { it.url.startsWith("https://example.org/") })

        val exact = ModelSettings(ModelProtocol.OPENAI, "https://example.org/custom/v1/chat/completions", "fixture", "fixture",
            endpointMode = EndpointMode.EXACT)
        val exactPlan = EndpointPolicy.modelListPlan(exact)
        assertEquals(1, exactPlan.candidates.size)
        assertEquals("https://example.org/custom/v1/models", exactPlan.candidates.single().url)
    }
}
