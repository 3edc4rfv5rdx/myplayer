#!/usr/bin/env bash
#
# Put the APKs of the newest release build into OUT/ as links under their own
# names, and sweep everything else out of that folder:
#
#   OUT/myplayer-<version>-arm64-v8a.apk
#   OUT/myplayer-<version>-universal.apk
#
# One place to copy a build from, instead of a path deep inside app/build/. The
# x86_64 split is left where it is: it only ever goes to the emulator, which is
# installed to from 11-EmulRELEASE.sh and never carried anywhere by hand.
#
# The links are hard ones: the entry here is the file itself, so copying it
# elsewhere copies a build and not a dangling path, and a gradle clean leaves it
# whole. The version ends in the build number, so the listing says which build
# it is. Nothing is built here: 00-MakeAll.sh runs this after a build, and on
# its own it picks up a build that already exists.
#
cd "$(dirname "$0")"

if [ "$1" = "-h" ] || [ "$1" = "--help" ]; then
    sed -n '2,18p' "$0"
    exit 0
fi

APK_DIR="app/build/outputs/apk/release"

MISSING=""
# An array, not a string of names, so a name with a space in it cannot turn the
# membership test below into a match on halves of two different names.
LINKED=()

link_latest() { # link_latest <candidate files...>
    local newest
    newest=$(ls -t "$@" 2>/dev/null | head -1)
    if [ -z "$newest" ] || [ ! -f "$newest" ]; then
        echo ">>> nothing to link into OUT"
        MISSING="yes"
        return 0
    fi
    local name
    name=$(basename "$newest")
    mkdir -p OUT
    ln -f "$newest" "OUT/$name"
    LINKED+=("$name")
    echo "OUT/$name"
}

link_latest "$APK_DIR"/*arm64-v8a*.apk
link_latest "$APK_DIR"/*universal*.apk

# Everything else goes: the previous build's names, an ABI no longer built, a
# copy left behind. Only files and links — a directory somebody made here is not
# ours to remove.
#
# And only when every artifact was linked. A run that could not find one of them
# would otherwise sweep anyway and delete the previous good build, leaving OUT/
# with half a release.
if [ -z "$MISSING" ] && [ -d OUT ]; then
    for entry in OUT/* ; do
        [ -d "$entry" ] && continue
        [ -e "$entry" ] || [ -L "$entry" ] || continue
        name=$(basename "$entry")
        keep=""
        for linked in ${LINKED+"${LINKED[@]}"}; do
            [ "$name" = "$linked" ] && { keep=yes; break; }
        done
        [ -n "$keep" ] && continue
        rm -f "$entry"
    done
fi

if [ -n "$MISSING" ]; then
    echo ">>> incomplete set: OUT left as it was"
    exit 1
fi
exit 0
