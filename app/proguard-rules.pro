# Default Android rules preserve classes and method names used by native JNI entry points.
# Add narrowly scoped rules here only for additional reflective contracts.

# Rawr 17U fork: Voice Shutter's recognizer (Vosk) reaches its native library through JNA, which looks classes,
# fields and callbacks up by name at run time.
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-keep class org.vosk.** { *; }
-dontwarn java.awt.**
