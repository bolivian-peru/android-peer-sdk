#!/usr/bin/env python3
"""Run only the owned, isolated AOSP emulator fixture. Never targets physical devices."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--avd", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--case", help="Run one named case for a bounded regression check")
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial) or not args.avd.startswith("psx_sdk_lifecycle_"):
        parser.error("Only an explicitly named private lifecycle emulator is allowed")
    os.umask(0o077)
    args.output.mkdir(parents=True, exist_ok=True)
    root = Path(__file__).resolve().parent.parent
    sdk = Path(os.environ["ANDROID_HOME"])
    adb = [str(sdk / "platform-tools/adb"), "-s", args.serial]

    def run(*command, timeout=15, check=True):
        result = subprocess.run(adb + list(command), capture_output=True, text=True, timeout=timeout)
        if check and result.returncode:
            raise RuntimeError("ADB fixture command failed: " + " ".join(command) + "\n" + result.stderr)
        return result

    identity = {
        "serial": args.serial,
        "avd": run("emu", "avd", "name").stdout.splitlines()[0],
        "qemu": run("shell", "getprop", "ro.kernel.qemu").stdout.strip(),
        "hardware": run("shell", "getprop", "ro.hardware").stdout.strip(),
        "fingerprint": run("shell", "getprop", "ro.build.fingerprint").stdout.strip(),
        "api": run("shell", "getprop", "ro.build.version.sdk").stdout.strip(),
    }
    if identity["avd"] != args.avd or identity["qemu"] != "1" or identity["hardware"] != "ranchu":
        raise RuntimeError("Emulator identity mismatch; no install or device mutation performed")
    apk = root / "sdk/build/outputs/apk/androidTest/debug/sdk-debug-androidTest.apk"
    if not apk.is_file():
        raise RuntimeError("Build :sdk:assembleDebugAndroidTest first")
    package = "sx.proxies.peer.test"
    run("root")
    run("wait-for-device", timeout=20)
    run("install", "-r", "-t", str(apk), timeout=45)
    info = run("shell", "pm", "list", "packages", "-U", package).stdout.strip()
    match = re.fullmatch(r"package:sx\.proxies\.peer\.test uid:(\d+)", info)
    if not match or int(match.group(1)) < 10000:
        raise RuntimeError("Cannot establish fixture package UID")
    uid = match.group(1)
    # Only this test APK's UID is affected. All its off-device IPv4/IPv6 traffic
    # is rejected, including the SDK's public-IP lookup. API/relay fixtures bind
    # to guest loopback. ADB and unrelated host/device processes are untouched.
    for table in ["iptables", "ip6tables"]:
        rule = ["OUTPUT", "-m", "owner", "--uid-owner", uid, "!", "-o", "lo", "-j", "REJECT"]
        if run("shell", table, "-C", *rule, check=False).returncode:
            run("shell", table, "-I", "OUTPUT", "1", *rule[1:])
        run("shell", table, "-C", *rule)
    run("shell", "pm", "grant", package, "android.permission.POST_NOTIFICATIONS")
    cases = [("SdkLifecycleInstrumentedTest", name) for name in [
        "expiredTokenRefreshesBeforeAnySocketAndPersistsRotation",
        "explicitAuthCloseRefreshesBeforeAuthenticatedReconnect",
        "transportLossReconnectsAndStopReleasesTheOnlyProxyListener",
        "stopDuringServiceRefreshCannotStartASocketLater",
        "stopAfterTransportOpenFencesLateAuthenticationAck",
        "notificationStopCancelsQueuedAuthRecovery",
        "backgroundServiceDestroyClosesSocketsAndProxy",
    ]] + [("ProcessPersistenceInstrumentedTest", name) for name in [
        "seedEncryptedIdentityBeforeProcessExit",
        "newProcessRetainsIdentityAndWaitsForExplicitStart",
    ]]
    if args.case:
        cases = [case for case in cases if case[1] == args.case]
        if not cases:
            raise RuntimeError("Unknown fixture case")
    receipt = {
        "startedAt": time.time(), "identity": identity, "packageUid": uid,
        "apkSha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
        "sourceCommit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip(),
        "sourceDirty": bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=root, text=True)),
        "sourceFileSha256": {
            str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest()
            for path in sorted(list((root / "sdk/src").rglob("*")) +
                               [root / "sdk/build.gradle.kts", Path(__file__).resolve()]) if path.is_file()
        },
        "isolation": "Test UID off-device IPv4 and IPv6 rejected; loopback API/relay only",
        "cases": [],
    }
    try:
        for class_name, name in cases:
            run("shell", "am", "force-stop", package)
            run("logcat", "-c")
            started = time.monotonic()
            selector = "sx.proxies.peer.lifecycle." + class_name + "#" + name
            result = run("shell", "am", "instrument", "-w", "-r", "-e", "isolatedLoopback", "true",
                         "-e", "class", selector, package + "/androidx.test.runner.AndroidJUnitRunner",
                         timeout=70, check=False)
            output = result.stdout + result.stderr
            (args.output / (name + ".txt")).write_text(output)
            passed = result.returncode == 0 and "OK (1 test)" in output and "FAILURES!!!" not in output
            row = {"case": name, "passed": passed, "seconds": round(time.monotonic() - started, 3),
                   "exitCode": result.returncode, "outputSha256": hashlib.sha256(output.encode()).hexdigest()}
            receipt["cases"].append(row)
            (args.output / (name + "-logcat.txt")).write_text(run("logcat", "-d", "-v", "threadtime").stdout)
            print(json.dumps(row), flush=True)
    finally:
        run("shell", "am", "force-stop", package, check=False)
        receipt["finishedAt"] = time.time()
        receipt["firewall"] = {table: run("shell", table, "-L", "OUTPUT", "-n", "-v").stdout
                               for table in ["iptables", "ip6tables"]}
        (args.output / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    return 0 if all(row["passed"] for row in receipt["cases"]) else 1


if __name__ == "__main__":
    raise SystemExit(main())
