package dev.humanagent.agent

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The bus must not drop the notification that woke the agent service: on a cold start the
 * accessibility service hears the message before the agent service has a listener attached.
 */
class NotificationBusTest {

    private fun event(packageName: String) = NotificationEvent(
        packageName = packageName,
        appName = packageName,
        title = "title",
        text = "text",
        whenMs = 0L,
    )

    @Test
    fun anEventPublishedBeforeAnyListenerWaitsForTheNextOne() = runBlocking {
        NotificationBus.publish(event("wake"))
        val received = withTimeout(2_000) { NotificationBus.events.first() }
        assertEquals("wake", received.packageName)
    }

    @Test
    fun bufferedEventsArriveInOrderAndTheOldestAreDroppedWhenFull() = runBlocking {
        val capacity = NotificationBus.BUFFER_LIMIT
        repeat(capacity + 3) { index -> NotificationBus.publish(event("n$index")) }
        val received = withTimeout(2_000) { NotificationBus.events.take(capacity).toList() }
        assertEquals(capacity, received.size)
        assertEquals("n3", received.first().packageName)
        assertEquals("n${capacity + 2}", received.last().packageName)
    }
}
