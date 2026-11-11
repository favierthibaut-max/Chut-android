plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.chut"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.chut"
        minSdk = 29          // Android 10 : nécessaire pour le filtrage d'appels par une app tierce
        targetSdk = 34
        versionCode = 4
        versionName = "1.3"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signé avec la clé de debug pour pouvoir l'installer directement.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
