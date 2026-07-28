# ──────────────────────────────────────────────
# ProGuard / R8 Rules for LocalAlbum
# ──────────────────────────────────────────────

# ── Kotlin ──
-keepattributes *Annotation*
-keepattributes SourceFile, LineNumberTable
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# ── Jetpack Compose ──
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**
-keep class androidx.lifecycle.** { *; }

# ── Room ──
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keepclassmembers @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface *
-keepclassmembers @androidx.room.Dao interface * { *; }
-dontwarn androidx.room.paging.**

# ── TensorFlow Lite ──
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**
-keep class org.tensorflow.lite.support.** { *; }
-dontwarn org.tensorflow.lite.support.**

# ── ONNX Runtime ──
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
-keep class com.microsoft.onnxruntime.** { *; }
-dontwarn com.microsoft.onnxruntime.**

# ── PyTorch Mobile ──
-keep class org.pytorch.** { *; }
-dontwarn org.pytorch.**
-keep class com.facebook.jni.** { *; }
-dontwarn com.facebook.jni.**

# ── OpenCV ──
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**
-keepclassmembers class org.opencv.** { native <methods>; }

# ── ML Kit ──
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# ── osmdroid (Map) ──
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# ── Coil ──
-keep class coil.** { *; }
-dontwarn coil.**

# ── Media3 ExoPlayer ──
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# ── DataStore ──
-keep class androidx.datastore.** { *; }
-dontwarn androidx.datastore.**

# ── Paging 3 ──
-keep class androidx.paging.** { *; }
-dontwarn androidx.paging.**

# ── WorkManager ──
-keep class androidx.work.** { *; }
-dontwarn androidx.work.**

# ── Gson / JSON (if used) ──
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class org.json.** { *; }

# ── Plugin System ──
# Keep all AiPlugin implementations for DexClassLoader
-keep interface com.renyxin.localalbum.core.plugin.AiPlugin
-keep interface com.renyxin.localalbum.core.plugin.ClassificationPlugin
-keep interface com.renyxin.localalbum.core.plugin.FeatureExtractionPlugin
-keep interface com.renyxin.localalbum.core.plugin.DetectionPlugin
-keep interface com.renyxin.localalbum.core.plugin.GenerativePlugin
-keep class * extends com.renyxin.localalbum.core.plugin.AiPlugin { *; }

# ── Entity classes (used by Room + Gson serialization) ──
-keep class com.renyxin.localalbum.data.db.entity.** { *; }
-keep class com.renyxin.localalbum.core.model.** { *; }
-keep class com.renyxin.localalbum.core.plugin.PluginManifest { *; }
-keep class com.renyxin.localalbum.core.plugin.PluginManifest$** { *; }
-keep class com.renyxin.localalbum.core.plugin.PluginInput { *; }
-keep class com.renyxin.localalbum.core.plugin.PluginInput$** { *; }
-keep class com.renyxin.localalbum.core.plugin.PluginOutput { *; }
-keep class com.renyxin.localalbum.core.plugin.PluginOutput$** { *; }
-keep class com.renyxin.localalbum.core.plugin.FeatureSchema { *; }
-keep class com.renyxin.localalbum.core.plugin.FeatureSchema$** { *; }

# ── Native methods ──
-keepclasseswithmembernames class * {
    native <methods>;
}

# ── Enum classes ──
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ── Serializable / Parcelable ──
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# ── Remove logging in release ──
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}
