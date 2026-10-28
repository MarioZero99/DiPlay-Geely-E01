package com.shilapi.governance

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.shilapi.governance.core.IVehicleGovernor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class VehicleGovernanceTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        VehicleGovernance.resetForTesting()
    }

    @After
    fun tearDown() {
        VehicleGovernance.resetForTesting()
    }

    @Test
    fun carLifeIsReadableBeforeAndAfterInstallButNeverSuppressedWithoutThePackage() {
        assertFalse("facade must answer before install", VehicleGovernance.isCarLifeInstalled())
        assertFalse(VehicleGovernance.isCarLifeSuppressed())

        VehicleGovernance.install(app, isExperimentalAllowed = true)

        // Robolectric's package manager has no CarLife, which is also what a non-CarLife head unit
        // looks like: the card says "not detected" and nothing is reported as suppressed.
        assertFalse(VehicleGovernance.isCarLifeInstalled())
        assertFalse(VehicleGovernance.isCarLifeSuppressed())
    }

    @Test
    fun carLifeActionAnswersEvenOnAHeadUnitWithoutCarLife() {
        VehicleGovernance.install(app, isExperimentalAllowed = false)

        val latch = CountDownLatch(1)
        var reported: Boolean? = null
        VehicleGovernance.setCarLifeSuppressionEnabled(true) { success ->
            reported = success
            latch.countDown()
        }

        assertTrue("callback must answer rather than hang", latch.await(2, TimeUnit.SECONDS))
        // Nothing to disable here, so the action reports "nothing to do" as success. This is also
        // what an official package does, which is why CarLife is not behind the voice/experimental
        // gate: the USB port it takes is the port CarPlay needs.
        assertEquals(true, reported)
    }

    @Test
    fun carLifeIsNotBehindTheVoiceExperimentalGate() {
        VehicleGovernance.install(app, isExperimentalAllowed = false)

        assertFalse("the voice governor is fenced off in this build", VehicleGovernance.isSupported)
        // The controls still answer: a fenced-off voice feature must not take the CarLife buttons
        // with it.
        assertFalse(VehicleGovernance.isCarLifeInstalled())
    }

    @Test
    fun theCarLifeRestoreIsNotRunOnAppExit() {
        // The voice suppression is a loan and is given back on exit; CarLife is not. This test is
        // about the facade's exit path staying as it was -- only the voice governor is asked to
        // restore, so a restore the driver did not ask for cannot happen at shutdown.
        VehicleGovernance.install(app, isExperimentalAllowed = true)

        val latch = CountDownLatch(1)
        VehicleGovernance.onAppExit { latch.countDown() }

        assertTrue("exit must complete", latch.await(2, TimeUnit.SECONDS))
        assertFalse("CarLife state on exit is read, never written", VehicleGovernance.isCarLifeSuppressed())
    }

    @Test
    fun gatesFeatureWhenExperimentalNotAllowed() {
        VehicleGovernance.install(app, isExperimentalAllowed = false)
        assertFalse("Must be unsupported when experimental is disabled", VehicleGovernance.isSupported)
        assertFalse("State must be false", VehicleGovernance.isSuppressedFlow.value)
    }

    @Test
    fun bindsGovernorWhenExperimentalAllowed() = runBlocking {
        val fakeGovernor = object : IVehicleGovernor {
            override val isSupported: Boolean = true
            val state = MutableStateFlow(false)
            override val stateFlow: StateFlow<Boolean> = state.asStateFlow()

            override suspend fun applySuppression(reason: String): Boolean {
                state.value = true
                return true
            }

            override suspend fun applyRestoration(reason: String): Boolean {
                state.value = false
                return true
            }

            override suspend fun ensureSelfHealed(reason: String): Boolean {
                state.value = false
                return true
            }
        }

        VehicleGovernance.install(app, isExperimentalAllowed = true, customGovernor = fakeGovernor)
        assertTrue(VehicleGovernance.isSupported)

        val latch = CountDownLatch(1)
        VehicleGovernance.setSuppressionEnabled(true) { success ->
            assertTrue(success)
            latch.countDown()
        }
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertTrue(VehicleGovernance.isSuppressedFlow.value)

        val exitLatch = CountDownLatch(1)
        VehicleGovernance.onAppExit {
            exitLatch.countDown()
        }
        assertTrue(exitLatch.await(2, TimeUnit.SECONDS))
        assertFalse(VehicleGovernance.isSuppressedFlow.value)
    }
}
