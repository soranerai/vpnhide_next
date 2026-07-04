# Keep Xposed entry points — LSPosed loads these by reflection via assets/xposed_init
-keep class dev.soranerai.vpnhidenext.HookEntry { *; }
-keepnames class dev.soranerai.vpnhidenext.** { *; }

# Keep Xposed API types
-keep class de.robv.android.xposed.** { *; }
-dontwarn de.robv.android.xposed.**

# JNA — UniFFI Kotlin bindings call Rust via JNA. Native.initIDs looks up
# com.sun.jna.Pointer.peer via JNI at init time; R8 renaming these classes
# or their fields makes it fail with UnsatisfiedLinkError: "Can't obtain
# peer field ID for class com.sun.jna.Pointer". Rules lifted from the
# JNA FAQ (https://github.com/java-native-access/jna/blob/master/www/
# FrequentlyAskedQuestions.md#jna-on-android). Gobley generates the same
# rules into build/generated/uniffi/.../generated-proguard-rules.txt but
# its auto-wiring into the app's R8 configuration doesn't fire for pure
# Android (non-KMP) applications, so we keep them here directly.
-dontwarn java.awt.*
-keep class com.sun.jna.* { *; }
-keepclassmembers class * extends com.sun.jna.* { public *; }
