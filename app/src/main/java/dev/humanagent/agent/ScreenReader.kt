package dev.humanagent.agent

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/** One addressable thing on screen. [index] is stable for a given dump, not across dumps. */
data class UiElement(
    val index: Int,
    val className: String,
    val text: String,
    val description: String,
    val hint: String,
    val viewId: String?,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val focused: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val enabled: Boolean,
    val centerX: Int,
    val centerY: Int,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

data class ScreenSnapshot(
    val appLabel: String,
    val windowTitle: String,
    val packageName: String,
    val nodeCount: Int,
    val truncated: Boolean,
    val elements: List<UiElement>,
    val rendered: String,
) {
    companion object {
        fun empty(): ScreenSnapshot = ScreenSnapshot(
            appLabel = "",
            windowTitle = "",
            packageName = "",
            nodeCount = 0,
            truncated = false,
            elements = emptyList(),
            rendered = "The screen could not be read (no active window).",
        )
    }
}

/** Turns a dump into the compact text the model reads. Pure so it can be unit tested. */
object ScreenSnapshotRenderer {

    private const val MAX_TEXT = 120

    fun render(
        appLabel: String,
        windowTitle: String,
        nodeCount: Int,
        truncated: Boolean,
        elements: List<UiElement>,
    ): String {
        val header = buildString {
            append("Screen: ")
            append(appLabel.ifBlank { "unknown app" })
            if (windowTitle.isNotBlank()) append(" — ").append(windowTitle)
            append(" (")
            append(nodeCount)
            append(" nodes, ")
            append(elements.size)
            append(" addressable")
            if (truncated) append(", truncated")
            append(")")
        }
        if (elements.isEmpty()) return "$header\nNothing tappable or readable on this screen."
        return buildString {
            append(header)
            elements.forEach { element ->
                append('\n')
                append(renderElement(element))
            }
        }
    }

    private fun renderElement(element: UiElement): String = buildString {
        append('[').append(element.index).append("] ").append(element.className)
        val label = element.text.ifBlank { element.description }
        if (label.isNotBlank()) append(" \"").append(shorten(label)).append('"')
        if (element.text.isNotBlank() && element.description.isNotBlank()) {
            append(" desc=\"").append(shorten(element.description)).append('"')
        }
        if (element.hint.isNotBlank()) append(" hint=\"").append(shorten(element.hint)).append('"')
        element.viewId?.takeIf { it.isNotBlank() }?.let { append(" id=").append(it) }
        append(" @").append(element.centerX).append(',').append(element.centerY)
        if (element.clickable) append(" clickable")
        if (element.editable) append(" editable")
        if (element.scrollable) append(" scrollable")
        if (element.focused) append(" focused")
        if (element.checkable) append(if (element.checked) " checked" else " unchecked")
        if (!element.enabled) append(" disabled")
    }

    private fun shorten(value: String): String {
        val collapsed = value.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
        return if (collapsed.length <= MAX_TEXT) collapsed else collapsed.take(MAX_TEXT - 1) + "…"
    }
}

/**
 * Reads the accessibility tree in a deterministic pre-order walk. [snapshot] and [locate] share the
 * same traversal and node budget, which is what makes the indices printed for the model addressable.
 */
class ScreenReader(private val service: AgentAccessibilityService) {

    fun snapshot(maxNodes: Int = MAX_NODES): ScreenSnapshot {
        val root = service.rootInActiveWindowSafe() ?: return ScreenSnapshot.empty()
        val elements = ArrayList<UiElement>(64)
        var visited = 0
        walk(root, maxNodes) { index, node, _ ->
            visited++
            describe(index, node)?.let { elements.add(it) }
        }
        val packageName = runCatching { root.packageName?.toString().orEmpty() }.getOrDefault("")
        val appLabel = applicationLabel(packageName)
        val windowTitle = runCatching { root.window?.title?.toString().orEmpty() }.getOrDefault("")
        return ScreenSnapshot(
            appLabel = appLabel,
            windowTitle = windowTitle,
            packageName = packageName,
            nodeCount = visited,
            truncated = visited >= maxNodes,
            elements = elements,
            rendered = ScreenSnapshotRenderer.render(appLabel, windowTitle, visited, visited >= maxNodes, elements),
        )
    }

    fun locate(index: Int, maxNodes: Int = MAX_NODES): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindowSafe() ?: return null
        walk(root, maxNodes) { candidate, node, _ ->
            if (candidate == index) return node
        }
        return null
    }

    /** Finds the first node whose text or content description contains [query]. */
    fun findByText(query: String, maxNodes: Int = MAX_NODES): AccessibilityNodeInfo? {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return null
        val root = service.rootInActiveWindowSafe() ?: return null
        walk(root, maxNodes) { _, node, _ ->
            val text = runCatching { node.text?.toString().orEmpty() }.getOrDefault("")
            val description = runCatching { node.contentDescription?.toString().orEmpty() }.getOrDefault("")
            if (text.lowercase().contains(needle) || description.lowercase().contains(needle)) return node
        }
        return null
    }

    fun focusedEditable(maxNodes: Int = MAX_NODES): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindowSafe() ?: return null
        var firstEditable: AccessibilityNodeInfo? = null
        walk(root, maxNodes) { _, node, _ ->
            val editable = runCatching { node.isEditable }.getOrDefault(false)
            if (editable) {
                if (runCatching { node.isFocused }.getOrDefault(false)) return node
                if (firstEditable == null) firstEditable = node
            }
        }
        return firstEditable
    }

    /** The scrollable container to prefer, favouring the one that currently has focus. */
    fun scrollableNode(maxNodes: Int = MAX_NODES): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindowSafe() ?: return null
        var firstScrollable: AccessibilityNodeInfo? = null
        walk(root, maxNodes) { _, node, _ ->
            val scrollable = runCatching { node.isScrollable }.getOrDefault(false)
            if (scrollable) {
                if (runCatching { node.isFocused }.getOrDefault(false)) return node
                if (firstScrollable == null) firstScrollable = node
            }
        }
        return firstScrollable
    }

    /** Walks up until something clickable is found, which is how real fingers hit small icons. */
    fun clickableAncestor(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current = node
        var hops = 0
        while (current != null && hops < 8) {
            if (runCatching { current!!.isClickable }.getOrDefault(false)) return current
            current = runCatching { current!!.parent }.getOrNull()
            hops++
        }
        return null
    }

    fun centerOf(node: AccessibilityNodeInfo): Pair<Int, Int> {
        val rect = Rect().also { runCatching { node.getBoundsInScreen(it) } }
        return rect.centerX() to rect.centerY()
    }

    private fun applicationLabel(packageName: String): String {
        if (packageName.isBlank()) return ""
        return runCatching {
            val manager = service.packageManager
            val info = manager.getApplicationInfo(packageName, 0)
            manager.getApplicationLabel(info).toString()
        }.getOrDefault(packageName)
    }

    private fun describe(index: Int, node: AccessibilityNodeInfo): UiElement? = runCatching {
        if (!node.isVisibleToUser) return null
        val text = node.text?.toString().orEmpty().trim()
        val description = node.contentDescription?.toString().orEmpty().trim()
        val hint = node.hintText?.toString().orEmpty().trim()
        val clickable = node.isClickable
        val editable = node.isEditable
        val scrollable = node.isScrollable
        val focused = node.isFocused
        val checkable = node.isCheckable
        if (text.isEmpty() && description.isEmpty() && hint.isEmpty() &&
            !clickable && !editable && !scrollable && !focused && !checkable
        ) {
            return null
        }
        val rect = Rect().also { node.getBoundsInScreen(it) }
        if (rect.width() <= 0 || rect.height() <= 0) return null
        UiElement(
            index = index,
            className = node.className?.toString()?.substringAfterLast('.') ?: "View",
            text = text,
            description = description,
            hint = hint,
            viewId = node.viewIdResourceName,
            clickable = clickable,
            editable = editable,
            scrollable = scrollable,
            focused = focused,
            checkable = checkable,
            checked = checkable && node.isChecked,
            enabled = node.isEnabled,
            centerX = rect.centerX(),
            centerY = rect.centerY(),
            left = rect.left,
            top = rect.top,
            right = rect.right,
            bottom = rect.bottom,
        )
    }.getOrNull()

    private inline fun walk(
        root: AccessibilityNodeInfo,
        maxNodes: Int,
        visit: (index: Int, node: AccessibilityNodeInfo, depth: Int) -> Unit,
    ) {
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.addLast(root to 0)
        var index = 0
        var visited = 0
        while (stack.isNotEmpty() && visited < maxNodes) {
            val (node, depth) = stack.removeLast()
            visited++
            visit(index, node, depth)
            index++
            if (depth < MAX_DEPTH) {
                val childCount = runCatching { node.childCount }.getOrDefault(0)
                for (position in childCount - 1 downTo 0) {
                    val child = runCatching { node.getChild(position) }.getOrNull() ?: continue
                    stack.addLast(child to depth + 1)
                }
            }
        }
    }

    companion object {
        const val MAX_NODES = 320
        const val MAX_DEPTH = 30
    }
}
