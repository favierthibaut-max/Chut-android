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
        versionCode = 8
        versionName = "1.7"
    }

    // Clé fixe pour l'installation directe (hors Play Store) : toutes les versions ont la même
    // signature, donc chaque mise à jour s'installe par-dessus la précédente.
    // Pour le Play Store, il faudra une clé d'upload gardée secrète (pas dans le dépôt).
    signingConfigs {
        create("sideload") {
            storeFile = rootProject.file("signing/chut-sideload.jks")
            storePassword = "chut-sideload"
            keyAlias = "chut"
            keyPassword = "chut-sideload"
        }
    }

    flavorDimensions += "edition"
    productFlavors {
        create("complet") {
            dimension = "edition"
        }
        create("lite") {
            dimension = "edition"
            versionNameSuffix = "-lite" // sans accès au journal d'appels
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sideload")
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
