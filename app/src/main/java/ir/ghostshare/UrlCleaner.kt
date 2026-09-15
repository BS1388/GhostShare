package ir.ghostshare

import android.net.Uri

/**
 * منطق بهینه‌سازی‌شده و ایمن پاک‌سازی پارامترهای رهگیری (Tracking Parameters) از لینک‌ها.
 *
 * اقدامات امنیتی و پایداری اعمال‌شده:
 * ۱. پیش‌فیلتر فوق‌سریع O(1)/O(N) برای عبور بدون پردازش ۹۹٪ متون فاقد لینک در system_server.
 * ۲. سقف طول ورودی و گارد عمق بازگشتی جهت جلوگیری از ReDoS، حملات StackOverflow و مصرف بی‌رویه حافظه.
 * ۳. گارد ایمنی سخت‌گیرانه برای حفظ قطعی پارامترهای محتوایی یوتیوب (v, t, start, list, index)، ردیت و غیره.
 * ۴. مدیریت دقیق کوئری‌های تعبیه‌شده در فرگمنت‌های SPA (مثل /#/path?utm_source=...).
 */
object UrlCleaner {

    /**
     * نتیجه پاک‌سازی متن به همراه لیست جفت‌های تغییر یافته (لینک خام -> لینک تمیز)
     */
    data class CleanReport(
        val cleanedText: String,
        val cleanedPairs: List<Pair<String, String>>
    )

    // حداکثر طول متن ورودی جهت جلوگیری از فریز شدن ترد بایندر در system_server (۲۵۰ کیلوبایت)
    private const val MAX_TEXT_LENGTH = 250_000

    // حداکثر طول مجاز یک URL منفرد
    private const val MAX_URL_LENGTH = 4_096

    // حداکثر عمق بازگشتی برای لینک‌های تو در تو
    private const val MAX_RECURSION_DEPTH = 3

    // رگکس تشخیص لینک: خطی O(N) و کاملاً مصون در برابر Catastrophic Backtracking (ReDoS)
    private val URL_REGEX = Regex(
        """(?i)(?:https?://|(?<![\w.-])www\.)[^\s<>"'{}|\\^`\[\]]+"""
    )

    // علائم نگارشی که معمولاً در انتهای لینک قرار می‌گیرند (متن فارسی یا انگلیسی) و جزو لینک نیستند
    private val TRAILING_PUNCTUATION = charArrayOf(
        '.', ',', ';', ':', '!', '?', '،', '؛', '؟', '»', '”', '’', '"', '\'', '>', '~'
    )

    // کلیدهای ردیابی سراسری (Global Tracking Keys) که در تمام دامنه‌ها حذف می‌شوند
    private val GLOBAL_TRACKING_KEYS = hashSetOf(
        // Instagram / Meta / Facebook / Threads
        "igsh",
        "igsi",
        "igshid",
        "stkn",
        "sktn",
        "fbclid",
        "mibextid",
        "xmt",
        "slof",

        // Google / Analytics / Ads
        "gclid",
        "gclsrc",
        "dclid",
        "gbraid",
        "wbraid",
        "gad_source",
        "srsltid",
        "_ga",
        "_gl",

        // YouTube
        "si",
        "feature",
        "pp",

        // Twitter / X
        "twclid",
        "ref_src",
        "ref_url",

        // TikTok
        "ttclid",

        // Spotify
        "nd",

        // Microsoft / Bing
        "msclkid",
        "cvid",
        "form",
        "ocid",

        // Yahoo
        "soc_src",
        "soc_trk",

        // LinkedIn
        "li_fat_id",
        "trackingid",

        // Reddit
        "rdt_cid",
        "share_id",

        // Pinterest
        "epik",
        "invite_code",

        // E-commerce & Affiliates
        "spm",
        "scm",
        "aff_fcid",
        "aff_fsk",
        "aff_platform",
        "aff_trace_key",

        // Email / CRM / Marketing
        "mc_cid",
        "mc_eid",
        "mkt_tok",
        "_hsenc",
        "_hsmi",
        "vero_id",
        "vero_conv",
        "yclid",
        "_openstat",
        "wickedid"
    )

    // پیشوندهای ردیابی که در هر دامنه‌ای باید فیلتر شوند
    private val GLOBAL_TRACKING_PREFIXES = arrayOf(
        "utm_",           // Google Analytics (utm_source, utm_medium, utm_campaign, etc.)
        "fb_",            // Facebook action / ref (fb_action_ids, fb_source, etc.)
        "hsa_",           // HubSpot Ads (hsa_cam, hsa_src, hsa_ad, etc.)
        "action_object_",
        "action_type_",
        "action_ref_"
    )

    /**
     * متن را بررسی کرده و در صورت وجود لینک دارای پارامتر رهگیری،
     * نسخه تمیزشده را برمی‌گرداند. در غیر این صورت null بازمی‌گردد.
     */
    fun clean(text: String): String? {
        return cleanWithReport(text)?.cleanedText
    }

    /**
     * کل متن را بررسی کرده و جفت‌های قبل/بعد لینک‌های تغییر یافته را به همراه متن نهایی بازمی‌گرداند.
     */
    fun cleanWithReport(text: String): CleanReport? {
        if (text.isBlank() || text.length > MAX_TEXT_LENGTH) return null

        // فیلتر سریع خطی: اگر متن اصلاً شامل نشانه‌های URL نباشد، بدون اجرای رگکس فوراً برگردد
        if (!text.contains("http://", ignoreCase = true) &&
            !text.contains("https://", ignoreCase = true) &&
            !text.contains("www.", ignoreCase = true)) {
            return null
        }

        val pairs = mutableListOf<Pair<String, String>>()

        val result = URL_REGEX.replace(text) { match ->
            val rawMatch = match.value
            if (rawMatch.length > MAX_URL_LENGTH) {
                return@replace rawMatch
            }

            val (rawUrl, trailingPunct) = extractUrlAndTrailing(rawMatch)

            val hasScheme = rawUrl.startsWith("http://", ignoreCase = true) ||
                    rawUrl.startsWith("https://", ignoreCase = true)
            val urlToClean = if (hasScheme) rawUrl else "https://$rawUrl"

            val cleaned = cleanUrlString(urlToClean, depth = 0)
            if (cleaned != null) {
                val finalUrl = if (!hasScheme) cleaned.removePrefix("https://") else cleaned
                pairs.add(rawUrl to finalUrl)
                finalUrl + trailingPunct
            } else {
                rawMatch
            }
        }

        return if (pairs.isNotEmpty()) CleanReport(result, pairs) else null
    }

    /**
     * یک URL منفرد را دریافت کرده و در صورت داشتن پارامتر ردیابی، نسخه تمیز شده را بازمی‌گرداند.
     * همچنین پارامترهای ردیابی درون Fragmentهای مسیریابی SPA (مثل /#/path?utm_source=...) را پوشش می‌دهد.
     */
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
        var urlModified = false
        val builder = uri.buildUpon()

        // ۱. بررسی و پاک‌سازی query string استاندارد
        val encodedQuery = uri.encodedQuery
        if (!encodedQuery.isNullOrEmpty()) {
            val queryCleanResult = cleanQueryString(encodedQuery, host, depth)
            if (queryCleanResult != null) {
                val (newQuery, changed) = queryCleanResult
                if (changed) {
                    urlModified = true
                    builder.clearQuery()
                    if (newQuery.isNotEmpty()) {
                        builder.encodedQuery(newQuery)
                    }
                }
            }
        }

        // ۲. بررسی و پاک‌سازی SPA hash router query (مثلاً /#/path?utm_source=...)
        val encodedFragment = uri.encodedFragment
        if (!encodedFragment.isNullOrEmpty() && encodedFragment.contains('?')) {
            val fragPath = encodedFragment.substringBefore('?')
            val fragQuery = encodedFragment.substringAfter('?')
            val fragCleanResult = cleanQueryString(fragQuery, host, depth)
            if (fragCleanResult != null) {
                val (newFragQuery, changed) = fragCleanResult
                if (changed) {
                    urlModified = true
                    val newFrag = if (newFragQuery.isNotEmpty()) {
                        if (fragPath.isNotEmpty()) "$fragPath?$newFragQuery" else newFragQuery
                    } else {
                        if (fragPath.isNotEmpty()) fragPath else null
                    }
                    if (newFrag != null) {
                        builder.encodedFragment(newFrag)
                    } else {
                        builder.fragment(null)
                    }
                }
            }
        }

        if (!urlModified) return null

        return try {
            builder.build().toString()
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * رشته query را تجزیه کرده و پارامترهای ردیابی را از آن حذف می‌کند.
     */
    private fun cleanQueryString(query: String, host: String?, depth: Int): Pair<String, Boolean>? {
        if (query.isEmpty()) return null

        val normalized = query.replace("&amp;", "&")
        val rawParams = normalized.split('&').filter { it.isNotEmpty() }
        val keptParams = mutableListOf<String>()
        var modified = false

        for (param in rawParams) {
            val eqIdx = param.indexOf('=')
            val rawKey = if (eqIdx != -1) param.substring(0, eqIdx) else param
            val decodedKey = Uri.decode(rawKey).trim().lowercase()

            if (isTrackingParam(decodedKey, host)) {
                modified = true
                continue
            }

            // بررسی احتمال وجود لینک‌های تو در تو (Nested URLs) درون مقدار پارامتر
            if (eqIdx != -1) {
                val rawValue = param.substring(eqIdx + 1)
                val decodedValue = Uri.decode(rawValue)
                if (decodedValue.startsWith("http://", ignoreCase = true) || decodedValue.startsWith("https://", ignoreCase = true)) {
                    val cleanedNested = cleanUrlString(decodedValue, depth + 1)
                    if (cleanedNested != null) {
                        val reEncodedValue = Uri.encode(cleanedNested)
                        keptParams.add("$rawKey=$reEncodedValue")
                        modified = true
                        continue
                    }
                }
            }

            keptParams.add(param)
        }

        if (!modified && keptParams.size == rawParams.size) {
            return null
        }

        return Pair(keptParams.joinToString("&"), true)
    }

    /**
     * بررسی می‌کند که آیا یک کلید query مربوط به پارامترهای رهگیری است یا خیر.
     * دارای گارد ایمنی سخت‌گیرانه برای حفظ پارامترهای حیاتی سرویس‌ها مانند v و t در یوتیوب.
     */
    private fun isTrackingParam(key: String, host: String?): Boolean {
        if (host != null) {
            val h = host.lowercase()

            // یوتیوب: پارامترهای v (شناسه ویدیو)، t و start (زمان پخش)، list (پلی‌لیست) و index هرگز نباید پاک شوند
            if (h == "youtube.com" || h == "www.youtube.com" || h == "m.youtube.com" ||
                h == "music.youtube.com" || h == "youtu.be" || h.endsWith(".youtube.com")) {
                if (key == "v" || key == "t" || key == "start" || key == "list" || key == "index") {
                    return false
                }
            }

            // ردیت: پارامتر context ساختار سلسله‌مراتب کامنت‌هاست و نباید حذف شود
            if (h == "reddit.com" || h == "www.reddit.com" || h == "old.reddit.com" ||
                h == "redd.it" || h.endsWith(".reddit.com")) {
                if (key == "context") {
                    return false
                }
            }
        }

        if (key in GLOBAL_TRACKING_KEYS) return true

        for (prefix in GLOBAL_TRACKING_PREFIXES) {
            if (key.startsWith(prefix)) return true
        }

        return isHostSpecificTracking(key, host)
    }

    /**
     * پارامترهای خاص دامنه‌ها را بررسی می‌کند.
     */
    private fun isHostSpecificTracking(key: String, host: String?): Boolean {
        if (host == null) return false
        val h = host.lowercase()

        // ۱. یوتیوب (YouTube & YouTube Music):
        // حذف شناسه‌های اشتراک‌گذاری کاربر، تگ‌های کانال و تبلیغات با حفظ کامل ویدیو و زمان
        if (h == "youtube.com" || h == "www.youtube.com" || h == "m.youtube.com" ||
            h == "music.youtube.com" || h == "youtu.be" || h.endsWith(".youtube.com")) {
            val ytTrackers = setOf(
                "si", "feature", "pp", "ab_channel", "cbrd", "themeRefresh"
            )
            return key in ytTrackers
        }

        // ۲. اسپوتیفای (Spotify):
        // حذف پارامترهای ردیابی اشتراک‌گذاری si (شناسه حساب اشتراک‌گذار)، nd، pi و pt
        if (h == "spotify.com" || h == "open.spotify.com" || h == "spotify.link" || h.endsWith(".spotify.com")) {
            val spotifyTrackers = setOf("si", "nd", "pi", "pt")
            return key in spotifyTrackers
        }

        // ۳. ساندکلاد (SoundCloud):
        // حذف پارامتر si و تگ‌های کمپین و ارجاع
        if (h == "soundcloud.com" || h == "on.soundcloud.com" || h == "m.soundcloud.com" || h.endsWith(".soundcloud.com")) {
            val soundcloudTrackers = setOf("si", "p", "c", "ref")
            return key in soundcloudTrackers
        }

        // ۴. ردیت (Reddit):
        // حذف ردیاب کلیک rdt_cid، شناسه اشتراک‌گذاری share_id و ref
        if (h == "reddit.com" || h == "www.reddit.com" || h == "old.reddit.com" ||
            h == "redd.it" || h.endsWith(".reddit.com")) {
            val redditTrackers = setOf("rdt_cid", "share_id", "ref", "ref_source")
            return key in redditTrackers
        }

        // ۵. پینترست (Pinterest):
        // حذف شناسه کلیک epik، کد دعوت، شناسه فرستنده sender و تگ‌های اشتراک‌گذاری
        if (h == "pinterest.com" || h == "pin.it" || h.contains(".pinterest.")) {
            val pinTrackers = setOf("epik", "invite_code", "sender", "sfo", "nic")
            return key in pinTrackers
        }

        // ۶. اینستاگرام و تردز (Instagram & Threads)
        if (h == "instagram.com" || h.endsWith(".instagram.com") || h == "threads.net" || h.endsWith(".threads.net")) {
            val igKeys = setOf("igsh", "igsi", "igshid", "stkn", "sktn", "xmt", "slof", "source", "src")
            if (key in igKeys || key.startsWith("ig")) return true
        }

        // ۷. توییتر / ایکس (Twitter / X):
        // پارامترهای s و t در توییتر شناسه اشتراک‌گذاری و ترکینگ هستند
        if (h == "twitter.com" || h == "x.com" || h == "mobile.twitter.com" || h == "t.co" ||
            h.endsWith(".twitter.com") || h.endsWith(".x.com")) {
            if (key == "s" || key == "t") return true
        }

        // ۸. تیک‌تاک (TikTok)
        if (h == "tiktok.com" || h.endsWith(".tiktok.com")) {
            val tiktokKeys = setOf(
                "_r", "_d", "is_from_webapp", "is_fromwebapp", "sender_device",
                "share_app_id", "share_item_id", "share_link_id", "tt_from", "checksum"
            )
            if (key in tiktokKeys) return true
        }

        // ۹. آمازون (Amazon)
        if (h.contains("amazon.")) {
            if (key.startsWith("pd_rd_") || key.startsWith("pf_rd_") || key == "ref_" || key == "tag") {
                return true
            }
        }

        return false
    }

    /**
     * علائم نگارشی اطراف و انتهای لینک را بدون خراب کردن پرانتزهای داخلی لینک (مثل ویکی‌پدیا) جدا می‌کند.
     */
    private fun extractUrlAndTrailing(raw: String): Pair<String, String> {
        var url = raw
        var trailing = ""

        while (url.isNotEmpty()) {
            val last = url.last()
            if (last in TRAILING_PUNCTUATION) {
                trailing = last + trailing
                url = url.dropLast(1)
            } else if (last == ')' && url.count { it == ')' } > url.count { it == '(' }) {
                trailing = last + trailing
                url = url.dropLast(1)
            } else if (last == ']' && url.count { it == ']' } > url.count { it == '[' }) {
                trailing = last + trailing
                url = url.dropLast(1)
            } else if (last == '}' && url.count { it == '}' } > url.count { it == '{' }) {
                trailing = last + trailing
                url = url.dropLast(1)
            } else {
                break
            }
        }

        return Pair(url, trailing)
    }
}
