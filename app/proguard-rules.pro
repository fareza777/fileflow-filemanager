# Keep Room generated implementations.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**

# Hilt / Dagger generated components are looked up reflectively by name.
-keep,allowobfuscation @interface dagger.hilt.**

# kotlinx.serialization: keep generated serializers for our spec models.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.fileflow.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.fileflow.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# WorkManager instantiates workers reflectively.
-keep class * extends androidx.work.ListenableWorker { <init>(...); }

# Google Mobile Ads
-keep class com.google.android.gms.ads.** { *; }
-dontwarn com.google.android.gms.**

# Coil
-dontwarn okio.**
-dontwarn okhttp3.**

# Keep line numbers so Play Vitals crashes stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
