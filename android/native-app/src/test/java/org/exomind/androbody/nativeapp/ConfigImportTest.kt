package org.exomind.androbody.nativeapp

import org.exomind.androbody.nativeapp.config.*
import org.junit.Assert.*
import org.junit.Test

class ConfigImportTest {
    @Test fun correctsMissingColonOnlyAsProposal() {
        val value = ConfigImport.parse("https//api.dev，sk-example1234")
        assertEquals(listOf("https://api.dev"), value.endpoints)
        assertEquals(listOf("sk-example1234"), value.keys)
        assertTrue(value.repairs.isNotEmpty())
        assertNull(value.suggestedProtocol)
        assertFalse(value.toString().contains("example1234"))
    }
    @Test fun extractsNamedJsonAndEnvironmentValues() {
        val value = ConfigImport.parse("""{"base_url":"https://example.org/v1/messages","api_key":"private-key","model":"demo/model"}""")
        assertEquals("private-key", value.keys.single())
        assertEquals("demo/model", value.models.single())
        assertEquals(ModelProtocol.ANTHROPIC, value.suggestedProtocol)
    }
    @Test fun multipleCandidatesStayAmbiguous() {
        val value = ConfigImport.parse("https://one.invalid/v1 sk-first1234\nhttps://two.invalid/v1 sk-second1234")
        assertTrue(value.ambiguous); assertEquals(2, value.endpoints.size); assertEquals(2, value.keys.size)
    }
    @Test fun oversizedImportDoesNotSilentlyTruncate() {
        assertThrows(IllegalArgumentException::class.java) { ConfigImport.parse("a".repeat(ConfigImport.MAX_INPUT + 1)) }
    }
    @Test fun labelsDoNotExposeShortSecrets() { assertEquals("••••••••", ConfigImport.masked("abcd")) }
    @Test fun recognizesStandaloneMiniMaxModelButDoesNotGuessItsProtocol() {
        val proposal = ConfigImport.parse("temporary-key-not-an-id\nMiniMax-M3")
        assertEquals(listOf("MiniMax-M3"), proposal.models)
        assertNull(proposal.suggestedProtocol)
    }
    @Test fun endpointNormalizationPreservesCustomPrefix() {
        val value = ModelSettings(ModelProtocol.ANTHROPIC, "https://example.org/proxy/v1/messages", "test-key", "fixture")
        assertEquals("https://example.org/proxy/v1/messages", EndpointPolicy.endpoint(value).toString())
        assertEquals("https://example.org/proxy/v1/messages/count_tokens", EndpointPolicy.endpoint(value, true).toString())
    }
    @Test fun rootEndpointAddsV1ExactlyOnce() {
        val value = ModelSettings(ModelProtocol.OPENAI, "https://example.org/", "test-key", "fixture")
        assertEquals("https://example.org/v1/chat/completions", EndpointPolicy.endpoint(value).toString())
    }
    @Test fun credentialUrlsAndRecursiveDestinationsAreRejected() {
        for (url in listOf("https://user:secret@example.org/v1", "https://example.org/v1?key=x", "http://127.0.0.1:8787"))
            assertThrows(IllegalArgumentException::class.java) { EndpointPolicy.endpoint(ModelSettings(ModelProtocol.ANTHROPIC, url, "test", "fixture", allowInsecureLocal = true)) }
    }
    @Test fun localHttpNeedsExplicitConsent() {
        assertThrows(IllegalArgumentException::class.java) { EndpointPolicy.endpoint(ModelSettings(ModelProtocol.OPENAI, "http://192.168.1.4:3425/v1", "test", "fixture")) }
        assertEquals("http://192.168.1.4:3425/v1/chat/completions", EndpointPolicy.endpoint(ModelSettings(ModelProtocol.OPENAI,
            "http://192.168.1.4:3425/v1", "test", "fixture", allowInsecureLocal = true)).toString())
    }
    @Test fun privateLookingDnsNamesDoNotAuthorizePlaintextSecrets() {
        for (url in listOf("http://10.attacker.invalid/v1", "http://192.168.attacker.invalid/v1", "http://172.16.attacker.invalid/v1"))
            assertThrows(IllegalArgumentException::class.java) {
                EndpointPolicy.endpoint(ModelSettings(ModelProtocol.OPENAI, url, "test", "fixture", allowInsecureLocal = true))
            }
    }

    @Test fun minimaxAnthropicRouteIsRecognizedAndKeepsProviderPrefix() {
        val value = ConfigImport.parse("https://api.minimaxi.com/anthropic sk-cp-temporary-example-key")
        assertEquals(ModelProtocol.ANTHROPIC, value.suggestedProtocol)
        assertEquals("https://api.minimaxi.com/anthropic", value.endpoints.single())
        assertEquals("sk-cp-temporary-example-key", value.keys.single())
        val settings = ModelSettings(ModelProtocol.ANTHROPIC, value.endpoints.single(), value.keys.single(), "MiniMax-M3")
        assertEquals("https://api.minimaxi.com/anthropic/v1/messages", EndpointPolicy.endpoint(settings).toString())
    }

}
