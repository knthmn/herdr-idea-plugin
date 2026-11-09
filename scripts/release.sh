#!/usr/bin/env bash
# Moves the Unreleased changelog section to a new version, commits and tags it. Does not push.
# Usage: scripts/release.sh 0.2.0
set -euo pipefail

version="${1:?Usage: scripts/release.sh <version>}"
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

if [ -n "$(git status --porcelain)" ]; then
  echo "The working tree has changes. Commit or stash them first." >&2
  exit 1
fi
if git rev-parse -q --verify "refs/tags/v$version" >/dev/null; then
  echo "Tag v$version exists." >&2
  exit 1
fi
notes="$(./gradlew getChangelog --unreleased --no-header --no-empty-sections --no-links --console=plain -q)"
if [ -z "${notes//[[:space:]]/}" ]; then
  echo "The Unreleased section of CHANGELOG.md is empty." >&2
  exit 1
fi

sed -i.bak "s/^version = .*/version = $version/" gradle.properties
rm gradle.properties.bak
./gradlew patchChangelog --console=plain -q
git commit -q -am "chore: release $version"
git tag "v$version"

echo "Created commit and tag v$version. Push them to publish:"
echo "  git push origin HEAD v$version"
