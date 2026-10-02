#!/usr/bin/env bash
set -euo pipefail
python3 -u tools/qa_torrent_seed.py > android-seed.log 2>&1 &
seed_pid=$!
trap 'kill "$seed_pid" 2>/dev/null || true; adb logcat -d > android-qa-logcat.txt || true' EXIT
for attempt in {1..30}; do
  test -s ../artifacts/android-torrent-fixture.json && break
  sleep 1
done
fixture=../artifacts/android-torrent-fixture.json
magnet=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["magnet"])' "$fixture")
sha256=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["sha256"])' "$fixture")
bytes=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["bytes"])' "$fixture")
./gradlew :androidApp:connectedFullDebugAndroidTest \
  '-Pandroid.testInstrumentationRunnerArguments.class=com.nuvio.android.ForkAndroidIntegrationTest,com.nuvio.android.LibraryDownloadsUiTest' \
  '-Pandroid.testInstrumentationRunnerArguments.manifest=https://raw.githubusercontent.com/dr-octagon/nuvio/main/manifest.json' \
  "-Pandroid.testInstrumentationRunnerArguments.magnet=$magnet" \
  "-Pandroid.testInstrumentationRunnerArguments.sha256=$sha256" \
  "-Pandroid.testInstrumentationRunnerArguments.bytes=$bytes" \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  -Pkotlin.compiler.execution.strategy=in-process --max-workers=1 --no-daemon --no-configuration-cache
grep -q 'metadata served' android-seed.log
grep -q 'piece served:' android-seed.log
mkdir -p android-ui-screenshots
adb pull /sdcard/Android/data/com.wgodfather.nuvio.debug/files/fork-ui-qa/library-downloads.png android-ui-screenshots/
