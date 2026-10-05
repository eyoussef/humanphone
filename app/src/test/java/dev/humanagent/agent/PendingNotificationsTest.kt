package dev.humanagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingNotificationsTest {

    private fun event(packageName: String) = NotificationEvent(
        packageName = packageName,
        appName = packageName,
        title = "title",
        text = "text",
        whenMs = 0L,
    )

    @Test
    fun replaysInArrivalOrderThenStops() {
        val queue = PendingNotifications()
        queue.offer(event("first"))
        queue.offer(event("second"))
        assertEquals("first", queue.drainOne()?.packageName)
        assertEquals("second", queue.drainOne()?.packageName)
        assertNull(queue.drainOne())
    }

    @Test
    fun overflowDropsTheOldest() {
        val queue = PendingNotifications()
        repeat(PendingNotifications.PENDING_LIMIT + 1) { index -> queue.offer(event("n$index")) }
        assertEquals(PendingNotifications.PENDING_LIMIT, queue.size)
        assertEquals("n1", queue.drainOne()?.packageName)
    }

    @Test
    fun clearEmptiesTheQueue() {
        val queue = PendingNotifications()
        queue.offer(event("one"))
        queue.clear()
        assertEquals(0, queue.size)
        assertNull(queue.drainOne())
    }
}