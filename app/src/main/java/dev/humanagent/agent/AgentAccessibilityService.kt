package dev.humanagent.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.DisplayMetrics
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * The eyes and hands of the assistant. It never reacts to events; instead the agent loop asks it
 * for the current screen and for gestures, exactly the way a person looks and then touches.
 */
class AgentAccessibilityService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private val screenshotExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "humanphone-screenshot")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 200
        }
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        screenshotExecutor.shutdownNow()
        super.onDestroy()
    }

    fun rootInActiveWindowSafe(): AccessibilityNodeInfo? = try {
        rootInActiveWindow
    } catch (e: Exception) {
        null
    }

    fun global(action: Int): Boolean = try {
        performGlobalAction(action)
    } catch (e: Exception) {
        false
    }

    /** Screen size in pixels, used to turn directions into swipe coordinates. */
    fun screenSize(): Pair<Int, Int> {
        val metrics: DisplayMetrics = resources.displayMetrics
        return metrics.widthPixels to metrics.heightPixels
    }

    /** Tap or swipe through the raw gesture API; works on anything drawn on screen. */
    suspend fun gesture(path: Path, durationMs: Long): Boolean =
        suspendCancellableCoroutine { continuation ->
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs.coerceAtLeast(20L)))
                .build()
            val dispatched = try {
                dispatchGesture(gesture, null, main)
            } catch (e: Exception) {
                false
            }
            if (!dispatched) {
                if (continuation.isActive) continuation.resume(false)
                return@suspendCancellableCoroutine
            }
            main.postDelayed(
                { if (continuation.isActive) continuation.resume(true) },
                durationMs.coerceAtLeast(20L) + 40L
            )
        }

    suspend fun tapPoint(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return gesture(path, 40L)
    }

    suspend fun swipe(
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int,
        durationMs: Long = 280L,
    ): Boolean {
        val path = Path().apply {
            moveTo(fromX.toFloat(), fromY.toFloat())
            lineTo(toX.toFloat(), toY.toFloat())
        }
        return gesture(path, durationMs)
    }

    /**
     * Captures the display and returns a downscaled JPEG as base64, or null when the platform
     * refuses the capture (screen off, secure window) or the service lacks the capability.
     */
    suspend fun captureScreenshotBase64(maxDimension: Int = 900, quality: Int = 60): String? {
        val bitmap = captureScreenshot(2_000L) ?: return null
        return withContext(Dispatchers.IO) {
            try {
                val scaled = scaleDown(bitmap, maxDimension)
                ByteArrayOutputStream().use { buffer ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, quality, buffer)
                    if (scaled !== bitmap) scaled.recycle()
                    Base64.encodeToString(buffer.toByteArray(), Base64.NO_WRAP)
                }
            } finally {
                bitmap.recycle()
            }
        }
    }

    /** Saves a screenshot to the app cache and returns the file path, or null on failure. */
    suspend fun saveScreenshot(fileName: String): String? {
        val bitmap = captureScreenshot(3_000L) ?: return null
        return withContext(Dispatchers.IO) {
            try {
                val directory = java.io.File(cacheDir, "screenshots").apply { mkdirs() }
                val file = java.io.File(directory, fileName)
                java.io.FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                file.absolutePath
            } catch (e: Exception) {
                null
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun scaleDown(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxDimension) return bitmap
        val ratio = maxDimension.toFloat() / longest.toFloat()
        val width = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private suspend fun captureScreenshot(timeoutMs: Long): Bitmap? = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { continuation ->
            val callback = object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val buffer: HardwareBuffer = result.hardwareBuffer
                    val bitmap = try {
                        Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                    } catch (e: Exception) {
                        null
                    } finally {
                        buffer.close()
                    }
                    if (continuation.isActive) continuation.resume(bitmap)
                }

                override fun onFailure(errorCode: Int) {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
            try {
                takeScreenshot(Display.DEFAULT_DISPLAY, screenshotExecutor, callback)
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }

    companion object {
        @Volatile
        var instance: AgentAccessibilityService? = null
            private set

        fun isConnected(): Boolean = instance != null

        /** Bounds of a node, empty rect when the node is gone. */
        fun boundsOf(node: AccessibilityNodeInfo): Rect = Rect().also { node.getBoundsInScreen(it) }
    }
}
