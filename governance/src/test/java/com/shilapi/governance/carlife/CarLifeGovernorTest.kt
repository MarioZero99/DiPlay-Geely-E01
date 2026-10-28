package com.shilapi.governance.carlife

import android.content.ComponentName
import android.content.Context
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
 * What this class sends to the head unit, and what it reports afterwards.
 *
 * The component names below are literals on purpose. They come from the `AndroidManifest.xml` inside
 * CarLife itself (`package="com.baidu.carlifevehicle"`, versionName 3.0.0); reading
 * [CarLifeGovernor]'s own constants back would let a typo pass on both sides of the assertion, and
 * the card would quietly do nothing on the car.
 */
@RunWith(RobolectricTestRunner::class)
class CarLifeGovernorTest {

    private val broadcast = "com.baidu.carlifevehicle.broadcast.BroadcastActionReceiver"
    private val aoa = "com.baidu.carlifevehicle.connect.AOAAccessoryReceiver"
    private val connect = "com.baidu.carlifevehicle.connect.ConnectService"

    private val allPresent = listOf(broadcast, aoa, connect)

    /** Records the commands and answers the way the vendor service does. */
    private inner class FakeShell(
        private val answers: (String) -> E01PrivilegedBridge.Result = { E01PrivilegedBridge.Result.Success("") },
    ) : PrivilegedShell {
        val sent = mutableListOf<String>()
        override fun run(command: String): E01PrivilegedBridge.Result {
            sent += command
            return answers(command)
        }
    }

    /**
     * CarLife's package state, driven the way the shell would drive it.
     *
     * A successful `pm disable X` sets X disabled; a `pm enable X` puts it back. A component not in
     * [present] does not exist, which is what the shell answers with an error and what the governor
     * must not count as a failure.
     */
    private inner class FakePackage(
        private val present: List<String> = allPresent,
    ) : CarLifePackage {
        val disabled = mutableSetOf<String>()
        var installed = true

        override fun isInstalled() = installed

        override fun componentStates(): List<CarLifeComponentState> =
            CarLifeGovernor.COMPONENTS_TO_SUPPRESS.map { className ->
                CarLifeComponentState(
                    exists = className in present,
                    disabled = className in disabled,
                )
            }
    }

    @Test
    fun eachCommandFitsTheBridgeByteBudget() {
        for (className in allPresent) {
            for (command in listOf(CarLifeGovernor.disableCommand(className), CarLifeGovernor.enableCommand(className))) {
                val bytes = E01PrivilegedBridge.wrap(command).toByteArray(Charsets.UTF_8).size
                assertTrue("$command wraps to $bytes bytes, over the 220-byte budget", bytes <= E01PrivilegedBridge.MAX_COMMAND_BYTES)
            }
        }
        val stopBytes = E01PrivilegedBridge.wrap(CarLifeGovernor.FORCE_STOP_COMMAND).toByteArray(Charsets.UTF_8).size
        assertTrue("force-stop wraps to $stopBytes bytes", stopBytes <= E01PrivilegedBridge.MAX_COMMAND_BYTES)
    }

    @Test
    fun eachCommandNamesItsComponentTheWayPmWantsIt() {
        assertEquals(
            "pm disable com.baidu.carlifevehicle/com.baidu.carlifevehicle.broadcast.BroadcastActionReceiver",
            CarLifeGovernor.disableCommand(broadcast),
        )
        assertEquals(
            "pm enable com.baidu.carlifevehicle/com.baidu.carlifevehicle.connect.ConnectService",
            CarLifeGovernor.enableCommand(connect),
        )
        assertEquals("am force-stop com.baidu.carlifevehicle", CarLifeGovernor.FORCE_STOP_COMMAND)
    }

    @Test
    fun suppressionCountsOnlyWhenEveryComponentTheBuildCarriesIsOff() {
        val off = CarLifeComponentState(exists = true, disabled = true)
        val on = CarLifeComponentState(exists = true, disabled = false)
        val absent = CarLifeComponentState(exists = false, disabled = false)

        assertTrue(CarLifeGovernor.suppressionVerdict(listOf(off, off, off)))
        // A build without ConnectService: the two receivers it does have are off.
        assertTrue(CarLifeGovernor.suppressionVerdict(listOf(off, off, absent)))
        // One still on is not suppressed, whichever one it is.
        assertFalse(CarLifeGovernor.suppressionVerdict(listOf(off, on, off)))
        assertFalse(CarLifeGovernor.suppressionVerdict(listOf(on, on, on)))
        assertFalse("nothing present is not everything off", CarLifeGovernor.suppressionVerdict(listOf(absent, absent, absent)))
        assertFalse(CarLifeGovernor.suppressionVerdict(emptyList()))
    }

    @Test
    fun aHeadUnitWithoutCarLifeSendsNothingAndIsNotAFailure() = runBlocking {
        val shell = FakeShell()
        val governor = CarLifeGovernor(FakePackage(present = emptyList()).apply { installed = false }, shell)

        assertFalse(governor.isInstalled())
        assertTrue(governor.applySuppression())
        assertTrue(governor.applyRestoration())
        assertEquals(emptyList<String>(), shell.sent)
    }

    @Test
    fun oneCommandPerComponentAndTheForceStopComesFirst() = runBlocking {
        val packageState = FakePackage()
        val shell = FakeShell { command ->
            val className = command.substringAfter('/', missingDelimiterValue = "")
            if (className.isNotEmpty()) packageState.disabled += className
            E01PrivilegedBridge.Result.Success("")
        }

        val governor = CarLifeGovernor(packageState, shell)
        assertTrue(governor.applySuppression())

        // The 220-byte budget is per command: joining these with `;` is one line the bridge refuses,
        // so the shape of this list is the guarantee, not a style choice. The stop comes first so the
        // port is released before anything is disallowed, not as a side effect of the last command.
        assertEquals(
            listOf("am force-stop com.baidu.carlifevehicle") + listOf(
                "pm disable com.baidu.carlifevehicle/com.baidu.carlifevehicle.broadcast.BroadcastActionReceiver",
                "pm disable com.baidu.carlifevehicle/com.baidu.carlifevehicle.connect.AOAAccessoryReceiver",
                "pm disable com.baidu.carlifevehicle/com.baidu.carlifevehicle.connect.ConnectService",
            ),
            shell.sent,
        )
    }

    /**
     * The shape that shipped broken, reproduced through the real lookup.
     *
     * On API 22 a disabled component is invisible to `getReceiverInfo`/`getServiceInfo` unless the
     * caller passes `GET_DISABLED_COMPONENTS`; the app-side wrapper turns that into a thrown
     * `NameNotFoundException`. So asking "does the package declare this class" *after* turning it off
     * answered "no", a successful disable read back as "not suppressed", and a press after that
     * concluded CarLife was not installed at all.
     *
     * The fake below answers the way the real service does -- and it reads the caller's flags, so a
     * lookup that forgets them fails this test rather than agreeing with itself.
     */
    private fun api22Lookup(declared: List<String>, disabled: Set<String>): AndroidComponentLookup {
        fun visible(name: ComponentName, flags: Int): Boolean =
            name.className in declared &&
                (name.className !in disabled || (flags and android.content.pm.PackageManager.GET_DISABLED_COMPONENTS) != 0)
        return AndroidComponentLookup(
            receiverDeclared = { name, flags -> visible(name, flags) && !name.className.endsWith("ConnectService") },
            serviceDeclared = { name, flags -> visible(name, flags) && name.className.endsWith("ConnectService") },
        )
    }

    @Test
    fun aDisabledComponentStillExistsWhenTheLookupAsksForDisabledComponents() {
        val lookup = api22Lookup(allPresent, disabled = allPresent.toSet())

        for (className in allPresent) {
            assertTrue(
                "$className is off, not gone; reading it as absent is what made the card lie",
                lookup.exists(className),
            )
        }
    }

    @Test
    fun aClassThePackageDoesNotDeclareIsAbsent() {
        val lookup = api22Lookup(allPresent, disabled = emptySet())
        assertFalse(lookup.exists("com.baidu.carlifevehicle.broadcast.NotAComponent"))
    }

    @Test
    fun aRefusedComponentDoesNotStopTheOthers() = runBlocking {
        val packageState = FakePackage()
        val shell = FakeShell { command ->
            if (command.contains(aoa)) {
                // What `pm disable` answers for a class the package does not have.
                E01PrivilegedBridge.Result.CommandFailed(1, "Component class does not exist")
            } else {
                packageState.disabled += command.substringAfter('/')
                E01PrivilegedBridge.Result.Success("")
            }
        }

        CarLifeGovernor(packageState, shell).applySuppression()

        // Broadcast is first, Connect is last: both were still attempted after the refusal.
        assertTrue(shell.sent.any { it.contains(broadcast) })
        assertTrue(shell.sent.any { it.contains(connect) })
    }

    @Test
    fun reportsNotSuppressedWhenAComponentStaysOn() = runBlocking {
        val packageState = FakePackage()
        val shell = FakeShell { command ->
            if (command.contains(connect)) {
                E01PrivilegedBridge.Result.CommandFailed(1, "unknown")
            } else {
                packageState.disabled += command.substringAfter('/')
                E01PrivilegedBridge.Result.Success("")
            }
        }

        val governor = CarLifeGovernor(packageState, shell)

        // Both receivers went off, the service did not. Saying "suppressed" here is the lie the card
        // would show while CarLife still holds the port.
        assertFalse(governor.applySuppression())
        assertFalse(governor.isSuppressed())
    }

    @Test
    fun aComponentTheBuildDoesNotCarryIsSkippedAndSentNothing() = runBlocking {
        val packageState = FakePackage(present = listOf(broadcast, aoa))
        val shell = FakeShell { command ->
            packageState.disabled += command.substringAfter('/', missingDelimiterValue = "")
            E01PrivilegedBridge.Result.Success("")
        }

        val governor = CarLifeGovernor(packageState, shell)

        assertTrue("the two components it does have are off", governor.applySuppression())
        assertFalse(
            "sending pm disable at a class the build does not have only produces an error",
            shell.sent.any { it.contains(connect) },
        )
    }

    @Test
    fun pressingDisableASecondTimeDoesNotClaimCarLifeIsNotInstalled() = runBlocking {
        // The second half of the bug: once the components were off they read as absent, so the
        // "nothing present means not installed" branch fired and reported a head unit that has
        // CarLife as one that does not.
        val packageState = FakePackage()
        val shell = FakeShell { command ->
            val className = command.substringAfter('/', missingDelimiterValue = "")
            if (command.startsWith("pm disable ") && className.isNotEmpty()) packageState.disabled += className
            if (command.startsWith("pm enable ") && className.isNotEmpty()) packageState.disabled -= className
            E01PrivilegedBridge.Result.Success("")
        }

        val governor = CarLifeGovernor(packageState, shell)

        assertTrue(governor.applySuppression())
        assertTrue("CarLife is still installed", governor.isInstalled())

        val sentFirstRun = shell.sent.size
        assertTrue("a second press still reports suppressed", governor.applySuppression())
        assertTrue("and is still installed", governor.isInstalled())
        assertTrue(
            "a second press asks the car again rather than short-circuiting to 'not installed'",
            shell.sent.size > sentFirstRun,
        )
    }

    @Test
    fun restorationTurnsTheComponentsBackOnAndDoesNotStartCarLife() = runBlocking {
        val packageState = FakePackage().apply { disabled += allPresent }
        val shell = FakeShell { command ->
            if (command.startsWith("pm enable ")) packageState.disabled -= command.substringAfter('/', missingDelimiterValue = "")
            E01PrivilegedBridge.Result.Success("")
        }

        val governor = CarLifeGovernor(packageState, shell)
        assertTrue(governor.isSuppressed())

        assertTrue(governor.applyRestoration())
        assertFalse(governor.isSuppressed())
        assertFalse("restoring is not a reason to start CarLife again", shell.sent.contains(CarLifeGovernor.FORCE_STOP_COMMAND))
    }

    @Test
    fun anEmptyPackageIsNeitherInstalledNorSuppressed() {
        // Guards the split between "the package is gone" and "the package is present but nothing is
        // off": the governor must ask the package, not infer it from an empty send list.
        val governor = CarLifeGovernor(FakePackage(present = emptyList()).apply { installed = false }, FakeShell())
        assertFalse(governor.isInstalled())
        assertFalse(governor.isSuppressed())
    }

    @Test
    fun theRealPackageReaderAnswersOnADeviceWithoutCarLife() {
        // Robolectric's package manager has no CarLife, which is also a non-CarLife head unit. Every
        // name is asked about and answered; a rename inside CarLife would read as "not suppressed"
        // rather than throwing on the settings page.
        val context: Context = RuntimeEnvironment.getApplication()
        val governor = CarLifeGovernor(context)
        assertFalse(governor.isInstalled())
        assertFalse(governor.isSuppressed())
    }

    @Test
    fun aPartlyDisabledPackageIsNotReportedAsSuppressed() = runBlocking {
        // The shape the first version of this class had: it read the first component back and called
        // that the answer. Two receivers off and the service running read as "suppressed" while
        // CarLife still held the port.
        val packageState = FakePackage()
        packageState.disabled += broadcast
        packageState.disabled += aoa

        val shell = FakeShell()
        val governor = CarLifeGovernor(packageState, shell)

        assertFalse(governor.isSuppressed())
        // Nothing was asked of the car for a read, and a read never writes.
        assertEquals(emptyList<String>(), shell.sent)
    }
}
