# Proxies.sx Peer SDK for Android

[![](https://jitpack.io/v/bolivian-peru/android-peer-sdk.svg)](https://jitpack.io/#bolivian-peru/android-peer-sdk)

Android SDK for integrating bandwidth sharing into your app. Users earn money by sharing their unused mobile bandwidth while you earn proxy credits.

**The Android SDK is one of several ways to join the Peer Network.** See [Other Integration Paths](#other-integration-paths) below for Node.js, Docker, and Linux options.

---

> ## ⚡ Use **v1.2.0** (released 2026-05-19) — binary tunnel protocol
>
> If you are integrating today, use `v1.2.0`. It's a **drop-in API-compatible upgrade** that delivers 4–10× the customer-routing throughput per peer device by eliminating the base64+JSON envelope on the hot data path.
>
> **What the upgrade does for your devices**:
>
> - **Per-peer throughput**: typically 70–250 KB/s on v1.1.x → **600–1500 KB/s on v1.2.0** for a peer on a healthy mobile uplink.
> - **CPU on the encode loop**: ~480ms per MB on v1.1.x (Base64+Gson on mobile) → **~30ms** on v1.2.0 (raw binary frame write).
> - **Wire size**: 33% smaller (no base64 expansion).
>
> Older versions (still supported but slower):
>
> - **v1.1.4** — encrypted credential storage. Stable. Same network protocol as v1.1.x, so same throughput ceiling.
> - **v1.1.3** — `sdkVersion` constant tracked, encrypted-creds NOT yet shipped.
> - **v1.1.0 – v1.1.2** — stale `sdkVersion` string (hardcoded `"1.0.1"`). Avoid.
> - **v1.0.x** — tunnel-forwarding regression. Customer requests time out. Avoid.
>
> Bump to 1.2.0 with a 1-line gradle change:
>
> ```kotlin
> implementation("com.github.bolivian-peru:android-peer-sdk:1.2.0")
> ```
>
> The API surface is **unchanged from v1.1.x** — same `ProxiesPeerSDK.init / start / stop / getEarnings`. The upgrade is transparent.
>
> Commit history: [`afae66f2`](https://github.com/bolivian-peru/android-peer-sdk/commit/afae66f2) (v1.1.1 — reconnection + tunnel fix), [`c684da28`](https://github.com/bolivian-peru/android-peer-sdk/commit/c684da28) (v1.1.3 — sdkVersion string aligned), v1.1.4 (encrypted credential storage), **v1.2.0 (binary tunnel protocol — this release)**.

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
    implementation("com.github.bolivian-peru:android-peer-sdk:1.2.0")
}
```

Or in Groovy:

```groovy
dependencies {
    implementation 'com.github.bolivian-peru:android-peer-sdk:1.2.0'
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

### Verification requirements

| Check | Requirement |
|-------|------------|
| IP Type | Must be residential or mobile |
| ISP/ASN | Checked against datacenter and VPN databases |
| GeoIP | Country must match claimed location |
| Uptime | Minimum 1 hour online |
| Quality | Score must be >= 50/100 |

## IP Rotation (planned — not in v1.1.x)

SDK-side IP rotation via accessibility-service-driven airplane-mode toggle is on the roadmap but **does not ship in v1.1.x**. The classes `AirplaneModeAccessibilityService`, `IPRotationManager` and the methods `sdk.rotateIP()`, `sdk.isIPRotationAvailable()`, `sdk.rotateIPAsync()`, `sdk.openIPRotationSettings()` are not present in the artifact — earlier README revisions documented them in error.

The backend route `POST /v1/peer/devices/:id/rotate` exists but returns HTTP 501 for v1.1.x clients. The feature is tracked under Phase 3b of the SDK production-readiness plan and will land in a future major version.

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

## Privacy & IP Reputation (May 2026)

Your device's exit IP and identifiers are **never publicly enumerable** through the Proxies.sx API. Specifically:

- The previously-public `GET /v1/peer/board`, `/v1/peer/proxy/credentials`, `/v1/peer/proxy/devices`, `/v1/peer/stats/online`, and `relay.proxies.sx/health` endpoints have all been locked to admin-only authentication.
- Customer-facing endpoints (`gw.proxies.sx:7000` / `:7001`, `/v1/gateway/pool/availability`) expose aggregate counts only — never per-device IPs or carriers.
- Credentials persisted on your device are AES-256-GCM encrypted via Android Keystore (`androidx.security:security-crypto`) since v1.1.4.
- Refresh tokens, wallet addresses, and your developer API key are stripped from any admin-side debug response.

This means anti-bot vendors (DataDome, PerimeterX, Cloudflare Bot Manager, Akamai) cannot pre-emptively blacklist your device by scraping our pool listing — a common failure mode for shared-proxy networks. Your IP enters the customer routing pipeline cold, not on a public reputation feed.

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
| AI Agent Skill File | https://agents.proxies.sx/peer/skill.md |
| Peer Landing Page | https://agents.proxies.sx/peer/ |
| API Docs (Swagger) | https://api.proxies.sx/docs/api |
| MCP Server | https://www.npmjs.com/package/@proxies-sx/mcp-server |

## Support

- Telegram: https://t.me/proxyforai
- GitHub Issues: https://github.com/bolivian-peru/android-peer-sdk/issues

## License

MIT License - See [LICENSE](LICENSE) for details.
