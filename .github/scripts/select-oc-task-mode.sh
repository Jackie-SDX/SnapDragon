#!/usr/bin/env bash
set -euo pipefail

body="$(jq -r '.comment.body // empty' "${GITHUB_EVENT_PATH:-/dev/null}" 2>/dev/null || true)"
request="$(printf '%s' "$body" | sed -E 's#^/(oc|opencode)[[:space:]]*##')"
lower="$(printf '%s' "$request" | tr '[:upper:]' '[:lower:]')"

new_session_requested=false
if [[ "$request" =~ (^|[[:space:]])--new([[:space:]]|$) ]]; then
  new_session_requested=true
  request="$(printf '%s' "$request" | sed -E "s/(^|[[:space:]])--new([[:space:]]|$)/\\1/g" | sed -E "s/[[:space:]]+/ /g; s/^[[:space:]]+//; s/[[:space:]]+$//")"
  lower="$(printf '%s' "$request" | tr '[:upper:]' '[:lower:]')"
fi

session_exists=false
target_number="${TARGET_NUMBER:-0}"
if [[ "$new_session_requested" != "true" && "$target_number" =~ ^[0-9]+$ && "$target_number" != 0 ]]; then
  issue_body="$(gh issue view "$target_number" --repo "${GITHUB_REPOSITORY:-}" --json body --jq .body 2>/dev/null || true)"
  if [[ -z "$issue_body" && -n "${GITHUB_REPOSITORY:-}" ]]; then
    issue_body="$(gh api "/repos/${GITHUB_REPOSITORY}/issues/${target_number}" --jq .body 2>/dev/null || true)"
  fi
  if grep -Fq "<!-- oc-session-memory:v2 issue:${target_number} -->" <<<"$issue_body"; then
    session_exists=true
  fi
fi

runner_temp="${RUNNER_TEMP:-/tmp}"
request_file="$runner_temp/oc-request.txt"
printf '%s\n' "$request" > "$request_file"

mode=code
intent=execute
resume_requested=false
merge_requested=false
session_required=true
branch_required=false

if [[ "$new_session_requested" == "true" ]]; then
  session_required=true
  branch_required=true
  mode=code
  intent=code
elif [[ "$session_exists" == "true" ]]; then
  resume_requested=true
  session_required=true
  branch_required=true
  mode=code
  intent=code
elif [[ "$lower" =~ ^continue([[:space:]]|$) ]] &&
   ! printf '%s' "$lower" | grep -Eiq '\b(story|stories|chapter|fiction|poem|poetry|essay|prose|dialogue|joke|caption|lyrics?|creative|co-?author|part[[:space:]-]*[0-9]+)\b'; then
  resume_requested=true
  session_required=true
  branch_required=true
  mode=code
  intent=continue
  request="$(printf '%s' "$request" | sed -E 's#^continue[[:space:]]*##')"
  printf '%s\n' "$request" > "$request_file"
elif [[ "$lower" =~ ^merge([[:space:]]|$) ]]; then
  merge_requested=true
  session_required=true
  branch_required=true
  mode=merge
  intent=merge
else
  positive_request="$(printf '%s' "$lower" | sed -E -e '/^[[:space:]]*(do not|dont|don'\''t|without)\b/d' -e 's/\b(do not|dont|don'\''t|without)\b.*$//g')"

  if printf '%s' "$positive_request" | grep -Eiq '\b(create|open|publish|submit)[[:space:]]+(a[[:space:]]+)?(pull[[:space:]-]*request|pr)\b'; then
    session_required=true
    branch_required=true
    intent=publish
  elif printf '%s' "$positive_request" | grep -Eiq '\b(fix|edit|change|modify|implement|add|remove|create|delete|refactor|debug|repair|update|build|write|test|patch|migrate|replace|rename|commit|push|merge|release)\b'; then
    session_required=true
    branch_required=true
    intent=code
  fi
fi

emit_value() {
  local key="$1" value="$2" file="$3" delim
  if [[ "$value" == *$'\n'* ]]; then
    delim="EOF_${key}_${RANDOM}_${RANDOM}"
    while printf '%s' "$value" | grep -Fq "$delim"; do
      delim="EOF_${key}_${RANDOM}_${RANDOM}"
    done
    printf '%s<<%s\n%s\n%s\n' "$key" "$delim" "$value" "$delim" >> "$file"
  else
    printf '%s=%s\n' "$key" "$value" >> "$file"
  fi
}

emit_env(){ emit_value "$1" "$2" "${GITHUB_ENV:-/dev/null}"; emit_value "$1" "$2" "${GITHUB_OUTPUT:-/dev/null}"; }
emit_out(){ emit_value "$1" "$2" "${GITHUB_OUTPUT:-/dev/null}"; }

emit_env OC_COMMAND_TEXT "$request"
emit_out mode "$mode"
emit_out intent "$intent"
emit_env OC_REQUEST_FILE "$request_file"
emit_env OC_TASK_MODE "$mode"
emit_env OC_INTENT "$intent"
emit_env OC_RESUME_REQUESTED "$resume_requested"
emit_env OC_NEW_SESSION_REQUEST "$new_session_requested"
emit_env OC_MERGE_REQUESTED "$merge_requested"
emit_env OC_SESSION_REQUIRED "$session_required"
emit_env OC_SESSION_BRANCH_REQUIRED "$branch_required"

echo "Selected /oc mode=$mode intent=$intent resume=$resume_requested merge=$merge_requested"
