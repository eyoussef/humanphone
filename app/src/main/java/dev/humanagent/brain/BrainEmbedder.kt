package dev.humanagent.brain

import android.content.Context
import android.os.ParcelFileDescriptor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedder
import com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedderOptions
import java.io.File

/**
 * Thin wrapper over the MediaPipe Universal Embedder (LiteRT-LM) running EmbeddingGemma 2 from
 * the downloaded bundle. One engine, created lazily and kept warm; callers serialize access.
 */
class BrainEmbedder(
    private val context: Context,
    private val modelFile: File,
) {

    private var engine: UniversalEmbedder? = null

    /** 768 raw (L2-normalized) dimensions; callers truncate for storage. */
    fun embed(text: String): FloatArray {
        val active = engine ?: create().also { engine = it }
        val result = active.embedText(text)
        return result.embeddings().first().floatEmbedding()
    }

    fun close() {
        runCatching { engine?.close() }
        engine = null
    }

    private fun create(): UniversalEmbedder {
        val descriptor = ParcelFileDescriptor.open(modelFile, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            // The engine mmaps the bundle from the descriptor during creation, so the
            // descriptor can be closed right after — the mapping outlives it.
            val options = UniversalEmbedderOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder()
                        .setModelAssetFileDescriptor(descriptor.fd)
                        .build(),
                )
                .setL2Normalize(true)
                .build()
            return UniversalEmbedder.createFromOptions(context, options)
        } finally {
            descriptor.close()
        }
    }
}
