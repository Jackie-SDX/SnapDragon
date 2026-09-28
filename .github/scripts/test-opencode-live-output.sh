#!/usr/bin/env bash
set -euo pipefail

# bash ignores `set -e` for a pipeline that begins with `!`, so a bare
# `! grep …` can never abort this suite. Route every negative assertion
# through this helper so an unexpected match is a hard failure.
fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
absent() { # absent <fixed-string> <path...>
  local needle="$1"; shift
  if grep -Fq -- "$needle" "$@"; then fail "expected '$needle' to be absent from: $*"; fi
}

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
FILTER="$ROOT_DIR/.github/scripts/filter-opencode-live-output.awk"
TMP="$(mktemp -d "${RUNNER_TEMP:-/tmp}/oc-live-filter-XXXXXX")"
trap 'rm -rf "$TMP"' EXIT

cat > "$TMP/input" <<'EOF'
OC-STATUS: I now have the full picture; the remaining work is isolated to the controller logging layer.
OC-PLAN: I’ll update the presentation filter, then run the controller validation suite.
|  Read {"filePath":"/home/runner/work/SnapDragon/SnapDragon/.github/workflows/opencode.yml"}
[19:01:22.137] INFO (#8936): touching file {
  file: "/home/runner/work/SnapDragon/SnapDragon/.github/workflows/opencode.yml"
}
|  Shell {"command":"git status --short"}
Useful finding: the controller contract is intact.
[19:01:54.183] INFO (#8293): process {
  "session.id": "ses_example",
  messageID: "msg_example",
}
[19:02:10.000] INFO (#8293): tracking {
  hash: "bc4f449ba226a7ba340b0104f7eb2505d1f09083",
  cwd: "/home/runner/work/example/example",
}
[19:02:11.000] INFO (#8293): loop {
  "session.id": "ses_example",
  step: 4,
}
[19:01:54.184] INFO (#8293): stream {
  providerID: "opencode",
  modelID: "example",
}
[19:01:54.187] INFO (#9519): evaluated {
  permission: "read",
  pattern: "docs/example.md",
}
[19:02:03.000] INFO (#7000): llm runtime selected {
  "llm.runtime": "ai-sdk",
  "llm.provider": "opencode",
  "llm.model": "example",
}
OC-DONE: The logging change is implemented and the validation checks are green.
🤖 : Let me explore the repository to understand what this setup contains.
⚡ Ran command
✓ bash completed
📖 Read file
✎ Edit file
⌕ Search
◆ Tool call
📄 Final response captured
✗ Error: example failure
⚠ Warning: example warning
[OPENCODE] session idle
EOF

awk -f "$FILTER" "$TMP/input" | sed $'s/\033\[[0-9;]*m//g' > "$TMP/output"

grep -Fq '▶ I now have the full picture; the remaining work is isolated to the controller logging layer.' "$TMP/output"
grep -Fq '◆ I’ll update the presentation filter, then run the controller validation suite.' "$TMP/output"
grep -Fq '• Reading file' "$TMP/output"
grep -Fq '• Editing /home/runner/work/SnapDragon/SnapDragon/.github/workflows/opencode.yml' "$TMP/output"
grep -Fq '→ Running command' "$TMP/output"
grep -Fq 'Useful finding: the controller contract is intact.' "$TMP/output"
grep -Fq '✓ The logging change is implemented and the validation checks are green.' "$TMP/output"
grep -Fq '🤖 : Let me explore the repository to understand what this setup contains.' "$TMP/output"
grep -Fq '⚡ Ran command' "$TMP/output"
grep -Fq '✓ bash completed' "$TMP/output"
grep -Fq '📖 Read file' "$TMP/output"
grep -Fq '✎ Edit file' "$TMP/output"
grep -Fq '⌕ Search' "$TMP/output"
grep -Fq '◆ Tool call' "$TMP/output"
grep -Fq '📄 Final response captured' "$TMP/output"
grep -Fq '✗ Error: example failure' "$TMP/output"
grep -Fq '⚠ Warning: example warning' "$TMP/output"

absent '|  Read ' "$TMP/output"
absent '|  Shell ' "$TMP/output"
absent 'session.id' "$TMP/output"
absent 'providerID: "opencode"' "$TMP/output"
absent 'permission: "read"' "$TMP/output"
absent 'llm.runtime' "$TMP/output"
absent 'tracking {' "$TMP/output"
absent 'GEMINI' "$TMP/output"
absent 'COPILOT' "$TMP/output"
absent '[OPENCODE] session idle' "$TMP/output"
absent 'Thinking:' "$TMP/output"
absent '"type":"tool_use"' "$TMP/output"

echo "human-oriented live OpenCode output filter: OK"
