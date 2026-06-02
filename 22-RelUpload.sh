#!/usr/bin/env bash
set -e

SCRIPT_DIR="/home/e/PRJ/myplayer"
cd "$SCRIPT_DIR"

PROJECT="myplayer"
APK_DIR="$SCRIPT_DIR/app/build/outputs/apk/release"
CHANGELOG_FILE="$(mktemp /tmp/myplayer-release-notes.XXXXXX.md)"
DRY_RUN=false

# ------------------------------------------------------------
# Dry-run mode: show what would happen without touching GitHub
# ------------------------------------------------------------
#DRY_RUN=true

# ------------------------------------------------------------
# Upload protection
# ------------------------------------------------------------
UPLOAD_TIMEOUT=180     # seconds per attempt
UPLOAD_RETRY=2         # number of attempts

echo "=== Detecting latest tag ==="
mapfile -t TAGS < <(git tag --list 'v*' | sort -V)
if (( ${#TAGS[@]} == 0 )); then
    echo "ERROR: No tags found."
    exit 1
fi

TAG="${TAGS[$(( ${#TAGS[@]} - 1 ))]}"
if (( ${#TAGS[@]} > 1 )); then
    PREV_TAG="${TAGS[$(( ${#TAGS[@]} - 2 ))]}"
else
    PREV_TAG=""
fi

echo "Tag: $TAG"
if [[ -n "$PREV_TAG" ]]; then
    echo "Prev: $PREV_TAG"
else
    echo "Prev: (none)"
fi

# ------------------------------------------------------------
# Parse tag: v0.1.20260602+24  ->  VERSION=0.1.20260602  BUILD=24
# ------------------------------------------------------------
CLEAN_TAG="${TAG#v}"
VERSION="${CLEAN_TAG%%+*}"
BUILD="${CLEAN_TAG##*+}"

if [[ -z "$VERSION" || -z "$BUILD" ]]; then
    echo "ERROR: Failed to parse tag: $TAG"
    exit 1
fi

echo "Version: $VERSION"
echo "Build:   $BUILD"

APK_PREFIX="${PROJECT}-${VERSION}+${BUILD}-release"

# ------------------------------------------------------------
# Build changelog from CHANGELOG.md
# Extract the notes under the latest tag section and stop at the
# previous tag section. If there is no previous tag, stop at the next
# top-level changelog header.
# ------------------------------------------------------------
echo "=== Building changelog from CHANGELOG.md ==="

if ! grep -q "^## ${TAG}$" CHANGELOG.md; then
    echo "ERROR: Changelog does not contain section for $TAG."
    exit 1
fi

LEGEND_LINE=$(grep -m 1 '^> ' CHANGELOG.md || true)

awk -v cur="## ${TAG}" -v stop="${PREV_TAG:+## ${PREV_TAG}}" '
    $0 == cur { capture=1; next }
    capture && stop != "" && $0 == stop { exit }
    capture && stop == "" && /^## / { exit }
    capture { print }
' CHANGELOG.md > "$CHANGELOG_FILE"

if [[ ! -s "$CHANGELOG_FILE" ]]; then
    echo "ERROR: No changelog notes found between $TAG and ${PREV_TAG:-end of file}."
    exit 1
fi

if [[ -n "$LEGEND_LINE" ]]; then
    {
        echo "$LEGEND_LINE"
        echo
        cat "$CHANGELOG_FILE"
    } > "${CHANGELOG_FILE}.tmp"
    mv "${CHANGELOG_FILE}.tmp" "$CHANGELOG_FILE"
fi

echo "Generated changelog:"
echo "--------------------------------------------------"
cat "$CHANGELOG_FILE"
echo "--------------------------------------------------"

# ------------------------------------------------------------
# Find APK files
# ------------------------------------------------------------
APK_ARM64="$APK_DIR/${APK_PREFIX}-arm64-v8a.apk"
APK_UNIVERSAL="$APK_DIR/${APK_PREFIX}-universal.apk"

if [[ ! -f "$APK_ARM64" ]]; then
    APK_ARM64=$(ls -t "$APK_DIR"/*.apk 2>/dev/null | head -1)
fi

if [[ ! -f "$APK_UNIVERSAL" ]]; then
    APK_UNIVERSAL="$APK_ARM64"
fi

echo "=== Checking APK files ==="

for f in "$APK_ARM64" "$APK_UNIVERSAL"; do
    if [[ -z "$f" || ! -f "$f" ]]; then
        echo "ERROR: APK not found for tag $TAG"
        echo "Expected file: $f"
        echo "APK directory: $APK_DIR"
        echo "Available APKs:"
        ls -1 "$APK_DIR"/*.apk 2>/dev/null || echo "(none)"
        exit 1
    fi
    echo "OK: $(basename "$f")"
done

# ------------------------------------------------------------
# Target file names in GitHub Release
# ------------------------------------------------------------
DST_ARM64="${PROJECT}-${VERSION}+${BUILD}-arm64-v8a.apk"
DST_UNIVERSAL="${PROJECT}-${VERSION}+${BUILD}-universal.apk"

FILES=(
    "$APK_ARM64#$DST_ARM64"
    "$APK_UNIVERSAL#$DST_UNIVERSAL"
)

# ------------------------------------------------------------
# Create release if not exists
# ------------------------------------------------------------
echo "=== Checking if GitHub Release exists ==="

if $DRY_RUN; then
    echo "[DRY RUN] Would create release: $TAG"
    echo "[DRY RUN] Title: Release $TAG"
    echo "[DRY RUN] Notes file: $CHANGELOG_FILE"
elif gh release view "$TAG" >/dev/null 2>&1; then
    echo "Release already exists."
else
    echo "Creating GitHub Release..."
    gh release create "$TAG" \
        --title "Release $TAG" \
        --notes-file "$CHANGELOG_FILE"
fi

# ------------------------------------------------------------
# Upload helper with retry + timeout + cleanup
# ------------------------------------------------------------
upload_asset() {
    local tag="$1"
    local src="$2"
    local dst="$3"

    echo "--------------------------------------------------"
    echo "Uploading: $(basename "$src") -> $dst"

    if $DRY_RUN; then
        echo "[DRY RUN] Would upload: $(basename "$src") -> $dst"
        return 0
    fi

    for ((i=1; i<=UPLOAD_RETRY; i++)); do
        echo "Attempt $i/$UPLOAD_RETRY..."

        # Remove broken asset if exists (ignore errors)
        gh release delete-asset "$tag" "$dst" -y 2>/dev/null || true

        if timeout "$UPLOAD_TIMEOUT" \
            gh release upload "$tag" "${src}#${dst}" --clobber
        then
            echo "Upload OK: $dst"
            return 0
        fi

        echo "Upload failed or timeout, retrying in 5s..."
        sleep 5
    done

    echo "ERROR: Upload failed after $UPLOAD_RETRY attempts: $dst"
    return 1
}

# ------------------------------------------------------------
# Upload files
# ------------------------------------------------------------
echo "=== Uploading files to Release ==="

for pair in "${FILES[@]}"; do
    SRC="${pair%%#*}"
    DST="${pair##*#}"
    upload_asset "$TAG" "$SRC" "$DST"
done

echo "=== Release upload completed successfully ==="

# ------------------------------------------------------------
# Cleanup
# ------------------------------------------------------------
rm -f "$CHANGELOG_FILE"

sleep 2
