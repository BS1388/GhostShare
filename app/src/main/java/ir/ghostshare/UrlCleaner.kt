package ir.ghostshare

import android.net.Uri

object UrlCleaner {

    data class CleanReport(
        val cleanedText: String,
        val cleanedPairs: List<Pair<String, String>>
    )

    private const val MAX_TEXT_LENGTH = 250_000
    private const val MAX_URL_LENGTH = 4_096
    private const val MAX_RECURSION_DEPTH = 3

    @Volatile
    var redirectUnwrapEnabled: Boolean = true

    @Volatile
    var exceptionDomains: Set<String> = emptySet()

    @Volatile
    var siteRules: CompiledSiteRules = CompiledSiteRules.of(DefaultSiteRules.ALL)

    @Volatile
    var matcher: ParameterMatcher = ParameterMatcher.of(DefaultParameters.ALL)

    @Volatile
    var protectedMatcher: ParameterMatcher = ParameterMatcher.EMPTY

    @Volatile
    private var redirectSource: Set<String> = DefaultRedirectRules.SET

    @Volatile
    private var redirectCompiled: CompiledRedirectRules? = null

    fun setRedirectRules(rules: Set<String>) {
        if (redirectCompiled != null && rules == redirectSource) return
        redirectCompiled = CompiledRedirectRules.of(rules)
        redirectSource = rules
    }

    private fun redirectRules(): CompiledRedirectRules =
        redirectCompiled ?: CompiledRedirectRules.of(redirectSource).also { redirectCompiled = it }

    private val URL_REGEX = Regex(
        """(?i)(?:https?://|(?<![\w.-])www\.)[^\s<>"'{}|\\^`\[\]]+"""
    )

    private val TRAILING_PUNCTUATION = charArrayOf(
        '.', ',', ';', ':', '!', '?', '،', '؛', '؟', '»', '”', '’', '"', '\'', '>', '~'
    )

    private val YOUTUBE_PROTECTED_KEYS = setOf("v", "t", "start", "list", "index")

    fun clean(text: String): String? = cleanWithReport(text)?.cleanedText

    fun cleanWithReport(text: String): CleanReport? {
        if (text.isBlank() || text.length > MAX_TEXT_LENGTH) return null

        if (!text.contains("http://", ignoreCase = true) &&
            !text.contains("https://", ignoreCase = true) &&
            !text.contains("www.", ignoreCase = true)
        ) {
            return null
        }

        val pairs = mutableListOf<Pair<String, String>>()

        val result = URL_REGEX.replace(text) { match ->
            val rawMatch = match.value
            if (rawMatch.length > MAX_URL_LENGTH) {
                return@replace rawMatch
            }

            val (rawUrl, trailing) = splitTrailing(rawMatch)

            val hasScheme = rawUrl.startsWith("http://", ignoreCase = true) ||
                rawUrl.startsWith("https://", ignoreCase = true)
            val cleaned = cleanUrlString(if (hasScheme) rawUrl else "https://$rawUrl", depth = 0)

            if (cleaned == null) {
                rawMatch
            } else {
                val finalUrl = if (hasScheme) cleaned else cleaned.removePrefix("https://")
                pairs.add(rawUrl to finalUrl)
                finalUrl + trailing
            }
        }

        return if (pairs.isNotEmpty()) CleanReport(result, pairs) else null
    }

    fun cleanUrlString(url: String, depth: Int = 0): String? {
        if (depth > MAX_RECURSION_DEPTH || url.length > MAX_URL_LENGTH) {
            return null
        }

        if (isExcepted(url)) return null

        if (redirectUnwrapEnabled) {
            val target = unwrapRedirect(url)
            if (target != null) {
                return cleanUrlString(target, depth + 1) ?: target
            }
        }

        val uri = try {
            Uri.parse(url)
        } catch (_: Throwable) {
            return null
        }

        val host = uri.host
        val builder = uri.buildUpon()
        var modified = false

        val query = uri.encodedQuery
        if (!query.isNullOrEmpty()) {
            val cleanedQuery = cleanQueryString(query, host, depth)
            if (cleanedQuery != null) {
                modified = true
                builder.clearQuery()
                if (cleanedQuery.isNotEmpty()) {
                    builder.encodedQuery(cleanedQuery)
                }
            }
        }

        val fragment = uri.encodedFragment
        if (fragment != null) {
            val cleanedFragment = cleanFragment(fragment, host, depth)
            if (cleanedFragment != null) {
                modified = true
                if (cleanedFragment.isEmpty()) {
                    builder.fragment(null)
                } else {
                    builder.encodedFragment(cleanedFragment)
                }
            }
        }

        if (!modified) return null

        return try {
            builder.build().toString()
        } catch (_: Throwable) {
            null
        }
    }

    private fun isExcepted(url: String): Boolean {
        val domains = exceptionDomains
        if (domains.isEmpty()) return false
        val host = (try { Uri.parse(url).host?.lowercase() } catch (_: Throwable) { null }) ?: return false
        return domains.any { host == it || host.endsWith(".$it") }
    }

    private fun unwrapRedirect(url: String): String? {
        if (!url.startsWith("http", ignoreCase = true)) return null
        return redirectRules().unwrap(url) { raw -> normalizeRedirectTarget(Uri.decode(raw)) }
    }

    private fun normalizeRedirectTarget(value: String): String? {
        val v = value.trim()
        if (v.isEmpty() || v.length > MAX_URL_LENGTH) return null
        if (v.startsWith("http://", ignoreCase = true) || v.startsWith("https://", ignoreCase = true)) return v
        val hostPart = v.substringBefore('/')
        return if (hostPart.contains('.') && !hostPart.contains(' ') && !hostPart.contains(':')) "https://$v" else null
    }

    private fun cleanQueryString(query: String, host: String?, depth: Int): String? {
        if (query.isEmpty()) return null

        val rawParams = query.replace("&amp;", "&").split('&').filter { it.isNotEmpty() }
        val keptParams = mutableListOf<String>()
        var modified = false

        for (param in rawParams) {
            val eqIndex = param.indexOf('=')
            val rawKey = if (eqIndex != -1) param.substring(0, eqIndex) else param
            val key = Uri.decode(rawKey).trim().lowercase()

            if (isTrackingParam(key, host)) {
                modified = true
                continue
            }

            val cleanedNested = if (eqIndex != -1) cleanNestedUrl(param.substring(eqIndex + 1), depth) else null
            if (cleanedNested != null) {
                keptParams.add("$rawKey=${Uri.encode(cleanedNested)}")
                modified = true
            } else {
                keptParams.add(param)
            }
        }

        return if (modified) keptParams.joinToString("&") else null
    }

    private fun cleanNestedUrl(rawValue: String, depth: Int): String? {
        val decoded = Uri.decode(rawValue)
        val isUrl = decoded.startsWith("http://", ignoreCase = true) ||
            decoded.startsWith("https://", ignoreCase = true)
        return if (isUrl) cleanUrlString(decoded, depth + 1) else null
    }

    private fun cleanFragment(fragment: String, host: String?, depth: Int): String? {
        if (!fragment.contains('?')) return null

        val path = fragment.substringBefore('?')
        val cleanedQuery = cleanQueryString(fragment.substringAfter('?'), host, depth) ?: return null

        return when {
            cleanedQuery.isEmpty() -> path
            path.isEmpty() -> cleanedQuery
            else -> "$path?$cleanedQuery"
        }
    }

    private fun isTrackingParam(key: String, host: String?): Boolean {
        val h = host?.lowercase()

        if (h != null) {
            val siteDecision = siteRules.decide(h, key)
            if (siteDecision != null) return siteDecision
        }

        if (protectedMatcher.matches(key)) return false

        if (h != null) {
            if (isYouTube(h) && key in YOUTUBE_PROTECTED_KEYS) return false
            if (isReddit(h) && key == "context") return false
        }

        return matcher.matches(key)
    }

    private fun isYouTube(h: String) =
        h == "youtube.com" || h == "www.youtube.com" || h == "m.youtube.com" ||
            h == "music.youtube.com" || h == "youtu.be" || h.endsWith(".youtube.com")

    private fun isReddit(h: String) =
        h == "reddit.com" || h == "www.reddit.com" || h == "old.reddit.com" ||
            h == "redd.it" || h.endsWith(".reddit.com")

    private fun splitTrailing(raw: String): Pair<String, String> {
        var url = raw
        var trailing = ""

        while (url.isNotEmpty() && isTrailingChar(url)) {
            trailing = url.last() + trailing
            url = url.dropLast(1)
        }

        return Pair(url, trailing)
    }

    private fun isTrailingChar(url: String): Boolean = when (val last = url.last()) {
        ')' -> url.count { it == ')' } > url.count { it == '(' }
        ']' -> url.count { it == ']' } > url.count { it == '[' }
        '}' -> url.count { it == '}' } > url.count { it == '{' }
        else -> last in TRAILING_PUNCTUATION
    }
}
