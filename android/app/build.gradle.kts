import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing material lives in android/keystore.properties (gitignored).
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val releaseKeystore = keystoreProps.getProperty("storeFile")?.let { rootProject.file(it) }
val hasReleaseKey = releaseKeystore?.exists() == true

android {
    namespace = "ph.zenktx.tv"
    compileSdk = 34

    defaultConfig {
        minSdk = 24          // Android TV 7.0 / Android 7.0
        targetSdk = 34
        // CI overrides these from the release tag: -PversionName=1.2.0 -PversionCode=42
        versionCode = (findProperty("versionCode") as String?)?.toIntOrNull() ?: 10503
        versionName = (findProperty("versionName") as String?) ?: "1.5.3"

        buildConfigField("String", "CERT_SHA256", "\"${keystoreProps.getProperty("certSha256", "")}\"")
        buildConfigField("String", "GITHUB_REPO", "\"ppoykulot-lgtm/zenktx-tv\"")
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                // Every APK up to 1.3.3 carried only the v2 block. Android 7 and
                // 8 installers, and several file managers, refuse an APK with no
                // v1 (JAR) signature and show a bare "App not installed".
                // Sign with all three schemes so any device can verify it.
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    flavorDimensions += "device"
    productFlavors {
        create("tv") {
            dimension = "device"
            applicationId = "ph.zenktx.tv"
            manifestPlaceholders["orientation"] = "landscape"
            resValue("string", "device_label", "ZENKTX TV Stream")
        }
        create("mobile") {
            dimension = "device"
            applicationId = "ph.zenktx.mobile"
            manifestPlaceholders["orientation"] = "unspecified"
            resValue("string", "device_label", "ZENKTX Mobile")
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }
}

dependencies {
    val media3 = "1.3.1"

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.flexbox:flexbox:3.0.0")   // wrapping category chips
    implementation("io.coil-kt:coil:2.6.0")   // channel logos
    implementation("io.coil-kt:coil-svg:2.6.0")   // 45 catalogue logos are .svg

    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-datasource:$media3")
    implementation("androidx.media3:media3-ui:$media3")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
