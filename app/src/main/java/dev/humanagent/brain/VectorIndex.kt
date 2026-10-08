package dev.humanagent.brain

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The memory index: chunks of chat, episodes and facts with their vectors, in one small SQLite
 * file. Queries scan every vector (a few thousand at most) and rank with [BrainMath] — simple,
 * durable and fast enough that no ANN structure earns its complexity.
 */
class VectorIndex(private val file: File) {

    private var db: SQLiteDatabase? = null

    private fun open(): SQLiteDatabase =
        db ?: run {
            file.parentFile?.mkdirs()
            SQLiteDatabase.openOrCreateDatabase(file, null).also {
                it.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS chunks (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        ref TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        title TEXT NOT NULL,
                        text TEXT NOT NULL,
                        ts INTEGER NOT NULL,
                        vec BLOB NOT NULL
                    )
                    """.trimIndent(),
                )
                it.execSQL("CREATE INDEX IF NOT EXISTS idx_chunks_ref ON chunks(ref)")
                db = it
            }
        }

    /** Inserts or replaces one chunk ([BrainChunk.ref] is its identity). */
    fun upsert(chunk: BrainChunk, vector: FloatArray) {
        val database = open()
        database.beginTransaction()
        try {
            database.delete("chunks", "ref = ?", arrayOf(chunk.ref))
            database.execSQL(
                "INSERT INTO chunks(ref, kind, title, text, ts, vec) VALUES (?, ?, ?, ?, ?, ?)",
                arrayOf(chunk.ref, chunk.kind, chunk.title, chunk.text, chunk.ts, pack(vector)),
            )
            database.execSQL(
                "DELETE FROM chunks WHERE id NOT IN (SELECT id FROM chunks ORDER BY (ts = 0) DESC, ts DESC, id DESC LIMIT ${BrainSpec.MAX_CHUNKS})",
            )
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    /** Removes one chunk by identity. */
    fun delete(ref: String) {
        open().delete("chunks", "ref = ?", arrayOf(ref))
    }

    /** Removes every chunk whose ref starts with [prefix] (a source's parts). */
    fun deleteByRefPrefix(prefix: String) {
        open().delete("chunks", "ref LIKE ? ESCAPE '\\'", arrayOf(prefix.replace("%", "\\%").replace("_", "\\_") + "%"))
    }

    fun count(): Int {
        open().rawQuery("SELECT COUNT(*) FROM chunks", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    fun clear() {
        open().execSQL("DELETE FROM chunks")
    }

    /** The [k] chunks closest to [query], best first. */
    fun topK(query: FloatArray, k: Int, nowMs: Long): List<BrainChunk> {
        val database = open()
        val refs = mutableListOf<String>()
        val kinds = mutableListOf<String>()
        val titles = mutableListOf<String>()
        val texts = mutableListOf<String>()
        val timestamps = mutableListOf<Long>()
        val vectors = mutableListOf<FloatArray>()
        database.rawQuery("SELECT ref, kind, title, text, ts, vec FROM chunks", null).use { cursor ->
            while (cursor.moveToNext()) {
                refs.add(cursor.getString(0))
                kinds.add(cursor.getString(1))
                titles.add(cursor.getString(2))
                texts.add(cursor.getString(3))
                timestamps.add(cursor.getLong(4))
                vectors.add(unpack(cursor.getBlob(5)))
            }
        }
        return BrainMath.topK(query, vectors, timestamps, nowMs, k).map { index ->
            BrainChunk(refs[index], kinds[index], titles[index], texts[index], timestamps[index])
        }
    }

    fun close() {
        db?.close()
        db = null
    }

    private fun pack(vector: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (value in vector) buffer.putFloat(value)
        return buffer.array()
    }

    private fun unpack(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { buffer.getFloat() }
    }
}
