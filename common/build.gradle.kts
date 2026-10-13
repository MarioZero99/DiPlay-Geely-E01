import java.util.Properties

plugins {
    id("com.android.library")
}

// The Supabase key exists for local runs that need the on-device "generate and upload report"
// button. It is off unless the caller asks for it with DIPLAY_CLOUD_LOGS=1, so no build carries the
// key by accident — not a CI build, not a bare compile, and not a workspace that happens to hold a
// local.properties for reasons that have nothing to do with the build being made in it.
val cloudLogs = System.getenv("DIPLAY_CLOUD_LOGS") == "1"

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
