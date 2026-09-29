# Human-oriented OpenCode live stream filter.
# Input is secret-sanitized by run-opencode-attempt.sh.
# Keep operator logs concise: preserve reasoning/decisions, summarize tools,
# show commands without their stdout/stderr, and color semantic events.

function color(c,s) { return c s "\033[0m" }
function green(s) { return color("\033[92m",s) }
function dark_green(s) { return color("\033[32m",s) }
function cyan(s) { return color("\033[96m",s) }
function purple(s) { return color("\033[38;5;141m",s) }
function light_blue(s) { return color("\033[38;5;117m",s) }
function agent_teal(s) { return color("\033[38;5;121m",s) }
function blue(s) { return color("\033[94m",s) }
function orange(s) { return color("\033[38;5;208m",s) }
function bright_orange(s) { return color("\033[38;5;214m",s) }
function red(s) { return color("\033[91m",s) }

function emit(s) { print s; fflush() }
# Net brace delta outside of double-quoted strings; used to swallow a whole
# structured INFO payload instead of leaking its member lines.
function brace_delta(s,    i,ch,quoted,escaped,delta) {
  quoted=0; escaped=0; delta=0
  for (i=1; i<=length(s); i++) {
    ch=substr(s,i,1)
    if (escaped) { escaped=0; continue }
    if (ch=="\\" && quoted) { escaped=1; continue }
    if (ch=="\"") { quoted=!quoted; continue }
    if (!quoted) { if (ch=="{") delta++; else if (ch=="}") delta-- }
  }
  return delta
}
function clean_line(line) { gsub(/\033\[[0-9;]*m/, "", line); sub(/^[[:space:]]+/, "", line); sub(/[[:space:]]+$/, "", line); return line }
function status_text(line,prefix,out) { out=line; sub(prefix,"",out); sub(/^[[:space:]]+/,"",out); sub(/[[:space:]]+$/,"",out); return out }
function extract_file(line,file) { file=line; sub(/^.*file:[[:space:]]*"/,"",file); sub(/".*$/,"",file); return file }
function shell_summary(line,cmd) { cmd=line; sub(/^[[:space:]]*\$[[:space:]]+/,"",cmd); if(length(cmd)>180) cmd=substr(cmd,1,177)"..."; return cmd }
function tool_summary(line,out,fields,tool) { out=line; sub(/^.*⚙[[:space:]]*/,"",out); split(out,fields,/[[:space:]]+/); tool=fields[1]; sub(/^composio_/,"",tool); gsub(/_/," ",tool); return tool }

BEGIN {
  suppress_command_output=0
  suppress_info=0
  info_depth=0
  touching=0
  touch_file=""
  tool_event["Read"]="Reading file"
  tool_event["Edit"]="Editing file"
  tool_event["Write"]="Editing file"
  tool_event["Patch"]="Editing file"
  tool_event["Shell"]="Running command"
  tool_event["Glob"]="Searching repository"
  tool_event["Grep"]="Searching repository"
  tool_event["WebFetch"]="Consulting documentation"
  tool_event["WebSearch"]="Searching documentation"
}

{
  line=clean_line($0)

  if (line ~ /^🧠[[:space:]]*:/) { emit(purple(line)); suppress_command_output=0; next }
  if (line ~ /^💭[[:space:]]*:/) { emit(light_blue(line)); suppress_command_output=0; next }
  if (line ~ /^👾[[:space:]]*:/) { emit(agent_teal(line)); suppress_command_output=0; next }
  if (line ~ /^🤖[[:space:]]*:/) { emit(purple(line)); suppress_command_output=0; next }
  if (line ~ /^⚡[[:space:]]*Ran command/) { emit(dark_green(line)); suppress_command_output=0; next }
  if (line ~ /^✓[[:space:]]*(bash|shell|read|Read|edit|Edit|write|Write|patch|Patch|grep|Grep|glob|Glob|websearch|WebSearch|webfetch|WebFetch|search)[[:space:]]+completed/) { emit(green(line)); suppress_command_output=0; next }
  if (line ~ /^📖[[:space:]]*Read file/) { emit(cyan(line)); suppress_command_output=0; next }
  if (line ~ /^✎[[:space:]]*Edit file/) { emit(purple(line)); suppress_command_output=0; next }
  if (line ~ /^⌕[[:space:]]*Search/) { emit(blue(line)); suppress_command_output=0; next }
  if (line ~ /^◆[[:space:]]*Tool call/) { emit(cyan(line)); suppress_command_output=0; next }
  if (line ~ /^📄[[:space:]]*Final response captured/) { emit(green(line)); suppress_command_output=0; next }
  if (line ~ /^✗[[:space:]]*Error:/) { emit(red(line)); suppress_command_output=0; next }
  if (line ~ /^⚠[[:space:]]*Warning:/) { emit(bright_orange(line)); suppress_command_output=0; next }
  if (line ~ /^\[OPENCODE\][[:space:]]+(session started|session idle|tool|edited)/) { next }
  if (line ~ /^Thinking:[[:space:]]*/) { msg=line; sub(/^Thinking:[[:space:]]*/, "", msg); if(msg!="") emit(light_blue("💭 : " msg)); suppress_command_output=0; next }

  if (line ~ /OC-STATUS:[[:space:]]*/) { msg=status_text(line,"^.*OC-STATUS:[[:space:]]*"); if(msg!="") emit(light_blue("💭 : " msg)); suppress_command_output=0; next }
  if (line ~ /OC-PLAN:[[:space:]]*/) { msg=status_text(line,"^.*OC-PLAN:[[:space:]]*"); if(msg!="") emit(light_blue("💭 : " msg)); suppress_command_output=0; next }
  if (line ~ /OC-DONE:[[:space:]]*/) { msg=status_text(line,"^.*OC-DONE:[[:space:]]*"); if(msg!="") emit(green("✓ " msg)); suppress_command_output=0; next }

  if (line ~ /^\[OC\]\[PHASE/) { phase=line; sub(/^\[OC\]\[PHASE[^]]*\][[:space:]]*/,"",phase); emit(green("━━ " phase)); suppress_command_output=0; next }
  if (line ~ /^\[OC\]\[DECISION SUMMARY\]/) { summary=line; sub(/^\[OC\]\[DECISION SUMMARY\][[:space:]]*/,"",summary); if(summary!="") emit(cyan("◆ " summary)); suppress_command_output=0; next }

  if (line ~ /^\[OC\]\[heartbeat/ || line ~ /^\[OC\]\[attempt=.*\][[:space:]]heartbeat/) next
  if (line ~ /^\[OC\]\[attempt=.*\][[:space:]]+(started|finished|live stream complete)/ || line ~ /^\[OC\]\[LIVE\]/) next

  # Structured INFO payloads (process/stream/llm runtime/…) are internals the
  # operator never needs: swallow the whole brace-delimited body, not just its
  # opening line.
  if (suppress_info) {
    info_depth += brace_delta(line)
    if (info_depth <= 0) { suppress_info=0; info_depth=0 }
    next
  }
  if (line ~ /^\[[^]]+\] INFO \(#[0-9]+\): (process|stream|llm runtime selected|evaluated|tracking|loop|snapshot|telemetry)[[:space:]]*\{/) {
    info_depth = brace_delta(line)
    if (info_depth > 0) suppress_info = 1
    next
  }

  if (line ~ /(ERROR|Error|error|FAILED|Failed|failure|Failure|exception|Exception|panic|denied|DENIED|fatal)/) { emit(red("✗ " line)); suppress_command_output=0; next }
  if (line ~ /(WARNING|Warning|warning|deprecated|DEPRECATED|timeout|timed out|rate limit)/) { emit(bright_orange("⚠ " line)); suppress_command_output=0; next }
  if (line ~ /(success|Success|SUCCESS|completed|Completed|verified|Verified|isSuccess[=:][[:space:]]*true)/) { emit(green("✓ " line)); suppress_command_output=0; next }

  if (line ~ /^[[:space:]]*\$[[:space:]]+/) { emit(orange("→ " shell_summary(line))); suppress_command_output=1; next }

  if (suppress_command_output) {
    if (line ~ /^⚙[[:space:]]+/ || line ~ /^[[:space:]]*\|[[:space:]]+(Read|Edit|Write|Patch|Shell|Glob|Grep|WebFetch|WebSearch)([[:space:]]|$)/ || line ~ /^\[OPENCODE\]/ || line ~ /^Thinking:/ || line ~ /^OC-/ || line=="") {
      suppress_command_output=0
    } else next
  }

  if (line ~ /^\[[^]]+\] INFO \(#[0-9]+\): touching file[[:space:]]*\{/) {
    touching=1; touch_file=extract_file(line)
    if (line ~ /\}/) { if(touch_file!="") emit(purple("• Editing " touch_file)); else emit(purple("• Editing file")); touching=0; touch_file="" }
    next
  }
  if (touching) {
    if (line ~ /file:[[:space:]]*"/) touch_file=extract_file(line)
    if (line ~ /\}/) { if(touch_file!="") emit(purple("• Editing " touch_file)); else emit(purple("• Editing file")); touching=0; touch_file="" }
    next
  }

  if (line ~ /^⚙[[:space:]]+/) { summary=tool_summary(line); if(summary!="") emit(agent_teal("👾 : " summary)); next }

  if (line ~ /^[[:space:]]*\|[[:space:]]+(Read|Edit|Write|Patch|Shell|Glob|Grep|WebFetch|WebSearch)([[:space:]]|$)/) {
    toolline=line; sub(/^[[:space:]]*\|[[:space:]]+/,"",toolline); split(toolline,fields,/[[:space:]]+/); tool=fields[1]
    if(tool in tool_event) {
      if(tool=="Shell") emit(agent_teal("👾 : " tool_event[tool]))
      else if(tool=="Read" || tool=="Edit" || tool=="Write" || tool=="Patch") emit(agent_teal("👾 : " tool_event[tool]))
      else emit(agent_teal("👾 : " tool_event[tool]))
    }
    next
  }

  if (line ~ /^\[[^]]+\] INFO \(#[0-9]+\): (process|stream|llm runtime selected|evaluated|tracking|loop|snapshot|telemetry)[[:space:]]*\{/) next
  if (line ~ /^\[[^]]+\] (INFO|DEBUG|TRACE) \(#[0-9]+\):/) next
  if (line ~ /^[[:space:]]*(assistant|tool|function|input|output|result):[[:space:]]*$/) next

  if (line ~ /^> build · /) { emit(blue("● " line)); next }
  emit(line)
}
