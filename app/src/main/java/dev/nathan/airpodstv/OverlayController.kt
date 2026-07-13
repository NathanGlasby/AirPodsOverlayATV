package dev.nathan.airpodstv

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Compact tvOS-style discovery pill drawn over the foreground app.
 *
 * The full capsule is the only focus target. Its artwork, device name, status
 * icon, and status label all use fixed geometry, so connection state changes do
 * not resize the pill or move the text. BACK dismisses the overlay.
 */
class OverlayController(
    private val context: Context,
    private val onConnect: () -> Unit,
    private val onDismiss: () -> Unit,
) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density

    private var root: FrameLayout? = null
    private var pillView: LinearLayout? = null
    private var subtitleView: TextView? = null
    private var statusIndicator: StatusIndicatorView? = null
    private var timeoutRunnable: Runnable? = null
    private var canConnect = false

    val isShowing: Boolean get() = root != null

    private fun dp(v: Int): Int = (v * density).toInt()
    private fun dpF(v: Float): Float = v * density

    private companion object {
        val PILL_BG = 0xD915191F.toInt()
        val PILL_FOCUSED = 0xE63A3F48.toInt()
        val PILL_STROKE = 0x18FFFFFF
        val PILL_FOCUSED_STROKE = 0x38FFFFFF
        val TEXT_PRIMARY = 0xFFF4F4F7.toInt()
        val TEXT_SECONDARY = 0xFF9D9DA5.toInt()
        val GREEN = 0xFF64D987.toInt()
        val RED = 0xFFE5544B.toInt()
    }

    /**
     * @param withButtons false shows a transient, non-focusable information pill.
     */
    fun show(deviceName: String, timeoutSec: Int, withButtons: Boolean = true): Boolean {
        if (isShowing) return true
        if (!Settings.canDrawOverlays(context)) return false
        canConnect = withButtons

        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pillBackground()
            setPadding(dp(8), dp(5), dp(15), dp(5))
            elevation = dpF(12f)
            isFocusable = withButtons
            isFocusableInTouchMode = withButtons
            isClickable = withButtons
            setOnClickListener {
                if (canConnect) {
                    setConnecting()
                    onConnect()
                }
            }
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    onDismiss()
                    true
                } else {
                    false
                }
            }
            setOnFocusChangeListener { view, hasFocus ->
                view.animate()
                    .scaleX(if (hasFocus) 1.03f else 1f)
                    .scaleY(if (hasFocus) 1.03f else 1f)
                    .setDuration(130)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        }
        pillView = pill

        val artwork = ImageView(context).apply {
            setImageResource(R.drawable.airpods4_photo)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = false
        }
        pill.addView(artwork, LinearLayout.LayoutParams(dp(31), dp(31)))

        val copy = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val copyParams = LinearLayout.LayoutParams(dp(134), dp(34)).apply {
            marginStart = dp(9)
        }

        copy.addView(TextView(context).apply {
            text = deviceName
            setTextColor(TEXT_PRIMARY)
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(18)))

        val statusRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val statusParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(14)).apply {
            topMargin = dp(2)
        }

        val indicator = StatusIndicatorView(context).apply {
            mode = if (withButtons) IndicatorMode.OK else IndicatorMode.NONE
        }
        statusIndicator = indicator
        statusRow.addView(indicator, LinearLayout.LayoutParams(dp(12), dp(12)))

        val subtitle = TextView(context).apply {
            text = if (withButtons) "Press OK to connect" else "Nearby"
            setTextColor(TEXT_SECONDARY)
            textSize = 10f
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        subtitleView = subtitle
        statusRow.addView(subtitle, LinearLayout.LayoutParams(dp(118), dp(14)).apply {
            marginStart = dp(4)
        })
        copy.addView(statusRow, statusParams)
        pill.addView(copy, copyParams)

        val container = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(pill, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                dp(50),
            ))
            isFocusableInTouchMode = true
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    onDismiss()
                    true
                } else {
                    false
                }
            }
        }
        var windowFlags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        if (!withButtons) {
            windowFlags = windowFlags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            windowFlags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(10)
            y = dp(10)
        }

        try {
            wm.addView(container, params)
        } catch (_: SecurityException) {
            clearViewReferences()
            return false
        } catch (_: WindowManager.BadTokenException) {
            clearViewReferences()
            return false
        } catch (_: WindowManager.InvalidDisplayException) {
            clearViewReferences()
            return false
        }
        root = container

        container.alpha = 0f
        container.translationY = dpF(-10f)
        container.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(210)
            .setInterpolator(DecelerateInterpolator())
            .start()
        if (withButtons) pill.requestFocus()

        timeoutRunnable = Runnable { onDismiss() }.also {
            main.postDelayed(it, timeoutSec * 1000L)
        }
        return true
    }

    private fun pillBackground(): StateListDrawable {
        fun capsule(color: Int, stroke: Int) = GradientDrawable().apply {
            cornerRadius = dpF(200f)
            setColor(color)
            setStroke(dp(1), stroke)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), capsule(PILL_FOCUSED, PILL_FOCUSED_STROKE))
            addState(intArrayOf(), capsule(PILL_BG, PILL_STROKE))
        }
    }

    /** Directly updates the fixed status slot, such as an AAP battery line. */
    fun setSubtitle(text: String, color: Int? = null) {
        subtitleView?.apply {
            this.text = text
            setTextColor(color ?: TEXT_SECONDARY)
        }
        statusIndicator?.mode = when (color) {
            GREEN -> IndicatorMode.CONNECTED
            RED -> IndicatorMode.ERROR
            else -> IndicatorMode.NONE
        }
    }

    fun setConnecting() {
        canConnect = false
        subtitleView?.apply {
            text = "Connecting…"
            setTextColor(TEXT_SECONDARY)
        }
        statusIndicator?.mode = IndicatorMode.CONNECTING
        timeoutRunnable?.let { main.removeCallbacks(it) }
        timeoutRunnable = Runnable { onDismiss() }.also { main.postDelayed(it, 30_000L) }
    }

    fun setResult(success: Boolean, autoHideMs: Long = 2500L, message: String? = null) {
        canConnect = !success
        subtitleView?.apply {
            text = if (success) "Connected" else (message ?: "Failed") + " · Press OK to retry"
            setTextColor(if (success) GREEN else RED)
        }
        statusIndicator?.mode = if (success) IndicatorMode.CONNECTED else IndicatorMode.ERROR
        if (!success) pillView?.requestFocus()

        timeoutRunnable?.let { main.removeCallbacks(it) }
        timeoutRunnable = Runnable { onDismiss() }.also {
            main.postDelayed(it, if (success) autoHideMs else 15_000L)
        }
    }

    fun hide() {
        timeoutRunnable?.let { main.removeCallbacks(it) }
        timeoutRunnable = null
        statusIndicator?.stopAnimation()
        val view = root ?: return
        clearViewReferences()
        view.animate().alpha(0f).translationY(dpF(-10f)).setDuration(160).withEndAction {
            try {
                wm.removeView(view)
            } catch (_: Exception) {
            }
        }.start()
    }

    private fun clearViewReferences() {
        root = null
        pillView = null
        subtitleView = null
        statusIndicator = null
    }

    private enum class IndicatorMode { NONE, OK, CONNECTING, CONNECTED, ERROR }

    private inner class StatusIndicatorView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeCap = Paint.Cap.ROUND
        }
        private var angle = 0f
        private val arcBounds = RectF()
        private var animator: ValueAnimator? = null

        var mode: IndicatorMode = IndicatorMode.NONE
            set(value) {
                if (field == value) return
                field = value
                if (value == IndicatorMode.CONNECTING) startAnimation() else stopAnimation()
                invalidate()
            }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            when (mode) {
                IndicatorMode.NONE -> Unit
                IndicatorMode.OK -> {
                    paint.color = TEXT_SECONDARY
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = dpF(1.25f)
                    canvas.drawCircle(cx, cy, dpF(4.7f), paint)
                    paint.style = Paint.Style.FILL
                    canvas.drawCircle(cx, cy, dpF(1.15f), paint)
                }
                IndicatorMode.CONNECTING -> {
                    paint.color = TEXT_SECONDARY
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = dpF(1.4f)
                    val inset = dpF(1.6f)
                    arcBounds.set(inset, inset, width - inset, height - inset)
                    canvas.drawArc(arcBounds, angle, 255f, false, paint)
                }
                IndicatorMode.CONNECTED -> {
                    paint.color = GREEN
                    paint.style = Paint.Style.FILL
                    canvas.drawCircle(cx, cy, dpF(2.8f), paint)
                }
                IndicatorMode.ERROR -> {
                    paint.color = RED
                    paint.style = Paint.Style.FILL
                    canvas.drawCircle(cx, cy, dpF(2.8f), paint)
                }
            }
        }

        private fun startAnimation() {
            if (animator?.isRunning == true) return
            animator = ValueAnimator.ofFloat(0f, 360f).apply {
                duration = 780
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    angle = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        fun stopAnimation() {
            animator?.cancel()
            animator = null
        }

        override fun onDetachedFromWindow() {
            stopAnimation()
            super.onDetachedFromWindow()
        }
    }
}
