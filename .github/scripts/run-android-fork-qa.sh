#!/usr/bin/env bash
set -euo pipefail
python3 -u tools/qa_torrent_seed.py > android-seed.log 2>&1 &
seed_pid=$!
NUVIO_QA_EPISODE_PACK=1 NUVIO_QA_FIXTURE_OUTPUT=android-pack-fixture.json python3 -u tools/qa_torrent_seed.py > android-pack-seed.log 2>&1 &
pack_pid=$!
trap 'kill "$seed_pid" "$pack_pid" 2>/dev/null || true; adb logcat -d > android-qa-logcat.txt || true' EXIT
for attempt in {1..30}; do
  test -s ../artifacts/android-torrent-fixture.json && break
  sleep 1
done
fixture=../artifacts/android-torrent-fixture.json
magnet=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["magnet"])' "$fixture")
sha256=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["sha256"])' "$fixture")
bytes=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["bytes"])' "$fixture")
for attempt in {1..30}; do
  test -s android-pack-fixture.json && break
  sleep 1
done
pack_magnet=$(python3 -c 'import json; print(json.load(open("android-pack-fixture.json"))["magnet"])')
pack_tracker=$(python3 -c 'import json; print(json.load(open("android-pack-fixture.json"))["tracker_url"])')
pack_sha8=$(python3 -c 'import json; print(json.load(open("android-pack-fixture.json"))["episodes"]["8"]["sha256"])')
pack_sha9=$(python3 -c 'import json; print(json.load(open("android-pack-fixture.json"))["episodes"]["9"]["sha256"])')
./gradlew :androidApp:connectedFullDebugAndroidTest \
  '-Pandroid.testInstrumentationRunnerArguments.class=com.nuvio.android.ForkAndroidIntegrationTest,com.nuvio.android.LibraryDownloadsUiTest,com.nuvio.android.TorrentEpisodePackIntegrationTest' \
  '-Pandroid.testInstrumentationRunnerArguments.manifest=https://raw.githubusercontent.com/dr-octagon/nuvio/main/manifest.json' \
  "-Pandroid.testInstrumentationRunnerArguments.magnet=$magnet" \
  "-Pandroid.testInstrumentationRunnerArguments.sha256=$sha256" \
  "-Pandroid.testInstrumentationRunnerArguments.bytes=$bytes" \
  "-Pandroid.testInstrumentationRunnerArguments.packMagnet=$pack_magnet" \
  "-Pandroid.testInstrumentationRunnerArguments.packTracker=$pack_tracker" \
  "-Pandroid.testInstrumentationRunnerArguments.packSha8=$pack_sha8" \
  "-Pandroid.testInstrumentationRunnerArguments.packSha9=$pack_sha9" \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  -Pkotlin.compiler.execution.strategy=in-process --max-workers=1 --no-daemon --no-configuration-cache
grep -q 'metadata served' android-seed.log
grep -q 'piece served:' android-seed.log
grep -q 'metadata served' android-pack-seed.log
grep -q 'piece served:' android-pack-seed.log
mkdir -p android-ui-screenshots
adb pull /sdcard/Android/data/com.wgodfather.nuvio.debug/files/fork-ui-qa/library-downloads.png android-ui-screenshots/
