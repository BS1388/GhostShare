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
    var matcher: ParameterMatcher = ParameterMatcher.of(DefaultParameters.ALL)

    @Volatile
    var protectedMatcher: ParameterMatcher = ParameterMatcher.EMPTY

    private val REDIRECT_REGEXES: List<Regex> by lazy {
        ClearUrlsData.REDIRECTIONS.mapNotNull { pattern ->
            try { Regex(pattern) } catch (_: Throwable) { null }
        }
    }

    private val URL_REGEX = Regex(
        """(?i)(?:https?://|(?<![\w.-])www\.)[^\s<>"'{}|\\^`\[\]]+"""
    )

    private val TRAILING_PUNCTUATION = charArrayOf(
        '.', ',', ';', ':', '!', '?', '،', '؛', '؟', '»', '”', '’', '"', '\'', '>', '~'
    )

    private val YOUTUBE_PROTECTED_KEYS = setOf("v", "t", "start", "list", "index")
    private val YOUTUBE_ONLY_KEYS = setOf("si", "feature", "pp", "kw", "ab_channel", "cbrd", "themerefresh")
    private val SPOTIFY_ONLY_KEYS = setOf("si", "nd", "pi", "pt")
    private val SOUNDCLOUD_ONLY_KEYS = setOf("si", "p", "c", "ref")
    private val REDDIT_ONLY_KEYS = setOf(
        "rdt_cid", "share_id", "ref", "ref_source", "ref_campaign", "correlation_id", "rdt",
        "\$deep_link", "\$3p", "\$original_url"
    )
    private val PINTEREST_ONLY_KEYS = setOf("epik", "invite_code", "sender", "sfo", "nic")
    private val TIKTOK_ONLY_KEYS = setOf(
        "_r", "_t", "_d", "web_id", "is_from_webapp", "is_fromwebapp", "sender_device",
        "share_app_id", "share_item_id", "share_link_id", "tt_from", "checksum",
        "u_code", "preview_pb", "share_app_name", "share_iid", "user_id", "source"
    )
    private val META_KEEP_KEYS = setOf("img_index", "story_media_id", "next", "q")
    private val META_REDIRECTOR_KEYS = setOf(
        "igsh", "igsi", "igshid", "stkn", "sktn", "xmt", "slof", "shid", "obrf", "source", "src"
    )
    private val TWITTER_ONLY_KEYS = setOf("s", "t", "src", "cn")
    private val FACEBOOK_ONLY_KEYS = setOf(
        "__tn__", "comment_tracking", "dti", "ls_ref", "tracking", "rdc", "rdr", "_rdr",
        "eav", "paipv", "wtsid", "idorvanity", "referral_code", "referral_story_type"
    )
    private val LINKEDIN_ONLY_KEYS = setOf("refid", "trk", "lipi", "lici")
    private val SNAPCHAT_ONLY_KEYS = setOf("sc_referrer", "sc_ua")
    private val EBAY_ONLY_KEYS = setOf("_trkparms", "_trksid", "_from", "hash", "mkevt", "mkcid")
    private val NETFLIX_ONLY_KEYS = setOf("trackid", "tctx")
    private val BING_ONLY_KEYS = setOf("sk", "sp", "sc", "qs", "qp")
    private val ETSY_ONLY_KEYS = setOf("click_key", "click_sum", "organic_search_click")
    private val AIRBNB_ONLY_KEYS = setOf("federated_search_id", "search_type", "source_impression_id")
    private val AMAZON_ONLY_KEYS = setOf(
        "ref_", "tag", "qid", "sr", "srs", "sprefix", "crid", "linkcode", "creativeasin",
        "ascsubtag", "aaxitk", "rnid", "dchild", "camp", "creative", "content-id", "dib",
        "dib_tag", "social_share", "starsleft", "skiptwisterog", "th", "refrid", "colid",
        "coliid", "_encoding", "smid", "qualifier", "ms3_c", "spia"
    )
    private val GOOGLE_SEARCH_ONLY_KEYS = setOf(
        "ved", "ei", "gs_lcrp", "gs_lp", "gs_ssp", "oq", "esrc", "uact", "cad", "gws_rd", "sei",
        "sa", "dpr", "usg", "aqs", "sourceid", "sxsrf", "rlz", "pcampaignid", "sca_esv",
        "sca_upv", "iflsig", "fbs", "ictx", "cshid"
    )
    private val EBAY_HOST = Regex("""(?:.*\.)?ebay\.(?:[a-z]{2,3}|(?:co|com)\.[a-z]{2})""")
    private val BING_HOST = Regex("""(?:.*\.)?bing\.(?:[a-z]{2,3}|(?:co|com)\.[a-z]{2})""")
    private val AIRBNB_HOST = Regex("""(?:.*\.)?airbnb\.(?:[a-z]{2,3}|(?:co|com)\.[a-z]{2})""")
    private val GOOGLE_SEARCH_HOST = Regex("""(?:www\.)?google\.(?:[a-z]{2,3}|(?:co|com)\.[a-z]{2})""")

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
            if (host != null && isMetaContentHost(host.lowercase())) {
                modified = true
                builder.fragment(null)
            } else {
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
        for (regex in REDIRECT_REGEXES) {
            val match = regex.find(url) ?: continue
            val raw = match.groupValues.getOrNull(1) ?: continue
            val target = normalizeRedirectTarget(Uri.decode(raw)) ?: continue
            if (target != url) return target
        }
        return null
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
        if (protectedMatcher.matches(key)) return false

        val h = host?.lowercase()

        if (h != null) {
            if (isYouTube(h) && key in YOUTUBE_PROTECTED_KEYS) return false
            if (isMetaContentHost(h)) return key !in META_KEEP_KEYS
            if (isReddit(h) && key == "context") return false
        }

        if (matcher.matches(key)) return true

        return h != null && isHostSpecificTracking(key, h)
    }

    private fun isHostSpecificTracking(key: String, h: String): Boolean = when {
        isYouTube(h) -> key in YOUTUBE_ONLY_KEYS
        isSpotify(h) -> key in SPOTIFY_ONLY_KEYS
        isSoundCloud(h) -> key in SOUNDCLOUD_ONLY_KEYS
        isReddit(h) -> key in REDDIT_ONLY_KEYS
        isPinterest(h) -> key in PINTEREST_ONLY_KEYS
        isMetaRedirector(h) -> key in META_REDIRECTOR_KEYS || key.startsWith("ig")
        isTwitter(h) -> key in TWITTER_ONLY_KEYS
        isTikTok(h) -> key in TIKTOK_ONLY_KEYS
        isAmazon(h) -> key.startsWith("pd_rd_") || key.startsWith("pf_rd_") || key in AMAZON_ONLY_KEYS
        isFacebook(h) -> key in FACEBOOK_ONLY_KEYS || key.startsWith("hc_") ||
            key.startsWith("__cft__") || key.startsWith("__xts__")
        isLinkedIn(h) -> key in LINKEDIN_ONLY_KEYS
        isSnapchat(h) -> key in SNAPCHAT_ONLY_KEYS
        EBAY_HOST.matches(h) -> key in EBAY_ONLY_KEYS
        h == "netflix.com" || h.endsWith(".netflix.com") -> key in NETFLIX_ONLY_KEYS
        BING_HOST.matches(h) -> key in BING_ONLY_KEYS
        h == "imdb.com" || h.endsWith(".imdb.com") -> key == "ref_" || key.startsWith("pf_rd_")
        h == "etsy.com" || h.endsWith(".etsy.com") -> key in ETSY_ONLY_KEYS
        AIRBNB_HOST.matches(h) -> key in AIRBNB_ONLY_KEYS
        GOOGLE_SEARCH_HOST.matches(h) -> key in GOOGLE_SEARCH_ONLY_KEYS
        else -> false
    }

    private fun isYouTube(h: String) =
        h == "youtube.com" || h == "www.youtube.com" || h == "m.youtube.com" ||
            h == "music.youtube.com" || h == "youtu.be" || h.endsWith(".youtube.com")

    private fun isSpotify(h: String) =
        h == "spotify.com" || h == "open.spotify.com" || h == "spotify.link" ||
            h.endsWith(".spotify.com")

    private fun isSoundCloud(h: String) =
        h == "soundcloud.com" || h == "on.soundcloud.com" || h == "m.soundcloud.com" ||
            h.endsWith(".soundcloud.com")

    private fun isReddit(h: String) =
        h == "reddit.com" || h == "www.reddit.com" || h == "old.reddit.com" ||
            h == "redd.it" || h.endsWith(".reddit.com")

    private fun isPinterest(h: String) =
        h == "pinterest.com" || h == "pin.it" || h.contains(".pinterest.")

    private fun isTwitter(h: String) =
        h == "twitter.com" || h == "x.com" || h == "mobile.twitter.com" || h == "t.co" ||
            h.endsWith(".twitter.com") || h.endsWith(".x.com")

    private fun isTikTok(h: String) = h == "tiktok.com" || h.endsWith(".tiktok.com")

    private fun isAmazon(h: String) = h.contains("amazon.")

    private fun isFacebook(h: String) = h == "facebook.com" || h.endsWith(".facebook.com")

    private fun isLinkedIn(h: String) = h == "linkedin.com" || h.endsWith(".linkedin.com")

    private fun isSnapchat(h: String) = h == "snapchat.com" || h.endsWith(".snapchat.com")

    private fun isMetaSocial(h: String) =
        h == "instagram.com" || h == "instagr.am" || h.endsWith(".instagram.com") ||
            h == "threads.net" || h.endsWith(".threads.net") ||
            h == "threads.com" || h.endsWith(".threads.com")

    private fun isMetaRedirector(h: String) =
        h == "l.instagram.com" || h == "l.threads.net" || h == "l.threads.com"

    private fun isMetaContentHost(h: String) = isMetaSocial(h) && !isMetaRedirector(h)

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
