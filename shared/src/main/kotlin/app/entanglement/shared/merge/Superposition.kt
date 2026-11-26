package app.entanglement.shared.merge

/**
 * Marker lines for a superposition: the same lines were changed differently on two observers.
 * The user "measures" by keeping one version (or both); the markers disappear and the superposition collapses.
 */
object Superposition {
    const val PREFIX = "⚛⚛⚛ "
    private const val HEADER = "${PREFIX}Superposition – "
    private const val DIVIDER = "${PREFIX}─────────── "
    private const val END_LINE = "${PREFIX}End"
    const val END = "$END_LINE\n"

    fun header(observer: String) = "$HEADER$observer\n"
    fun divider(observer: String) = "$DIVIDER$observer\n"

    /** Number of unresolved superpositions in [text]. */
    fun count(text: String): Int = text.lineSequence().count { it.startsWith(HEADER) }

    fun isPresent(text: String): Boolean = count(text) > 0

    /** One superposition: characters [start, end) of the text, including its three marker lines. */
    data class Block(
        val start: Int,
        val end: Int,
        val firstObserver: String,
        val first: String,
        val secondObserver: String,
        val second: String,
    )

    enum class Keep { FIRST, SECOND, BOTH }

    /** All complete superpositions in [text], in order. Blocks whose markers were partly edited away are skipped. */
    fun find(text: String): List<Block> {
        val blocks = ArrayList<Block>()
        var headerStart = -1
        var dividerStart = -1
        var firstObserver = ""
        var secondObserver = ""
        var firstStart = 0
        var secondStart = 0
        var pos = 0
        while (pos < text.length) {
            val nl = text.indexOf('\n', pos)
            val lineEnd = if (nl < 0) text.length else nl + 1
            val line = text.substring(pos, if (nl < 0) text.length else nl).removeSuffix("\r")
            when {
                line.startsWith(HEADER) -> {
                    headerStart = pos
                    dividerStart = -1
                    firstObserver = line.removePrefix(HEADER)
                    firstStart = lineEnd
                }
                line.startsWith(DIVIDER) && headerStart >= 0 && dividerStart < 0 -> {
                    dividerStart = pos
                    secondObserver = line.removePrefix(DIVIDER)
                    secondStart = lineEnd
                }
                line == END_LINE && headerStart >= 0 && dividerStart >= 0 -> {
                    blocks += Block(
                        start = headerStart,
                        end = lineEnd,
                        firstObserver = firstObserver,
                        first = text.substring(firstStart, dividerStart),
                        secondObserver = secondObserver,
                        second = text.substring(secondStart, pos),
                    )
                    headerStart = -1
                    dividerStart = -1
                }
            }
            pos = lineEnd
        }
        return blocks
    }

    /** Collapses [block] in [text], keeping the chosen version(s). */
    fun resolve(text: String, block: Block, keep: Keep): String {
        val kept = when (keep) {
            Keep.FIRST -> block.first
            Keep.SECOND -> block.second
            Keep.BOTH -> block.first + block.second
        }
        return text.substring(0, block.start) + kept + text.substring(block.end)
    }
}
