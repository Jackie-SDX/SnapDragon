#!/usr/bin/env bash
set -euo pipefail

target="$(printenv TARGET_NUMBER || printf 0)"
repo="$(printenv GITHUB_REPOSITORY || true)"
runner_temp="${RUNNER_TEMP:-/tmp}"
request_file="${OC_REQUEST_FILE:-$runner_temp/oc-request.txt}"
full="$runner_temp/oc-issue-context-full.md"
seed="$runner_temp/oc-issue-context-seed.md"
index="$runner_temp/oc-issue-context.index"
refs="$runner_temp/oc-reference-context.md"
seed_bytes="${OC_CONTEXT_SEED_BYTES:-60000}"

mkdir -p "$runner_temp"
: > "$full"; : > "$index"; : > "$refs"
[[ "$target" =~ ^[0-9]+$ && "$target" != 0 && -n "$repo" ]] || exit 0

issue_json="$(mktemp)"
trap 'rm -f "$issue_json"' EXIT
gh api "/repos/$repo/issues/$target" > "$issue_json"
memory_marker="<!-- oc-session-memory:v2 issue:$target -->"
memory_end="<!-- /oc-session-memory -->"
issue_memory="$(jq -r '.body // ""' "$issue_json" | awk -v a="$memory_marker" -v b="$memory_end" 'index($0,a){inside=1;next} index($0,b){inside=0;next} inside{print}' | sed -n '/^STATE-BEGIN$/,/^STATE-END$/p' | sed '1d;$d')"

{
  echo "# /oc complete issue context"
  echo
  echo "Source: GitHub issue #$target in $repo"
  echo "Complete issue context is read in bounded batches; do not load the entire file into one prompt."
  if jq empty <<<"$issue_memory" >/dev/null 2>&1; then
    echo
    echo "## Durable /oc memory"
    jq . <<<"$issue_memory"
  fi
  echo "Read the complete issue context from beginning to end using bounded batches."
  echo
  echo "## Issue"
  jq -r '"- Number: #\(.number)\n- Title: \(.title // "")\n- Author: @\(.user.login // "unknown")\n- State: \(.state // "unknown")\n- Created: \(.created_at // "")\n\n### Body\n\n\(.body // "")\n\n---\n"' "$issue_json"
  echo "## Issue comments (chronological)"
} >> "$full"

comment_count=0
last_comment_id=0
while IFS= read -r encoded; do
  [[ -n "$encoded" ]] || continue
  row="$(printf '%s' "$encoded" | base64 -d 2>/dev/null || true)"
  id="$(jq -r '.[0]' <<<"$row")"
  user="$(jq -r '.[1] // "unknown"' <<<"$row")"
  created="$(jq -r '.[2] // ""' <<<"$row")"
  body="$(jq -r '.[3] // ""' <<<"$row")"
  start="$(( $(wc -l < "$full") + 1 ))"
  {
    printf '### Comment #%s — @%s — %s\n\n' "$id" "$user" "$created"
    printf '%s\n\n---\n' "$body"
  } >> "$full"
  end="$(wc -l < "$full")"
  printf '%s\t%s\t%s\t%s\n' "$id" "$created" "$start" "$end" >> "$index"
  comment_count=$((comment_count + 1))
  last_comment_id="$id"
done < <(gh api --paginate --jq '.[] | [.id, .user.login, .created_at, .body] | @base64' "/repos/$repo/issues/$target/comments?per_page=100" 2>/dev/null || true)

echo "## Pull-request review comments (chronological)" >> "$full"
while IFS= read -r encoded; do
  [[ -n "$encoded" ]] || continue
  row="$(printf '%s' "$encoded" | base64 -d 2>/dev/null || true)"
  id="$(jq -r '.[0]' <<<"$row")"
  user="$(jq -r '.[1] // "unknown"' <<<"$row")"
  created="$(jq -r '.[2] // ""' <<<"$row")"
  body="$(jq -r '.[3] // ""' <<<"$row")"
  start="$(( $(wc -l < "$full") + 1 ))"
  {
    printf '### Review comment #%s — @%s — %s\n\n' "$id" "$user" "$created"
    printf '%s\n\n---\n' "$body"
  } >> "$full"
  end="$(wc -l < "$full")"
  printf 'review:%s\t%s\t%s\t%s\n' "$id" "$created" "$start" "$end" >> "$index"
done < <(gh api --paginate --jq '.[] | [.id, .user.login, .created_at, .body] | @base64' "/repos/$repo/pulls/$target/comments?per_page=100" 2>/dev/null || true)

request="$(cat "$request_file" 2>/dev/null || jq -r '.comment.body // ""' "${GITHUB_EVENT_PATH:-/dev/null}" 2>/dev/null || true)"
reference_urls="$(grep -Eo 'https?://github\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/(issues|pull)/[0-9]+' <<<"$request" 2>/dev/null || true)"
while IFS= read -r url; do
  path="${url#https://github.com/}"
  owner="$(cut -d/ -f1 <<<"$path")"
  rrepo="$(cut -d/ -f2 <<<"$path")"
  kind="$(cut -d/ -f3 <<<"$path")"
  number="$(cut -d/ -f4 <<<"$path")"
  [[ "$number" =~ ^[0-9]+$ ]] || continue
  [[ "$kind" == issues || "$kind" == pull ]] || continue
  {
    echo
    echo "## Referenced GitHub item: $url"
    echo
    gh api "/repos/$owner/$rrepo/issues/$number" 2>/dev/null |
      jq -r '"Title: \(.title // "")\nAuthor: @\(.user.login // "unknown")\nState: \(.state // "unknown")\n\n\(.body // "")\n\n---"' || true
    gh api --paginate --jq '.[] | "### Comment #\(.id) — @\(.user.login // "unknown") — \(.created_at // "")\n\n\(.body // "")\n\n---"' "/repos/$owner/$rrepo/issues/$number/comments?per_page=100" 2>/dev/null || true
  } >> "$refs"
done < <(printf '%s\n' "$reference_urls" | sort -u | head -n 5)

full_size="$(wc -c < "$full")"
{
  head -c "$seed_bytes" "$full"
  echo
  echo "[CONTEXT SEED BOUNDARY: complete source remains in OC_ISSUE_CONTEXT_FILE; use read-oc-context.sh for additional batches.]"
  echo
  tail -c "$seed_bytes" "$full"
} > "$seed"

{
  printf 'OC_ISSUE_CONTEXT_FILE=%s\n' "$full"
  printf 'OC_ISSUE_CONTEXT_SEED_FILE=%s\n' "$seed"
  printf 'OC_ISSUE_CONTEXT_INDEX_FILE=%s\n' "$index"
  printf 'OC_REFERENCE_CONTEXT_FILE=%s\n' "$refs"
  printf 'OC_ISSUE_COMMENT_COUNT=%s\n' "$comment_count"
  printf 'OC_ISSUE_LAST_COMMENT_ID=%s\n' "$last_comment_id"
  printf 'OC_ISSUE_CONTEXT_BYTES=%s\n' "$full_size"
} >> "${GITHUB_OUTPUT:-/dev/null}"
{
  printf 'OC_ISSUE_CONTEXT_FILE=%s\n' "$full"
  printf 'OC_ISSUE_CONTEXT_SEED_FILE=%s\n' "$seed"
  printf 'OC_ISSUE_CONTEXT_INDEX_FILE=%s\n' "$index"
  printf 'OC_REFERENCE_CONTEXT_FILE=%s\n' "$refs"
  printf 'OC_ISSUE_COMMENT_COUNT=%s\n' "$comment_count"
  printf 'OC_ISSUE_LAST_COMMENT_ID=%s\n' "$last_comment_id"
  printf 'OC_ISSUE_CONTEXT_BYTES=%s\n' "$full_size"
} >> "${GITHUB_ENV:-/dev/null}"

echo "Captured complete issue context: $full ($full_size bytes, comments=$comment_count)"
