#!/usr/bin/env bash
set -e

BUILD_FILE="build_number.txt"

if [[ ! -f "$BUILD_FILE" ]]; then
    echo "base_version=0.1" > "$BUILD_FILE"
    echo "build=0" >> "$BUILD_FILE"
    echo "version=0.1.00000000" >> "$BUILD_FILE"
fi

source "$BUILD_FILE"
NEW_BUILD=$((build + 1))
TODAY=$(date +%Y%m%d)
NEW_VERSION="${base_version}.${TODAY}"

cat > "$BUILD_FILE" <<EOF
base_version=${base_version}
build=${NEW_BUILD}
version=${NEW_VERSION}
EOF

echo "Version: $NEW_VERSION"
echo ">>> Build: $NEW_BUILD <<<"

./gradlew assembleRelease

echo
echo "Release APKs: app/build/outputs/apk/release/"
ls -1 app/build/outputs/apk/release/*.apk 2>/dev/null

# Fold the build_number bump into the previous commit, if safe.
# Safe = HEAD is not yet on any remote branch AND the only modified file is build_number.txt.
echo
if git rev-parse --verify HEAD >/dev/null 2>&1; then
    dirty=$(git status --porcelain | awk '{print $2}')
    if [[ "$dirty" == "$BUILD_FILE" ]]; then
        if [[ -z "$(git branch -r --contains HEAD 2>/dev/null)" ]]; then
            git add "$BUILD_FILE"
            git commit --amend --no-edit >/dev/null
            echo ">>> Folded $BUILD_FILE into $(git log -1 --pretty=format:'%h %s')"
        else
            echo ">>> HEAD already pushed; leaving $BUILD_FILE uncommitted."
        fi
    else
        echo ">>> Other changes present; leaving $BUILD_FILE uncommitted."
    fi
fi

sleep 2
