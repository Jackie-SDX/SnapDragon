#!/usr/bin/env bash
set -u

target="${TARGET_NUMBER:-0}"
repo="${GITHUB_REPOSITORY:-}"
runner_temp="${RUNNER_TEMP:-/tmp}"
request_file="${OC_REQUEST_FILE:-$runner_temp/oc-request.txt}"
full="$runner_temp/oc-issue-context-full.md"
seed="$runner_temp/oc-issue-context-seed.md"
index="$runner_temp/oc-issue-context.index"
refs="$runner_temp/oc-reference-context.md"
ci_file="$runner_temp/oc-live-ci.md"
seed_bytes="${OC_CONTEXT_SEED_BYTES:-60000}"
ci_limit="${OC_CONTEXT_CI_RUNS:-10}"

mkdir -p "$runner_temp"
: > "$full"
: > "$index"
: > "$refs"
: > "$ci_file"

[[ "$target" =~ ^[0-9]+$ && "$target" != 0 && -n "$repo" ]] || {
  echo "::warning title=/oc context unavailable::Issue number or repository was not available to the on-demand collector."
  exit 0
}

context_degraded=false
issue_complete=true
comments_complete=true
reviews_complete=true
ci_complete=true
references_complete=true
comment_count=0
last_comment_id=0
: > "$runner_temp/oc-ci-run-ids"

issue_json="$runner_temp/oc-issue.json"
comments_file="$runner_temp/oc-comments.jsonl"
reviews_file="$runner_temp/oc-review-comments.jsonl"

if ! gh api "/repos/$repo/issues/$target" > "$issue_json" 2>/dev/null; then
  issue_complete=false
  context_degraded=true
  printf '{"number":%s,"title":"","body":"","state":"unknown"}\n' "$target" > "$issue_json"
fi

memory_marker="<!-- oc-session-memory:v2 issue:$target -->"
memory_end="<!-- /oc-session-memory -->"
issue_memory="$(jq -r '.body // ""' "$issue_json" 2>/dev/null | awk -v a="$memory_marker" -v b="$memory_end" 'index($0,a){inside=1;next} index($0,b){inside=0;next} inside{print}' | sed -n '/^STATE-BEGIN$/,/^STATE-END$/p' | sed '1d;$d')"
is_pr="$(jq -r 'if .pull_request then "true" else "false" end' "$issue_json" 2>/dev/null || printf false)"

{
  echo "# /oc on-demand context"
  echo
  echo "Source: GitHub issue/PR #$target in $repo"
  echo "This context was explicitly collected on demand. The agent chose to retrieve it for the current request."
  echo
  if [[ -n "$(printf "%s" "$issue_memory" | tr -d "[:space:]")" ]] && jq -e 'type=="object"' >/dev/null 2>&1 <<<"$issue_memory"; then
    echo "## Durable /oc memory"
    jq . <<<"$issue_memory"
    echo
  fi
  echo "## Issue / PR"
  jq -r '"- Number: #\(.number)\n- Title: \(.title // "")\n- State: \(.state // "unknown")\n\n\(.body // "")"' "$issue_json" 2>/dev/null
  echo
  echo "## Issue comments (chronological)"
} >> "$full"

if ! gh api --paginate --jq '.[] | [.id, .user.login, .created_at, .body] | @base64' "/repos/$repo/issues/$target/comments?per_page=100" > "$comments_file" 2>/dev/null; then
  comments_complete=false
  context_degraded=true
  : > "$comments_file"
fi

while IFS= read -r encoded; do
  [[ -n "$encoded" ]] || continue
  row="$(printf "%s" "$encoded" | base64 -d 2>/dev/null || true)"
  if [[ -z "$row" ]]; then
    comments_complete=false
    context_degraded=true
    continue
  fi
  id="$(jq -r '.[0] // 0' <<<"$row")"
  user="$(jq -r '.[1] // "unknown"' <<<"$row")"
  created="$(jq -r '.[2] // ""' <<<"$row")"
  body="$(jq -r '.[3] // ""' <<<"$row")"
  start="$(( $(wc -l < "$full") + 1 ))"
  {
    printf '### Comment #%s — @%s — %s\n\n' "$id" "$user" "$created"
    printf "%s\n\n---\n" "$body"
  } >> "$full"
  end="$(wc -l < "$full")"
  printf "%s\t%s\t%s\t%s\n" "$id" "$created" "$start" "$end" >> "$index"
  comment_count=$((comment_count + 1))
  last_comment_id="$id"
done < "$comments_file"

echo "## Pull-request review comments (chronological)" >> "$full"
if [[ "$is_pr" == "true" ]]; then
  if ! gh api --paginate --jq '.[] | [.id, .user.login, .created_at, .body] | @base64' "/repos/$repo/pulls/$target/comments?per_page=100" > "$reviews_file" 2>/dev/null; then
    reviews_complete=false
    context_degraded=true
    : > "$reviews_file"
  fi
  while IFS= read -r encoded; do
    [[ -n "$encoded" ]] || continue
    row="$(printf "%s" "$encoded" | base64 -d 2>/dev/null || true)"
    [[ -n "$row" ]] || { reviews_complete=false; context_degraded=true; continue; }
    id="$(jq -r '.[0] // 0' <<<"$row")"
    user="$(jq -r '.[1] // "unknown"' <<<"$row")"
    created="$(jq -r '.[2] // ""' <<<"$row")"
    body="$(jq -r '.[3] // ""' <<<"$row")"
    start="$(( $(wc -l < "$full") + 1 ))"
    {
      printf '### Review comment #%s — @%s — %s\n\n' "$id" "$user" "$created"
      printf "%s\n\n---\n" "$body"
    } >> "$full"
    end="$(wc -l < "$full")"
    printf "review:%s\t%s\t%s\t%s\n" "$id" "$created" "$start" "$end" >> "$index"
  done < "$reviews_file"
else
  echo "Review comments not applicable: target is not a pull request." >> "$full"
fi

request="$(cat "$request_file" 2>/dev/null || true)"
reference_urls="$(grep -Eo 'https?://github\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/(issues|pull)/[0-9]+' <<<"$request" 2>/dev/null | sort -u | head -n 5)"
while IFS= read -r url; do
  [[ -n "$url" ]] || continue
  path="${url#https://github.com/}"
  owner="$(cut -d/ -f1 <<<"$path")"
  rrepo="$(cut -d/ -f2 <<<"$path")"
  kind="$(cut -d/ -f3 <<<"$path")"
  number="$(cut -d/ -f4 <<<"$path")"
  [[ "$number" =~ ^[0-9]+$ ]] || continue
  {
    echo
    echo "## Referenced GitHub item: $url"
    if ! gh api "/repos/$owner/$rrepo/issues/$number" 2>/dev/null | jq -r '"Title: \(.title // "")\nState: \(.state // "unknown")\n\(.body // "")"'; then
      references_complete=false
      context_degraded=true
    fi
    if ! gh api --paginate --jq '.[] | "### Comment #\(.id) — @\(.user.login // "unknown") — \(.created_at // "")\n\n\(.body // "")\n\n---"' "/repos/$owner/$rrepo/issues/$number/comments?per_page=100" 2>/dev/null; then
      references_complete=false
      context_degraded=true
    fi
  } >> "$refs"
done <<< "$reference_urls"

target_repo="${OC_TARGET_REPO:-$repo}"
target_base="${OC_TARGET_BASE:-main}"
target_branch="${OC_TARGET_BRANCH:-}"
target_head_sha=""
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
  if gh api "/repos/$target_repo/commits/$target_head_sha/check-runs?per_page=50" > "$runner_temp/oc-check-runs.json" 2>/dev/null; then
    jq -r '.check_runs[]? | "- \(.name): status=\(.status) conclusion=\(.conclusion // "pending") \(.html_url // "")"' "$runner_temp/oc-check-runs.json" >> "$full" 2>/dev/null || true
  else
    ci_complete=false
    context_degraded=true
    echo "- Check-run retrieval failed." >> "$full"
  fi
fi

if [[ -n "$target_head_sha" ]]; then
  gh api --method GET --paginate -f "head_sha=$target_head_sha" -f "per_page=$ci_limit" "/repos/$target_repo/actions/runs" > "$runner_temp/oc-workflow-runs.json" 2>/dev/null || { ci_complete=false; context_degraded=true; : > "$runner_temp/oc-workflow-runs.json"; }
elif [[ -n "$target_branch" ]]; then
  gh api --method GET --paginate -f "branch=$target_branch" -f "per_page=$ci_limit" "/repos/$target_repo/actions/runs" > "$runner_temp/oc-workflow-runs.json" 2>/dev/null || { ci_complete=false; context_degraded=true; : > "$runner_temp/oc-workflow-runs.json"; }
else
  gh api --method GET --paginate -f "per_page=$ci_limit" "/repos/$target_repo/actions/runs" > "$runner_temp/oc-workflow-runs.json" 2>/dev/null || { ci_complete=false; context_degraded=true; : > "$runner_temp/oc-workflow-runs.json"; }
fi

echo "### Recent workflow runs" >> "$full"
if [[ -s "$runner_temp/oc-workflow-runs.json" ]]; then
  jq -r '.workflow_runs[]? | "- run \(.id): \(.name // "unknown") — status=\(.status // "unknown") conclusion=\(.conclusion // "pending") branch=\(.head_branch // "detached") sha=\(.head_sha // "") \(.html_url // "")"' "$runner_temp/oc-workflow-runs.json" | head -n "$ci_limit" >> "$full" 2>/dev/null || true
  jq -r '[.workflow_runs[]?.id] | map(select(. != null)) | map(tostring) | join(",")' "$runner_temp/oc-workflow-runs.json" 2>/dev/null > "$runner_temp/oc-ci-run-ids" || : > "$runner_temp/oc-ci-run-ids"
else
  echo "- No workflow runs returned for the selected target/head." >> "$full"
fi

full_size="$(wc -c < "$full")"
{
  printf "OC_ISSUE_CONTEXT_FILE=%s\n" "$full"
  printf "OC_ISSUE_CONTEXT_SEED_FILE=%s\n" "$seed"
  printf "OC_ISSUE_CONTEXT_INDEX_FILE=%s\n" "$index"
  printf "OC_REFERENCE_CONTEXT_FILE=%s\n" "$refs"
  printf "OC_ISSUE_COMMENT_COUNT=%s\n" "$comment_count"
  printf "OC_ISSUE_LAST_COMMENT_ID=%s\n" "$last_comment_id"
  printf "OC_ISSUE_CONTEXT_BYTES=%s\n" "$full_size"
  printf "OC_CONTEXT_DEGRADED=%s\n" "$context_degraded"
  printf "OC_CONTEXT_ISSUE_COMPLETE=%s\n" "$issue_complete"
  printf "OC_CONTEXT_COMMENTS_COMPLETE=%s\n" "$comments_complete"
  printf "OC_CONTEXT_REVIEWS_COMPLETE=%s\n" "$reviews_complete"
  printf "OC_CONTEXT_REFERENCES_COMPLETE=%s\n" "$references_complete"
  printf "OC_CONTEXT_CI_COMPLETE=%s\n" "$ci_complete"
  printf "OC_CONTEXT_CI_RUN_IDS=%s\n" "$(cat "$runner_temp/oc-ci-run-ids" 2>/dev/null || true)"
} >> "${GITHUB_ENV:-/dev/null}"

{
  printf "OC_ISSUE_CONTEXT_FILE=%s\n" "$full"
  printf "OC_ISSUE_CONTEXT_SEED_FILE=%s\n" "$seed"
  printf "OC_ISSUE_CONTEXT_INDEX_FILE=%s\n" "$index"
  printf "OC_REFERENCE_CONTEXT_FILE=%s\n" "$refs"
  printf "OC_ISSUE_COMMENT_COUNT=%s\n" "$comment_count"
  printf "OC_ISSUE_LAST_COMMENT_ID=%s\n" "$last_comment_id"
  printf "OC_ISSUE_CONTEXT_BYTES=%s\n" "$full_size"
  printf "OC_CONTEXT_DEGRADED=%s\n" "$context_degraded"
  printf "OC_CONTEXT_ISSUE_COMPLETE=%s\n" "$issue_complete"
  printf "OC_CONTEXT_COMMENTS_COMPLETE=%s\n" "$comments_complete"
  printf "OC_CONTEXT_REVIEWS_COMPLETE=%s\n" "$reviews_complete"
  printf "OC_CONTEXT_REFERENCES_COMPLETE=%s\n" "$references_complete"
  printf "OC_CONTEXT_CI_COMPLETE=%s\n" "$ci_complete"
  printf "OC_CONTEXT_CI_RUN_IDS=%s\n" "$(cat "$runner_temp/oc-ci-run-ids" 2>/dev/null || true)"
} >> "${GITHUB_OUTPUT:-/dev/null}"

if [[ "$context_degraded" == "true" ]]; then
  echo "::warning title=/oc context degraded::On-demand context collection completed with one or more unavailable sources. Use the available evidence and verify missing pieces directly before acting."
else
  echo "On-demand /oc context collected: $full ($full_size bytes, comments=$comment_count)"
fi

# The collector is deliberately non-fatal: the agent asked for context and can continue
# with whatever it can retrieve, rather than losing the entire /oc turn because one source failed.
exit 0
