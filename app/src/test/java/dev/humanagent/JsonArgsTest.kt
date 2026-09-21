package dev.humanagent

import dev.humanagent.util.JsonArgs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JsonArgsTest {

    @Test
    fun readsPlainArguments() {
        val args = """{"text":"hello there","index":7}"""
        assertEquals("hello there", JsonArgs.string(args, "text"))
        assertEquals(7, JsonArgs.int(args, "index"))
    }

    @Test
    fun toleratesNumbersSentAsStrings() {
        assertEquals(12, JsonArgs.int("""{"seconds":"12"}""", "seconds"))
        assertEquals(3, JsonArgs.int("""{"seconds":3.7}""", "seconds"))
    }

    @Test
    fun missingAndNullValuesYieldNull() {
        assertNull(JsonArgs.string("""{"text":null}""", "text"))
        assertNull(JsonArgs.string("""{"other":"x"}""", "text"))
        assertNull(JsonArgs.int("""{"index":"not a number"}""", "index"))
        assertNull(JsonArgs.string("not json at all", "text"))
    }

    @Test
    fun blankStringsAreTreatedAsAbsent() {
        assertNull(JsonArgs.string("""{"text":"   "}""", "text"))
    }

    @Test
    fun readsBooleansInCommonSpellings() {
        assertEquals(true, JsonArgs.bool("""{"a":"true"}""", "a"))
        assertEquals(true, JsonArgs.bool("""{"a":1}""", "a"))
        assertEquals(false, JsonArgs.bool("""{"a":"OFF"}""", "a"))
        assertNull(JsonArgs.bool("""{"a":"maybe"}""", "a"))
    }

    @Test
    fun emptyArgumentObjectIsAccepted() {
        assertEquals(emptySet<String>(), JsonArgs.keys(""))
    }
}
