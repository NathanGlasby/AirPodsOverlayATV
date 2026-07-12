package dev.nathan.airpodstv

/**
 * Tracks one physical AirPods wake/open session independently of Android UI objects.
 * A known lid-close or a long beacon silence re-arms the next popup.
 */
class PopupSession(private val resetAfterMs: Long) {

    enum class Event { OFFER_POPUP, LID_CLOSED, NONE }

    var lastBeaconAt: Long = 0L
        private set

    var suppressed: Boolean = false
        private set

    private var lastObservedLidState: BeaconParser.LidState = BeaconParser.LidState.UNKNOWN

    fun onBeacon(lidState: BeaconParser.LidState, now: Long): Event {
        val freshSession = lastBeaconAt == 0L || now - lastBeaconAt > resetAfterMs
        lastBeaconAt = now
        // State from a prior session must not suppress transitions in a new one.
        val previousLidState = if (freshSession) BeaconParser.LidState.UNKNOWN else lastObservedLidState
        lastObservedLidState = lidState

        return when (lidState) {
            BeaconParser.LidState.CLOSED -> {
                // Closed is an explicit boundary, even if closed beacons continue indefinitely.
                suppressed = false
                if (previousLidState == BeaconParser.LidState.CLOSED) Event.NONE else Event.LID_CLOSED
            }
            BeaconParser.LidState.OPEN -> {
                if (freshSession) suppressed = false
                if (suppressed) Event.NONE else Event.OFFER_POPUP
            }
            BeaconParser.LidState.UNKNOWN -> {
                // Some models/firmware do not expose a reliable lid bit. Preserve the v1.7
                // behavior on a fresh beacon session. CLOSED -> UNKNOWN is also a useful wake
                // transition for one-pod-in-case frames whose lid byte is intentionally ignored.
                if (freshSession || previousLidState == BeaconParser.LidState.CLOSED) {
                    suppressed = false
                    Event.OFFER_POPUP
                } else {
                    Event.NONE
                }
            }
        }
    }

    fun suppress() {
        suppressed = true
    }

    fun rearmIfSilent(now: Long): Boolean {
        if (!suppressed || lastBeaconAt == 0L || now - lastBeaconAt <= resetAfterMs) return false
        suppressed = false
        return true
    }

    fun clearAfterLongSilence(now: Long, silenceMs: Long) {
        if (lastBeaconAt != 0L && now - lastBeaconAt > silenceMs) {
            lastBeaconAt = 0L
            lastObservedLidState = BeaconParser.LidState.UNKNOWN
        }
    }

    fun reset() {
        lastBeaconAt = 0L
        suppressed = false
        lastObservedLidState = BeaconParser.LidState.UNKNOWN
    }
}
