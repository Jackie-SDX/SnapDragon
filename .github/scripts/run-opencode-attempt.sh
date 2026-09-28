#!/usr/bin/env bash
set -u

attempt="${1:-unknown}"

# Single source of truth for control-plane defaults (single-budget-source audit item).
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [[ -f "$script_dir/oc-control-plane-config.sh" ]]; then
  source "$script_dir/oc-control-plane-config.sh"
else
  OC_CONTROL_PLANE_AGENT_TIMEOUT_MINUTES=350
  OC_CONTROL_PLANE_JOB_BUDGET_SECONDS=21600
  OC_CONTROL_PLANE_JOB_SAFETY_MARGIN_SECONDS=120
  OC_CONTROL_PLANE_PROGRESS_INTERVAL_SECONDS=30
fi

agent_timeout_minutes="${OPENCODE_AGENT_TIMEOUT_MINUTES:-${OC_CONTROL_PLANE_AGENT_TIMEOUT_MINUTES:-350}}"
if [[ ! "$agent_timeout_minutes" =~ ^[0-9]+$ ]] || (( agent_timeout_minutes < 1 || agent_timeout_minutes >= 360 )); then
  echo "::error title=Invalid OpenCode timeout::OPENCODE_AGENT_TIMEOUT_MINUTES must be an integer from 1 to 359."
  exit 2
fi
command -v timeout >/dev/null 2>&1 || {
  echo "::error title=Missing timeout utility::GNU timeout is required for controlled OpenCode execution."
  exit 2
}
command -v mkfifo >/dev/null 2>&1 || {
  echo "::error title=Missing mkfifo utility::mkfifo is required for live OpenCode output streaming."
  exit 2
}

runner_temp="${RUNNER_TEMP:-/tmp}"
activity_guidance="$(cat <<'OC_GUIDANCE'
Start with the exact user request. The request is the first source of truth. Do not automatically inspect the repository, issue history, CI, or durable memory merely because an /oc session exists. For simple conversational/read-only requests, answer directly. When the request is a code task, continuation, diagnosis, release, CI investigation, or otherwise needs historical/state context, decide what context is actually relevant and retrieve it on demand.

On-demand context is available without being preloaded:
- durable memory: $OC_SESSION_STATE_FILE
- bulk issue/PR/history/CI collector: ${OC_CONTEXT_COLLECTOR:-.github/scripts/collect-oc-context.sh}
- generated context files: $RUNNER_TEMP/oc-issue-context-seed.md and $RUNNER_TEMP/oc-issue-context-full.md
Use the collector only when it materially helps the current request. The controller must not decide the agent needs all context up front.

Research mode: keep OpenCode thinking blocks enabled and visible in the live Actions log. Do not replace the thinking stream with summary-only markers. Continue to redact credentials, API keys, tokens, and other secrets from streamed output.

Autonomous self-modification: you may modify workflows, shell scripts, controller code, prompts, configuration, and other execution-critical files when requested. Before changing one, identify the currently running step/process, the checkout/worktree it uses, and later dependencies. Do not assume a running process reloads edited files; preserve the active control plane and validate the new behavior from a fresh process, job, or workflow when needed. Prefer isolated worktrees/branches and coherent checkpoints for risky changes. Inspect references before deleting/renaming execution-critical files, and preserve recovery, publication, redaction, session-state, and CI-observation paths unless the task explicitly changes them. This is engineering judgment, not a hard restriction.

Research-first: when facts are current, niche, ambiguous, version-sensitive, or uncertain, prioritize web search through available Composio tools before guessing. Prefer official documentation, GitHub/GitHub Actions docs, upstream repositories/releases, standards, and vendor docs. Verify exact commands, API fields, action versions, configuration syntax, and compatibility. Web research accelerates execution and must not become an approval gate; if unavailable, continue with the strongest local evidence and distinguish assumptions from verified facts.

Capability discovery / outside-the-box execution: treat the requested outcome as the specification. Use tools, repository capabilities, APIs, runtimes, package managers, and connected services when they are relevant to the current request. Do not claim impossibility until viable reachable alternatives have been investigated. Do not inventory the environment for a simple conversational/read-only request. When a non-code or otherwise unusual capability is actually required, compose available primitives into the smallest practical path and verify the result.
OC_GUIDANCE
)"
mkdir -p "$runner_temp"
safe_log="$runner_temp/opencode-${attempt}-safe.log"
progress_log="$runner_temp/opencode-${attempt}-progress.log"
fifo="$runner_temp/opencode-${attempt}.fifo"
output_file="${GITHUB_OUTPUT:-/dev/null}"
final_response_file="${RUNNER_TEMP:-/tmp}/opencode-final-response-${attempt}.md"
plan_file="${RUNNER_TEMP:-/tmp}/opencode-plan-${attempt}.md"
context_full="${OC_ISSUE_CONTEXT_FILE:-${RUNNER_TEMP:-/tmp}/oc-issue-context-full.md}"
context_seed="${OC_ISSUE_CONTEXT_SEED_FILE:-${RUNNER_TEMP:-/tmp}/oc-issue-context-seed.md}"
rm -f "$final_response_file"
export OC_FINAL_RESPONSE_FILE="$final_response_file"
controller_root="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
export OC_CONTROLLER_ROOT="$controller_root"
agent_worktree=""
agent_cwd=""
session_branch="${OC_SESSION_BRANCH:-}"
session_state_file="${OC_SESSION_STATE_FILE:-}"
native_session_id="${OC_NATIVE_SESSION_ID:-}"
native_restore_file="${OC_NATIVE_SESSION_EXPORT_FILE:-}"
native_session_artifact=""
native_session_export_file=""
native_session_restored=false
target_number="$(printenv TARGET_NUMBER 2>/dev/null || printf 0)"
native_session_id_file="$runner_temp/opencode-$attempt-native-session-id"
native_archive_branch="oc-native-session-$target_number"
native_archive_path="session.json.enc"
native_archive_commit_file="$runner_temp/opencode-$attempt-native-archive-commit"
: > "$safe_log"
: > "$progress_log"
: > "$native_session_id_file"


checkpoint_worktree() {
  [[ -n "$agent_worktree" && -d "$agent_worktree" && -n "$session_branch" ]] || return 0
  OC_ATTEMPT="$attempt" OC_SESSION_BRANCH="$session_branch" bash "$controller_root/.github/scripts/checkpoint-oc-working-tree.sh" "$agent_worktree" || true
}
cleanup() {
  [[ -n "${heartbeat_pid:-}" ]] && kill "$heartbeat_pid" 2>/dev/null || true
  checkpoint_worktree
  if [[ -n "$agent_worktree" && -d "$agent_worktree" ]]; then
    git -C "$controller_root" worktree remove --force "$agent_worktree" >/dev/null 2>&1 || true
  fi
  rm -f "$fifo"
}

trap cleanup EXIT

runtime_model="${MODEL:-opencode/mimo-v2.6-flash-free}"
task_mode="${TASK_MODE:-code}"
# Inline runtime config has highest precedence, so the selected route model is
# honored by the same OpenCode runner without changing the project policy.
if [[ -z "${COMPOSIO_MCP_URL:-}" || "${COMPOSIO_MCP_ENABLED:-true}" != "true" ]]; then
  export OPENCODE_CONFIG_CONTENT="{\"model\":\"$runtime_model\",\"mcp\":{\"composio\":{\"enabled\":false}},\"permission\":\"allow\"}"
  echo "[OC][attempt=${attempt}] Composio MCP inactive; selected model: $runtime_model"
else
  export OPENCODE_CONFIG_CONTENT="{\"model\":\"$runtime_model\",\"permission\":\"allow\"}"
fi

request="$(printenv OC_COMMAND_TEXT 2>/dev/null || true)"
event_path="$(printenv GITHUB_EVENT_PATH 2>/dev/null || true)"
if [ -z "$request" ] && [ -f "$event_path" ]; then
  request="$(jq -r '.comment.body // empty' "$event_path" 2>/dev/null | sed -E 's#^/(oc|opencode)[[:space:]]*##')"
fi
context_seed="$(printenv OC_ISSUE_CONTEXT_SEED_FILE 2>/dev/null || true)"
context_full="$(printenv OC_ISSUE_CONTEXT_FILE 2>/dev/null || true)"
context_refs="$(printenv OC_REFERENCE_CONTEXT_FILE 2>/dev/null || true)"
[ -n "$context_seed" ] || context_seed="$runner_temp/oc-issue-context-seed.md"
[ -n "$context_full" ] || context_full="$runner_temp/oc-issue-context-full.md"
[ -n "$context_refs" ] || context_refs="$runner_temp/oc-reference-context.md"

agent_cmd=()
if [[ "${OC_TARGET_MODE:-local}" == "remote" ]]; then
  ws="${OC_TARGET_WORKSPACE:-}"
  agent_cwd="$ws"
  if [[ -z "$ws" || ! -d "$ws/.git" ]]; then
    echo "::error title=Remote target workspace missing for attempt ${attempt}::prepare-oc-target.sh must run before the agent attempt." >&2
    exit 2
  fi
  task_prompt="${OC_TARGET_TASK:-}"
  if [[ -n "${OC_TARGET_TASK_FILE:-}" && -f "${OC_TARGET_TASK_FILE:-}" ]]; then
    task_prompt="$(cat "$OC_TARGET_TASK_FILE")"
  fi
  [[ -n "$task_prompt" ]] || task_prompt="Inspect the target repository workspace and implement the requested change. Work inside this repository only; use its own project instructions. You may commit, push, create/update PRs, inspect CI, repair failures, and merge when the user explicitly requests that lifecycle step. Never force-push, rewrite protected history, bypass branch protection, expose credentials, or make unrelated changes."
  model_name="${MODEL:-opencode/mimo-v2.6-flash-free}"
  task_prompt="$task_prompt"$'\n\n'"$activity_guidance"
  if [[ "$native_session_restored" == "true" ]]; then
    agent_cmd=(opencode run --session "$native_session_id" --thinking --format json --dir "$ws" --model "$model_name")
  else
    agent_cmd=(opencode run --thinking --format json --dir "$ws" --model "$model_name")
  fi
  [[ -n "${VARIANT:-}" ]] && agent_cmd+=(--variant "$VARIANT")
  agent_cmd+=(--agent build --title "oc remote ${OC_TARGET_REPO:-target}")
else
  initial_sha="${OC_INITIAL_SHA:-}"
  [[ -n "$initial_sha" ]] || initial_sha="$(git rev-parse HEAD)"
  agent_worktree="$runner_temp/opencode-agent-$$-$attempt"
  if ! git -C "$controller_root" config extensions.worktreeConfig true >/dev/null 2>&1; then
    echo "::error title=Agent worktree configuration failed::Could not enable per-worktree Git configuration." >&2
    exit 2
  fi
  if [[ "$task_mode" == "code" && -n "$session_branch" ]]; then
    if ! git -C "$controller_root" show-ref --verify --quiet "refs/heads/$session_branch"; then
      echo "::error title=Durable session branch missing::prepare-oc-session.sh must create $session_branch before code execution." >&2
      exit 2
    fi
    if ! git -C "$controller_root" worktree add "$agent_worktree" "$session_branch" >/dev/null 2>&1; then
      echo "::error title=Agent worktree setup failed::Could not create the durable session worktree from $session_branch." >&2
      exit 2
    fi
    agent_branch="$session_branch"
  else
    if ! git -C "$controller_root" worktree add --detach "$agent_worktree" "$initial_sha" >/dev/null 2>&1; then
      echo "::error title=Agent worktree setup failed::Could not create an isolated OpenCode worktree from $initial_sha." >&2
      exit 2
    fi
  fi
  agent_cwd="$agent_worktree"
  echo "[OC][attempt=$attempt] isolated OpenCode workspace is ready"
  model_name="${MODEL:-opencode/mimo-v2.6-flash-free}"
  task_prompt="${request:-}"
  if [[ -z "$task_prompt" || "$task_prompt" == "run" ]]; then
    task_prompt="Execute the user's latest request directly. Start from the request itself. Do not assume repository inspection, issue-history retrieval, durable-memory loading, or CI inspection is necessary. Decide whether additional context is actually needed for this request; retrieve it only when it materially helps answer or execute the request."
  fi
  task_prompt="$task_prompt"$'\n\n'"$activity_guidance"
  if [[ "$native_session_restored" == "true" ]]; then
    agent_cmd=(opencode run --session "$native_session_id" --thinking --format json --dir "$agent_cwd" --model "$model_name")
  else
    agent_cmd=(opencode run --thinking --format json --dir "$agent_cwd" --model "$model_name")
  fi
  [[ -n "${VARIANT:-}" ]] && agent_cmd+=(--variant "$VARIANT")
  agent_cmd+=(--agent build --title "oc local ${TARGET_NUMBER:-issue}")
fi
  restore_native_session() {
  [[ -n "$native_session_id" && -s "$native_restore_file" && -n "$agent_cwd" ]] || return 0
  if [[ ! "$native_session_id" =~ ^ses_[A-Za-z0-9_-]+$ ]]; then
    echo "::warning title=Invalid native OpenCode session ID::Ignoring persisted session ID '$native_session_id'."
    native_session_id=""
    return 0
  fi
  printf '%s\n' "$native_session_id" > "$native_session_id_file"
  echo "[OC][attempt=$attempt] importing native OpenCode session $native_session_id"
  if (cd "$agent_cwd" && opencode import "$native_restore_file" >/dev/null 2>&1); then
    native_session_restored=true
    echo "[OC][attempt=$attempt] native OpenCode session restored"
  else
    echo "::warning title=Native OpenCode session restore failed::Falling back to durable logical session state."
  fi
}

sanitize_line() {
  local line="$1" secret
  for secret in \
    "${COMPOSIO_API_KEY:-}" \
    "${OPENCODE_API_KEY:-}" \
    "${GITHUB_TOKEN:-}" \
    "${GH_TOKEN:-}" \
    "${UNIVERSAL_TOKEN:-}" \
    "${GITHUB_TOKEN:-}" \
    "${GH_TOKEN:-}" \
    "${UNIVERSAL_TOKEN:-}"; do
    if [[ -n "$secret" ]]; then
      line="${line//$secret/[REDACTED]}"
    fi
  done
  printf "%s" "$line" |
    sed -E \
      -e "s/(AIza[[:alnum:]_-]{20,})/[REDACTED_GOOGLE_KEY]/g" \
      -e "s/(gh[ps]_[[:alnum:]_]{20,}|github_pat_[[:alnum:]_]{20,})/[REDACTED_GITHUB_TOKEN]/g" \
      -e "s/(sk-or-v1-[[:alnum:]_-]{20,})/[REDACTED_EXTERNAL_API_KEY]/g" \
      -e "s/(Bearer[[:space:]]+)[^[:space:]]+/\1[REDACTED]/g"
}

if [[ -n "$native_session_id" ]]; then
  restore_native_session
fi
if [[ "$native_session_restored" == "true" ]]; then
  if [[ "${OC_TARGET_MODE:-local}" == "remote" ]]; then
    agent_cmd=(opencode run --session "$native_session_id" --thinking --format json --dir "$agent_cwd" --model "${MODEL:-opencode/mimo-v2.6-flash-free}")
    [[ -n "${VARIANT:-}" ]] && agent_cmd+=(--variant "$VARIANT")
    agent_cmd+=(--agent build --title "oc remote ${OC_TARGET_REPO:-target}")
  else
    agent_cmd=(opencode run --session "$native_session_id" --thinking --format json --dir "$agent_cwd" --model "${MODEL:-opencode/mimo-v2.6-flash-free}")
    [[ -n "${VARIANT:-}" ]] && agent_cmd+=(--variant "$VARIANT")
    agent_cmd+=(--agent build --title "oc local ${TARGET_NUMBER:-issue}")
  fi
fi

configured_timeout_seconds=$((agent_timeout_minutes * 60))
effective_timeout_seconds="$configured_timeout_seconds"
job_budget_seconds="${OC_JOB_BUDGET_SECONDS:-${OC_CONTROL_PLANE_JOB_BUDGET_SECONDS:-}}"
job_safety_seconds="${OC_JOB_SAFETY_MARGIN_SECONDS:-${OC_CONTROL_PLANE_JOB_SAFETY_MARGIN_SECONDS:-120}}"
job_start_epoch="${OC_JOB_START_EPOCH:-}"

if [[ -n "$job_budget_seconds" && "$job_budget_seconds" =~ ^[0-9]+$ &&
      "$job_safety_seconds" =~ ^[0-9]+$ && "$job_start_epoch" =~ ^[0-9]+$ ]]; then
  now_epoch="$(date +%s)"
  elapsed=$((now_epoch - job_start_epoch))
  remaining=$((job_budget_seconds - elapsed - job_safety_seconds))
  if (( remaining < effective_timeout_seconds )); then
    effective_timeout_seconds="$remaining"
  fi
fi

{
  printf "effective_timeout_seconds=%s\n" "$effective_timeout_seconds"
  printf "progress_log_path=%s\n" "$progress_log"
  printf "safe_log_path=%s\n" "$safe_log"
} >> "$output_file"

if [[ "$task_mode" == "code" ]]; then
  if [[ -n "$job_budget_seconds" && "$job_budget_seconds" =~ ^[0-9]+$ &&
        "$job_safety_seconds" =~ ^[0-9]+$ && "$job_start_epoch" =~ ^[0-9]+$ ]]; then
    now_epoch="$(date +%s)"
    elapsed=$((now_epoch - job_start_epoch))
    remaining=$((job_budget_seconds - elapsed - job_safety_seconds))
    if (( remaining < effective_timeout_seconds )); then
      effective_timeout_seconds="$remaining"
    fi
  fi
fi

if (( effective_timeout_seconds < 1 )); then
  printf "termination_reason=timeout\nexit_code=124\n" >> "$output_file"
  echo "::warning title=OpenCode attempt budget exhausted::No remaining job budget is available for attempt ${attempt}."
  exit 124
fi

heartbeat_interval="${OC_PROGRESS_INTERVAL_SECONDS:-${OC_CONTROL_PLANE_PROGRESS_INTERVAL_SECONDS:-30}}"
if [[ ! "$heartbeat_interval" =~ ^[0-9]+$ ]] || (( heartbeat_interval < 1 )); then
  heartbeat_interval=30
fi

start_epoch="$(date +%s)"
printf "[OC][attempt=%s][elapsed=0s] started route=%s\n" "$attempt" "${MODEL:-github}" >> "$progress_log"
echo "[OC][attempt=${attempt}][elapsed=0s] started route=${MODEL:-github}"
mkfifo "$fifo"

checkpoint_session_state() {
  local elapsed="$1"
  local branch="" head="" dirty="false" durable="false" target_note="" base_head=""
  if [[ "${OC_TARGET_MODE:-local}" == "remote" && -n "${OC_TARGET_WORKSPACE:-}" && -d "${OC_TARGET_WORKSPACE:-}" ]]; then
    branch="$(git -C "$OC_TARGET_WORKSPACE" branch --show-current 2>/dev/null || true)"
    head="$(git -C "$OC_TARGET_WORKSPACE" rev-parse HEAD 2>/dev/null || true)"
    [[ -n "$(git -C "$OC_TARGET_WORKSPACE" status --porcelain 2>/dev/null || true)" ]] && dirty="true"
    base_head="$(git -C "$OC_TARGET_WORKSPACE" rev-parse "origin/${OC_TARGET_BASE:-main}" 2>/dev/null || true)"
    if [[ "$head" =~ ^[0-9a-f]{40}$ && "$base_head" =~ ^[0-9a-f]{40}$ && "$head" != "$base_head" ]]; then durable="true"; fi
    [[ "$dirty" == "true" ]] && durable="true"
    target_note="target=${OC_TARGET_REPO:-unknown}@${branch:-unknown} head=${head:-unknown} dirty=${dirty}"
  elif [[ -n "$agent_worktree" && -d "$agent_worktree" ]]; then
    branch="$(git -C "$agent_worktree" branch --show-current 2>/dev/null || true)"
    head="$(git -C "$agent_worktree" rev-parse HEAD 2>/dev/null || true)"
    [[ -n "$(git -C "$agent_worktree" status --porcelain 2>/dev/null || true)" ]] && durable="true"
    target_note="controller-branch=${branch:-detached} head=${head:-unknown} dirty=${durable}"
  else
    target_note="workspace not available for checkpoint"
  fi
  if [[ -n "${OC_SESSION_STATE_FILE:-}" ]]; then
    OC_SESSION_PHASE="working" \
    OC_SESSION_STATUS="active" \
    OC_SESSION_MILESTONE="heartbeat_checkpoint" \
    OC_SESSION_NEXT_ACTION="continue current task from the durable target branch; inspect existing work and CI" \
    OC_SESSION_EVIDENCE="elapsed=${elapsed}s; ${target_note}" \
    OC_SESSION_BRANCH="$branch" \
    OC_SESSION_HEAD_SHA="$head" \
    OC_SESSION_ATTEMPT="$attempt" \
    OC_DURABLE_WORK="$durable" \
    bash "$controller_root/.github/scripts/record-oc-session-progress.sh" || true
  fi
}

heartbeat() {
  local elapsed next_checkpoint=300 last_native_export=0 sid snapshot
  while kill -0 "$agent_pid" 2>/dev/null; do
    sleep "$heartbeat_interval"
    kill -0 "$agent_pid" 2>/dev/null || break
    elapsed=$(( $(date +%s) - start_epoch ))
    printf "[OC][attempt=%s][elapsed=%ss] heartbeat state=running\n" "$attempt" "$elapsed" >> "$progress_log"
    sid="$(cat "$native_session_id_file" 2>/dev/null || true)"
    if [[ -n "$sid" && "$sid" =~ ^ses_[A-Za-z0-9_-]+$ && -n "$agent_cwd" && -d "$agent_cwd" ]]; then
      if (( elapsed - last_native_export >= 300 )); then
        snapshot="$runner_temp/opencode-native-session-live-$target_number.json"
        if (cd "$agent_cwd" && timeout 60s opencode export "$sid" > "$snapshot.tmp" 2>/dev/null) && [[ -s "$snapshot.tmp" ]]; then
          mv -f "$snapshot.tmp" "$snapshot"
          NATIVE_SESSION_ID="$sid" NATIVE_SESSION_EXPORT_FILE="$snapshot" OC_NATIVE_ARCHIVE_BRANCH="$native_archive_branch" OC_NATIVE_ARCHIVE_PATH="$native_archive_path" OC_NATIVE_ARCHIVE_COMMIT_FILE="$native_archive_commit_file" bash "$controller_root/.github/scripts/persist-oc-native-session.sh" || true
          native_session_id="$sid"; native_session_export_file="$snapshot"; last_native_export="$elapsed"
        else
          rm -f "$snapshot.tmp"
        fi
      fi
    fi
    if (( elapsed >= next_checkpoint )); then
      checkpoint_worktree
      checkpoint_session_state "$elapsed"
      next_checkpoint=$((elapsed + 300))
    fi
  done
}

set +e
if [[ -n "$agent_cwd" ]]; then
  pushd "$agent_cwd" >/dev/null || {
    echo "::error title=Agent worktree entry failed::Could not enter $agent_cwd." >&2
    exit 2
  }
  printf "[OC][LIVE] OpenCode session started; streaming safe activity summaries and tool actions.\n" | tee -a "$progress_log"
  timeout --signal=TERM --kill-after=60s "${effective_timeout_seconds}s" "${agent_cmd[@]}" < <(printf "%s\n" "$task_prompt") >"$fifo" 2>&1 &
  agent_pid=$!
  popd >/dev/null
else
  printf "[OC][LIVE] OpenCode session started; streaming safe activity summaries and tool actions.\n" | tee -a "$progress_log"
  timeout --signal=TERM --kill-after=60s "${effective_timeout_seconds}s" "${agent_cmd[@]}" >"$fifo" 2>&1 &
  agent_pid=$!
fi
heartbeat &
heartbeat_pid=$!

while IFS= read -r raw_line || [[ -n "$raw_line" ]]; do
  if jq -e . >/dev/null 2>&1 <<<"$raw_line"; then
    event_session_id="$(jq -r '.sessionID // empty' <<<"$raw_line")"
    if [[ "$event_session_id" =~ ^ses_[A-Za-z0-9_-]+$ ]]; then
      native_session_id="$event_session_id"
      printf '%s\n' "$native_session_id" > "$native_session_id_file"
    fi
    event_type="$(jq -r '.type // empty' <<<"$raw_line")"
    case "$event_type" in
      text)
        event_text="$(jq -r '.part.text // empty' <<<"$raw_line")"
        synthetic="$(jq -r '.part.synthetic // false' <<<"$raw_line")"
        ignored="$(jq -r '.part.ignored // false' <<<"$raw_line")"
        compaction_continue="$(jq -r '.part.metadata.compaction_continue // false' <<<"$raw_line")"
        if [[ "$synthetic" != "true" && "$ignored" != "true" && "$compaction_continue" != "true" && -n "$event_text" ]]; then
          event_text="$(sanitize_line "$event_text")"
          printf "%s\n" "$event_text" > "$final_response_file"
          printf "%s\n" "$event_text"
        fi
        ;;
      reasoning)
        event_text="$(jq -r '.part.text // empty' <<<"$raw_line")"
        [[ -n "$event_text" ]] || continue
        event_text="$(sanitize_line "$event_text")"
        printf "Thinking: %s\n" "$event_text"
        ;;
      tool_use)
        tool_name="$(jq -r '.part.tool // empty' <<<"$raw_line")"
        tool_status="$(jq -r '.part.state.status // empty' <<<"$raw_line")"
        [[ -n "$tool_name" ]] || continue
        if [[ -n "$tool_status" ]]; then
          printf "|%s %s\n" "$tool_name" "$tool_status"
        else
          printf "|%s\n" "$tool_name"
        fi
        ;;
      step_start)
        printf "OC-STATUS: step started\n"
        ;;
      step_finish)
        step_reason="$(jq -r '.part.reason // empty' <<<"$raw_line")"
        if [[ -n "$step_reason" ]]; then
          printf "OC-DONE: %s\n" "$(sanitize_line "$step_reason")"
        else
          printf "OC-DONE: step finished\n"
        fi
        ;;
      error)
        error_text="$(jq -r '.error.data.message // .error.message // empty' <<<"$raw_line")"
        [[ -n "$error_text" ]] || error_text="OpenCode reported an error."
        printf "ERROR: %s\n" "$(sanitize_line "$error_text")"
        ;;
    esac
  else
    safe_line="$(sanitize_line "$raw_line")"
    printf "%s\n" "$safe_line"
  fi
done < <(awk -f "$script_dir/filter-opencode-live-output.awk" "$fifo" | tee -a "$safe_log")
wait "$agent_pid"
exit_code=$?

if [[ "$task_mode" == "code" && -n "$agent_cwd" ]]; then
  checkpoint_worktree
fi

agent_branch=""
session_head_sha=""
remote_dirty="false"
remote_base_sha=""
if [[ "${OC_TARGET_MODE:-local}" == "remote" && -n "${OC_TARGET_WORKSPACE:-}" && -d "${OC_TARGET_WORKSPACE:-}" ]]; then
  agent_branch="${OC_TARGET_BRANCH:-$(git -C "$OC_TARGET_WORKSPACE" branch --show-current 2>/dev/null || true)}"
  session_head_sha="$(git -C "$OC_TARGET_WORKSPACE" rev-parse HEAD 2>/dev/null || true)"
  remote_base_sha="$(git -C "$OC_TARGET_WORKSPACE" rev-parse "origin/${OC_TARGET_BASE:-main}" 2>/dev/null || true)"
  [[ -n "$(git -C "$OC_TARGET_WORKSPACE" status --porcelain 2>/dev/null || true)" ]] && remote_dirty="true"
elif [[ -n "$agent_worktree" && -e "$agent_worktree/.git" ]]; then
  agent_branch="$(git -C "$agent_worktree" branch --show-current 2>/dev/null || true)"
  session_head_sha="$(git -C "$agent_worktree" rev-parse HEAD 2>/dev/null || true)"
fi
printf "agent_branch=%s\n" "$agent_branch" >> "$output_file"

clarification_required="false"
clarification_source="$runner_temp/oc-clarification.md"
rm -f "$clarification_source"
for candidate in   "$agent_cwd/.opencode/NEEDS_CLARIFICATION.md"   "$agent_cwd/.opencode/needs-clarification.md"   "$agent_worktree/.opencode/NEEDS_CLARIFICATION.md"   "$agent_worktree/.opencode/needs-clarification.md"; do
  if [ -s "$candidate" ]; then
    cp "$candidate" "$clarification_source"
    clarification_required="true"
    break
  fi
done
printf "clarification_required=%s\n" "$clarification_required" >> "$output_file"
set -e

elapsed=$(( $(date +%s) - start_epoch ))
termination_reason="completed"
if [[ -s "$final_response_file" ]]; then
  printf "[OC][attempt=%s][elapsed=%ss] final response captured from structured event stream\n" "$attempt" "$elapsed" | tee -a "$progress_log"
fi
provider_warning="false"
case "$exit_code" in
  124) termination_reason="timeout" ;;

  125|126|127) termination_reason="failed" ;;
  128|129|130|131|132|133|134|135|136|137|138|139|140|141|142|143|144|145|146|147|148|149|150|151|152|153|154|155|156|157|158|159) termination_reason="signal" ;;
  0) termination_reason="completed" ;;
  *) termination_reason="failed" ;;
esac
if [ "$clarification_required" = "true" ]; then
  termination_reason="clarification"
fi

printf "[OC][attempt=%s][elapsed=%ss] finished exit_code=%s termination_reason=%s\n" "$attempt" "$elapsed" "$exit_code" "$termination_reason" | tee -a "$progress_log"

printf "[OC][LIVE] OpenCode session finished; final result is being reconciled.\n" | tee -a "$progress_log"

if [[ "$native_session_id" =~ ^ses_[A-Za-z0-9_-]+$ && -n "$agent_cwd" && -d "$agent_cwd" ]]; then
  native_session_export_file="$runner_temp/opencode-native-session-$attempt-$target_number.json"
  native_session_artifact="opencode-native-session-$target_number-$native_session_id"
  if (cd "$agent_cwd" && opencode export "$native_session_id" > "$native_session_export_file" 2>/dev/null) && [[ -s "$native_session_export_file" ]]; then
    persist_rc=0
    NATIVE_SESSION_ID="$native_session_id" NATIVE_SESSION_EXPORT_FILE="$native_session_export_file" OC_NATIVE_ARCHIVE_BRANCH="$native_archive_branch" OC_NATIVE_ARCHIVE_PATH="$native_archive_path" OC_NATIVE_ARCHIVE_COMMIT_FILE="$native_archive_commit_file" bash "$controller_root/.github/scripts/persist-oc-native-session.sh" || persist_rc=$?
    if [[ "$persist_rc" -eq 0 ]]; then
      printf "native_session_id=%s\n" "$native_session_id" >> "$output_file"
      printf "native_session_export_file=%s\n" "$native_session_export_file" >> "$output_file"
      printf "native_session_artifact=%s\n" "$native_session_artifact" >> "$output_file"
      printf "OC_NATIVE_SESSION_ID=%s\n" "$native_session_id" >> "$GITHUB_ENV"
      printf "OC_NATIVE_SESSION_RUN_ID=%s\n" "$GITHUB_RUN_ID" >> "$GITHUB_ENV"
      printf "OC_NATIVE_SESSION_ARTIFACT=%s\n" "$native_session_artifact" >> "$GITHUB_ENV"
      printf "OC_NATIVE_SESSION_EXPORTED=true\n" >> "$GITHUB_ENV"
    else
      printf "native_session_id=\n" >> "$output_file"
      printf "native_session_export_file=\n" >> "$output_file"
      printf "native_session_artifact=\n" >> "$output_file"
    fi
  else
    printf "native_session_id=\n" >> "$output_file"
    printf "native_session_export_file=\n" >> "$output_file"
    printf "native_session_artifact=\n" >> "$output_file"
  fi
else
  printf "native_session_id=\n" >> "$output_file"
  printf "native_session_export_file=\n" >> "$output_file"
  printf "native_session_artifact=\n" >> "$output_file"
fi
