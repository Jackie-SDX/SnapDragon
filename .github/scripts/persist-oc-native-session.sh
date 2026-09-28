#!/usr/bin/env bash
set -euo pipefail
repo="$(printenv GITHUB_REPOSITORY || true)"
token="$(printenv GITHUB_TOKEN || true)"
[[ -n "$token" ]] || token="$(printenv GH_TOKEN || true)"
[[ -n "$token" ]] || token="$(printenv UNIVERSAL_TOKEN || true)"
target="$(printenv TARGET_NUMBER || printf 0)"
session_id="$(printenv NATIVE_SESSION_ID || true)"
export_file="$(printenv NATIVE_SESSION_EXPORT_FILE || true)"
branch="$(printenv OC_NATIVE_ARCHIVE_BRANCH || printf 'oc-native-session-')$target"
path="$(printenv OC_NATIVE_ARCHIVE_PATH || printf 'session.json.enc')"
archive_commit_file="$(printenv OC_NATIVE_ARCHIVE_COMMIT_FILE || true)"
runner_temp="$(printenv RUNNER_TEMP || printf /tmp)"
[[ "$target" =~ ^[0-9]+$ && "$target" != 0 ]] || exit 0
[[ "$session_id" =~ ^ses_[A-Za-z0-9_-]+$ ]] || exit 2
[[ -s "$export_file" ]] || exit 2
[[ -n "$token" ]] || exit 1
tmp_dir="$runner_temp/oc-native-archive"; mkdir -p "$tmp_dir"
compressed="$tmp_dir/session.json.gz"; encrypted="$tmp_dir/session.json.enc"
gzip -c "$export_file" > "$compressed"
printf '%s' "$token" | openssl enc -aes-256-cbc -pbkdf2 -salt -in "$compressed" -out "$encrypted" -pass stdin >/dev/null 2>&1
blob_payload="$tmp_dir/blob.json"
python3 - "$encrypted" > "$blob_payload" <<'PY'
import base64, json, sys
with open(sys.argv[1], "rb") as f:
    print(json.dumps({"content": base64.b64encode(f.read()).decode(), "encoding": "base64"}))
PY
blob_sha="$(gh api --method POST "/repos/$repo/git/blobs" --input "$blob_payload" --jq .sha)"
ref_json="$(gh api "/repos/$repo/git/ref/heads/$branch" 2>/dev/null || true)"
if [[ -n "$ref_json" ]]; then
  parent_sha="$(jq -r '.object.sha // empty' <<<"$ref_json")"
else
  base_ref="$(printenv BASE_REF || printf main)"
  parent_sha="$(gh api "/repos/$repo/git/ref/heads/$base_ref" --jq '.object.sha')"
  gh api --method POST "/repos/$repo/git/refs" -f "ref=refs/heads/$branch" -f "sha=$parent_sha" >/dev/null
fi
base_tree="$(gh api "/repos/$repo/git/commits/$parent_sha" --jq '.tree.sha')"
tree_payload="$tmp_dir/tree.json"
jq -n --arg base "$base_tree" --arg path "$path" --arg sha "$blob_sha" '{base_tree:$base,tree:[{path:$path,mode:"100644",type:"blob",sha:$sha}]}' > "$tree_payload"
tree_sha="$(gh api --method POST "/repos/$repo/git/trees" --input "$tree_payload" --jq .sha)"
commit_payload="$tmp_dir/commit.json"
jq -n --arg message "oc: native session checkpoint $session_id" --arg tree "$tree_sha" --arg parent "$parent_sha" '{message:$message,tree:$tree,parents:[$parent]}' > "$commit_payload"
commit_sha="$(gh api --method POST "/repos/$repo/git/commits" --input "$commit_payload" --jq .sha)"
gh api --method PATCH "/repos/$repo/git/refs/heads/$branch" -f "sha=$commit_sha" -F force=false >/dev/null
printf 'OC_NATIVE_SESSION_ARCHIVE_BRANCH=%s\n' "$branch" >> "$GITHUB_ENV"
printf 'OC_NATIVE_SESSION_ARCHIVE_PATH=%s\n' "$path" >> "$GITHUB_ENV"
printf 'OC_NATIVE_SESSION_ARCHIVE_COMMIT=%s\n' "$commit_sha" >> "$GITHUB_ENV"
printf 'OC_NATIVE_SESSION_EXPORTED=true\n' >> "$GITHUB_ENV"
printf 'native_session_archive_branch=%s\n' "$branch" >> "$GITHUB_OUTPUT"
printf 'native_session_archive_path=%s\n' "$path" >> "$GITHUB_OUTPUT"
printf 'native_session_archive_commit=%s\n' "$commit_sha" >> "$GITHUB_OUTPUT"
if [[ -n "$archive_commit_file" ]]; then printf '%s\n' "$commit_sha" > "$archive_commit_file"; fi
