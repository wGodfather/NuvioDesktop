#!/usr/bin/env bash
set -euo pipefail
test "${GITHUB_ACTIONS:-}" = true
test "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = 1
# Collect while the emulator is still alive; the action shuts it down on return.
collect() {
  mkdir -p vpn-emulator-report
  adb pull /sdcard/Android/data/com.wgodfather.nuvio.debug/files/vpn-qa vpn-emulator-report/ || true
  adb shell getprop ro.build.fingerprint > vpn-emulator-report/fingerprint.txt || true
  curl --silent --fail http://127.0.0.1:8765/metrics > vpn-emulator-report/peer-metrics.json || true
}
trap collect EXIT
./gradlew :androidApp:connectedFullDebugAndroidTest \
  '-Pandroid.testInstrumentationRunnerArguments.class=com.nuvio.android.VpnAndroidIntegrationTest,com.nuvio.android.VpnPeerIntegrationTest' \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  -Pkotlin.compiler.execution.strategy=in-process --max-workers=1 --no-daemon --no-configuration-cache
