package dev.nathan.airpodstv

/** Pure policy decision used by the service and covered without Android Bluetooth mocks. */
internal object ConnectionPolicyPlan {
    enum class Action { BLOCK_WHEN_READY, RESTORE_WHEN_READY, DEFER_UNTIL_DISCONNECTED }

    fun decide(blockAutoConnect: Boolean, deviceConnected: Boolean): Action = when {
        !blockAutoConnect -> Action.RESTORE_WHEN_READY
        deviceConnected -> Action.DEFER_UNTIL_DISCONNECTED
        else -> Action.BLOCK_WHEN_READY
    }
}
