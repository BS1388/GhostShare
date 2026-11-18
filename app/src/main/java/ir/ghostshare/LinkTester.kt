package ir.ghostshare

import android.net.Uri

object LinkTester {

    data class Item(
        val original: String,
        val cleaned: String,
        val removed: List<String>,
        val fragmentRemoved: Boolean,
        val redirected: Boolean
    )

    sealed class Result {
        object Empty : Result()
        object NoLink : Result()
        object Excluded : Result()
        object Unchanged : Result()
        class Cleaned(val text: String, val items: List<Item>) : Result()
    }

    private val ANY_URL = Regex("""(?i)(?:https?://|www\.)[^\s<>"']+""")

    @Synchronized
    fun run(config: GhostConfig, input: String): Result {
        if (input.isBlank()) return Result.Empty
        config.applyToCleaner()
        val report = UrlCleaner.cleanWithReport(input)
        if (report != null) {
            val items = report.cleanedPairs.map { (original, cleaned) -> describe(original, cleaned) }
            return Result.Cleaned(report.cleanedText, items)
        }
        val first = ANY_URL.find(input)?.value ?: return Result.NoLink
        val host = try {
            Uri.parse(if (first.startsWith("www.", ignoreCase = true)) "https://$first" else first).host?.lowercase()
        } catch (_: Throwable) {
            null
        }
        val excluded = host != null && config.exceptionDomains.any { host == it || host.endsWith(".$it") }
        return if (excluded) Result.Excluded else Result.Unchanged
    }

    private fun describe(original: String, cleaned: String): Item {
        val before = queryParts(original)
        val remaining = HashMap<String, Int>()
        for (part in queryParts(cleaned)) remaining[part] = (remaining[part] ?: 0) + 1
        val removed = LinkedHashSet<String>()
        for (part in before) {
            val left = remaining[part] ?: 0
            if (left > 0) remaining[part] = left - 1 else removed.add(part)
        }
        val fragmentRemoved = original.contains('#') && !cleaned.contains('#')
        val redirected = hostOf(original) != hostOf(cleaned)
        return Item(original, cleaned, removed.toList(), fragmentRemoved, redirected)
    }

    private fun queryParts(url: String): List<String> {
        val withoutFragment = url.substringBefore('#')
        val query = if (withoutFragment.contains('?')) withoutFragment.substringAfter('?') else ""
        val fragment = if (url.contains('#')) url.substringAfter('#') else ""
        val raw = ArrayList<String>()
        raw.addAll(query.split('&'))
        if (fragment.contains('?')) raw.addAll(fragment.substringAfter('?').split('&'))
        return raw.filter { it.isNotEmpty() }.map { Uri.decode(it.substringBefore('=')).trim().lowercase() }
    }

    private fun hostOf(url: String): String? = try {
        Uri.parse(url).host?.lowercase()
    } catch (_: Throwable) {
        null
    }
}
