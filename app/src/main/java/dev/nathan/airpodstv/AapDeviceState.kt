package dev.nathan.airpodstv

/**
 * Reconciles device data for one connected-device generation without Android side effects.
 *
 * Battery callbacks are deltas. Ear callbacks are complete observations from one source,
 * where a null side means that source did not provide a trustworthy placement for that pod.
 */
internal class AapDeviceState {

    data class SessionKey(val address: String, val generation: Long)

    enum class EarSource { BLE, AAP }

    data class Snapshot(
        val sessionKey: SessionKey?,
        val batteries: Map<AapClient.Component, AapClient.Battery>,
        val primaryPlacement: AapClient.Placement?,
        val secondaryPlacement: AapClient.Placement?,
        val earSource: EarSource?,
        val ancMode: Int?,
    ) {
        val batteryLine: String?
            get() = formatBatteryLine(batteries)

        val knownPlacementCount: Int
            get() = listOfNotNull(primaryPlacement, secondaryPlacement).size

        val inEarCount: Int?
            get() = if (knownPlacementCount == 0) null else {
                listOf(primaryPlacement, secondaryPlacement)
                    .count { it == AapClient.Placement.IN_EAR }
            }

        val bothPodsInCase: Boolean
            get() = knownPlacementCount == 2 &&
                primaryPlacement == AapClient.Placement.IN_CASE &&
                secondaryPlacement == AapClient.Placement.IN_CASE
    }

    data class BatteryReduction(
        val changed: Boolean,
        val batteryLine: String?,
        val announcement: String?,
    )

    data class EarReduction(
        val changed: Boolean,
        val sourceChanged: Boolean,
        val knownSetChanged: Boolean,
        val inEarCount: Int?,
        val knownPlacementCount: Int,
        val bothPodsInCase: Boolean,
    )

    private var sessionKey: SessionKey? = null
    private val batteries = linkedMapOf<AapClient.Component, AapClient.Battery>()
    private var batteryBaselineEstablished = false
    private var primaryPlacement: AapClient.Placement? = null
    private var secondaryPlacement: AapClient.Placement? = null
    private var earSource: EarSource? = null
    private var ancMode: Int? = null

    fun beginSession(address: String, generation: Long): Boolean {
        val next = SessionKey(address, generation)
        if (sessionKey == next) return false
        sessionKey = next
        clearMeasurements()
        return true
    }

    fun resetSession(): Boolean {
        val changed = sessionKey != null || batteries.isNotEmpty() ||
            batteryBaselineEstablished || primaryPlacement != null ||
            secondaryPlacement != null || earSource != null || ancMode != null
        sessionKey = null
        clearMeasurements()
        return changed
    }

    /**
     * Applies only the components present in [delta]. A null value explicitly removes that
     * component. Missing map keys leave the current component untouched.
     */
    fun applyBattery(
        delta: Map<AapClient.Component, AapClient.Battery?>,
    ): BatteryReduction {
        val before = batteries.toMap()
        val hadBaseline = batteryBaselineEstablished
        delta.forEach { (component, battery) ->
            if (battery == null) batteries.remove(component) else batteries[component] = battery
        }
        if (delta.isNotEmpty()) batteryBaselineEstablished = true

        val changed = before != batteries
        val line = formatBatteryLine(batteries)
        val announcement = when {
            !changed || !hadBaseline -> null
            line != null -> line
            else -> BATTERY_UNAVAILABLE
        }
        return BatteryReduction(changed, line, announcement)
    }

    /**
     * Replaces the placement observation for [source]. A source handoff first clears the
     * previous source, so a missing pod is never borrowed from stale state.
     */
    fun applyEarPlacement(
        source: EarSource,
        primary: AapClient.Placement?,
        secondary: AapClient.Placement?,
    ): EarReduction {
        val previousSource = earSource
        val previousPrimary = primaryPlacement
        val previousSecondary = secondaryPlacement
        val previousKnownMask = knownMask(previousPrimary, previousSecondary)
        val sourceChanged = previousSource != source

        if (sourceChanged) {
            primaryPlacement = null
            secondaryPlacement = null
        }
        earSource = source
        primaryPlacement = primary
        secondaryPlacement = secondary

        val next = snapshot()
        val changed = sourceChanged || previousPrimary != primaryPlacement ||
            previousSecondary != secondaryPlacement
        val knownSetChanged = sourceChanged ||
            previousKnownMask != knownMask(primaryPlacement, secondaryPlacement)
        return EarReduction(
            changed = changed,
            sourceChanged = sourceChanged,
            knownSetChanged = knownSetChanged,
            inEarCount = next.inEarCount,
            knownPlacementCount = next.knownPlacementCount,
            bothPodsInCase = next.bothPodsInCase,
        )
    }

    fun applyAncMode(wireMode: Int?): Boolean {
        if (ancMode == wireMode) return false
        ancMode = wireMode
        return true
    }

    fun snapshot(): Snapshot = Snapshot(
        sessionKey = sessionKey,
        batteries = batteries.toMap(),
        primaryPlacement = primaryPlacement,
        secondaryPlacement = secondaryPlacement,
        earSource = earSource,
        ancMode = ancMode,
    )

    private fun clearMeasurements() {
        batteries.clear()
        batteryBaselineEstablished = false
        primaryPlacement = null
        secondaryPlacement = null
        earSource = null
        ancMode = null
    }

    private fun knownMask(
        primary: AapClient.Placement?,
        secondary: AapClient.Placement?,
    ): Int = (if (primary != null) 1 else 0) or (if (secondary != null) 2 else 0)

    private companion object {
        const val BATTERY_UNAVAILABLE = "Battery unavailable"

        fun formatBatteryLine(
            batteries: Map<AapClient.Component, AapClient.Battery>,
        ): String? {
            val parts = listOf(
                AapClient.Component.LEFT to "L",
                AapClient.Component.RIGHT to "R",
                AapClient.Component.CASE to "Case",
            ).mapNotNull { (component, label) ->
                batteries[component]?.let { battery ->
                    "$label ${battery.percent}%" + if (battery.charging) "\u26A1" else ""
                }
            }
            return parts.joinToString(" \u00B7 ").takeIf { it.isNotEmpty() }
        }
    }
}
