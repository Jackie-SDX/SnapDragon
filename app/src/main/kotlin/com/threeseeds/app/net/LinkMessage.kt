package com.threeseeds.app.net

/**
 * Wire messages for a nearby match — one message per line, fields
 * separated by '|'. Deliberately hand-rolled and tiny: the payload is
 * at most a player name, a board index, or GameStateCodec's alphabet
 * (".", "1", "2", ";"), so a parser is a handful of split() calls and
 * stays unit-testable without pulling in a serialization library.
 *
 * Free-text fields (player names) are percent-encoded so a name can
 * never smuggle a '|' or a newline into the framing.
 */
sealed class LinkMessage {

    data class Hi(val name: String, val protocolVersion: Int = PROTOCOL_VERSION) : LinkMessage()

    /** Host answer to [Hi]; [rules] is the host's frozen ruleset the guest adopts. */
    data class Welcome(val guestName: String, val hostName: String, val rules: String) : LinkMessage()

    data class Tap(val index: Int) : LinkMessage()

    data class State(val encoded: String) : LinkMessage()

    data object Rematch : LinkMessage()

    data object Bye : LinkMessage()

    data class Err(val reason: String) : LinkMessage()

    fun encode(): String = when (this) {
        is Hi -> "HI|${name.enc()}|$protocolVersion"
        is Welcome -> "WELCOME|${guestName.enc()}|${hostName.enc()}|${rules.enc()}"
        is Tap -> "TAP|$index"
        is State -> "STATE|${encoded.enc()}"
        Rematch -> "REMATCH"
        Bye -> "BYE"
        is Err -> "ERR|${reason.enc()}"
    }

    companion object {
        const val PROTOCOL_VERSION = 1

        /** Decodes one wire line; null means malformed (never throws). */
        fun decode(line: String): LinkMessage? = try {
            when {
                line == "REMATCH" -> Rematch
                line == "BYE" -> Bye
                else -> {
                    val parts = line.split('|')
                    when (parts[0]) {
                        "HI" -> if (parts.size == 3) {
                            Hi(parts[1].dec(), parts[2].toInt())
                        } else null

                        "WELCOME" -> if (parts.size == 4) {
                            Welcome(parts[1].dec(), parts[2].dec(), parts[3].dec())
                        } else null

                        "TAP" -> if (parts.size == 2) {
                            parts[1].toInt().takeIf { it in 0..8 }?.let(::Tap)
                        } else null

                        "STATE" -> if (parts.size == 2) State(parts[1].dec()) else null

                        "ERR" -> if (parts.size == 2) Err(parts[1].dec()) else null

                        else -> null
                    }
                }
            }
        } catch (malformed: Exception) {
            null
        }
    }
}

/** %XX escaping for '|', '%', and line breaks so framing can't be broken. */
private fun String.enc(): String = buildString(length) {
    for (c in this@enc) when (c) {
        '|' -> append("%7C")
        '%' -> append("%25")
        '\n' -> append("%0A")
        '\r' -> append("%0D")
        else -> append(c)
    }
}

private fun String.dec(): String {
    val out = StringBuilder(length)
    var i = 0
    while (i < length) {
        val c = this[i]
        if (c == '%' && i + 2 < length) {
            val code = substring(i + 1, i + 3).toIntOrNull(16)
            if (code != null) {
                out.append(code.toChar())
                i += 3
                continue
            }
        }
        out.append(c)
        i++
    }
    return out.toString()
}
