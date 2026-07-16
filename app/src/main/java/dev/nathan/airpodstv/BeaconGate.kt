package dev.nathan.airpodstv

/** Pure decision logic for accepting or rejecting a parsed AirPods beacon. */
object BeaconGate {

    enum class Reason(val label: String) {
        ACCEPTED("accepted"),
        IDENTITY_MATCH("identity match"),
        IDENTITY_NOT_READY("identity key not verified"),
        WRONG_MODEL("wrong model"),
        IDENTITY_MISMATCH("identity mismatch"),
        WEAK_SIGNAL("too far"),
    }

    data class Decision(
        val passes: Boolean,
        val reason: Reason,
        val identityMatched: Boolean = false,
    )

    /** Strict identity mode never falls back to distance: that would accept somebody else's pair. */
    fun evaluate(
        beacon: BeaconParser.Beacon,
        modelFilter: Boolean,
        requiredModel: Int,
        identityFilter: Boolean,
        irk: ByteArray?,
        identityKeyVerified: Boolean,
        rssiThreshold: Int,
    ): Decision {
        // Identity belongs to the selected device, so popup-only filters must not hide it
        // from connected-device reactions.
        val identityMatches = irk?.let { RpaVerifier.verify(beacon.address, it) } == true

        if (modelFilter && beacon.model != requiredModel) {
            return Decision(false, Reason.WRONG_MODEL, identityMatched = identityMatches)
        }

        // Validate a stored key even while the strict filter is off, so enabling it later is safe.
        if (identityFilter) {
            if (irk == null || !identityKeyVerified) {
                return Decision(
                    passes = false,
                    reason = Reason.IDENTITY_NOT_READY,
                    identityMatched = identityMatches,
                )
            }
            return if (identityMatches) {
                Decision(true, Reason.IDENTITY_MATCH, identityMatched = true)
            } else {
                Decision(false, Reason.IDENTITY_MISMATCH)
            }
        }

        val passesDistance = beacon.rssi >= rssiThreshold
        return when {
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
