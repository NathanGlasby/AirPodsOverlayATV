package dev.nathan.airpodstv

import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("airpods_overlay", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    var autoConnect: Boolean
        get() = sp.getBoolean("auto_connect", false)
        set(v) = sp.edit().putBoolean("auto_connect", v).apply()

    /** Forbid the OS from auto-connecting the AirPods; connect only via the popup. */
    var blockAutoConnect: Boolean
        get() = sp.getBoolean("block_auto_connect", true)
        set(v) = sp.edit().putBoolean("block_auto_connect", v).apply()

    var deviceAddress: String?
        get() = sp.getString("device_address", null)
        set(v) = sp.edit().putString("device_address", v).apply()

    var deviceName: String?
        get() = sp.getString("device_name", null)
        set(v) = sp.edit().putString("device_name", v).apply()

    /** User-chosen display name for the popup; null/blank = use the device name. */
    var customName: String?
        get() = sp.getString("custom_name", null)?.takeIf { it.isNotBlank() }
        set(v) = sp.edit().putString("custom_name", v?.trim()?.takeIf { it.isNotBlank() }).apply()

    /** The name the popup should show. */
    val displayName: String?
        get() = customName ?: deviceName

    /** Minimum RSSI (dBm) for a beacon to count as "our" AirPods near the TV. */
    var rssiThreshold: Int
        get() = sp.getInt("rssi_threshold", -60)
        set(v) = sp.edit().putInt("rssi_threshold", v).apply()

    /** Seconds before an untouched popup auto-dismisses. */
    var popupTimeoutSec: Int
        get() = sp.getInt("popup_timeout", 20)
        set(v) = sp.edit().putInt("popup_timeout", v).apply()

    // ---- reactions ----

    /** Only react to beacons matching the AirPods 4 ANC model id. */
    var modelFilter: Boolean
        get() = sp.getBoolean("model_filter", true)
        set(v) = sp.edit().putBoolean("model_filter", v).apply()

    /** Pause TV playback when a pod is taken out of the ear; resume when back in. */
    var autoPause: Boolean
        get() = sp.getBoolean("auto_pause", true)
        set(v) = sp.edit().putBoolean("auto_pause", v).apply()

    /** Disconnect the AirPods when the case lid closes with the pods inside. */
    var autoDisconnectOnLidClose: Boolean
        get() = sp.getBoolean("auto_disconnect_lid", true)
        set(v) = sp.edit().putBoolean("auto_disconnect_lid", v).apply()

    // ---- AAP / identity ----

    /** Open an AAP session after connecting (exact battery, ANC control, ear events, IRK). */
    var aapEnabled: Boolean
        get() = sp.getBoolean("aap_enabled", true)
        set(v) = sp.edit().putBoolean("aap_enabled", v).apply()

    /** Identity Resolving Key captured from the AirPods via AAP key exchange (hex). */
    var irkHex: String?
        get() = sp.getString("irk_hex", null)?.takeIf { it.length == 32 }
        set(v) = sp.edit().putString("irk_hex", v).apply()

    /** When the IRK is known, only react to beacons cryptographically proven to be ours. */
    var identityFilter: Boolean
        get() = sp.getBoolean("identity_filter", true)
        set(v) = sp.edit().putBoolean("identity_filter", v).apply()
}
