#!/bin/sh

# Emulator is x86_64 — pick that split, fall back to universal, then anything
apk=$(ls -t app/build/outputs/apk/release/*-x86_64.apk 2>/dev/null | head -1)
[ -z "$apk" ] && apk=$(ls -t app/build/outputs/apk/release/*-universal.apk 2>/dev/null | head -1)
[ -z "$apk" ] && apk=$(ls -t app/build/outputs/apk/release/*.apk 2>/dev/null | head -1)

if [ -z "$apk" ]; then
    echo "No release APK found"
    exit 1
fi

echo ">>> Installing: $(basename "$apk")"
adb -s emulator-5554 install -r "$apk"

sleep 2