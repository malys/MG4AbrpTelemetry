# ABRP_Uploader — R8 keep rules.
#
# CarPropertyAdapter reaches the car through reflection only (android.car is not on the
# compile classpath), so R8 cannot see those uses. Anything it reflects on must be kept
# by name or the release build fails silently at runtime, on a vehicle.

# Reflected via Class.forName in CarPropertyAdapter.
-keep class android.car.** { *; }

# The adapter itself: it defines the dynamic-proxy callback interfaces the framework
# invokes back into, which R8 cannot trace.
-keep class com.leonkernan.abrp_uploader.CarPropertyAdapter { *; }
-keep interface com.leonkernan.abrp_uploader.CarPropertyAdapter$* { *; }

# Components named from the manifest.
-keep class com.leonkernan.abrp_uploader.AbrpUploadService { *; }
-keep class com.leonkernan.abrp_uploader.BootReceiver { *; }

# EncryptedSharedPreferences (androidx.security) pulls Tink in via reflection.
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# Readable crash stack traces from a release build.
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
