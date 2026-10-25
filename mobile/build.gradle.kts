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
        versionCode = 48
        versionName = "0.4.8-geely"

        // Package only the two ABIs the E01 reports. This is what keeps the bundled
        // conscrypt-android native libraries (2.15 + 2.39 MB for x86/x86_64) out of the APK; the
        // filters in :shared only cover that module's own ndkBuild output.
        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a"))
        }
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
            // R8 and resource shrinking, for the package that is handed out. This was off, and it is
            // what the size was made of: 13.6 MB of dex before shrinking, 4.9 MB of it inside the APK.
            // The only classes this app looks up by name are the two constants in proguard-rules.pro.
            optimization {
                enable = true
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    // Java resources carried by the libraries, cut down to the ones this app can reach. They are
    // packaged as they sit in the jars, so R8 cannot drop them: unused cryptography parameter tables
    // are here as files, not as code, and they are not small.
    //
    // org/bouncycastle/pqc/** is the Picnic signature parameter tables (L1/L3/L5, 1.19 MB). BouncyCastle
    // is used for ChaCha20-Poly1305, X25519, Ed25519, HKDF and SHA-512 and for nothing else; no PQC
    // code path is called. org/bouncycastle/x509/CertPathReviewerMessages* is the PKIX certificate-path
    // reviewer's message catalogue (90 KB), which belongs to the provider registration this app does
    // not do — BouncyCastle is used through its low-level API, never as a java.security.Provider.
    packaging {
        resources {
            excludes += listOf(
                "org/bouncycastle/pqc/**",
                "org/bouncycastle/x509/CertPathReviewerMessages*",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }
    androidResources {
        // AppLocale offers exactly two languages (AppLocale.ALL: system, English, Simplified Chinese),
        // and this project's own strings exist in exactly those two. Everything else in the resource
        // table is a translation the libraries ship — about 80 locales of "Cancel" and "OK" — which no
        // path in this app can reach. Dropping them keeps the resource table to what the language
        // setting can actually produce.
        // Resource qualifiers, passed to aapt2's -c as they are written: "zh-CN" is refused outright
        // ("invalid config 'zh-CN' for -c option"), and the bare "zh" links without complaint while
        // matching no directory in this tree — it takes the Chinese strings out silently. The
        // qualifier here is the one the resources use, values-zh-rCN.
        localeFilters += listOf("en", "zh-rCN")
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
