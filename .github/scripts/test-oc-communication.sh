#!/usr/bin/env bash
set -euo pipefail

# bash ignores `set -e` for a pipeline that begins with `!`, so a bare
# `! grep …` can never abort this suite. Route every negative assertion
# through these helpers so an unexpected match is a hard failure.
fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
absent() { # absent <fixed-string> <path...>
  local needle="$1"; shift
  if grep -Fq -- "$needle" "$@"; then fail "expected '$needle' to be absent from: $*"; fi
}
absent_in() { # absent_in <fixed-string>  (reads the text from stdin)
  if grep -Fq -- "$1"; then fail "expected '$1' to be absent from the published text"; fi
}

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
bin="$tmp/bin"
mkdir -p "$bin"
cat > "$bin/gh" <<'FAKEGH'
#!/usr/bin/env bash
set -euo pipefail
case "$*" in
  "api user --jq .login") printf '%s\n' github-actions[bot] ;;
  *"/reactions?per_page=100"*)
    if [[ -f "${FAKEGH_REACTION_STATE:-$RUNNER_TEMP/fake-reaction-state}" ]]; then
      printf '%s\n' '[{"id":123,"content":"eyes","user":{"login":"github-actions[bot]"}}]'
    else
      printf '%s\n' '[]'
    fi
    ;;
  *"--method POST"*"/reactions"*)
    printf '%s\n' 123 > "${FAKEGH_REACTION_STATE:-$RUNNER_TEMP/fake-reaction-state}"
    printf '%s\n' '123'
    ;;
  *"--method DELETE"*"/reactions/123"*)
    rm -f "${FAKEGH_REACTION_STATE:-$RUNNER_TEMP/fake-reaction-state}"
    exit 0 ;;
  *"/issues/7/comments?per_page=100"*) printf '%s\n' '[[]]' ;;
  *"api -X POST"*"/issues/"*"/comments"*) printf '%s\n' "$*" >> "${GH_PUBLISH_LOG:-/dev/null}" ;;
  "issue comment"*) printf '%s\n' "$*" > "${GH_COMMENT_FILE:?GH_COMMENT_FILE test seam is unset}" ;;
  *) exit 0 ;;
esac
FAKEGH
chmod +x "$bin/gh"
export PATH="$bin:$PATH"
export FAKEGH_REACTION_STATE="$tmp/fake-reaction-state"
export GITHUB_REPOSITORY=fixture/repo GITHUB_EVENT_NAME=issue_comment RUNNER_TEMP="$tmp" GH_COMMENT_FILE="$tmp/comment" GH_PUBLISH_LOG="$tmp/api-publish" RUN_ID=424242
: > "$GH_PUBLISH_LOG"
cat > "$tmp/event.json" <<'JSON'
{"comment":{"id":7},"issue":{"number":7}}
JSON
export GITHUB_EVENT_PATH="$tmp/event.json" GITHUB_RUN_ID=424242
bash "$root/.github/scripts/oc-running-reaction.sh" add >"$tmp/add"
grep -Fq 'Added /oc running reaction 123.' "$tmp/add"
[[ "$(cat "$tmp/oc-running-reaction.state")" == 123 ]]
[[ -f "$FAKEGH_REACTION_STATE" ]]
bash "$root/.github/scripts/oc-running-reaction.sh" remove >"$tmp/remove"
grep -Fq 'Removed /oc running reaction(s) owned by github-actions[bot].' "$tmp/remove"
[[ ! -e "$tmp/oc-running-reaction.state" ]]
[[ ! -e "$FAKEGH_REACTION_STATE" ]]
bash -n "$root/.github/scripts/oc-running-reaction.sh"
bash -n "$root/.github/scripts/post-oc-result.sh"
node --check "$root/.opencode/plugins/agentic-observability.js"
printf '%s\n' 'clean final answer' > "$tmp/opencode-final-response-1.md"
export A1=success P1=report-only V1=false PR1= SHA1= TASK_MODE=report
bash "$root/.github/scripts/post-oc-result.sh"
body="$(cat "$tmp/comment")"
grep -Fq 'clean final answer' <<<"$body"
absent_in '[object Object]' <<<"$body"
absent_in '[OPENCODE]' <<<"$body"
rm -f "$tmp/comment"
printf '%s\n' 'story response without PR publication' > "$tmp/opencode-final-response-1.md"
export A1=success P1=not-requested V1=false PR1= SHA1= TASK_MODE=code
bash "$root/.github/scripts/post-oc-result.sh"
body="$(cat "$tmp/comment")"
grep -Fq 'story response without PR publication' <<<"$body"
absent_in 'Publication was not requested' <<<"$body"
absent 'tail -n 160 "$REPORT_LOG"' "$root/.github/workflows/opencode.yml"
# Current communication contract: the triggering comment is claimed with an
# authenticated eyes reaction and the running reaction is cleared when the run
# finishes. The obsolete "Mark triggering /oc comment as running" workflow step
# must not return; test-controller.sh enforces the same invariant.
grep -Fq 'Claim unique /oc comment delivery' "$root/.github/workflows/opencode.yml"
grep -Fq 'content=eyes' "$root/.github/scripts/claim-oc-command.sh"
absent 'name: Mark triggering /oc comment as running' "$root/.github/workflows/opencode.yml"
grep -Fq 'Clear /oc running reaction' "$root/.github/workflows/opencode.yml"
grep -Fq 'message.part.updated' "$root/.opencode/plugins/agentic-observability.js"
# Publication is opt-in: the attempt pipeline falls back to false when the
# caller does not provide OC_PUBLISH_REQUESTED.
grep -Fq 'OC_PUBLISH_REQUESTED:-' "$root/.github/scripts/run-attempt-pipeline.sh"

# Production publisher path: with the GH_COMMENT_FILE seam unset, the result
# must go out through the authenticated API POST and never through
# `gh issue comment` (the seam-only form asserted above).
rm -f "$tmp/comment"
: > "$GH_PUBLISH_LOG"
unset GH_COMMENT_FILE
export A1=success P1=not-requested V1=false PR1= SHA1= TASK_MODE=code
bash "$root/.github/scripts/post-oc-result.sh"
grep -Fq 'api -X POST' "$GH_PUBLISH_LOG"
grep -Fq '/repos/fixture/repo/issues/7/comments' "$GH_PUBLISH_LOG"
absent 'issue comment' "$GH_PUBLISH_LOG"
[[ ! -e "$tmp/comment" ]]
cat > "$tmp/event-code.json" <<'JSON'
{"comment":{"id":10,"body":"/oc Fix the workflow bug and add a regression test. Do not create a PR yet."},"issue":{"number":10}}
JSON
: > "$tmp/env-code"
: > "$tmp/out-code"
GITHUB_EVENT_PATH="$tmp/event-code.json" GITHUB_ENV="$tmp/env-code" GITHUB_OUTPUT="$tmp/out-code" bash "$root/.github/scripts/select-oc-task-mode.sh"
grep -Fq 'OC_TASK_MODE=code' "$tmp/env-code"
grep -Fq 'mode=code' "$tmp/out-code"
grep -Fq 'intent=code' "$tmp/out-code"
# Classification no longer emits a report/content downgrade signal at all.
absent 'OC_CONTENT_TASK' "$tmp/env-code" "$tmp/out-code"

cat > "$tmp/event-content2.json" <<'JSON'
{"comment":{"id":12,"body":"/oc Answer this question in two lines.\nSecond line of the question body."},"issue":{"number":12}}
JSON
: > "$tmp/env-content2"
: > "$tmp/out-content2"
GITHUB_EVENT_PATH="$tmp/event-content2.json" GITHUB_ENV="$tmp/env-content2" GITHUB_OUTPUT="$tmp/out-content2" bash "$root/.github/scripts/select-oc-task-mode.sh"
# An ordinary conversational request launches the agent directly; it is not
# rerouted into a capability-starved report/content path.
grep -Fq 'mode=code' "$tmp/out-content2"
grep -Fq 'intent=execute' "$tmp/out-content2"
grep -Fq 'OC_SESSION_REQUIRED=false' "$tmp/out-content2"
absent 'OC_CONTENT_TASK' "$tmp/env-content2" "$tmp/out-content2"
absent 'OC_TASK_MODE=report' "$tmp/env-content2" "$tmp/out-content2"

cat > "$tmp/event-multiline.json" <<'JSON'
{"comment":{"id":13,"body":"/oc Fix the GitHub workflow\nFirst action item for the agent to work on.\nSecond action item with more detail about the CI pipeline."},"issue":{"number":13}}
JSON
: > "$tmp/env-multiline"
: > "$tmp/out-multiline"
GITHUB_EVENT_PATH="$tmp/event-multiline.json" GITHUB_ENV="$tmp/env-multiline" GITHUB_OUTPUT="$tmp/out-multiline" bash "$root/.github/scripts/select-oc-task-mode.sh"
grep -Fq 'OC_COMMAND_TEXT<<' "$tmp/env-multiline"
grep -Fq 'OC_COMMAND_TEXT<<' "$tmp/out-multiline"
grep -Fq 'OC_TASK_MODE=code' "$tmp/env-multiline"
absent 'OC_COMMAND_TEXT=Fix the parser' "$tmp/env-multiline"
absent 'OC_COMMAND_TEXT=Fix the parser' "$tmp/out-multiline"

bash -n "$root/.github/scripts/claim-oc-command.sh"
claim_tmp="$(mktemp -d)"
trap 'rm -rf "$tmp" "$claim_tmp"' EXIT
claim_bin="$claim_tmp/bin"
mkdir -p "$claim_bin"
cat > "$claim_bin/gh" <<'FAKECLAIMGH'
#!/usr/bin/env bash
set -euo pipefail
exit 22
FAKECLAIMGH
chmod +x "$claim_bin/gh"
printf '%s\n' '{"comment":{"id":42},"issue":{"number":7}}' > "$claim_tmp/event.json"
set +e
PATH="$claim_bin:$PATH" GITHUB_REPOSITORY=example/repo \
  GITHUB_EVENT_PATH="$claim_tmp/event.json" GITHUB_OUTPUT="$claim_tmp/output" \
  bash "$root/.github/scripts/claim-oc-command.sh" >"$claim_tmp/log" 2>&1
claim_rc=$?
set -e
[[ "$claim_rc" -ne 0 ]]
grep -Fq 'accepted=false' "$claim_tmp/output"
grep -Fq 'refusing to execute' "$claim_tmp/log"
grep -Fq 'needs: oc_claim' "$root/.github/workflows/opencode.yml"
grep -Fq 'claim-oc-command.sh' "$root/.github/workflows/opencode.yml"
echo 'oc communication/reaction contract: OK'
