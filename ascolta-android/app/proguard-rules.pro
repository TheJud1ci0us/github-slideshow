# LingoPod release build (R8). Classes reached from native code or by reflection are kept;
# everything else is shrunk and renamed.

# whisper.cpp JNI (native code calls back into these, and finds them by name).
-keep class com.ascolta.app.whisper.WhisperLib { *; }
-keep class com.ascolta.app.whisper.WhisperSegment { *; }
# JNI looks up these callbacks by literal name; Kotlin never calls them directly.
-keep interface com.ascolta.app.whisper.TranscribeListener { *; }
-keep class * implements com.ascolta.app.whisper.TranscribeListener { *; }

-keepclasseswithmembernames class * { native <methods>; }

# sherpa-onnx (Kotlin API used from its JNI) and ONNX Runtime's Java API.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class ai.onnxruntime.** { *; }

# Kuromoji (Japanese dictionary) loads its resources by class.
-keep class com.atilika.kuromoji.** { *; }
-dontwarn com.atilika.kuromoji.**

# kotlinx.serialization (transcripts are stored as JSON).
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.ascolta.app.** { *** Companion; }
-keepclasseswithmembers class com.ascolta.app.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.ascolta.app.**$$serializer { *; }

# Room entities and DAOs are generated; keep their names for clarity in crash reports.
-keep class com.ascolta.app.data.*Entity { *; }

# WorkManager workers are created by class name.
-keep class * extends androidx.work.ListenableWorker { <init>(...); }

# Media3 session service is declared in the manifest.
-keep class com.ascolta.app.player.PlaybackService { *; }

# Google Play Billing / Integrity.
-keep class com.android.vending.billing.** { *; }
-dontwarn com.google.android.play.core.**

# Readable stack traces in Play Console (the mapping file is uploaded with the bundle).
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
