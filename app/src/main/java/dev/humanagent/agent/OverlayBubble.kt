package dev.humanagent.agent

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.abs

/**
 * The always-there dot the user can talk to. Tap opens the app, long-press starts listening,
 * dragging moves it wherever the thumb expects it.
 */
class OverlayBubble(
    private val context: Context,
    private val onTap: () -> Unit,
    private val onLongPress: () -> Unit,
) {

    private val windowManager: WindowManager? = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var view: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    val isShowing: Boolean get() = view != null

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (view != null) return
        main.post {
            val manager = windowManager ?: return@post
            val text = TextView(context).apply {
                text = "HP"
                setTextColor(Color.WHITE)
                textSize = 14f
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#CC4C8DFF"))
                    setStroke(3, Color.parseColor("#8BE9C0"))
                }
            }
            val size = (56 * context.resources.displayMetrics.density).toInt()
            val layoutParams = WindowManager.LayoutParams(
                size,
                size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = context.resources.displayMetrics.heightPixels / 3
            }

            val detector = GestureDetector(
                context,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(e: MotionEvent): Boolean = true
                    override fun onSingleTapUp(e: MotionEvent): Boolean {
                        onTap()
                        return true
                    }

                    override fun onLongPress(e: MotionEvent) {
                        onLongPress()
                    }
                },
            )

            val slop = (12 * context.resources.displayMetrics.density)
            var startX = 0f
            var startY = 0f
            var originX = 0
            var originY = 0
            var dragging = false

            text.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX
                        startY = event.rawY
                        originX = layoutParams.x
                        originY = layoutParams.y
                        dragging = false
                        detector.onTouchEvent(event)
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - startX
                        val dy = event.rawY - startY
                        if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                        if (dragging) {
                            layoutParams.x = (originX + dx).toInt()
                            layoutParams.y = (originY + dy).toInt()
                            runCatching { manager.updateViewLayout(text, layoutParams) }
                        }
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (!dragging) detector.onTouchEvent(event)
                        dragging = false
                    }
                }
                true
            }

            runCatching { manager.addView(text, layoutParams) }
                .onSuccess {
                    view = text
                    params = layoutParams
                }
        }
    }

    fun setLabel(label: String) {
        main.post { view?.text = label }
    }

    fun hide() {
        main.post {
            val current = view ?: return@post
            runCatching { windowManager?.removeView(current) }
            view = null
            params = null
        }
    }
}
