plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.glassmate"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.glassmate"
        minSdk = 29
        targetSdk = 34
        versionCode = 11
        versionName = "0.11"
    }

    // Постоянный ключ подписи: без него каждая сборка на GitHub получает новый ключ,
    // и Android отказывается ставить обновление поверх старой версии.
    signingConfigs {
        create("glassmate") {
            storeFile = file("glassmate.jks")
            storePassword = "glassmate"
            keyAlias = "glassmate"
            keyPassword = "glassmate"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("glassmate")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("glassmate")
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

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
}
