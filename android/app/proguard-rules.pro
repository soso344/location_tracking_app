# Flutter + Kotlin rules
-keep class io.flutter.** { *; }
-keep class kotlin.** { *; }
-dontwarn io.flutter.embedding.**
-dontwarn kotlin.**

# Keep WorkManager stuff
-keep class androidx.work.** { *; }
-dontwarn androidx.work.**

# Keep Room entities & DAO
-keep class androidx.room.** { *; }
-dontwarn androidx.room.**

# Keep Google Play Services
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**

# Optional: prevent obfuscating models if using reflection
-keep class com.example.location_tracking_app.** { *; }
