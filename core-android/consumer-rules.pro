# kotlinx.serialization generates serializer classes next to @Serializable types.
-keepattributes *Annotation*,InnerClasses,Signature,EnclosingMethod
-keep,includedescriptorclasses class de.jenz.nahtlos.**$$serializer { *; }
-keepclassmembers class de.jenz.nahtlos.** {
    *** Companion;
}
-keepclasseswithmembers class de.jenz.nahtlos.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-dontnote kotlinx.serialization.**
-keep class kotlinx.serialization.** { *; }
-keep class androidx.media3.exoplayer.hls.** { *; }
-keep class androidx.media3.extractor.** { *; }
-keep class androidx.media3.container.** { *; }
-keep class androidx.media3.datasource.** { *; }
-keep class androidx.media3.database.** { *; }
-keep class com.google.common.collect.** { *; }
-keep class com.google.common.util.concurrent.** { *; }
-keep class org.bouncycastle.crypto.hpke.** { *; }
-keep class org.bouncycastle.crypto.agreement.** { *; }
-dontwarn org.bouncycastle.**
