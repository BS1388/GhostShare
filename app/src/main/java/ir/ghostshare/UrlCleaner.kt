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

    private val URL_REGEX = Regex(
        """(?i)(?:https?://|(?<![\w.-])www\.)[^\s<>"'{}|\\^`\[\]]+"""
    )

    private val TRAILING_PUNCTUATION = charArrayOf(
        '.', ',', ';', ':', '!', '?', '،', '؛', '؟', '»', '”', '’', '"', '\'', '>', '~'
    )

    private val META_KEYS = setOf(
        "igsh", "igsi", "igshid", "stkn", "sktn",
        "fbclid", "mibextid", "rdid", "sfnsn", "xmt", "slof"
    )
    private val GOOGLE_KEYS = setOf(
        "gclid", "gclsrc", "dclid", "gbraid", "wbraid",
        "gad_source", "srsltid", "_ga", "_gl"
    )
    private val YOUTUBE_KEYS = setOf("si", "feature", "pp")
    private val TWITTER_KEYS = setOf("twclid", "ref_src", "ref_url")
    private val TIKTOK_KEYS = setOf("ttclid")
    private val SPOTIFY_KEYS = setOf("nd")
    private val MICROSOFT_KEYS = setOf("msclkid", "cvid", "form", "ocid")
    private val YAHOO_KEYS = setOf("soc_src", "soc_trk")
    private val LINKEDIN_KEYS = setOf("li_fat_id", "trackingid")
    private val REDDIT_KEYS = setOf("rdt_cid", "share_id")
    private val PINTEREST_KEYS = setOf("epik", "invite_code")
    private val AFFILIATE_KEYS = setOf(
        "spm", "scm", "aff_fcid", "aff_fsk", "aff_platform", "aff_trace_key"
    )
    private val MARKETING_KEYS = setOf(
        "mc_cid", "mc_eid", "mkt_tok", "_hsenc", "_hsmi",
        "vero_id", "vero_conv", "yclid", "_openstat", "wickedid"
    )

    private val GLOBAL_TRACKING_KEYS: Set<String> =
        META_KEYS + GOOGLE_KEYS + YOUTUBE_KEYS + TWITTER_KEYS + TIKTOK_KEYS +
            SPOTIFY_KEYS + MICROSOFT_KEYS + YAHOO_KEYS + LINKEDIN_KEYS +
            REDDIT_KEYS + PINTEREST_KEYS + AFFILIATE_KEYS + MARKETING_KEYS

    private val GLOBAL_TRACKING_PREFIXES = arrayOf(
        "utm_", "fb_", "hsa_", "action_object_", "action_type_", "action_ref_"
    )

    private val YOUTUBE_PROTECTED_KEYS = setOf("v", "t", "start", "list", "index")
    private val YOUTUBE_ONLY_KEYS = setOf("si", "feature", "pp", "ab_channel", "cbrd", "themerefresh")
    private val SPOTIFY_ONLY_KEYS = setOf("si", "nd", "pi", "pt")
    private val SOUNDCLOUD_ONLY_KEYS = setOf("si", "p", "c", "ref")
    private val REDDIT_ONLY_KEYS = setOf("rdt_cid", "share_id", "ref", "ref_source")
    private val PINTEREST_ONLY_KEYS = setOf("epik", "invite_code", "sender", "sfo", "nic")
    private val TIKTOK_ONLY_KEYS = setOf(
        "_r", "_t", "_d", "web_id", "is_from_webapp", "is_fromwebapp", "sender_device",
        "share_app_id", "share_item_id", "share_link_id", "tt_from", "checksum"
    )
    private val META_KEEP_KEYS = setOf("img_index", "story_media_id", "next", "q")
    private val META_REDIRECTOR_KEYS = setOf(
        "igsh", "igsi", "igshid", "stkn", "sktn", "xmt", "slof", "shid", "obrf", "source", "src"
    )

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
            if (isYouTube(h) && key in YOUTUBE_PROTECTED_KEYS) return false
            if (isMetaContentHost(h)) return key !in META_KEEP_KEYS
            if (isReddit(h) && key == "context") return false
        }

        if (key in GLOBAL_TRACKING_KEYS) return true
        if (GLOBAL_TRACKING_PREFIXES.any { key.startsWith(it) }) return true

        return h != null && isHostSpecificTracking(key, h)
    }

    private fun isHostSpecificTracking(key: String, h: String): Boolean = when {
        isYouTube(h) -> key in YOUTUBE_ONLY_KEYS
        isSpotify(h) -> key in SPOTIFY_ONLY_KEYS
        isSoundCloud(h) -> key in SOUNDCLOUD_ONLY_KEYS
        isReddit(h) -> key in REDDIT_ONLY_KEYS
        isPinterest(h) -> key in PINTEREST_ONLY_KEYS
        isMetaRedirector(h) -> key in META_REDIRECTOR_KEYS || key.startsWith("ig")
        isTwitter(h) -> key == "s" || key == "t"
        isTikTok(h) -> key in TIKTOK_ONLY_KEYS
        isAmazon(h) -> key.startsWith("pd_rd_") || key.startsWith("pf_rd_") || key == "ref_" || key == "tag"
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
