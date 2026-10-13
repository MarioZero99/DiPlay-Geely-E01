plugins {
    alias(libs.plugins.android.application)
}

// Optional local-only input. CI and ordinary source builds contain no accessory identity.
val localAuthenticationAssets = providers.environmentVariable("DIPLAY_AUTH_ASSETS_DIR")
    .orNull?.let { file(it).canonicalFile }

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
        versionCode = 41
        versionName = "4.0-geely-rc"

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
        debug {
            applicationIdSuffix = ".hudtest"
            versionNameSuffix = "-hud-test"
        }
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

// Car-test packages must be standalone. Keep ordinary source/CI builds identity-free.
val verifyStandaloneAuthentication by tasks.registering {
    group = "verification"
    description = "Require the explicit runtime authentication input for a standalone car-test APK."
    val directory = localAuthenticationAssets
    doLast {
        check(directory != null) {
            "Standalone car builds require DIPLAY_AUTH_ASSETS_DIR; assembleDebug alone is source-only."
        }
        check(listOf("identity.pk8", "certificate.p7b").all {
            directory.resolve("offline-mfi/$it").let { file -> file.isFile && file.length() > 0 }
        }) { "Standalone CarPlay authentication files are missing or empty" }
    }
}
tasks.named("preBuild") { mustRunAfter(verifyStandaloneAuthentication) }
tasks.register("assembleStandaloneDebug") {
    group = "build"
    description = "Build a standalone car-test APK with explicitly provisioned authentication."
    dependsOn(verifyStandaloneAuthentication, "assembleDebug")
}

// The car hop is in the tree only when DIPLAY_VENDOR_HOP_DIR names it. A car-test APK without one runs
// the AOSP stack and would waste the trip, so the standalone build asks for it as well.
val verifyVendorHop by tasks.registering {
    group = "verification"
    description = "Require the car hop for a standalone car-test APK."
    val directory = providers.environmentVariable("DIPLAY_VENDOR_HOP_DIR").orNull?.let { file(it) }
    doLast {
        check(directory != null) {
            "Standalone car builds require DIPLAY_VENDOR_HOP_DIR; without it the APK carries no car hop."
        }
        check(directory.resolve("src/com/shilapi/xcertplay/carhop/VendorCarHop.kt").isFile) {
            "DIPLAY_VENDOR_HOP_DIR does not hold the car hop source."
        }
    }
}
tasks.named("preBuild") { mustRunAfter(verifyVendorHop) }
tasks.named("assembleStandaloneDebug") { dependsOn(verifyVendorHop) }

// The adapter stack is in the tree only when DIPLAY_ADAPTER_DIR names it, and nothing in the build asks
// for it: without one the build is the published build, and a car-test APK still goes out carrying the car
// hop's Bluetooth. So this is asked for by hand, and asking for it is a claim that this run means to have
// the adapter — an unset variable is then a failure rather than a build that quietly has no adapter in it.
val verifyAdapter by tasks.registering {
    group = "verification"
    description = "Require the adapter stack, for a run that means to carry it."
    val directory = providers.environmentVariable("DIPLAY_ADAPTER_DIR").orNull?.let { file(it) }
    doLast {
        check(directory != null) {
            "DIPLAY_ADAPTER_DIR is unset; this build carries no adapter stack."
        }
        check(directory.resolve("src/com/shilapi/xcertplay/adapter/UsbAdapterRoute.kt").isFile) {
            "DIPLAY_ADAPTER_DIR does not hold the adapter route."
        }
        check(directory.resolve("jni/Android.mk").isFile) {
            "DIPLAY_ADAPTER_DIR does not hold the adapter's native stack."
        }
    }
}
