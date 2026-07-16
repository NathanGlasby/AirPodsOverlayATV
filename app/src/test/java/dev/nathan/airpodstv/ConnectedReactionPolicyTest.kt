package dev.nathan.airpodstv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectedReactionPolicyTest {
    @Test
    fun proximityMatchWithoutVerifiedIdentityIsDenied() {
        assertFalse(
            ConnectedReactionPolicy.allowsBleReaction(
                selectedDeviceConnected = true,
                identityKeyVerified = false,
                identityMatched = false,
            )
        )
    }

    @Test
    fun unverifiedKeyCannotAuthorizeAReaction() {
        assertFalse(
            ConnectedReactionPolicy.allowsBleReaction(
                selectedDeviceConnected = true,
                identityKeyVerified = false,
                identityMatched = true,
            )
        )
    }

    @Test
    fun verifiedNonMatchingBeaconIsDenied() {
        assertFalse(
            ConnectedReactionPolicy.allowsBleReaction(
                selectedDeviceConnected = true,
                identityKeyVerified = true,
                identityMatched = false,
            )
        )
    }

    @Test
    fun matchingBeaconIsDeniedWhileDeviceIsDisconnected() {
        assertFalse(
            ConnectedReactionPolicy.allowsBleReaction(
                selectedDeviceConnected = false,
                identityKeyVerified = true,
                identityMatched = true,
            )
        )
    }

    @Test
    fun verifiedMatchingBeaconCanDriveConnectedReactions() {
        assertTrue(
            ConnectedReactionPolicy.allowsBleReaction(
                selectedDeviceConnected = true,
                identityKeyVerified = true,
                identityMatched = true,
            )
        )
    }
}
