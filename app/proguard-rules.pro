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

# SMB (jcifs-ng): the SMB1/SMB2 dialect tables and the properties file are
# loaded by name, and its crypto goes through Bouncy Castle providers that R8
# cannot see being used.
-keep class jcifs.** { *; }
-dontwarn jcifs.**
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-keep class org.slf4j.** { *; }
-dontwarn org.slf4j.**

# SFTP (JSch): ciphers, key exchange and MAC implementations are named in
# strings and instantiated reflectively.
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**

# Shizuku: the API talks to a service in another process through generated AIDL,
# and the provider is looked up by name from the manifest.
-keep class rikka.shizuku.** { *; }
-keep class dev.rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**
-dontwarn dev.rikka.shizuku.**

# Keep line numbers so Play Vitals crashes stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
