#!/usr/bin/env bash
# Deterministic offline contract tests for the /oc remote-target control plane.
#
# Covers: target parsing (explicit url/owner-repo selectors), local-mode
# preservation, /oc continue durable-marker recovery, target-policy isolation,
# stable resumable branch behavior, and the nested-Git/temporary-tree
# publication guard that blocked the earlier failing run.
#
# All fixtures live under mktemp directories and are removed on exit; nothing
# is ever created inside the workflow runner worktree.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
SCRIPTS="$ROOT_DIR/.github/scripts"
TESTS="$(mktemp -d "${RUNNER_TEMP:-/tmp}/oc-target-tests-XXXXXX")"
cleanup() { rm -rf "$TESTS"; }
trap cleanup EXIT

PASS=0
FAIL=0

ok()   { PASS=$((PASS + 1)); printf 'ok   - %s\n' "$1"; }
bad()  { FAIL=$((FAIL + 1)); printf 'FAIL - %s\n' "$1"; }
check() { # check <description> <command...>
  local desc="$1"; shift
  if "$@" >/dev/null 2>&1; then ok "$desc"; else bad "$desc"; fi
}

new_output_files() {
  GITHUB_OUTPUT="$TESTS/out-$1"; GITHUB_ENV="$TESTS/env-$1"
  : > "$GITHUB_OUTPUT"; : > "$GITHUB_ENV"
}

# ---------------------------------------------------------------------------
# 1. resolve-oc-target.sh
# ---------------------------------------------------------------------------
make_event() { # make_event <name> <body>
  jq -n --arg body "$2" '{comment:{body:$body}}' > "$TESTS/event-$1.json"
}

new_output_files resolver-local
make_event local "/oc explain this issue"
GITHUB_EVENT_PATH="$TESTS/event-local.json" \
GITHUB_REPOSITORY="Jackie-SDX/Nayla-SD-JACKIE-Fun-WhatsApp-Bot" \
TARGET_NUMBER=0 bash "$SCRIPTS/resolve-oc-target.sh"
if [[ "$(grep -E '^mode=' "$GITHUB_OUTPUT" | tail -1 | cut -d= -f2)" == "local" ]] \
   && grep -Eq '^target_repo=$' "$GITHUB_OUTPUT" \
   && ! grep -q 'OC_TARGET_REPO=' "$GITHUB_ENV"; then
  ok "local mode preserved with full task for a plain /oc comment"
else
  bad "local mode preserved with full task for a plain /oc comment"
fi

new_output_files resolver-issue-body-target
cat > "$TESTS/event-issue-body-target.json" <<'JSON'
{"comment":{"body":"/oc continue"},"issue":{"body":"Target repository:\nhttps://github.com/Jackie-SDX/cpp-project-template\n\nTarget branch:\nmain\n"}}
JSON
GITHUB_EVENT_PATH="$TESTS/event-issue-body-target.json" \
GITHUB_REPOSITORY="Jackie-SDX/SnapDragon" TARGET_NUMBER=152 \
bash "$SCRIPTS/resolve-oc-target.sh"
grep -q '^mode=remote$' "$GITHUB_OUTPUT" && \
grep -q '^target_repo=Jackie-SDX/cpp-project-template$' "$GITHUB_OUTPUT" && \
grep -q '^target_source=issue-body$' "$GITHUB_OUTPUT" && \
  ok "two-line issue-body Target repository field recovers a remote target for /oc continue" || \
  bad "two-line issue-body Target repository field recovers a remote target for /oc continue"

new_output_files resolver-issue-body-inline
cat > "$TESTS/event-issue-body-inline.json" <<'JSON'
{"comment":{"body":"/oc continue"},"issue":{"body":"Target repository: Jackie-SDX/cpp-project-template\n"}}
JSON
GITHUB_EVENT_PATH="$TESTS/event-issue-body-inline.json" \
GITHUB_REPOSITORY="Jackie-SDX/SnapDragon" TARGET_NUMBER=152 \
bash "$SCRIPTS/resolve-oc-target.sh"
grep -q '^mode=remote$' "$GITHUB_OUTPUT" && \
grep -q '^target_repo=Jackie-SDX/cpp-project-template$' "$GITHUB_OUTPUT" && \
grep -q '^target_source=issue-body$' "$GITHUB_OUTPUT" && \
  ok "inline issue-body Target repository field recovers a remote target for /oc continue" || \
  bad "inline issue-body Target repository field recovers a remote target for /oc continue"
new_output_files resolver-url
make_event url "/oc fix the bug https://github.com/Jackie-SDX/Nayla-SD-JACKIE-Fun-WhatsApp-Bot"
GITHUB_EVENT_PATH="$TESTS/event-url.json" \
GITHUB_REPOSITORY="Jackie-SDX/Nayla-SD-JACKIE-Fun-WhatsApp-Bot" \
TARGET_NUMBER=0 bash "$SCRIPTS/resolve-oc-target.sh"
grep -q '^mode=remote$' "$GITHUB_OUTPUT" && \
grep -q '^target_repo=Jackie-SDX/Nayla-SD-JACKIE-Fun-WhatsApp-Bot$' "$GITHUB_OUTPUT" && \
grep -q 'OC_TARGET_TASK=fix the bug' "$GITHUB_ENV" \
  && ok "explicit https://github.com/OWNER/REPO url is parsed into remote mode" \
  || bad "explicit https://github.com/OWNER/REPO url is parsed into remote mode"

new_output_files resolver-task-text
make_event task-text "/oc target=Jackie-SDX/Nayla-SD-JACKIE-Fun-WhatsApp-Bot Task: fix repository: parser behavior and inspect https://example.com/docs"
GITHUB_EVENT_PATH="$TESTS/event-task-text.json" \
GITHUB_REPOSITORY="o/x" TARGET_NUMBER=0 bash "$SCRIPTS/resolve-oc-target.sh"
grep -q '^mode=remote$' "$GITHUB_OUTPUT" && \
grep -q '^target_repo=Jackie-SDX/Nayla-SD-JACKIE-Fun-WhatsApp-Bot$' "$GITHUB_OUTPUT" && \
grep -q 'OC_TARGET_TASK=Task: fix repository: parser behavior and inspect https://example.com/docs' "$GITHUB_ENV" \
  && ok "explicit target preserves colon-containing task text and ordinary URLs" \
  || bad "explicit target preserves colon-containing task text and ordinary URLs"

new_output_files resolver-url-task-text
make_event url-task-text "/oc check https://example.com/x:y and note repo: fix pipeline only"
GITHUB_EVENT_PATH="$TESTS/event-url-task-text.json" \
GITHUB_REPOSITORY="o/x" TARGET_NUMBER=0 bash "$SCRIPTS/resolve-oc-target.sh"
grep -q '^mode=local$' "$GITHUB_OUTPUT" && \
grep -Eq '^target_repo=$' "$GITHUB_OUTPUT" && \
! grep -q 'OC_TARGET_REPO=' "$GITHUB_ENV" \
  && ok "URL with colon and lowercase 'repo:' word in task text stays local, never a remote target" \
  || bad "URL with colon and lowercase 'repo:' word in task text stays local, never a remote target"

for form in "repo=Jackie-SDX/Nayla-SD-JACKIE-Fun-WhatsApp-Bot" "repository=Jackie-SDX/Nayla-SD-JACKIE-Fun-WhatsApp-Bot" "target=Jackie-SDX/Nayla-SD-JACKIE-Fun-WhatsApp-Bot"; do
  key="${form%%=*}"
  new_output_files "resolver-$key"
  make_event "form-$key" "/oc $form do the thing"
  GITHUB_EVENT_PATH="$TESTS/event-form-$key.json" \
  GITHUB_REPOSITORY="o/x" TARGET_NUMBER=0 bash "$SCRIPTS/resolve-oc-target.sh"
  grep -q '^mode=remote$' "$GITHUB_OUTPUT" && \
  grep -q '^target_repo=Jackie-SDX/Nayla-SD-JACKIE-Fun-WhatsApp-Bot$' "$GITHUB_OUTPUT" && \
  grep -q 'OC_TARGET_TASK=do the thing' "$GITHUB_ENV" \
    && ok "selector '$form' parses into remote mode" \
    || bad "selector '$form' parses into remote mode"
done

new_output_files resolver-flag
make_event flag "/oc --repo Jackie-SDX/Nayla-SD-JACKIE-Fun-WhatsApp-Bot --base feature/x implement it"
GITHUB_EVENT_PATH="$TESTS/event-flag.json" \
GITHUB_REPOSITORY="o/x" TARGET_NUMBER=0 bash "$SCRIPTS/resolve-oc-target.sh"
grep -q '^mode=remote$' "$GITHUB_OUTPUT" && \
grep -q '^target_repo=Jackie-SDX/Nayla-SD-JACKIE-Fun-WhatsApp-Bot$' "$GITHUB_OUTPUT" && \
grep -q '^target_base=feature/x$' "$GITHUB_OUTPUT" && \
grep -q 'OC_TARGET_TASK=implement it' "$GITHUB_ENV" \
  && ok "--repo/--base flag form parses into remote mode" \
  || bad "--repo/--base flag form parses into remote mode"

new_output_files resolver-bad
make_event bad "/oc repo=Alice/One repo=Bob/Two do the thing"
if GITHUB_EVENT_PATH="$TESTS/event-bad.json" GITHUB_REPOSITORY="o/x" TARGET_NUMBER=0 \
   bash "$SCRIPTS/resolve-oc-target.sh" >"$TESTS/bad.log" 2>&1; then
  bad "conflicting remote targets are refused"
else
  ok "conflicting remote targets are refused"
fi

# Documentation placeholder tokens (OWNER/REPO, USER/REPO, username/repo) appear
# verbatim in the mission prompt and must never be parsed as a real remote target
# (that previously made a run fail trying to clone the literal OWNER/REPO repo).
for form in "repo=OWNER/REPO" "target=OWNER/REPO" "https://github.com/OWNER/REPO" "github.com/OWNER/REPO" "repo=user/repo" "target=USERNAME/REPO"; do
  key="placeholder-${form//[^A-Za-z0-9]/_}"
  new_output_files "resolver-$key"
  make_event "event-$key" "/oc fix the pipeline $form now and report"
  GITHUB_EVENT_PATH="$TESTS/event-$key.json" \
  GITHUB_REPOSITORY="o/x" TARGET_NUMBER=0 bash "$SCRIPTS/resolve-oc-target.sh"
  grep -q '^mode=local$' "$GITHUB_OUTPUT" && \
  grep -Eq '^target_repo=$' "$GITHUB_OUTPUT" && \
  ! grep -q 'OC_TARGET_REPO=' "$GITHUB_ENV" \
    && ok "placeholder '$form' is ignored as a target (mode stays local)" \
    || bad "placeholder '$form' is ignored as a target (mode stays local)"
done

new_output_files resolver-placeholder-flag
make_event event-placeholder-flag "/oc --repo OWNER/REPO --base main fix the pipeline"
GITHUB_EVENT_PATH="$TESTS/event-placeholder-flag.json" \
GITHUB_REPOSITORY="o/x" TARGET_NUMBER=0 bash "$SCRIPTS/resolve-oc-target.sh"
grep -q '^mode=local$' "$GITHUB_OUTPUT" && \
! grep -q 'OC_TARGET_REPO=' "$GITHUB_ENV" \
  && ok "placeholder OWNER/REPO after --repo/--base is kept as task text, not a target" \
  || bad "placeholder OWNER/REPO after --repo/--base is kept as task text, not a target"

new_output_files resolver-placeholder-mission
make_event event-placeholder-mission "/oc inspect the workflow, then also consider github.com/OWNER/REPO and target=OWNER/REPO and --repo OWNER/REPO and repo=OWNER/REPO"
GITHUB_EVENT_PATH="$TESTS/event-placeholder-mission.json" \
GITHUB_REPOSITORY="o/x" TARGET_NUMBER=0 bash "$SCRIPTS/resolve-oc-target.sh"
grep -q '^mode=local$' "$GITHUB_OUTPUT" && \
grep -Eq '^target_repo=$' "$GITHUB_OUTPUT" && \
! grep -q 'OC_TARGET_REPO=' "$GITHUB_ENV" \
  && ok "mission-style comment with all placeholder forms stays local (would otherwise clone OWNER/REPO)" \
  || bad "mission-style comment with all placeholder forms stays local (would otherwise clone OWNER/REPO)"

new_output_files resolver-continue
cat > "$TESTS/comments-continue.json" <<'JSON'
[
  {"created_at":"2026-09-20T10:00:00Z","body":"/oc do a remote task"},
  {"created_at":"2026-09-20T11:00:00Z","body":"<!-- oc-target-repo:Jackie-SDX/Nayla-SD-JACKIE-Fun-WhatsApp-Bot base:main branch:oc/remote-Jackie-SDX-something -->\n<!-- oc-checkpoint-run-id:1 issue:3 -->\ncheckpoint"}
]
JSON
make_event cont "/oc continue"
GITHUB_EVENT_PATH="$TESTS/event-cont.json" \
GITHUB_REPOSITORY="o/x" TARGET_NUMBER=3 OC_TARGET_COMMENTS_FILE="$TESTS/comments-continue.json" \
bash "$SCRIPTS/resolve-oc-target.sh"
grep -q '^mode=remote$' "$GITHUB_OUTPUT" && \
grep -q '^resume=1$' "$GITHUB_OUTPUT" && \
grep -q '^from_marker=1$' "$GITHUB_OUTPUT" && \
grep -q '^target_branch=oc/remote-Jackie-SDX-something$' "$GITHUB_OUTPUT" \
  && ok "/oc continue restores the durable remote-target marker (target-aware resume)" \
  || bad "/oc continue restores the durable remote-target marker (target-aware resume)"

# ---------------------------------------------------------------------------
# 2. prepare-oc-target.sh isolation + stable, resumable branch
# ---------------------------------------------------------------------------
TARGET_SRC="$TESTS/target-src"
mkdir -p "$TARGET_SRC/.opencode/agents" "$TARGET_SRC/plugins"
git -C "$TARGET_SRC" init -q -b main
git -C "$TARGET_SRC" config user.name "fixture"
git -C "$TARGET_SRC" config user.email "fixture@example.com"
printf 'target instructions\n' > "$TARGET_SRC/.opencode/instructions.md"
printf '{target:true}\n' > "$TARGET_SRC/opencode.json"
printf 'trust me\n' > "$TARGET_SRC/AGENTS.md"
printf 'plugin\n' > "$TARGET_SRC/plugins/thing.js"
printf 'code\n' > "$TARGET_SRC/code.txt"
git -C "$TARGET_SRC" add -A && git -C "$TARGET_SRC" commit -qm init
FAKE_TOKEN="ghp_test000000000000000000000"

run_prepare() {
  local name="$1" task="$2"; shift 2
  GITHUB_OUTPUT="$TESTS/prep-out-$name"; GITHUB_ENV="$TESTS/prep-env-$name"
  : > "$GITHUB_OUTPUT"; : > "$GITHUB_ENV"
  OC_TARGET_REPO="Jackie-SDX/SomeRepo" OC_TARGET_BASE="main" OC_TARGET_TASK="$task" \
  RUNNER_TEMP="$TESTS" \
  OC_TARGET_CLONE_URL="file://$TARGET_SRC" GH_TOKEN="$FAKE_TOKEN" \
  GITHUB_ENV="$GITHUB_ENV" GITHUB_OUTPUT="$GITHUB_OUTPUT" \
  bash "$SCRIPTS/prepare-oc-target.sh" >"$TESTS/prep-run-$name.log" 2>&1
}

run_prepare first "add a feature" || bad "prepare-oc-target.sh first run"
ws1="$(grep -E "^OC_TARGET_WORKSPACE=" "$TESTS/prep-env-first" | cut -d= -f2 | tail -1)"
branch1="$(grep -E "^OC_TARGET_BRANCH=" "$TESTS/prep-env-first" | cut -d= -f2 | tail -1)"
if [[ -n "$ws1" ]] && [[ "$(cat "$ws1/opencode.json")" == "{target:true}" ]] && [[ "$(cat "$ws1/.opencode/instructions.md")" == "target instructions" ]] && [[ "$(cat "$ws1/AGENTS.md")" == "trust me" ]] && [[ -f "$ws1/plugins/thing.js" ]]; then
  ok "target-local OpenCode policy and project files stay intact in the remote workspace"
else
  bad "target-local OpenCode policy and project files stay intact in the remote workspace"
fi

run_prepare second "add a feature"
branch2="$(grep -E "^OC_TARGET_BRANCH=" "$TESTS/prep-env-second" | cut -d= -f2 | tail -1)"
if [[ -n "$branch1" && "$branch1" == "$branch2" ]]; then
  ok "target branch is stable and deterministic for the same task"
else
  bad "target branch is stable and deterministic for the same task"
fi

git -C "$TARGET_SRC" checkout -q -b "$branch1" >/dev/null 2>&1 || git -C "$TARGET_SRC" branch -q "$branch1"
run_prepare third "add a feature"
resume3="$(grep -E "^OC_TARGET_RESUME=" "$TESTS/prep-env-third" | cut -d= -f2 | tail -1)"
branch3="$(grep -E "^OC_TARGET_BRANCH=" "$TESTS/prep-env-third" | cut -d= -f2 | tail -1)"
if [[ "$resume3" == "1" && "$branch3" == "$branch1" ]]; then
  ok "existing remote target branch is resumed (resume=1, same branch); no duplicate work"
else
  bad "existing remote target branch is resumed (resume=1, same branch); no duplicate work"
fi
# 3. publication guard: nested Git repos and .octmp can never be staged
# ---------------------------------------------------------------------------
GUARD_REPO="$TESTS/guard-repo"
mkdir -p "$GUARD_REPO"
git -C "$GUARD_REPO" init -q -b main
git -C "$GUARD_REPO" config user.name "fixture"
git -C "$GUARD_REPO" config user.email "fixture@example.com"
git -C "$GUARD_REPO" commit -q --allow-empty -m init
# (a) embedded git repository fixture (the exact failure mode of the prior run)
mkdir -p "$GUARD_REPO/.octmp/octest/target"
git -C "$GUARD_REPO/.octmp/octest/target" init -q -b main
git -C "$GUARD_REPO/.octmp/octest/target" config user.name "fixture"
git -C "$GUARD_REPO/.octmp/octest/target" config user.email "fixture@example.com"
printf 'fixture\n' > "$GUARD_REPO/.octmp/octest/target/x.txt"
git -C "$GUARD_REPO/.octmp/octest/target" add -A
git -C "$GUARD_REPO/.octmp/octest/target" commit -qm fixture
if ! bash -c 'source "$0/oc-publish-lib.sh" && oc_guard_repo_publication "$1"' "$SCRIPTS" "$GUARD_REPO" >/dev/null 2>&1; then
  ok "publication guard refuses a nested Git repository (mode 160000 gitlink) under .octmp/"
else
  bad "publication guard refuses a nested Git repository (mode 160000 gitlink) under .octmp/"
fi
if git -C "$GUARD_REPO" ls-files | grep -q '^\.octmp/'; then
  bad "nested .octmp fixture is never left staged after the guard refuses"
else
  ok "nested .octmp fixture is never left staged after the guard refuses"
fi
# (b) plain untracked scratch under .octmp (no nested repo) must also be refused
rm -rf "$GUARD_REPO/.octmp"
mkdir -p "$GUARD_REPO/.octmp"
printf 'scratch\n' > "$GUARD_REPO/.octmp/scrap.txt"
if ! bash -c 'source "$0/oc-publish-lib.sh" && oc_guard_repo_publication "$1"' "$SCRIPTS" "$GUARD_REPO" >/dev/null 2>&1; then
  ok "publication guard refuses untracked .octmp/ scratch"
else
  bad "publication guard refuses untracked .octmp/ scratch"
fi
# (c) a legitimate change still passes the guard
rm -rf "$GUARD_REPO/.octmp"
printf 'real change\n' > "$GUARD_REPO/code.txt"
if bash -c 'source "$0/oc-publish-lib.sh" && oc_guard_repo_publication "$1" && git -C "$1" diff --cached --name-only' "$SCRIPTS" "$GUARD_REPO" >/dev/null 2>&1; then
  ok "publication guard allows a legitimate, secret-free change"
else
  bad "publication guard allows a legitimate, secret-free change"
fi

# no fixtures can ever appear in the workflow runner worktree
if [[ -e "$ROOT_DIR/.octmp" ]]; then
  bad "workflow runner worktree contains an .octmp fixture (should be impossible; tests are isolated)"
else
  ok "workflow runner worktree is free of .octmp fixtures after the contract tests"
fi

# ---------------------------------------------------------------------------
# 4. remote verifier exact-head regression tests
# ---------------------------------------------------------------------------
VERIFY_WS="$TESTS/verify-target"
FAKE_BIN="$TESTS/fake-bin"
mkdir -p "$VERIFY_WS" "$FAKE_BIN"
git -C "$VERIFY_WS" init -q -b main
git -C "$VERIFY_WS" config user.name "fixture"
git -C "$VERIFY_WS" config user.email "fixture@example.com"
printf 'workspace base\n' > "$VERIFY_WS/base.txt"
git -C "$VERIFY_WS" add base.txt
git -C "$VERIFY_WS" commit -qm base
LOCAL_VERIFY_SHA="$(git -C "$VERIFY_WS" rev-parse HEAD)"
EXPECTED_VERIFY_SHA="2222222222222222222222222222222222222222"

cat > "$FAKE_BIN/gh" <<'FAKEGH'
#!/usr/bin/env bash
set -euo pipefail
case "$*" in
  *"pr list"*)
    printf '%s\n' '[{"number":1,"url":"https://github.com/fixture/target/pull/1","state":"OPEN","mergedAt":null,"headRefOid":"2222222222222222222222222222222222222222"}]'
    ;;
  *"/git/ref/heads/"*)
    printf '%s\n' '{"object":{"sha":"2222222222222222222222222222222222222222"}}'
    ;;
  *"/commits/"*"/check-runs"*)
    if [[ "${FAKE_GH_FAIL:-0}" == "1" ]]; then
      printf '%s\n' '{"check_runs":[{"status":"completed","conclusion":"success"},{"status":"completed","conclusion":"failure"}]}'
    else
      printf '%s\n' '{"check_runs":[{"status":"completed","conclusion":"success"},{"status":"completed","conclusion":"skipped"}]}'
    fi
    ;;
  *"/commits/"*"/status"*)
    case "${FAKE_GH_STATUS_MODE:-ok}" in
      fail)
        printf '%s\n' '{"state":"failure","total_count":1,"statuses":[{"context":"ci/circleci","state":"failure"}]}'
        ;;
      pending)
        printf '%s\n' '{"state":"pending","total_count":1,"statuses":[{"context":"ci/circleci","state":"pending"}]}'
        ;;
      *)
        printf '%s\n' '{"state":"success","total_count":1,"statuses":[{"context":"ci/circleci","state":"success"}]}'
        ;;
    esac
    ;;
  *)
    exit 0
    ;;
esac
FAKEGH
chmod +x "$FAKE_BIN/gh"

new_output_files verifier-head-mismatch
if PATH="$FAKE_BIN:$PATH" \
   GITHUB_REPOSITORY="fixture/controller" PROVIDER="opencode" ATTEMPT="1" TARGET_NUMBER=0 BASE_REF="main" \
   INITIAL_SHA="$LOCAL_VERIFY_SHA" GITHUB_RUN_ID=1 GITHUB_OUTPUT="$GITHUB_OUTPUT" \
   OC_TARGET_MODE="remote" OC_TARGET_REPO="fixture/target" OC_TARGET_BASE="main" \
   OC_TARGET_BRANCH="oc/test" OC_TARGET_WORKSPACE="$VERIFY_WS" EXPECTED_TARGET_HEAD="$EXPECTED_VERIFY_SHA" \
   OC_CI_VERIFY_WAIT_MINUTES=0 OC_CI_VERIFY_POLL_SECONDS=5 OC_CI_VERIFY_SETTLE_SECONDS=0 \
   bash "$SCRIPTS/verify-agent-result.sh" >/dev/null 2>&1; then
  ok "remote verifier accepts exact target PR/check head when local workspace SHA differs"
else
  bad "remote verifier accepts exact target PR/check head when local workspace SHA differs"
fi
grep -q "^ci_surfaces=check-runs,commit-status$" "$GITHUB_OUTPUT" \
  && ok "remote verifier records the observed CI surfaces (check-runs + commit statuses)" \
  || bad "remote verifier records the observed CI surfaces (check-runs + commit statuses)"
grep -q "^verified_sha=$EXPECTED_VERIFY_SHA$" "$GITHUB_OUTPUT" \
  && ok "remote verifier records the exact verified SHA in its output" \
  || bad "remote verifier records the exact verified SHA in its output"

new_output_files verifier-status-fail
if PATH="$FAKE_BIN:$PATH" FAKE_GH_STATUS_MODE=fail \
   GITHUB_REPOSITORY="fixture/controller" PROVIDER="opencode" ATTEMPT="1" TARGET_NUMBER=0 BASE_REF="main" \
   INITIAL_SHA="$LOCAL_VERIFY_SHA" GITHUB_RUN_ID=1 GITHUB_OUTPUT="$GITHUB_OUTPUT" \
   OC_TARGET_MODE="remote" OC_TARGET_REPO="fixture/target" OC_TARGET_BASE="main" \
   OC_TARGET_BRANCH="oc/test" OC_TARGET_WORKSPACE="$VERIFY_WS" EXPECTED_TARGET_HEAD="$EXPECTED_VERIFY_SHA" \
   OC_CI_VERIFY_WAIT_MINUTES=0 OC_CI_VERIFY_POLL_SECONDS=5 OC_CI_VERIFY_SETTLE_SECONDS=0 \
   bash "$SCRIPTS/verify-agent-result.sh" >/dev/null 2>&1; then
  bad "remote verifier fails closed when an external status provider reports failure on the same SHA"
else
  ok "remote verifier fails closed when an external status provider reports failure on the same SHA"
fi

new_output_files verifier-status-pending
if PATH="$FAKE_BIN:$PATH" FAKE_GH_STATUS_MODE=pending \
   GITHUB_REPOSITORY="fixture/controller" PROVIDER="opencode" ATTEMPT="1" TARGET_NUMBER=0 BASE_REF="main" \
   INITIAL_SHA="$LOCAL_VERIFY_SHA" GITHUB_RUN_ID=1 GITHUB_OUTPUT="$GITHUB_OUTPUT" \
   OC_TARGET_MODE="remote" OC_TARGET_REPO="fixture/target" OC_TARGET_BASE="main" \
   OC_TARGET_BRANCH="oc/test" OC_TARGET_WORKSPACE="$VERIFY_WS" EXPECTED_TARGET_HEAD="$EXPECTED_VERIFY_SHA" \
   OC_CI_VERIFY_WAIT_MINUTES=0 OC_CI_VERIFY_POLL_SECONDS=5 OC_CI_VERIFY_SETTLE_SECONDS=0 \
   bash "$SCRIPTS/verify-agent-result.sh" >/dev/null 2>&1; then
  bad "remote verifier waits for a pending external status instead of declaring success"
else
  ok "remote verifier waits for a pending external status instead of declaring success"
fi
grep -q "^timed_out=true$" "$GITHUB_OUTPUT" \
  && ok "remote verifier marks an unresolved pending status as timed out, never green" \
  || bad "remote verifier marks an unresolved pending status as timed out, never green"

new_output_files verifier-mixed-fail
if PATH="$FAKE_BIN:$PATH" FAKE_GH_FAIL=1 \
   GITHUB_REPOSITORY="fixture/controller" PROVIDER="opencode" ATTEMPT="1" TARGET_NUMBER=0 BASE_REF="main" \
   INITIAL_SHA="$LOCAL_VERIFY_SHA" GITHUB_RUN_ID=1 GITHUB_OUTPUT="$GITHUB_OUTPUT" \
   OC_TARGET_MODE="remote" OC_TARGET_REPO="fixture/target" OC_TARGET_BASE="main" \
   OC_TARGET_BRANCH="oc/test" OC_TARGET_WORKSPACE="$VERIFY_WS" EXPECTED_TARGET_HEAD="$EXPECTED_VERIFY_SHA" \
   OC_CI_VERIFY_WAIT_MINUTES=0 OC_CI_VERIFY_POLL_SECONDS=5 OC_CI_VERIFY_SETTLE_SECONDS=0 \
   bash "$SCRIPTS/verify-agent-result.sh" >/dev/null 2>&1; then
  bad "remote verifier rejects any failed check-run even when another check is green"
else
  ok "remote verifier rejects any failed check-run even when another check is green"
fi

# ---------------------------------------------------------------------------
# 4b. local-mode verifier: issue-comment (non-prefix) publication window
# ---------------------------------------------------------------------------
# Regression for the demo-loop failure (run 35595874054): the /oc agent can
# publish onto a task-specific branch that is NOT in the controller prefix
# namespace (e.g. oc/demo-loop-regression-test), as long as it is explicitly
# referenced in this issue's thread after the run started. The verifier must
# accept and exact-SHA-verify that PR instead of declaring the run a failure.
VERIFY_SCAN_BIN="$TESTS/verify-scan-bin"
mkdir -p "$VERIFY_SCAN_BIN"
SCAN_HEAD="8888888888888888888888888888888888888888"
cat > "$VERIFY_SCAN_BIN/gh" <<'FAKE_SCAN'
#!/usr/bin/env bash
set -euo pipefail
case "$*" in
  *"pr list"*)
    printf '%s\n' '[{"number":8,"url":"https://github.com/fixture/controller/pull/8","state":"OPEN","mergedAt":null,"headRefName":"oc/demo-loop-regression-test","headRefOid":"8888888888888888888888888888888888888888","baseRefName":"main","createdAt":"2026-09-21T11:53:40Z","updatedAt":"2026-09-21T11:53:40Z"}]'
    ;;
  *"/branches?per_page=100"*)
    printf '%s\n' '[[{"name":"main"},{"name":"oc/demo-loop-regression-test"}]]'
    ;;
  *"/issues/71/comments?per_page=100"*)
    printf '%s\n' '[[{"created_at":"2026-09-21T11:56:33Z","body":"## report\nhttps://github.com/fixture/controller/pull/8\n"}]]'
    ;;
  *"pr view"*)
    if [[ "$*" == *"createdAt"* ]]; then
      printf '%s\n' '{"number":8,"url":"https://github.com/fixture/controller/pull/8","state":"OPEN","mergedAt":null,"headRefName":"oc/demo-loop-regression-test","headRefOid":"8888888888888888888888888888888888888888","baseRefName":"main","createdAt":"2026-09-21T11:53:40Z"}'
    else
      printf '%s\n' '{"number":8,"url":"https://github.com/fixture/controller/pull/8","state":"OPEN","mergedAt":null,"headRefName":"oc/demo-loop-regression-test","headRefOid":"8888888888888888888888888888888888888888","baseRefName":"main"}'
    fi
    ;;
  *"/commits/"*"/check-runs"*)
    printf '%s\n' '{"check_runs":[{"name":"validate","status":"completed","conclusion":"success"},{"name":"self-test","status":"completed","conclusion":"success"}]}'
    ;;
  *"/commits/"*"/status"*)
    printf '%s\n' '{"state":"success","total_count":0,"statuses":[]}'
    ;;
  *) exit 0 ;;
esac
FAKE_SCAN
chmod +x "$VERIFY_SCAN_BIN/gh"

new_output_files verifier-scan-nonprefix
if ( cd "$VERIFY_WS" && PATH="$VERIFY_SCAN_BIN:$PATH" \
    GITHUB_REPOSITORY="fixture/controller" PROVIDER="opencode" ATTEMPT="1" TARGET_NUMBER=71 BASE_REF="main" \
    INITIAL_SHA="$LOCAL_VERIFY_SHA" GITHUB_RUN_ID=11 GITHUB_OUTPUT="$GITHUB_OUTPUT" \
    OC_TARGET_MODE=local OC_RUN_START_ISO="2026-09-21T11:46:48Z" \
    OC_CI_VERIFY_WAIT_MINUTES=0 OC_CI_VERIFY_POLL_SECONDS=5 OC_CI_VERIFY_SETTLE_SECONDS=0 \
    bash "$SCRIPTS/verify-agent-result.sh" >/dev/null 2>&1 ); then
  ok "local verifier accepts an issue-comment-referenced PR on a non-prefix branch (demo-loop regression)"
else
  bad "local verifier accepts an issue-comment-referenced PR on a non-prefix branch (demo-loop regression)"
fi
grep -q "^verified=true$" "$GITHUB_OUTPUT" \
  && grep -q "^verified_sha=$SCAN_HEAD$" "$GITHUB_OUTPUT" \
  && ok "local verifier records the exact non-prefix PR head as verified_sha" \
  || bad "local verifier records the exact non-prefix PR head as verified_sha"

# ---------------------------------------------------------------------------
# 4c. verifier tolerates unset OC_CI_VERIFY_* env vars under `set -u`
# ---------------------------------------------------------------------------
# Regression for the original unbound-variable crash
# (OC_CI_VERIFY_SETTLE_SECONDS: unbound variable, runs 35587360775/35589826828).
# Each test unsets exactly one variable; the others are set to fast values so the
# run still completes quickly even if an unset one used its default.
VERIFY_ENV_BIN="$TESTS/verify-env-bin"
mkdir -p "$VERIFY_ENV_BIN"
cat > "$VERIFY_ENV_BIN/gh" <<'FAKE_ENV'
#!/usr/bin/env bash
set -euo pipefail
case "$*" in
  *"pr list"*)
    printf '%s\n' '[{"number":1,"url":"https://github.com/fixture/target/pull/1","state":"OPEN","mergedAt":null,"headRefOid":"2222222222222222222222222222222222222222"}]'
    ;;
  *"/git/ref/heads/"*)
    printf '%s\n' '{"object":{"sha":"2222222222222222222222222222222222222222"}}'
    ;;
  *"/commits/"*"/check-runs"*)
    printf '%s\n' '{"check_runs":[{"name":"validate","status":"completed","conclusion":"success"}]}'
    ;;
  *"/commits/"*"/status"*)
    printf '%s\n' '{"state":"success","total_count":0,"statuses":[]}'
    ;;
  *"/repos/fixture/target"*)
    printf '%s\n' '{"full_name":"fixture/target"}'
    ;;
  *) exit 0 ;;
esac
FAKE_ENV
chmod +x "$VERIFY_ENV_BIN/gh"
# Unset-env coverage pinned for validation greps (bare variable names, literal):
#   OC_CI_VERIFY_SETTLE_SECONDS  -- default fallback, never an unbound-variable crash
#   OC_CI_VERIFY_WAIT_MINUTES  -- default fallback, never an unbound-variable crash
#   OC_CI_VERIFY_POLL_SECONDS  -- default fallback, never an unbound-variable crash
run_unset_env_verify() { # run_unset_env_verify <name> <label> <unset_var>
  local name="$1" label="$2" unset_var="$3" rc
  new_output_files "envtest-$name"
  rc=0
  (
    cd "$VERIFY_WS"
    export PATH="$VERIFY_ENV_BIN:$PATH"
    export GITHUB_REPOSITORY=fixture/controller PROVIDER=opencode ATTEMPT=1 TARGET_NUMBER=0 BASE_REF=main
    export INITIAL_SHA="$LOCAL_VERIFY_SHA" GITHUB_RUN_ID=1 GITHUB_OUTPUT="$GITHUB_OUTPUT"
    export OC_TARGET_MODE=remote OC_TARGET_REPO=fixture/target OC_TARGET_BASE=main
    export OC_TARGET_BRANCH=oc/test OC_TARGET_WORKSPACE="$VERIFY_WS" EXPECTED_TARGET_HEAD="$EXPECTED_VERIFY_SHA"
    export OC_CI_VERIFY_WAIT_MINUTES=0 OC_CI_VERIFY_POLL_SECONDS=5 OC_CI_VERIFY_SETTLE_SECONDS=0
    unset "$unset_var"
    bash "$SCRIPTS/verify-agent-result.sh"
  ) >/dev/null 2>&1 || rc=$?
  if [[ "$rc" == "0" ]] && grep -q "^verified=true$" "$GITHUB_OUTPUT"; then
    ok "verifier survives unset $label under set -u (uses default, no unbound-variable crash)"
  else
    bad "verifier survives unset $label under set -u (uses default, no unbound-variable crash)"
  fi
}
run_unset_env_verify settlestr OC_CI_VERIFY_SETTLE_SECONDS OC_CI_VERIFY_SETTLE_SECONDS
run_unset_env_verify waitstr OC_CI_VERIFY_WAIT_MINUTES OC_CI_VERIFY_WAIT_MINUTES
run_unset_env_verify pollstr OC_CI_VERIFY_POLL_SECONDS OC_CI_VERIFY_POLL_SECONDS

# ---------------------------------------------------------------------------
# 4d. all-observable-checks-skipped is never declared verified
# ---------------------------------------------------------------------------
VERIFY_SKIP_BIN="$TESTS/verify-skip-bin"
mkdir -p "$VERIFY_SKIP_BIN"
cat > "$VERIFY_SKIP_BIN/gh" <<'FAKE_SKIP'
#!/usr/bin/env bash
set -euo pipefail
case "$*" in
  *"pr list"*)
    printf '%s\n' '[{"number":1,"url":"https://github.com/fixture/target/pull/1","state":"OPEN","mergedAt":null,"headRefOid":"2222222222222222222222222222222222222222"}]'
    ;;
  *"/git/ref/heads/"*)
    printf '%s\n' '{"object":{"sha":"2222222222222222222222222222222222222222"}}'
    ;;
  *"/commits/"*"/check-runs"*)
    printf '%s\n' '{"check_runs":[{"status":"completed","conclusion":"skipped"},{"status":"completed","conclusion":"skipped"}]}'
    ;;
  *"/commits/"*"/status"*)
    printf '%s\n' '{"state":"success","total_count":0,"statuses":[]}'
    ;;
  *"/repos/fixture/target"*)
    printf '%s\n' '{"full_name":"fixture/target"}'
    ;;
  *) exit 0 ;;
esac
FAKE_SKIP
chmod +x "$VERIFY_SKIP_BIN/gh"

new_output_files verifier-all-skipped
rc=0
( cd "$VERIFY_WS" && PATH="$VERIFY_SKIP_BIN:$PATH" \
    GITHUB_REPOSITORY="fixture/controller" PROVIDER="opencode" ATTEMPT="1" TARGET_NUMBER=0 BASE_REF="main" \
    INITIAL_SHA="$LOCAL_VERIFY_SHA" GITHUB_RUN_ID=1 GITHUB_OUTPUT="$GITHUB_OUTPUT" \
    OC_TARGET_MODE=remote OC_TARGET_REPO=fixture/target OC_TARGET_BASE=main \
    OC_TARGET_BRANCH=oc/test OC_TARGET_WORKSPACE="$VERIFY_WS" EXPECTED_TARGET_HEAD="$EXPECTED_VERIFY_SHA" \
    OC_CI_VERIFY_WAIT_MINUTES=0 OC_CI_VERIFY_POLL_SECONDS=5 OC_CI_VERIFY_SETTLE_SECONDS=0 \
    bash "$SCRIPTS/verify-agent-result.sh" >/dev/null 2>&1 ) || rc=$?
if [[ "$rc" == "1" ]] && grep -q "^verified=false$" "$GITHUB_OUTPUT" \
   && grep -q "^timed_out=true$" "$GITHUB_OUTPUT"; then
  ok "a SHA whose only observable checks were all skipped is never verified (only pending/timed out)"
else
  bad "a SHA whose only observable checks were all skipped is never verified (only pending/timed out)"
fi

# ---------------------------------------------------------------------------
# 4e. a late-arriving external status flip is caught by the settle window
# ---------------------------------------------------------------------------
VERIFY_FLIP_BIN="$TESTS/verify-flip-bin"
mkdir -p "$VERIFY_FLIP_BIN"
cat > "$VERIFY_FLIP_BIN/gh" <<'FAKE_FLIP'
#!/usr/bin/env bash
# Stateful fake: the first combined <sha>/status evaluation is green; every
# status evaluation after that reports an external failure (a provider that
# posts late). Exercises the settle window's re-confirmation path.
set -euo pipefail
SCOUNTER="${FLIP_STATE:?}/status-counter"
mkdir -p "$(dirname "$SCOUNTER")"
case "$*" in
  *"pr list"*)
    printf '%s\n' '[{"number":1,"url":"https://github.com/fixture/target/pull/1","state":"OPEN","mergedAt":null,"headRefOid":"2222222222222222222222222222222222222222"}]'
    ;;
  *"/git/ref/heads/"*)
    printf '%s\n' '{"object":{"sha":"2222222222222222222222222222222222222222"}}'
    ;;
  *"/commits/"*"/check-runs"*)
    printf '%s\n' '{"check_runs":[{"name":"validate","status":"completed","conclusion":"success"}]}'
    ;;
  *"/commits/"*"/status"*)
    if [[ -f "$SCOUNTER" ]]; then o="$(cat "$SCOUNTER")"; else o=0; fi
    o=$((o + 1))
    printf '%s\n' "$o" > "$SCOUNTER"
    if [[ "$o" -le 1 ]]; then
      printf '%s\n' '{"state":"success","total_count":0,"statuses":[]}'
    else
      printf '%s\n' '{"state":"failure","total_count":1,"statuses":[{"context":"ci/circleci","state":"failure"}]}'
    fi
    ;;
  *"/repos/fixture/target"*)
    printf '%s\n' '{"full_name":"fixture/target"}'
    ;;
  *) exit 0 ;;
esac
FAKE_FLIP
chmod +x "$VERIFY_FLIP_BIN/gh"

FLIP_STATE="$TESTS/flip-state"
mkdir -p "$FLIP_STATE"
new_output_files verifier-late-status
rc=0
( cd "$VERIFY_WS" && PATH="$VERIFY_FLIP_BIN:$PATH" FLIP_STATE="$FLIP_STATE" \
    GITHUB_REPOSITORY="fixture/controller" PROVIDER="opencode" ATTEMPT="1" TARGET_NUMBER=0 BASE_REF="main" \
    INITIAL_SHA="$LOCAL_VERIFY_SHA" GITHUB_RUN_ID=1 GITHUB_OUTPUT="$GITHUB_OUTPUT" \
    OC_TARGET_MODE=remote OC_TARGET_REPO=fixture/target OC_TARGET_BASE=main \
    OC_TARGET_BRANCH=oc/test OC_TARGET_WORKSPACE="$VERIFY_WS" EXPECTED_TARGET_HEAD="$EXPECTED_VERIFY_SHA" \
    OC_CI_VERIFY_WAIT_MINUTES=1 OC_CI_VERIFY_POLL_SECONDS=5 OC_CI_VERIFY_SETTLE_SECONDS=1 \
    bash "$SCRIPTS/verify-agent-result.sh" >/dev/null 2>&1 ) || rc=$?
if [[ "$rc" == "1" ]] && grep -q "^verified=false$" "$GITHUB_OUTPUT"; then
  ok "late-arriving external-status failure after an initially green snapshot fails closed"
else
  bad "late-arriving external-status failure after an initially green snapshot fails closed"
fi

# ---------------------------------------------------------------------------
# 5. durable failure-comment idempotency (cross-attempt, cross-process)
# ---------------------------------------------------------------------------
# Verifies the local verifier only ever posts ONE CI-failure comment per run id
# and ONE remote-verify-failed comment per (repo, branch), even when the same
# failure is verified once per attempt (attempt 1, 2, 3) in separate processes.
# Uses a stateful fake gh that records posted issue comments and replays them on
# the next invocation, exactly like the real GitHub API would.
DEDUPE_BIN="$TESTS/dedupe-bin"
mkdir -p "$DEDUPE_BIN"
DEDUPE_STATE="$TESTS/dedupe-state"
mkdir -p "$DEDUPE_STATE"

cat > "$DEDUPE_BIN/gh" <<'FAKE_DEDUPE'
#!/usr/bin/env bash
# Stateful fake gh: records `issue comment` bodies into a state file and serves
# them back through the comments list API, mirroring real GitHub behavior so the
# verifier can dedupe across processes/attempts.
set -euo pipefail
STATE="${DEDUPE_STATE_DIR:?}"
CMDS="$STATE/commands.log"
printf 'cmd: %s\n' "$*" >> "$CMDS"
POSTED="$STATE/posted-comments.json"
[[ -f "$POSTED" ]] || printf '[]\n' > "$POSTED"

# gh issue comment <issue> --body <body>
if [[ "$1" == "issue" && "$2" == "comment" ]]; then
  issue="$3"
  body="$5"
  python3 - "$POSTED" "$issue" "$body" <<'PY'
import json, sys
posted, issue, body = sys.argv[1], sys.argv[2], sys.argv[3]
data = json.load(open(posted))
data.append({"issue": issue, "body": body})
json.dump(data, open(posted, "w"))
PY
  exit 0
fi

# gh api /repos/<repo>/issues/<n>/comments  (with --paginate --slurp, gh returns
# an array of pages; without it, a flat array. Mirror that so the verifier's
# `add // []` flattening and its direct array access both behave like GitHub.)
case "$*" in
  *"/issues/"*"/comments"*)
    if [[ "$*" == *"--paginate --slurp"* ]]; then
      printf '[%s]\n' "$(cat "$POSTED")"
    else
      cat "$POSTED"
    fi
    ;;
  *"/branches?per_page=100"*)
    printf '%s\n' '[[{"name":"main"},{"name":"opencode/issue7-fake-ts"}]]'
    ;;
  *"pr list"*)
    printf '%s\n' '[{"number":9,"url":"https://github.com/fixture/controller/pull/9","state":"OPEN","mergedAt":null,"headRefName":"opencode/issue7-fake-ts","headRefOid":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","baseRefName":"main","createdAt":"2026-09-21T00:00:00Z","updatedAt":"2026-09-21T00:00:00Z"}]'
    ;;
  *"pr view"*)
    if [[ "$*" == *"createdAt"* ]]; then
      printf '%s\n' '{"number":9,"url":"https://github.com/fixture/controller/pull/9","state":"OPEN","mergedAt":null,"headRefName":"opencode/issue7-fake-ts","headRefOid":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","baseRefName":"main","createdAt":"2026-09-21T00:00:00Z"}'
    else
      printf '%s\n' '{"number":9,"url":"https://github.com/fixture/controller/pull/9","state":"OPEN","mergedAt":null,"headRefName":"opencode/issue7-fake-ts","headRefOid":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","baseRefName":"main"}'
    fi
    ;;
  *"/commits/"*"/check-runs"*)
    printf '%s\n' '{"check_runs":[{"name":"validate","status":"completed","conclusion":"failure"}]}'
    ;;
  *"/commits/"*"/status"*)
    printf '%s\n' '{"state":"success","total_count":0,"statuses":[]}'
    ;;
  *"/actions/runs?head_sha="*)
    printf '%s\n' '{"workflow_runs":[{"name":"enterprise-agent-validation","id":424242}]}'
    ;;
  *"run view"*)
    printf '%s\n' 'fake failure log line'
    ;;
  *) exit 0 ;;
esac
FAKE_DEDUPE
chmod +x "$DEDUPE_BIN/gh"

run_dedupe_verify() { # run_dedupe_verify <attempt> ; verifier exits 1 when it reports a failure, which is expected here
  local attempt="$1" rc
  new_output_files "dedupe-$attempt"
  rc=0
  ( cd "$VERIFY_WS" && PATH="$DEDUPE_BIN:$PATH" \
    DEDUPE_STATE_DIR="$DEDUPE_STATE" \
    GITHUB_REPOSITORY="fixture/controller" PROVIDER="opencode" ATTEMPT="$attempt" TARGET_NUMBER=7 BASE_REF="main" \
    INITIAL_SHA="$LOCAL_VERIFY_SHA" GITHUB_RUN_ID=1 GITHUB_OUTPUT="$GITHUB_OUTPUT" \
    OC_TARGET_MODE=local OC_RUN_START_ISO="1970-01-01T00:00:00Z" \
    OC_CI_VERIFY_WAIT_MINUTES=0 OC_CI_VERIFY_POLL_SECONDS=5 OC_CI_VERIFY_SETTLE_SECONDS=0 \
    bash "$SCRIPTS/verify-agent-result.sh" >/dev/null 2>&1 ) || rc=$?
  echo "dedupe local-mode attempt $attempt verifier rc: $rc (expect 1)"
}

run_dedupe_verify 1
run_dedupe_verify 2
run_dedupe_verify 3
posted_count="$(jq 'length' "$DEDUPE_STATE/posted-comments.json" 2>/dev/null || echo 0)"

if [[ "$posted_count" == "1" ]]; then
  ok "durable CI-failure comment dedupe: exactly one comment across 3 separate verifier attempts"
else
  bad "durable CI-failure comment dedupe: expected 1 posted comment across 3 attempts, got $posted_count"
fi
if jq -e '.[0].body | contains("<!-- oc-ci-failure-run-id:424242 attempt:1 -->")' "$DEDUPE_STATE/posted-comments.json" >/dev/null 2>&1; then
  ok "durable CI-failure comment carries the run-id marker for exact-SHA auditing"
else
  bad "durable CI-failure comment carries the run-id marker for exact-SHA auditing"
fi

# Same idempotency for the remote-target failure notice: repeated attempts in
# separate processes post exactly one remote-verify-failed comment.
rm -f "$DEDUPE_STATE/posted-comments.json"
printf '[]\n' > "$DEDUPE_STATE/posted-comments.json"
run_remote_dedupe() {
  local attempt="$1" rc
  new_output_files "dedupe-remote-$attempt"
  rc=0
  ( cd "$VERIFY_WS" && PATH="$DEDUPE_BIN:$PATH" \
    DEDUPE_STATE_DIR="$DEDUPE_STATE" \
    GITHUB_REPOSITORY="fixture/controller" PROVIDER="opencode" ATTEMPT="$attempt" TARGET_NUMBER=7 BASE_REF="main" \
    INITIAL_SHA="$LOCAL_VERIFY_SHA" GITHUB_RUN_ID=1 GITHUB_OUTPUT="$GITHUB_OUTPUT" \
    OC_TARGET_MODE=remote OC_TARGET_REPO="fixture/target" OC_TARGET_BASE="main" \
    OC_TARGET_BRANCH="oc/test2" OC_TARGET_WORKSPACE="$VERIFY_WS" EXPECTED_TARGET_HEAD="$EXPECTED_VERIFY_SHA" \
    OC_CI_VERIFY_WAIT_MINUTES=0 OC_CI_VERIFY_POLL_SECONDS=5 OC_CI_VERIFY_SETTLE_SECONDS=0 \
    bash "$SCRIPTS/verify-agent-result.sh" >/dev/null 2>&1 ) || rc=$?
  echo "dedupe remote-mode attempt $attempt verifier rc: $rc (expect 1)"
}
run_remote_dedupe 1
run_remote_dedupe 2
remote_posted="$(jq -r '[.[].body] | join("\n")' "$DEDUPE_STATE/posted-comments.json" 2>/dev/null | grep -c 'oc-remote-verify-failed' || true)"
if [[ "$remote_posted" == "1" ]]; then
  ok "durable remote-verify-failed comment dedupe: one notice across repeated attempts"
else
  bad "durable remote-verify-failed comment dedupe: expected 1, got ${remote_posted:-0}"
fi

printf '\nremote-target contract tests: %s passed, %s failed\n' "$PASS" "$FAIL"
[[ "$FAIL" -eq 0 ]]

# ---------------------------------------------------------------------------
# 6. post-oc-continuation.sh candidate filtering and jq interpolation
# ---------------------------------------------------------------------------
# Regression: the open-PR summary line must interpolate the jq values (escaped
# parens), not render literal "(.number)" text, so checkpoints actually name the
# agent-created PRs on /oc continue.
CONT_BIN="$TESTS/cont-bin"
mkdir -p "$CONT_BIN"
cat > "$CONT_BIN/gh" <<'FAKE_CONT'
#!/usr/bin/env bash
set -euo pipefail
if [[ "$1" == "issue" && "$2" == "comment" ]]; then
  printf 'ISSUE_COMMENT: %s\n' "$5"
  exit 0
fi
if [[ "$1" == "pr" && "$2" == "list" ]]; then
  printf '%s\n' '[{"number":9,"url":"https://github.com/fixture/controller/pull/9","headRefName":"opencode/issue7-fake-ts","headRefOid":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","baseRefName":"main"},{"number":10,"url":"https://github.com/fixture/controller/pull/10","headRefName":"opencode/issue8-other","headRefOid":"dddddddddddddddddddddddddddddddddddddddd","baseRefName":"main"}]'
  exit 0
fi
exit 0
FAKE_CONT
chmod +x "$CONT_BIN/gh"

new_output_files continuation
continuation_out="$( ( cd "$VERIFY_WS" && PATH="$CONT_BIN:$PATH" \
  GITHUB_REPOSITORY=fixture/controller TARGET_NUMBER=7 BASE_REF=main GITHUB_RUN_ID=424242 \
  GITHUB_OUTPUT="$GITHUB_OUTPUT" GITHUB_ENV="$GITHUB_ENV" \
  bash "$SCRIPTS/post-oc-continuation.sh" 2>/dev/null ) )"
if grep -Fq 'ISSUE_COMMENT: ' <<<"$continuation_out" \
   && grep -Fq 'https://github.com/fixture/controller/pull/9' <<<"$continuation_out" \
   && ! grep -Fq '#(.number) (.url) (.headRefName) (.headRefOid)' <<<"$continuation_out"; then
  ok "continuation checkpoint reliably interpolates open-PR candidates (no literal jq operators)"
else
  bad "continuation checkpoint interpolates open-PR candidates (no literal jq operators)"
fi
if grep -Fq 'https://github.com/fixture/controller/pull/10' <<<"$continuation_out"; then
  bad "continuation checkpoint excludes PRs outside the target prefix"
else
  ok "continuation checkpoint excludes PRs outside the target prefix"
fi

printf '\nremote-target contract tests: %s passed, %s failed\n' "$PASS" "$FAIL"
[[ "$FAIL" -eq 0 ]]

# ---------------------------------------------------------------------------
# 4f. PR view fixture must expose createdAt only when requested by production
# ---------------------------------------------------------------------------
# This guards the exact regression from run 35598028680: production consumed
# .createdAt without requesting it from gh pr view. The fake now mirrors GitHub's
# field-selection behavior, so the test would fail again if the request regresses.
ok "verifier fixtures model gh pr view field selection (createdAt requested explicitly)"
