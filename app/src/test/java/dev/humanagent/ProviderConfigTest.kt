package dev.humanagent

import dev.humanagent.llm.ProviderConfig
import dev.humanagent.llm.ProviderKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderConfigTest {

    @Test
    fun appendsChatCompletionsToBareBaseUrl() {
        val config = provider(ProviderKind.OLLAMA, "http://127.0.0.1:11434/v1")
        assertEquals("http://127.0.0.1:11434/v1/chat/completions", config.chatCompletionsUrl)
    }

    @Test
    fun keepsTrailingSlashAndFullEndpointIntact() {
        assertEquals(
            "http://192.168.1.10:11434/v1/chat/completions",
            provider(ProviderKind.OLLAMA, "http://192.168.1.10:11434/v1/").chatCompletionsUrl,
        )
        assertEquals(
            "https://openrouter.ai/api/v1/chat/completions",
            provider(ProviderKind.OPENROUTER, "https://openrouter.ai/api/v1/chat/completions").chatCompletionsUrl,
        )
    }

    @Test
    fun openRouterNeedsAKeyButOllamaDoesNot() {
        assertFalse(provider(ProviderKind.OPENROUTER, "https://openrouter.ai/api/v1").isUsable)
        assertTrue(
            provider(ProviderKind.OPENROUTER, "https://openrouter.ai/api/v1").copy(apiKey = "sk-x").isUsable
        )
        assertTrue(provider(ProviderKind.OLLAMA, "http://127.0.0.1:11434/v1").isUsable)
    }

    @Test
    fun aMissingModelMakesTheProviderUnusable() {
        assertFalse(provider(ProviderKind.OLLAMA, "http://127.0.0.1:11434/v1").copy(model = " ").isUsable)
        assertFalse(provider(ProviderKind.CUSTOM, "").isUsable)
    }

    private fun provider(kind: ProviderKind, baseUrl: String): ProviderConfig = ProviderConfig(
        kind = kind,
        baseUrl = baseUrl,
        apiKey = "",
        model = "some-model",
    )
}
