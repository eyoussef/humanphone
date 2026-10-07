package dev.humanagent.agent

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The launch memory: remembers where apps live, updates, and stays bounded. */
class AppLaunchCacheTest {

    private fun fresh(): File = File.createTempFile("launches", ".json").also { it.delete() }

    @Test
    fun learnedNamesOpenTheSamePackageEveryTime() = runTest {
        val file = fresh()
        val cache = AppLaunchCache(file)
        cache.load()
        cache.remember("WhatsApp", "com.whatsapp")
        cache.remember("wattsapp", "com.whatsapp") // the model's spelling of the day

        val reloaded = AppLaunchCache(file)
        reloaded.load()
        assertEquals("com.whatsapp", reloaded.lookup("WhatsApp"))
        assertEquals("com.whatsapp", reloaded.lookup("  WHATSAPP  ")) // case and whitespace free
        assertEquals("com.whatsapp", reloaded.lookup("wattsapp"))
        assertNull(reloaded.lookup("Telegram"))
        file.delete()
    }

    @Test
    fun relearningAnAppReplacesTheOldPackage() = runTest {
        val file = fresh()
        val cache = AppLaunchCache(file)
        cache.load()
        cache.remember("photos", "com.old.photos")
        cache.remember("photos", "com.google.android.apps.photos")

        val reloaded = AppLaunchCache(file)
        reloaded.load()
        assertEquals("com.google.android.apps.photos", reloaded.lookup("photos"))
        assertEquals(1, reloaded.snapshot().size)
        file.delete()
    }

    @Test
    fun memoryStaysBoundedAndKeepsTheAppsActuallyUsed() = runTest {
        val file = fresh()
        val cache = AppLaunchCache(file)
        cache.load()
        cache.remember("daily", "com.daily.app")
        repeat(AppLaunchCache.MAX_ENTRIES) { index -> cache.remember("app $index", "pkg.$index") }
        cache.remember("daily", "com.daily.app") // used again: stays even though it is old

        val reloaded = AppLaunchCache(file)
        reloaded.load()
        assertEquals(AppLaunchCache.MAX_ENTRIES, reloaded.snapshot().size)
        assertEquals("com.daily.app", reloaded.lookup("daily"))
        assertNull(reloaded.lookup("app 0")) // the oldest one-off dropped off
        file.delete()
    }

    @Test
    fun corruptFileReadsAsNoExperience() = runTest {
        val file = fresh()
        file.writeText("{not json at all")
        val cache = AppLaunchCache(file)
        cache.load()
        assertTrue(cache.snapshot().isEmpty())
        assertNull(cache.lookup("anything"))
        file.delete()
    }
}