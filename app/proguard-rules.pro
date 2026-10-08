# Keep kotlinx.serialization generated serializers for our model classes.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class dev.humanagent.** {
    *** Companion;
}
-keepclasseswithmembers class dev.humanagent.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# The MediaPipe / LiteRT-LM engines read protobuf-generated messages and their fields
# reflectively from JNI by exact name (e.g. MediaPipeLoggingProto$SystemInfo.platform_).
# Renaming or stripping those members breaks engine creation at setup time.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.ai.edge.litertlm.** { *; }
-keep class com.google.ai.edge.litert.** { *; }

# Optional pieces of the full MediaPipe framework the slim tasks distribution does not ship:
# an auto-value compile-time annotation, and profiler/graph-template protos referenced only
# from GraphProfiler and Graph.loadBinaryGraphTemplate — none of which the embedder runs.
-dontwarn com.google.auto.value.extension.memoized.Memoized
-dontwarn com.google.mediapipe.proto.CalculatorProfileProto$CalculatorProfile
-dontwarn com.google.mediapipe.proto.GraphTemplateProto$CalculatorGraphTemplate

# pdfbox-android's optional JPEG2000 codec (not shipped); only JPEG2000-filtered embedded
# images in PDFs would touch it — the brain extracts text and the renderer writes plain text.
-dontwarn com.gemalto.jp2.JP2Decoder
-dontwarn com.gemalto.jp2.JP2Encoder
