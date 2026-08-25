# =============================================================================
# SmartVision / 智能视界 — ProGuard / R8 rules
# =============================================================================
# Goal: aggressive shrink + obfuscation while keeping the surface that AndroidX
#       needs at runtime (Room, Compose, Parcelable, JNI, Coroutines, Coil).
# =============================================================================

# --- Kotlin -----------------------------------------------------------------
-dontwarn kotlin.**
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$WhenMappings { <fields>; }

# kotlinx.coroutines
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# --- AndroidX / Compose ------------------------------------------------------
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.** { *; }
-keepclassmembers class androidx.compose.** { *; }
-keep class androidx.lifecycle.** { *; }

# Saveable state classes (used by rememberSaveable)
-keepclassmembers class * implements androidx.compose.runtime.saveable.Saver {
    public static ** CREATOR;
}

# --- Parcelable --------------------------------------------------------------
-keep class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
-keep class * implements android.os.Parcelable$Creator {
    *;
}

# --- Data models (passed via Intent / saved state) --------------------------
-keep class com.smartvision.gallery.data.model.** { *; }
-keep class com.smartvision.gallery.cloud.** { *; }
-keep class com.smartvision.gallery.decoder.format.MediaFormat { *; }
-keep class com.smartvision.gallery.decoder.format.MediaFormat$* { *; }

# --- Room -------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao class *
-keepclassmembers @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# --- JNI / native bridge ----------------------------------------------------
-keepclasseswithmembernames class * { native <methods>; }
-keep class com.smartvision.gallery.decoder.bridge.** { *; }
-keep class com.smartvision.gallery.privacy.EncryptedPrivacyVault { *; }
-keep class com.smartvision.gallery.privacy.EncryptedPrivacyVault$* { *; }
# Anyone calling System.loadLibrary("smartvision_decoder") must keep the class
# name intact because we resolve it by name in NativeBridge.
-keep class com.smartvision.gallery.decoder.bridge.NativeBridge { *; }

# --- Biometric --------------------------------------------------------------
-keep class androidx.biometric.** { *; }
-dontwarn androidx.biometric.**

# --- Coil -------------------------------------------------------------------
-keep class coil.** { *; }
-dontwarn coil.**

# --- OkHttp / Okio ----------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# --- Timber -----------------------------------------------------------------
-keep class timber.log.** { *; }
-dontwarn org.jetbrains.annotations.**

# --- ExifInterface ----------------------------------------------------------
-keep class androidx.exifinterface.** { *; }

# --- ML stubs (we don't actually ship TFLite, but the dep declarations leave
# traces; this prevents R8 from spamming warnings in release) ----------------
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**
-keep class com.alibaba.mnn.** { *; }

# --- DataStore --------------------------------------------------------------
-keep class androidx.datastore.preferences.** { *; }

# --- SplashScreen -----------------------------------------------------------
-keep class androidx.core.splashscreen.** { *; }

# --- AndroidX Fragment ------------------------------------------------------
-keep class androidx.fragment.app.** { *; }

# --- Enums (used reflectively by AppPrefs + CloudProvider + MediaFormat) ----
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# --- BiometricPrompt needs to instantiate our FragmentActivity; some OEMs'
#     custom biometric services use Class.forName on the host Activity. Keep
#     our Activity's class name.
-keep class com.smartvision.gallery.ui.MainActivity { *; }
-keep class com.smartvision.gallery.SmartVisionApp { *; }

# --- Coroutines internals (used by StackTrace recovery) ---------------------
-keep class kotlinx.coroutines.android.AndroidExceptionPreHandler { *; }
-keep class kotlinx.coroutines.android.AndroidDispatcherFactory { *; }

# --- ONNX Runtime ----------------------------------------------------------
# onnxruntime-android 1.18.0 ships a native JNI layer that resolves the Java
# side reflectively (GetMethodID on ai.onnxruntime.*). R8 obfuscation makes
# the native layer fail with "java_class == null in call to GetMethodID" → SIGABRT.
# The AAR ships NO consumer proguard rules, so the app MUST keep these classes.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# --- Keep line numbers for crash reports ------------------------------------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
