<div align="center">

# 🐉 SnapDragon

**A self-hosted [OpenCode](https://opencode.ai) agent that lives inside your GitHub repo.**

Comment `/oc` on any issue or PR and the agent inspects, edits, tests, and reports back —
straight from GitHub Actions, with durable sessions and automatic recovery.

![OpenCode](https://img.shields.io/badge/OpenCode-1.18.32-8A2BE2?style=flat-square&logo=opencode&logoColor=white)
![GitHub Actions](https://img.shields.io/badge/GitHub_Actions-Workflow-ea4aaa?style=flat-square&logo=githubactions&logoColor=white)
![Composio MCP](https://img.shields.io/badge/Composio-MCP_bridge-1f6feb?style=flat-square&logo=composio&logoColor=white)
![Model](https://img.shields.io/badge/model-mimo--v2.6--flash--free-2ea44f?style=flat-square)

🟥 🟧 🟨 🟩 🟦 🟪

</div>

---

## ✨ Why it's nice

- 🗨️ **Zero setup per task** — just type `/oc fix the flaky test` in a comment.
- 🧠 **Durable memory** — long runs checkpoint, time out cleanly, and resume with `/oc continue`.
- 🔒 **Safe by default** — secrets are redacted from logs, sharing is disabled, no credentials in git.
- ⚡ **Fast & verified** — OpenCode is installed from a sha256-verified release and cached daily.
- 📈 **Fully observable** — live log stream, machine-readable run records, session artifacts.

---

## 🗂️ What's in the box

```text
opencode.json                     # model, agent, MCP bridge, permissions
.opencode/
├── instructions.md               # the operator contract the agent follows
├── plugins/agentic-observability.js  # live event stream + secret redaction
└── package.json                  # @opencode-ai/plugin
.github/
├── workflows/opencode.yml        # the /oc runner (issue + PR comments)
├── workflows/opencode-cache.yml  # daily verified-binary cache warmer
├── workflows/pandoc-pdf.yml      # bonus: Markdown → PDF via Pandoc
├── actions/oc-attempt/           # one full agent attempt
└── scripts/                      # session state, context, publish, recovery
docs/                             # run-record + demo docs
```

---

## 🚀 Set up your own (5 steps)

**1 · Copy the wiring**
Bring over `opencode.json`, `.opencode/`, `.github/workflows/`, `.github/actions/`, and `.github/scripts/`.

**2 · Add the secrets** → *Settings → Secrets and variables → Actions*

| Secret | Required | Purpose |
| --- | --- | --- |
| `OPENCODE_API_KEY` | ✅ | model access for the agent |
| `UNIVERSAL_TOKEN` | ⚪ | PAT with cross-repo rights; falls back to `GITHUB_TOKEN` |
| `COMPOSIO_API_KEY` | ⚪ | enables the Composio MCP tool bridge |

**3 · Add the variables** (optional tuning)

| Variable | Default | Purpose |
| --- | --- | --- |
| `OPENCODE_MODEL` | `opencode/mimo-v2.6-flash-free` | which model runs |
| `OPENCODE_VERSION` | `1.18.32` | pinned, digest-verified OpenCode release |
| `COMPOSIO_USER_ID` | repo owner | Composio workspace identity |

**4 · Allow the operator**
The workflow only answers the repository owner — keep `.github/CODEOWNERS` pointing at your account.

**5 · Fire it up**

```bash
# on any issue or PR, as a comment:
/oc audit the repo and suggest three quick wins
```

---

## 🎮 Commands

| Command | What happens |
| --- | --- |
| `/oc <request>` | Run the agent on your request (also works as `/opencode`) |
| `/oc continue` | Resume a timed-out or paused run from its checkpoint |
| reply with the answer | If the agent asks for clarification, answer then `/oc continue` |

Runs queue per issue/PR (no parallel stomping), allow up to ~5.5 h of agent time,
and post their result back to the same thread.

---

## ⚙️ Configuration

- **`opencode.json`** — model, default `build` agent, `permission: allow`, `share: disabled`, and the local `mcp-remote` bridge for Composio.
- **`.opencode/instructions.md`** — the operator contract: interpret the request yourself, inspect before modifying, verify with real evidence, never invent results.
- **`.opencode/plugins/agentic-observability.js`** — streams `tool ->`, `edited`, and error events into the Actions log and strips anything that looks like a token.
- **Version pinning** — bump `OPENCODE_VERSION` to move the whole fleet to a new release; the digest check keeps it honest.

---

## 📊 Observability & recovery

- Every run writes a schema-stable record to `docs/oc-runs/<run_id>.json` and uploads it as an artifact (never committed).
- Native OpenCode sessions are exported, uploaded (90-day artifact), and restored on the next attempt.
- Session state survives on a dedicated `oc/session-*` branch, so a re-trigger never restarts finished work.

---

## ✅ Verify a checkout

```bash
for t in .github/scripts/test-*.sh; do bash "$t"; done
```

---

## 🧰 Good to know

- `dependabot.yml` keeps GitHub Actions (and npm) dependencies fresh.
- `pandoc-pdf.yml` renders `docs/pandoc-demo/**` to PDF on push to `main`.
- Never commit `.env`, tokens, or session credentials — `.gitignore` already blocks the usual suspects.

---

<div align="center">

🟥 🟧 🟨 🟩 🟦 🟪

**Made for people who'd rather talk to their CI than their keyboard.**

</div>
