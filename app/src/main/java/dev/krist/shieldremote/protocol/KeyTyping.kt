package dev.krist.shieldremote.protocol

/**
 * Fallback typing: turns an edit (old text -> new text, cursor at end) into Android key events.
 * Works in any focused text box, even when the TV's IME bridge rejects batch edits.
 */
object KeyTyping {
    data class Stroke(val code: Int, val shift: Boolean = false)

    const val DEL = 67
    private const val KEY_0 = 7
    private const val KEY_A = 29

    private val plain = mapOf(
        ' ' to 62, '.' to 56, ',' to 55, '-' to 69, '=' to 70, '[' to 71, ']' to 72, '\\' to 73, ';' to 74,
        '\'' to 75, '/' to 76, '@' to 77, '`' to 68, '+' to 81, '*' to 17, '#' to 18, '\n' to 66, '\t' to 61,
    )
    private val shifted = mapOf(
        '!' to KEY_0 + 1, '$' to KEY_0 + 4, '%' to KEY_0 + 5, '^' to KEY_0 + 6, '&' to KEY_0 + 7, '(' to KEY_0 + 9,
        ')' to KEY_0, '_' to 69, '?' to 76, ':' to 74, '"' to 75, '<' to 55, '>' to 56, '{' to 71, '}' to 72,
        '|' to 73, '~' to 68,
    )

    fun strokeFor(c: Char): Stroke? = when (c) {
        in 'a'..'z' -> Stroke(KEY_A + (c - 'a'))
        in 'A'..'Z' -> Stroke(KEY_A + (c - 'A'), shift = true)
        in '0'..'9' -> Stroke(KEY_0 + (c - '0'))
        else -> plain[c]?.let { Stroke(it) } ?: shifted[c]?.let { Stroke(it, shift = true) }
    }

    /** Backspaces to the common prefix, then types the rest. Characters without a key are skipped. */
    fun diff(old: String, new: String): List<Stroke> {
        var p = 0
        while (p < old.length && p < new.length && old[p] == new[p]) p++
        return List(old.length - p) { Stroke(DEL) } + new.substring(p).mapNotNull { strokeFor(it) }
    }
}
