plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ir.ghostshare"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.bs1388.ghostshare"
        minSdk = 28
        targetSdk = 35
        versionCode = 140
        versionName = "1.4.0"
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    val ciKeystore = System.getenv("KEYSTORE_PATH")?.takeIf { it.isNotEmpty() }?.let { file(it) }?.takeIf { it.exists() }

    signingConfigs {
        if (ciKeystore != null) {
            create("ci") {
                storeFile = ciKeystore
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            signingConfigs.findByName("ci")?.let { signingConfig = it }
        }
        release {
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = signingConfigs.findByName("ci") ?: signingConfigs.getByName("debug")
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
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    compileOnly(files("libs/libxposed-api-102.0.0.jar"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.2")

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
}
