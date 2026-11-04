# GuiaMed ProGuard Rules

# Keep Tesseract native libraries
-keep class com.rmtheis.tessandroid.** { *; }
-keep class com.googlecode.tesseract.android.** { *; }
-dontwarn com.rmtheis.tessandroid.**
-dontwarn com.googlecode.tesseract.android.**

# Keep Retrofit/Gson
-keep class com.squareup.retrofit2.** { *; }
-keep class com.google.gson.** { *; }
-dontwarn com.squareup.retrofit2.**
-dontwarn com.google.gson.**

# Keep OkHttp
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**

# Keep Kotlin coroutines
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# Keep CameraX
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# Keep PDF Viewer
-keep class com.github.barteksc.pdfviewer.** { *; }
-dontwarn com.github.barteksc.pdfviewer.**

# Keep Material Components
-keep class com.google.android.material.** { *; }
-dontwarn com.google.android.material.**

# Keep app classes
-keep class com.guiamed.** { *; }

# Keep Parcelable implementations
-keep class * implements android.os.Parcelable { *; }

# Keep enum values
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}