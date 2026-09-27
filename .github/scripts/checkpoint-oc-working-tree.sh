#!/usr/bin/env bash
set -euo pipefail
dir="${1:-.}"
branch="${OC_SESSION_BRANCH:-$(git -C "$dir" branch --show-current 2>/dev/null || true)}"
attempt="${OC_ATTEMPT:-checkpoint}"
source "$(cd "$(dirname "$0")" && pwd)/oc-publish-lib.sh"

[[ -d "$dir/.git" || -f "$dir/.git" ]] || exit 0
[[ -n "$branch" && "$branch" != "main" ]] || exit 0
if [[ -z "$(git -C "$dir" status --porcelain --untracked-files=normal 2>/dev/null)" ]]; then
  exit 0
fi

if ! git -C "$dir" diff --check >/dev/null 2>&1; then
  echo "::warning title=Checkpoint quality warning::The current checkpoint fails git diff --check; preserving it anyway for /oc continue recovery."
fi
oc_guard_repo_publication "$dir" || exit 0
git -C "$dir" add -A
if git -C "$dir" diff --cached --quiet; then
  git -C "$dir" reset -q >/dev/null 2>&1 || true
  exit 0
fi
oc_repo_identity "$dir"
git -C "$dir" commit -m "checkpoint(oc): preserve session progress (attempt $attempt)" >/dev/null 2>&1 || {
  git -C "$dir" reset -q >/dev/null 2>&1 || true
  exit 0
}
checkpoint_sha="$(git -C "$dir" rev-parse HEAD)"
if oc_git_push -C "$dir" origin "HEAD:refs/heads/$branch" >/dev/null 2>&1; then
  printf "OC_CHECKPOINT_SHA=%s\n" "$checkpoint_sha" >> "${GITHUB_ENV:-/dev/null}"
  printf "checkpoint_sha=%s\n" "$checkpoint_sha" >> "${GITHUB_OUTPUT:-/dev/null}"
  echo "Durable checkpoint pushed: $branch @ $checkpoint_sha"
else
  echo "::warning title=Checkpoint push degraded::Checkpoint commit exists locally but could not be pushed."
fi
