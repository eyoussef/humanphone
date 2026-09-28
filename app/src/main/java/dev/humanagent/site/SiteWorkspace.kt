package dev.humanagent.site

import java.io.File

/**
 * The file layer for websites the assistant builds on the phone: every site is a real directory
 * of HTML/CSS/JS/image files under `files/sites/<slug>/`, so what the agent produces is a
 * deliverable that can be served, zipped or uploaded, not text trapped in a chat.
 *
 * [writeFile] and [fileFor] refuse any path that escapes the site directory, and downloaded
 * images always land in `images/` so an HTML file can reference them as `images/<name>`.
 */
class SiteWorkspace(private val root: File) {

    /** The site later site-tool calls act on when they omit the site argument. */
    @Volatile
    var current: String = DEFAULT_SITE
        private set

    constructor(context: android.content.Context) : this(File(context.filesDir, "sites"))

    /** Creates (or keeps) the site directory and makes it the target of later calls. */
    fun open(site: String): File {
        val slug = slugify(site)
        val dir = File(root, slug)
        val rootCanon = rootDir().canonicalPath + File.separator
        require(dir.canonicalPath.startsWith(rootCanon)) { "site path escapes workspace" }
        dir.mkdirs()
        current = slug
        return dir
    }

    fun currentDir(): File = dirFor(null)

    /** Writes one file into the site, creating parent folders; returns the path relative to the site root. */
    fun writeFile(site: String?, relativePath: String, content: String): String {
        val dir = dirFor(site)
        val target = guarded(dir, relativePath)
        target.parentFile?.mkdirs()
        target.writeText(content)
        return target.relativeTo(dir).invariantSeparatorsPath
    }

    /** Reads a file back for checks; anything unreadable (missing or escaping) is null. */
    fun readFile(site: String?, relativePath: String): String? =
        runCatching {
            val target = guarded(dirFor(site), relativePath)
            if (target.isFile) target.readText() else null
        }.getOrNull()

    /** Every file in the site as relative paths with sizes. */
    fun list(site: String?): String {
        val dir = dirFor(site)
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        if (files.isEmpty()) return "The site directory is empty; write index.html first."
        return files.joinToString("\n") { file ->
            val relative = file.relativeTo(dir).invariantSeparatorsPath
            val size = if (file.length() < 1024) "${file.length()} B" else "${file.length() / 1024} KB"
            "$relative ($size)"
        }
    }

    /** The absolute path of a site file, for the server and for attachments. */
    fun fileFor(site: String?, relativePath: String): File = guarded(dirFor(site), relativePath)

    /** The site directory itself, for the preview server root. */
    fun dir(site: String?): File = dirFor(site)

    /** Saves already-downloaded image bytes under images/; returns the path HTML references. */
    fun saveImage(site: String?, fileName: String, bytes: ByteArray, extension: String): String {
        val dir = dirFor(site)
        val base = slugify(fileName).ifEmpty { "image" }
        val ext = extension.trim().removePrefix(".").lowercase().take(5).ifEmpty { "jpg" }
        val target = guarded(dir, "images/$base.$ext")
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
        return target.relativeTo(dir).invariantSeparatorsPath
    }

    fun rootDir(): File = root.apply { mkdirs() }

    private fun dirFor(site: String?): File {
        val slug = if (site.isNullOrBlank()) current else slugify(site)
        current = slug
        return File(root, slug).apply { mkdirs() }
    }

    private fun guarded(dir: File, relativePath: String): File {
        val cleaned = relativePath.trim().removePrefix("/").take(240)
        require(cleaned.isNotEmpty()) { "file path is required" }
        require(!cleaned.contains("..")) { "file path may not contain \"..\"" }
        require(!cleaned.contains('\\')) { "use / as the separator" }
        val target = File(dir, cleaned)
        val dirCanon = dir.canonicalPath + File.separator
        require(target.canonicalPath.startsWith(dirCanon)) { "file path escapes the site directory" }
        return target
    }

    companion object {
        const val DEFAULT_SITE = "website"

        /** Lowercase letters, digits and dashes; everything else becomes a dash. */
        fun slugify(name: String): String =
            name.lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
                .take(40)
                .ifEmpty { DEFAULT_SITE }
    }
}