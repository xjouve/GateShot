pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolution {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "GateShot"

include(":app")
include(":core")
include(":platform")
include(":capture:camera")
include(":capture:preset")
