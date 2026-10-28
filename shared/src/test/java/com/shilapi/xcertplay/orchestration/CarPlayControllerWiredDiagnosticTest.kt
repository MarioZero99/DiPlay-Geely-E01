package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.transport.DisconnectOrigin
import com.shilapi.xcertplay.transport.FirstFaultRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayControllerWiredDiagnosticTest {
    @Test
    fun physicalDetachIsRecordedAsFirstFaultWhenSessionIsLive() {
        val recorder = FirstFaultRecorder()
        CarPlayController.recordWiredDetachFault(
            isLiveSession = true,
            recorder = recorder,
            nowMillis = 1000L,
        )
        assertEquals(DisconnectOrigin.USB_PHYSICAL_DETACH, recorder.origin)
    }

    @Test
    fun physicalDetachUpgradesPreexistingSecondaryFaultInWiredSession() {
        val recorder = FirstFaultRecorder()
        recorder.record(DisconnectOrigin.NCM_READ_FAULT, "NCM read fault", nowMillis = 1000L)
        assertEquals(DisconnectOrigin.NCM_READ_FAULT, recorder.origin)

        CarPlayController.recordWiredDetachFault(
            isLiveSession = true,
            recorder = recorder,
            nowMillis = 1050L,
        )
        assertEquals(DisconnectOrigin.USB_PHYSICAL_DETACH, recorder.origin)
    }

    @Test
    fun fastReattachIsIdentifiedAsSupplyDipCandidate() {
        val isDip = CarPlayController.isSupplyDipReattach(
            lastDetachMillis = 1000L,
            attachMillis = 1800L, // 800ms gap
            wasSessionActive = true,
        )
        assertTrue("Reattach within 1.5s after active session indicates supply dip", isDip)

        val notDipSlow = CarPlayController.isSupplyDipReattach(
            lastDetachMillis = 1000L,
            attachMillis = 5000L, // 4s gap
            wasSessionActive = true,
        )
        assertFalse("Reattach after 4s is deliberate unplug", notDipSlow)

        val notDipInactive = CarPlayController.isSupplyDipReattach(
            lastDetachMillis = 1000L,
            attachMillis = 1800L,
            wasSessionActive = false,
        )
        assertFalse("Reattach without active session is loose contact / normal discovery", notDipInactive)
    }
}
