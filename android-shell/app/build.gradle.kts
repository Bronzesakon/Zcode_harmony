import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---------------------------------------------------------------------------
// Release signing
//
// Mirrors zemote's scheme so both apps share ONE keystore (see the migration
// doc, decision D14):
//   * local builds  -> android-shell/key.properties (git-ignored)
//   * CI builds     -> the workflow writes the same key.properties from the
//                      ANDROID_* GitHub Secrets
// A missing keystore is NOT an error: the release build falls back to the debug
// key so the pipeline still produces an installable artefact (with a warning).
// ---------------------------------------------------------------------------
val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("key.properties")
if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
}

fun propOrEnv(name: String): String? =
    keystoreProperties.getProperty(name) ?: System.getenv("ANDROID_${name.uppercase()}")

val releaseStoreFile = propOrEnv("storeFile")

android {
    namespace = "com.zcode.remote"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zcode.remote"
        minSdk = 26
        targetSdk = 35
        // NOTE: versionCode/versionName live here AND in CHANGELOG.md. That is
        // the whole list — the in-app version string is read from
        // PackageManager, so there is no third copy to drift (zemote had one).
        versionCode = 1
        versionName = "1.0.0"

        // Pure Kotlin/Java: no native libraries, so one universal APK covers
        // every ABI. No splits, no abiFilters (decision in §11.2 of the doc).
    }

    signingConfigs {
        create("release") {
            if (!releaseStoreFile.isNullOrBlank()) {
                keyAlias = propOrEnv("keyAlias")
                keyPassword = propOrEnv("keyPassword")
                // CI writes an absolute path; a local key.properties may use a
                // path relative to the repository root.
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = propOrEnv("storePassword")
                // Explicit, because a PKCS12 store named .jks would otherwise be
                // read as legacy JKS and fail to load. Overridable for anyone
                // bringing an older keystore.
                storeType = propOrEnv("storeType") ?: when {
                    releaseStoreFile.endsWith(".p12", true) ||
                        releaseStoreFile.endsWith(".pfx", true) -> "PKCS12"
                    else -> "JKS"
                }
            }
        }
    }

    buildTypes {
        release {
            val releaseSigning = signingConfigs.findByName("release")
            signingConfig = if (releaseSigning?.storeFile != null) {
                releaseSigning
            } else {
                logger.warn(
                    "WARNING: no release keystore configured (key.properties / " +
                        "ANDROID_* env) — signing the release build with the debug key."
                )
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
        )
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.google.android.material:material:1.12.0")

    // addDocumentStartJavaScript / WebViewCompat — the document-start injection
    // this whole design depends on (§5.1).
    implementation("androidx.webkit:webkit:1.12.1")

    // QR scanning without Google Play Services (works on Chinese ROMs).
    // ZXing core is pulled transitively; no GMS dependency anywhere.
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    testImplementation("junit:junit:4.13.2")
}
