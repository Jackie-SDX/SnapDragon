#!/usr/bin/env bash
set -u

attempt="${ATTEMPT:-1}"
model="${MODEL:-opencode/mimo-v2.6-flash-free}"
mode="${OC_TARGET_MODE:-local}"
task_mode="${TASK_MODE:-code}"
publish_requested="${OC_PUBLISH_REQUESTED:-${PUBLISH_REQUESTED:-false}}"
initial_sha="${INITIAL_SHA:-}"
output_file="${GITHUB_OUTPUT:-/dev/null}"
start_epoch="$(date +%s)"
controller_gh_token="${OC_CONTROLLER_GH_TOKEN:-}"
controller_universal_token="${OC_CONTROLLER_UNIVERSAL_TOKEN:-}"

out() { printf '%s=%s\n' "$1" "$2" >> "$output_file"; }
read_back() { sed -nE "s/^${1}=//p" "$output_file" 2>/dev/null | tail -n 1; }

set +e
MODEL="$model" VARIANT="" SHARE="false" AGENT="build" bash .github/scripts/run-opencode-attempt.sh "$attempt"
agent_rc=$?
set -e

agent_outcome="failure"
[[ "$agent_rc" -eq 0 ]] && agent_outcome="success"
termination_reason="$(read_back termination_reason)"
[[ -n "$termination_reason" ]] || termination_reason="failed"
# Read before use: under `set -u`, referencing clarification_required before
# this assignment aborts the whole attempt before a single output is written.
clarification_required="$(read_back clarification_required)"
[[ -n "$clarification_required" ]] || clarification_required="false"
[[ "$clarification_required" == "true" ]] && agent_outcome="clarification"
timed_out="false"
[[ "$termination_reason" == "timeout" ]] && timed_out="true"
safe_log_path="$(read_back safe_log_path)"
agent_branch="$(read_back agent_branch)"
session_head_sha="$(read_back session_head_sha)"
durable_work="false"
remote_dirty="false"
remote_base_sha=""

if [[ "$mode" == "remote" && -n "${OC_TARGET_WORKSPACE:-}" && -d "${OC_TARGET_WORKSPACE:-}" ]]; then
  remote_head="${session_head_sha:-$(git -C "$OC_TARGET_WORKSPACE" rev-parse HEAD 2>/dev/null || true)}"
  remote_base="$(git -C "$OC_TARGET_WORKSPACE" rev-parse "origin/${OC_TARGET_BASE:-main}" 2>/dev/null || true)"
  remote_dirty="$(git -C "$OC_TARGET_WORKSPACE" status --porcelain 2>/dev/null || true)"
  if [[ -n "$remote_dirty" || ( "$remote_head" =~ ^[0-9a-f]{40}$ && "$remote_base" =~ ^[0-9a-f]{40}$ && "$remote_head" != "$remote_base" ) ]]; then
    durable_work="true"
  fi
  [[ -n "$agent_branch" ]] || agent_branch="${OC_TARGET_BRANCH:-$(git -C "$OC_TARGET_WORKSPACE" branch --show-current 2>/dev/null || true)}"
  session_head_sha="$remote_head"
  remote_base_sha="$remote_base"
elif [[ -n "$agent_branch" ]]; then
  [[ -n "$session_head_sha" ]] || session_head_sha="$(git rev-parse "$agent_branch" 2>/dev/null || true)"
  if [[ "$initial_sha" =~ ^[0-9a-f]{40}$ && "$session_head_sha" =~ ^[0-9a-f]{40}$ && "$session_head_sha" != "$initial_sha" ]]; then
    durable_work="true"
  fi
fi

# Persist an actionable resume checkpoint whenever the attempt times out or durable work exists.
if [[ "$termination_reason" == "timeout" || "$durable_work" == "true" ]]; then
  OC_SESSION_PHASE="checkpointed" \
  OC_SESSION_STATUS="active" \
  OC_SESSION_MILESTONE="budget_or_progress_checkpoint" \
  OC_SESSION_NEXT_ACTION="resume with /oc continue; recover the durable target branch, inspect current work and CI, and continue without repeating completed work" \
  OC_SESSION_EVIDENCE="attempt=$attempt; termination=$termination_reason; target=${OC_TARGET_REPO:-local}; branch=${agent_branch:-unknown}; head=${session_head_sha:-unknown}; dirty=${remote_dirty:-unknown}; durable_work=$durable_work" \
  OC_SESSION_BRANCH="$agent_branch" \
  OC_SESSION_HEAD_SHA="$session_head_sha" \
  OC_SESSION_ATTEMPT="$attempt" \
  OC_TERMINATION_REASON="$termination_reason" \
  OC_DURABLE_WORK="$durable_work" \
  bash .github/scripts/record-oc-session-progress.sh || true
fi
publish_outcome="not-requested"
pr_url="$(read_back pr_url)"
publish_rc=0

result_state="failed"
[[ "$agent_outcome" == "success" ]] && result_state="completed"
[[ "$agent_outcome" == "clarification" ]] && result_state="awaiting-input"
[[ "$durable_work" == "true" && "$agent_outcome" != "success" ]] && result_state="checkpointed"

out agent_outcome "$agent_outcome"
out clarification_required "$clarification_required"
out termination_reason "$termination_reason"
out timed_out "$timed_out"
out safe_log_path "$safe_log_path"
out durable_work "$durable_work"
out session_head_sha "$session_head_sha"
out publish_outcome "$publish_outcome"
out pr_url "$pr_url"
out verified "false"
out verified_sha ""
out ci_surfaces "unobserved"
out ci_run_id ""
out result_state "$result_state"
out attempt_elapsed_seconds "$(( $(date +%s) - start_epoch ))"
exit 0
