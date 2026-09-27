#!/usr/bin/env bash
# Resolves the /oc command into either the current controller-repository flow
# (local mode) or an explicit remote-target flow. Runs once per workflow run,
# before route selection, and emits OC_TARGET_* state to GITHUB_ENV and
# GITHUB_OUTPUT.
#
# Remote-target syntax (all supported, exactly one target allowed):
#   /oc <task> https://github.com/OWNER/REPO
#   /oc <task> github.com/OWNER/REPO
#   /oc target=OWNER/REPO <task>
#   /oc repo=OWNER/REPO <task>
#   /oc repository=OWNER/REPO <task>
#   /oc --repo OWNER/REPO [--base main] <task>
#   /oc --target OWNER/REPO [--base main] <task>
#   /oc --base main --repo OWNER/REPO <task>
#
# When the comment is "/oc continue" with no explicit target, the most recent
# durable target marker (posted by a timed-out remote run's checkpoint) is
# restored so the exact target base/branch is resumed instead of duplicating
# work. The target is external/untrusted project input; only the explicit
# GitHub url and owner/repo forms are accepted, and the rest of the control
# plane keeps applying controller-owned policy.

set -euo pipefail

emit_env() { printf '%s=%s\n' "$1" "$2" >> "$GITHUB_ENV"; }
emit_out() { printf '%s=%s\n' "$1" "$2" >> "$GITHUB_OUTPUT"; }

event_file="${GITHUB_EVENT_PATH:-}"
controller_repo="${GITHUB_REPOSITORY:-}"
target_number="${TARGET_NUMBER:-}"
[[ "$target_number" =~ ^[0-9]+$ ]] || target_number=0

mode="local"
target_repo=""
target_base=""
target_branch=""
resume="0"
from_marker="0"
target_source=""
raw_task=""
issue_body=""

if [[ -n "$event_file" && -f "$event_file" ]]; then
  raw_task="$(jq -r '.comment.body // empty' "$event_file")"
  issue_body="$(jq -r '.issue.body // .pull_request.body // empty' "$event_file")"
fi
raw_task="$(printf '%s' "$raw_task" | sed -E 's#^/[A-Za-z]+[[:space:]]*##')"
task="$raw_task"

declare -a tokens=()
while IFS= read -r -d '' tok; do
  tokens+=("$tok")
done < <(printf '%s' "$task" | awk '{ for (i = 1; i <= NF; i++) printf "%s%c", $i, 0 }')

declare -a kept=()
clean_arg() { printf '%s' "$1" | sed -E 's#(\.git)?/?$##'; }

valid_repo() {
  [[ "$1" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]*[A-Za-z0-9]/[A-Za-z0-9][A-Za-z0-9_.-]*[A-Za-z0-9]$ ]]
}

valid_ref() {
  [[ "$1" =~ ^[A-Za-z0-9._/-]+$ ]]
}

# Documentation placeholder tokens such as "OWNER/REPO", "USER/REPO" or
# "username/repo" appear verbatim in the enterprise mission prompt. They must
# never be parsed as a real remote target (that previously made a run fail by
# attempting to clone the literal OWNER/REPO repository). When either segment
# is a placeholder keyword the value is ignored as a target and kept as task
# text, with a visible warning.
is_placeholder_repo() {
  local value="$1"
  local owner="${value%%/*}"
  local repo_part="${value#*/}"
  case "$owner" in OWNER|owner|USER|user|USERNAME|username) return 0 ;; esac
  case "$repo_part" in REPO|repo) return 0 ;; esac
  return 1
}

# Non-fatal placeholder rejection: returns 1 so callers can preserve the token
# as task text. Genuinely invalid syntax or conflicting targets still exit 2.
set_target() {
  local value="$1"
  local value_clean
  value_clean="$(clean_arg "$value")"
  if is_placeholder_repo "$value_clean"; then
    echo "::warning title=Ignored target placeholder::$value_clean looks like documentation placeholder text (OWNER/REPO); keeping it as task text, not a remote target."
    return 1
  fi
  if ! valid_repo "$value_clean"; then
    echo "::error title=Invalid remote target::Target must be OWNER/REPO (alphanumeric, dash, dot, underscore). Got: $value_clean" >&2
    exit 2
  fi
  if [[ -n "$target_repo" && "$target_repo" != "$value_clean" ]]; then
    echo "::error title=Conflicting remote targets::More than one distinct target repository was specified." >&2
    exit 2
  fi
  target_repo="$value_clean"
  mode="remote"
}

set_base() {
  local value="$1"
  if ! valid_ref "$value"; then
    echo "::error title=Invalid target base::Base must be a valid Git ref name." >&2
    exit 2
  fi
  target_base="$value"
}

i=0
while (( i < ${#tokens[@]} )); do
  tok="${tokens[$i]}"
  case "$tok" in
    repo=*|repository=*|target=*)
      if ! set_target "${tok#*=}"; then
        kept+=("$tok")
      fi
      ;;
    base=*)
      set_base "${tok#*=}"
      ;;
    --repo|--repository|--target)
      if (( i + 1 >= ${#tokens[@]} )); then
        echo "::error title=Missing remote target::$tok requires an OWNER/REPO value." >&2
        exit 2
      fi
      if ! set_target "${tokens[$((i + 1))]}"; then
        kept+=("$tok")
        kept+=("${tokens[$((i + 1))]}")
      fi
      i=$((i + 1))
      ;;
    --base)
      if (( i + 1 >= ${#tokens[@]} )); then
        echo "::error title=Missing target base::--base requires a branch name." >&2
        exit 2
      fi
      set_base "${tokens[$((i + 1))]}"
      i=$((i + 1))
      ;;
    https://github.com/*|http://github.com/*|github.com/*)
      value_clean="${tok#https://github.com/}"
      value_clean="${value_clean#http://github.com/}"
      value_clean="${value_clean#github.com/}"
      if ! set_target "$value_clean"; then
        kept+=("$tok")
      fi
      ;;
    *)
      # A bare OWNER/REPO token is accepted exactly when it is the first
      # positional token; selectors and URLs are recognized in any position.
      if (( ${#kept[@]} == 0 )) && valid_repo "$(clean_arg "$tok")"; then
        if ! set_target "$(clean_arg "$tok")"; then
          kept+=("$tok")
        fi
      else
        # Once an explicit target has been selected, every other token is
        # task text. Colons and ordinary URLs are valid senior-engineering
        # prompt content and must never be interpreted as a second target.
        kept+=("$tok")
      fi
      ;;
  esac
  i=$((i + 1))
done

task="$(printf '%s' "${kept[*]:-}" | sed -e 's/  */ /g' -e 's/^[[:space:]]//' -e 's/[[:space:]]$//')"

# /oc continue without an explicit target: recover the durable marker left by
# the previous timed-out remote run instead of starting duplicate work.
if [[ "$mode" == "local" && "$raw_task" =~ ^continue([[:space:]]|$) ]]; then
  recover_marker=0
  comments_file="${OC_TARGET_COMMENTS_FILE:-}"
  if [[ -n "$comments_file" && -f "$comments_file" ]]; then
    recover_marker=1
  elif [[ "$target_number" != "0" && -n "$controller_repo" ]]; then
    comments="$(gh api --paginate --slurp "/repos/$controller_repo/issues/$target_number/comments?per_page=100" 2>/dev/null | jq 'add // []' 2>/dev/null || true)"
    if [[ -n "$comments" && "$comments" != "[]" ]]; then
      printf '%s\n' "$comments" > /tmp/oc-target-comments.json
      comments_file="/tmp/oc-target-comments.json"
      recover_marker=1
    fi
  fi
  if [[ "$recover_marker" == "1" ]]; then
    marker="$(jq -r '[.[] | select((.body // "") | test("<!-- oc-target-repo:[^>]+ -->"; ""))] | sort_by(.created_at) | last | (.body // "")' "$comments_file" 2>/dev/null || true)"
    if [[ -n "$marker" ]]; then
      recovered_repo="$(sed -nE 's/.*<!-- oc-target-repo:([^ ]+) base:[^ ]+ branch:[^>]+ -->.*/\1/p' <<<"$marker" | head -n 1)"
      recovered_base="$(sed -nE 's/.*<!-- oc-target-repo:[^ ]+ base:([^ ]+) branch:[^>]+ -->.*/\1/p' <<<"$marker" | head -n 1)"
      recovered_branch="$(sed -nE 's/.*<!-- oc-target-repo:[^ ]+ base:[^ ]+ branch:([^>]+) -->.*/\1/p' <<<"$marker" | head -n 1)"
      if valid_repo "$recovered_repo" && valid_ref "$recovered_base" && valid_ref "$recovered_branch"; then
        target_repo="$recovered_repo"
        target_base="$recovered_base"
        target_branch="$recovered_branch"
        mode="remote"
        resume="1"
        from_marker="1"
        echo "Recovered durable remote-target marker for /oc continue: $target_repo@$target_base#$target_branch"
      fi
    fi
  fi
fi

# Recover an explicit Target repository field from the issue/PR body when
# the /oc comment itself has no target and no prior durable marker exists.
# Keep this deliberately narrow so arbitrary URLs in task text never become
# remote targets.
if [[ "$mode" == "local" && -n "$issue_body" ]]; then
  issue_target_line="$(printf '%s\n' "$issue_body" | grep -Eim1 '^[[:space:]]*Target[[:space:]]+(repository|repo)[[:space:]]*:[[:space:]]*[^[:space:]]+' || true)"
  if [[ -n "$issue_target_line" ]]; then
    issue_target="${issue_target_line#*:}"
    issue_target="$(printf '%s' "$issue_target" | sed -E 's/^\s+//; s/\s+$//')"
    issue_target="${issue_target#https://github.com/}"
    issue_target="${issue_target#http://github.com/}"
    issue_target="${issue_target#github.com/}"
    issue_target="$(clean_arg "$issue_target")"
    if valid_repo "$issue_target" && ! is_placeholder_repo "$issue_target"; then
      if set_target "$issue_target"; then
        target_source="issue-body"
        echo "Recovered remote target from explicit issue/PR body field: $target_repo"
      fi
    fi
  fi
fi

[[ -n "$target_base" ]] || target_base="main"
[[ -n "$controller_repo" ]] && controller_target_ok=1 || true

emit_env OC_TARGET_MODE "$mode"
emit_env OC_TARGET_SOURCE "${target_source:-command-or-marker}"
if [[ "$mode" == "remote" ]]; then
  emit_env OC_TARGET_REPO "$target_repo"
  emit_env OC_TARGET_BASE "$target_base"
  emit_env OC_TARGET_BRANCH "$target_branch"
  emit_env OC_TARGET_RESUME "$resume"
  emit_env OC_TARGET_FROM_MARKER "$from_marker"
  emit_env OC_TARGET_TASK "$task"
  # Remote targets are always published and verified by controller-owned logic.
fi

emit_out mode "$mode"
emit_out target_repo "$target_repo"
emit_out target_source "${target_source:-command-or-marker}"
emit_out target_base "$target_base"
emit_out target_branch "$target_branch"
emit_out resume "$resume"
emit_out from_marker "$from_marker"

if [[ "$mode" == "remote" ]]; then
  echo "Remote-target mode selected: $target_repo (base=$target_base${target_branch:+ branch=$target_branch})"
else
  echo "Local controller-repository mode selected: $controller_repo"
fi