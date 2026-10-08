package dev.humanagent.brain

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceStoreTest {

    private fun fresh(): File = File.createTempFile("sources", ".json").also { it.delete() }

    @Test
    fun sourcesRoundTripAndReplaceById() = runTest {
        val file = fresh()
        val store = SourceStore(file)
        store.load()
        store.add(SourceRecord("s1", "Doc", "https://d", 1L, "text one"))
        store.add(SourceRecord("s1", "Doc v2", "https://d", 2L, "text two")) // same id refreshes

        val reloaded = SourceStore(file)
        reloaded.load()
        assertEquals(1, reloaded.list().size)
        assertEquals("Doc v2", reloaded.list().first().title)
        assertEquals("text two", reloaded.list().first().text)
        file.delete()
    }

    @Test
    fun removingWorksAndSurvivesReload() = runTest {
        val file = fresh()
        val store = SourceStore(file)
        store.load()
        store.add(SourceRecord("s1", "One", "https://one", 1L, "a"))
        store.add(SourceRecord("s2", "Two", "https://two", 2L, "b"))
        assertTrue(store.remove("s1"))
        assertFalse(store.remove("s1"))

        val reloaded = SourceStore(file)
        reloaded.load()
        assertEquals(listOf("s2"), reloaded.list().map { it.id })
        file.delete()
    }

    @Test
    fun oldSourcesDropOffAtTheCap() = runTest {
        val file = fresh()
        val store = SourceStore(file)
        store.load()
        repeat(SourceStore.MAX_SOURCES + 3) { index ->
            store.add(SourceRecord("s$index", "T$index", "https://$index", index.toLong(), "x"))
        }
        assertEquals(SourceStore.MAX_SOURCES, store.list().size)
        assertEquals("s${SourceStore.MAX_SOURCES + 2}", store.list().last().id)
        file.delete()
    }
}