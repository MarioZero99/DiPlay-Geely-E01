package com.shilapi.xcertplay.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Whose updates this app's are, which decides whether the update link offers at all.
 *
 * The car's copy is installed by hand -- pushed over ADB or copied from a USB stick -- and nothing
 * records having done it. That is the only case the release is built for, and the only case in which
 * this link may offer: a package put over an app that a store owns is not the store's copy, and the
 * store's next update would put its own build back, possibly older than the one just installed.
 *
 * The manifest and the installer-side of this are in [UpdateInstallerFileProviderTest]; what is here
 * is the question and its answer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class UpdateInstallerTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test fun aHandInstallIsThisLinkSToOffer() {
        // Nothing recorded the install. There is nothing that owns this app's updates, so this
        // link does.
        assertNull(UpdateInstaller.installingPackageOnBehalfOf(context, packageName = context.packageName))
        assertTrue(!UpdateInstaller.shouldStandAside(context, packageName = context.packageName))
    }

    @Test fun anAppInstalledByAStoreLeavesUpdatesToIt() {
        // Registered as installed first, the way a device's package list has it: the stand-in
        // package manager refuses to answer about a package it does not know.
        shadowOf(context.packageManager).addPackage(context.packageName)
        context.packageManager.setInstallerPackageName(context.packageName, "com.android.vending")

        assertEquals("com.android.vending", UpdateInstaller.installingPackageOnBehalfOf(context, packageName = context.packageName))
        assertTrue(UpdateInstaller.shouldStandAside(context, packageName = context.packageName))
    }

    @Test fun anAppTheSystemPreinstalledLeavesUpdatesToIt() {
        shadowOf(context.packageManager).addPackage(context.packageName)
        context.packageManager.setInstallerPackageName(context.packageName, "com.android.settings")

        assertTrue(UpdateInstaller.shouldStandAside(context, packageName = context.packageName))
    }

    @Test fun aMissingPackageIsNotAnAnswerAboutWhoOwnsIt() {
        // No such package: the question cannot be answered, so the link offers and the system has
        // the last word. Standing aside where the package manager could not answer would hide the
        // button from a car that has nothing wrong with it.
        assertNull(UpdateInstaller.installingPackageOnBehalfOf(context, packageName = "not.installed.here"))
        assertTrue(!UpdateInstaller.shouldStandAside(context, packageName = "not.installed.here"))
    }

    @Test fun theSystemInstallerStillOffersUpdates() {
        shadowOf(context.packageManager).addPackage(context.packageName)
        context.packageManager.setInstallerPackageName(context.packageName, "com.android.packageinstaller")

        assertTrue(!UpdateInstaller.shouldStandAside(context, packageName = context.packageName))
    }

    @Test fun anAdbInstallStillOffersUpdates() {
        shadowOf(context.packageManager).addPackage(context.packageName)
        context.packageManager.setInstallerPackageName(context.packageName, "com.android.shell")

        assertTrue(!UpdateInstaller.shouldStandAside(context, packageName = context.packageName))
    }

    @Test fun anUpdateThisAppInstalledStillOffersTheNextOne() {
        shadowOf(context.packageManager).addPackage(context.packageName)
        context.packageManager.setInstallerPackageName(context.packageName, context.packageName)

        assertTrue(!UpdateInstaller.shouldStandAside(context, packageName = context.packageName))
    }

    @Test fun theSystemInstallerIsNotAnotherOwner() {
        // An app installed through the system's own installer UI is not store-owned: nothing keeps
        // a record that would be broken by replacing it.
        assertTrue(UpdateInstaller.isKnownInstallPath(null))
        assertTrue(UpdateInstaller.isKnownInstallPath("com.android.packageinstaller"))
        assertTrue(UpdateInstaller.isKnownInstallPath("com.google.android.packageinstaller"))
        assertTrue(!UpdateInstaller.isKnownInstallPath("com.android.vending"))
    }
}
