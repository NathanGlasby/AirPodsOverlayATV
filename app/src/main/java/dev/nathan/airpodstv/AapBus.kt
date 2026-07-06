package dev.nathan.airpodstv

import android.os.Handler
import android.os.Looper

/** In-process state of the AAP session, for the settings UI. */
object AapBus {
    interface Listener {
        fun onAapChanged()
    }

    @Volatile
    var sessionActive = false

    @Volatile
    var batteryLine: String? = null

    @Volatile
    var ancMode: Int? = null

    @Volatile
    var listener: Listener? = null

    private val main = Handler(Looper.getMainLooper())

    fun notifyChanged() {
        val l = listener ?: return
        main.post { l.onAapChanged() }
    }
}
