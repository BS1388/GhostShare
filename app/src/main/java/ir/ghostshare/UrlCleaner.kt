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
    var builtinExceptionsEnabled: Boolean = true

    @Volatile
    var userExceptionDomains: Set<String> = emptySet()

    private val REDIRECT_REGEXES: List<Regex> by lazy {
        ClearUrlsData.REDIRECTIONS.mapNotNull { pattern ->
            try { Regex(pattern) } catch (_: Throwable) { null }
        }
    }

    private val EXCEPTION_REGEXES: List<Regex> by lazy {
        ClearUrlsData.EXCEPTIONS.mapNotNull { pattern ->
            try { Regex(pattern) } catch (_: Throwable) { null }
        }
    }

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
        "gad_source", "srsltid", "_ga", "_gl", "gtm_debug", "usqp"
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
        "spm", "scm", "aff_fcid", "aff_fsk", "aff_platform", "aff_trace_key",
        "irclickid", "cjevent", "cjdata", "tduid", "shareasale", "gci"
    )
    private val MARKETING_KEYS = setOf(
        "mc_cid", "mc_eid", "mkt_tok", "_hsenc", "_hsmi",
        "vero_id", "vero_conv", "yclid", "_openstat", "wickedid"
    )
    private val ADS_KEYS = setOf(
        "sccid", "qclid", "tblci", "dicbo", "ymclid", "ysclid", "awclid", "ad_id",
        "oicd", "vmcid", "afid", "guce_referrer", "guce_referrer_sig", "auctid",
        "unicorn_click_id", "_z1_agid", "_z1_caid", "_z1_msid", "_z1_pub"
    )
    private val EMAIL_KEYS = setOf(
        "__hsfp", "__hssc", "__hstc", "hsctatracking", "_kx", "_ke", "_bhlid", "bbeml",
        "ml_subscriber", "ml_subscriber_hash", "ck_subscriber_id", "ct_params",
        "bsft_clkid", "bsft_uid", "omnisendcontactid", "sc_customer", "sc_eh", "sc_uid",
        "ss_email_id", "__s", "vgo_ee", "et_rid", "at_recipient_id", "at_recipient_list",
        "h_sid", "h_slt"
    )
    private val ANALYTICS_KEYS = setOf(
        "adobe_mc_ref", "adobe_mc_sdid", "ef_id", "s_cid", "s_kwcid",
        "_branch_match_id", "_branch_referrer", "mtm_cid", "pk_cid", "wt.mc_id",
        "oft_c", "oft_campaign", "oft_ck", "oft_d", "oft_id", "oft_ids", "oft_k",
        "oft_lk", "oft_sk", "oly_anon_id", "oly_enc_id", "otc", "icid", "rb_clickid",
        "subafid", "cn_reloaded", "ao_noptimize"
    )
    private val GENERIC_KEYS = setOf("cid", "clickid", "eid", "campaign")
    private val MYTRACKER_KEYS = setOf(
        "mt_click_id", "mt_network", "mt_campaign", "mt_adset", "mt_creative", "mt_medium",
        "mt_sub1", "mt_sub2", "mt_sub3", "mt_sub4", "mt_sub5", "mt_link_id"
    )
    private val ADJUST_KEYS = setOf(
        "adj_label", "adj_campaign", "adj_creative", "gps_adid", "adjust_creative",
        "adjust_tracker_limit", "adjust_tracker", "adjust_adgroup", "adjust_campaign",
        "adjust_referrer"
    )
    private val APPSFLYER_KEYS = setOf(
        "af_xp", "af_ad", "af_adset", "af_click_lookback", "af_force_deeplink",
        "is_retargeting"
    )
    private val AT_INTERNET_KEYS = setOf(
        "at_campaign", "at_campaign_type", "at_creation", "at_emailtype", "at_link",
        "at_link_id", "at_link_origin", "at_link_type", "at_medium", "at_ptr_name",
        "at_send_date"
    )
    private val CAMPAIGN_KEYS = setOf(
        "tw_medium", "tw_profile_id", "tw_source", "_sgm_campaign", "_sgm_term",
        "_sgm_pinned", "ebisother1", "ebisother2", "ebisother3", "ebisother4", "ebisother5",
        "vsm_type", "vsm_cid", "vsm_pid", "sms_click", "sms_source", "sms_uph", "cm_me",
        "cm_cr", "ir_campaignid", "ir_adid", "ir_partnerid", "pk_vid", "_sgm_source",
        "_sgm_action", "int_content", "int_term", "int_source", "int_medium",
        "int_campaign", "itm_source", "itm_medium", "itm_campaign", "itm_content",
        "itm_term", "dpg_source", "dpg_campaign", "dpg_medium", "dpg_content", "bsft_eid",
        "bsft_mid", "bsft_aaid", "bsft_ek", "pk_campaign", "pk_medium", "pk_source",
        "ebisadid"
    )
    private val MORE_TRACKING_KEYS = setOf(
        "pjclid", "x-clickref", "x-source", "x-a-medium", "ranmid", "raneaid", "ransiteid",
        "eml-name", "eml-mediaplan", "eml-publisher", "eurl", "link_source", "taid",
        "tgclid", "analytics_context", "analytics_trace_id", "bance_xuid", "winflncrtag",
        "ftag", "janet", "vs_campaign_id", "adsterra_clid", "adsterra_placement_id",
        "loclid", "ldtag_cl", "lt_r", "srclt", "_ly_c", "_ly_r", "yj_r", "line_uid",
        "adc_publisher", "adc_token", "uzcid", "beyond_uzcvid", "beyond_uzmcvid",
        "bemobdata", "_bdadid", "recommended_by", "recommended_code",
        "personaclick_search_query", "personaclick_input_query", "ems_dl", "emcs_t",
        "cstrackid", "btag", "jmtyclid", "tcsack", "_ope", "spot_im_redirect_source",
        "iclid", "user_email_address", "sscid", "rtkcid", "__io_lv", "_io_session_id",
        "asgtbndr", "ymid", "mindbox-click-id", "famad_xuid", "cx_click", "cx_recsorder",
        "cx_recswidget", "mindbox-message-key", "awc", "clckid", "ym_tracking_id",
        "aiad_clid", "maf", "_clde", "_cldee", "oprtrack", "xtor", "gad_campaignid",
        "admitad_uid", "external_click_id", "irgwc", "fbadid", "nb_placement",
        "nb_expid_meta", "adfrom", "nx_source", "_zucks_suid", "guccounter", "vc_lpp",
        "gs_l", "hmb_campaign", "hmb_medium", "hmb_source", "os_ehash",
        "__twitter_impression", "wt_zmc", "wtrid", "echobox", "tracking_source", "ceneo_spo"
    )

    private val GLOBAL_TRACKING_KEYS: Set<String> =
        META_KEYS + GOOGLE_KEYS + YOUTUBE_KEYS + TWITTER_KEYS + TIKTOK_KEYS +
            SPOTIFY_KEYS + MICROSOFT_KEYS + YAHOO_KEYS + LINKEDIN_KEYS +
            REDDIT_KEYS + PINTEREST_KEYS + AFFILIATE_KEYS + MARKETING_KEYS +
            ADS_KEYS + EMAIL_KEYS + ANALYTICS_KEYS + GENERIC_KEYS +
            MYTRACKER_KEYS + ADJUST_KEYS + APPSFLYER_KEYS + AT_INTERNET_KEYS + CAMPAIGN_KEYS + MORE_TRACKING_KEYS

    private val GLOBAL_TRACKING_PREFIXES = arrayOf(
        "utm_", "fb_", "hsa_", "action_object_", "action_type_", "action_ref_",
        "mtm_", "ga_", "otm_", "vn_", "at_custom", "cm_mmc"
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
        val domains = userExceptionDomains
        if (domains.isNotEmpty()) {
            val host = try { Uri.parse(url).host?.lowercase() } catch (_: Throwable) { null }
            if (host != null && domains.any { host == it || host.endsWith(".$it") }) return true
        }
        if (!builtinExceptionsEnabled) return false
        return EXCEPTION_REGEXES.any { it.containsMatchIn(url) }
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
