# kotlinx.serialization, OkHttp and Compose ship their own consumer rules.
# BouncyCastle: only the lightweight X25519 classes are used, directly (no JCA provider lookup by name).
-dontwarn org.bouncycastle.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
# Termux terminal-emulator: libtermux.so binds JNI by class and method name.
-keep class com.termux.terminal.JNI { *; }
