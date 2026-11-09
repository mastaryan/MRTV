import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Optional developer values from "secrets.properties" (ignored by Git) or environment variables (Google Drive backup).
val secrets = Properties().apply {
    val f = rootProject.file("secrets.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
// Google sign-in for the optional Google Drive backup (see docs/GOOGLE_DRIVE.md); client IDs contain '-' and '.'
fun secretId(name: String): String =
    (secrets.getProperty(name) ?: System.getenv(name) ?: "").trim().filter { it.isLetterOrDigit() || it == '-' || it == '.' || it == '_' }

// MR TV 2: crash webhook settings (see defaultConfig). Order: -P property, environment, crash.properties (git-ignored).
val crashProps = Properties().apply {
    val f = rootProject.file("crash.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun buildSecret(name: String): String =
    ((project.findProperty(name) as String?) ?: System.getenv(name) ?: crashProps.getProperty(name) ?: "")
        .trim().replace("\\", "\\\\").replace("\"", "\\\"")

// MR TV 2: built-in Xtream server (users then only enter username and password). Order: -P property, environment,
// "server.properties" (ignored by Git, see server.properties.example). Unset = normal IPVIO login with server field.
// Never commit the address. Note: it can still be read out of a built APK.
val serverProps = Properties().apply {
    val f = rootProject.file("server.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val serverUrl: String =
    ((project.findProperty("MRTV2_SERVER_URL") as String?) ?: System.getenv("MRTV2_SERVER_URL") ?: serverProps.getProperty("MRTV2_SERVER_URL") ?: "")
        .trim().trimEnd('/').replace("\\", "").replace("\"", "")

// Release signing from "keystore.properties" (ignored by Git, see docs/RELEASE.md). Every release MUST be signed with the
// same key: only then does Android install an update over the old version and keep history, favorites and settings.
val keystore = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasKeystore = listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all { !keystore.getProperty(it).isNullOrBlank() }

android {
    namespace = "com.ipvio.stream"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // MR TV 2: own app ID so it installs next to the old MR TV (com.watchmrtv.tv) and next to IPVIO.
        // The Kotlin namespace stays com.ipvio.stream on purpose, so upstream IPVIO updates merge cleanly.
        applicationId = "com.watchmrtv.tv2"
        minSdk = 24
        targetSdk = 37
        // Raise both for every release; the GitHub release tag must match versionName (e.g. tag "v1.1").
        versionCode = 2
        versionName = "2.0.1"
        // Name unter dem Symbol; der Debug-Build überschreibt ihn (siehe buildTypes)
        manifestPlaceholders["appLabel"] = "MR TV 2"
        // In-app updater: small JSON file on watchmrtv.com ({"versionCode":2,"versionName":"1.0.1","apk":"MRTV2-1.0.1.apk","notes":"..."}).
        buildConfigField("String", "UPDATE_URL", "\"https://watchmrtv.com/mrtv2-version.json\"")

        // MR TV 2: outside movie-info and rating services were removed; the app only uses the provider's own data.
        buildConfigField("String", "SERVER_URL", "\"$serverUrl\"")
        // MR TV 2 crash reports: webhook URL and Authorization value come only from the build machine
        // (env MRTV_CRASH_WEBHOOK_URL / MRTV_CRASH_WEBHOOK_KEY, -P properties, or the git-ignored crash.properties).
        // The key is sent as "Authorization: Bearer <key>".
        // Unset = reporter off. Never commit these values.
        buildConfigField("String", "CRASH_WEBHOOK_URL", "\"${buildSecret("MRTV_CRASH_WEBHOOK_URL")}\"")
        buildConfigField("String", "CRASH_WEBHOOK_KEY", "\"${buildSecret("MRTV_CRASH_WEBHOOK_KEY")}\"")
        buildConfigField("String", "GOOGLE_CLIENT_ID", "\"${secretId("GOOGLE_CLIENT_ID")}\"")
        buildConfigField("String", "GOOGLE_CLIENT_SECRET", "\"${secretId("GOOGLE_CLIENT_SECRET")}\"")
    }

    signingConfigs {
        if (hasKeystore) create("release") {
            storeFile = rootProject.file(keystore.getProperty("storeFile"))
            storePassword = keystore.getProperty("storePassword")
            keyAlias = keystore.getProperty("keyAlias")
            keyPassword = keystore.getProperty("keyPassword")
        }
    }

    buildTypes {
        // Test-Build (Run in Android Studio / Debug-APK): eigene App-ID, damit die private IPVIO-App auf dem Fernseher
        // nicht überschrieben wird. Beide Apps lassen sich parallel installieren (eigene Daten, eigener Name).
        debug {
            applicationIdSuffix = ".test"
            manifestPlaceholders["appLabel"] = "MR TV 2 Test"
        }
        release {
            if (hasKeystore) signingConfig = signingConfigs.getByName("release")
            optimization {
                // Off: the shrunk release build (12.7 MB) did not start on the device. Turn back on only together with
                // keep rules in src/main/keepRules and a test on a real device.
                enable = false
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        disable += "UnsafeOptInUsageError"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")

    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    // MPEG-DASH (.mpd) streams; without it Media3 throws IllegalStateException when such a stream is opened
    implementation("androidx.media3:media3-exoplayer-dash:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    // Software audio decoder (AC3/E-AC3/DTS ...) for headphones/Bluetooth, see SoftAudio.kt. GPL-3.0 – the reason the app is GPL-3.0.
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.9.0+1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")

    implementation("androidx.work:work-runtime-ktx:2.10.0")

    testImplementation("junit:junit:4.13.2")
}
