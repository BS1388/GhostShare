package ir.ghostshare

object RedirectRules {

    const val MAX_RULES = 1000
    private const val MAX_LENGTH = 600

    fun normalize(input: String): String? {
        val value = input.trim().replace("\\/", "/")
        if (value.isEmpty() || value.length > MAX_LENGTH) return null
        if (value.any { it == '\n' || it == '\r' }) return null
        if (!value.startsWith("^http", ignoreCase = true)) return null
        val regex = try {
            Regex(value, RegexOption.IGNORE_CASE)
        } catch (_: Throwable) {
            return null
        }
        if (regex.toPattern().matcher("").groupCount() < 1) return null
        return value
    }
}

private class RedirectTimeout : RuntimeException()

private class DeadlineChars(
    private val source: CharSequence,
    private val deadline: Long
) : CharSequence {

    private var calls = 0

    override val length: Int get() = source.length

    override fun get(index: Int): Char {
        calls++
        if ((calls and 0x3FF) == 0 && System.nanoTime() > deadline) throw RedirectTimeout()
        return source[index]
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        DeadlineChars(source.subSequence(startIndex, endIndex), deadline)

    override fun toString(): String = source.toString()
}

class CompiledRedirectRules private constructor(private val regexes: List<Regex>) {

    fun isEmpty(): Boolean = regexes.isEmpty()

    fun unwrap(url: String, resolve: (String) -> String?): String? {
        if (regexes.isEmpty()) return null
        val input = DeadlineChars(url, System.nanoTime() + BUDGET_NANOS)
        try {
            for (regex in regexes) {
                val match = regex.find(input) ?: continue
                val raw = match.groupValues.getOrNull(1) ?: continue
                val target = resolve(raw) ?: continue
                if (target != url) return target
            }
        } catch (_: Throwable) {
            return null
        }
        return null
    }

    companion object {
        private const val BUDGET_NANOS = 40_000_000L

        val EMPTY = CompiledRedirectRules(emptyList())

        fun of(rules: Collection<String>): CompiledRedirectRules {
            val compiled = rules.mapNotNull { rule ->
                try {
                    Regex(rule, RegexOption.IGNORE_CASE)
                } catch (_: Throwable) {
                    null
                }
            }
            return if (compiled.isEmpty()) EMPTY else CompiledRedirectRules(compiled)
        }
    }
}
