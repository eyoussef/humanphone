package dev.humanagent

import dev.humanagent.agent.ScreenSnapshotRenderer
import dev.humanagent.agent.UiElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenSnapshotRendererTest {

    @Test
    fun rendersHeaderCountsAndIndices() {
        val rendered = ScreenSnapshotRenderer.render(
            appLabel = "Messages",
            windowTitle = "Chat with Sam",
            nodeCount = 42,
            truncated = false,
            elements = listOf(element(index = 3, text = "Send", clickable = true), element(index = 9, text = "Hi")),
        )
        assertTrue(rendered.startsWith("Screen: Messages — Chat with Sam (42 nodes, 2 addressable)"))
        assertTrue(rendered.contains("[3] Button \"Send\""))
        assertTrue(rendered.contains("[9] TextView \"Hi\""))
        assertTrue(rendered.contains("clickable"))
    }

    @Test
    fun marksTruncatedDumps() {
        val rendered = ScreenSnapshotRenderer.render("App", "", 320, true, listOf(element(1, "x")))
        assertTrue(rendered.contains("truncated"))
    }

    @Test
    fun emptyScreenSaysSoInsteadOfReturningNothing() {
        val rendered = ScreenSnapshotRenderer.render("App", "", 4, false, emptyList())
        assertTrue(rendered.contains("Nothing tappable or readable"))
    }

    @Test
    fun longTextIsShortenedAndNewlinesCollapsed() {
        val long = "word ".repeat(60)
        val rendered = ScreenSnapshotRenderer.render("App", "", 1, false, listOf(element(1, "line one\nline two $long")))
        assertTrue(rendered.contains("line one line two"))
        assertTrue(rendered.contains("…"))
        assertTrue(rendered.lines().size == 2)
    }

    @Test
    fun flagsDescribeTheControlState() {
        val rendered = ScreenSnapshotRenderer.render(
            "App",
            "",
            1,
            false,
            listOf(
                element(5, "Remember me", checkable = true, checked = false),
                element(6, "", editable = true, focused = true, description = "Message"),
            ),
        )
        assertTrue(rendered.contains("unchecked"))
        assertTrue(rendered.contains("editable focused"))
        assertTrue(rendered.contains("desc=\"Message\""))
    }

    @Test
    fun disabledControlsAreMarked() {
        val rendered = ScreenSnapshotRenderer.render("App", "", 1, false, listOf(element(2, "Save", enabled = false)))
        assertTrue(rendered.contains("disabled"))
    }

    @Test
    fun viewIdIsPrintedOnlyWhenTheAppExposesOne() {
        val withId = ScreenSnapshotRenderer.render("App", "", 1, false, listOf(element(1, "Send", viewId = "com.app:id/send")))
        assertEquals(1, Regex("id=com.app:id/send").findAll(withId).count())
        val withoutId = ScreenSnapshotRenderer.render("App", "", 1, false, listOf(element(1, "Send")))
        assertTrue(!withoutId.contains("id="))
    }

    private fun element(
        index: Int,
        text: String,
        description: String = "",
        viewId: String? = null,
        clickable: Boolean = false,
        editable: Boolean = false,
        focused: Boolean = false,
        checkable: Boolean = false,
        checked: Boolean = false,
        enabled: Boolean = true,
    ) = UiElement(
        index = index,
        className = if (editable) "EditText" else "Button",
        text = text,
        description = description,
        hint = "",
        viewId = viewId,
        clickable = clickable,
        editable = editable,
        scrollable = false,
        focused = focused,
        checkable = checkable,
        checked = checked,
        enabled = enabled,
        centerX = 100,
        centerY = 200,
        left = 0,
        top = 0,
        right = 200,
        bottom = 400,
    )
}
