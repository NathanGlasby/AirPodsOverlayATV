package dev.nathan.airpodstv

import android.app.Activity
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView

class LicensesActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val text = resources.openRawResource(R.raw.licenses)
            .readBytes().toString(Charsets.UTF_8)

        val pad = (48 * resources.displayMetrics.density).toInt()
        val content = TextView(this).apply {
            setText(text)
            setTextColor(0xFFD0D0D6.toInt())
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        // The ScrollView itself takes focus so the D-pad scrolls the text.
        val scroll = ScrollView(this).apply {
            setBackgroundColor(0xFF0E0E12.toInt())
            isFocusable = true
            addView(content)
        }
        setContentView(scroll)
        scroll.requestFocus()
    }
}
