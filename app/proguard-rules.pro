# Proxies.sx Peer SDK ProGuard rules

# Keep SDK public API
-keep class sx.proxies.peer.ProxiesPeerSDK { *; }
-keep class sx.proxies.peer.ProxiesPeerSDK$* { *; }

# Keep data classes
-keep class sx.proxies.peer.service.ProxyRequest { *; }
-keep class sx.proxies.peer.service.ProxyResponse { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# NanoHTTPD
-keep class fi.iki.elonen.** { *; }

# WebSocket
-keep class okhttp3.internal.ws.** { *; }
