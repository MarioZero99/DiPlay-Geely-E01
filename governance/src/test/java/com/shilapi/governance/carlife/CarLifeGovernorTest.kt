package com.shilapi.governance.carlife

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.shilapi.governance.platform.E01PrivilegedBridge
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * What CarLife governance sends to the head unit, and what it reports afterwards.
 *
 * The three component names are literals on purpose: they come from CarLife's own
 * `AndroidManifest.xml` (`com.baidu.carlifevehicle`), not from [CarLifeGovernor]'s constants. Reading
 * the constants back would let one typo pass on both sides of the assertion.
 */
@RunWith(RobolectricTestRunner::class)
class CarLifeGovernorTest {

    private val broadcast = "com.baidu.carlifevehicle.broadcast.BroadcastActionReceiver"
    private val aoa = "com.baidu.carlifevehicle.connect.AOAAccessoryReceiver"
    private val connect = "com.baidu.carlifevehicle.connect.ConnectService"
    private val all = listOf(broadcast, aoa, connect)

    /** A device whose package state and shell answer the way the real ones do. */
    private inner class FakeCarLife(
        private val present: List<String> = all,
        private val refuse: String? = null,
    ) : CarLifeSystem {
        val off = mutableSetOf<String>()
        val sent = mutableListOf<String>()
        var alive = true

        override fun installed() = present.isNotEmpty()

        override fun components() = CarLifeGovernor.COMPONENTS.map { className ->
            CarLifeComponentState(exists = className in present, disabled = className in off)
        }

        override fun run(command: String): E01PrivilegedBridge.Result {
            sent += command
            if (command == CarLifeGovernor.FORCE_STOP_COMMAND) {
                alive = false
                return E01PrivilegedBridge.Result.Success("")
            }
            val className = command.substringAfter('/', missingDelimiterValue = "")
            if (className == refuse) return E01PrivilegedBridge.Result.CommandFailed(1, "does not exist")
            if (command.startsWith("pm disable ")) off += className
            if (command.startsWith("pm enable ")) off -= className
            return E01PrivilegedBridge.Result.Success("")
        }
    }

    @Test
    fun everyCommandFitsTheBridgeByteBudget() {
        val commands = all.flatMap { listOf(CarLifeGovernor.disableCommand(it), CarLifeGovernor.enableCommand(it)) } +
            CarLifeGovernor.FORCE_STOP_COMMAND
        for (command in commands) {
            val bytes = E01PrivilegedBridge.wrap(command).toByteArray(Charsets.UTF_8).size
            assertTrue("$command wraps to $bytes bytes, over the 220-byte budget", bytes <= E01PrivilegedBridge.MAX_COMMAND_BYTES)
        }
    }

    @Test
    fun stopsCarLifeFirstThenDisablesEachComponentSeparately() = runBlocking {
        val device = FakeCarLife()

        assertTrue(CarLifeGovernor(device).applySuppression())

        // Not one joined line: the bridge carries 220 bytes per command. And the stop comes first, so
        // the port is released before anything is disallowed.
        assertEquals(
            listOf(
                "am force-stop com.baidu.carlifevehicle",
                "pm disable com.baidu.carlifevehicle/com.baidu.carlifevehicle.broadcast.BroadcastActionReceiver",
                "pm disable com.baidu.carlifevehicle/com.baidu.carlifevehicle.connect.AOAAccessoryReceiver",
                "pm disable com.baidu.carlifevehicle/com.baidu.carlifevehicle.connect.ConnectService",
            ),
            device.sent,
        )
    }

    @Test
    fun oneRefusalDoesNotLeaveTheRestEnabled() = runBlocking {
        val device = FakeCarLife(refuse = aoa)

        val off = CarLifeGovernor(device).applySuppression()

        assertTrue("the component after the refusal was still attempted", device.sent.last().contains(connect))
        assertFalse("one component is still on, so this is not off", off)
    }

    @Test
    fun aComponentTheBuildDoesNotCarryIsSkipped() = runBlocking {
        val device = FakeCarLife(present = listOf(broadcast, aoa))

        val off = CarLifeGovernor(device).applySuppression()

        assertTrue(off)
        assertFalse("nothing was sent at a class this build does not have", device.sent.any { it.contains(connect) })
    }

    @Test
    fun aHeadUnitWithoutCarLifeIsNotAnErrorAndGetsNoCommands() = runBlocking {
        val device = FakeCarLife(present = emptyList())
        val governor = CarLifeGovernor(device)

        assertFalse(governor.isInstalled())
        assertTrue(governor.applySuppression())
        assertTrue(governor.applyRestoration())
        assertEquals(emptyList<String>(), device.sent)
    }

    @Test
    fun restoringTurnsThemBackOnAndDoesNotStartCarLife() = runBlocking {
        val device = FakeCarLife().apply { off += all }
        val governor = CarLifeGovernor(device)

        assertTrue(governor.isSuppressed())
        assertTrue(governor.applyRestoration())
        assertFalse(governor.isSuppressed())
        assertFalse("restoring is not a reason to start CarLife", device.sent.contains(CarLifeGovernor.FORCE_STOP_COMMAND))
    }

    @Test
    fun aDisabledComponentStillExistsSoTheCardCanSaySo() {
        // The bug this guards: on API 22 a disabled component is invisible unless
        // GET_DISABLED_COMPONENTS is passed, so a successful disable read back as "no such component"
        // and the card stayed on "still takes the port" for ever.
        val disabled = all.toSet()
        val lookup = AndroidCarLifeSystem(
            RuntimeEnvironment.getApplication(),
            declared = { name, flags ->
                name.className in all &&
                    (name.className !in disabled || flags and PackageManager.GET_DISABLED_COMPONENTS != 0)
            },
        )

        assertTrue(
            "off is not gone",
            lookup.components().all { it.exists },
        )
        // And with the flag dropped the same lookup would call them absent -- which is what the
        // production call site must never do.
        assertFalse(CarLifeGovernor.isOff(emptyList()))
    }

    @Test
    fun aPackageWithoutCarLifeReportsNothingInstalled() {
        val system: CarLifeSystem = AndroidCarLifeSystem(RuntimeEnvironment.getApplication() as Context)
        assertFalse(system.installed())
    }

    @Test
    fun oneComponentStillOnIsNotOff() {
        fun state(exists: Boolean, disabled: Boolean) = CarLifeComponentState(exists, disabled)
        assertTrue(CarLifeGovernor.isOff(listOf(state(true, true), state(true, true), state(true, true))))
        assertTrue("a build with only two of the names", CarLifeGovernor.isOff(listOf(state(true, true), state(true, true), state(false, false))))
        assertFalse(CarLifeGovernor.isOff(listOf(state(true, true), state(true, false), state(true, true))))
        assertFalse("nothing present is not everything off", CarLifeGovernor.isOff(listOf(state(false, false))))
    }

    @Test
    fun askingTwiceAsksTheCarTwiceRatherThanDecidingItIsGone() = runBlocking {
        val device = FakeCarLife()
        val governor = CarLifeGovernor(device)

        assertTrue(governor.applySuppression())
        val afterFirst = device.sent.size

        // The second half of the same bug: once off, the components read as absent and the governor
        // concluded CarLife was not installed, so it stopped talking to the car at all.
        assertTrue(governor.applySuppression())
        assertTrue("still installed", governor.isInstalled())
        assertTrue("still talks to the car", device.sent.size > afterFirst)
    }
}
