package com.shilapi.xcertplay

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The experimental bucket is in the local package and in no other.
 *
 * Two packages come out of this tree and they differ by one build switch. The half that can be asserted
 * here is the half a driver sees: the official package must not draw the bucket at all, because a
 * heading over nothing reads as a fault and an empty bucket is what a half-applied switch would look
 * like. The other half -- the log-upload key being in the local package and not the official one -- is
 * a fact about the APK, and [com.shilapi.xcertplay.SupabaseLogUploadTest] and the packaging script's
 * own checks are where it is pinned.
 *
 * The answer is pinned rather than read, the way [SettingsPagesUiTest] pins the adapter: a unit test
 * cannot rebuild the package around itself, so without the pin this would assert whatever the machine
 * that ran it happened to be built with.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en")
class ExperimentalFeaturesUiTest {

    @After fun giveThePackageItsOwnAnswerBack() = ExperimentalFeatures.resetOverride()

    private fun settingsPage(): DiPlayActivity {
        val intent = Intent(RuntimeEnvironment.getApplication(), DiPlayActivity::class.java)
            .putExtra("page", "settings")
        return Robolectric.buildActivity(DiPlayActivity::class.java, intent).setup().get()
    }

    private fun texts(activity: DiPlayActivity): List<String> {
        val found = mutableListOf<String>()
        fun walk(view: View) {
            if (view is TextView) view.text?.toString()?.let { found += it }
            if (view is ViewGroup) for (index in 0 until view.childCount) walk(view.getChildAt(index))
        }
        walk(activity.findViewById(android.R.id.content))
        return found
    }

    @Test
    fun anOfficialPackageDrawsNoExperimentalBucket() {
        ExperimentalFeatures.override(false)

        val texts = texts(settingsPage())

        // The page it is being compared against is the settings page, so an empty list cannot pass.
        assertTrue("the settings page should carry its own buckets", texts.contains("System settings"))
        assertFalse(
            "the official package must not draw the experimental bucket",
            texts.contains("Experimental features"),
        )
        assertFalse(
            "nor anything that belongs to it",
            texts.contains("Report upload"),
        )
    }

    @Test
    fun aLocalPackageDrawsIt() {
        ExperimentalFeatures.override(true)

        val texts = texts(settingsPage())

        assertTrue(texts.contains("System settings"))
        assertTrue("the local package draws the bucket", texts.contains("Experimental features"))
        assertTrue("and what is in it", texts.contains("Report upload"))
    }
}
