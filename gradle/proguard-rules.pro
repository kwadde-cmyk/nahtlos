# Manifest-Komponenten behält R8 über die Manifest-Zusammenführung.
# Die Regeln hier sichern Reflexion: Serialisierung, Hilt, Room, Media3, Krypto.

-keepattributes *Annotation*,InnerClasses,Signature,EnclosingMethod,RuntimeVisibleAnnotations

# kotlinx.serialization
-keep,includedescriptorclasses class de.jenz.nahtlos.**$$serializer { *; }
-keepclassmembers class de.jenz.nahtlos.** {
    *** Companion;
}
-keepclasseswithmembers class de.jenz.nahtlos.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}

# Room. Die AAR bringt eigene Consumer-Rules mit; das hier fängt die eigenen Entities ab.
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase {
    *** companion;
}
-dontwarn androidx.room.paging.**

# Hilt / Dagger
-dontwarn dagger.hilt.**
-dontwarn dagger.internal.**
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# Media3: Session-Dienst und ExoPlayer werden per Manifest und Reflexion angesprochen.
-keep class androidx.media3.session.** { *; }
-keep class androidx.media3.exoplayer.** { *; }
-keep class androidx.media3.common.** { *; }
-dontwarn androidx.media3.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Ktor-Client. Heute spricht das Handy über OkHttp; die Regeln bleiben, falls der Client später dazukommt.
-keep class io.ktor.client.** { *; }
-keep class io.ktor.serialization.** { *; }
-dontwarn io.ktor.**
-dontwarn kotlinx.coroutines.**

# NIP-44 und secp256k1 sind reines Kotlin. R8 soll die Chiffre nicht umbauen.
-keep class de.jenz.nahtlos.wallet.** { *; }

# EncryptedSharedPreferences / Tink
-dontwarn com.google.crypto.tink.**
-keep class com.google.crypto.tink.** { *; }
-keep class androidx.security.crypto.** { *; }

# android.car liegt auf dem AAOS-Gerät, nicht im APK.
-dontwarn android.car.**

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
