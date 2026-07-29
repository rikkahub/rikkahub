#!/usr/bin/env bash
set -euo pipefail

project_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
evidence_dir=$(mktemp -d)

cd "$project_root"

./gradlew :app:dependencies --configuration debugRuntimeClasspath \
    -PvoiceAgentLiveKitExperimentEnabled=false \
    > "$evidence_dir/flag-off-dependencies.txt"

if rg -q 'io\.livekit:livekit-android|io\.github\.webrtc-sdk:android-prefixed' \
    "$evidence_dir/flag-off-dependencies.txt"; then
    echo "Flag-off runtime graph still contains LiveKit or WebRTC" >&2
    exit 1
fi

./gradlew :app:packageDebugUniversalApk \
    -PvoiceAgentLiveKitExperimentEnabled=false
flag_off_apk="app/build/outputs/apk_from_bundle/debug/app-debug-universal.apk"
test -f "$flag_off_apk"
unzip -Z1 "$flag_off_apk" > "$evidence_dir/flag-off-apk.txt"
if rg -qi 'livekit|webrtc|jingle_peerconnection' "$evidence_dir/flag-off-apk.txt"; then
    echo "Flag-off APK still contains LiveKit or WebRTC artifacts" >&2
    exit 1
fi

./gradlew :app:dependencies --configuration debugRuntimeClasspath \
    -PvoiceAgentLiveKitExperimentEnabled=true \
    > "$evidence_dir/flag-on-dependencies.txt"

rg -q 'io\.livekit:livekit-android' "$evidence_dir/flag-on-dependencies.txt"
rg -q 'io\.github\.webrtc-sdk:android-prefixed' "$evidence_dir/flag-on-dependencies.txt"

./gradlew :app:packageDebugUniversalApk \
    -PvoiceAgentLiveKitExperimentEnabled=true
flag_on_apk="app/build/outputs/apk_from_bundle/debug/app-debug-universal.apk"
test -f "$flag_on_apk"
unzip -Z1 "$flag_on_apk" > "$evidence_dir/flag-on-apk.txt"
rg -qi 'webrtc|jingle_peerconnection' "$evidence_dir/flag-on-apk.txt"

echo "Voice Agent LiveKit boundary verified; evidence: $evidence_dir"
