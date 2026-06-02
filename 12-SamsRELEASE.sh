#!/bin/sh

# Samsung is arm64-v8a — pick that split, fall back to universal, then anything
apk=$(ls -t app/build/outputs/apk/release/*-arm64-v8a.apk 2>/dev/null | head -1)
[ -z "$apk" ] && apk=$(ls -t app/build/outputs/apk/release/*-universal.apk 2>/dev/null | head -1)
[ -z "$apk" ] && apk=$(ls -t app/build/outputs/apk/release/*.apk 2>/dev/null | head -1)

if [ -z "$apk" ]; then
    echo "No release APK found"
    exit 1
fi

echo ">>> Installing: $(basename "$apk")"
TEL=$(adb  devices | awk '/device$/ && !/emulator/{print $1; exit}')
echo ">>>>>>>: "$TEL
adb -s $TEL install -r "$apk"
#adb -s 192.168.204.252:45245 install -r "$apk"
#adb -s RFGL205J57N install -r "$apk"
sleep 3
