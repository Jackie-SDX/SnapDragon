#!/usr/bin/env bash
set -euo pipefail
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
file="$tmp/context"
for i in $(seq 1 1000); do printf 'line-%04d\n' "$i" >> "$file"; done
out="$(bash .github/scripts/read-oc-context.sh "$file" 501 50)"
[[ "$out" == *line-0501* ]]
[[ "$out" == *line-0550* ]]
[[ "$out" != *line-0500* ]]
echo 'bounded context reader contract: OK'

# Regression: a valid remote-target command with no URL must not fail under pipefail,
# and a linked issue URL must be discoverable without breaking context capture.
gh_stub="$tmp/gh-bin"
cat > "$gh_stub" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
endpoint=""
filter=""
while (( $# )); do
  case "$1" in
    --paginate) shift ;;
    --jq) filter="$2"; shift 2 ;;
    *) endpoint="$1"; shift ;;
  esac
done
case "$endpoint" in
  /repos/example/repo/issues/117)
    data='{"number":117,"title":"Remote context regression","user":{"login":"tester"},"state":"open","created_at":"2026-09-22T00:00:00Z","body":"test"}'
    ;;
  /repos/example/repo/issues/117/comments?*)
    data='[]'
    ;;
  /repos/example/repo/pulls/117/comments?*)
    data='[]'
    ;;
  /repos/MangaD/cpp-project-template/issues/7)
    data='{"number":7,"title":"Referenced issue","user":{"login":"upstream"},"state":"closed","body":"reference"}'
    ;;
  /repos/MangaD/cpp-project-template/issues/7/comments?*)
    data='[]'
    ;;
  /repos/example/repo/actions/runs) data='{"workflow_runs":[]}' ;;
  *) data='[]' ;;
esac
if [[ -n "$filter" && "$filter" == *'@base64'* && "$data" == '[]' ]]; then exit 0; fi
printf '%s\n' "$data"
EOF
chmod +x "$gh_stub"
bin="$tmp/bin"
mkdir -p "$bin"
ln -s "$gh_stub" "$bin/gh"
request_file="$tmp/request"
printf '%s\n' '/oc --repo Jackie-SDX/cpp-project-template audit https://github.com/MangaD/cpp-project-template/issues/7' > "$request_file"
out="$tmp/collect-out"
envfile="$tmp/collect-env"
set +e
PATH="$bin:$PATH" RUNNER_TEMP="$tmp/runner" GITHUB_REPOSITORY="example/repo" TARGET_NUMBER=117 OC_REQUEST_FILE="$request_file" GITHUB_OUTPUT="$out" GITHUB_ENV="$envfile" bash .github/scripts/collect-oc-context.sh >"$tmp/collect-log" 2>&1
rc=$?
set -e
[[ "$rc" == "0" ]] || { echo "collect context regression failed rc=$rc"; cat "$tmp/collect-log"; exit 1; }
grep -Fq 'On-demand /oc context collected:' "$tmp/collect-log"
context_file="$(sed -n 's/^OC_ISSUE_CONTEXT_FILE=//p' "$out")"
refs_file="$(sed -n 's/^OC_REFERENCE_CONTEXT_FILE=//p' "$out")"
test -s "$context_file"
test -s "$refs_file"
grep -Fq 'Referenced GitHub item: https://github.com/MangaD/cpp-project-template/issues/7' "$refs_file"
grep -Fq 'Referenced issue' "$refs_file"
echo 'remote-target context capture regression: OK'
