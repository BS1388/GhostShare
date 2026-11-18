package ir.ghostshare

class ParameterMatcher private constructor(
    private val exact: Set<String>,
    private val prefixes: List<String>,
    private val matchAll: Boolean
) {

    fun matches(key: String): Boolean {
        if (matchAll) return true
        if (key in exact) return true
        for (prefix in prefixes) {
            if (key.startsWith(prefix)) return true
        }
        return false
    }

    companion object {
        val EMPTY = ParameterMatcher(emptySet(), emptyList(), false)

        fun of(entries: Collection<String>, allowMatchAll: Boolean = false): ParameterMatcher {
            val exact = HashSet<String>()
            val prefixes = ArrayList<String>()
            var matchAll = false
            for (raw in entries) {
                val entry = raw.trim().lowercase()
                if (entry.isEmpty()) continue
                if (entry.endsWith("*")) {
                    val prefix = entry.dropLast(1)
                    if (prefix.isNotEmpty()) prefixes.add(prefix) else if (allowMatchAll) matchAll = true
                } else {
                    exact.add(entry)
                }
            }
            return ParameterMatcher(exact, prefixes, matchAll)
        }
    }
}
