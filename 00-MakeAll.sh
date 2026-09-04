#!/usr/bin/env bash
#
# Everything in one run: build the release APKs, install them on the emulator
# and on the phone, then link the build into OUT/.
#
# A build that fails stops the run. A device that is not plugged in does not:
# an absent emulator should not cost you the phone install. The difference is in
# the exit code of the step — 3 means there was nothing to run it on, anything
# else non-zero means it tried and failed — and this run ends non-zero when
# something actually failed, so it is not only the log that says so.
#
# Finally 19-LinkOut.sh puts the arm64 and universal APKs into OUT/ as links
# under their own names, and sweeps whatever else was there.
#
cd "$(dirname "$0")"

if [ "$1" = "-h" ] || [ "$1" = "--help" ]; then
    sed -n '2,13p' "$0"
    exit 0
fi

# Two different endings, kept apart: a step that was not there or had nothing to
# work on, and a step that ran and did not finish.
SKIPPED=""
FAILED=""

run() { # run <script> <fatal|optional>
    echo
    echo "=== $1 ==="
    if [ ! -x "$1" ]; then
        echo ">>> $1 is missing or not executable"
        [ "$2" = "fatal" ] && exit 1
        SKIPPED="$SKIPPED $1"
        return 0
    fi
    ./"$1"
    local rc=$?
    [ "$rc" = 0 ] && return 0
    if [ "$rc" = 3 ]; then
        echo ">>> $1 had nothing to work on"
        SKIPPED="$SKIPPED $1"
        return 0
    fi
    echo ">>> $1 failed"
    [ "$2" = "fatal" ] && exit 1
    FAILED="$FAILED $1"
}

run 10-MakeRelease.sh fatal
run 11-EmulRELEASE.sh optional
run 12-SamsRELEASE.sh optional
run 18-ToUpdate.sh optional
# Its own step, so the same link also works on a build that already exists.
run 19-LinkOut.sh optional

echo
if [ -n "$FAILED" ]; then
    echo "Finished, but these failed:$FAILED"
elif [ -n "$SKIPPED" ]; then
    echo "Done, without:$SKIPPED"
else
    echo "Done: APK installed on both, build linked into OUT/"
fi
# Only a step that tried and failed makes the run itself a failure.
[ -z "$FAILED" ] || exit 1

sleep 3
