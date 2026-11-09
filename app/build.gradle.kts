plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Default Jellyseerr address. It can be changed at runtime from the in-app settings
// (Menu / Start button, or hold Back), or at build time with -PserverUrl=...
val serverUrl: String = (project.findProperty("serverUrl") as String?) ?: "http://yarr.melhorn.com:5055/"

// Optional stable release signing (so updates install over the top without
// uninstalling). Supplied by CI secrets or ~/.gradle/gradle.properties.
val keystorePath: String? = System.getenv("SIGNING_KEYSTORE_PATH")
val keystorePassword: String? = System.getenv("SIGNING_KEYSTORE_PASSWORD")
val keyAliasName: String? = System.getenv("SIGNING_KEY_ALIAS")
val keyPasswordValue: String? = System.getenv("SIGNING_KEY_PASSWORD")
val hasReleaseKey = !keystorePath.isNullOrBlank() && file(keystorePath!!).exists()

android {
    namespace = "com.melhorn.jellyseerrtv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.melhorn.jellyseerrtv"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "DEFAULT_SERVER_URL", "\"$serverUrl\"")
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = keystorePassword
                keyAlias = keyAliasName
                keyPassword = keyPasswordValue
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Fall back to the debug key so the release APK is always installable.
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
}
