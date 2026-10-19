package com.shilapi.xcertplay

import android.content.Context
import android.content.SharedPreferences
import com.shilapi.xcertplay.network.ExistingWifiDriverCredentials
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Where the passwords the driver typed for existing Wi-Fi networks are kept.
 *
 * Each entry belongs to one network, and the one that is used is the one whose name matches the
 * network the car is on — so the two things worth holding to here are that saving today's password
 * does not throw away yesterday's, and that the name a head unit reports finds the entry the driver
 * typed even when the two spellings differ. The pair a build from before this list wrote is read once
 * and only when the list itself says nothing, which is what carries a driver's saved password across
 * the update that introduced the list.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ExistingWifiNetworksPersistenceTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val prefs: SharedPreferences
        get() = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)

    @Test
    fun aPasswordSavedForOneNetworkDoesNotThrowAwayTheOneSavedForAnother() {
        AirPlayPersistence.saveExistingWifiNetwork(context, "Home Wi-Fi", "home-password")
        AirPlayPersistence.saveExistingWifiNetwork(context, "Work Wi-Fi", "work-password")

        assertEquals(
            listOf(
                ExistingWifiDriverCredentials("Home Wi-Fi", "home-password"),
                ExistingWifiDriverCredentials("Work Wi-Fi", "work-password"),
            ),
            AirPlayPersistence.loadExistingWifiNetworks(context),
        )
        // The older one is still the one the car finds when it is on that network, which is the whole
        // reason the list is kept: a driver is not asked for the same password every other day.
        assertEquals(
            "home-password",
            AirPlayPersistence.loadExistingWifiPassphrase(context, "Home Wi-Fi"),
        )
    }

    @Test
    fun savingTheSameNetworkAgainReplacesItsPasswordInsteadOfAddingAnEntry() {
        AirPlayPersistence.saveExistingWifiNetwork(context, "Home Wi-Fi", "first-password")
        AirPlayPersistence.saveExistingWifiNetwork(context, "Home Wi-Fi", "second-password")

        assertEquals(
            listOf(ExistingWifiDriverCredentials("Home Wi-Fi", "second-password")),
            AirPlayPersistence.loadExistingWifiNetworks(context),
        )
    }

    @Test
    fun saveExistingWifiNetworkTrimsLeadingAndTrailingWhitespace() {
        AirPlayPersistence.saveExistingWifiNetwork(context, "  Home Wi-Fi  ", "  pass1234  ")
        val stored = AirPlayPersistence.loadExistingWifiNetworks(context)
        assertEquals("Home Wi-Fi", stored.first().ssid)
        assertEquals("pass1234", stored.first().passphrase)
    }

    @Test(expected = IllegalArgumentException::class)
    fun saveExistingWifiNetworkRejectsPassphraseShorterThanEightCharacters() {
        AirPlayPersistence.saveExistingWifiNetwork(context, "Home Wi-Fi", "1234567")
    }

    /**
     * The single pair a build from before the list wrote, which is where an existing driver's saved
     * password is sitting on the head unit the list arrives on.
     */
    @Test
    fun theSinglePairFromBeforeTheListSeedsItOnce() {
        prefs.edit()
            .putString("existing_wifi_ssid", "Car Wi-Fi")
            .putString("existing_wifi_passphrase", "legacy-password")
            .apply()

        assertEquals(
            listOf(ExistingWifiDriverCredentials("Car Wi-Fi", "legacy-password")),
            AirPlayPersistence.loadExistingWifiNetworks(context),
        )
        assertEquals(
            "legacy-password",
            AirPlayPersistence.loadExistingWifiPassphrase(context, "car wi-fi"),
        )

        // Half a pair is not a password: a name with no key is nothing the phone could join with, so
        // it seeds nothing rather than an entry that would be sent as an empty one.
        prefs.edit().remove("existing_wifi_passphrase").apply()
        assertEquals(
            emptyList<ExistingWifiDriverCredentials>(),
            AirPlayPersistence.loadExistingWifiNetworks(context),
        )
    }

    @Test
    fun malformedLegacyCredentialsAreRejected() {
        // Passphrase too short (< 8 chars)
        prefs.edit()
            .putString("existing_wifi_ssid", "Car Wi-Fi")
            .putString("existing_wifi_passphrase", "short")
            .remove(KEY_NETWORKS)
            .apply()
        assertEquals(emptyList<ExistingWifiDriverCredentials>(), AirPlayPersistence.loadExistingWifiNetworks(context))

        // Contains NUL
        prefs.edit()
            .putString("existing_wifi_ssid", "Car\u0000Wi-Fi")
            .putString("existing_wifi_passphrase", "valid-pass-123")
            .remove(KEY_NETWORKS)
            .apply()
        assertEquals(emptyList<ExistingWifiDriverCredentials>(), AirPlayPersistence.loadExistingWifiNetworks(context))

        // Passphrase contains NUL
        prefs.edit()
            .putString("existing_wifi_ssid", "Car Wi-Fi")
            .putString("existing_wifi_passphrase", "valid\u0000pass-123")
            .remove(KEY_NETWORKS)
            .apply()
        assertEquals(emptyList<ExistingWifiDriverCredentials>(), AirPlayPersistence.loadExistingWifiNetworks(context))

        // Blank SSID
        prefs.edit()
            .putString("existing_wifi_ssid", "   ")
            .putString("existing_wifi_passphrase", "valid-pass-123")
            .remove(KEY_NETWORKS)
            .apply()
        assertEquals(emptyList<ExistingWifiDriverCredentials>(), AirPlayPersistence.loadExistingWifiNetworks(context))
    }

    /** Once the list exists it is the whole truth, and the pair beside it is stale by construction. */
    @Test
    fun theListWinsOverTheSinglePairOnceItIsThere() {
        prefs.edit()
            .putString("existing_wifi_ssid", "Car Wi-Fi")
            .putString("existing_wifi_passphrase", "legacy-password")
            .putString(KEY_NETWORKS, """[{"ssid":"Home Wi-Fi","passphrase":"home-password"}]""")
            .apply()

        assertEquals(
            listOf(ExistingWifiDriverCredentials("Home Wi-Fi", "home-password")),
            AirPlayPersistence.loadExistingWifiNetworks(context),
        )
        assertEquals("", AirPlayPersistence.loadExistingWifiPassphrase(context, "Car Wi-Fi"))
    }

    /**
     * One element this build cannot send must not cost the driver the elements it can.
     *
     * The text in storage is this app's own, but it is also a file: it can be hand-edited, truncated,
     * or written by a build with a different idea of what an entry is. Every element below is one that
     * could not be sent as a password, and each is dropped on its own.
     */
    @Test
    fun anElementThatCouldNotBeSentIsDroppedAndTheRestStillLoad() {
        val stored = JSONArray()
            .put(JSONObject().put("ssid", "Home Wi-Fi").put("passphrase", "home-password"))
            // Too short to be a passphrase the phone would accept, so it is a rejected join rather
            // than the missing password it looks like.
            .put(JSONObject().put("ssid", "Short Wi-Fi").put("passphrase", "short"))
            .put(JSONObject().put("ssid", "  ").put("passphrase", "blank-name-password"))
            .put(JSONObject().put("passphrase", "no-name-password"))
            .put(JSONObject().put("ssid", "").put("passphrase", "empty-name-password"))
            .put("not-an-object")
            .put(JSONObject().put("ssid", "Nul Wi-Fi").put("passphrase", "nul\u0000password"))
        prefs.edit().putString(KEY_NETWORKS, stored.toString()).apply()

        assertEquals(
            listOf(ExistingWifiDriverCredentials("Home Wi-Fi", "home-password")),
            AirPlayPersistence.loadExistingWifiNetworks(context),
        )
    }

    @Test
    fun removingANetworkForgetsItAndLeavesTheOthers() {
        AirPlayPersistence.saveExistingWifiNetwork(context, "Home Wi-Fi", "home-password")
        AirPlayPersistence.saveExistingWifiNetwork(context, "Work Wi-Fi", "work-password")

        AirPlayPersistence.removeExistingWifiNetwork(context, "work wi-fi")

        assertEquals(
            listOf(ExistingWifiDriverCredentials("Home Wi-Fi", "home-password")),
            AirPlayPersistence.loadExistingWifiNetworks(context),
        )
        assertFalse(AirPlayPersistence.hasExistingWifiPassphrase(context, "Work Wi-Fi"))
        // The pair named the network just removed, so it goes with it: a build from before this change
        // reads only the pair, and it would otherwise go on handing out a password taken away here.
        assertNull(prefs.getString("existing_wifi_ssid", null))
        assertNull(prefs.getString("existing_wifi_passphrase", null))
    }

    @Test
    fun aPasswordSavedForANameThatDiffersOnlyInCaseCountsAsSaved() {
        AirPlayPersistence.saveExistingWifiNetwork(context, "Home Wi-Fi", "home-password")

        assertTrue(AirPlayPersistence.hasExistingWifiPassphrase(context, "home wi-fi"))
        assertTrue(AirPlayPersistence.hasExistingWifiPassphrase(context, " HOME WI-FI "))
        assertEquals(
            "home-password",
            AirPlayPersistence.loadExistingWifiPassphrase(context, "HOME WI-FI"),
        )
        assertFalse(AirPlayPersistence.hasExistingWifiPassphrase(context, "Someone Else's Wi-Fi"))
    }

    /**
     * Two entries that answer to the name but cannot be told apart hold no password for it.
     *
     * This is the connect path's rule asked again on the storage side, and it has to give the same
     * answer: the page says a password is saved exactly when one would be sent, and a page that says
     * "saved" over a password the manager will refuse to use is a driver who never types it in again.
     */
    @Test
    fun twoNamesThatDifferOnlyInCaseHoldNoPasswordForTheNameTheyBothAnswerTo() {
        prefs.edit()
            .putString(
                KEY_NETWORKS,
                """[{"ssid":"Home Wi-Fi","passphrase":"first-password"},""" +
                    """{"ssid":"home wi-fi","passphrase":"second-password"}]""",
            )
            .apply()

        assertEquals("", AirPlayPersistence.loadExistingWifiPassphrase(context, "HOME WI-FI"))
        assertFalse(AirPlayPersistence.hasExistingWifiPassphrase(context, "HOME WI-FI"))
    }

    private companion object {
        const val KEY_NETWORKS = "existing_wifi_networks"
    }
}
