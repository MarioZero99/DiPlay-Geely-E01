package com.shilapi.xcertplay

import com.shilapi.xcertplay.host.BuildConfig

/**
 * Whether this package carries the features that are not ready to be handed to a driver.
 *
 * The answer is fixed when the package is built, by `-Pexperimental=on|off` (see common/build.gradle.kts),
 * and it decides two things that are really one: what the settings page draws, and whether the package
 * carries the log-upload key. It is read while the settings page is laying out, so it is a field read and
 * nothing else — anything with a cost here would be paid on every repaint.
 *
 * This is an object rather than a direct read of [BuildConfig] for the reason
 * [com.shilapi.xcertplay.transport.adapter.AdapterRoutes] is one: a test that reached the settings page
 * would otherwise pass or fail according to how the machine that ran it was built, and a suite that is
 * green either way is evidence of nothing.
 */
object ExperimentalFeatures {

    @Volatile
    private var overridden = false

    @Volatile
    private var testValue = false

    /** Whether this package was built with the experimental features in it. */
    val enabled: Boolean get() = if (overridden) testValue else BuildConfig.EXPERIMENTAL

    /**
     * Pins the answer for the rest of the process, for tests.
     *
     * The build decides this once and a test cannot rebuild the package around it, so the test says what
     * it means to be looking at instead.
     */
    fun override(value: Boolean) {
        testValue = value
        overridden = true
    }

    /**
     * Gives this package its own answer back, after [override].
     *
     * Needed rather than tidy: the override stands for the rest of the process, and a test that left one
     * in place would decide the answer for every test that ran after it in the same JVM.
     */
    fun resetOverride() {
        overridden = false
        testValue = false
    }
}
