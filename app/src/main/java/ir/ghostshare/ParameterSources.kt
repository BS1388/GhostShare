package ir.ghostshare

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

enum class SourceKind { PARAMETERS, EXCEPTIONS, SITE_RULES, REDIRECTS }

enum class SourceFormat { LIST, CLEARURLS, ADGUARD }

data class ListSource(
    val id: String,
    val name: String,
    val url: String,
    val kind: SourceKind,
    val builtIn: Boolean,
    val format: SourceFormat? = null
)

object SourceCatalog {

    private const val GHOST_BASE = "https://raw.githubusercontent.com/BS1388/GhostShare/main/Parameters/"

    val parameters: List<ListSource> = listOf(
        ListSource("ghostshare", "GhostShare", GHOST_BASE + "Parameters", SourceKind.PARAMETERS, true, SourceFormat.LIST),
        ListSource(
            "adguard", "AdGuard",
            "https://raw.githubusercontent.com/AdguardTeam/FiltersRegistry/master/filters/filter_17_TrackParam/filter.txt",
            SourceKind.PARAMETERS, true, SourceFormat.ADGUARD
        ),
        ListSource(
            "clearurls", "ClearURLs",
            "https://raw.githubusercontent.com/ClearURLs/Rules/master/data.min.json",
            SourceKind.PARAMETERS, true, SourceFormat.CLEARURLS
        )
    )

    val exceptions: List<ListSource> = listOf(
        ListSource("ghostshare", "GhostShare", GHOST_BASE + "Exceptions", SourceKind.EXCEPTIONS, true, SourceFormat.LIST)
    )

    val siteRules: List<ListSource> = listOf(
        ListSource("ghostshare", "GhostShare", GHOST_BASE + "Site-Rules", SourceKind.SITE_RULES, true, SourceFormat.LIST)
    )

    val redirects: List<ListSource> = listOf(
        ListSource("ghostshare", "GhostShare", GHOST_BASE + "Redirect-Rules", SourceKind.REDIRECTS, true, SourceFormat.LIST),
        ListSource(
            "clearurls", "ClearURLs",
            "https://raw.githubusercontent.com/ClearURLs/Rules/master/data.min.json",
            SourceKind.REDIRECTS, true, SourceFormat.CLEARURLS
        )
    )

    fun builtIn(kind: SourceKind): List<ListSource> = when (kind) {
        SourceKind.PARAMETERS -> parameters
        SourceKind.EXCEPTIONS -> exceptions
        SourceKind.SITE_RULES -> siteRules
        SourceKind.REDIRECTS -> redirects
    }

    private val GITHUB_BLOB = Regex("""^https://(?:www\.)?github\.com/([^/]+)/([^/]+)/blob/([^/]+)/(.+)$""")

    fun normalizeUrl(input: String): String? {
        var value = input.trim()
        if (value.isEmpty() || value.length > 2048 || value.any { it.isWhitespace() }) return null
        if (!value.contains("://")) value = "https://$value"
        val blob = GITHUB_BLOB.matchEntire(value.substringBefore('?').substringBefore('#'))
        if (blob != null) {
            val (owner, repo, ref, path) = blob.destructured
            value = "https://raw.githubusercontent.com/$owner/$repo/$ref/$path"
        }
        val uri = try {
            URI(value)
        } catch (_: Exception) {
            return null
        }
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrEmpty()) return null
        return value
    }
}

object RegexRules {

    private const val MAX_RESULTS = 24
    private const val LEADING = "(?:%3F)?"
    private val TAIL = Regex("""^(.*?)(\(\?:_\[a-z_]\*\)\?|\(\?:_\[a-z]\*\)\+|\[a-z_]\*|\[a-z_]\+|\[a-z]\*|\[a-z]\+)$""")
    private val LITERAL = Regex("[A-Za-z0-9_-]")

    fun expand(rule: String): List<String>? {
        val body = rule.removePrefix(LEADING)
        if (body.isEmpty()) return null
        val tail = TAIL.matchEntire(body)
        if (tail != null) {
            val bases = literals(tail.groupValues[1]) ?: return null
            val result = ArrayList<String>()
            for (base in bases) {
                when (tail.groupValues[2]) {
                    "(?:_[a-z_]*)?" -> {
                        result.add(base)
                        result.add("${base}_*")
                    }
                    "(?:_[a-z]*)+" -> result.add("${base}_*")
                    else -> result.add("$base*")
                }
            }
            return result.filter { it.isNotEmpty() && it != "*" && it != "_*" }.ifEmpty { null }
        }
        return literals(body)?.filter { it.isNotEmpty() }?.ifEmpty { null }
    }

    private fun literals(pattern: String): List<String>? {
        var results = listOf("")
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            val options: List<String>
            if (c == '(') {
                if (!pattern.startsWith("(?:", i)) return null
                val end = pattern.indexOf(')', i)
                if (end < 0) return null
                val alternatives = pattern.substring(i + 3, end).split('|')
                if (alternatives.any { it.isEmpty() || !it.all { ch -> LITERAL.matches(ch.toString()) } }) return null
                i = end + 1
                options = if (i < pattern.length && pattern[i] == '?') {
                    i++
                    alternatives + ""
                } else {
                    alternatives
                }
            } else if (LITERAL.matches(c.toString())) {
                i++
                options = if (i < pattern.length && pattern[i] == '?') {
                    i++
                    listOf(c.toString(), "")
                } else {
                    listOf(c.toString())
                }
            } else {
                return null
            }
            val next = ArrayList<String>(results.size * options.size)
            for (prefix in results) for (option in options) next.add(prefix + option)
            if (next.size > MAX_RESULTS) return null
            results = next
        }
        return results
    }
}

object SourceParser {

    private val RISKY_EXACT = setOf("cmpid", "cuid", "a8", "erid", "adj_t", "mc_tc", "wt_mc")
    private val RISKY_PREFIX = listOf("elq")
    private val PROTECTED_EXACT = setOf(
        "id", "q", "v", "p", "u", "url", "page", "query", "search", "key", "token", "code", "state", "lang"
    )
    private const val MIN_WILDCARD_PREFIX = 3
    private val ADGUARD_PREFIX_RULE = Regex("""^/\^([A-Za-z0-9_.\-]+)/$""")
    private val ADGUARD_EXACT_REGEX_RULE = Regex("""^/\^([A-Za-z0-9_.\-]+)\$/$""")

    class Parsed(val items: Set<String>, val skipped: Int, val rules: Map<String, SiteRule> = emptyMap())

    fun isRisky(name: String): Boolean =
        name in RISKY_EXACT || RISKY_PREFIX.any { name.startsWith(it) }

    private fun isUnsafeRemoteEntry(name: String): Boolean {
        if (name.endsWith("*")) return name.length - 1 < MIN_WILDCARD_PREFIX
        return name in PROTECTED_EXACT
    }

    fun looksLikeHtml(text: String): Boolean {
        val head = text.trimStart('\uFEFF', ' ', '\n', '\r', '\t').take(64).lowercase()
        return head.startsWith("<!doctype") || head.startsWith("<html") || head.startsWith("<?xml")
    }

    fun detect(text: String): SourceFormat {
        val head = text.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        if (head.startsWith("{") && head.contains("\"providers\"")) return SourceFormat.CLEARURLS
        if (text.lineSequence().take(4000).any { it.contains("\$removeparam") }) return SourceFormat.ADGUARD
        return SourceFormat.LIST
    }

    fun parseParameters(text: String, format: SourceFormat): Parsed {
        val raw = when (format) {
            SourceFormat.LIST -> splitList(text)
            SourceFormat.ADGUARD -> adguardParameters(text)
            SourceFormat.CLEARURLS -> clearUrlsParameters(text)
        }
        val items = LinkedHashSet<String>()
        var skipped = raw.second
        for (entry in raw.first) {
            val name = GhostConfig.normalizeParameter(entry)
            if (name == null || name == "*" || isRisky(name.removeSuffix("*")) || isUnsafeRemoteEntry(name)) skipped++ else items.add(name)
        }
        return Parsed(items, skipped)
    }

    fun parseDomains(text: String): Parsed {
        val items = LinkedHashSet<String>()
        var skipped = 0
        if (text.trimStart('\uFEFF', ' ', '\n', '\r', '\t').startsWith("{")) {
            val result = ConfigJson.parse(text)
            if (result !is ConfigJson.ParseResult.Success) throw FetchException(FetchException.Reason.UNRECOGNIZED)
            val domains = result.data.exceptionDomains ?: throw FetchException(FetchException.Reason.UNRECOGNIZED)
            return Parsed(domains, result.data.skipped)
        }
        for (entry in splitList(text).first) {
            val domain = GhostConfig.normalizeDomain(entry)
            if (domain == null) skipped++ else items.add(domain)
        }
        return Parsed(items, skipped)
    }

    private val BLOCKED_REDIRECT_HOSTS = listOf("site.com", "site2.com", "site3.com", "clearurls.xyz", "kevinroebert.gitlab.io")

    fun detectRedirectFormat(text: String): SourceFormat {
        val head = text.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        return if (head.startsWith("{") && head.contains("\"providers\"")) SourceFormat.CLEARURLS else SourceFormat.LIST
    }

    fun parseRedirects(text: String, format: SourceFormat): Parsed {
        val raw: List<String> = if (format == SourceFormat.CLEARURLS) {
            val providers = JSONObject(text).getJSONObject("providers")
            val list = ArrayList<String>()
            for (name in providers.keys()) {
                val array = providers.optJSONObject(name)?.optJSONArray("redirections") ?: continue
                for (i in 0 until array.length()) list.add(array.optString(i))
            }
            list
        } else {
            text.removePrefix("\uFEFF").lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("!") }.toList()
        }
        val items = LinkedHashSet<String>()
        var skipped = 0
        for (entry in raw) {
            val rule = RedirectRules.normalize(entry)
            if (rule == null || BLOCKED_REDIRECT_HOSTS.any { rule.contains(it) }) skipped++ else items.add(rule)
            if (items.size >= RedirectRules.MAX_RULES) break
        }
        return Parsed(items, skipped)
    }

    fun parseSiteRules(text: String): Parsed {
        val result = ConfigJson.parse(text)
        if (result !is ConfigJson.ParseResult.Success) throw FetchException(FetchException.Reason.UNRECOGNIZED)
        val rules = result.data.siteRules ?: throw FetchException(FetchException.Reason.UNRECOGNIZED)
        return Parsed(rules.keys, result.data.skipped, rules)
    }

    private fun splitList(text: String): Pair<List<String>, Int> {
        val items = ArrayList<String>()
        var skipped = 0
        for (line in text.removePrefix("\uFEFF").lineSequence()) {
            for (part in line.split(',')) {
                val token = part.trim()
                if (token.isEmpty()) continue
                if (token.any { it.isWhitespace() }) skipped++ else items.add(token)
            }
        }
        return items to skipped
    }

    private fun adguardParameters(text: String): Pair<List<String>, Int> {
        val items = ArrayList<String>()
        var skipped = 0
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (!line.startsWith("\$removeparam=")) continue
            var value = line.removePrefix("\$removeparam=")
            if (!value.startsWith("/")) {
                val comma = value.indexOf(',')
                if (comma >= 0) {
                    if (value.substring(comma + 1) != "important") {
                        skipped++
                        continue
                    }
                    value = value.substring(0, comma)
                }
            }
            val prefix = ADGUARD_PREFIX_RULE.matchEntire(value)
            val exact = ADGUARD_EXACT_REGEX_RULE.matchEntire(value)
            when {
                prefix != null -> items.add(prefix.groupValues[1] + "*")
                exact != null -> items.add(exact.groupValues[1])
                value.startsWith("/") || value.startsWith("~") -> skipped++
                else -> items.add(value)
            }
        }
        return items to skipped
    }

    private fun clearUrlsParameters(text: String): Pair<List<String>, Int> {
        val rules = JSONObject(text)
            .getJSONObject("providers")
            .getJSONObject("globalRules")
            .optJSONArray("rules") ?: JSONArray()
        val items = ArrayList<String>()
        var skipped = 0
        for (i in 0 until rules.length()) {
            val expanded = RegexRules.expand(rules.optString(i))
            if (expanded == null) skipped++ else items.addAll(expanded)
        }
        return items to skipped
    }
}

class FetchException(val reason: Reason, val httpCode: Int = 0) : Exception(reason.name) {
    enum class Reason { NETWORK, HTTP, TOO_LARGE, UNRECOGNIZED }
}

object ListFetcher {

    private const val MAX_BYTES = 8 * 1024 * 1024
    private const val CONNECT_TIMEOUT_MS = 15000
    private const val READ_TIMEOUT_MS = 20000

    fun fetch(url: String): String {
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (_: IOException) {
            throw FetchException(FetchException.Reason.NETWORK)
        }
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "GhostShare")
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw FetchException(FetchException.Reason.HTTP, code)
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            connection.inputStream.use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (output.size() + read > MAX_BYTES) throw FetchException(FetchException.Reason.TOO_LARGE)
                    output.write(buffer, 0, read)
                }
            }
            return output.toString("UTF-8")
        } catch (e: FetchException) {
            throw e
        } catch (_: IOException) {
            throw FetchException(FetchException.Reason.NETWORK)
        } finally {
            connection.disconnect()
        }
    }
}

class UpdateResult(val found: Int, val added: Int)

object SourceUpdater {

    fun parse(source: ListSource, kind: SourceKind, text: String): SourceParser.Parsed {
        if (SourceParser.looksLikeHtml(text)) throw FetchException(FetchException.Reason.UNRECOGNIZED)
        val parsed = try {
            when (kind) {
                SourceKind.PARAMETERS -> SourceParser.parseParameters(text, source.format ?: SourceParser.detect(text))
                SourceKind.EXCEPTIONS -> SourceParser.parseDomains(text)
                SourceKind.SITE_RULES -> SourceParser.parseSiteRules(text)
                SourceKind.REDIRECTS -> SourceParser.parseRedirects(text, source.format ?: SourceParser.detectRedirectFormat(text))
            }
        } catch (_: Exception) {
            throw FetchException(FetchException.Reason.UNRECOGNIZED)
        }
        if (parsed.items.isEmpty() && parsed.skipped > 0) throw FetchException(FetchException.Reason.UNRECOGNIZED)
        return parsed
    }

    private fun applySiteRules(config: GhostConfig, parsed: SourceParser.Parsed): Pair<GhostConfig, UpdateResult> {
        val merged = LinkedHashMap(config.siteRules)
        var changed = 0
        for ((domain, rule) in parsed.rules) {
            val existing = merged[domain]
            val next = if (existing == null) rule else SiteRule(existing.keep + rule.keep, existing.strip + rule.strip)
            if (next != existing) {
                merged[domain] = next
                changed++
            }
        }
        val found = parsed.rules.size
        if (changed == 0) return config to UpdateResult(found, 0)
        return config.copy(siteRules = merged) to UpdateResult(found, changed)
    }

    fun apply(kind: SourceKind, config: GhostConfig, parsed: SourceParser.Parsed): Pair<GhostConfig, UpdateResult> {
        if (kind == SourceKind.SITE_RULES) return applySiteRules(config, parsed)
        if (kind == SourceKind.REDIRECTS) {
            val fresh = parsed.items - config.redirectRules
            if (fresh.isEmpty()) return config to UpdateResult(parsed.items.size, 0)
            return config.copy(redirectRules = config.redirectRules + fresh) to UpdateResult(parsed.items.size, fresh.size)
        }
        val fresh = LinkedHashSet<String>()
        if (kind == SourceKind.PARAMETERS) {
            val effective = config.effectiveParameters()
            val matcher = ParameterMatcher.of(effective)
            for (name in parsed.items) {
                if (name in config.removedParameters) continue
                if (name in effective) continue
                if (!name.endsWith("*") && matcher.matches(name)) continue
                fresh.add(name)
            }
        } else {
            fresh.addAll(parsed.items - config.exceptionDomains)
        }
        if (fresh.isEmpty()) return config to UpdateResult(parsed.items.size, 0)
        val next = if (kind == SourceKind.PARAMETERS) {
            config.copy(addedParameters = config.addedParameters + fresh)
        } else {
            config.copy(exceptionDomains = config.exceptionDomains + fresh)
        }
        return next to UpdateResult(parsed.items.size, fresh.size)
    }
}

object SourceStore {

    private const val KEY_CUSTOM = "custom_sources"

    private fun prefs(context: Context) =
        context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

    private fun orderKey(kind: SourceKind) = "source_order_${kind.name.lowercase()}"

    private fun updatedKey(kind: SourceKind, id: String) = "source_updated_${kind.name.lowercase()}_$id"

    private fun readCustom(context: Context): List<ListSource> {
        val raw = prefs(context).getString(KEY_CUSTOM, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            val result = ArrayList<ListSource>()
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val kind = try {
                    SourceKind.valueOf(item.optString("kind"))
                } catch (_: IllegalArgumentException) {
                    continue
                }
                val id = item.optString("id")
                val url = item.optString("url")
                if (id.isEmpty() || url.isEmpty()) continue
                result.add(ListSource(id, item.optString("name").ifEmpty { url }, url, kind, false))
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeCustom(context: Context, items: List<ListSource>) {
        val array = JSONArray()
        for (item in items) {
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("name", item.name)
                    .put("url", item.url)
                    .put("kind", item.kind.name)
            )
        }
        prefs(context).edit().putString(KEY_CUSTOM, array.toString()).apply()
    }

    fun sources(context: Context, kind: SourceKind): List<ListSource> {
        val all = SourceCatalog.builtIn(kind) + readCustom(context).filter { it.kind == kind }
        val saved = prefs(context).getString(orderKey(kind), "").orEmpty()
            .split(',').filter { it.isNotEmpty() }
        val byId = all.associateBy { it.id }
        val ordered = ArrayList<ListSource>()
        for (id in saved) {
            val source = byId[id]
            if (source != null && ordered.none { it.id == id }) ordered.add(source)
        }
        for (source in all) {
            if (ordered.none { it.id == source.id }) ordered.add(source)
        }
        return ordered
    }

    fun saveOrder(context: Context, kind: SourceKind, ids: List<String>) {
        prefs(context).edit().putString(orderKey(kind), ids.joinToString(",")).apply()
    }

    fun move(context: Context, kind: SourceKind, id: String, offset: Int) {
        val ids = sources(context, kind).map { it.id }.toMutableList()
        val from = ids.indexOf(id)
        val to = from + offset
        if (from < 0 || to < 0 || to >= ids.size) return
        ids.add(to, ids.removeAt(from))
        saveOrder(context, kind, ids)
    }

    fun addCustom(context: Context, kind: SourceKind, name: String, url: String): ListSource? {
        val normalized = SourceCatalog.normalizeUrl(url) ?: return null
        val id = "c" + Integer.toHexString(normalized.lowercase().hashCode())
        val existing = sources(context, kind)
        if (existing.any { it.id == id || it.url.equals(normalized, ignoreCase = true) }) return null
        val label = name.trim().take(40).ifEmpty { URI(normalized).host.removePrefix("www.") }
        val source = ListSource(id, label, normalized, kind, false)
        writeCustom(context, readCustom(context) + source)
        saveOrder(context, kind, existing.map { it.id } + id)
        return source
    }

    fun removeCustom(context: Context, kind: SourceKind, id: String) {
        writeCustom(context, readCustom(context).filterNot { it.kind == kind && it.id == id })
        saveOrder(context, kind, sources(context, kind).map { it.id }.filter { it != id })
        prefs(context).edit().remove(updatedKey(kind, id)).apply()
    }

    fun lastUpdated(context: Context, kind: SourceKind, id: String): Long =
        prefs(context).getLong(updatedKey(kind, id), 0L)

    fun markUpdated(context: Context, kind: SourceKind, id: String) {
        prefs(context).edit().putLong(updatedKey(kind, id), System.currentTimeMillis()).apply()
    }
}
