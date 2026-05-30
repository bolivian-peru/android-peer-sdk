# Proxies.sx Peer SDK for Android

[![](https://jitpack.io/v/bolivian-peru/android-peer-sdk.svg)](https://jitpack.io/#bolivian-peru/android-peer-sdk)

Android SDK for integrating bandwidth sharing into your app. Users earn money by sharing their unused mobile bandwidth while you earn proxy credits.

**The Android SDK is one of several ways to join the Peer Network.** See [Other Integration Paths](#other-integration-paths) below for Node.js, Docker, and Linux options.

---

> ## ⚡ Use **v1.3.1** — correctness & security hardening
>
> If you are integrating today, use `v1.3.1`. It is a drop-in patch over v1.3.0 (same API, same wire format, same geo-routing) that fixes a tunnel data-corruption bug and closes several security gaps:
>
> - **Tunnel ordering fix (critical).** Inbound tunnel bytes are now written by a single per-session writer. v1.3.0 and earlier dispatched each frame to a thread pool, so writes for one connection could race and reorder — corrupting TLS/HTTP streams under load. Upgrade is strongly recommended.
> - **SSRF egress filtering.** Peers refuse to proxy to loopback, RFC-1918/LAN, link-local (incl. `169.254.169.254` cloud metadata), and CGNAT targets.
> - **Authenticated account calls.** Wallet, payout, token-refresh, and earnings requests now send the API key + device token (previously authenticated on the non-secret `deviceId` alone).
> - **Working status callbacks.** `onStatusChange(CONNECTED)` / `isRunning()` now actually fire once the relay connects (the service→SDK status bridge was missing in v1.3.0).
> - **Reliability:** idle-tunnel reaper + tunnel cap, network-aware reconnect (no more permanent give-up after an outage), periodic wake-lock renewal, throttled notification updates, inbound frame-size cap, earnings-poll backoff.
> - **First-party TLS hardened.** Cleartext is disabled for `*.proxies.sx` (control traffic); cleartext stays available only for arbitrary proxy egress.
>
> v1.3.0 added **server-controlled relay routing** so each peer connects to the geographically nearest relay instead of the hardcoded EU host:
>
> - **Geo-assigned relay at registration** — the `/peer/register` response now carries a `relay` field (US/LATAM → `wss://relay-us.proxies.sx`, everyone else → `wss://relay.proxies.sx`). The SDK connects to that relay and persists it. A device in Brazil or the US no longer tunnels every byte across the Atlantic — which was collapsing single-stream throughput to ~0.3 MB/s regardless of the device's real uplink.
> - **Runtime relay redirect** — after advertising `supportsRelayRedirect: true` in `device_info`, the relay can send `relay_redirect` to migrate the peer to a nearer relay at runtime. The fleet auto-migrates when a new region comes online — no app update needed. Guards: only `*.proxies.sx` wss targets are honored, a 60s anti-flap interval prevents ping-ponging, and an explicit `relayUrl` operator pin disables redirects entirely.
>
> Throughput targets (typical mobile uplink, text-heavy scrape workload):
>
> | SDK | KB/s | CPU per MB encode | Relay |
> |---|---|---|---|
> | v1.1.x | 70–250 | ~480 ms | EU only (hardcoded) |
> | v1.2.0 | 600–1500 | ~30 ms | EU only (hardcoded) |
> | v1.2.1 | 1500–4000 | ~30 ms | EU only (hardcoded) |
> | v1.3.0 | 1500–4000 | ~30 ms | nearest region (geo-routed) |
> | **v1.3.1** | **1500–4000** | ~30 ms | **nearest region (geo-routed)** |
>
> Older versions:
>
> - **v1.3.0** — multi-region relay routing. Has the tunnel-ordering bug fixed in v1.3.1; prefer 1.3.1.
> - **v1.2.1** — compression + 256 KB frames. Stable, but pins every peer to the EU relay.
> - **v1.2.0** — binary tunnel protocol. Stable. Same wire format as v1.2.1/v1.3.0 — interoperable.
> - **v1.1.4** — encrypted credential storage. Stable but slow JSON+base64 path.
> - **v1.1.3** — `sdkVersion` constant tracked, encrypted-creds NOT yet shipped.
> - **v1.1.0 – v1.1.2** — stale `sdkVersion` string. Avoid.
> - **v1.0.x** — tunnel-forwarding regression. Customer requests time out. Avoid.
>
> Bump to 1.3.1 with a 1-line gradle change:
>
> ```kotlin
> implementation("com.github.bolivian-peru:android-peer-sdk:1.3.1")
> ```
>
> The API surface is **unchanged** — same `ProxiesPeerSDK.init / start / stop / getEarnings`. Relay routing is fully automatic; `Config.relayUrl` stays optional (leave unset to geo-route). The upgrade is transparent, and `onStatusChange`/`isRunning()` now report `CONNECTED` correctly.
>
> Commit history: [`c684da28`](https://github.com/bolivian-peru/android-peer-sdk/commit/c684da28) (v1.1.3 — sdkVersion string aligned), v1.1.4 (encrypted credential storage), [`b4e315fc`](https://github.com/bolivian-peru/android-peer-sdk/commit/b4e315fc) (v1.2.0 — binary tunnel protocol), v1.2.1 (compression + larger frames + frame size cap), v1.3.0 (multi-region relay routing), **v1.3.1 (tunnel-ordering fix + security hardening — this release)**.

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
    implementation("com.github.bolivian-peru:android-peer-sdk:1.3.1")
}
```

Or in Groovy:

```groovy
dependencies {
    implementation 'com.github.bolivian-peru:android-peer-sdk:1.3.1'
}
```

### Requirements

| | |
|---|---|
| Min SDK | API 24 (Android 7.0) |
| Compile / target SDK | API 34 |
| Language | Kotlin (Java-interop via `@JvmStatic` entry points) |
| Runtime | Runs as a foreground service (`dataSync` type) with a wake lock |
| Transitive deps | OkHttp, Gson, NanoHTTPD, AndroidX security-crypto, coroutines (pulled in automatically) |

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

## Architecture

```
ProxiesPeerSDK.init() ──► register device (POST /v1/peer/register)
        │                   └─► geo-assigned relay URL (persisted)
        ▼
ProxiesPeerSDK.start() ──► PeerProxyService (foreground service, wake lock)
                                │
                                ├─► RelayConnection ──WSS──► nearest relay
                                │       │
                                │       ├─ control msgs (JSON text): connected,
                                │       │  proxy_request, tunnel_connect/open/
                                │       │  data/close, relay_redirect, heartbeat
                                │       │
                                │       └─ tunnel data (binary frames) ⇄ target
                                │          via one ordered writer per session
                                │
                                └─► LocalProxyServer (127.0.0.1:8888)
```

Data flow in one line: **relay → RelayConnection → target socket** (customer traffic the device performs on its own network), with bytes streamed back the same way. `LocalProxyServer` is a localhost-only HTTP entry point (bound to `127.0.0.1`, never exposed off-device) for host apps that want to route their *own* requests through the same relay path.

- **Registration** returns a per-device token and a geo-assigned relay
  (`relay-us` for US/LATAM, EU otherwise). The relay can later migrate the
  peer with `relay_redirect` (only to `*.proxies.sx`, ≥60s apart, disabled when
  you pin `Config.relayUrl`).
- **Binary tunnel protocol** (v1.2.0+): tunnel payloads ship as raw WebSocket
  binary frames — `[type][sidLen][sessionId][payload]` — instead of base64+JSON,
  for 4–10× throughput. The codec lives in `BinaryTunnelCodec`.
- **Ordering guarantee** (v1.3.1): each tunnel session has exactly one writer
  coroutine, so inbound bytes reach the target socket in the order the relay
  sent them.
- **Credentials** (device id/token) are stored in Keystore-backed
  `EncryptedSharedPreferences` (v1.1.4+).

The library is the `:sdk` Gradle module; the `:app` module is a demo harness
that depends on it. See [CLAUDE.md](CLAUDE.md) for the developer/agent map.

### Wire protocol reference

The peer↔relay link is a single WebSocket (`wss://…proxies.sx`). Control
messages are JSON text `{ "type": …, "payload": … }`; tunnel data uses raw
binary frames once the relay acks binary mode.

| Direction | `type` | Purpose |
|---|---|---|
| relay → peer | `connected` | Handshake ack; carries `deviceId` and observed `ip`. Enables binary mode. |
| relay → peer | `proxy_request` | One-shot HTTP request to perform and return. |
| relay → peer | `proxy_http_request` | HTTP request answered over a tunnel session. |
| relay → peer | `tunnel_connect` | Open a TCP tunnel to `host:port` (CONNECT-style). |
| relay → peer | `tunnel_data` / `tunnel_close` | Bytes for / close of a tunnel (JSON fallback). |
| relay → peer | `relay_redirect` | Migrate to a nearer relay (`*.proxies.sx` only, ≥60 s apart). |
| peer → relay | `device_info` | Country, carrier, `protocol: "binary-v1"`, `supportsRelayRedirect`, `sdkVersion`. |
| peer → relay | `heartbeat` | Keep-alive every 30 s. |
| peer → relay | `tunnel_data` / `tunnel_closed` | Bytes from / close of a tunnel. |

**Binary tunnel frame** (`BinaryTunnelCodec`, byte-for-byte with the relay):

```
┌────────┬─────────┬──────────────────┬───────────────────────┐
│  [0]   │   [1]   │   [2 .. 2+L)      │   [2+L .. end)         │
│  type  │  sidLen │   sessionId (L)   │   payload (raw bytes)  │
└────────┴─────────┴──────────────────┴───────────────────────┘
  type:    0x01 = tunnel_data, 0x03 = tunnel_close
  sidLen:  UTF-8 byte length of sessionId (≤ 255)
```

## Security model & limitations

A peer is an **exit node for third-party traffic**. Understand this before shipping:

- **Egress filtering.** The SDK refuses to proxy to loopback, RFC-1918/LAN,
  link-local (incl. `169.254.169.254` cloud metadata), CGNAT, any-local, and
  multicast addresses (`EgressFilter`). This prevents the relay or a malicious
  customer request from using the device as an SSRF pivot into the user's home
  or corporate network. It does **not** make the device safe to run on a
  sensitive/internal network — treat a peer like any open egress proxy.
- **Account API auth.** Wallet, payout, token, and earnings calls send your
  API key and the device token. The `deviceId` (derived from `ANDROID_ID`) is
  **not** a secret and must never be the sole authenticator server-side.
- **Transport.** Control/API traffic to `*.proxies.sx` is TLS-only (cleartext
  disabled in the network security config). Certificate pinning hooks are
  present but commented until pins are provisioned. Cleartext remains permitted
  for arbitrary proxy egress (required for HTTP customer targets).
- **Consent & legality.** Always obtain explicit, informed user consent (see
  *User consent* below). Operating an exit node may carry legal/contractual
  obligations in some jurisdictions and on some carrier networks.
- **Credential storage.** Sensitive values use encrypted prefs; never log them.
  Verbose/debug logcat (which can include proxied target URLs) is gated behind
  `Log.isLoggable(...)` and off by default in release builds.

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

## IP Rotation (planned — not shipped as of v1.3.1)

SDK-side IP rotation via accessibility-service-driven airplane-mode toggle is on the roadmap but **does not ship in any 1.x release, including v1.3.1**. The classes `AirplaneModeAccessibilityService`, `IPRotationManager` and the methods `sdk.rotateIP()`, `sdk.isIPRotationAvailable()`, `sdk.rotateIPAsync()`, `sdk.openIPRotationSettings()` are not present in the artifact — earlier README revisions documented them in error.

The backend route `POST /v1/peer/devices/:id/rotate` exists but returns HTTP 501 for 1.x clients. The feature is tracked under Phase 3b of the SDK production-readiness plan and will land in a future major version.

## Required Permissions

The SDK automatically adds these permissions via manifest merge:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.READ_PHONE_STATE" />
```

**Runtime permissions you must request yourself:**

- **`READ_PHONE_STATE`** (dangerous permission) — used to read the carrier
  name and network country for relay routing/quality. If you don't request it
  at runtime, the SDK silently falls back to the device locale (less accurate
  country, carrier reported as `Unknown`). The SDK still works without it.
- **`POST_NOTIFICATIONS`** (Android 13+) — required for the foreground-service
  notification to be visible. Request at runtime.

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
| `getEarnings()` | Get earnings summary (suspend) |
| `getDetailedEarnings()` | Get earnings + payout/wallet detail (suspend) |
| `refreshEarningsNow()` | Force an immediate earnings refresh |
| `updateWallets(usdt, btc, sol)` | Set payout wallet addresses (suspend) |
| `requestPayout(currency)` | Request a payout (suspend) |

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
    // Link earnings to your user system (optional)
    userId = "user-123",

    // Status change callback (delivered on the main thread)
    onStatusChange = { status -> },

    // Earnings update callback (auto-polled ~every 60s)
    onEarningsUpdate = { earnings -> },

    // Operator relay pin (optional). Leave unset to let the platform
    // geo-route this device to the nearest relay and migrate it at runtime.
    // Set this ONLY to force a specific relay (disables geo-routing AND
    // ignores all runtime relay_redirects).
    relayUrl = null,

    // Override the API base URL (optional; defaults to https://api.proxies.sx/v1)
    apiUrl = "https://api.proxies.sx/v1",
)
```

> The full set of `Config` fields is exactly: `apiUrl`, `relayUrl`, `userId`,
> `onEarningsUpdate`, `onStatusChange`. (Earlier README revisions listed
> `maxBandwidthMBPerHour` / `onlyWhenCharging` / `mobileDataOnly` — those have
> never existed in the artifact.)

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

## Repository layout

For contributors and AI agents working in this repo. The deeper map,
build/test commands, and the invariants you must not break live in
[CLAUDE.md](CLAUDE.md).

| Path | What it is |
|---|---|
| `sdk/` | **The published library** (source of truth). |
| `sdk/src/main/java/sx/proxies/peer/ProxiesPeerSDK.kt` | Public API + registration / earnings / status. |
| `…/service/PeerProxyService.kt` | Foreground service; hosts the relay connection. |
| `…/network/RelayConnection.kt` | WebSocket client + protocol + tunnels + reconnect. |
| `…/network/BinaryTunnelCodec.kt` | Pure binary-frame encode/decode (unit-tested). |
| `…/network/EgressFilter.kt` | SSRF guard (blocks loopback/LAN/metadata). |
| `…/network/LocalProxyServer.kt` | Localhost-only HTTP entry point. |
| `…/util/SecurePreferences.kt` | Keystore-encrypted credential storage. |
| `sdk/src/test/` | JVM unit tests (codec, egress, status mapping). |
| `app/` | Demo harness app — **depends on `:sdk`, never copies it**. |

```sh
# Build + test (JDK 17 + Android SDK on PATH)
./gradlew :sdk:testDebugUnitTest    # unit tests
./gradlew :sdk:assembleRelease      # library AAR
./gradlew :app:assembleDebug        # demo app
```

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
