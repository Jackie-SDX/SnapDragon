#!/usr/bin/env bash
set -euo pipefail
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
export RUNNER_TEMP="$tmp"
export TARGET_NUMBER=42
export GITHUB_REPOSITORY=example/repo
export GITHUB_OUTPUT="$tmp/out"
export GITHUB_ENV="$tmp/env"
export GITHUB_EVENT_PATH="$tmp/event.json"
printf '%s\n' '{"comment":{"body":"/oc continue"}}' > "$GITHUB_EVENT_PATH"
cat > "$tmp/state.json" <<'JSON'
{"schema_version":1,"session_id":"oc-42","repository":"example/repo","issue":42,"base_ref":"main","active_branch":"oc/session-42","active_pr_number":0,"active_pr_url":"","active_head_sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","phase":"implementation","status":"active","state_revision":3,"next_action":"run tests","target_repository":"Jackie-SDX/cpp-project-template","target_base":"main","target_branch":"oc/issue-152-conan2"}
JSON
SESSION_JSON="$(cat "$tmp/state.json")" OC_SESSION_STATE_FILE="$tmp/state.json" bash .github/scripts/oc-session-state.sh set >/dev/null
test -s "$tmp/state.json"
grep -Fq 'oc/session-42' "$tmp/state.json"
grep -Fq 'oc/issue-152-conan2' "$tmp/state.json"
if grep -Eq 'gh[pous]_ |github_pat_|sk-or-v1-|AIza|Bearer ' "$tmp/state.json"; then exit 1; fi
echo 'durable session state contract: OK'
