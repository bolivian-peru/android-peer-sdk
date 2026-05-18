# Proxies.sx Peer SDK for Android

[![](https://jitpack.io/v/bolivian-peru/android-peer-sdk.svg)](https://jitpack.io/#bolivian-peru/android-peer-sdk)

Android SDK for integrating bandwidth sharing into your app. Users earn money by sharing their unused mobile bandwidth while you earn proxy credits.

**The Android SDK is one of several ways to join the Peer Network.** See [Other Integration Paths](#other-integration-paths) below for Node.js, Docker, and Linux options.

---

> ## ⚠️ Minimum supported version: **v1.1.2**
>
> If you are integrating today, use `v1.1.2`. **Do not use v1.0.x** — those builds have a tunnel-forwarding regression (the SDK accepts CONNECTs but never forwards bytes), so every customer request times out and the device gets auto-demoted from customer routing within hours.
>
> The platform now **rejects registration from `v1.0.x` builds with HTTP 400** and an upgrade message — your app will fail to start until you bump the dependency. The fix is a 1-line gradle change:
>
> ```kotlin
> implementation("com.github.bolivian-peru:android-peer-sdk:1.1.2")
> ```
>
> Fix shipped in `v1.1.1` (commit [`afae66f2`](https://github.com/bolivian-peru/android-peer-sdk/commit/afae66f2)) — reconnection with exponential backoff + JitPack build fix. `v1.1.2` is the clean JitPack-cached build of v1.1.1 with no code changes.

---

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
    implementation("com.github.bolivian-peru:android-peer-sdk:1.1.2")
}
```

Or in Groovy:

```groovy
dependencies {
    implementation 'com.github.bolivian-peru:android-peer-sdk:1.1.2'
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
            apiKey = "psx_your_api_key", // Get from farmer.proxies.sx > Account > API Keys
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

The `apiKey` (format: `psx_...`) auto-links the device to your farmer account. Devices appear in your [farmer dashboard](https://farmer.proxies.sx/peers) immediately after connecting.

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

## Dashboard & Marketplace

Once your device is connected, manage it from the farmer dashboard:

1. **farmer.proxies.sx/peers** — See all your devices, status, IP type, country, ISP, traffic, earnings
2. **Toggle "Listed for Sale"** — List your device in the pool gateway so customers can route traffic through it
3. **Automated verification** — System checks IP quality, ISP legitimacy, VPN/proxy detection, GeoIP match
4. **Quality score** — Each device gets a 0-100 score; verified devices serve customer traffic and earn more
5. **Live peer board** — See all peers at [agents.proxies.sx/peer/board/](https://agents.proxies.sx/peer/board/)

### Verification requirements

| Check | Requirement |
|-------|------------|
| IP Type | Must be residential or mobile |
| ISP/ASN | Checked against datacenter and VPN databases |
| GeoIP | Country must match claimed location |
| Uptime | Minimum 1 hour online |
| Quality | Score must be >= 50/100 |

## IP Rotation

The SDK supports IP rotation on non-rooted Android devices via the Accessibility Service.

```kotlin
// Check if rotation available
if (sdk.isIPRotationAvailable()) {
    sdk.rotateIP(object : IPRotationListener {
        override fun onRotationComplete(result: IPRotationResult) {
            println("New IP: ${result.newIp}")
        }
    })
} else {
    // Prompt user to enable accessibility service
    sdk.openIPRotationSettings()
}

// Coroutine version
val result = sdk.rotateIPAsync()
```

Rotation toggles airplane mode on/off (10s delay) to get a new carrier IP. Requires the "Proxies IP Rotation" accessibility service to be enabled in device settings. 60-second cooldown between rotations.

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

Earnings are tiered by IP type — mobile IPs earn the most:

| IP Type | Rate Tier | Examples |
|---------|-----------|---------|
| **Mobile** | Highest | AT&T, Verizon, T-Mobile, Vodafone |
| **Residential** | Mid | Comcast, Spectrum, Cox, BT |
| **Datacenter** | Base | AWS, GCP, Azure, VPNs |

IP type is classified server-side via ASN lookup. Earnings accumulate per-GB and are paid out in USDC on Solana. Minimum payout: $5.

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
| `isIPRotationAvailable()` | Check if IP rotation is enabled |
| `rotateIP(listener)` | Trigger IP rotation via airplane mode toggle |
| `rotateIPAsync()` | Coroutine version of IP rotation |
| `openIPRotationSettings()` | Open accessibility settings for user to enable rotation |

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

## Other Integration Paths

The Android SDK is best for mobile apps. For other environments, use these alternatives:

### Node.js / Linux / VPS

Run a lightweight peer script on any machine with Node.js 18+:

```bash
# 1. Get your API key from farmer.proxies.sx > Account > API Keys

# 2. Create peer.mjs and run it
node peer.mjs
```

The script registers via `POST /v1/peer/agents/register` with your `apiKey`, connects to `wss://relay.proxies.sx`, and handles proxy requests. Full integration guide at [farmer.proxies.sx](https://farmer.proxies.sx) > Peers > SDK Integration tab.

### Docker

Run a peer node as a Docker container:

```bash
docker run -d --name proxies-peer \
  -e DEVICE_NAME=my-docker-peer \
  -e API_KEY=psx_your_key \
  --restart unless-stopped \
  node:18-slim node -e "$(curl -s https://agents.proxies.sx/peer/skill.md | ...)"
```

Or use the Node.js script in a Dockerfile. See the integration guide for the full peer.mjs script.

### AI Agents (Claude, GPT, Custom)

AI agents register programmatically:

```bash
curl -X POST https://api.proxies.sx/v1/peer/agents/register \
  -H "Content-Type: application/json" \
  -d '{"name":"my-agent","type":"claude","apiKey":"psx_your_key"}'
```

Full API reference: [agents.proxies.sx/peer/skill.md](https://agents.proxies.sx/peer/skill.md)

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
- Verify API key is correct (format: `psx_...`)
- Check if VPN is blocking WebSocket connections

### Service stops unexpectedly

- Disable battery optimization for your app
- On MIUI/EMUI, add app to protected apps list

### Low earnings

- Earnings depend on traffic demand in user's region
- Mobile data connections are more valuable than WiFi
- Peak hours have higher demand
- List your device for sale in the farmer dashboard to serve customer traffic

### Device not appearing in dashboard

- Make sure you're using an API key (`psx_...`) from your farmer account
- The `apiKey` in `init()` auto-links the device — without it, the device registers but isn't linked to your account

## Links

| Resource | URL |
|----------|-----|
| Farmer Dashboard | https://farmer.proxies.sx/peers |
| Live Peer Board | https://agents.proxies.sx/peer/board/ |
| AI Agent Skill File | https://agents.proxies.sx/peer/skill.md |
| Peer Landing Page | https://agents.proxies.sx/peer/ |
| API Docs (Swagger) | https://api.proxies.sx/docs/api |
| MCP Server | https://www.npmjs.com/package/@proxies-sx/mcp-server |

## Support

- Telegram: https://t.me/proxyforai
- GitHub Issues: https://github.com/bolivian-peru/android-peer-sdk/issues

## License

MIT License - See [LICENSE](LICENSE) for details.
