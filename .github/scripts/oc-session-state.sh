#!/usr/bin/env bash
set -euo pipefail

repo="$(printenv GITHUB_REPOSITORY || true)"
target="$(printenv TARGET_NUMBER || printf 0)"
cmd="$1"
[[ -n "$cmd" ]] || cmd=load
runner_temp="$(printenv RUNNER_TEMP || printf /tmp)"
state_file="$(printenv OC_SESSION_STATE_FILE || printf '%s/oc-session-state.json' "$runner_temp")"
memory_marker="<!-- oc-session-memory:v2 issue:$target -->"
memory_end="<!-- /oc-session-memory -->"
legacy_marker="<!-- oc-session-state:v1 issue:$target -->"
legacy_end="<!-- /oc-session-state -->"

mkdir -p "$runner_temp"
[[ "$target" =~ ^[0-9]+$ && "$target" != 0 && -n "$repo" ]] || exit 0
env_file="$(printenv GITHUB_ENV || printf /dev/null)"
out_file="$(printenv GITHUB_OUTPUT || printf /dev/null)"
emit_env(){ printf '%s=%s
' "$1" "$2" >> "$env_file"; }
emit_out(){ printf '%s=%s
' "$1" "$2" >> "$out_file"; }

issue_body(){ gh issue view "$target" --repo "$repo" --json body --jq .body 2>/dev/null || gh api "/repos/$repo/issues/$target" --jq .body 2>/dev/null; }

extract_block(){
  local body="$1" begin="$2" end="$3"
  awk -v a="$begin" -v b="$end" '
    index($0,a){inside=1;next}
    index($0,b){inside=0;next}
    inside{print}
  ' <<<"$body"
}

legacy_comment_id(){
  gh api --paginate --jq --arg marker "$legacy_marker"     '.[] | select((.body // "") | contains($marker)) | .id'     "/repos/$repo/issues/$target/comments?per_page=100" 2>/dev/null | tail -n 1
}

remove_legacy_comment(){
  local id
  id="$(legacy_comment_id || true)"
  [[ "$id" =~ ^[0-9]+$ ]] || return 0
  gh api -X DELETE "/repos/$repo/issues/comments/$id" >/dev/null 2>&1 || true
}

write_memory(){
  local json existing stripped body verify_body verify_json expected_sid expected_revision
  json="$(cat "$state_file")"
  existing="$(issue_body)" || return 1
  stripped="$(awk -v a="$memory_marker" -v b="$memory_end" '
    index($0,a){inside=1;next}
    index($0,b){inside=0;next}
    !inside{print}
  ' <<<"$existing")"
  body="$(printf '%s

%s
STATE-BEGIN
%s
STATE-END
%s' "$stripped" "$memory_marker" "$json" "$memory_end")"
  gh api -X PATCH -f body="$body" "/repos/$repo/issues/$target" >/dev/null || return 1
  verify_body="$(issue_body)" || return 1
  verify_json="$(extract_block "$verify_body" "$memory_marker" "$memory_end" | sed -n '/^STATE-BEGIN$/,/^STATE-END$/p' | sed '1d;$d')"
  valid_json "$verify_json" || return 1
  expected_sid="$(jq -r '.session_id // ""' "$state_file")"
  expected_revision="$(jq -r '.state_revision // 0' "$state_file")"
  jq -e --arg sid "$expected_sid" --argjson rev "$expected_revision" '.session_id == $sid and (.state_revision // 0) == $rev' >/dev/null 2>&1 <<<"$verify_json" || return 1
  remove_legacy_comment
}

export_state(){
  local json="$1"
  printf '%s
' "$json" > "$state_file"
  emit_env OC_SESSION_STATE_FILE "$state_file"
  emit_env OC_SESSION_ID "$(jq -r '.session_id // ""' "$state_file")"
  emit_env OC_SESSION_BRANCH "$(jq -r '.active_branch // ""' "$state_file")"
  emit_env OC_SESSION_BASE "$(jq -r '.base_ref // "main"' "$state_file")"
  emit_env OC_SESSION_HEAD_SHA "$(jq -r '.active_head_sha // ""' "$state_file")"
  emit_env OC_SESSION_PR_NUMBER "$(jq -r '.active_pr_number // 0' "$state_file")"
  emit_env OC_SESSION_PR_URL "$(jq -r '.active_pr_url // ""' "$state_file")"
  emit_env OC_SESSION_PHASE "$(jq -r '.phase // "unknown"' "$state_file")"
  emit_env OC_SESSION_STATUS "$(jq -r '.status // "new"' "$state_file")"
  emit_env OC_SESSION_EXISTS true
  emit_out state_file "$state_file"
  emit_out session_id "$(jq -r '.session_id // ""' "$state_file")"
  emit_out branch "$(jq -r '.active_branch // ""' "$state_file")"
  emit_out head_sha "$(jq -r '.active_head_sha // ""' "$state_file")"
  emit_out pr_number "$(jq -r '.active_pr_number // 0' "$state_file")"
  emit_out pr_url "$(jq -r '.active_pr_url // ""' "$state_file")"
  emit_out phase "$(jq -r '.phase // "unknown"' "$state_file")"
}

valid_json() { [[ -n "$(printf "%s" "$1" | tr -d "[:space:]")" ]] && jq -e 'type=="object"' >/dev/null 2>&1 <<<"$1"; }

case "$cmd" in
  load)
    if [[ "${OC_NEW_SESSION_REQUEST:-false}" == "true" ]]; then
      now="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
      base="$(printenv BASE_REF || printf main)"
      target_repo="${OC_TARGET_REPO:-}"
      target_base="${OC_TARGET_BASE:-$base}"
      target_branch="${OC_TARGET_BRANCH:-}"
      fresh_suffix="$(date -u +%Y%m%d%H%M%S)-${GITHUB_RUN_ID:-0}-${GITHUB_RUN_ATTEMPT:-1}"
      fresh_session_id="oc-$target-new-$fresh_suffix"
      fresh_branch="oc/session-$target-new-$fresh_suffix"
      json="$(cat <<EOF
{
  "schema_version":2,
  "session_id":"$fresh_session_id",
  "repository":"$repo",
  "issue":$target,
  "base_ref":"$base",
  "active_branch":"$fresh_branch",
  "active_pr_number":0,
  "active_pr_url":"",
  "active_head_sha":"",
  "goal":"",
  "milestone":"received",
  "phase":"received",
  "status":"new",
  "state_revision":0,
  "capabilities":{"push":false,"target":""},
  "last_verified_sha":"",
  "last_verified_evidence":"",
  "created_at":"$now",
  "updated_at":"$now",
  "last_processed_comment_id":0,
  "current_request":"",
  "completed_steps":[],
  "remaining_steps":[],
  "tests_run":[],
  "ci_runs":[],
  "research_sources":[],
  "warnings":[],
  "artifacts":[],
  "next_action":"classify request",
  "durable_work":false,
  "target_repository":"$target_repo",
  "target_base":"$target_base",
  "target_branch":"$target_branch",
  "last_run_id":null,
  "agent_attempt":null,
  "termination_reason":null,
  "last_checkpoint_at":"$now"
}
EOF
)"
      export_state "$json"
      emit_env OC_SESSION_MEMORY_PERSISTED false
      emit_env OC_SESSION_EXISTS false
      echo "Started fresh /oc session $fresh_session_id on $fresh_branch"
      exit 0
    fi
    body="$(issue_body || true)"
    json="$(extract_block "$body" "$memory_marker" "$memory_end" | sed -n '/^STATE-BEGIN$/,/^STATE-END$/p' | sed '1d;$d')"
    expected_target="${OC_TARGET_REPO:-}"

    validate_candidate() {
      local candidate="$1" stored_target
      valid_json "$candidate" || return 1
      jq -e --arg repo "$repo" --argjson issue "$target" '(.repository // "") == $repo and (.issue // -1) == $issue' >/dev/null 2>&1 <<<"$candidate" || return 1
      stored_target="$(jq -r '.target_repository // ""' <<<"$candidate" 2>/dev/null || true)"
      if [[ -n "$expected_target" || -n "$stored_target" ]] && [[ "$expected_target" != "$stored_target" ]]; then
        echo "::warning title=Session scope mismatch::Ignoring durable memory for ${stored_target:-local} because this request targets ${expected_target:-local}."
        return 1
      fi
      return 0
    }

    if validate_candidate "$json"; then
      export_state "$json"
      emit_env OC_SESSION_MEMORY_PERSISTED true
      emit_env OC_SESSION_EXISTS true
      echo "Loaded durable session memory $(jq -r '.session_id' "$state_file")"
      exit 0
    fi

    id="$(legacy_comment_id || true)"
    if [[ "$id" =~ ^[0-9]+$ ]]; then
      raw="$(gh api "/repos/$repo/issues/comments/$id" --jq '.body // ""' 2>/dev/null || true)"
      legacy_json="$(extract_block "$raw" "$legacy_marker" "$legacy_end" | sed -n '/^STATE-BEGIN$/,/^STATE-END$/p' | sed '1d;$d')"
      if validate_candidate "$legacy_json"; then
        printf '%s\n' "$legacy_json" > "$state_file"
        export_state "$legacy_json"
        emit_env OC_SESSION_MEMORY_PERSISTED false
        emit_env OC_SESSION_EXISTS true
        remove_legacy_comment || true
        echo "Loaded legacy session memory $(jq -r '.session_id' "$state_file")"
        exit 0
      fi
    fi
    now="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    base="$(printenv BASE_REF || printf main)"
    json="$(jq -n --arg repo "$repo" --arg issue "$target" --arg base "$base" --arg now "$now" --arg sid "oc-$target" '{
      schema_version:2,session_id:$sid,repository:$repo,issue:($issue|tonumber),base_ref:$base,
      active_branch:"",active_pr_number:0,active_pr_url:"",active_head_sha:"",
      goal:"",milestone:"received",phase:"received",status:"new",state_revision:0,
      capabilities:{push:false,target:""},last_verified_sha:"",last_verified_evidence:"",
      created_at:$now,updated_at:$now,last_processed_comment_id:0,current_request:"",
      completed_steps:[],remaining_steps:[],tests_run:[],ci_runs:[],research_sources:[],
      warnings:[],artifacts:[],next_action:"classify request",
      durable_work:false,target_repository:"",target_base:$base,target_branch:"",
      last_run_id:null,agent_attempt:null,termination_reason:null,last_checkpoint_at:$now
    }')"
    export_state "$json"
    emit_env OC_SESSION_MEMORY_PERSISTED false
    emit_env OC_SESSION_EXISTS false
    ;;
  save|checkpoint)
    [[ -f "$state_file" ]] || exit 0
    if grep -Eq 'gh[pous]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,}|sk-or-v1-[A-Za-z0-9_-]{20,}|AIza[A-Za-z0-9_-]{20,}|Bearer[[:space:]]+[A-Za-z0-9._-]+' "$state_file"; then
      echo "::warning title=Session memory blocked::Potential credential material detected."
      exit 0
    fi
    memory_persisted=true
    if ! write_memory; then
      memory_persisted=false
      echo "::warning title=Session memory degraded::Issue-body memory could not be verified; Git, issue comments, and live CI context remain the recovery sources."
    fi
    export_state "$(cat "$state_file")"
    emit_env OC_SESSION_MEMORY_PERSISTED "$memory_persisted"
    [[ "$memory_persisted" == "true" ]]
    ;;
  set)
    json="$(printenv SESSION_JSON || true)"
    valid_json "$json" || exit 2
    printf '%s
' "$json" > "$state_file"
    memory_persisted=true
    if ! write_memory; then
      memory_persisted=false
      echo "::warning title=Session memory degraded::Issue-body memory write verification failed; continuing with Git/issue/CI recovery sources."
    fi
    export_state "$json"
    emit_env OC_SESSION_MEMORY_PERSISTED "$memory_persisted"
    [[ "$memory_persisted" == "true" ]]
    ;;
  *) exit 2 ;;
esac
