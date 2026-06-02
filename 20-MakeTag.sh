#!/usr/bin/env bash
set -e

BUILD_FILE="build_number.txt"

echo "=== Checking that the working tree is clean ==="

if ! git diff --quiet || ! git diff --cached --quiet; then
    echo "ERROR: You have uncommitted changes."
    echo "Please commit or stash them before running this script."
    exit 1
fi

echo "OK: Working tree is clean."

echo "=== Reading build info ==="

if [[ ! -f "$BUILD_FILE" ]]; then
    echo "ERROR: $BUILD_FILE not found."
    exit 1
fi

source "$BUILD_FILE"

if [[ -z "$version" || -z "$build" ]]; then
    echo "ERROR: Failed to read version/build from $BUILD_FILE"
    exit 1
fi

TAG="v${version}+${build}"
CHANGELOG_FILE="CHANGELOG.md"

echo "Version: $version"
echo "Build:   $build"
echo "Tag:     $TAG"

# Check if tag already exists
if git tag --list "$TAG" | grep -q "$TAG"; then
    echo "Tag $TAG already exists. Nothing to do."
    exit 0
fi

if [[ ! -f "$CHANGELOG_FILE" ]]; then
    echo "ERROR: $CHANGELOG_FILE not found."
    exit 1
fi

if grep -q "^## ${TAG}$" "$CHANGELOG_FILE"; then
    echo "Changelog already has section for $TAG. Skipping update."
else
    echo "=== Inserting $TAG section right after Unreleased ==="
    updated_changelog="$(mktemp /tmp/myplayer-changelog-updated.XXXXXX.md)"

    awk -v tag="$TAG" '
        BEGIN { in_unreleased=0 }

        /^## Unreleased$/ {
            # Keep Unreleased at the top and add the new release section right after it.
            print $0
            print "## " tag
            in_unreleased=1
            next
        }

        in_unreleased && /^## / {
            in_unreleased=0
        }

        { print }
    ' "$CHANGELOG_FILE" > "$updated_changelog"

    if ! grep -q "^## ${TAG}$" "$updated_changelog"; then
        echo "ERROR: Failed to insert $TAG into changelog."
        rm -f "$updated_changelog"
        exit 1
    fi

    mv "$updated_changelog" "$CHANGELOG_FILE"

    echo "=== Committing changelog update ==="
    git add "$CHANGELOG_FILE"
    git commit -m "Add release notes for $TAG"
fi

echo "=== Creating tag $TAG ==="
git tag -a "$TAG" -m "Build $build"

echo "=== Done: tag $TAG created ==="

sleep 2
