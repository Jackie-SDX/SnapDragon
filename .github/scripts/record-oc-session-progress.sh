#!/usr/bin/env bash
set -euo pipefail

state_file="${OC_SESSION_STATE_FILE:-${RUNNER_TEMP:-/tmp}/oc-session-state.json}"
[[ -f "$state_file" ]] || exit 0

phase="${OC_SESSION_PHASE:-${SESSION_PHASE:-}}"
status="${OC_SESSION_STATUS:-${SESSION_STATUS:-active}}"
next_action="${OC_SESSION_NEXT_ACTION:-${SESSION_NEXT_ACTION:-}}"
request="${OC_COMMAND_TEXT:-${SESSION_REQUEST:-}}"
goal="$request"
milestone="${OC_SESSION_MILESTONE:-${SESSION_MILESTONE:-${phase:-working}}}"
evidence="${OC_SESSION_EVIDENCE:-${SESSION_EVIDENCE:-}}"
session_branch="${OC_SESSION_BRANCH:-}"
head_sha="${OC_SESSION_HEAD_SHA:-}"
if [[ -z "$head_sha" && -n "$session_branch" ]]; then head_sha="$(git rev-parse "$session_branch" 2>/dev/null || true)"; fi
pr_number="${OC_SESSION_PR_NUMBER:-0}"
[[ "$pr_number" =~ ^[0-9]+$ ]] || pr_number=0
pr_url="${OC_SESSION_PR_URL:-}"
comment_id="${OC_ISSUE_LAST_COMMENT_ID:-0}"
[[ "$comment_id" =~ ^[0-9]+$ ]] || comment_id=0
run_id="${GITHUB_RUN_ID:-}"
durable="${OC_DURABLE_WORK:-false}"
[[ "$durable" == "true" || "$durable" == "false" ]] || durable=false
attempt="${OC_SESSION_ATTEMPT:-}"
target_repo="${OC_TARGET_REPO:-}"
target_base="${OC_TARGET_BASE:-}"
target_branch="${OC_TARGET_BRANCH:-${session_branch:-}}"
termination="${OC_TERMINATION_REASON:-}"
context_degraded="${OC_CONTEXT_DEGRADED:-false}"
ci_run_ids="${OC_CONTEXT_CI_RUN_IDS:-}"
native_session_id="${OC_NATIVE_SESSION_ID:-}"
native_session_run_id="${OC_NATIVE_SESSION_RUN_ID:-${GITHUB_RUN_ID:-}}"
native_session_artifact="${OC_NATIVE_SESSION_ARTIFACT:-}"
native_session_exported="${OC_NATIVE_SESSION_EXPORTED:-false}"
now="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

json="$(jq   --arg phase "$phase"   --arg status "$status"   --arg next "$next_action"   --arg request "$request"   --arg goal "$goal"   --arg milestone "$milestone"   --arg evidence "$evidence"   --arg branch "$session_branch"   --arg head "$head_sha"   --arg pr_url "$pr_url"   --arg run "$run_id"   --arg now "$now"   --arg attempt "$attempt"   --arg target_repo "$target_repo"   --arg target_base "$target_base"   --arg target_branch "$target_branch"   --arg termination "$termination"   --arg native_session_id "$native_session_id"   --arg native_session_run_id "$native_session_run_id"   --arg native_session_artifact "$native_session_artifact"   --arg native_session_exported "$native_session_exported"   --argjson pr "${pr_number:-0}"   --argjson comment "${comment_id:-0}"   --arg context_degraded "$context_degraded"   --arg ci_run_ids "$ci_run_ids"   --argjson durable "${durable:-false}"   '.phase = (if $phase != "" then $phase else .phase end)
   | .status = (if $status != "" then $status else .status end)
   | .next_action = (if $next != "" then $next else .next_action end)
   | .current_request = (if $request != "" then $request else .current_request end)
   | .goal = (if $goal != "" then $goal else (.goal // .current_request // "") end)
   | .milestone = (if $milestone != "" then $milestone else (.milestone // .phase // "working") end)
   | .last_verified_sha = (if $head != "" then $head else .last_verified_sha end)
   | .last_verified_evidence = (if $evidence != "" then $evidence else .last_verified_evidence end)
   | .active_branch = (if $branch != "" then $branch else .active_branch end)
   | .active_head_sha = (if $head != "" then $head else .active_head_sha end)
   | .active_pr_number = (if $pr > 0 then $pr else .active_pr_number end)
   | .active_pr_url = (if $pr_url != "" then $pr_url else .active_pr_url end)
   | .last_processed_comment_id = (if $comment > 0 then $comment else .last_processed_comment_id end)
   | .last_run_id = (if $run != "" then ($run|tonumber) else .last_run_id end)
   | .durable_work = $durable
   | .target_repository = (if $target_repo != "" then $target_repo else (.target_repository // "") end)
   | .target_base = (if $target_base != "" then $target_base else (.target_base // .base_ref // "main") end)
   | .target_branch = (if $target_branch != "" then $target_branch else (.target_branch // .active_branch // "") end)
   | .agent_attempt = (if $attempt != "" then ($attempt|tonumber) else .agent_attempt end)
   | .termination_reason = (if $termination != "" then $termination else .termination_reason end)
   | .native_session_id = (if $native_session_id != "" then $native_session_id else (.native_session_id // "") end)
   | .native_session_run_id = (if ($native_session_run_id | test("^[0-9]+$")) then ($native_session_run_id|tonumber) else .native_session_run_id end)
   | .native_session_artifact = (if $native_session_artifact != "" then $native_session_artifact else (.native_session_artifact // "") end)
   | .native_session_exported = (if $native_session_exported == "true" then true else (.native_session_exported // false) end)
   | .ci_runs = (if $ci_run_ids != "" then ($ci_run_ids | split(",") | map(select(test("^[0-9]+$")) | tonumber)) else .ci_runs end)
   | .warnings = (if $context_degraded == "true" then (((.warnings // []) + ["context retrieval degraded; verify live issue/PR and CI state before acting"]) | unique) else .warnings end)
   | .updated_at = $now
   | .last_checkpoint_at = $now
   | .state_revision = ((.state_revision // 0) + 1)' "$state_file")"

printf '%s\n' "$json" > "$state_file"
persist_rc=0
SESSION_JSON="$json" bash .github/scripts/oc-session-state.sh set || persist_rc=$?
memory_persisted=true
[[ "$persist_rc" -eq 0 ]] || memory_persisted=false
printf 'OC_SESSION_MEMORY_PERSISTED=%s\n' "$memory_persisted" >> "${GITHUB_ENV:-/dev/null}"
printf 'memory_persisted=%s\n' "$memory_persisted" >> "${GITHUB_OUTPUT:-/dev/null}"
echo "Session checkpoint: phase=$(jq -r '.phase' "$state_file") revision=$(jq -r '.state_revision' "$state_file") durable=$(jq -r '.durable_work' "$state_file") memory_persisted=$memory_persisted"
