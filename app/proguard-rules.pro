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

# AndroidJUnitRunner uses this app-provided API across the instrumentation APK boundary.
# Keep its runtime name/methods when testing the minified release.
-keep class androidx.tracing.Trace { *; }
