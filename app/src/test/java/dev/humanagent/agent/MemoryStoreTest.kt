package dev.humanagent.agent

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The twin's memory: facts, people and episodes round-trip, migrate and stay bounded. */
class MemoryStoreTest {

    private fun fresh(): File = File.createTempFile("memory", ".json").also { it.delete() }

    @Test
    fun legacyNoteListMigratesToTheTwinDocument() = runTest {
        val file = fresh()
        file.writeText("""[{"key":"mum's number","value":"+1 555 0100"}]""")
        MemoryStore(file).load()
        val store = MemoryStore(file)
        store.load()
        assertEquals("+1 555 0100", store.snapshot()["mum's number"])
        assertTrue(store.render().contains("mum's number: +1 555 0100"))
        file.delete()
    }

    @Test
    fun factsReplaceByKeyAndDropTheOldestBeyondTheCap() = runTest {
        val file = fresh()
        val store = MemoryStore(file)
        store.load()
        repeat(MemoryStore.MAX_FACTS) { index -> store.put("fact $index", "value $index") }
        repeat(3) { index -> store.put("fact 10", "newer value $index") }
        store.put("brand new", "the newest fact")
        val loaded = MemoryStore(file)
        loaded.load()
        assertEquals(MemoryStore.MAX_FACTS, loaded.snapshot().size)
        assertEquals("newer value 2", loaded.snapshot()["fact 10"])
        assertEquals("the newest fact", loaded.snapshot()["brand new"])
        assertNull(loaded.snapshot()["fact 0"])
        file.delete()
    }

    @Test
    fun peopleMergeNotesAndChannelsWithoutDuplicates() = runTest {
        val file = fresh()
        val store = MemoryStore(file)
        store.load()
        store.rememberPerson("Sam", "brother", "WhatsApp", "prefers Arabic")
        store.rememberPerson("Sam", "", "WhatsApp", "never call before 9am")
        store.rememberPerson("sam", "brother", "", "")

        val loaded = MemoryStore(file)
        loaded.load()
        val people = loaded.people()
        assertEquals(1, people.size)
        val sam = people.first()
        assertEquals("Sam", sam.name)
        assertEquals("brother", sam.relation)
        assertEquals(listOf("WhatsApp"), sam.channels)
        assertEquals(2, sam.notes.size)
        assertTrue(
            loaded.renderPeople()
                .contains("- Sam (brother) — WhatsApp · prefers Arabic · never call before 9am"),
        )
        file.delete()
    }

    @Test
    fun episodesKeepTheNewestFirstForThePromptAndMarkOwedOnes() = runTest {
        val file = fresh()
        val store = MemoryStore(file)
        store.load()
        store.addEpisode("older task", "did it", "WhatsApp", Episode.STATUS_DONE)
        store.addEpisode("later task", "result never sent", "WhatsApp", Episode.STATUS_OWED)

        val loaded = MemoryStore(file)
        loaded.load()
        val recent = loaded.recentEpisodes(2)
        assertEquals("later task", recent.first().task)
        assertEquals("older task", recent.last().task)
        val aged = loaded.render(nowMs = recent.first().whenMs + 60_000)
        assertTrue(aged.contains("1m ago"))
        assertTrue(loaded.render().contains("NOT delivered yet — result never sent"))
        file.delete()
    }
}