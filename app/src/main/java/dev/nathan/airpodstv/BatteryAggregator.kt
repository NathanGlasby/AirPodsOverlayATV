package dev.nathan.airpodstv

/**
 * Stabilizes battery readings across beacon frames, mirroring CAPod's history approach.
 *
 * AirPods broadcast from both pods (and case) on rotating addresses; individual frames
 * disagree, omit the case battery entirely, or carry anomalous nibbles. Displaying raw
 * frames makes values jump around. This keeps a short rolling window per component and
 * shows the median, and latches the last known value when a frame omits a component.
 */
class BatteryAggregator {

    private companion object {
        const val WINDOW_MS = 20_000L
        const val WINDOW_SIZE = 12
        const val HOLD_MS = 90_000L
        const val CHARGE_VOTES = 3
    }

    private class Reading(val value: Int, val at: Long)

    private class Track {
        private val readings = ArrayDeque<Reading>()
        private var latched: Int? = null
        private var latchedAt = 0L

        fun add(value: Int?, now: Long) {
            if (value == null) return
            readings.addLast(Reading(value, now))
            while (readings.size > WINDOW_SIZE) readings.removeFirst()
            while (readings.isNotEmpty() && now - readings.first().at > WINDOW_MS) readings.removeFirst()
            latched = median()
            latchedAt = now
        }

        private fun median(): Int? {
            if (readings.isEmpty()) return null
            val sorted = readings.map { it.value }.sorted()
            return sorted[sorted.size / 2]
        }

        fun value(now: Long): Int? = latched?.takeIf { now - latchedAt < HOLD_MS }

        fun reset() {
            readings.clear()
            latched = null
        }
    }

    private class ChargeTrack {
        private val votes = ArrayDeque<Boolean>()

        fun add(charging: Boolean) {
            votes.addLast(charging)
            while (votes.size > CHARGE_VOTES) votes.removeFirst()
        }

        fun value(): Boolean = votes.count { it } * 2 > votes.size

        fun reset() = votes.clear()
    }

    private val left = Track()
    private val right = Track()
    private val case = Track()
    private val leftCharge = ChargeTrack()
    private val rightCharge = ChargeTrack()
    private val caseCharge = ChargeTrack()

    fun update(b: BeaconParser.Beacon) {
        val now = b.timestamp
        left.add(b.leftBattery, now)
        right.add(b.rightBattery, now)
        case.add(b.caseBattery, now)
        if (b.leftBattery != null) leftCharge.add(b.leftCharging)
        if (b.rightBattery != null) rightCharge.add(b.rightCharging)
        if (b.caseBattery != null) caseCharge.add(b.caseCharging)
    }

    /** The beacon as it should be displayed: smoothed values, gaps filled from latches. */
    fun display(base: BeaconParser.Beacon): BeaconParser.Beacon {
        val now = System.currentTimeMillis()
        return base.copy(
            leftBattery = left.value(now),
            rightBattery = right.value(now),
            caseBattery = case.value(now),
            leftCharging = leftCharge.value(),
            rightCharging = rightCharge.value(),
            caseCharging = caseCharge.value(),
        )
    }

    fun reset() {
        left.reset(); right.reset(); case.reset()
        leftCharge.reset(); rightCharge.reset(); caseCharge.reset()
    }
}
