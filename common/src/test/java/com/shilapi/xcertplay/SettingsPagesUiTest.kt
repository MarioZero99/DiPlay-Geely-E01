package com.shilapi.xcertplay

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.shilapi.xcertplay.transport.WirelessHotspotMode
import com.shilapi.xcertplay.transport.adapter.AdapterRoutes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

/**
 * The settings area is one page that carries every bucket, and the two routes still left.
 *
 * The page has to show what each bucket has -- hotspot, phone, the connect step, display, audio,
 * diagnostics, firmware, language -- because hunting for which bucket a setting was filed under is
 * what the hub cost, and the hub's entry cards going away is the other half of that assertion.
 *
 * Wireless and wired are buckets and nothing else. They used to be pages of their own as well, and
 * having two ways in is what made "which settings page am I on" a question: a connect that found
 * something missing moved the driver to the wireless page, and back from there went to the settings
 * page rather than home. What is asserted here is that they are drawn on the settings page and
 * nowhere else, and that back means the home page from every page there is.
 *
 * What the pages say is pinned to a build with no adapter stack. These assertions are about the page
 * and the wording of the gaps, and a page that offers an external radio words several of them
 * differently — so without the pin the same test would pass or fail according to whether the machine
 * that ran it had the adapter mounted, and a suite that is green either way is evidence of nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en")
class SettingsPagesUiTest {

    @Before fun thisBuildHasNoAdapterStack() = AdapterRoutes.override(null)

    @After fun giveTheBuildItsOwnAnswerBack() = AdapterRoutes.resetOverride()

    /**
     * The buckets: the route that still opens each one, its heading, and a control only it has.
     *
     * The heading stands in for the control where a bucket's own is conditional -- the wired card is
     * only drawn on a head unit with a USB host, and Robolectric's answer to that is not this test's to
     * depend on. The controls are matched by prefix because a `choice` draws its own title and its
     * current value in one label ("Resolution · Native"), not the title alone.
     */
    private val bucket = listOf(
        Triple("wireless", "Wireless settings", "1 · Car hotspot (Wi-Fi)"),
        Triple("wired", "Wired settings", null),
        Triple("display", "Display and audio", "Resolution"),
        Triple("system", "System settings", "Save diagnostic report"),
    )

    /**
     * The buckets that are still pages of their own. The first two are the settings page's, and only
     * the settings page's: [everyBucketRouteStillOpensItsOwnPage] is the other half of that.
     */
    private val bucketRoute = listOf("display", "system")

    private fun open(page: String): DiPlayActivity {
        val intent = Intent(RuntimeEnvironment.getApplication(), DiPlayActivity::class.java)
            .putExtra("page", page)
        return Robolectric.buildActivity(DiPlayActivity::class.java, intent).setup().get()
    }

    /** Setup mode is restored from saved state, which is how a driver who left the page mid-setup returns to it. */
    private fun openInHotspotSetup(): DiPlayActivity {
        val intent = Intent(RuntimeEnvironment.getApplication(), DiPlayActivity::class.java)
            .putExtra("page", "settings")
        val pending = Bundle().apply { putBoolean("pending_car_hotspot", true) }
        return Robolectric.buildActivity(DiPlayActivity::class.java, intent).setup(pending).get()
    }

    private fun allViews(activity: DiPlayActivity): List<View> {
        val found = mutableListOf<View>()
        fun walk(view: View) {
            found += view
            if (view is ViewGroup) for (index in 0 until view.childCount) walk(view.getChildAt(index))
        }
        walk(activity.findViewById(android.R.id.content))
        return found
    }

    private inline fun <reified T : View> views(activity: DiPlayActivity): List<T> =
        allViews(activity).filterIsInstance<T>()

    private fun texts(activity: DiPlayActivity) =
        views<TextView>(activity).mapNotNull { it.text?.toString() }.filter { it.isNotBlank() }

    private fun onlyButton(activity: DiPlayActivity, label: String): Button {
        val found = views<Button>(activity).filter { it.text?.toString() == label }
        assertEquals("exactly one button labelled '$label'", 1, found.size)
        return found.single()
    }

    /**
     * Everything a bucket has, on the settings page the driver reaches by tapping the gear.
     *
     * This is the assertion that would have caught the hub: it is not enough for the page to be a
     * list of ways in. Each bucket's own controls have to be drawn, and the entry cards that stood in
     * for them have to be gone.
     */
    @Test fun theSettingsPageCarriesEveryBucket() {
        val settings = texts(open("settings"))
        bucket.forEach { (_, heading, control) ->
            assertTrue("the settings page shows the '$heading' bucket", heading in settings)
            assertTrue("and what '$heading' holds", control == null || settings.any { it.startsWith(control) })
        }
        bucket.forEach { (_, heading, _) ->
            assertFalse("no way in to '$heading' is left to tap", "Open $heading" in settings)
        }
        assertFalse("the hub's entry cards are gone", "Open wireless settings" in settings)
    }

    /** The routes that are still routes, each one still its own page. */
    @Test fun everyBucketRouteStillOpensItsOwnPage() {
        bucket.filter { it.first in bucketRoute }.forEach { (page, heading, control) ->
            val shown = texts(open(page))
            assertTrue("'$page' is headed '$heading'", heading in shown)
            assertTrue("'$page' still holds its own controls", control == null || shown.any { it.startsWith(control) })
            bucket.filter { it.first != page }.forEach { (_, other, _) ->
                assertFalse("'$page' is not '$other'", other in shown)
            }
        }
    }

    /**
     * The wireless and wired buckets are on the settings page and nowhere else.
     *
     * They were pages as well as buckets, and that is what the connect flow used to move the driver
     * onto — so pressing Connect, the one control whose job is to connect, landed on a settings page.
     * Asking for the old route by name now gets the home page, which is the same statement from the
     * other side: there is no second settings page to be sent to.
     */
    @Test fun theConnectionBucketsAreOnlyOnTheSettingsPage() {
        listOf("wireless", "wired").forEach { route ->
            val shown = texts(open(route))
            assertFalse("'$route' is no longer a page of its own", "Wireless settings" in shown)
            assertTrue("'$route' falls through to the home page", "Connect phone" in shown)
        }
    }

    /** Back means one thing everywhere: the home page, and out of the app from there. */
    @Test fun backGoesToTheHomePageFromEveryPage() {
        (bucketRoute + "settings" + "about").forEach { page ->
            val activity = open(page)
            assertTrue("'$page' offers Back", views<Button>(activity).any { it.text?.toString() == "Back" })
            activity.onBackPressedDispatcher.onBackPressed()
            assertTrue(
                "'$page' goes back to the home page",
                views<Button>(activity).any { it.text?.toString() == "Connect phone" },
            )
            assertFalse("back from '$page' does not leave the app", activity.isFinishing)
        }
    }

    /** The settings page is not the bottom of the stack either: back from it is the home page. */
    @Test fun backFromTheSettingsPageReturnsToTheHomePage() {
        val activity = open("settings")
        assertTrue(
            "the settings page offers Back",
            views<Button>(activity).any { it.text?.toString() == "Back" },
        )
        activity.onBackPressedDispatcher.onBackPressed()
        assertTrue(
            "the home page is shown",
            views<Button>(activity).any { it.text?.toString() == "Connect phone" },
        )
        assertFalse("the settings page is behind us", "Wireless settings" in texts(activity))
        assertFalse("back from the settings page does not leave the app", activity.isFinishing)
    }

    /** The card a section heading sits in: the label, the heading's own row, then the card that holds it. */
    private fun cardTitled(activity: DiPlayActivity, title: String): View {
        val heading = views<TextView>(activity).first { it.text?.toString() == title }
        return (heading.parent as View).parent as View
    }

    /**
     * One column where the head unit is narrow, two where it is wide.
     *
     * The settings page carries everything now, and a 1920 px head unit is 1200 dp: drawn one to a
     * row, every card is stretched across the full 1856 px and the page runs to several screens. The
     * pairing is the whole of that fix, so it is pinned here rather than left to a screenshot -- two
     * cards in one row are two cards in one row, whether or not the machine running this has a head
     * unit attached.
     */
    @Test fun onANarrowScreenTheSettingsCardsAreOneToARow() {
        val activity = open("settings")
        assertSame(
            "a narrow head unit keeps the single column",
            cardTitled(activity, "1 · Car hotspot (Wi-Fi)").parent,
            cardTitled(activity, "2 · Bluetooth and phone").parent,
        )
    }

    @Test
    @Config(sdk = [28], qualifiers = "en-w1200dp-h720dp")
    fun onAWideScreenTheSettingsCardsArePairedIntoRowsOfTwo() {
        val activity = open("settings")
        val first = cardTitled(activity, "1 · Car hotspot (Wi-Fi)")
        val second = cardTitled(activity, "2 · Bluetooth and phone")
        assertNotSame("a paired card is not in the same cell as the next", first.parent, second.parent)
        assertSame("the pair shares one row", first.parent.parent, second.parent.parent)
    }

    @Test fun noPageOffersTheSameActionTwice() {
        listOf("settings", "display", "system", "about").forEach { page ->
            val labels = views<Button>(open(page)).mapNotNull { it.text?.toString() }
            val repeated = labels.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            assertTrue("$page repeats $repeated", repeated.isEmpty())
        }
    }

    /**
     * The permission deep link used to sit on the connection page, on the wired section and on the
     * settings page at once. It lives in the system bucket's permissions card now, so it is on the
     * settings page (which carries that card) and on the system route (which is that card's own page)
     * and nowhere else: the two are one card drawn twice, not two ways in.
     */
    @Test fun theAppPermissionsLinkIsOfferedInOnePlace() {
        assertTrue("the settings page offers it, through the system bucket", "App permissions" in texts(open("settings")))
        assertTrue("the system route is that bucket's own page", "App permissions" in texts(open("system")))
        listOf("display").forEach { page ->
            assertFalse("$page carries no permissions card", "App permissions" in texts(open(page)))
        }
    }

    /**
     * The car's Bluetooth screen is reached from the pairing step on the wireless page, through the
     * "Open Bluetooth" button that step shows while there is no phone paired yet. Nothing on the
     * system page reaches it: the copy that used to sit there was a second way to the same screen.
     */
    @Test fun theSystemPageDoesNotOfferTheBluetoothScreen() {
        assertFalse("the system page leaves Bluetooth to the wireless page", "Bluetooth settings" in texts(open("system")))
    }

    /**
     * The phone is chosen in the Bluetooth card, and the pages that do not carry that card offer no
     * chooser.
     *
     * This used to say "the wireless page and nothing else", which the settings page now contradicts on
     * purpose: it carries the same card. What is left to guard is that the chooser was not spread any
     * further. It is drawn from what the probe says this head unit has, and this test head unit has no
     * radio at all — so on it no page draws a chooser, and the assertion below is weaker here than it
     * reads. The card itself is pinned by [theSettingsPageCarriesEveryBucket].
     */
    @Test fun noPageOutsideTheBluetoothCardOffersAPhoneChooser() {
        listOf("display", "system").forEach { page ->
            val labels = views<Button>(open(page)).mapNotNull { it.text?.toString() }
            assertFalse(
                "$page does not offer a phone chooser",
                labels.any { it.startsWith("Choose iPhone") },
            )
        }
    }

    /**
     * Both legs of the wireless route are on the page and the hotspot is reachable. This is the shape the
     * page used to lose: the whole thing sat behind `deviceSupportsWireless()`, so a head unit whose
     * Bluetooth or hotspot calls did not answer got a page with no way to set the hotspot up at all --
     * the one route that asks nothing of those calls, since the driver turns the car's hotspot on and
     * types its name in. The probe half of that is pinned in DeviceConnectionSupportTest; this is the page.
     */
    @Test fun theWirelessPageCarriesBothLegsAndTheConnectStep() {
        val shown = texts(open("settings"))
        assertTrue("the hotspot leg is a step", "1 · Car hotspot (Wi-Fi)" in shown)
        assertTrue("the Bluetooth leg is a step", "2 · Bluetooth and phone" in shown)
        assertTrue("the connect step is there", "3 · Connect" in shown)
        assertTrue("the hotspot can be opened", "Open car hotspot settings" in shown)
        assertTrue("the hotspot can be edited", shown.any { it.startsWith("Edit saved hotspot") })
        assertFalse("no blocked-connect card without a blocked connect", "Wireless is not ready" in shown)
    }

    /**
     * A head unit no radio here could carry the leg for is told so — and keeps its steps.
     *
     * The manual hotspot route asks nothing of the Bluetooth leg, so the steps are not the probe's to
     * take: losing them is what this page used to do, and the test above is the other half of that.
     * This test head unit has no Bluetooth service at all, so both halves run on the same page.
     */
    @Test fun aHeadUnitWithNoRadioIsToldSoAndKeepsItsSteps() {
        val shown = texts(open("settings"))
        assertTrue(
            "the unsupported line is said",
            shown.any { it.startsWith("This head unit is not supported") },
        )
        assertTrue("the hotspot leg is still a step", "1 · Car hotspot (Wi-Fi)" in shown)
        assertTrue("the connect step is still there", "3 · Connect" in shown)
    }

    /**
     * The switch that lets the app bring the car's own hotspot up as it opens.
     *
     * It is drawn where the car hotspot is the mode, which is the only mode whose access point the app
     * does not bring up itself at connect time. What the switch means for the other two modes is in
     * [CarHotspotAutoStartTest]; what it costs the driver is in `CarHotspotController`.
     */
    @Test fun theCarHotspotIsOfferedASwitchToComeUpOnItsOwn() {
        assertTrue(
            "the switch is offered where the car hotspot is the mode",
            "Turn on the car hotspot at start" in texts(open("settings")),
        )
    }

    /** The step is named for the leg it is, not for the gesture the leg used to hide behind. */
    @Test fun theWirelessStepsAreNamedForTheirLegs() {
        val shown = texts(open("settings"))
        assertFalse("nothing is called a connection choice", "1 · Choose your connection" in shown)
        assertFalse("nothing is called just a pairing", "2 · Pair your iPhone" in shown)
    }

    /**
     * A mode saved where it was offered can outlive the mode being offered. Storage keeps WIFI_P2P on Android
     * 10 and later, and the probe can still say the hotspot calls do not answer there, which leaves the manual
     * mode as the only one on offer. Drawing the saved mode anyway put the step on the Wi-Fi Direct controls:
     * the hotspot could not be opened, and the one mode on offer was drawn as a button that never showed as
     * chosen.
     */
    @Test
    @Config(sdk = [33], qualifiers = "en")
    fun aSavedHotspotModeThatIsNoLongerOfferedFallsBackToTheOneThatIs() {
        AirPlayPersistence.saveWirelessHotspotMode(RuntimeEnvironment.getApplication(), WirelessHotspotMode.WIFI_P2P)
        val activity = open("settings")
        val shown = texts(activity)
        assertTrue("the manual mode's own controls are drawn", "Open car hotspot settings" in shown)
        assertFalse("the Wi-Fi Direct controls are not drawn", "Open car Wi-Fi settings" in shown)
        assertFalse(
            "the lone mode is stated as the one in use, not left as an unchosen button",
            views<Button>(activity).any { it.text?.toString() == "Built-in car hotspot" },
        )
    }

    /**
     * The same-network route is not refused over a hotspot state the head unit never reported.
     *
     * `carHotspotOff()` is a car-hotspot-mode notion: it is true only where the mode is MANUAL *and* the
     * hotspot is known to be off. Read as its negation for every other mode it says "the car's hotspot
     * is on" on a head unit that was never asked — and since one radio cannot be an access point and a
     * station at once, the same-network route was then refused before it started, every time, with the
     * page telling the driver to turn off a hotspot that was already off.
     *
     * The head unit here answers nothing about its access point, which is the case that must not be
     * read as "on". The other direction — a hotspot the head unit *does* report as running — is pinned
     * on the rule itself, in `WirelessReadinessCheckTest`.
     */
    @Test fun theSameNetworkRouteIsNotRefusedOverAHotspotTheHeadUnitWillNotReport() {
        AirPlayPersistence.saveWirelessHotspotMode(
            RuntimeEnvironment.getApplication(),
            WirelessHotspotMode.EXISTING_WIFI,
        )

        val shown = texts(open("settings"))

        assertFalse(
            "the car's own hotspot is not claimed to be running",
            shown.any { it.startsWith("The car’s own hotspot is still on") },
        )
    }

    /**
     * A saved password is shown for a network the car is not on -- the whole point of the list.
     *
     * `joinedWifiName()` reads the *live* connection, and on a head unit whose Wi-Fi has not come up
     * yet -- every start after a reboot -- there is none to read. A page that took its idea of what is
     * saved from that name alone said "not on any Wi-Fi yet", offered the dialog with both fields
     * empty, and left the password the driver had already saved invisible and uneditable, which is
     * what "my password was not saved" was read from. The list is storage's, so it is drawn the same
     * whatever the radio is doing; this pins the head unit that is on nothing at all.
     *
     * The joined-state line is asserted first and is what makes the rest evidence: if this head unit
     * ever started reporting a connection, the test would fail on that line rather than pass on a name
     * that had come from the live network.
     */
    @Test fun aSavedPasswordIsShownForANetworkTheCarIsNotOn() {
        val context = RuntimeEnvironment.getApplication()
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.EXISTING_WIFI)
        AirPlayPersistence.saveExistingWifiNetwork(context, "Flat 12 Wi-Fi", "hunter2hunter2")

        val activity = open("settings")
        val shown = texts(activity)

        assertTrue(
            "this head unit is on no Wi-Fi at all",
            "Car is not on any Wi-Fi yet · Connect it in the car’s Wi-Fi settings." in shown,
        )
        assertTrue("the saved network is named on the page", "Flat 12 Wi-Fi" in shown)
        assertTrue(
            "the saved password is offered for editing, not only listed",
            views<Button>(activity).any { it.text?.toString() == "Edit" },
        )
    }

    /**
     * With nothing saved, the list says so instead of drawing no rows and no word about them.
     *
     * An empty list and a list the page failed to draw are the same picture otherwise, and on the head
     * unit this page is for the driver has no other way to tell a password that was never saved from
     * one the page cannot see.
     */
    @Test fun theSavedListSaysWhenThereIsNothingInIt() {
        AirPlayPersistence.saveWirelessHotspotMode(
            RuntimeEnvironment.getApplication(),
            WirelessHotspotMode.EXISTING_WIFI,
        )

        val activity = open("settings")

        assertTrue(
            "the empty list is said in words",
            "No Wi-Fi password saved yet." in texts(activity),
        )
        assertFalse(
            "and no row is drawn for a network that is not there",
            views<Button>(activity).any { it.text?.toString() in listOf("Edit", "Delete") },
        )
    }

    /**
     * The name in a saved row's dialog is shown, not typed into -- an edit must not be able to add.
     *
     * The entry is stored and looked up *by* its name, so an editable field there is not an edit: the
     * save upserts by name, and a driver who corrected a spelling would get the corrected entry added
     * while the old one, password and all, stayed in the list -- two rows for one network, from a
     * dialog whose whole job is to change one. Held to the name its row carries, the dialog can only
     * change that entry's password.
     */
    @Test fun theSavedRowsDialogShowsTheNameItCannotChange() {
        val context = RuntimeEnvironment.getApplication()
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.EXISTING_WIFI)
        AirPlayPersistence.saveExistingWifiNetwork(context, "HomeWiFi", "hunter2hunter2")

        val activity = open("settings")
        onlyButton(activity, "Edit").performClick()
        val dialog = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())

        val fields = descendants(dialog.window!!.decorView).filterIsInstance<EditText>().toList()
        val name = fields.first()
        val password = fields.last()
        assertEquals("the entry's own name is in the name field", "HomeWiFi", name.text.toString())
        assertFalse("and the name cannot be typed over", name.isEnabled)
        assertFalse("nor reached by the keyboard", name.isFocusable)
        assertEquals("the password is what this dialog is for", "hunter2hunter2", password.text.toString())
        assertTrue("and the password stays editable", password.isEnabled)
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    /**
     * A refused connect leads the page with what it was refused for, and that card has to be recomputed rather
     * than remembered. The driver saves the hotspot details, or turns the hotspot on in the car settings, and
     * comes back — a card holding the readiness captured at the refusal went on saying it was unsaved while
     * the connect step below it said nothing was missing.
     */
    @Test fun aRefusedConnectLeadsWithWhyAndStopsSayingItOnceItIsFixed() {
        val activity = open("settings")
        onlyButton(activity, "Connect phone").performClick()
        assertTrue("the refusal is led with", "Wireless is not ready" in texts(activity))
        assertTrue(
            "the unsaved hotspot is named",
            texts(activity).any { it.startsWith("The hotspot name and password are not saved") },
        )
        // The driver saves them and presses Connect again. They are still on the page they pressed it
        // on: a refusal says why and leaves them there, which is the whole of the change.
        AirPlayPersistence.saveManualHotspotSsid(activity, "MyCar")
        AirPlayPersistence.saveManualHotspotPassphrase(activity, "hunter2hunter2")
        onlyButton(activity, "Connect phone").performClick()
        // The card is still raised -- other gaps remain -- so its absence below is recomputation, not the card
        // having gone away, which is the shape that would make this assertion pass for the wrong reason.
        assertTrue("the card is still there", "Wireless is not ready" in texts(activity))
        assertTrue(
            "a gap that is still open is still named",
            texts(activity).any { it.startsWith("Bluetooth is chosen, but the iPhone is not") },
        )
        assertFalse(
            "the gap that was fixed is no longer said",
            texts(activity).any { it.startsWith("The hotspot name and password are not saved") },
        )
    }

    /**
     * Setup mode used to be one-way on this head unit. Its one mode is drawn as a statement, so there was no
     * other mode to tap, and the only thing that cleared the flag was saving the dialog — a driver who tapped
     * the mode to look at it, or who was sent there by a connect that found nothing usable, had to save
     * something before the page would behave normally again.
     */
    @Test fun hotspotSetupCanBeLeftWithoutSaving() {
        val activity = openInHotspotSetup()
        assertTrue("setup asks for the details", "Save hotspot details and use this mode" in texts(activity))
        onlyButton(activity, "Cancel").performClick()
        assertFalse(
            "setup is no longer being asked for",
            "Save hotspot details and use this mode" in texts(activity),
        )
        assertTrue(
            "the ordinary edit button is back",
            texts(activity).any { it.startsWith("Edit saved hotspot") },
        )
        assertFalse(
            "the way out went with the mode it left",
            views<Button>(activity).any { it.text?.toString() == "Cancel" },
        )
    }
}
