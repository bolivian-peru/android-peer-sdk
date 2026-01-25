# Proxies.sx Peer SDK ProGuard rules for consumers
# These rules are automatically included in apps that use this library

# Keep the public SDK API
-keep class sx.proxies.peer.ProxiesPeerSDK { *; }
-keep class sx.proxies.peer.ProxiesPeerSDK$* { *; }

# Keep callback interfaces
-keep interface sx.proxies.peer.ProxiesPeerSDK$StatusCallback { *; }

# Keep OkHttp classes (needed for WebSocket)
-keepattributes Signature
-keepattributes *Annotation*
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# Keep Gson classes for JSON serialization
-keep class com.google.gson.** { *; }
-keepattributes *Annotation*

# Keep NanoHTTPD classes for local proxy server
-keep class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**
