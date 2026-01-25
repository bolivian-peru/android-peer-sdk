# Proxies.sx Peer SDK for Android

[![](https://jitpack.io/v/bolivian-peru/android-peer-sdk.svg)](https://jitpack.io/#bolivian-peru/android-peer-sdk)

Android SDK for integrating bandwidth sharing into your app. Users earn money by sharing their unused mobile bandwidth while you earn proxy credits.

## Installation

### Step 1: Add JitPack repository

Add JitPack to your root `build.gradle.kts` (or `settings.gradle.kts`):

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

Or in Groovy (`build.gradle`):

```groovy
allprojects {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}
```

### Step 2: Add the dependency

In your app's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.bolivian-peru:android-peer-sdk:1.0.0")
}
```

Or in Groovy:

```groovy
dependencies {
    implementation 'com.github.bolivian-peru:android-peer-sdk:1.0.0'
}
```

## Quick Start

### 1. Initialize SDK

```kotlin
// In your Application class or main Activity
import sx.proxies.peer.ProxiesPeerSDK

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        ProxiesPeerSDK.init(
            context = this,
            apiKey = "your-developer-api-key", // Get from dashboard
            config = ProxiesPeerSDK.Config(
                userId = "optional-user-id", // Link to your user system
                onStatusChange = { status ->
                    Log.d("ProxiesSDK", "Status: $status")
                },
                onEarningsUpdate = { earnings ->
                    Log.d("ProxiesSDK", "Earned: $${earnings.totalEarnedCents / 100.0}")
                }
            )
        )
    }
}
```

### 2. Start/Stop sharing

```kotlin
val sdk = ProxiesPeerSDK.getInstance()

// Start sharing (runs as foreground service)
sdk.start()

// Stop sharing
sdk.stop()

// Check status
val isRunning = sdk.isRunning()
```

### 3. Get earnings

```kotlin
lifecycleScope.launch {
    val earnings = sdk.getEarnings()
    println("Total earned: $${earnings.totalEarnedCents / 100.0}")
    println("Traffic shared: ${earnings.totalTrafficMB} MB")
}
```

## Required Permissions

The SDK automatically adds these permissions via manifest merge:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
```

**Note:** For Android 13+, you'll need to request POST_NOTIFICATIONS permission at runtime.

## Best Practices

### When to share

- When the app is in the background
- When the device is charging
- When connected to mobile data (more valuable than WiFi)
- During idle periods in games or apps

### User consent

Always obtain clear user consent before enabling bandwidth sharing:

```kotlin
// Show consent dialog first
AlertDialog.Builder(this)
    .setTitle("Earn Money")
    .setMessage("Share your unused bandwidth to earn rewards. You control when sharing happens.")
    .setPositiveButton("Enable") { _, _ ->
        sdk.start()
    }
    .setNegativeButton("Later", null)
    .show()
```

### Battery optimization

The SDK uses minimal resources and runs as a foreground service with a persistent notification. Users can see their sharing status at all times.

## Revenue Model

| Party | Earnings per GB |
|-------|-----------------|
| User (device owner) | $0.50 |
| Developer (you) | $0.10 |

Payments are processed monthly. Minimum payout: $10.

## SDK Methods

| Method | Description |
|--------|-------------|
| `init(context, apiKey, config)` | Initialize SDK (call once) |
| `getInstance()` | Get SDK instance |
| `start()` | Start sharing service |
| `stop()` | Stop sharing service |
| `isRunning()` | Check if service is running |
| `getStatus()` | Get current status enum |
| `getEarnings()` | Get earnings (suspend function) |

## Status Values

| Status | Description |
|--------|-------------|
| `STOPPED` | Service not running |
| `CONNECTING` | Connecting to relay server |
| `CONNECTED` | Active and sharing bandwidth |
| `ERROR` | Connection error (check logs) |

## Configuration Options

```kotlin
ProxiesPeerSDK.Config(
    // Link earnings to your user system
    userId = "user-123",

    // Status change callback
    onStatusChange = { status -> },

    // Earnings update callback
    onEarningsUpdate = { earnings -> },

    // Custom relay server (optional, for testing)
    relayUrl = "wss://relay.proxies.sx",

    // Maximum bandwidth to share (MB per hour)
    maxBandwidthMBPerHour = 100,

    // Only share when charging
    onlyWhenCharging = false,

    // Only share on mobile data (not WiFi)
    mobileDataOnly = true
)
```

## Sample App

See the `/app` module for a complete sample application demonstrating:
- SDK initialization
- Start/stop sharing
- Displaying earnings
- Handling status changes
- Foreground notification

## ProGuard

If you use ProGuard/R8, the SDK includes consumer ProGuard rules automatically. No additional configuration needed.

## Troubleshooting

### "Relay connection failed"

- Check internet connectivity
- Verify API key is correct
- Check if VPN is blocking WebSocket connections

### Service stops unexpectedly

- Disable battery optimization for your app
- On MIUI/EMUI, add app to protected apps list

### Low earnings

- Earnings depend on traffic demand in user's region
- Mobile data connections are more valuable than WiFi
- Peak hours have higher demand

## Support

- Documentation: https://docs.proxies.sx/android-sdk
- Email: sdk@proxies.sx
- GitHub Issues: https://github.com/bolivian-peru/android-peer-sdk/issues

## License

MIT License - See [LICENSE](LICENSE) for details.
