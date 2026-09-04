#!/usr/bin/env bash
set -e

cd "$(dirname "$0")"
REMOTE="origin"

# ===== dry-run switch =====
#DRY="--dry-run"
DRY=""
# ==========================

echo "=== Checking that the working tree is clean ==="

# --porcelain reports staged, unstaged AND untracked files; git diff --quiet misses untracked,
# so a new (still untracked) source file could be bundled into the APK yet omitted from the tag.
DIRTY="$(git status --porcelain)"
if [[ -n "$DIRTY" ]]; then
    echo "ERROR: You have uncommitted or untracked changes:"
    echo "$DIRTY"
    echo "Please commit, stash, or remove them before running this script."
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
    # The branch is named rather than left to push.default: a branch without an
    # upstream yet would otherwise stop the release with "no upstream branch".
    git push $DRY "$REMOTE" "$BRANCH"
fi

# Asked for the one ref by its full name, and answered by whether that exact ref
# came back: a substring test would find v0.6.20260804-17 inside -175 and skip a
# tag that was never pushed.
if git ls-remote --tags "$REMOTE" "refs/tags/$LAST_TAG" | grep -q "refs/tags/${LAST_TAG}$"; then
    echo "Tag $LAST_TAG already exists on $REMOTE."
else
    echo "=== Pushing tag $LAST_TAG ($DRY) ==="
    git push $DRY "$REMOTE" "$LAST_TAG"
fi

echo "=== Done ==="

sleep 2
