package app.entanglement.shared.merge

/**
 * Line based three-way merge (diff3).
 *
 * Changes made on different lines are combined automatically. When the same lines were changed
 * differently on both sides, both versions are kept in the text as a [Superposition], so no text is ever lost.
 * All inputs must use LF line endings.
 */
object ThreeWayMerge {

    data class Result(val text: String, val superpositions: Int)

    fun merge(base: String, local: String, remote: String, localLabel: String, remoteLabel: String): Result {
        if (local == remote) return Result(local, 0)
        if (local == base) return Result(remote, 0)
        if (remote == base) return Result(local, 0)

        val o = splitLines(base)
        val a = splitLines(local)
        val b = splitLines(remote)

        val aMatch = IntArray(o.size) { -1 }
        for (m in LineDiff.matches(o, a)) aMatch[m.a] = m.b
        val bMatch = IntArray(o.size) { -1 }
        for (m in LineDiff.matches(o, b)) bMatch[m.a] = m.b

        val out = StringBuilder(maxOf(local.length, remote.length))
        var superpositions = 0
        var io = 0
        var ia = 0
        var ib = 0

        while (io < o.size || ia < a.size || ib < b.size) {
            // Stable run: base line present unchanged in both sides at the expected position.
            var k = 0
            while (io + k < o.size && aMatch[io + k] == ia + k && bMatch[io + k] == ib + k) k++
            if (k > 0) {
                for (i in io until io + k) out.append(o[i])
                io += k; ia += k; ib += k
                continue
            }

            // Unstable chunk: runs until the next base line that both sides still contain.
            var next = io
            while (next < o.size && (aMatch[next] < 0 || bMatch[next] < 0)) next++
            val aEnd = if (next < o.size) aMatch[next] else a.size
            val bEnd = if (next < o.size) bMatch[next] else b.size

            val oChunk = o.subList(io, next)
            val aChunk = a.subList(ia, aEnd)
            val bChunk = b.subList(ib, bEnd)

            when {
                aChunk == bChunk -> aChunk.forEach(out::append)
                aChunk == oChunk -> bChunk.forEach(out::append)
                bChunk == oChunk -> aChunk.forEach(out::append)
                else -> {
                    val combined = combineDisjointEdits(oChunk, aChunk, bChunk)
                    if (combined != null) {
                        combined.forEach(out::append)
                    } else {
                        appendSuperposition(out, aChunk, bChunk, localLabel, remoteLabel)
                        superpositions++
                    }
                }
            }
            io = next; ia = aEnd; ib = bEnd
        }
        return Result(out.toString(), superpositions)
    }

    /** One edit relative to the base: base lines [start, end) are replaced by [lines]. */
    private class Hunk(val start: Int, val end: Int, val lines: List<String>) {
        val isInsertion get() = start == end
    }

    private fun hunks(base: List<String>, side: List<String>): List<Hunk> {
        val result = ArrayList<Hunk>()
        var i = 0
        var j = 0
        for (m in LineDiff.matches(base, side) + LineDiff.Match(base.size, side.size)) {
            if (m.a > i || m.b > j) result += Hunk(i, m.a, side.subList(j, m.b))
            i = m.a + 1
            j = m.b + 1
        }
        return result
    }

    private fun overlap(x: Hunk, y: Hunk): Boolean = when {
        x.isInsertion && y.isInsertion -> x.start == y.start
        x.isInsertion -> x.start in y.start..y.end
        y.isInsertion -> y.start in x.start..x.end
        else -> x.start < y.end && y.start < x.end
    }

    /**
     * Both sides changed the same region, but maybe on different lines (e.g. neighbouring lines).
     * If no edit of one side touches an edit of the other, all edits are applied; otherwise null.
     */
    private fun combineDisjointEdits(base: List<String>, local: List<String>, remote: List<String>): List<String>? {
        val a = hunks(base, local)
        val b = hunks(base, remote)
        if (a.any { x -> b.any { y -> overlap(x, y) } }) return null
        val out = ArrayList<String>()
        var pos = 0
        for (h in (a + b).sortedBy { it.start }) {
            out += base.subList(pos, h.start)
            out += h.lines
            pos = h.end
        }
        out += base.subList(pos, base.size)
        return out
    }

    /** Writes both versions; lines common to both at the start or end stay outside the markers. */
    private fun appendSuperposition(
        out: StringBuilder,
        local: List<String>,
        remote: List<String>,
        localLabel: String,
        remoteLabel: String,
    ) {
        var head = 0
        while (head < local.size && head < remote.size && local[head] == remote[head]) head++
        var tail = 0
        while (tail < local.size - head && tail < remote.size - head &&
            local[local.size - 1 - tail] == remote[remote.size - 1 - tail]
        ) tail++

        for (i in 0 until head) out.append(local[i])
        ensureLineStart(out)
        out.append(Superposition.header(localLabel))
        local.subList(head, local.size - tail).forEach { out.append(it); ensureLineStart(out) }
        out.append(Superposition.divider(remoteLabel))
        remote.subList(head, remote.size - tail).forEach { out.append(it); ensureLineStart(out) }
        out.append(Superposition.END)
        for (i in local.size - tail until local.size) out.append(local[i])
    }

    private fun ensureLineStart(out: StringBuilder) {
        if (out.isNotEmpty() && out.last() != '\n') out.append('\n')
    }

    /** Splits into lines that keep their trailing `\n`; only the last line may lack it. */
    internal fun splitLines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val lines = ArrayList<String>()
        var start = 0
        while (start < text.length) {
            val nl = text.indexOf('\n', start)
            if (nl < 0) {
                lines += text.substring(start)
                break
            }
            lines += text.substring(start, nl + 1)
            start = nl + 1
        }
        return lines
    }
}
