package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class UsbAutoConfirmServiceTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun isEnabledReturnsFalseByDefault() {
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            null,
        )
        assertFalse(UsbAutoConfirmService.isEnabled(context))
    }

    @Test
    fun isEnabledReturnsTrueWhenConfiguredInSecureSettings() {
        val component = ComponentName(context, UsbAutoConfirmService::class.java).flattenToString()
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            "other.package/other.Service:$component",
        )
        assertTrue(UsbAutoConfirmService.isEnabled(context))
    }

    @Test
    fun isEnabledAcceptsTheShortenedComponentSpelling() {
        val short = ComponentName(context, UsbAutoConfirmService::class.java).flattenToShortString()
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            short,
        )
        assertTrue(UsbAutoConfirmService.isEnabled(context))
    }

    @Test
    fun isEnabledIgnoresAnotherServiceOfThisApp() {
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            "${context.packageName}/.SomethingElse",
        )
        assertFalse(UsbAutoConfirmService.isEnabled(context))
    }

    @Test
    fun onlySystemUsbActivitiesAreAccepted() {
        assertTrue(
            UsbAutoConfirmService.isSystemUsbWindow(
                "com.android.systemui",
                "com.android.systemui.usb.UsbPermissionActivity",
            ),
        )
        assertFalse(
            UsbAutoConfirmService.isSystemUsbWindow(
                "evil.app",
                "com.android.systemui.usb.UsbPermissionActivity",
            ),
        )
        assertFalse(
            UsbAutoConfirmService.isSystemUsbWindow("com.android.systemui", "android.app.AlertDialog"),
        )
        assertFalse(
            UsbAutoConfirmService.isSystemUsbWindow(
                "com.android.systemui",
                "com.android.systemui.media.MediaProjectionPermissionActivity",
            ),
        )
    }

    @Test
    fun promptMustNameDiPlayAndUsbExplicitly() {
        assertTrue(UsbAutoConfirmService.isTargetPrompt("Allow DiPlay to access this USB device?", "DiPlay"))
        assertTrue(UsbAutoConfirmService.isTargetPrompt("允许 DiPlay 访问 USB 设备？", "DiPlay"))
        assertFalse(UsbAutoConfirmService.isTargetPrompt("Allow CarPlay access to iPhone?", "DiPlay"))
        assertFalse(UsbAutoConfirmService.isTargetPrompt("Allow DiPlay to access your contacts?", "DiPlay"))
        assertFalse(UsbAutoConfirmService.isTargetPrompt("Allow FakeDiPlay USB access?", "DiPlay"))
        assertFalse(UsbAutoConfirmService.isTargetPrompt("Allow USB access?", ""))
    }
}
