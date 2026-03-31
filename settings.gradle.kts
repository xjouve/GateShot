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
include(":platform")
include(":capture:camera")
include(":capture:burst")
include(":capture:preset")
include(":capture:trigger")
include(":session")
include(":processing:snow-exposure")
include(":processing:burst-culling")
include(":processing:bib-detection")
include(":processing:autoclip")
include(":processing:export")
include(":processing:super-resolution")
include(":coaching:replay")
include(":coaching:timing")
include(":coaching:annotation")
