#!/usr/bin/env bash
set -e

cd "$(dirname "$0")"
BUILD_FILE="build_number.txt"

# Whether anything waiting for release is a new feature. The changelog already
# says so itself: N is a feature, everything else is a fix, a tweak or plumbing.
# 20-MakeTag.sh empties Unreleased when it stamps a version, so this reads
# exactly what has landed since the last tag.
unreleased_has_feature() {
    [[ -f CHANGELOG.md ]] || return 1
    awk '
        /^## Unreleased$/ { inside = 1; next }
        /^## / { inside = 0 }
        inside && /^- N[: ]/ { found = 1 }
        END { exit !found }
    ' CHANGELOG.md
}

# The line the last release went out on, so the rule fires once per feature and
# not on every build after it.
# Silence rather than failure when there is no tag yet, or none that parses: an
# assignment from a function that returns non-zero ends the script under set -e,
# and a project without releases is not an error.
released_line() {
    local tag
    # Release tags only: a tag like "duplex" sorts ahead of them and would answer
    # this question with a name that carries no version at all.
    tag=$(git tag --list 'v*' --sort=-v:refname 2>/dev/null | head -1) || true
    if [[ "$tag" =~ ^v([0-9]+\.[0-9]+)\. ]]; then
        echo "${BASH_REMATCH[1]}"
    fi
    return 0
}

if [[ ! -f "$BUILD_FILE" ]]; then
    echo "base_version=0.1" > "$BUILD_FILE"
    echo "build=0" >> "$BUILD_FILE"
    echo "version=0.1.00000000" >> "$BUILD_FILE"
fi

source "$BUILD_FILE"
NEW_BUILD=$((build + 1))
TODAY=$(date +%Y%m%d)

# The line moves by itself when the changelog says a feature is waiting and the
# last release went out on this same line. Nothing to pass and nothing to
# remember: the decision was made when the entry was written as N: rather than
# E:. A tree with no tags yet never matches, so the line stays where it is.
NEW_BASE_VERSION="$base_version"
if [[ "$(released_line)" == "$base_version" ]] && unreleased_has_feature; then
    NEW_BASE_VERSION="${base_version%.*}.$(( ${base_version##*.} + 1 ))"
    echo ">>> A feature is waiting in the changelog: ${base_version} -> ${NEW_BASE_VERSION}"
fi

NEW_VERSION="${NEW_BASE_VERSION}.${TODAY}"

cat > "$BUILD_FILE" <<EOF
base_version=${NEW_BASE_VERSION}
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
