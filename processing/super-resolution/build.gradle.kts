plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.gateshot.processing.sr"
    compileSdk = 35
    defaultConfig { minSdk = 29 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":platform"))
    implementation(libs.core.ktx)
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    implementation(libs.camerax.core)
    implementation(libs.tflite)
    implementation(libs.tflite.gpu)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
