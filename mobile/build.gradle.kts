plugins {
    alias(libs.plugins.android.application)
}

// The accessory identity, when this build was asked to carry one. Command line only, for the same
// reason the hop directory is: this input decides what the package authenticates as, and an inherited
// variable must not be able to decide that. CI and ordinary source builds pass nothing here.
val localAuthenticationAssets = gradle.startParameter.projectProperties["authAssetsDir"]
    ?.let { file(it).canonicalFile }

android {
    namespace = "com.shilapi.xcertplay"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.shihab.diplay"
        minSdk = 19
        targetSdk = 37
        multiDexEnabled = true
        versionCode = 42
        versionName = "0.4.2-geely"

    }


    localAuthenticationAssets?.let { sourceSets.getByName("main").assets.srcDir(it) }

    signingConfigs {
        create("release") {
            storeFile = file(
                providers.environmentVariable("ANDROID_KEYSTORE_PATH")
                    .getOrElse("missing-release-keystore.jks"),
            )
            storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").getOrElse("")
            keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").getOrElse("")
            keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").getOrElse("")
        }
    }

    buildTypes {
        // No debug block, so the debug type is AGP's own: that is the source-only build CI makes. It
        // used to add ".hudtest" to the application id and "-hud-test" to the version name so a test
        // build could sit beside a released one — and that is what made the package that actually
        // connects the one with the odd name, in a second app with its own settings. Both packages
        // this project hands out are release builds: one application id, one key, and each installs
        // over the other with its settings kept.
        release {
            optimization {
                enable = false
            }
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(project(":common"))
    implementation(project(":shared"))
    implementation("androidx.multidex:multidex:2.0.1")
}

// No implicit import. Only the two explicitly selected local runtime assets are allowed.
val credentialAssets = files(android.sourceSets.flatMap { source ->
    source.assets.directories.map { directory ->
        fileTree(directory) {
            include("**/offline-mfi/**", "**/*.pk8", "**/*.p7b", "**/*.key",
                "**/*.pem", "**/*.p12", "**/*.pfx", "**/*.jks", "**/*.keystore")
        }
    }
})
val rejectBundledCredentials by tasks.registering {
    group = "verification"
    description = "Reject unexpected credential files in APK assets."
    val filesToCheck = credentialAssets
    val allowed = localAuthenticationAssets?.let { dir ->
        listOf("identity.pk8", "certificate.p7b").map { dir.resolve("offline-mfi/$it").canonicalFile }.toSet()
    } ?: emptySet()
    inputs.files(filesToCheck)
    doLast {
        check(allowed.all { it.isFile }) { "Explicit local authentication assets are incomplete" }
        val unexpected = filesToCheck.files.filter { it.canonicalFile !in allowed }
        check(unexpected.isEmpty()) { "Unexpected credential files in APK assets" }
    }
}
tasks.named("preBuild") { dependsOn(rejectBundledCredentials) }

// The packages that are handed out must be standalone. Keep ordinary source/CI builds identity-free.
val verifyStandaloneAuthentication by tasks.registering {
    group = "verification"
    description = "Require the explicit runtime authentication input for a standalone car APK."
    val directory = localAuthenticationAssets
    doLast {
        check(directory != null) {
            "Standalone car builds require -PauthAssetsDir=<path>; assembleDebug alone is source-only."
        }
        check(listOf("identity.pk8", "certificate.p7b").all {
            directory.resolve("offline-mfi/$it").let { file -> file.isFile && file.length() > 0 }
        }) { "Standalone CarPlay authentication files are missing or empty" }
    }
}
tasks.named("preBuild") { mustRunAfter(verifyStandaloneAuthentication) }
tasks.register("assembleStandaloneRelease") {
    group = "build"
    description = "Build a package to hand out: the car hop and an explicitly provisioned identity."
    dependsOn(verifyStandaloneAuthentication, "assembleRelease")
}

// The car hop is compiled in only when -PvendorHopDir names it. Both packages this project hands out
// carry it — the difference between them is -Pexperimental and nothing else — so the standalone build
// asks for it, and a hop-less package is not one of the two.
val verifyVendorHop by tasks.registering {
    group = "verification"
    description = "Require the car hop for a standalone car APK."
    val directory = gradle.startParameter.projectProperties["vendorHopDir"]?.let { file(it) }
    doLast {
        check(directory != null) {
            "Standalone car builds require -PvendorHopDir=<path>; without it the APK carries no car hop."
        }
        check(directory.resolve("src/com/shilapi/xcertplay/carhop/VendorCarHop.kt").isFile) {
            "-PvendorHopDir=${directory} does not hold the car hop source."
        }
    }
}
tasks.named("preBuild") { mustRunAfter(verifyVendorHop) }
tasks.named("assembleStandaloneRelease") { dependsOn(verifyVendorHop) }

// There is no adapter check here any more. The adapter stack was compiled in by -PadapterDir and this
// file used to require it for the standalone build; neither package carries one now, so a check that
// demanded it would fail the only two builds there are. -PadapterDir still mounts the sources in
// shared/build.gradle, for a hand-run build that means to have it, and nothing here asks for it.
