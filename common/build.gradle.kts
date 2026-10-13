import java.util.Properties

plugins {
    id("com.android.library")
}

// Local test upload only. Absent on CI and on any machine that did not opt in, so a published build
// keeps the on-device report and never contacts Supabase.
//
// An absent local.properties is not on its own a guarantee about the released APK: a workspace can
// hold one for reasons that have nothing to do with the build being made in it, and a build off a git
// checkout would carry the key out with it. scripts/package-geely.sh therefore passes
// -PcloudLogs=off for the builds it makes from git and leaves local compiles alone.
//
// An unrecognised value is refused rather than treated as "on". The failure that matters here is a
// published package that still carries the key, and a typo silently reading as "keep it" would be
// exactly that — with the release workflow's own check as the only thing standing behind it.
val cloudLogs = when (val requested = gradle.startParameter.projectProperties["cloudLogs"]) {
    null, "on" -> true
    "off" -> false
    else -> throw GradleException("-PcloudLogs=$requested is not on or off")
}

val supabaseLocal = Properties().apply {
    val file = rootProject.file("local.properties")
    if (cloudLogs && file.isFile) file.inputStream().use { load(it) }
}

fun supabaseField(name: String): String {
    val value = supabaseLocal.getProperty(name).orEmpty()
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "")
        .replace("\r", "")
    return "\"$value\""
}

android {
    namespace = "com.shilapi.xcertplay.host"
    compileSdk {
        version = release(37)
    }

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        minSdk = 19
        multiDexEnabled = true
        buildConfigField("String", "SUPABASE_URL", supabaseField("diplay.supabase.url"))
        buildConfigField("String", "SUPABASE_KEY", supabaseField("diplay.supabase.key"))
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }

    testOptions {
        // Lets Robolectric tests read res/xml and res/values, not just plain Android APIs.
        unitTests.isIncludeAndroidResources = true
    }

}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    api(project(":shared"))
    implementation(libs.androidx.activity)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
}
