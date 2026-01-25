# ProGuard rules for Proxies.sx Peer SDK

# Keep the SDK public API
-keep class sx.proxies.peer.** { *; }

# Keep WebSocket and HTTP classes
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Keep Gson for JSON
-keep class com.google.gson.** { *; }
