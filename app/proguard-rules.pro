# GateShot ProGuard / R8 rules
#
# media3 (media3-exoplayer, media3-ui) and Hilt ship their own consumer
# proguard rules inside their AARs, so no manual keep rules are needed for
# either here.
#
# core.api / core.module: EndpointRegistry looks endpoints up by an
# EndpointDescriptor.path *string value* (a normal field on each instance,
# not a class name), and FeatureModule instances are wired through a Hilt
# multibinding Set<FeatureModule> (compile-time DI, not reflection /
# Class.forName / ServiceLoader). Nothing resolves these types by class name
# at runtime, so R8's ordinary reachability analysis (plus Hilt's own
# consumer rules keeping its generated component/binding classes) is
# sufficient -- no blanket -keep is required. If a future feature adds
# reflection-based lookup (e.g. Class.forName on a module/endpoint class
# name), add a targeted -keep for that class then.
#
# The former "capture.preset" keep rules are gone: the capture module was
# deleted in the video-analysis pivot and no longer exists in this codebase.

# TensorFlow Lite (MoveNet pose model): the runtime loads native/delegate
# classes reflectively, so keep the whole package.
-keep class org.tensorflow.** { *; }
-dontwarn org.tensorflow.**

# kotlinx.serialization -- official recommended R8 rules for the Kotlin
# serialization compiler plugin (used across coaching/processing modules).
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class **$$serializer {
    private ** descriptor;
}
-keepclassmembers class * {
    *** Companion;
}
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <1>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1>$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
