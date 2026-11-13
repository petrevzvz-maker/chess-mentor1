plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.chessmentor"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.chessmentor"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.5-stability-pass"
    }
}

dependencies {
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
}

kotlin {
    jvmToolchain(17)
}
