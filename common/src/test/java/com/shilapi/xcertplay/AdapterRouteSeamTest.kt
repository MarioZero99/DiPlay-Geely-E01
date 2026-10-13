package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.transport.WirelessBluetoothHop
import com.shilapi.xcertplay.transport.adapter.AdapterPage
import com.shilapi.xcertplay.transport.adapter.AdapterRoute
import com.shilapi.xcertplay.transport.adapter.AdapterRoutes
import com.shilapi.xcertplay.transport.adapter.WirelessAdapterStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The adapter seam itself, and the fallback when a build has no adapter stack.
 *
 * Both sides are driven through [AdapterRoutes.override], which is the point of having it: these assertions
 * hold whether or not the machine running them has the stack mounted, so a green run says something about the
 * seam rather than about the machine.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AdapterRouteSeamTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @After fun giveTheBuildItsOwnAnswerBack() {
        AdapterRoutes.resetOverride()
    }

    @Test fun aBuildWithNoAdapterStackSaysItHasNone() {
        AdapterRoutes.override(null)
        assertFalse(AdapterRoutes.available)
    }

    @Test fun aBuildWithAnAdapterStackSaysItHasOne() {
        AdapterRoutes.override(NoRadioRoute())
        assertTrue(AdapterRoutes.available)
    }

    /**
     * The fallback that matters: the driver's stored choice is the adapter, but this build cannot honour it.
     * Leaving the choice standing would offer a radio the build has no way to drive, so the car's own radio is
     * the answer and the page shows no choice to make.
     */
    @Test fun withoutTheStackAStoredAdapterChoiceFallsBackToTheCarRadio() {
        AdapterRoutes.override(null)
        DiPlayPreferences.saveBluetoothHop(context, WirelessBluetoothHop.USB_ADAPTER)
        assertEquals(WirelessBluetoothHop.CAR, DiPlayPreferences.bluetoothHop(context))
    }

    @Test fun withTheStackAStoredAdapterChoiceIsHonoured() {
        AdapterRoutes.override(NoRadioRoute())
        DiPlayPreferences.saveBluetoothHop(context, WirelessBluetoothHop.USB_ADAPTER)
        assertEquals(WirelessBluetoothHop.USB_ADAPTER, DiPlayPreferences.bluetoothHop(context))
    }

    /**
     * The one gap wording that is shown by every build, including one with no external radio to offer.
     *
     * Its long form ends by sending the driver to that radio, so without one it would point at a button that
     * is not on the page. The pair is chosen rather than fixed, and this pins the choice — reverting it to a
     * single string is what this test exists to catch.
     */
    @Test fun theCarBluetoothWordingMentionsTheExternalRadioOnlyWhereThereIsOne() {
        assertEquals(R.string.gap_car_bluetooth, carBluetoothOffText(hasAdapter = true))
        assertEquals(R.string.gap_car_bluetooth_no_adapter, carBluetoothOffText(hasAdapter = false))
    }

    @Test fun theWordingWithoutAnAdapterNamesNoRadioThisBuildHasNotGot() {
        val text = context.getString(R.string.gap_car_bluetooth_no_adapter)
        assertFalse("the no-adapter wording sends the driver to the external radio: $text", text.contains("external Bluetooth", ignoreCase = true))
        assertTrue(context.getString(R.string.gap_car_bluetooth).contains("external Bluetooth", ignoreCase = true))
    }

    /** The same, in the other locale: a translation that kept the sentence would be just as wrong. */
    @Test @Config(qualifiers = "zh-rCN")
    fun theWordingWithoutAnAdapterNamesNoRadioThisBuildHasNotGotInChinese() {
        val text = context.getString(R.string.gap_car_bluetooth_no_adapter)
        assertFalse("没装外置蓝牙的构建里不该出现「外置蓝牙」：$text", text.contains("外置蓝牙"))
        assertTrue("外置蓝牙", context.getString(R.string.gap_car_bluetooth).contains("外置蓝牙"))
    }

    /** A route for the tests that only ask whether one is there; nothing in them opens a page. */
    private class NoRadioRoute : AdapterRoute {
        override fun present(context: Context): Boolean = false

        override fun page(
            context: Context,
            onStatus: (WirelessAdapterStatus) -> Unit,
            onLine: (String) -> Unit,
        ): AdapterPage = error("no test here opens an adapter page")

        override fun drainDiagnostics(): List<String> = emptyList()
    }
}
