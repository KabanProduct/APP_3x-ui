package dev.threex.mobile.domain

import org.junit.Assert.*
import org.junit.Test

class ServerInspectionTest {
    private val clean = ServerInspection(PanelPresence.ABSENT, true, true, true, emptyList(), emptyList())

    @Test fun cleanServerOffersInstallation() {
        assertEquals(SetupDecision.OfferInstallation, clean.decide())
    }

    @Test fun existingPanelNeverOffersReinstallation() {
        assertEquals(SetupDecision.ConnectExisting, clean.copy(panel = PanelPresence.PRESENT, checksComplete = false).decide())
    }

    @Test fun uncertaintyAndExistingWorkloadsPreventCleanInstall() {
        val unsafe = listOf(
            clean.copy(panel = PanelPresence.UNKNOWN),
            clean.copy(checksComplete = false),
            clean.copy(supportedOs = false),
            clean.copy(privilegedAccess = false),
            clean.copy(conflicts = listOf("Порт занят")),
            clean.copy(existingWorkloads = listOf("nginx")),
        )
        unsafe.forEach { assertTrue(it.decide() is SetupDecision.ReviewRequired) }
    }
}
