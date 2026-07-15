package dev.nathan.airpodstv

/** Ordered Classic L2CAP socket variants used to open the AirPods AAP channel. */
internal object AapTransportPlan {

    enum class Security(
        val methodName: String,
        val label: String,
    ) {
        INSECURE("createInsecureL2capSocket", "insecure L2CAP"),
        SECURE("createL2capSocket", "secure L2CAP"),
    }

    /**
     * The target Xiaomi Android 11 stack has been observed stalling the insecure path.
     * Bonded secure L2CAP reuses the existing AirPods link key and is tried first there.
     */
    fun forSdk(sdkInt: Int): List<Security> = if (sdkInt <= 30) {
        listOf(Security.SECURE, Security.INSECURE)
    } else {
        listOf(Security.INSECURE, Security.SECURE)
    }

    /** Keep the one-time scan pause bounded so BLE-only reactions recover promptly. */
    fun timeoutMs(sdkInt: Int, security: Security): Long = 8_000L

    /** True when reflection cannot expose the Classic L2CAP socket on this Android build. */
    fun isHiddenApiAccessFailure(error: Throwable): Boolean {
        var current: Throwable? = error
        val visited = mutableSetOf<Throwable>()
        var root = true
        while (current != null && visited.add(current)) {
            if (current is NoSuchMethodException ||
                current is LinkageError ||
                (root && current is SecurityException)
            ) {
                return true
            }
            root = false
            current = current.cause
        }
        return false
    }

    fun failureDetail(failures: List<String>): String = when {
        failures.isEmpty() -> "Classic L2CAP connection failed"
        failures.size == 1 -> failures.first()
        else -> failures.joinToString(separator = "; ")
    }
}
