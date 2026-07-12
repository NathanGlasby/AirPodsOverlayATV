package dev.nathan.airpodstv

/** Pure decision logic for accepting or rejecting a parsed AirPods beacon. */
object BeaconGate {

    enum class Reason(val label: String) {
        ACCEPTED("accepted"),
        IDENTITY_MATCH("identity match"),
        IDENTITY_FALLBACK("identity unverified; using distance"),
        WRONG_MODEL("wrong model"),
        IDENTITY_MISMATCH("identity mismatch"),
        WEAK_SIGNAL("too far"),
    }

    data class Decision(
        val passes: Boolean,
        val reason: Reason,
        val identityMatched: Boolean = false,
    )

    /**
     * Identity matching is fail-open until a captured key has matched a live RPA at least once.
     * This prevents a stale or incorrectly captured key from permanently disabling detection.
     */
    fun evaluate(
        beacon: BeaconParser.Beacon,
        modelFilter: Boolean,
        requiredModel: Int,
        identityFilter: Boolean,
        irk: ByteArray?,
        identityKeyVerified: Boolean,
        rssiThreshold: Int,
    ): Decision {
        if (modelFilter && beacon.model != requiredModel) {
            return Decision(false, Reason.WRONG_MODEL)
        }

        // Validate a stored key even while the strict filter is off, so enabling it later is safe.
        val identityMatches = irk?.let { RpaVerifier.verify(beacon.address, it) } == true

        if (identityFilter && irk != null) {
            if (identityMatches) {
                return Decision(true, Reason.IDENTITY_MATCH, identityMatched = true)
            }
            if (identityKeyVerified) {
                return Decision(false, Reason.IDENTITY_MISMATCH)
            }
        }

        val passesDistance = beacon.rssi >= rssiThreshold
        val fallback = identityFilter && irk != null && !identityKeyVerified
        return when {
            passesDistance && fallback -> Decision(
                true,
                Reason.IDENTITY_FALLBACK,
                identityMatched = identityMatches,
            )
            passesDistance -> Decision(
                true,
                Reason.ACCEPTED,
                identityMatched = identityMatches,
            )
            else -> Decision(
                false,
                Reason.WEAK_SIGNAL,
                identityMatched = identityMatches,
            )
        }
    }
}
