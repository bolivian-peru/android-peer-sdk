# Android service lifecycle qualification

This harness exercises the canonical `sdk` library in an Android OS. It does not
use the duplicated SDK sources in `app`, contact a production API or relay, or
publish a package. The test APK contains a foreground activity, the actual SDK
service, Android Keystore-backed preferences and a pinned MockWebServer fixture.
Production API and relay defaults remain unchanged.

## Bounded execution

Use an otherwise unused **private AOSP emulator**, named
`psx_sdk_lifecycle_<unique suffix>`. Do not run this harness on personal, supplier
or shared test devices. The runner checks the explicit serial, AVD name and
`ranchu` emulator identity before installing or changing anything. A rooted AOSP
image is required for the fixture's firewall; a Play Store image is unsuitable.

Check disk, memory, hardware virtualization, existing ADB devices and emulator
processes first. The September 27 run used API 34 ARM64, emulator 37.1.11, two CPU
cores and 2 GiB guest RAM in a private AVD directory. Keep the SDK/AVD paths explicit.
Example provisioning, after the resource check:

```sh
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_AVD_HOME=/private/qualification/avd
export JAVA_HOME=/path/to/jdk17
sdkmanager --sdk_root="$ANDROID_HOME" "emulator" "system-images;android-34;default;arm64-v8a"
avdmanager create avd --name psx_sdk_lifecycle_example \
  --path "$ANDROID_AVD_HOME/psx_sdk_lifecycle_example.avd" \
  --package 'system-images;android-34;default;arm64-v8a' --device pixel_4
"$ANDROID_HOME/emulator/emulator" -avd psx_sdk_lifecycle_example \
  -sysdir "$ANDROID_HOME/system-images/android-34/default/arm64-v8a" \
  -port 5580 -no-window -no-snapshot -no-audio -no-boot-anim -no-metrics \
  -gpu swiftshader -memory 2048 -cores 2 -camera-back none -camera-front none \
  -dns-server 127.0.0.1
```

Run the emulator in an owned process and retain its PID. The image path must
resolve under the selected SDK; an `avdmanager` installed outside that SDK can
generate an incorrect relative image path. `-sysdir` avoids that ambiguity.
The official [emulator command-line reference](https://developer.android.com/studio/run/emulator-commandline)
describes the AVD, image, snapshot and resource options.

Build and run the existing test runner:

```sh
./gradlew :sdk:testDebugUnitTest :sdk:assembleDebugAndroidTest :sdk:assembleRelease \
  --no-daemon --max-workers=2
python3 scripts/run-android-lifecycle.py \
  --serial emulator-5580 --avd psx_sdk_lifecycle_example \
  --output /private/qualification/results
```

The runner installs only `sx.proxies.peer.test`, then rejects **that APK UID's**
off-device IPv4 and IPv6 traffic with guest firewall rules before instrumentation.
It leaves ADB and the host network alone. Both fake API and relay bind guest
`127.0.0.1`; the SDK's public-IP lookup cannot connect off-device. No real key,
account, proxy target, registration or supplier traffic is involved. Cleartext
permission and the fake service are confined to the test APK manifest/code.

Each case gets a fresh instrumentation process and a 70-second deadline. The
runner preserves app data between the two process-persistence phases, verifies a
different process PID, and records the APK hash, device fingerprint, case result,
source file hashes and dirty state, output hashes, logcat and firewall counters. `--case <methodName>` selects one
known case for debugging. It must not be used to treat a partial run as full
qualification. The process restore case requires its seed phase first.

At the end, the runner force-stops only the fixture package. Stop the private
emulator explicitly with `adb -s emulator-5580 emu kill` after verifying its AVD
identity again. Retain private receipts and hashes, not a running background
service. Do not uninstall or clear any real app's data.

## What the OS cases establish

- An already-expired token refreshes before opening a relay WebSocket, and rotated
  credentials persist in actual encrypted preferences.
- Explicit relay authentication close 4002 refreshes credentials before the
  authenticated reconnect.
- Dropping real accepted loopback TCP sockets exercises normal reconnect without
  changing device identity or refreshing an otherwise usable token. A subsequent
  SDK stop must release the local proxy listener.
- Stop while service refresh is blocked prevents a later socket dial; a late
  authentication acknowledgement cannot revive sharing after stop.
- The actual notification Stop `PendingIntent` cancels queued authentication
  recovery and destroys the service/listener.
- Moving the activity to the background leaves the foreground service running;
  framework `stopService` closes it, clears connected status and prevents its
  reconnect. A later explicit SDK start can recover the same identity.
- A controlled process exit/relaunch preserves encrypted identity and refresh
  credentials. SDK initialization remains stopped until explicit start.

The transport-loss fixture closes MockWebServer 4.12's accepted sockets through a
small reflection helper. That avoids its server-side WebSocket `cancel()` null
client-call limitation and avoids listener-rebind/TIME_WAIT failures. No SDK
production seam is needed. The real Android service, SDK and credentials logic
remain in the path. The fixture sends application auth acknowledgements but does
not validate cryptographic JWT signatures or production policy.

## Regressions found on 0528b0c1

The OS suite passed seven cases and failed two against the unchanged production
sources of `0528b0c17befb7ec41456be2e99e0a103d6302f9`:

1. After transport reconnect, `startLocalProxy` replaced the existing server
   reference with a second server. The bind failed and SDK stop lost the reference
   required to close the original listener. The fixture observed port 8888 still
   accepting connections after service destruction.
2. Framework service destruction closed the socket/listener but left SDK status
   `CONNECTED`. The fixture observed that contradiction while the app process
   remained alive, including a bounded five-second wait for `STOPPED`.

The narrow service change reuses the listener already owned by the service and
reports `STOPPED` after destruction. OS destruction does not call
`sharingStopped`, revoke consent, or change any server/device policy. Existing
connection generation fencing and explicit user-stop handling remain in place.

## September 27 qualification result

The repaired source passed all **9 Android OS cases** on the isolated API 34
ARM64 AOSP emulator and all **17 JVM tests**. Replaying the identical final OS
fixture against unchanged `0528b0c1` production sources still failed exactly the
two regressions above (7 passed, 2 failed). The status assertion waits up to five
seconds for `STOPPED`, because disappearance from the framework service list and
listener closure can precede completion of `onDestroy`.

The passing service-destruction case also waits through the reconnect interval,
then explicitly starts the SDK again and verifies the original identity and
resource cleanup. APK/source hashes, raw per-case output, build logs and earlier
failed attempts are retained in the private qualification packet. Tests ran from
the working tree before its commit; the receipt records both that state and the
actual source file hashes. Release AAR assembly succeeded. Nothing was published
or installed in a supplier app.

## Remaining qualification

JVM tests, Android emulator tests and physical farmer-app acceptance are distinct.
This API 34 emulator does not establish OEM battery management, physical radio or
Wi-Fi-to-cellular handoff, process resurrection under memory pressure, all Android
versions, background-start permissions in every host app, a 24-hour expiry cycle,
or supplier app binary parity. Socket loss here is a controlled network-failure
input; it is not a physical network-switch test.

The two process phases use explicit force-stop/relaunch of the fixture and a
fresh SDK instance. They do not prove a host application's consent behavior or
automatic recovery after arbitrary OS process death. SDK initialization alone
does not start sharing; host applications must still honor their own consent UI.

Before publication or rollout, qualify the exact candidate in a consenting
internal/physical host app: real service/notification/background transitions,
actual network switches, expired/rejected tokens, delayed refresh plus stop,
process recreation, and the same original identity. A successful restart of an
existing supplier 1.3.1 installation does not test this candidate. No result here
lifts production health/original-cohort gates or authorizes deployment.
