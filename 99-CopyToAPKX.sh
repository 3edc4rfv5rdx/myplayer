#!/usr/bin/env bash
set -e
# Everything below is relative to the project, and the sweep further down deletes
# .apkx links in the current directory: without this it would run against
# whatever directory the script was called from.
cd "$(dirname "$0")"

APK_DIR="app/build/outputs/apk/release"

# The name comes from the APK itself — the version and the build number are
# already in it — so nothing here reads build_number.txt.
apk=$(ls -t "$APK_DIR"/*arm64-v8a*.apk 2>/dev/null | head -1)

if [[ -z "$apk" || ! -f "$apk" ]]; then
    echo "ERROR: Release arm64 APK not found. Build first: ./10-MakeRelease.sh"
    exit 1
fi

dst="$(basename "${apk%.apk}").apkx"

# Only the current build keeps a link: the earlier ones are stale the moment this
# one is made, and dead ones point at APKs that were cleaned away. Plain .apkx
# files are left alone, they are copies someone made on purpose.
find . -maxdepth 1 -name '*.apkx' -type l ! -name "$dst" -delete

ln -sf "$apk" "$dst" 2>/dev/null || cp "$apk" "$dst"

echo "$(basename "$apk") -> $dst"

sleep 2
