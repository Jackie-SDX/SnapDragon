#!/usr/bin/env bash
set -euo pipefail
repo="$(printenv GITHUB_REPOSITORY || true)"
target="$(printenv TARGET_NUMBER || printf 0)"
base="$(printenv BASE_REF || printf main)"
state_file="${OC_SESSION_STATE_FILE:-${RUNNER_TEMP:-/tmp}/oc-session-state.json}"
[[ "$target" =~ ^[0-9]+$ && "$target" != 0 ]] || exit 0
emit_env(){ printf '%s=%s\n' "$1" "$2" >> "${GITHUB_ENV:-/dev/null}"; }
emit_out(){ printf '%s=%s\n' "$1" "$2" >> "${GITHUB_OUTPUT:-/dev/null}"; }

branch="$(jq -r '.active_branch // ""' "$state_file" 2>/dev/null || true)"
pr_number="$(jq -r '.active_pr_number // 0' "$state_file" 2>/dev/null || printf 0)"
[[ "$pr_number" =~ ^[0-9]+$ ]] || pr_number=0

# Backward-compatible rescue for a pre-durable-session OpenCode PR.
if [[ -z "$branch" ]]; then
  legacy="$(gh pr list --repo "$repo" --base "$base" --state open --limit 100 --json number,headRefName,createdAt 2>/dev/null |
    jq -r --arg p "opencode/issue$target-" '[.[]|select((.headRefName|startswith($p)))]|sort_by(.createdAt)|last // empty' 2>/dev/null || true)"
  if [[ -n "$legacy" ]]; then
    branch="$(jq -r '.headRefName // ""' <<<"$legacy")"
    pr_number="$(jq -r '.number // 0' <<<"$legacy")"
  else
    branch="oc/session-$target"
  fi
fi

# Keep controller checkout on main; the agent gets a dedicated worktree later.
git fetch origin "$base" >/dev/null 2>&1 || true
if git show-ref --verify --quiet "refs/heads/$branch"; then
  :
elif remote_sha="$(git ls-remote --heads origin "$branch" 2>/dev/null | awk '{print $1}')"; then
  if [[ -n "$remote_sha" ]]; then
    git fetch origin "$branch" >/dev/null 2>&1
    git branch "$branch" "refs/remotes/origin/$branch" 2>/dev/null || true
  fi
fi
if ! git show-ref --verify --quiet "refs/heads/$branch"; then
  git branch "$branch" "origin/$base"
fi

gh auth setup-git >/dev/null 2>&1 || echo "::warning title=Git credential setup degraded::The repository credential helper could not be configured; native agent integration may still push."

head_sha="$(git rev-parse "$branch")"
session_id="$(jq -r --arg target "$target" '.session_id // ("oc-" + $target)' "$state_file" 2>/dev/null || printf 'oc-%s' "$target")"
resume="false"
if [[ "$(jq -r '.state_revision // 0' "$state_file" 2>/dev/null || printf 0)" =~ ^[1-9][0-9]*$ ]]; then resume="true"; fi
now="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

json="$(jq --arg branch "$branch" --arg base "$base" --arg head "$head_sha" --arg now "$now" --argjson pr "$pr_number" \
  '.active_branch=$branch | .base_ref=$base | .active_head_sha=$head | .active_pr_number=$pr | .status="active" |
   .phase=(if .phase=="received" then "branch_ready" else "resuming" end) |
   .state_revision=((.state_revision//0)+1) | .updated_at=$now |
   .next_action="inspect current durable branch, then continue the task" |
   .warnings=((.warnings//[]) | unique)' "$state_file")"
printf '%s\n' "$json" > "$state_file"

emit_env OC_SESSION_ID "$session_id"
emit_env OC_SESSION_BRANCH "$branch"
emit_env OC_SESSION_BASE "$base"
emit_env OC_SESSION_START_SHA "$head_sha"
emit_env OC_SESSION_STATE_FILE "$state_file"
emit_env OC_SESSION_RESUME "$resume"
emit_out branch "$branch"
emit_out head_sha "$head_sha"
emit_out resume "$resume"
echo "Durable task branch: $branch @ $head_sha (resume=$resume)"
