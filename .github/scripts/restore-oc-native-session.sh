#!/usr/bin/env bash
set -euo pipefail
repo="$(printenv GITHUB_REPOSITORY || true)"
token="$(printenv UNIVERSAL_TOKEN || true)"
session_id="$(printenv OC_NATIVE_SESSION_ID || true)"
branch="$(printenv OC_NATIVE_SESSION_ARCHIVE_BRANCH || true)"
path="$(printenv OC_NATIVE_SESSION_ARCHIVE_PATH || printf 'session.json.enc')"
target="$(printenv TARGET_NUMBER || printf 0)"
runner_temp="$(printenv RUNNER_TEMP || printf /tmp)"
[[ "$target" =~ ^[0-9]+$ && "$target" != 0 ]] || exit 0
[[ "$session_id" =~ ^ses_[A-Za-z0-9_-]+$ ]] || exit 2
[[ -n "$branch" && -n "$token" ]] || exit 2
tmp_dir="$runner_temp/oc-native-restore"; mkdir -p "$tmp_dir"
encrypted="$tmp_dir/session.json.enc"; compressed="$tmp_dir/session.json.gz"
export_file="$runner_temp/opencode-native-session-$target.json"
ref_sha="$(gh api "/repos/$repo/git/ref/heads/$branch" --jq '.object.sha')" || exit 1
tree_sha="$(gh api "/repos/$repo/git/commits/$ref_sha" --jq '.tree.sha')" || exit 1
blob_sha="$(gh api "/repos/$repo/git/trees/$tree_sha" --jq --arg path "$path" '.tree[] | select(.path == $path and .type == "blob") | .sha' | head -n 1)" || exit 1
[[ "$blob_sha" =~ ^[0-9a-f]{40}$ ]] || exit 1
content="$(gh api "/repos/$repo/git/blobs/$blob_sha" --jq '.content // empty')" || exit 1
printf '%s' "$content" | tr -d '
' | base64 -d > "$encrypted"
printf '%s' "$token" | openssl enc -d -aes-256-cbc -pbkdf2 -in "$encrypted" -out "$compressed" -pass stdin >/dev/null 2>&1 || exit 1
gzip -dc "$compressed" > "$export_file"
jq -e --arg sid "$session_id" '.info.id == $sid' "$export_file" >/dev/null || exit 1
printf 'OC_NATIVE_SESSION_EXPORT_FILE=%s\n' "$export_file" >> "$GITHUB_ENV"
