package dev.claudiverse.core

/**
 * Where a URL starting at [start] ends: at whitespace or a character URLs do
 * not carry in prose, then without trailing sentence punctuation, and
 * without a closing bracket the URL did not open ("(see https://x.y/a)").
 */
fun linkEnd(line: String, start: Int): Int {
    var end = start
    while (end < line.length && !line[end].isWhitespace() && line[end] !in "<>\"'`") end++
    while (end > start) {
        val c = line[end - 1]
        val opens = when (c) { ')' -> '('; ']' -> '['; else -> null }
        when {
            c in ".,;:!?" -> end--
            opens != null && line.substring(start, end).count { it == opens } < line.substring(start, end).count { it == c } -> end--
            else -> break
        }
    }
    return end
}
