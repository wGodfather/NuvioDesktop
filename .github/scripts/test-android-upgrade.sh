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
adb shell dumpsys package "$package" | grep -E 'versionCode=31|versionName=0.1.30-alpha'
adb shell am force-stop "$package"
echo 'PASS same fork certificate, signed 0.1.29 → 0.1.30-alpha / 31 install, data preservation and release launch'
