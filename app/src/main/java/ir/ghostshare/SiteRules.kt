package ir.ghostshare

data class SiteRule(
    val keep: Set<String> = emptySet(),
    val strip: Set<String> = emptySet()
) {
    val isEmpty: Boolean get() = keep.isEmpty() && strip.isEmpty()
}

class CompiledSiteRules private constructor(
    private val rules: Map<String, Pair<ParameterMatcher, ParameterMatcher>>
) {

    fun isEmpty(): Boolean = rules.isEmpty()

    fun decide(host: String, key: String): Boolean? {
        if (rules.isEmpty()) return null
        var strip = false
        var domain = host
        while (true) {
            val rule = rules[domain]
            if (rule != null) {
                if (rule.first.matches(key)) return false
                if (rule.second.matches(key)) strip = true
            }
            val dot = domain.indexOf('.')
            if (dot < 0 || domain.indexOf('.', dot + 1) < 0) break
            domain = domain.substring(dot + 1)
        }
        return if (strip) true else null
    }

    companion object {
        val EMPTY = CompiledSiteRules(emptyMap())

        fun of(source: Map<String, SiteRule>): CompiledSiteRules {
            if (source.isEmpty()) return EMPTY
            val compiled = HashMap<String, Pair<ParameterMatcher, ParameterMatcher>>()
            for ((domain, rule) in source) {
                compiled[domain] = ParameterMatcher.of(rule.keep, allowMatchAll = true) to
                    ParameterMatcher.of(rule.strip, allowMatchAll = true)
            }
            return CompiledSiteRules(compiled)
        }
    }
}
