package dev.nathan.airpodstv

import android.os.Handler
import android.os.Looper

/** In-process feed of parsed beacons so the settings UI can show a live debug view. */
object BeaconBus {
    interface Listener {
        fun onBeacon(beacon: BeaconParser.Beacon, passedFilter: Boolean)

        /** Any other Apple BLE frame (non-0x07), for diagnosing what the case broadcasts. */
        fun onRawFrame(type: Int, hex: String, rssi: Int) {}
    }

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var listener: Listener? = null

    fun publish(beacon: BeaconParser.Beacon, passedFilter: Boolean) {
        val l = listener ?: return
        main.post { l.onBeacon(beacon, passedFilter) }
    }

    fun publishRaw(type: Int, hex: String, rssi: Int) {
        val l = listener ?: return
        main.post { l.onRawFrame(type, hex, rssi) }
    }
}
