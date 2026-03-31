# GateShot ProGuard Rules

# Keep API endpoint classes (reflection-based routing)
-keep class com.gateshot.core.api.** { *; }
-keep class * implements com.gateshot.core.api.ApiEndpoint { *; }
-keep class * implements com.gateshot.core.module.FeatureModule { *; }

# Keep serializable preset classes
-keep class com.gateshot.capture.preset.Preset { *; }
-keep class com.gateshot.capture.preset.** { *; }

# Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
