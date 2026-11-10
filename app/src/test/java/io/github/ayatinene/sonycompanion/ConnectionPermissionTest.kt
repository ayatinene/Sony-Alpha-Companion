package io.github.ayatinene.sonycompanion

import io.github.ayatinene.sonycompanion.ui.ConnectionPermissionOutcome
import io.github.ayatinene.sonycompanion.ui.ConnectionPermissionPolicy
import io.github.ayatinene.sonycompanion.ui.connectionPermissionOutcome
import io.github.ayatinene.sonycompanion.ui.planPermissionRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure permission matrix for the scenario-based connection guard. */
class ConnectionPermissionTest {
    private val notifications = ConnectionPermissionPolicy.NOTIFICATIONS

    @Test fun connectionNeedsNoRequiredRuntimePermissionOnAnyApi() {
        // Evidence: the only WifiManager use is createMulticastLock (CHANGE_WIFI_MULTICAST_STATE); the
        // rest is ConnectivityManager + Network.getAllByName/socketFactory + SSDP (ACCESS_NETWORK_STATE
        // / INTERNET). No WifiManager scan/connect APIs, so NEARBY_WIFI_DEVICES is not required.
        assertTrue(ConnectionPermissionPolicy.required(29).isEmpty())
        assertTrue(ConnectionPermissionPolicy.required(32).isEmpty())
        assertTrue(ConnectionPermissionPolicy.required(33).isEmpty())
    }

    @Test fun notificationsAreOptionalOnlyOnApi33AndAbove() {
        assertEquals(listOf(notifications), ConnectionPermissionPolicy.optional(33))
        assertTrue(ConnectionPermissionPolicy.optional(32).isEmpty())
        assertTrue(ConnectionPermissionPolicy.optional(29).isEmpty())
    }

    private fun plan(
        required: List<String> = emptyList(),
        optional: List<String> = emptyList(),
        granted: Set<String> = emptySet(),
        asked: Set<String> = emptySet(),
        rationale: Set<String> = emptySet(),
    ) = planPermissionRequest(required, optional, { it in granted }, asked, { it in rationale })

    @Test fun asksOnlyTheMissingUnaskedPermissions() {
        assertEquals(listOf("R", "O"), plan(required = listOf("R"), optional = listOf("O")).request)
        assertEquals(listOf("O"), plan(optional = listOf("O"), granted = setOf("R"), required = listOf("R")).request)
        assertTrue(plan(required = listOf("R"), optional = listOf("O"), granted = setOf("R", "O")).request.isEmpty())
    }

    @Test fun requiredCanBeReAskedOnlyWhileARationaleCanStillBeShown() {
        assertEquals(listOf("R"), plan(required = listOf("R"), asked = setOf("R"), rationale = setOf("R")).request)
        val blocked = plan(required = listOf("R"), asked = setOf("R"))
        assertTrue(blocked.request.isEmpty())
        assertTrue(blocked.blockedPermanently)
    }

    @Test fun optionalIsNeverAskedTwice() {
        val plan = plan(optional = listOf("O"), asked = setOf("O"), rationale = setOf("O"))
        assertTrue(plan.request.isEmpty())
        assertFalse(plan.blockedPermanently)
    }

    @Test fun outcomeConnectsWhenNothingRequiredIsMissing() {
        assertEquals(ConnectionPermissionOutcome.CONNECT, connectionPermissionOutcome(emptyList(), emptyMap(), canAskAgain = false))
        assertEquals(ConnectionPermissionOutcome.CONNECT,
            connectionPermissionOutcome(listOf("R"), mapOf("R" to true, "O" to false), canAskAgain = false))
    }

    @Test fun deniedRequiredBlocksAndPermanentDenialPointsAtSettings() {
        assertEquals(ConnectionPermissionOutcome.DENIED, connectionPermissionOutcome(listOf("R"), mapOf("R" to false), canAskAgain = true))
        assertEquals(ConnectionPermissionOutcome.PERMANENTLY_DENIED,
            connectionPermissionOutcome(listOf("R"), mapOf("R" to false), canAskAgain = false))
    }
}
