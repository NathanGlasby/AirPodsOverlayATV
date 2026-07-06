package dev.nathan.airpodstv

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * tvOS-style "mono glass" pill drawn over the foreground app (top-right).
 * One horizontal capsule: badge, name + battery line, Connect pill, ✕ circle.
 * D-pad left/right moves between the buttons; the focused control turns solid
 * white with dark text (the Apple TV focus idiom). BACK dismisses.
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
    private var subtitleView: TextView? = null
    private var connectBtn: TextView? = null
    private var dismissBtn: TextView? = null
    private var statusOverridesBattery = false
    private var timeoutRunnable: Runnable? = null

    val isShowing: Boolean get() = root != null

    private fun dp(v: Int): Int = (v * density).toInt()
    private fun dpF(v: Float): Float = v * density

    private companion object {
        val PILL_BG = 0xF516161A.toInt()
        val PILL_STROKE = 0x1AFFFFFF
        val CONTROL_IDLE = 0x14FFFFFF
        val TEXT_PRIMARY = 0xFFF4F4F7.toInt()
        val TEXT_SECONDARY = 0xFF98989F.toInt()
        val TEXT_ON_WHITE = 0xFF111114.toInt()
        val GREEN = 0xFF3ACB74.toInt()
        val RED = 0xFFE5544B.toInt()
    }

    /**
     * @param withButtons false = transient info pill: no Connect/✕, window doesn't
     * take remote focus, just slides in and auto-hides.
     */
    fun show(deviceName: String, timeoutSec: Int, withButtons: Boolean = true) {
        if (isShowing) return
        statusOverridesBattery = false

        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dpF(200f)
                setColor(PILL_BG)
                setStroke(dp(1), PILL_STROKE)
            }
            setPadding(dp(9), dp(9), dp(11), dp(9))
            elevation = dpF(12f)
        }

        // Badge: AirPods glyph in a soft translucent circle
        val badge = ImageView(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(CONTROL_IDLE)
            }
            setImageResource(R.drawable.airpods4)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(9), dp(9), dp(9), dp(9))
        }
        pill.addView(badge, LinearLayout.LayoutParams(dp(44), dp(44)))

        // Name + battery/status line
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(14), 0)
        }
        texts.addView(TextView(context).apply {
            text = deviceName
            setTextColor(TEXT_PRIMARY)
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        subtitleView = TextView(context).apply {
            text = "Ready to connect"
            setTextColor(TEXT_SECONDARY)
            textSize = 12f
        }
        texts.addView(subtitleView)
        pill.addView(texts)

        // Connect capsule + ✕ circle
        connectBtn = makeControl("Connect", circle = false).apply {
            setOnClickListener { setConnecting(); onConnect() }
        }
        dismissBtn = makeControl("✕", circle = true).apply {
            setOnClickListener { onDismiss() }
        }
        pill.addView(connectBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)))
        pill.addView(View(context), LinearLayout.LayoutParams(dp(8), 1))
        pill.addView(dismissBtn, LinearLayout.LayoutParams(dp(40), dp(40)))
        if (!withButtons) {
            connectBtn?.visibility = View.GONE
            dismissBtn?.visibility = View.GONE
        }

        val container = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(pill, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ))
            isFocusableInTouchMode = true
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    onDismiss()
                    true
                } else false
            }
        }
        root = container

        var windowFlags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        if (!withButtons) {
            // Transient pill must not steal the remote from the app underneath.
            windowFlags = windowFlags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            windowFlags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(10)
            y = dp(10)
        }

        wm.addView(container, params)

        container.alpha = 0f
        container.translationY = dpF(-28f)
        container.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(300)
            .setInterpolator(OvershootInterpolator(1.05f))
            .start()
        if (withButtons) connectBtn?.requestFocus()

        timeoutRunnable = Runnable { onDismiss() }.also {
            main.postDelayed(it, timeoutSec * 1000L)
        }
    }

    /** tvOS focus idiom: idle = translucent gray, focused = solid white + dark text. */
    private fun makeControl(label: String, circle: Boolean): TextView {
        fun shape(color: Int) = GradientDrawable().apply {
            if (circle) shape = GradientDrawable.OVAL else cornerRadius = dpF(20f)
            setColor(color)
        }
        return TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = if (circle) 15f else 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            if (!circle) setPadding(dp(20), 0, dp(20), 0)
            isFocusable = true
            isFocusableInTouchMode = true
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), shape(Color.WHITE))
                addState(intArrayOf(), shape(CONTROL_IDLE))
            }
            setTextColor(ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                intArrayOf(TEXT_ON_WHITE, 0xFFE0E0E6.toInt())
            ))
            setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.07f else 1f)
                    .scaleY(if (hasFocus) 1.07f else 1f)
                    .setDuration(110).start()
            }
        }
    }

    /** Directly set the status line (e.g. exact battery once AAP reports it). */
    fun setSubtitle(text: String, color: Int? = null) {
        subtitleView?.apply {
            this.text = text
            setTextColor(color ?: TEXT_SECONDARY)
        }
    }

    fun setConnecting() {
        statusOverridesBattery = true
        subtitleView?.apply {
            text = "Connecting…"
            setTextColor(TEXT_SECONDARY)
        }
        connectBtn?.visibility = View.GONE
        dismissBtn?.visibility = View.GONE
        // Connection can take a while; extend the auto-dismiss window.
        timeoutRunnable?.let { main.removeCallbacks(it) }
        timeoutRunnable = Runnable { onDismiss() }.also { main.postDelayed(it, 30_000L) }
    }

    fun setResult(success: Boolean, autoHideMs: Long = 2500L) {
        statusOverridesBattery = true
        subtitleView?.apply {
            text = if (success) "Connected ✓" else "Connection failed — try again"
            setTextColor(if (success) GREEN else RED)
        }
        if (!success) {
            connectBtn?.visibility = View.VISIBLE
            dismissBtn?.visibility = View.VISIBLE
            connectBtn?.requestFocus()
        }
        timeoutRunnable?.let { main.removeCallbacks(it) }
        timeoutRunnable = Runnable { onDismiss() }.also {
            main.postDelayed(it, if (success) autoHideMs else 15_000L)
        }
    }

    fun hide() {
        timeoutRunnable?.let { main.removeCallbacks(it) }
        timeoutRunnable = null
        val v = root ?: return
        root = null
        v.animate().alpha(0f).translationY(dpF(-28f)).setDuration(170).withEndAction {
            try {
                wm.removeView(v)
            } catch (_: Exception) {
            }
        }.start()
    }
}
