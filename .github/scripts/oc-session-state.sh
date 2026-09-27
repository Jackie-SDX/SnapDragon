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

issue_body(){ gh api "/repos/$repo/issues/$target" --jq '.body // ""' 2>/dev/null || true; }

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
  local json existing stripped body
  json="$(cat "$state_file")"
  existing="$(issue_body)"
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
  gh api -X PATCH -f body="$body" "/repos/$repo/issues/$target" >/dev/null
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

case "$cmd" in
  load)
    body="$(issue_body)"
    json="$(extract_block "$body" "$memory_marker" "$memory_end" | sed -n '/^STATE-BEGIN$/,/^STATE-END$/p' | sed '1d;$d')"
    if ! jq empty <<<"$json" >/dev/null 2>&1; then
      id="$(legacy_comment_id || true)"
      if [[ "$id" =~ ^[0-9]+$ ]]; then
        raw="$(gh api "/repos/$repo/issues/comments/$id" --jq '.body // ""' 2>/dev/null || true)"
        json="$(extract_block "$raw" "$legacy_marker" "$legacy_end" | sed -n '/^STATE-BEGIN$/,/^STATE-END$/p' | sed '1d;$d')"
      fi
    fi
    if jq empty <<<"$json" >/dev/null 2>&1; then
      export_state "$json"
      echo "Loaded durable session memory $(jq -r '.session_id' "$state_file")"
      exit 0
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
      warnings:[],artifacts:[],next_action:"classify request"
    }')"
    export_state "$json"
    ;;
  save|checkpoint)
    [[ -f "$state_file" ]] || exit 0
    if grep -Eq 'gh[pous]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,}|sk-or-v1-[A-Za-z0-9_-]{20,}|AIza[A-Za-z0-9_-]{20,}|Bearer[[:space:]]+[A-Za-z0-9._-]+' "$state_file"; then
      echo "::warning title=Session memory blocked::Potential credential material detected."
      exit 0
    fi
    write_memory || echo "::warning title=Session memory degraded::Issue body memory could not be updated; Git remains authoritative."
    export_state "$(cat "$state_file")"
    ;;
  set)
    json="$(printenv SESSION_JSON || true)"
    jq empty <<<"$json" >/dev/null 2>&1 || exit 2
    printf '%s
' "$json" > "$state_file"
    write_memory || true
    export_state "$json"
    ;;
  *) exit 2 ;;
esac
