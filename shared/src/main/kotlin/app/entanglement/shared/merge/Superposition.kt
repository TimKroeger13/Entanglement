package app.entanglement.shared.merge

/**
 * Marker lines for a superposition: the same lines were changed differently on two observers.
 * The user "measures" by deleting one version together with the markers, and the superposition collapses.
 */
object Superposition {
    const val PREFIX = "⚛⚛⚛ "
    private const val HEADER = "${PREFIX}Superposition – "
    private const val DIVIDER = "${PREFIX}─────────── "
    const val END = "${PREFIX}End\n"

    fun header(observer: String) = "$HEADER$observer\n"
    fun divider(observer: String) = "$DIVIDER$observer\n"

    /** Number of unresolved superpositions in [text]. */
    fun count(text: String): Int = text.lineSequence().count { it.startsWith(HEADER) }

    fun isPresent(text: String): Boolean = count(text) > 0
}
