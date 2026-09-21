# Keep kotlinx.serialization generated serializers for our model classes.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class dev.humanagent.** {
    *** Companion;
}
-keepclasseswithmembers class dev.humanagent.** {
    kotlinx.serialization.KSerializer serializer(...);
}
