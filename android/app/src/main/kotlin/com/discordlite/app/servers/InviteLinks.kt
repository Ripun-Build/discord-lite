package com.discordlite.app.servers

/**
 * Invite links. For now they're `discordlite://invite/<code>` (opens the app straight to a Join
 * card). Once there's a domain, `https://discord-lite.app/invite/<code>` works too — both are
 * recognised already, so old links keep working.
 */
object InviteLinks {
    private val pattern = Regex("(?:discordlite://invite/|https?://(?:www\\.)?discord-lite\\.app/invite/)([A-Za-z0-9]{8})\\b")
    private val bareCode = Regex("^[A-Za-z0-9]{8}$")

    fun link(code: String) = "discordlite://invite/$code"

    /** Every invite code in a message, in order, without repeats. */
    fun codesIn(text: String): List<String> = pattern.findAll(text).map { it.groupValues[1] }.distinct().toList()

    /** A pasted link or a bare 8-character code → the code. */
    fun parse(input: String): String? {
        val trimmed = input.trim()
        return codesIn(trimmed).firstOrNull() ?: trimmed.takeIf { bareCode.matches(it) }
    }
}
