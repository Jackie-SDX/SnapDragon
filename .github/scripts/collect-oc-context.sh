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

context_degraded=false
issue_comments_complete=true
review_comments_complete=true
reference_complete=true
ci_complete=true
ci_file="$runner_temp/oc-live-ci.md"
ci_runs_json="$runner_temp/oc-live-ci-runs.json"
ci_checks_json="$runner_temp/oc-live-ci-checks.json"
: > "$ci_file"; : > "$ci_runs_json"; : > "$ci_checks_json"
target_repo="${OC_TARGET_REPO:-$repo}"
target_base="${OC_TARGET_BASE:-main}"
target_branch="${OC_TARGET_BRANCH:-}"
target_head_sha=""

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
  if [[ -n "$(printf "%s" "$issue_memory" | tr -d "[:space:]")" ]] && jq -e 'type=="object"' >/dev/null 2>&1 <<<"$issue_memory"; then
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
comments_tmp="$runner_temp/oc-comments-jsonl"
if ! gh api --paginate --jq '.[] | [.id, .user.login, .created_at, .body] | @base64' "/repos/$repo/issues/$target/comments?per_page=100" > "$comments_tmp"; then
  issue_comments_complete=false
  context_degraded=true
  : > "$comments_tmp"
fi
done < "$comments_tmp"

echo "## Pull-request review comments (chronological)" >> "$full"
if [[ "$is_pr" == "true" ]]; then
  review_tmp="$runner_temp/oc-review-comments-jsonl"
  if ! gh api --paginate --jq '.[] | [.id, .user.login, .created_at, .body] | @base64' "/repos/$repo/pulls/$target/comments?per_page=100" > "$review_tmp"; then
    review_comments_complete=false
    context_degraded=true
    : > "$review_tmp"
  fi
  while IFS= read -r encoded; do
    [[ -n "$encoded" ]] || continue
    row="$(printf '%s' "$encoded" | base64 -d 2>/dev/null || true)"
    [[ -n "$row" ]] || { review_comments_complete=false; context_degraded=true; continue; }
    id="$(jq -r '.[0]' <<<"$row")"
    user="$(jq -r '.[1] // "unknown"' <<<"$row")"
    created="$(jq -r '.[2] // ""' <<<"$row")"
    body="$(jq -r '.[3] // ""' <<<"$row")"
    start="$(( $(wc -l < "$full") + 1 ))"
    {
      printf '### Review comment #%s — @%s — %s

' "$id" "$user" "$created"
      printf '%s

---
' "$body"
    } >> "$full"
    end="$(wc -l < "$full")"
    printf 'review:%s	%s	%s	%s
' "$id" "$created" "$start" "$end" >> "$index"
  done < "$review_tmp"
else
  echo "Review comments not applicable: target is not a pull request." >> "$full"
fi

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
    if ! gh api "/repos/$owner/$rrepo/issues/$number" 2>/dev/null |
      jq -r '"Title: \(.title // "")\nAuthor: @\(.user.login // "unknown")\nState: \(.state // "unknown")\n\n\(.body // "")\n\n---"'; then
      reference_complete=false
      context_degraded=true
    fi
    if ! gh api --paginate --jq '.[] | "### Comment #\(.id) — @\(.user.login // "unknown") — \(.created_at // "")\n\n\(.body // "")\n\n---"' "/repos/$owner/$rrepo/issues/$number/comments?per_page=100"; then
      reference_complete=false
      context_degraded=true
    fi
  } >> "$refs"
done < <(printf '%s\n' "$reference_urls" | sort -u | head -n 5)

# Live CI state is an independent recovery source when durable memory is absent.
if [[ -n "$target_branch" ]]; then
  target_head_sha="$(gh api "/repos/$target_repo/commits/$target_branch" --jq '.sha // empty' 2>/dev/null || true)"
fi
if [[ -z "$target_head_sha" && "$target_repo" == "$repo" ]]; then
  target_head_sha="$(git rev-parse HEAD 2>/dev/null || true)"
fi
{
  echo
  echo "## Live CI / GitHub Actions state"
  echo "- Target repository: $target_repo"
  echo "- Target base: $target_base"
  echo "- Target branch: ${target_branch:-unknown}"
  echo "- Target HEAD: ${target_head_sha:-unknown}"
} >> "$full"

if [[ -n "$target_head_sha" ]]; then
  if gh api "/repos/$target_repo/commits/$target_head_sha/check-runs?per_page=50" > "$ci_checks_json"; then
    echo "### Check runs for HEAD" >> "$full"
    jq -r '.check_runs[]? | "- (.name): status=(.status) conclusion=(.conclusion // "pending") (.html_url // "")"' "$ci_checks_json" >> "$full" 2>/dev/null || true
  else
    ci_complete=false
    context_degraded=true
    echo "- Check-run retrieval failed." >> "$full"
  fi
fi

if [[ -n "$target_head_sha" ]]; then
  if ! gh api --paginate -f "head_sha=$target_head_sha" -f "per_page=${OC_CONTEXT_CI_RUNS:-10}" "/repos/$target_repo/actions/runs" > "$ci_runs_json"; then
    ci_complete=false
    context_degraded=true
  fi
elif [[ -n "$target_branch" ]]; then
  if ! gh api --paginate -f "branch=$target_branch" -f "per_page=${OC_CONTEXT_CI_RUNS:-10}" "/repos/$target_repo/actions/runs" > "$ci_runs_json"; then
    ci_complete=false
    context_degraded=true
  fi
else
  if ! gh api --paginate -f "per_page=${OC_CONTEXT_CI_RUNS:-10}" "/repos/$target_repo/actions/runs" > "$ci_runs_json"; then
    ci_complete=false
    context_degraded=true
  fi
fi

echo "### Recent workflow runs" >> "$full"
if [[ -s "$ci_runs_json" ]]; then
  jq -r '.workflow_runs[]? | "- run (.id): (.name // "unknown") — status=(.status // "unknown") conclusion=(.conclusion // "pending") branch=(.head_branch // "detached") sha=(.head_sha // "") (.html_url // "")"' "$ci_runs_json" | head -n "${OC_CONTEXT_CI_RUNS:-10}" >> "$full" 2>/dev/null || true
  jq -r '[.workflow_runs[]?.id] | map(select(. != null)) | map(tostring) | join(",")' "$ci_runs_json" 2>/dev/null > "$runner_temp/oc-ci-run-ids" || : > "$runner_temp/oc-ci-run-ids"
else
  echo "- No workflow runs returned for the selected target/head." >> "$full"
  : > "$runner_temp/oc-ci-run-ids"
fi
if [[ "$ci_complete" != "true" ]]; then
  echo "- CI context retrieval is degraded; re-read live Actions/checks before relying on it." >> "$full"
fi

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
  printf 'OC_CONTEXT_DEGRADED=%s\n' "$context_degraded"
  printf 'OC_CONTEXT_ISSUE_COMMENTS_COMPLETE=%s\n' "$issue_comments_complete"
  printf 'OC_CONTEXT_REVIEW_COMMENTS_COMPLETE=%s\n' "$review_comments_complete"
  printf 'OC_CONTEXT_REFERENCE_COMPLETE=%s\n' "$reference_complete"
  printf 'OC_CONTEXT_CI_COMPLETE=%s\n' "$ci_complete"
  printf 'OC_CONTEXT_CI_FILE=%s\n' "$ci_file"
  printf 'OC_CONTEXT_CI_RUN_IDS=%s\n' "$(cat "$runner_temp/oc-ci-run-ids" 2>/dev/null || true)"
  printf 'OC_CONTEXT_TARGET_REPOSITORY=%s\n' "$target_repo"
  printf 'OC_CONTEXT_TARGET_HEAD_SHA=%s\n' "$target_head_sha"
} >> "${GITHUB_OUTPUT:-/dev/null}"
{
  printf 'OC_ISSUE_CONTEXT_FILE=%s\n' "$full"
  printf 'OC_ISSUE_CONTEXT_SEED_FILE=%s\n' "$seed"
  printf 'OC_ISSUE_CONTEXT_INDEX_FILE=%s\n' "$index"
  printf 'OC_REFERENCE_CONTEXT_FILE=%s\n' "$refs"
  printf 'OC_ISSUE_COMMENT_COUNT=%s\n' "$comment_count"
  printf 'OC_ISSUE_LAST_COMMENT_ID=%s\n' "$last_comment_id"
  printf 'OC_ISSUE_CONTEXT_BYTES=%s\n' "$full_size"
  printf 'OC_CONTEXT_DEGRADED=%s\n' "$context_degraded"
  printf 'OC_CONTEXT_ISSUE_COMMENTS_COMPLETE=%s\n' "$issue_comments_complete"
  printf 'OC_CONTEXT_CI_COMPLETE=%s\n' "$ci_complete"
  printf 'OC_CONTEXT_CI_FILE=%s\n' "$ci_file"
  printf 'OC_CONTEXT_CI_RUN_IDS=%s\n' "$(cat "$runner_temp/oc-ci-run-ids" 2>/dev/null || true)"
  printf 'OC_CONTEXT_TARGET_REPOSITORY=%s\n' "$target_repo"
  printf 'OC_CONTEXT_TARGET_HEAD_SHA=%s\n' "$target_head_sha"
} >> "${GITHUB_ENV:-/dev/null}"

if [[ "$context_degraded" == "true" ]]; then
  echo "::warning title=/oc context degraded::One or more issue/comment/CI context sources could not be verified. The agent must use live repository, issue/PR, and CI reads before acting."
else
  echo "Captured complete issue context plus live CI state: $full ($full_size bytes, comments=$comment_count)"
fi
