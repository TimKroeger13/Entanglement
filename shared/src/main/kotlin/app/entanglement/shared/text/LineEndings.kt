package app.entanglement.shared.text

/**
 * The server and the merge always work with LF. Files on Windows may use CRLF,
 * so a desktop replica remembers the style of the file and writes it back the same way.
 */
enum class LineEnding(val separator: String) {
    LF("\n"),
    CRLF("\r\n");

    fun apply(normalized: String): String =
        if (this == LF) normalized else normalized.replace("\n", "\r\n")

    companion object {
        fun detect(text: String): LineEnding = if (text.contains("\r\n")) CRLF else LF

        /** Converts CRLF and lone CR to LF. */
        fun normalize(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')
    }
}
