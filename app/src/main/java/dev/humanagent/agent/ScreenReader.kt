package dev.humanagent.agent

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

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

/** A text match together with the index the current dump prints for it. */
data class TextMatch(
    val index: Int,
    val node: AccessibilityNodeInfo,
    val label: String,
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
        windowCount: Int = 1,
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
            if (windowCount > 1) append(", ").append(windowCount).append(" windows")
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
 * Reads every interactive window — the app, but also the bottom sheets, dialogs and pickers that
 * live in their own windows — in one deterministic pre-order walk. [snapshot], [locate] and the
 * text lookups all share that walk, which is what makes the indices printed for the model
 * addressable by [locate].
 */
class ScreenReader(private val service: AgentAccessibilityService) {

    fun snapshot(maxNodes: Int = MAX_NODES): ScreenSnapshot {
        val roots = orderedRoots()
        if (roots.isEmpty()) return ScreenSnapshot.empty()
        val elements = ArrayList<UiElement>(64)
        var visited = 0
        var windowsRead = 0
        var primaryWindow = -1
        var currentWindow = -1
        walkWindows(roots, maxNodes) { index, node, windowIndex ->
            visited++
            if (windowIndex != currentWindow) {
                currentWindow = windowIndex
                windowsRead++
                if (primaryWindow < 0) primaryWindow = windowIndex
            }
            describe(index, node)?.let { elements.add(it) }
        }
        if (primaryWindow < 0) return ScreenSnapshot.empty()
        val primary = roots[primaryWindow]
        val packageName = runCatching { primary.packageName?.toString().orEmpty() }.getOrDefault("")
        val appLabel = applicationLabel(packageName)
        val windowTitle = runCatching { primary.window?.title?.toString().orEmpty() }.getOrDefault("")
        return ScreenSnapshot(
            appLabel = appLabel,
            windowTitle = windowTitle,
            packageName = packageName,
            nodeCount = visited,
            truncated = visited >= maxNodes,
            elements = elements,
            rendered = ScreenSnapshotRenderer.render(
                appLabel = appLabel,
                windowTitle = windowTitle,
                nodeCount = visited,
                truncated = visited >= maxNodes,
                elements = elements,
                windowCount = windowsRead,
            ),
        )
    }

    fun locate(index: Int, maxNodes: Int = MAX_NODES): AccessibilityNodeInfo? {
        val roots = orderedRoots()
        if (roots.isEmpty()) return null
        walkWindows(roots, maxNodes) { candidate, node, _ ->
            if (candidate == index) return node
        }
        return null
    }

    /** Finds the first node whose text, content description or hint contains [query]. */
    fun findByText(query: String, maxNodes: Int = MAX_NODES): AccessibilityNodeInfo? {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return null
        val roots = orderedRoots()
        if (roots.isEmpty()) return null
        walkWindows(roots, maxNodes) { _, node, _ ->
            if (labelOf(node).lowercase().contains(needle)) return node
        }
        return null
    }

    /** Finds the first node whose label satisfies [predicate], used for label-shaped searches. */
    fun findByLabel(predicate: (String) -> Boolean, maxNodes: Int = MAX_NODES): AccessibilityNodeInfo? {
        val roots = orderedRoots()
        if (roots.isEmpty()) return null
        walkWindows(roots, maxNodes) { _, node, _ ->
            val label = labelOf(node)
            if (label.isNotEmpty() && predicate(label)) return node
        }
        return null
    }

    /**
     * Every node whose label contains [query] with the index the dump prints for it, ordered by
     * exact label first, then shorter label, then screen order.
     */
    fun findMatches(query: String, maxMatches: Int = 8, maxNodes: Int = MAX_NODES): List<TextMatch> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty() || maxMatches <= 0) return emptyList()
        val roots = orderedRoots()
        if (roots.isEmpty()) return emptyList()
        val matches = ArrayList<TextMatch>(16)
        walkWindows(roots, maxNodes) { index, node, _ ->
            if (matches.size >= 400) return@walkWindows
            val label = labelOf(node)
            if (label.lowercase().contains(needle)) {
                matches.add(TextMatch(index = index, node = node, label = label))
            }
        }
        matches.sortWith(
            compareBy<TextMatch> { if (it.label.trim().equals(query.trim(), ignoreCase = true)) 0 else 1 }
                .thenBy { it.label.length }
                .thenBy { it.index }
        )
        return matches.take(maxMatches)
    }

    /** The nodes matching [query], in the same order as [findMatches]. */
    fun findAllByText(query: String, maxMatches: Int = 8): List<AccessibilityNodeInfo> =
        findMatches(query, maxMatches).map { it.node }

    fun focusedEditable(maxNodes: Int = MAX_NODES): AccessibilityNodeInfo? {
        val roots = orderedRoots()
        if (roots.isEmpty()) return null
        var firstEditable: AccessibilityNodeInfo? = null
        walkWindows(roots, maxNodes) { _, node, _ ->
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
        val roots = orderedRoots()
        if (roots.isEmpty()) return null
        var firstScrollable: AccessibilityNodeInfo? = null
        walkWindows(roots, maxNodes) { _, node, _ ->
            val scrollable = runCatching { node.isScrollable }.getOrDefault(false)
            if (scrollable) {
                if (runCatching { node.isFocused }.getOrDefault(false)) return node
                if (firstScrollable == null) firstScrollable = node
            }
        }
        return firstScrollable
    }

    /** Every scrollable container in dump order, so a specific strip can be scrolled on its own. */
    fun scrollableNodes(maxNodes: Int = MAX_NODES): List<AccessibilityNodeInfo> {
        val roots = orderedRoots()
        if (roots.isEmpty()) return emptyList()
        val found = ArrayList<AccessibilityNodeInfo>(8)
        walkWindows(roots, maxNodes) { _, node, _ ->
            if (runCatching { node.isScrollable }.getOrDefault(false)) found.add(node)
        }
        return found
    }

    /** App label and package of the window the user is looking at, without building a whole dump. */
    fun foregroundApp(): Pair<String, String> {
        val primary = orderedRoots().firstOrNull() ?: return "" to ""
        val packageName = runCatching { primary.packageName?.toString().orEmpty() }.getOrDefault("")
        return applicationLabel(packageName) to packageName
    }

    /** The label the model sees for a node: its text, else its description, else its hint. */
    fun labelOf(node: AccessibilityNodeInfo): String {
        val text = runCatching { node.text?.toString().orEmpty() }.getOrDefault("").trim()
        if (text.isNotEmpty()) return text
        val description = runCatching { node.contentDescription?.toString().orEmpty() }.getOrDefault("").trim()
        if (description.isNotEmpty()) return description
        return runCatching { node.hintText?.toString().orEmpty() }.getOrDefault("").trim()
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

    fun boundsOf(node: AccessibilityNodeInfo): Rect = Rect().also { runCatching { node.getBoundsInScreen(it) } }

    fun centerOf(node: AccessibilityNodeInfo): Pair<Int, Int> {
        val rect = boundsOf(node)
        return rect.centerX() to rect.centerY()
    }

    /**
     * The roots of every interactive window, most recently focused first, so the topmost window and
     * the app itself are read before anything they cover.
     */
    private fun orderedRoots(): List<AccessibilityNodeInfo> {
        val windows: List<AccessibilityWindowInfo> = runCatching { service.windows }.getOrNull().orEmpty()
        val ordered = if (windows.size <= 1) {
            windows
        } else {
            windows.sortedWith(
                compareByDescending<AccessibilityWindowInfo> { runCatching { it.isActive }.getOrDefault(false) }
                    .thenByDescending { runCatching { it.isFocused }.getOrDefault(false) }
                    .thenByDescending { runCatching { it.layer }.getOrDefault(0) }
            )
        }
        val roots = ArrayList<AccessibilityNodeInfo>(ordered.size)
        ordered.forEach { window ->
            runCatching { window.root }.getOrNull()?.let { roots.add(it) }
        }
        if (roots.isEmpty()) {
            service.rootInActiveWindowSafe()?.let { roots.add(it) }
        }
        return roots
    }

    /**
     * The single traversal: every window root in order, each walked in pre-order down to
     * [MAX_DEPTH], stopping at [maxNodes] nodes read and skipping nodes that repeat an earlier one
     * (same class, bounds and text, typical when a window is reported twice).
     */
    private inline fun walkWindows(
        roots: List<AccessibilityNodeInfo>,
        maxNodes: Int,
        visit: (index: Int, node: AccessibilityNodeInfo, windowIndex: Int) -> Unit,
    ) {
        if (maxNodes <= 0) return
        val seen = HashSet<String>(256)
        var index = 0
        var visited = 0
        for ((windowIndex, root) in roots.withIndex()) {
            if (visited >= maxNodes) return
            val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
            stack.addLast(root to 0)
            while (stack.isNotEmpty() && visited < maxNodes) {
                val (node, depth) = stack.removeLast()
                visited++
                if (isDistinct(node, seen)) {
                    visit(index, node, windowIndex)
                    index++
                }
                if (depth < MAX_DEPTH) {
                    val childCount = runCatching { node.childCount }.getOrDefault(0)
                    for (position in childCount - 1 downTo 0) {
                        val child = runCatching { node.getChild(position) }.getOrNull() ?: continue
                        stack.addLast(child to depth + 1)
                    }
                }
            }
        }
    }

    private fun isDistinct(node: AccessibilityNodeInfo, seen: MutableSet<String>): Boolean {
        val rect = boundsOf(node)
        val key = buildString(48) {
            append(runCatching { node.className?.toString().orEmpty() }.getOrDefault(""))
            append('|')
            append(rect.left).append(',').append(rect.top).append(',')
            append(rect.right).append(',').append(rect.bottom)
            append('|')
            append(runCatching { node.text?.toString().orEmpty() }.getOrDefault(""))
        }
        return seen.add(key)
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

    companion object {
        const val MAX_NODES = 500
        const val MAX_DEPTH = 30
    }
}
