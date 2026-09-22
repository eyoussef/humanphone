package dev.humanagent

import dev.humanagent.llm.LlmClient
import dev.humanagent.llm.Message
import dev.humanagent.llm.ProviderConfig
import dev.humanagent.llm.ProviderKind
import dev.humanagent.llm.StreamEvent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The request is built from what the user typed, so a mistyped key or endpoint has to end as an
 * error line in the chat — a crash here takes the whole app down before it can say anything.
 */
class LlmClientTest {

    @Test
    fun aKeyPastedWithALineBreakStillBuildsASendableRequest() {
        val request = client(apiKey = "sk-abc\ndef\n").buildRequest(listOf(Message.user("hi")), emptyList())

        assertEquals("Bearer sk-abcdef", request.header("Authorization"))
    }

    @Test
    fun aBrokenEndpointIsReportedAsAFailureInsteadOfThrown() = runBlocking {
        val events = client(baseUrl = "127.0.0.1:11434/v1").stream(listOf(Message.user("hi"))).toList()

        assertEquals(1, events.size)
        assertTrue(events.single() is StreamEvent.Failure)
    }

    private fun client(
        baseUrl: String = "http://127.0.0.1:11434/v1",
        apiKey: String = "",
    ): LlmClient = LlmClient(
        ProviderConfig(kind = ProviderKind.OLLAMA, baseUrl = baseUrl, apiKey = apiKey, model = "some-model")
    )
}
