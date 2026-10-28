package com.shilapi.xcertplay.instrument

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BTAudioGuardTest {

    private val context get() = RuntimeEnvironment.getApplication()
    private val fakeSettings = mutableMapOf<String, String>()
    private val executedCommands = mutableListOf<String>()

    private val fakeAccessor = object : BTAudioGuard.SystemSettingsAccessor {
        var putStringResult = true

        override fun getString(context: Context, key: String): String? {
            return fakeSettings[key]
        }

        override fun putString(context: Context, key: String, value: String): Boolean {
            return if (putStringResult) {
                fakeSettings[key] = value
                true
            } else {
                false
            }
        }

        override fun executeShell(command: String): Boolean {
            executedCommands.add(command)
            return true
        }
    }

    @Before
    fun setUp() {
        fakeSettings.clear()
        executedCommands.clear()
        BTAudioGuard.accessorOverrideForTest = fakeAccessor
    }

    @After
    fun tearDown() {
        BTAudioGuard.accessorOverrideForTest = null
    }

    @Test
    fun alreadyConnected_doesNotTriggerWrite() {
        fakeSettings[BTAudioGuard.KEY_BT_AUDIO] = BTAudioGuard.STATE_CONNECTED

        val result = BTAudioGuard.ensureConnected(context)

        assertTrue(result)
        assertEquals(BTAudioGuard.STATE_CONNECTED, fakeSettings[BTAudioGuard.KEY_BT_AUDIO])
        assertTrue("No shell command should be executed when already connected", executedCommands.isEmpty())
    }

    @Test
    fun disconnected_triggersWriteToConnected() {
        fakeSettings[BTAudioGuard.KEY_BT_AUDIO] = BTAudioGuard.STATE_DISCONNECTED

        val result = BTAudioGuard.ensureConnected(context)

        assertTrue(result)
        assertEquals(BTAudioGuard.STATE_CONNECTED, fakeSettings[BTAudioGuard.KEY_BT_AUDIO])
        assertTrue(executedCommands.isEmpty())
    }

    @Test
    fun nullState_triggersWriteToConnected() {
        fakeSettings.remove(BTAudioGuard.KEY_BT_AUDIO)

        val result = BTAudioGuard.ensureConnected(context)

        assertTrue(result)
        assertEquals(BTAudioGuard.STATE_CONNECTED, fakeSettings[BTAudioGuard.KEY_BT_AUDIO])
        assertTrue(executedCommands.isEmpty())
    }

    @Test
    fun putStringFails_fallsBackToShell() {
        fakeSettings[BTAudioGuard.KEY_BT_AUDIO] = BTAudioGuard.STATE_DISCONNECTED
        fakeAccessor.putStringResult = false

        val result = BTAudioGuard.ensureConnected(context)

        assertTrue(result)
        assertEquals(1, executedCommands.size)
        assertEquals("settings put system BTAudio Connected", executedCommands[0])
    }
}
