import java.io.ByteArrayOutputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

// versionCode tracks how many commits are on this branch so every build from
// a fresh clone gets a monotonically increasing code without manual bumping.
fun gitCommitCount(): Int = try {
    val out = ByteArrayOutputStream()
    exec {
        commandLine("git", "rev-list", "--count", "HEAD")
        standardOutput = out
        isIgnoreExitValue = true
    }
    out.toString().trim().toIntOrNull() ?: 1
} catch (e: Exception) {
    1
}

val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// Release signing comes from env vars first (CI), then local.properties (dev
// machines). When neither is set, release falls back to the debug keystore so
// `assembleRelease` always produces an installable APK.
fun releaseProp(key: String): String? =
    System.getenv(key)?.takeIf { it.isNotBlank() }
        ?: localProperties.getProperty(key)?.takeIf { it.isNotBlank() }

val hasReleaseSigning = releaseProp("GATESHOT_KEYSTORE") != null

android {
    namespace = "com.gateshot"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gateshot"
        minSdk = 29
        targetSdk = 35
        versionCode = gitCommitCount()
        versionName = "1.0.0"
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = file(releaseProp("GATESHOT_KEYSTORE")!!)
                storePassword = releaseProp("GATESHOT_KEYSTORE_PASSWORD")
                keyAlias = releaseProp("GATESHOT_KEY_ALIAS")
                keyPassword = releaseProp("GATESHOT_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

    lint {
        // Do not fail CI builds yet; findings are triaged and fixed by hand.
        // checkReleaseBuilds off since release is minified/shrunk separately.
        abortOnError = false
        checkReleaseBuilds = false
        warningsAsErrors = false
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion")
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":session"))
    implementation(project(":processing:autoclip"))
    implementation(project(":processing:export"))
    implementation(project(":processing:stabilize"))
    implementation(project(":coaching:replay"))
    implementation(project(":coaching:timing"))
    implementation(project(":coaching:annotation"))
    implementation(project(":coaching:athlete"))
    implementation(project(":coaching:pose"))
    implementation(project(":coaching:aicoach"))

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    debugImplementation(libs.compose.ui.tooling)

    // AndroidX
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // ExoPlayer (for replay)
    implementation(libs.exoplayer)
    implementation(libs.exoplayer.ui)
}
