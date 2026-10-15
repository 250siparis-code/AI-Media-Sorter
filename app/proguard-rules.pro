-keep class ai.onnxruntime.** { *; }
-keep class com.google.mlkit.** { *; }
-keepclassmembers class * {
    @kotlinx.serialization.Serializable *;
}

-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
# Preserve Room/serialization contracts and native safety-suite entry points.
-keep class com.aimediasorter.app.data.** { *; }
-keep class com.aimediasorter.app.domain.** { *; }
-keep public class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
