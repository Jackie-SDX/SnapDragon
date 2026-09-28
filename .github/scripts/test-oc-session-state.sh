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
mkdir -p "$tmp/bin"
export GH_STUB_BODY="$tmp/issue-body"
printf '%s\n' '' > "$GH_STUB_BODY"
cat > "$tmp/bin/gh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == "issue" && "${2:-}" == "view" ]]; then
  cat "$GH_STUB_BODY"
  exit 0
fi
if [[ "${1:-}" == "api" ]]; then
  shift
  patch=false
  body=""
  while (( $# )); do
    case "$1" in
      -X) [[ "${2:-}" == "PATCH" ]] && patch=true; shift 2 ;;
      -f) body="${2#body=}"; shift 2 ;;
      --paginate) shift ;;
      --jq) shift 2 ;;
      *) endpoint="$1"; shift ;;
    esac
  done
  if [[ "$patch" == true ]]; then
    printf '%s' "$body" > "$GH_STUB_BODY"
    printf '{}\n'
  else
    printf '[]\n'
  fi
  exit 0
fi
exit 0
EOF
chmod +x "$tmp/bin/gh"
export PATH="$tmp/bin:$PATH"
cat > "$tmp/state.json" <<'JSON'
{"schema_version":2,"session_id":"oc-42","repository":"example/repo","issue":42,"base_ref":"main","active_branch":"oc/session-42","active_pr_number":0,"active_pr_url":"","active_head_sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","goal":"run tests","milestone":"implementation","phase":"implementation","status":"active","state_revision":3,"target_repository":"Jackie-SDX/cpp-project-template","target_base":"main","target_branch":"oc/issue-152-conan2","completed_steps":[],"remaining_steps":[],"tests_run":[],"ci_runs":[],"research_sources":[],"warnings":[],"artifacts":[],"next_action":"run tests","durable_work":true,"last_verified_sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","last_verified_evidence":"test fixture","created_at":"2026-09-28T00:00:00Z","updated_at":"2026-09-28T00:00:00Z","last_processed_comment_id":1,"current_request":"/oc continue","last_run_id":null,"agent_attempt":1,"termination_reason":null,"native_session_id":"","native_session_run_id":null,"native_session_artifact":"","native_session_exported":false,"native_session_archive_branch":"","native_session_archive_path":"","native_session_archive_commit":"","last_checkpoint_at":"2026-09-28T00:00:00Z"}
JSON
SESSION_JSON="$(cat "$tmp/state.json")" OC_SESSION_STATE_FILE="$tmp/state.json" bash .github/scripts/oc-session-state.sh set >/dev/null
test -s "$tmp/state.json"
grep -Fq 'oc/session-42' "$tmp/state.json"
grep -Fq 'oc/issue-152-conan2' "$tmp/state.json"
grep -Fq 'oc-42' "$tmp/issue-body"
if grep -Eq 'gh[pous]_|github_pat_|sk-or-v1-|AIza|Bearer ' "$tmp/state.json"; then exit 1; fi
echo 'durable session state contract: OK'
