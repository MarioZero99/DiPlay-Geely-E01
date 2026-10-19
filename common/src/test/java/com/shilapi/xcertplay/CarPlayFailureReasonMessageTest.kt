package com.shilapi.xcertplay

import com.shilapi.xcertplay.orchestration.CarPlayFailureReason
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh-rCN")
class CarPlayFailureReasonMessageTest {

    @Test
    fun existingWifiFailureReasonsRenderAccurateGuidance() {
        val controller = Robolectric.buildActivity(CarPlayHostActivity::class.java).create()
        val activity = controller.get()

        val missingText = activity.failureText(CarPlayFailureReason.EXISTING_WIFI_CREDENTIALS_MISSING, null)
        assertTrue("missing credentials text should mention password", missingText.contains("密码"))

        val notOnNetworkText = activity.failureText(CarPlayFailureReason.EXISTING_WIFI_PHONE_NOT_ON_NETWORK, null)
        assertTrue("not on network text should mention password and iPhone", notOnNetworkText.contains("密码") && notOnNetworkText.contains("iPhone"))
    }
}
