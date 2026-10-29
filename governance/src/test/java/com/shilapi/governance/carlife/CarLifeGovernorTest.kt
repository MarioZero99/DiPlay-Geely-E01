package com.shilapi.governance.carlife

import android.content.ComponentName
import android.content.pm.PackageManager
import com.shilapi.governance.platform.E01PrivilegedBridge
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * What CarLife governance sends to the head unit, and what it reports afterwards.
 *
 * Package state runs through Robolectric's real `PackageManager` (its shadow implements
 * `set/getComponentEnabledSetting`), so `isSuppressed()` is exercised against the API the car uses.
 * Only the shell is faked -- that is the one thing a desk cannot do.
 *
 * The three component names are literals on purpose: they come from CarLife's own
 * `AndroidManifest.xml`, not from [CarLifeGovernor]'s constants. Reading the constants back would let
 * one typo pass on both sides of the assertion.
 */
@RunWith(RobolectricTestRunner::class)
class CarLifeGovernorTest {

    private val broadcast = "com.baidu.carlifevehicle.broadcast.BroadcastActionReceiver"
    private val aoa = "com.baidu.carlifevehicle.connect.AOAAccessoryReceiver"
    private val connect = "com.baidu.carlifevehicle.connect.ConnectService"
    private val all = listOf(broadcast, aoa, connect)

    private val context = RuntimeEnvironment.getApplication()
    private val sent = mutableListOf<String>()

    /** Puts the package on the shadow package manager with [present] declared as receivers or services. */
    private fun installCarLife(present: List<String> = all) {
        shadowOf(context.packageManager).installPackage(
            android.content.pm.PackageInfo().apply { packageName = CarLifeGovernor.PACKAGE_NAME },
        )
        for (className in present) {
            if (className == connect) {
                shadowOf(context.packageManager).addOrUpdateService(
                    android.content.pm.ServiceInfo().apply {
                        packageName = CarLifeGovernor.PACKAGE_NAME
                        name = className
                    },
                )
            } else {
                shadowOf(context.packageManager).addOrUpdateReceiver(
                    android.content.pm.ActivityInfo().apply {
                        packageName = CarLifeGovernor.PACKAGE_NAME
                        name = className
                    },
                )
            }
        }
    }

    /** A shell that writes the package state the way `pm disable` would. */
    private fun governor(refuse: String? = null) = CarLifeGovernor(
        context = context,
        runCommand = { command ->
            sent += command
            val className = command.substringAfter('/', missingDelimiterValue = "")
            if (className.isNotEmpty()) {
                if (className == refuse) return@CarLifeGovernor E01PrivilegedBridge.Result.CommandFailed(1, "does not exist")
                val state = if (command.startsWith("pm enable ")) {
                    PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                }
                // The real API `pm` writes through, which the shadow implements.
                context.packageManager.setComponentEnabledSetting(
                    ComponentName(CarLifeGovernor.PACKAGE_NAME, className),
                    state,
                    PackageManager.DONT_KILL_APP,
                )
            }
            E01PrivilegedBridge.Result.Success("")
        },
    )

    @Before
    fun setUp() {
        sent.clear()
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
        installCarLife()

        assertTrue(governor().applySuppression())

        // Not one joined line: the bridge carries 220 bytes per command. Stop first, so the port is
        // released before anything is disallowed.
        assertEquals(
            listOf(
                "am force-stop com.baidu.carlifevehicle",
                "pm disable com.baidu.carlifevehicle/com.baidu.carlifevehicle.broadcast.BroadcastActionReceiver",
                "pm disable com.baidu.carlifevehicle/com.baidu.carlifevehicle.connect.AOAAccessoryReceiver",
                "pm disable com.baidu.carlifevehicle/com.baidu.carlifevehicle.connect.ConnectService",
            ),
            sent,
        )
    }

    @Test
    fun oneRefusalDoesNotLeaveTheRestEnabled() = runBlocking {
        installCarLife()

        val off = governor(refuse = aoa).applySuppression()

        assertTrue("the component after the refusal was still attempted", sent.last().contains(connect))
        assertFalse("one component is still on, so this is not off", off)
    }

    @Test
    fun aComponentTheBuildDoesNotCarryIsSkipped() = runBlocking {
        installCarLife(present = listOf(broadcast, aoa))

        assertTrue(governor().applySuppression())
        assertFalse("nothing was sent at a class this build does not have", sent.any { it.contains(connect) })
    }

    @Test
    fun aHeadUnitWithoutCarLifeIsNotAnErrorAndGetsNoCommands() = runBlocking {
        val governor = governor()

        assertFalse(governor.isInstalled())
        assertTrue(governor.applySuppression())
        assertTrue(governor.applyRestoration())
        assertEquals(emptyList<String>(), sent)
    }

    @Test
    fun restoringTurnsThemBackOnAndDoesNotStartCarLife() = runBlocking {
        installCarLife()
        val governor = governor()
        assertTrue(governor.applySuppression())
        assertTrue(governor.isSuppressed())
        sent.clear()

        assertTrue(governor.applyRestoration())
        assertFalse(governor.isSuppressed())
        assertFalse("restoring is not a reason to start CarLife", sent.contains(CarLifeGovernor.FORCE_STOP_COMMAND))
    }

    @Test
    fun askingTwiceAsksTheCarTwiceRatherThanDecidingItIsGone() = runBlocking {
        installCarLife()
        val governor = governor()

        assertTrue(governor.applySuppression())
        val afterFirst = sent.size

        // The bug this guards: once off, the components read as absent, so the governor concluded
        // CarLife was not installed and stopped talking to the car at all.
        assertTrue(governor.applySuppression())
        assertTrue("still installed", governor.isInstalled())
        assertTrue("still talks to the car", sent.size > afterFirst)
    }

    /**
     * The guard for the one bug that inverts this feature.
     *
     * On API 22 `getReceiverInfo` returns null for a disabled component unless
     * `GET_DISABLED_COMPONENTS` is passed, and the app wrapper turns that into
     * `NameNotFoundException`. Without the flag, a successful disable reads back as "no such
     * component": the card stays on "still takes the port" for ever.
     *
     * Robolectric's package manager ignores the flags argument (verified by disassembling
     * `ShadowApplicationPackageManager.getComponentInfo`, which never reads it), so this has to drive
     * the lookup itself. The fake below answers the way the real service does -- it reads the flags,
     * so a call site that forgets them fails here rather than agreeing with itself.
     */
    @Test
    fun everyPresenceCheckAsksForDisabledComponents() {
        installCarLife()
        // The real enabled-setting half: all three are off, written through the API `pm` uses.
        for (className in all) {
            context.packageManager.setComponentEnabledSetting(
                ComponentName(CarLifeGovernor.PACKAGE_NAME, className),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }

        val asked = mutableListOf<Int>()
        val governor = CarLifeGovernor(
            context = context,
            declared = { name, flags, _ ->
                asked += flags
                name.className in all &&
                    (flags and PackageManager.GET_DISABLED_COMPONENTS != 0)
            },
        )

        assertTrue("off must read as off, which needs the components to still exist", governor.isSuppressed())
        assertTrue("the lookup was never called, so nothing was proved", asked.isNotEmpty())
        assertTrue(
            "every presence check must ask for disabled components, or 'off' reads as 'gone'",
            asked.all { it and PackageManager.GET_DISABLED_COMPONENTS != 0 },
        )
    }

    @Test
    fun aDisabledComponentStillReadsAsOff() = runBlocking {
        // The other half: the enabled setting itself comes from the real PackageManager, so this
        // exercises the API the car uses rather than a fake of it.
        installCarLife()
        val governor = governor()

        assertTrue(governor.applySuppression())
        assertTrue("off is not gone", governor.isSuppressed())
    }

    @Test
    fun preciseComponentLookupAvoidsMisdirectedServiceQueries() {
        installCarLife()
        val queryLog = mutableListOf<Pair<String, Boolean>>() // Pair<className, isService>
        val governor = CarLifeGovernor(
            context = context,
            declared = { name, flags, isService ->
                queryLog += (name.className to isService)
                true
            },
        )
        governor.isSuppressed()

        val serviceQueries = queryLog.filter { it.second }
        val receiverQueries = queryLog.filter { !it.second }

        assertEquals(1, serviceQueries.size)
        assertEquals("com.baidu.carlifevehicle.connect.ConnectService", serviceQueries.first().first)
        assertEquals(2, receiverQueries.size)
    }

    @Test
    fun buildWithNoVulnerableComponentsReportsSuppressedRatherThanDeadlocking() {
        // CarLife is installed, but carries none of the 3 vulnerable components (e.g., custom stripped build)
        installCarLife(present = emptyList())
        val governor = CarLifeGovernor(
            context = context,
            declared = { _, _, _ -> false },
        )
        assertTrue("CarLife with no vulnerable entrypoints is inherently safe", governor.isSuppressed())
    }

    @Test
    fun processRunningDetectionIdentifiesActiveCarLifeProcess() {
        var running = false
        val governor = CarLifeGovernor(
            context = context,
            processRunningProbe = { running },
        )
        assertFalse(governor.isProcessRunning())
        running = true
        assertTrue(governor.isProcessRunning())
    }
}
