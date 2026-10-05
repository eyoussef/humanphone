package dev.humanagent.agent

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * One thing the assistant still owes, and where it must land. The bug this class exists for:
 * a task that says "look it up and reply in the same conversation" used to live only inside
 * the model's context window. Once the loop trimmed the transcript, the instruction was gone
 * and the agent finished happily without ever sending the result — the work was done, the
 * answer was lost. An obligation written here survives trimming, the run ending, and the
 * process being killed: it is re-rendered into every step prompt and re-attempted until it is
 * confirmed delivered or abandoned as failed.
 */
@Serializable
data class Obligation(
    val id: String,
    val openedAtMs: Long,
    /** What was asked, verbatim, so a later run can pick the thread back up. */
    val task: String,
    /** Where the result must be typed, e.g. "the WhatsApp conversation with Sam". */
    val destination: String,
    /** The app the conversation lives in, e.g. WhatsApp. */
    val app: String,
    /** What to send; filled in as soon as the model knows the answer. */
    val result: String = "",
    /** Set only after confirm_delivered observed the text on screen (or it was insisted upon). */
    val delivered: Boolean = false,
    val attempts: Int = 0,
    val abandonedAtMs: Long = 0,
) {
    /** An obligation the watchdog must keep trying for: owed, not yet delivered, not given up. */
    val open: Boolean get() = !delivered && abandonedAtMs == 0L && result.isNotBlank()

    /** Something to work on even before the result is known: the task itself. */
    val pending: Boolean get() = !delivered && abandonedAtMs == 0L && result.isBlank()
}

@Serializable
private data class LedgerDocument(val obligations: List<Obligation> = emptyList())

class RunLedger(private val file: File) {

    private val mutex = Mutex()
    private var obligations = mutableListOf<Obligation>()

    constructor(context: Context) : this(File(context.filesDir, "ledger.json"))

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            obligations = runCatching {
                if (file.exists()) json.decodeFromString<LedgerDocument>(file.readText()).obligations.toMutableList()
                else mutableListOf()
            }.getOrDefault(mutableListOf())
                .filter { System.currentTimeMillis() - it.openedAtMs <= MAX_AGE_MS }
                .takeLast(MAX_OBLIGATIONS)
                .toMutableList()
        }
    }

    /**
     * Opens the obligation a delivery run must close. Called when Auto mode decides a task:
     * from this moment the result not being sent is a tracked failure, not a forgotten one.
     */
    suspend fun open(task: String, destination: String, app: String): Obligation = withContext(Dispatchers.IO) {
        mutex.withLock {
            val obligation = Obligation(
                id = "o" + System.currentTimeMillis().toString(36),
                openedAtMs = System.currentTimeMillis(),
                task = task.trim().take(TASK_MAX),
                destination = destination.trim().take(120).ifBlank { "the same conversation" },
                app = app.trim().take(40),
            )
            obligations = obligations
                // One obligation per destination, whatever its state: a re-mention refreshes
                // the live one instead of piling up siblings nobody will deliver.
                .filterNot {
                    !it.delivered && it.abandonedAtMs == 0L &&
                        it.destination == obligation.destination && it.app == obligation.app
                }
                .toMutableList()
            obligations.add(obligation)
            obligations = obligations.takeLast(MAX_OBLIGATIONS).toMutableList()
            persistLocked()
            obligation
        }
    }

    /** The obligation a run is working on: the open one for this destination, else any open one. */
    fun current(app: String = "", destination: String = ""): Obligation? {
        val wanted = obligations.firstOrNull { it.open && it.app == app && it.destination == destination }
        return wanted ?: obligations.firstOrNull { it.open }
    }

    /** Records what must be sent, so a crash after this point cannot lose the answer. */
    suspend fun setResult(id: String, result: String): Obligation? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val updated = obligations.firstOrNull { it.id == id }?.copy(result = result.trim().take(RESULT_MAX))
            if (updated != null) {
                obligations = obligations.map { if (it.id == id) updated else it }.toMutableList()
                persistLocked()
            }
            updated
        }
    }

    suspend fun markDelivered(id: String): Obligation? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val updated = obligations.firstOrNull { it.id == id }?.copy(delivered = true)
            if (updated != null) {
                obligations = obligations.map { if (it.id == id) updated else it }.toMutableList()
                persistLocked()
            }
            updated
        }
    }

    suspend fun recordAttempt(id: String): Obligation? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val existing = obligations.firstOrNull { it.id == id }
            val updated = existing?.let {
                val attempts = it.attempts + 1
                if (attempts >= MAX_ATTEMPTS) it.copy(attempts = attempts, abandonedAtMs = System.currentTimeMillis())
                else it.copy(attempts = attempts)
            }
            if (updated != null) {
                obligations = obligations.map { if (it.id == id) updated else it }.toMutableList()
                persistLocked()
            }
            updated
        }
    }

    fun openObligations(): List<Obligation> = obligations.filter { it.open || it.pending }

    /** The prompt block: what is still owed, so the model keeps seeing the contract. */
    fun render(): String {
        val open = openObligations()
        if (open.isEmpty()) return ""
        return buildString {
            append("Open obligations — a result was promised to someone and not delivered yet:\n")
            open.forEach { obligation ->
                append("- Send \"${obligation.result.take(160)}\" to ${obligation.destination}")
                if (obligation.app.isNotBlank()) append(" in ${obligation.app}")
                append(" (attempt ${obligation.attempts + 1})")
                if (obligation.attempts > 0) {
                    append("; earlier attempts failed — read the screen and find another way to reach the conversation.")
                }
                append('\n')
            }
            append("Send owed results before anything else, then confirm_delivered with the exact text you sent.")
        }
    }

    private fun persistLocked() {
        runCatching { file.writeText(json.encodeToString(LedgerDocument(obligations.toList()))) }
    }

    companion object {
        const val MAX_OBLIGATIONS = 8
        const val MAX_ATTEMPTS = 3
        const val TASK_MAX = 400
        const val RESULT_MAX = 600

        /** A day-old silence is the user's decision, not the assistant's to break. */
        const val MAX_AGE_MS = 24 * 60 * 60 * 1000L

        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = false }
    }
}