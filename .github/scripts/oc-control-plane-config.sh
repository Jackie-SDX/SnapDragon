#!/usr/bin/env bash
# Canonical defaults for the OpenCode control plane, sourced by the runner
# scripts so the workflow env block and the scripts cannot drift apart.
#
# The workflow explicitly overrides these per job; the scripts fall back to
# the values here, and enterprise-agent-validation.yml asserts that the
# workflow's env block matches this file. If a knob is ever tuned, change it
# here first, then bring the workflow env into parity.

OC_CONTROL_PLANE_AGENT_TIMEOUT_MINUTES="330"
OC_CONTROL_PLANE_JOB_BUDGET_SECONDS="21600"
OC_CONTROL_PLANE_JOB_SAFETY_MARGIN_SECONDS="1800"
OC_CONTROL_PLANE_PROGRESS_INTERVAL_SECONDS="30"
OC_CONTROL_PLANE_CI_VERIFY_SETTLE_SECONDS="30"
OC_CONTROL_PLANE_COPILOT_MAX_AI_CREDITS="60"
OC_CONTROL_PLANE_COPILOT_PEER_MAX_AI_CREDITS="30"
OC_CONTROL_PLANE_OPENCODE_DEFAULT_VERSION="1.18.31"
OC_CONTROL_PLANE_OPENROUTER_FALLBACK_MODEL="openrouter/openrouter/free"
