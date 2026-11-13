plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// El numero de version lo pone GitHub al compilar (-PverCode=N); asi la app sabe si hay una nueva
val verCode = (project.findProperty("verCode") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "com.evatv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.evatv"
        minSdk = 21
        targetSdk = 34
        versionCode = verCode
        versionName = "1.$verCode"
    }

    // Llave fija: asi cada version nueva se instala ENCIMA de la anterior
    signingConfigs {
        create("evatv") {
            storeFile = file("eva-tv.jks")
            storePassword = "evatv2026"
            keyAlias = "evatv"
            keyPassword = "evatv2026"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("evatv")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("evatv")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
    }
}

dependencies {
    val media3 = "1.3.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.annotation:annotation:1.8.0")
    implementation("androidx.core:core:1.13.1")
}
