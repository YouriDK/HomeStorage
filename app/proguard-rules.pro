# Most of the stack (Compose, Hilt, Room, Coil, media3, kotlinx.serialization)
# ships its own consumer R8 rules; only the gaps are listed here.

# --- cryptolib (M8 vault) ---
# The masterkey file is parsed by gson through reflection on this model's
# fields; renaming them would silently break vault unlock.
-keep class org.cryptomator.cryptolib.common.MasterkeyFile { *; }
# CryptorProvider implementations are discovered via ServiceLoader.
-keep class * implements org.cryptomator.cryptolib.api.CryptorProvider

# --- gson (transitive of cryptolib, only used for MasterkeyFile) ---
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.Unsafe

# --- transitive noise: desktop-only references that never run on Android ---
-dontwarn org.slf4j.**
-dontwarn org.bouncycastle.jsse.**
-dontwarn javax.naming.**
