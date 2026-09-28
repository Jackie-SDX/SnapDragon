import { writeFileSync } from "node:fs"

export const AgenticObservability = async () => {
  const safe = (value) => String(value ?? "")
    .replace(/(gh[ps]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,}|sk-or-v1-[A-Za-z0-9_-]{20,}|AIza[A-Za-z0-9_-]{20,}|Bearer\s+\S+)/g, "[REDACTED]")
    .replace(/\s+/g, " ")
    .slice(0, 240)

  const toolName = (value) => {
    if (!value) return "unknown"
    if (value === "bash") return "shell"
    if (value === "edit" || value === "write") return "file-edit"
    return String(value)
  }

  const emit = (line) => console.log("[OPENCODE] " + line)
  const assistantMessageIds = new Set()
  let latestAssistantText = ""

  const persistFinalResponse = () => {
    const file = process.env.OC_FINAL_RESPONSE_FILE
    if (!file || !latestAssistantText.trim()) return
    try {
      writeFileSync(file, latestAssistantText.trimEnd() + "\n", { encoding: "utf8", mode: 0o600 })
    } catch (_) {}
  }

  return {
    event: async ({ event }) => {
      try {
        if (event.type === "session.created") {}
        else if (event.type === "message.updated") {
          const info = event.properties?.info
          if (info?.role === "assistant" && info?.id) assistantMessageIds.add(info.id)
        } else if (event.type === "message.part.updated") {
          const part = event.properties?.part
          if (part?.type === "text" && assistantMessageIds.has(part.messageID)) {
            if (typeof part.text === "string" && part.text.trim()) latestAssistantText = part.text
            else if (typeof event.properties?.delta === "string" && event.properties.delta) latestAssistantText += event.properties.delta
          }
        } else if (event.type === "session.error") emit("ERROR " + safe(event.properties?.error ?? event.error))
        else if (event.type === "file.edited") {}
        else if (event.type === "tool.execute.before") {}
        else if (event.type === "tool.execute.after") {}
        else if (event.type === "todo.updated") emit("plan updated")
        else if (event.type === "session.compacted") emit("session compacted; continuing with preserved context")
        else if (event.type === "session.idle") {
          persistFinalResponse()
        }
      } catch (_) {}
    },
  }
}
