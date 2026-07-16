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

    private data class DeviceState(
        val batteryLine: String? = null,
        val ancMode: Int? = null,
        val primaryPlacement: AapClient.Placement? = null,
        val secondaryPlacement: AapClient.Placement? = null,
    )

    @Volatile
    private var deviceState = DeviceState()

    val batteryLine: String?
        get() = deviceState.batteryLine

    val ancMode: Int?
        get() = deviceState.ancMode

    internal val primaryPlacement: AapClient.Placement?
        get() = deviceState.primaryPlacement

    internal val secondaryPlacement: AapClient.Placement?
        get() = deviceState.secondaryPlacement

    @Volatile
    var listener: Listener? = null

    private val main = Handler(Looper.getMainLooper())

    internal fun reconcileDeviceState(snapshot: AapDeviceState.Snapshot): Boolean {
        val next = DeviceState(
            batteryLine = snapshot.batteryLine,
            ancMode = snapshot.ancMode,
            primaryPlacement = snapshot.primaryPlacement,
            secondaryPlacement = snapshot.secondaryPlacement,
        )
        if (deviceState == next) return false
        deviceState = next
        return true
    }

    fun notifyChanged() {
        val l = listener ?: return
        main.post { l.onAapChanged() }
    }
}
