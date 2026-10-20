plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing comes from the environment so no secret is ever committed:
//   DASHLENS_KEYSTORE (path), DASHLENS_KS_PASS, DASHLENS_KEY_ALIAS, DASHLENS_KEY_PASS
val ksPath: String? = System.getenv("DASHLENS_KEYSTORE")

android {
    namespace = "app.dashlens"
    compileSdk = 34
    defaultConfig {
        applicationId = "app.dashlens"
        minSdk = 26
        targetSdk = 34
        versionCode = 6
        versionName = "4.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    if (ksPath != null) {
        signingConfigs {
            create("release") {
                storeFile = file(ksPath)
                storePassword = System.getenv("DASHLENS_KS_PASS")
                keyAlias = System.getenv("DASHLENS_KEY_ALIAS")
                keyPassword = System.getenv("DASHLENS_KEY_PASS")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (ksPath != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    lint { abortOnError = false }
}
