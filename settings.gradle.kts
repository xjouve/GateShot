pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "GateShot"

include(":app")
include(":core")
include(":session")
include(":processing:autoclip")
include(":processing:export")
include(":processing:stabilize")
include(":coaching:replay")
include(":coaching:timing")
include(":coaching:annotation")
include(":coaching:athlete")
include(":coaching:pose")
