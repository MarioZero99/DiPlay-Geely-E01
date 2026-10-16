// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
}

// What a package contains is decided on the command line, not by the shell it happens to be built in.
//
// These four were environment variables. An environment variable is inherited, so a terminal that had
// one exported decided the contents of whatever was built in it — a release made in such a terminal
// carried the car hop and its payload without anyone asking for them. Reading only `-P<name>=...`
// removes the inheritance. A stale variable is refused rather than ignored: ignoring it would leave
// the same surprise one step further away, where it is harder to find.
val retiredEnvironmentVariables = mapOf(
    "DIPLAY_VENDOR_HOP_DIR" to "vendorHopDir",
    "DIPLAY_AUTH_ASSETS_DIR" to "authAssetsDir",
    "DIPLAY_CLOUD_LOGS" to "experimental",
    "DIPLAY_ADAPTER_DIR" to "adapterDir",
)
retiredEnvironmentVariables.forEach { (variable, property) ->
    check(!providers.environmentVariable(variable).isPresent) {
        "$variable is no longer read. Pass -P$property=... on the command line instead."
    }
}

// -PcloudLogs is retired the same way and for the same reason, one step further in: the log-upload
// key is an experimental feature now, and -Pexperimental is what decides whether a package carries
// any of them. A caller still passing the old name would get an official package while believing it
// asked for a local one, and the build would look like it worked — exactly the surprise the
// environment-variable check above is here to stop.
check(!gradle.startParameter.projectProperties.containsKey("cloudLogs")) {
    "-PcloudLogs is no longer read. Pass -Pexperimental=on|off instead."
}
