package dev.nathan.airpodstv

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionPolicyPlanTest {
    @Test
    fun blockingIsDeferredDuringAnApprovedConnection() {
        assertEquals(
            ConnectionPolicyPlan.Action.DEFER_UNTIL_DISCONNECTED,
            ConnectionPolicyPlan.decide(blockAutoConnect = true, deviceConnected = true),
        )
    }

    @Test
    fun disconnectedDeviceCanBeBlocked() {
        assertEquals(
            ConnectionPolicyPlan.Action.BLOCK_WHEN_READY,
            ConnectionPolicyPlan.decide(blockAutoConnect = true, deviceConnected = false),
        )
    }

    @Test
    fun disabledSettingAlwaysRestoresPolicy() {
        assertEquals(
            ConnectionPolicyPlan.Action.RESTORE_WHEN_READY,
            ConnectionPolicyPlan.decide(blockAutoConnect = false, deviceConnected = true),
        )
    }
}
