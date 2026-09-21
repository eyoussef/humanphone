package dev.humanagent.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Tolerant reader for the JSON argument blobs models produce. Never throws: a wrong shape simply
 * yields null so the tool can tell the model what it did wrong.
 */
object JsonArgs {

    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    fun asObject(raw: String): JsonObject? =
        runCatching { json.parseToJsonElement(raw.ifBlank { "{}" }) as? JsonObject }.getOrNull()

    fun string(raw: String, key: String): String? {
        val element = asObject(raw)?.get(key) ?: return null
        if (element is JsonNull) return null
        return (element as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun int(raw: String, key: String): Int? {
        val value = string(raw, key) ?: return null
        return value.toIntOrNull() ?: value.toDoubleOrNull()?.toInt()
    }

    fun bool(raw: String, key: String): Boolean? = when (string(raw, key)?.lowercase()) {
        "true", "1", "yes", "on" -> true
        "false", "0", "no", "off" -> false
        else -> null
    }

    fun keys(raw: String): Set<String> = asObject(raw)?.keys.orEmpty()
}
