package dev.threex.mobile.ssh

import dev.threex.mobile.domain.*
import org.junit.Assert.*
import org.junit.Test

class InspectionReportTest {
    private val clean = """
        __3XM_PANEL=ABSENT
        __3XM_UID=0
        __3XM_OS=ubuntu
        __3XM_OSVERSION=24.04
        __3XM_ARCH=x86_64
        __3XM_SYSTEMD=1
        __3XM_DISK_KB=8000000
        __3XM_COMPLETE=1
    """.trimIndent()
    @Test fun supportedCleanServerCanOfferInstallation() { assertEquals(SetupDecision.OfferInstallation, InspectionReport.parse(clean).inspection.decide()) }
    @Test fun truncatedOutputCannotOfferInstallation() { assertTrue(InspectionReport.parse(clean.substringBefore("__3XM_COMPLETE")).inspection.decide() is SetupDecision.ReviewRequired) }
    @Test fun dockerInstallationWinsOverEarlierAbsentResult() { assertEquals(SetupDecision.ConnectExisting, InspectionReport.parse(clean + "\n__3XM_PANEL=PRESENT").inspection.decide()) }
    @Test fun workloadsMissingRightsAndUnsupportedOsAreNotEmptyServers() {
        listOf(clean + "\n__3XM_PORT=443", clean + "\n__3XM_WORKLOAD=nginx.service", clean.replace("UID=0", "UID=1000"), clean.replace("OS=ubuntu", "OS=unknown"), clean.replace("DISK_KB=8000000", "DISK_KB=100"), "").forEach {
            assertTrue(InspectionReport.parse(it).inspection.decide() is SetupDecision.ReviewRequired)
        }
    }
}
