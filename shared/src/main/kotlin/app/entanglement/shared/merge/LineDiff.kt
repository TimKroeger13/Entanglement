package app.entanglement.shared.merge

/**
 * Line based longest-common-subsequence using Myers' O(ND) algorithm.
 *
 * Returns the matched line pairs `(indexInA, indexInB)` in increasing order.
 * Common prefix and suffix are trimmed first, so typical edits on large files stay cheap.
 */
internal object LineDiff {

    /** Upper bound for the edit trace (in ints). Beyond that the middle part is treated as fully replaced. */
    private const val MAX_TRACE_CELLS = 30_000_000L

    fun matches(a: List<String>, b: List<String>): List<Match> {
        var prefix = 0
        while (prefix < a.size && prefix < b.size && a[prefix] == b[prefix]) prefix++

        var suffix = 0
        while (suffix < a.size - prefix && suffix < b.size - prefix &&
            a[a.size - 1 - suffix] == b[b.size - 1 - suffix]
        ) suffix++

        val result = ArrayList<Match>(prefix + suffix)
        for (i in 0 until prefix) result += Match(i, i)

        val midA = a.subList(prefix, a.size - suffix)
        val midB = b.subList(prefix, b.size - suffix)
        for (m in myers(midA, midB)) result += Match(m.a + prefix, m.b + prefix)

        for (i in suffix downTo 1) result += Match(a.size - i, b.size - i)
        return result
    }

    private fun myers(a: List<String>, b: List<String>): List<Match> {
        val n = a.size
        val m = b.size
        if (n == 0 || m == 0) return emptyList()

        val max = n + m
        val offset = max + 1
        val v = IntArray(2 * max + 3)
        val trace = ArrayList<IntArray>()
        var traceCells = 0L

        for (d in 0..max) {
            trace += v.copyOf()
            traceCells += v.size
            if (traceCells > MAX_TRACE_CELLS) return emptyList()

            var k = -d
            while (k <= d) {
                var x = if (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])) {
                    v[offset + k + 1]
                } else {
                    v[offset + k - 1] + 1
                }
                var y = x - k
                while (x < n && y < m && a[x] == b[y]) {
                    x++
                    y++
                }
                v[offset + k] = x
                if (x >= n && y >= m) return backtrack(trace, offset, n, m, d)
                k += 2
            }
        }
        return emptyList()
    }

    private fun backtrack(trace: List<IntArray>, offset: Int, n: Int, m: Int, dEnd: Int): List<Match> {
        val out = ArrayList<Match>()
        var x = n
        var y = m
        for (d in dEnd downTo 0) {
            val v = trace[d]
            val k = x - y
            val prevK = if (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])) k + 1 else k - 1
            val prevX = v[offset + prevK]
            val prevY = prevX - prevK
            while (x > prevX && y > prevY && x > 0 && y > 0) {
                out += Match(x - 1, y - 1)
                x--
                y--
            }
            if (d > 0) {
                x = prevX
                y = prevY
            }
        }
        out.reverse()
        return out
    }

    data class Match(val a: Int, val b: Int)
}
