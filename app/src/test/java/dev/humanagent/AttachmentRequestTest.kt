package dev.humanagent

import dev.humanagent.llm.FilePart
import dev.humanagent.llm.LlmClient
import dev.humanagent.llm.Message
import dev.humanagent.llm.ProviderConfig
import dev.humanagent.llm.ProviderKind
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the model actually receives when the user attaches something: the request that leaves the
 * app must carry the picture or the document, not just the sentence next to it.
 */
class AttachmentRequestTest {

    private val config = ProviderConfig(
        kind = ProviderKind.OPENROUTER,
        baseUrl = "https://openrouter.ai/api/v1",
        apiKey = "test-key",
        model = "test-model",
    )

    private fun contentOf(body: JsonObject, index: Int): JsonElement =
        body.getValue("messages").jsonArray[index].jsonObject.getValue("content")

    private fun request(vararg messages: Message): JsonObject =
        LlmClient(config).buildRequestBody(messages.toList(), emptyList())

    @Test
    fun anAttachedImageTravelsAsAnImageUrlPart() {
        val body = request(Message.user("what is this?", images = listOf("QUJD")))
        val parts = contentOf(body, 0).jsonArray
        assertEquals("text", parts[0].jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("what is this?", parts[0].jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals("image_url", parts[1].jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(
            "data:image/jpeg;base64,QUJD",
            parts[1].jsonObject.getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content,
        )
    }

    @Test
    fun anAttachedDocumentTravelsAsAFilePart() {
        val body = request(
            Message.user("summarise this", files = listOf(FilePart("report.pdf", "application/pdf", "QUJD"))),
        )
        val part = contentOf(body, 0).jsonArray[1].jsonObject
        assertEquals("file", part.getValue("type").jsonPrimitive.content)
        val payload = part.getValue("file").jsonObject
        assertEquals("report.pdf", payload.getValue("filename").jsonPrimitive.content)
        assertEquals("data:application/pdf;base64,QUJD", payload.getValue("file_data").jsonPrimitive.content)
    }

    @Test
    fun messagesWithoutAttachmentsKeepAPlainStringContent() {
        val body = request(Message.system("be brief"), Message.user("hello"))
        assertEquals("be brief", contentOf(body, 0).jsonPrimitive.content)
        assertEquals("hello", contentOf(body, 1).jsonPrimitive.content)
    }

    @Test
    fun aToolResultKeepsItsCallIdAndName() {
        val body = request(Message.tool("call_1", "tap_text", "Tapped \"Send\"."))
        val message = body.getValue("messages").jsonArray[0].jsonObject
        assertEquals("tool", message.getValue("role").jsonPrimitive.content)
        assertEquals("call_1", message.getValue("tool_call_id").jsonPrimitive.content)
        assertEquals("tap_text", message.getValue("name").jsonPrimitive.content)
    }

    @Test
    fun everyMessageTravelsInOrder() {
        val body = request(Message.system("s"), Message.user("u"), Message.assistant("a"))
        val messages = body.getValue("messages").jsonArray
        val roles = messages.map { it.jsonObject.getValue("role").jsonPrimitive.content }
        assertEquals(listOf("system", "user", "assistant"), roles)
        assertEquals(3, messages.size)
    }
}
