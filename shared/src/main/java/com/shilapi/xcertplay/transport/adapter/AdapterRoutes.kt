package com.shilapi.xcertplay.transport.adapter

/**
 * Finds this build's adapter stack, if it has one.
 *
 * Written the way [com.shilapi.xcertplay.carhop.CarBluetoothHops] is, and for the same reason: the
 * implementation names a class this tree does not define, so one source tree serves both a build with the
 * adapter and the tree as published. Naming a class that nothing defines is the ordinary case, not an error.
 *
 * The lookup is deliberately the only thing [active] does. The settings page reads it while it is laying
 * out, and anything with a cost there would be paid on every repaint.
 */
object AdapterRoutes {
    private const val ADAPTER_ROUTE_CLASS = "com.shilapi.xcertplay.adapter.UsbAdapterRoute"

    @Volatile
    private var resolved = false

    @Volatile
    private var route: AdapterRoute? = null

    @Volatile
    private var overridden = false

    @Volatile
    private var testRoute: AdapterRoute? = null

    /** This build's adapter stack, or null when it has none. */
    val active: AdapterRoute?
        get() = if (overridden) testRoute else load()

    /** Whether this build has an adapter stack at all. */
    val available: Boolean get() = active != null

    /**
     * Puts a route in place of whatever this build has, for the rest of the process. Passing null says this
     * build has none.
     *
     * For tests, and needed rather than nice: without it, every test that reaches a page holding the adapter
     * would pass or fail according to whether the machine that ran it had the stack mounted, and a suite that
     * is green either way would no longer be evidence of anything.
     */
    fun override(route: AdapterRoute?) {
        testRoute = route
        overridden = true
    }

    /**
     * Gives this build its own answer back, after [override].
     *
     * Needed rather than tidy: the override stands for the rest of the process, and a test that left one in
     * place would decide the answer for every test that ran after it in the same JVM.
     */
    fun resetOverride() {
        overridden = false
        testRoute = null
    }

    @Synchronized
    private fun load(): AdapterRoute? {
        if (resolved) return route
        route = try {
            Class.forName(ADAPTER_ROUTE_CLASS).getDeclaredConstructor().newInstance() as AdapterRoute
        } catch (_: ClassNotFoundException) {
            null
        }
        resolved = true
        return route
    }
}
