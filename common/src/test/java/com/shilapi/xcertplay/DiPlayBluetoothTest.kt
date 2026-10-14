package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which of the head unit's own services answered for the local radio, and whether the answer is an
 * address at all.
 *
 * The value decides what the accessory tells the iPhone its own radio is -- the AirPlay `bluetoothIDs`
 * and the iAP2 Bluetooth transport's MAC -- so a report has to be able to say whether it came from the
 * car or from the AirPlay identity fallback. Downstream the two are the same shape, and the wrong one
 * is a link the phone is asked to believe belongs to a different radio.
 */
class DiPlayBluetoothTest {
    /** The order is the question: the adapter is asked first, and the setting is the second opinion. */
    @Test fun theSourcesAreAskedInOrderAndTheAnswerNamesItself() {
        val chosen = DiPlayBluetooth.firstUsableLocalAddress(
            listOf(
                DiPlayBluetooth.SOURCE_ADAPTER to null,
                DiPlayBluetooth.SOURCE_SETTINGS to CAR_RADIO,
            ),
        )

        assertEquals(DiPlayBluetooth.LocalAddress(CAR_RADIO, DiPlayBluetooth.SOURCE_SETTINGS), chosen)
    }

    @Test fun theAdapterWinsWhenBothAnswer() {
        val chosen = DiPlayBluetooth.firstUsableLocalAddress(
            listOf(
                DiPlayBluetooth.SOURCE_ADAPTER to CAR_RADIO,
                DiPlayBluetooth.SOURCE_SETTINGS to "AA:BB:CC:DD:EE:FF",
            ),
        )

        assertEquals(DiPlayBluetooth.LocalAddress(CAR_RADIO, DiPlayBluetooth.SOURCE_ADAPTER), chosen)
    }

    /**
     * Nothing usable is nothing, not the first value that happened to be there: the caller has its own
     * fallback, and it can only reach it if this says so.
     */
    @Test fun aSourceThatOnlyHoldsAPlaceholderIsNoAnswer() {
        assertNull(
            DiPlayBluetooth.firstUsableLocalAddress(
                listOf(DiPlayBluetooth.SOURCE_ADAPTER to "00:00:00:00:00:00"),
            ),
        )
    }

    /**
     * The two shapes the platform answers with when it has no address to give. Both pass every check
     * downstream, so they are rejected here, where the source is still known.
     */
    @Test fun theTwoPlaceholdersAreNotAddresses() {
        assertFalse(DiPlayBluetooth.isUsableLocalBluetoothAddress("00:00:00:00:00:00"))
        assertFalse(DiPlayBluetooth.isUsableLocalBluetoothAddress("02:00:00:00:00:00"))
    }

    @Test fun theCarsOwnRadioAddressIs() {
        assertTrue(DiPlayBluetooth.isUsableLocalBluetoothAddress(CAR_RADIO))
    }

    @Test fun somethingThatIsNotAnAddressAtAllIsNot() {
        assertFalse(DiPlayBluetooth.isUsableLocalBluetoothAddress(""))
        assertFalse(DiPlayBluetooth.isUsableLocalBluetoothAddress("not an address"))
    }

    private companion object {
        /** What this head unit's own service reports, and what the address has to look like to be used. */
        const val CAR_RADIO = "00:0D:41:B3:BD:18"
    }
}
