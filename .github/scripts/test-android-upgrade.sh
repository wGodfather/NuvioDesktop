#!/usr/bin/env bash
set -euo pipefail
# Only a disposable Android emulator; root is used to seed non-secret user-data.
test "${GITHUB_ACTIONS:-}" = true
test "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = 1
adb root
adb wait-for-device
package=com.wgodfather.nuvio
previous=build/vpn-upgrade-029/Nuvio-Android-x86_64-0.1.29-alpha.apk
current="dist/Nuvio-Android-x86_64-$RELEASE_VERSION.apk"
buildtools=$(find "$ANDROID_HOME/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)
oldcert=$("$buildtools/apksigner" verify --print-certs "$previous" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')
newcert=$("$buildtools/apksigner" verify --print-certs "$current" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')
test -n "$oldcert" && test "$oldcert" = "$newcert"
adb install "$previous"
adb shell am start -W -n "$package/com.nuvio.app.MainActivity"
sleep 3
adb shell pidof "$package"
adb shell "mkdir -p /data/user/0/$package/shared_prefs; printf '%s' '<map><string name=\"qa\">existing-library-download-preferences</string></map>' > /data/user/0/$package/shared_prefs/vpn-upgrade-qa.xml"
before=$(adb shell sha256sum "/data/user/0/$package/shared_prefs/vpn-upgrade-qa.xml" | cut -d' ' -f1)
adb shell am force-stop "$package"
adb install -r "$current"
after=$(adb shell sha256sum "/data/user/0/$package/shared_prefs/vpn-upgrade-qa.xml" | cut -d' ' -f1)
test "$before" = "$after"
adb shell am start -W -n "$package/com.nuvio.app.MainActivity"
sleep 3
adb shell pidof "$package"
expected_code=$(sed -n 's/^VERSION_CODE=//p' composeApp/Configuration/DesktopVersion.properties | tr -d '\r')
package_info=$(adb shell dumpsys package "$package" | tr -d '\r')
actual_code=$(printf '%s\n' "$package_info" | sed -n 's/^[[:space:]]*versionCode=\([0-9]*\).*/\1/p' | head -1)
actual_name=$(printf '%s\n' "$package_info" | sed -n 's/^[[:space:]]*versionName=//p' | head -1)
test "$actual_code" = "$expected_code"
test "$actual_name" = "$RELEASE_VERSION"
adb shell am force-stop "$package"
echo "PASS same fork certificate, signed 0.1.29 → $RELEASE_VERSION / $expected_code install, data preservation and release launch"
