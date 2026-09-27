#!/usr/bin/env bash
set -euo pipefail

target="$TARGET_NUMBER"
base_ref="$BASE_REF"
repo="$GITHUB_REPOSITORY"
[[ "$target" =~ ^[0-9]+$ && "$target" != "0" ]] || exit 0

# Single source of truth for control-plane defaults (see oc-control-plane-config.sh).
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [[ -f "$script_dir/oc-control-plane-config.sh" ]]; then
  source "$script_dir/oc-control-plane-config.sh"
else
  OC_CONTROL_PLANE_AGENT_TIMEOUT_MINUTES=330
fi

state_file="${OC_SESSION_STATE_FILE:-${RUNNER_TEMP:-/tmp}/oc-session-state.json}"
branch="$(jq -r '.target_branch // .active_branch // empty' "$state_file" 2>/dev/null || true)"
sha="$(jq -r '.active_head_sha // .last_verified_sha // empty' "$state_file" 2>/dev/null || true)"
target_repo="$(jq -r '.target_repository // empty' "$state_file" 2>/dev/null || true)"
target_base="$(jq -r '.target_base // .base_ref // empty' "$state_file" 2>/dev/null || true)"
phase="$(jq -r '.phase // empty' "$state_file" 2>/dev/null || true)"
next_action="$(jq -r '.next_action // empty' "$state_file" 2>/dev/null || true)"
goal="$(jq -r '.goal // .current_request // empty' "$state_file" 2>/dev/null || true)"
completed_steps="$(jq -r 'if (.completed_steps|type)=="array" then (.completed_steps | map(tostring) | join("; ")) else "" end' "$state_file" 2>/dev/null || true)"
remaining_steps="$(jq -r 'if (.remaining_steps|type)=="array" then (.remaining_steps | map(tostring) | join("; ")) else "" end' "$state_file" 2>/dev/null || true)"
termination="$(jq -r '.termination_reason // "timeout"' "$state_file" 2>/dev/null || true)"
durable_work="$(jq -r '.durable_work // false' "$state_file" 2>/dev/null || printf "%s" "false")"
mode="${OC_TARGET_MODE:-local}"

if [[ "$mode" == "remote" && -n "${OC_TARGET_WORKSPACE:-}" && -d "${OC_TARGET_WORKSPACE:-}" ]]; then
  target_repo="${OC_TARGET_REPO:-$target_repo}"
  target_base="${OC_TARGET_BASE:-${target_base:-main}}"
  branch="${OC_TARGET_BRANCH:-$branch}"
  ws_head="$(git -C "$OC_TARGET_WORKSPACE" rev-parse HEAD 2>/dev/null || true)"
  ws_branch="$(git -C "$OC_TARGET_WORKSPACE" branch --show-current 2>/dev/null || true)"
  ws_dirty="$(git -C "$OC_TARGET_WORKSPACE" status --short 2>/dev/null || true)"
  [[ -n "$ws_branch" ]] && branch="$ws_branch"
  [[ "$ws_head" =~ ^[0-9a-f]{40}$ ]] && sha="$ws_head"
  [[ -n "$ws_dirty" ]] && durable_work="true"
fi

[[ -n "$target_repo" ]] || target_repo="${OC_TARGET_REPO:-}"
[[ -n "$target_base" ]] || target_base="${OC_TARGET_BASE:-$base_ref}"
[[ -n "$branch" ]] || branch="${OC_TARGET_BRANCH:-}"
[[ -n "$sha" ]] || sha="$(git rev-parse HEAD 2>/dev/null || printf "%s" "unknown")"

last_commit="$(git log -1 --oneline 2>/dev/null || true)"
if [[ -n "$target_repo" && -n "$branch" ]]; then
  last_commit="$(gh api "/repos/$target_repo/commits/$branch" --jq '.sha + " " + (.commit.message | split("\n")[0])' 2>/dev/null || printf "%s" "$last_commit")"
fi
run_id="$GITHUB_RUN_ID"
run_url="$GITHUB_SERVER_URL/$repo/actions/runs/$run_id"

open_prs="$(
  gh pr list --state open --limit 50 --json number,url,headRefName,headRefOid,baseRefName |
    jq -r --arg a "opencode/issue$target-" --arg base "$base_ref" '
      .[] | select(.baseRefName == $base) |
      select(.headRefName | startswith($a)) |
      "#\(.number) \(.url) \(.headRefName) \(.headRefOid)"
    ' 2>/dev/null || true
)"

# Remote-target runs leave a machine-readable durable marker so a bare
# "/oc continue" can recover the exact target base/branch on a later run.
target_marker=""
if [[ "$mode" == "remote" && -n "$target_repo" && -n "$branch" ]]; then
  target_marker="<!-- oc-target-repo:$target_repo base:$target_base branch:$branch -->"
fi

open_prs="none visible"
if [[ -n "$target_repo" && -n "$branch" ]]; then
  open_prs="$(gh pr list --repo "$target_repo" --head "$branch" --base "$target_base" --state open --limit 10 --json number,url,headRefName,headRefOid | jq -r '.[] | "#\(.number) \(.url) \(.headRefName) \(.headRefOid)"' 2>/dev/null || true)"
  [[ -n "$open_prs" ]] || open_prs="none visible"
else
  open_prs="$(gh pr list --state open --limit 50 --json number,url,headRefName,headRefOid,baseRefName | jq -r --arg a "opencode/issue$target-" --arg base "$base_ref" '.[] | select(.baseRefName == $base) | select(.headRefName | startswith($a)) | "#\(.number) \(.url) \(.headRefName) \(.headRefOid)"' 2>/dev/null || true)"
  [[ -n "$open_prs" ]] || open_prs="none visible"
fi

body="$(cat <<EOF
$target_marker
<!-- oc-checkpoint-run-id:$run_id issue:$target -->
## /oc execution checkpoint

**Controlled budget reached before task completion. This is NOT a success claim.**

The OpenCode attempt stopped at the controller budget so the workflow has recovery room before GitHub Actions reaches its hard job limit.

Task:
${goal:-OpenCode task}

State:
- phase: ${phase:-checkpointed}
- termination: ${termination}
- durable work: ${durable_work}
- target: ${target_repo:-local controller repository}
- base: ${target_base:-$base_ref}
- branch: ${branch:-unknown}
- HEAD: ${sha:-unknown}
- next action: ${next_action:-recover the durable target branch and continue}

Completed steps recorded:
${completed_steps:-No structured completed-step list was recorded; use the durable branch, commits, logs, and CI as evidence.}

Remaining steps recorded:
${remaining_steps:-No structured remaining-step list was recorded; derive remaining work from the current target state and prior evidence before changing anything.}

Working tree at checkpoint:
${status:-unavailable}

Latest target commit:
${last_commit:-unknown}

Open PR(s):
${open_prs:-none visible}

Workflow run:
$run_url

**Resume command:**
`/oc continue`

On resume, recover this exact target repository/branch/HEAD and durable session state first. Do not restart the task from scratch, create duplicate work, or discard partial changes/evidence.
EOF
)"EOF
)"

gh issue comment "$target" --body "$body"
