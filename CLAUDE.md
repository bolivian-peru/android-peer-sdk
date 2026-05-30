# CLAUDE.md — repo guide for AI agents

Guidance for working on the **Proxies.sx Android Peer SDK**. Read this before editing.

## What this is

An Android SDK that turns a host app into a **peer / exit node** for the
Proxies.sx residential-proxy network. The device opens a WebSocket to a relay;
the relay hands it customer requests (HTTP and CONNECT-style TCP tunnels); the
device performs them on its own network and streams bytes back. Users earn;
integrators earn proxy credits.

Because a peer is an **exit node for third-party traffic**, security is not
optional — see *Invariants* and *Security model*.

## Module layout

- `:sdk` — **the published library** (`com.github.bolivian-peru:android-peer-sdk`).
  This is the source of truth. All SDK logic lives here.
- `:app` — a **demo/harness app only**. It MUST depend on `:sdk`
  (`implementation(project(":sdk"))`) and MUST NOT contain its own copy of any
  `sx.proxies.peer.*` SDK class. (It previously carried a diverged copy that
  stored credentials in plaintext — do not reintroduce that.)

`:app` uses a distinct namespace (`sx.proxies.peer.app`) from the library
(`sx.proxies.peer`) so generated `R`/`BuildConfig`/ViewBinding classes don't
collide. The app's `applicationId` stays `sx.proxies.peer`.

### Key files (`:sdk`)

- `ProxiesPeerSDK.kt` — public API + entry point: `init / start / stop /
  isRunning / getEarnings / getDetailedEarnings / updateWallets / requestPayout`.
  Owns device registration, token refresh, earnings polling, status.
- `service/PeerProxyService.kt` — foreground service hosting the relay
  connection + local proxy. Bridges connection status back to the SDK via
  `PeerProxyService.connectionListener`.
- `network/RelayConnection.kt` — the WebSocket client and **wire protocol**
  (control messages as JSON text; tunnel data as binary frames). Reconnect /
  backoff / relay-redirect / heartbeat / tunnels live here.
- `network/BinaryTunnelCodec.kt` — pure encode/decode of the binary tunnel
  frame. Android-free and unit-tested. The relay's encoder must match it
  byte-for-byte.
- `network/EgressFilter.kt` — SSRF guard; rejects loopback/LAN/link-local/CGNAT.
- `network/LocalProxyServer.kt` — localhost-only NanoHTTPD proxy.
- `util/SecurePreferences.kt` — Keystore-backed encrypted prefs for credentials.
- `util/DebugLogger.kt` — in-memory log buffer + gated logcat output.

## Build / test

JDK 17 + Android SDK required. In this environment:

```sh
export JAVA_HOME=/Users/admin/.local/android-build-toolchain/jdk-17.0.19+10/Contents/Home
export ANDROID_HOME=/Users/admin/.local/android-build-toolchain/android-sdk
export PATH="$JAVA_HOME/bin:$PATH"

./gradlew :sdk:testDebugUnitTest     # unit tests (codec, egress, status mapping)
./gradlew :sdk:assembleRelease       # library AAR (R8 on)
./gradlew :app:assembleDebug         # demo app (proves :app builds on :sdk)
```

Unit tests are plain JVM (`sdk/src/test/...`). `testOptions.unitTests
.isReturnDefaultValues = true` makes `android.util.Log` a no-op there.

## Invariants — do not break

1. **One writer per tunnel session.** Inbound tunnel bytes for a session are
   enqueued to that session's `Channel` and drained by a *single* coroutine.
   NEVER `launch` a write coroutine per frame — concurrent writes to one
   socket reorder/corrupt the TCP stream. (This was bug C1.)
2. **`SDK_VERSION` (ProxiesPeerSDK.kt) == the gradle artifact `version`
   (sdk/build.gradle.kts) == the README version table.** Bump all three together.
3. **All target connections pass `EgressFilter.isAllowedTarget(host)`** before
   dialing (tunnel connect, HTTP proxy, `proxy_request`).
4. **Wallet / payout / token / earnings calls send credentials**
   (`Request.Builder.withAuth()`), never authenticate on `deviceId` alone —
   it's derived from ANDROID_ID and is not a secret.
5. **`:app` never duplicates SDK sources.**
6. **`BinaryTunnelCodec` and the relay encoder stay byte-compatible.** If you
   change the frame layout, change both and the codec tests.

## Wire protocol (where it lives)

`RelayConnection.handleMessage` (JSON control: `connected`, `proxy_request`,
`tunnel_connect/open/data/close`, `relay_redirect`, `heartbeat_ack`, `error`)
and `handleBinaryMessage` (binary `tunnel_data` / `tunnel_close`). Outbound:
`sendMessage` (JSON) and `encodeBinaryTunnelFrame` / `sendTunnelData` (binary).

## Conventions

- Kotlin, coroutines on `Dispatchers.IO`; host callbacks delivered on `Main`.
- Match the surrounding comment density (this codebase comments the "why").
- New sensitive values go through `SecurePreferences`, never plain prefs.
