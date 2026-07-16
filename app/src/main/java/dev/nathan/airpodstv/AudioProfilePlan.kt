package dev.nathan.airpodstv

/** A2DP carries TV audio. HEADSET is optional and used only when the TV exposes it. */
internal object AudioProfilePlan {
    enum class Profile { A2DP, HEADSET }

    private val orderedProfiles = listOf(Profile.A2DP, Profile.HEADSET)

    fun isReady(available: Set<Profile>): Boolean = Profile.A2DP in available

    fun operationTargets(available: Set<Profile>): List<Profile> =
        if (isReady(available)) orderedProfiles.filter { it in available } else emptyList()

    fun canApplyPolicy(
        available: Set<Profile>,
        optionalProfilePending: Boolean,
        waitExpired: Boolean,
    ): Boolean = isReady(available) && (!optionalProfilePending || waitExpired)

    fun isAudioConnected(connected: Set<Profile>): Boolean = Profile.A2DP in connected

    fun isDisconnectComplete(
        targets: Set<Profile>,
        connected: Set<Profile>,
        failedQueries: Set<Profile>,
    ): Boolean = targets.isNotEmpty() &&
        targets.none { it in connected } &&
        targets.none { it in failedQueries }
}
