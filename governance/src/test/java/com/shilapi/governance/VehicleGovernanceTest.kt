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
