-keep class ai.onnxruntime.** { *; }
-keep class com.google.mlkit.** { *; }
-keepclassmembers class * {
    @kotlinx.serialization.Serializable *;
}
