# Rules for the release build.
#
# The file exists because androidApp/build.gradle.kts has always referenced it while nothing was
# ever built with minification enabled: the pipeline only assembled debug. The first assembleRelease
# needs it, and the entries below are the ones the app actually depends on.
#
# Deliberately narrow. kotlinx-serialization and kotlinx-coroutines ship their own consumer rules
# (META-INF/proguard), so re-keeping them here would only cost size. Nothing is kept wholesale: a
# rule that pins an entire library hides the next real missing class instead of fixing it.

# Generic signatures and annotations are read at runtime by kotlinx.serialization. Stripping them
# turns a working serializer into a ClassCastException.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

# Kotlin metadata is what the serialization runtime walks to resolve serializers for generic types.
-keep class kotlin.Metadata { *; }

# Every @Serializable model is reached through a generated companion serializer whose class name is
# an implementation detail, so both sides are kept by shape rather than by name.
-keepclassmembers class com.github.terrakok.wikwok.** {
    *** Companion;
}
-keepclasseswithmembers class com.github.terrakok.wikwok.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.github.terrakok.wikwok.**$$serializer { *; }

# Enums decoded by name.
-keepclassmembers enum com.github.terrakok.wikwok.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Optional dependencies of Ktor and coroutines that never exist on Android. Suppressing these is
# safe because the code paths that reach them are guarded by runtime checks; a real missing class in
# our own code still fails the build.
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn coil3.**
