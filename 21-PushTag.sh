#!/usr/bin/env bash
set -e

REMOTE="origin"

# ===== dry-run switch =====
#DRY="--dry-run"
DRY=""
# ==========================

echo "=== Checking that the working tree is clean ==="

if ! git diff --quiet || ! git diff --cached --quiet; then
    echo "ERROR: You have uncommitted changes."
    echo "Please commit or stash them before running this script."
    exit 1
fi

echo "OK: Working tree is clean."

echo "=== Detecting latest tag ==="
LAST_TAG=$(git tag --list 'v*' | sort -V | tail -n 1)

if [[ -z "$LAST_TAG" ]]; then
    echo "ERROR: No tags found."
    exit 1
fi

echo "Latest tag: $LAST_TAG"

# Check if branch needs pushing
BRANCH=$(git rev-parse --abbrev-ref HEAD)
git fetch "$REMOTE" "$BRANCH" --quiet 2>/dev/null || true
LOCAL=$(git rev-parse HEAD)
REMOTE_HEAD=$(git rev-parse "$REMOTE/$BRANCH" 2>/dev/null || echo "")

if [[ "$LOCAL" == "$REMOTE_HEAD" ]]; then
    echo "Branch $BRANCH is up to date with $REMOTE."
else
    echo "=== Pushing branch $BRANCH ($DRY) ==="
    git push $DRY "$REMOTE"
fi

# Check if tag needs pushing
if git ls-remote --tags "$REMOTE" "$LAST_TAG" | grep -q "$LAST_TAG"; then
    echo "Tag $LAST_TAG already exists on $REMOTE."
else
    echo "=== Pushing tag $LAST_TAG ($DRY) ==="
    git push $DRY "$REMOTE" "$LAST_TAG"
fi

echo "=== Done ==="

sleep 2
