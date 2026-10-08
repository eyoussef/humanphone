package dev.humanagent.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class BrainMathTest {

    private fun unit(vararg values: Float): FloatArray {
        val norm = sqrt(values.fold(0f) { acc, v -> acc + v * v })
        return FloatArray(values.size) { values[it] / norm }
    }

    @Test
    fun truncationKeepsLeadingDimensionsAndRenormalizes() {
        // 3-4-5 style check: [3,4,0...] truncated to 2 dims must become [0.6, 0.8].
        val vector = floatArrayOf(3f, 4f, 99f, 99f)
        val truncated = BrainMath.truncateRenormalize(vector, 2)
        assertEquals(2, truncated.size)
        assertEquals(0.6f, truncated[0], 1e-5f)
        assertEquals(0.8f, truncated[1], 1e-5f)
        // Re-normalized to unit length — the model card warns skipping this silently ruins ranking.
        val norm = sqrt(truncated.fold(0f) { acc, v -> acc + v * v })
        assertEquals(1f, norm, 1e-5f)
    }

    @Test
    fun zeroVectorDoesNotExplode() {
        val truncated = BrainMath.truncateRenormalize(FloatArray(768), BrainSpec.DIMS)
        assertEquals(BrainSpec.DIMS, truncated.size)
        assertTrue(truncated.all { it == 0f })
    }

    @Test
    fun identicalDirectionsScoreHighest() {
        val query = unit(1f, 1f, 0f)
        val same = unit(1f, 1f, 0f)
        val opposite = unit(-1f, -1f, 0f)
        val now = System.currentTimeMillis()
        assertTrue(BrainMath.score(query, same, 0, now) > BrainMath.score(query, opposite, 0, now))
    }

    @Test
    fun recencyBreaksTiesButStaysSmall() {
        val query = unit(1f, 0f)
        val candidate = unit(1f, 0f)
        val now = System.currentTimeMillis()
        val fresh = BrainMath.score(query, candidate, now, now)
        val old = BrainMath.score(query, candidate, now - 365L * 86_400_000, now)
        assertTrue(fresh > old)
        // The boost is a nudge, never the main signal.
        assertTrue(fresh - old <= BrainMath.RECENCY_BOOST + 1e-6f)
        assertTrue(abs(fresh) <= 1f + BrainMath.RECENCY_BOOST + 1e-6f)
    }

    @Test
    fun topKOrdersBestFirstAndKeepsStableIndices() {
        val query = unit(1f, 0f)
        val vectors = listOf(unit(0f, 1f), unit(1f, 0f), unit(0.5f, 0.5f))
        val timestamps = listOf(0L, 0L, 0L)
        val best = BrainMath.topK(query, vectors, timestamps, System.currentTimeMillis(), 2)
        assertEquals(listOf(1, 2), best)
    }

    @Test
    fun topKHandlesEmptyAndZeroK() {
        assertTrue(BrainMath.topK(floatArrayOf(1f), emptyList(), emptyList(), 0, 3).isEmpty())
        val vectors = listOf(unit(1f, 0f))
        assertTrue(BrainMath.topK(floatArrayOf(1f), vectors, listOf(0L), 0, 0).isEmpty())
    }
}