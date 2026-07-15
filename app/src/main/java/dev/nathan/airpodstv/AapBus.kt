package dev.nathan.airpodstv

import android.os.Handler
import android.os.Looper

/** In-process state of the AAP session, for the settings UI. */
object AapBus {
    enum class SessionState {
        DISABLED,
        WAITING_FOR_CONNECTION,
        CONNECTING,
        ACTIVE,
        RETRYING,
        UNSUPPORTED,
    }

    interface Listener {
        fun onAapChanged()
    }

    @Volatile
    var sessionState = SessionState.WAITING_FOR_CONNECTION

    val sessionActive: Boolean
        get() = sessionState == SessionState.ACTIVE

    @Volatile
    var sessionDetail: String? = null

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
