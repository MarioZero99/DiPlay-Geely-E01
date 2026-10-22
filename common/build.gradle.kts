import java.util.Properties

plugins {
    id("com.android.library")
}

// One switch says which of the two packages this is. `-Pexperimental=on` is the local one: the
// features that are not ready to be handed to a driver, and with them the log-upload key. Absent or
// `off` is the official one, which carries none of it. Nothing else in this build reads the switch's
// value; it is published as BuildConfig.EXPERIMENTAL and the settings page draws its bucket or not.
//
// The key used to have a switch of its own, `-PcloudLogs`, and the two could be asked for separately
// — which is how a package meant to be local came out official, and the other way round. What the key
// turns on is the on-device "generate and upload report" button, and that button is one of the
// experimental features now, so one switch decides both. The retired name is refused outright in the
// root build file rather than ignored here.
//
// An absent local.properties is not on its own a guarantee about the official package: a workspace can
// hold one for reasons that have nothing to do with the build being made in it. Only the switch puts
// the key in, so the file decides the key's value and never whether there is one.
//
// An unrecognised value is refused rather than treated as "off". A typo silently reading as "off"
// would make an official package out of a run that asked for a local one, and the run would look like
// it worked — the same shape as the typo this replaced, pointing the other way.
val experimental = when (val requested = gradle.startParameter.projectProperties["experimental"]) {
    null, "off" -> false
    "on" -> true
    else -> throw GradleException("-Pexperimental=$requested is not on or off")
}

val supabaseLocal = Properties().apply {
    val file = rootProject.file("local.properties")
    if (experimental && file.isFile) file.inputStream().use { load(it) }
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
        buildConfigField("boolean", "EXPERIMENTAL", experimental.toString())
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
    api(project(":governance"))
    implementation(libs.androidx.activity)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
}
