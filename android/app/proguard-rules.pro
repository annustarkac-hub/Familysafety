# --- kotlinx.serialization ---
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.familysafety.app.**$$serializer { *; }
-keepclassmembers class com.familysafety.app.** { *** Companion; }
-keepclasseswithmembers class com.familysafety.app.** { kotlinx.serialization.KSerializer serializer(...); }

# --- Retrofit (suspend functions + generic signatures) ---
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keep interface com.familysafety.app.data.network.ApiService { *; }
-keep interface com.familysafety.app.data.network.RefreshApi { *; }
-dontwarn retrofit2.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- WebRTC (JNI looks up Java classes/methods by name) ---
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# --- Strip verbose logging in release ---
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
