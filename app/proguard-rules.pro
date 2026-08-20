# ──────────────────────────────────────────────────────────────
# ProGuard / R8 rules for ctrlB Mobile
# ──────────────────────────────────────────────────────────────

# ── Kotlinx Serialization ────────────────────────────────────
# Keep @Serializable classes and their generated serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class ai.ctrlb.mobile.**$$serializer { *; }
-keepclassmembers class ai.ctrlb.mobile.** {
    *** Companion;
}
-keepclasseswithmembers class ai.ctrlb.mobile.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ── Clerk SDK ────────────────────────────────────────────────
-keep class com.clerk.** { *; }
-dontwarn com.clerk.**

# ── AndroidX / Jetpack ──────────────────────────────────────
-keep class androidx.datastore.** { *; }
-keep class androidx.work.** { *; }
