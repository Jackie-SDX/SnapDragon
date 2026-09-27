#!/usr/bin/env bash
set -euo pipefail

repo="${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"
target=0
if [[ -n "$GITHUB_EVENT_PATH" && -f "$GITHUB_EVENT_PATH" ]]; then
  target="$(jq -r '.issue.number // .pull_request.number // 0' "$GITHUB_EVENT_PATH" 2>/dev/null || printf '0')"
fi
[[ "$target" =~ ^[0-9]+$ && "$target" != "0" ]] || exit 0

run_id="${GITHUB_RUN_ID:-0}"
result_marker="<!-- oc-controller-result:run-$run_id -->"
marker="$result_marker"
if gh api --paginate --slurp "/repos/$repo/issues/$target/comments?per_page=100" 2>/dev/null |
    jq -e --arg marker "$result_marker" 'add // [] | any(.[]; (.body // "") | contains($marker))' >/dev/null 2>&1; then
  exit 0
fi

body=""
clarification_file="$RUNNER_TEMP/oc-clarification.md"

sanitize_response() {
  local file="$1"
  sed -E \
    -e '/^\[OPENCODE\]/d' \
    -e '/\[object Object\]/d' \
    -e 's/OC-STATUS:[[:space:]]*//g' \
    -e 's/OC-PLAN:[[:space:]]*//g' \
    -e 's/OC-DONE:[[:space:]]*//g' \
    -e '/^[[:space:]]*Thinking:[[:space:]]*/d' \
    -e '/^[[:space:]]*⚙[[:space:]]*/d' \
    -e '/^[[:space:]]*\\$[[:space:]]+/d' \
    -e 's/(gh[ps]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,})/[REDACTED_GITHUB_TOKEN]/g' \
    -e 's/(Bearer[[:space:]]+)[^[:space:]]+/\1[REDACTED]/g' \
    "$file" | tr -d '\r' | sed 's/[[:space:]]*$//' | cut -c1-12000
}

if [ -s "$clarification_file" ]; then
  question="$(sanitize_response "$clarification_file")"
  if [ -n "$question" ]; then
    body="$(printf "%s\n## /oc - clarification required\n\n%s\n\nPlease answer this question in the issue/PR, then resume with /oc continue." "$marker" "$question")"
  else
    body="$(printf "%s\n## /oc - clarification required\n\nThe agent needs clarification before it can safely continue. Answer the latest question, then resume with /oc continue." "$marker")"
  fi
  OC_SESSION_PHASE="waiting_for_clarification" OC_SESSION_STATUS="waiting" OC_SESSION_NEXT_ACTION="answer the clarification request, then resume with /oc continue" OC_SESSION_EVIDENCE="clarification request published from headless OpenCode session" OC_DURABLE_WORK="false" bash .github/scripts/record-oc-session-progress.sh >/dev/null 2>&1 || true
elif [[ "${MERGE_REQUESTED:-false}" == "true" ]]; then
  if [[ "${MERGE_EXIT:-1}" == "0" ]]; then
    body="$(printf "%s\n## /oc\nMerge command completed successfully." "$marker")"
  else
    body="$(printf "%s\n## /oc\nMerge command did not complete successfully. No merge success is claimed." "$marker")"
  fi
else
  runner_temp="$(printenv RUNNER_TEMP || printf /tmp)"
  response_file="$runner_temp/opencode-final-response-1.md"
  answer=""
  a1="$(printenv A1 || true)"
  if [[ "$a1" == "success" && -s "$response_file" ]]; then
    answer="$(sanitize_response "$response_file")"
  fi
  if [[ -n "$answer" ]]; then
    body="$(printf "%s\n## /oc\n\n%s" "$marker" "$answer")"
  elif [[ "$a1" == "success" ]]; then
    body="$(printf "%s\n## /oc\nOpenCode completed, but its final response was not captured." "$marker")"
  else
    body="$(printf "%s\n## /oc\nOpenCode did not complete successfully. No success is claimed." "$marker")"
  fi
fi

if [[ "${A1:-}" == "success" && "${MERGE_REQUESTED:-false}" != "true" ]]; then
  OC_SESSION_PHASE="completed" \
  OC_SESSION_STATUS="complete" \
  OC_SESSION_MILESTONE="response_published" \
  OC_SESSION_NEXT_ACTION="await user direction" \
  OC_SESSION_EVIDENCE="final OpenCode response published by run $run_id" \
  OC_DURABLE_WORK="false" \
  bash .github/scripts/record-oc-session-progress.sh >/dev/null 2>&1 || true
elif [[ "${CL1:-false}" == "true" ]]; then
  OC_SESSION_PHASE="waiting_for_clarification" \
  OC_SESSION_STATUS="waiting" \
  OC_SESSION_MILESTONE="clarification_requested" \
  OC_SESSION_NEXT_ACTION="answer the clarification request, then resume with /oc continue" \
  OC_DURABLE_WORK="false" \
  bash .github/scripts/record-oc-session-progress.sh >/dev/null 2>&1 || true
fi

body="$body"$'\n'"$result_marker"
if [[ -n "${GH_COMMENT_FILE:-}" ]]; then
  # Deterministic test seam used by controller contract tests; production keeps
  # the API-based publisher unchanged.
  gh issue comment "$target" --body "$body" >/dev/null
else
  gh api -X POST -f body="$body" "/repos/$repo/issues/$target/comments" >/dev/null
fi
