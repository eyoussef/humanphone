package dev.humanagent.doc

import android.content.Context
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The file layer for documents the assistant writes: every document is a directory under
 * `files/docs/<slug>/` holding project.json (the name and sections written so far) and the
 * rendered deliverables. Section caps keep a runaway run from producing unbounded files.
 */
class DocWorkspace(private val root: File) {

    constructor(context: Context) : this(File(context.filesDir, "docs"))

    private var current: Project? = null

    /** Creates a fresh document directory and makes it the target of later calls. */
    fun create(name: String): File {
        val slug = slugify(name)
        val dir = File(root, slug).apply { mkdirs() }
        val display = name.trim()
        writeProject(dir, StoredProject(name = display, sections = emptyList()))
        current = Project(slug, dir, display, mutableListOf())
        return dir
    }

    /** Opens the document, creating it first when it is new; later calls act on it. */
    fun open(name: String): File {
        val slug = slugify(name)
        val dir = File(root, slug).apply { mkdirs() }
        val stored = readProject(dir)
        if (stored == null) {
            val display = name.trim()
            writeProject(dir, StoredProject(name = display, sections = emptyList()))
            current = Project(slug, dir, display, mutableListOf())
        } else {
            val sections = stored.sections.mapTo(mutableListOf()) { DocSection(it.title, it.body) }
            current = Project(slug, dir, stored.name, sections)
        }
        return dir
    }

    fun currentDir(): File? = current?.dir

    /** The current document name ("" when none). */
    fun name(): String = current?.name ?: ""

    /** Appends one section, capped at [MAX_SECTIONS] sections, [MAX_TITLE_CHARS] title and [MAX_BODY_CHARS] body characters. */
    fun addSection(title: String, body: String) {
        val project = checkNotNull(current) { "open a document first" }
        require(project.sections.size < MAX_SECTIONS) { "a document holds at most $MAX_SECTIONS sections" }
        project.sections.add(DocSection(title.take(MAX_TITLE_CHARS), body.take(MAX_BODY_CHARS)))
        writeProject(project.dir, project.stored())
    }

    fun sections(): List<DocSection> = current?.sections?.toList() ?: emptyList()

    /** Renders the document as "pdf" or "docx" (case-insensitive) into the project directory. */
    fun render(format: String): File {
        val project = checkNotNull(current) { "open a document first" }
        val out = when (format.trim().lowercase()) {
            "pdf" -> File(project.dir, "${project.slug}.pdf").also { Pdf.write(project.name, project.sections, it) }
            "docx" -> File(project.dir, "${project.slug}.docx").also { Docx.write(project.name, project.sections, it) }
            else -> throw IllegalArgumentException("format must be \"pdf\" or \"docx\"")
        }
        return out
    }

    /** Every document as "name — N sections [rendered files…]". */
    fun list(): String {
        val dirs = root.listFiles()?.filter { File(it, PROJECT_FILE).isFile }?.sortedBy { it.name }.orEmpty()
        if (dirs.isEmpty()) return "No documents yet."
        return dirs.joinToString("\n") { dir ->
            val stored = readProject(dir)
            val rendered = dir.listFiles()
                ?.filter { it.isFile && (it.name.endsWith(".pdf") || it.name.endsWith(".docx")) }
                ?.map { it.name }
                ?.sorted()
                .orEmpty()
            val files = if (rendered.isEmpty()) "" else " [${rendered.joinToString(", ")}]"
            "${stored?.name ?: dir.name} — ${stored?.sections?.size ?: 0} sections$files"
        }
    }

    private class Project(
        val slug: String,
        val dir: File,
        val name: String,
        val sections: MutableList<DocSection>,
    ) {
        fun stored() = StoredProject(name, sections.map { StoredSection(it.title, it.body) })
    }

    private fun readProject(dir: File): StoredProject? {
        val raw = runCatching { File(dir, PROJECT_FILE).readText() }.getOrNull() ?: return null
        return runCatching { json.decodeFromString<StoredProject>(raw) }.getOrNull()
    }

    private fun writeProject(dir: File, project: StoredProject) {
        File(dir, PROJECT_FILE).writeText(json.encodeToString(project))
    }

    companion object {
        const val MAX_SECTIONS = 80
        const val MAX_TITLE_CHARS = 120
        const val MAX_BODY_CHARS = 20_000

        private const val PROJECT_FILE = "project.json"
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

        /** Lowercase letters, digits and dashes; everything else becomes a dash. */
        private fun slugify(name: String): String =
            name.lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
                .take(40)
                .ifEmpty { "document" }
    }
}

@Serializable
private data class StoredSection(val title: String = "", val body: String = "")

@Serializable
private data class StoredProject(val name: String = "", val sections: List<StoredSection> = emptyList())
