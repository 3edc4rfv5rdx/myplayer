#!/bin/sh

# Emulator is x86_64 — pick that split, fall back to universal, then anything

cd "$(dirname "$0")"
apk=$(ls -t app/build/outputs/apk/release/*-x86_64.apk 2>/dev/null | head -1)
[ -z "$apk" ] && apk=$(ls -t app/build/outputs/apk/release/*-universal.apk 2>/dev/null | head -1)
[ -z "$apk" ] && apk=$(ls -t app/build/outputs/apk/release/*.apk 2>/dev/null | head -1)

if [ -z "$apk" ]; then
    echo "No release APK found"
    exit 1
fi

# Nothing to work on is not a failure: 00-MakeAll.sh reads 3 as "no emulator running"
# and carries on, while anything else non-zero ends its run.
if ! adb devices | grep -q "^emulator-5554[[:space:]]*device$"; then
    echo "Emulator emulator-5554 is not running"
    exit 3
fi

echo ">>> Installing: $(basename "$apk")"
adb -s emulator-5554 install -r "$apk"
# Kept across the pause: the script used to end on sleep and report its exit code, so a
# failed install still read as success.
status=$?

sleep 2
exit $status
