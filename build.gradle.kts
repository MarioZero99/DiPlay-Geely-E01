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
    "DIPLAY_CLOUD_LOGS" to "cloudLogs",
    "DIPLAY_ADAPTER_DIR" to "adapterDir",
)
retiredEnvironmentVariables.forEach { (variable, property) ->
    check(!providers.environmentVariable(variable).isPresent) {
        "$variable is no longer read. Pass -P$property=... on the command line instead."
    }
}
