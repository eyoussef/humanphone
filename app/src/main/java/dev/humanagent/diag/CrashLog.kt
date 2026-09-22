package dev.humanagent.diag

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import android.provider.Settings
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Leaves a trail of what the app was doing in the phone's Downloads folder: breadcrumbs while it
 * starts (`humanphone-start.txt`) and, for every uncaught throwable, the stack trace with those
 * breadcrumbs above it (`humanphone-crash-<time>.txt`).
 *
 * The app is a debug build whose log only the system can read, and a crash dialog says nothing about
 * the cause, so these files are the only trace that can be read after the fact. Breadcrumbs are kept
 * in memory and land in the file on [flush], which is what makes a crash mid-startup visible: the
 * report carries the trail even when nothing was flushed yet.
 */
object CrashLog {

    private const val TAG = "HumanPhoneCrash"
    private val DIR = Environment.DIRECTORY_DOWNLOADS

    /** Marker rewritten on every flush, so the file shows how far the last start got. */
    const val START_FILE = "humanphone-start.txt"

    /** Breadcrumbs of this process, oldest first. */
    private val marks = StringBuilder()

    @Volatile
    private var app: Context? = null

    /** Installs the writer and marks the first step; call it before anything else can fail. */
    fun install(context: Context) {
        val application = context.applicationContext
        app = application
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        // The handler comes first: a failure while writing the marker must not leave the app blind.
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                write(
                    application,
                    "humanphone-crash-${fileStamp()}.txt",
                    crashReport(application, thread, error),
                    replace = false,
                )
            }
            // The system's own handler still has to run, or the process would hang instead of dying.
            if (previous != null) previous.uncaughtException(thread, error) else Process.killProcess(Process.myPid())
        }
        mark("application created")
        flush()
    }

    /** Records one step of the session; the trail shows up in the next flush or crash report. */
    fun mark(what: String) {
        synchronized(marks) { marks.appendLine(markText(iso(Date()), Thread.currentThread().name, what)) }
    }

    /** Writes the trail so far to the marker file; a crash without a Java throwable still leaves it. */
    fun flush() {
        val context = app ?: return
        runCatching {
            write(context, START_FILE, reportText(header(context), trail(), Thread.currentThread().name, null), replace = true)
        }
    }

    private fun crashReport(context: Context, thread: Thread, error: Throwable): String =
        reportText(header(context), trail(), thread.name, error)

    private fun trail(): String = synchronized(marks) { marks.toString() }

    /** One report: where, on what, the breadcrumbs, and the throwable with its causes. */
    internal fun reportText(header: String, marks: String, thread: String, error: Throwable?): String =
        buildString {
            append(header)
            appendLine("thread: $thread")
            appendLine()
            append(marks)
            if (error != null) {
                appendLine()
                append(stackTrace(error))
            }
        }

    internal fun markText(time: String, thread: String, what: String): String = "$time [$thread] $what"

    /** The throwable as text, causes included, without depending on android.util.Log. */
    internal fun stackTrace(error: Throwable): String {
        val text = StringWriter()
        PrintWriter(text).use { error.printStackTrace(it) }
        return text.toString()
    }

    internal fun iso(time: Date): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(time)

    /** What the app was given on this device; any failure is itself worth knowing, never fatal. */
    private fun header(context: Context): String = runCatching {
        buildString {
            appendLine("HumanPhone trail")
            appendLine("app:     ${version(context)}")
            appendLine(
                "device:  ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} " +
                    "(API ${Build.VERSION.SDK_INT}, ${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"})"
            )
            appendLine(
                "granted: microphone=${granted(context, Manifest.permission.RECORD_AUDIO)} " +
                    "notifications=${granted(context, Manifest.permission.POST_NOTIFICATIONS)} " +
                    "sms=${granted(context, Manifest.permission.SEND_SMS)} " +
                    "contacts=${granted(context, Manifest.permission.READ_CONTACTS)}"
            )
            appendLine(
                "state:   overlay=${runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)} " +
                    "recogniser=${runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)} " +
                    "accessibility=${accessibility(context)}"
            )
            appendLine("pid:     ${Process.myPid()}")
        }
    }.getOrDefault("HumanPhone trail (device details unavailable)\n")

    /** Whether HumanPhone's own accessibility service is switched on, without listing the others. */
    private fun accessibility(context: Context): String = runCatching {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )
        if (enabled?.contains(context.packageName) == true) "on" else "off"
    }.getOrDefault("unknown")

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Puts [text] in Downloads, falling back to app storage when the phone is not unlocked yet. */
    private fun write(context: Context, name: String, text: String, replace: Boolean) {
        runCatching {
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val resolver = context.contentResolver
            if (replace) {
                resolver.delete(
                    collection,
                    "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                    arrayOf(name, "$DIR/"),
                )
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, DIR)
            }
            val uri = resolver.insert(collection, values) ?: error("Downloads refused a $name entry")
            resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: error("no stream for $name")
        }.onFailure { failure ->
            Log.w(TAG, "could not write $name to Downloads", failure)
            runCatching {
                File(context.getExternalFilesDir(null) ?: context.filesDir, name).writeText(text)
            }
        }
    }

    private fun fileStamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())

    private fun version(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${context.packageName} ${info.versionName} (${info.longVersionCode})"
    }.getOrDefault(context.packageName)
}
