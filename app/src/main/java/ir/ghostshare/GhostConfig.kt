package ir.ghostshare

import android.content.SharedPreferences
import java.net.URLDecoder
import java.net.URLEncoder

data class GhostConfig(
    val redirectUnwrap: Boolean = true,
    val perAppEnabled: Boolean = false,
    val perAppPackages: Set<String> = emptySet(),
    val bypassEnabled: Boolean = false,
    val bypassPackages: Set<String> = emptySet(),
    val addedParameters: Set<String> = emptySet(),
    val removedParameters: Set<String> = emptySet(),
    val exceptionDomains: Set<String> = emptySet(),
    val siteRules: Map<String, SiteRule> = DefaultSiteRules.ALL,
    val redirectRules: Set<String> = DefaultRedirectRules.SET
) {

    fun effectiveParameters(): Set<String> {
        val result = LinkedHashSet<String>(DefaultParameters.ALL)
        result.removeAll(removedParameters)
        result.addAll(addedParameters)
        return result
    }

    fun shouldClean(packageName: String?): Boolean {
        val pkg = packageName.orEmpty()
        if (bypassEnabled && pkg in bypassPackages) return false
        if (perAppEnabled && pkg !in perAppPackages) return false
        return true
    }

    fun addParameter(name: String): GhostConfig =
        if (name in DefaultParameters.SET) {
            copy(removedParameters = removedParameters - name)
        } else {
            copy(addedParameters = addedParameters + name, removedParameters = removedParameters - name)
        }

    fun removeParameter(name: String): GhostConfig =
        copy(removedParameters = removedParameters + name, addedParameters = addedParameters - name)

    fun restoreDefaultParameters(): GhostConfig =
        copy(addedParameters = emptySet(), removedParameters = emptySet())

    fun restoreDefaultExceptions(): GhostConfig =
        copy(exceptionDomains = emptySet())

    fun restoreDefaultSiteRules(): GhostConfig =
        copy(siteRules = DefaultSiteRules.ALL)

    fun restoreDefaultRedirectRules(): GhostConfig =
        copy(redirectRules = DefaultRedirectRules.SET)

    fun applyToCleaner() {
        UrlCleaner.redirectUnwrapEnabled = redirectUnwrap
        UrlCleaner.setRedirectRules(redirectRules)
        UrlCleaner.exceptionDomains = exceptionDomains
        UrlCleaner.siteRules = CompiledSiteRules.of(siteRules)
        UrlCleaner.matcher = ParameterMatcher.of(effectiveParameters())
        UrlCleaner.protectedMatcher = ParameterMatcher.of(
            removedParameters.filterNot { it.endsWith("*") }
        )
    }

    fun encode(): String = listOf(
        "v=2",
        "r=${flag(redirectUnwrap)}",
        "pa=${flag(perAppEnabled)}",
        "pl=${joinList(perAppPackages)}",
        "bp=${flag(bypassEnabled)}",
        "bl=${joinList(bypassPackages)}",
        "ap=${joinList(addedParameters)}",
        "rp=${joinList(removedParameters)}",
        "ed=${joinList(exceptionDomains)}",
        "sr=${joinSiteRules(siteRules)}",
        "rr=${joinRedirectRules(redirectRules)}"
    ).joinToString(";")

    companion object {
        private const val MAX_ITEMS = 5000

        private val PACKAGE_REGEX = Regex("[A-Za-z0-9._]{1,255}")
        private val PARAMETER_REGEX = Regex("""[a-z0-9_.\-\[\]*$?]{1,100}""")
        private val STRICT_DOMAIN_REGEX =
            Regex("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*\\.[a-z]{2,24}")
        private val BOOLEAN_XML = Regex("""<boolean name="([^"]+)" value="(true|false)"\s*/>""")
        private val STRING_XML = Regex("""<string name="([^"]+)">([^<]*)</string>""")

        private val ALL_KEYS = setOf(
            Constants.PREF_REDIRECT_UNWRAP,
            Constants.PREF_PER_APP_ENABLED,
            Constants.PREF_PER_APP_PACKAGES,
            Constants.PREF_BYPASS_ENABLED,
            Constants.PREF_BYPASS_PACKAGES,
            Constants.PREF_ADDED_PARAMS,
            Constants.PREF_REMOVED_PARAMS,
            Constants.PREF_EXCEPTION_DOMAINS,
            Constants.PREF_USER_EXCEPTIONS,
            Constants.PREF_SITE_RULES,
            Constants.PREF_REDIRECT_RULES
        )

        private fun flag(value: Boolean) = if (value) "1" else "0"

        fun joinList(items: Set<String>): String = items.sorted().joinToString(",")

        fun joinRedirectRules(rules: Set<String>): String =
            rules.joinToString(",") { URLEncoder.encode(it, "UTF-8") }

        fun parseRedirectRules(raw: String?): Set<String> {
            if (raw.isNullOrEmpty()) return emptySet()
            val result = LinkedHashSet<String>()
            for (part in raw.split(',')) {
                if (part.isEmpty()) continue
                val rule = try {
                    val decoded = URLDecoder.decode(part, "UTF-8")
                    if (decoded in DefaultRedirectRules.SET) decoded else RedirectRules.normalize(decoded)
                } catch (_: Exception) {
                    null
                }
                if (rule != null) result.add(rule)
                if (result.size >= RedirectRules.MAX_RULES) break
            }
            return result
        }

        fun joinSiteRules(rules: Map<String, SiteRule>): String =
            rules.entries.sortedBy { it.key }.joinToString(",") { (domain, rule) ->
                "$domain|${rule.keep.sorted().joinToString("+")}|${rule.strip.sorted().joinToString("+")}"
            }

        fun parseSiteRules(raw: String?): Map<String, SiteRule> {
            if (raw.isNullOrBlank()) return emptyMap()
            val result = LinkedHashMap<String, SiteRule>()
            var total = 0
            for (entry in raw.lowercase().split(',')) {
                val parts = entry.split('|')
                if (parts.size != 3) continue
                val domain = parts[0].trim()
                if (!STRICT_DOMAIN_REGEX.matches(domain)) continue
                val keep = parts[1].split('+').map { it.trim() }.filter { it.isNotEmpty() && PARAMETER_REGEX.matches(it) }.toSet()
                val strip = parts[2].split('+').map { it.trim() }.filter { it.isNotEmpty() && PARAMETER_REGEX.matches(it) }.toSet()
                val rule = SiteRule(keep, strip)
                if (rule.isEmpty) continue
                total += keep.size + strip.size
                if (total > MAX_ITEMS) break
                result[domain] = rule
            }
            return result
        }

        fun normalizeDomain(input: String): String? {
            var value = input.trim().lowercase()
            value = value.removePrefix("https://").removePrefix("http://")
            value = value.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
            value = value.removePrefix("www.").trim('.')
            return if (STRICT_DOMAIN_REGEX.matches(value)) value else null
        }

        fun normalizeParameter(input: String): String? {
            val value = input.trim().lowercase()
            return if (PARAMETER_REGEX.matches(value)) value else null
        }

        fun parsePackages(raw: String?): Set<String> = parseList(raw) { PACKAGE_REGEX.matches(it) }

        fun parseParameters(raw: String?): Set<String> = parseList(raw?.lowercase()) { PARAMETER_REGEX.matches(it) }

        fun parseDomains(raw: String?): Set<String> = parseList(raw?.lowercase()) { STRICT_DOMAIN_REGEX.matches(it) }

        private fun parseList(raw: String?, isValid: (String) -> Boolean): Set<String> {
            if (raw.isNullOrBlank()) return emptySet()
            val result = LinkedHashSet<String>()
            for (part in raw.split(',')) {
                val item = part.trim()
                if (item.isNotEmpty() && isValid(item)) {
                    result.add(item)
                    if (result.size >= MAX_ITEMS) break
                }
            }
            return result
        }

        fun decode(raw: String?): GhostConfig? {
            if (raw.isNullOrBlank()) return null
            val map = HashMap<String, String>()
            for (part in raw.split(';')) {
                val index = part.indexOf('=')
                if (index > 0) map[part.substring(0, index)] = part.substring(index + 1)
            }
            if (map["v"] != "2") return null
            return GhostConfig(
                redirectUnwrap = map["r"] != "0",
                perAppEnabled = map["pa"] == "1",
                perAppPackages = parsePackages(map["pl"]),
                bypassEnabled = map["bp"] == "1",
                bypassPackages = parsePackages(map["bl"]),
                addedParameters = parseParameters(map["ap"]),
                removedParameters = parseParameters(map["rp"]),
                exceptionDomains = parseDomains(map["ed"]),
                siteRules = parseSiteRules(map["sr"]),
                redirectRules = if (map.containsKey("rr")) parseRedirectRules(map["rr"]) else DefaultRedirectRules.SET
            )
        }

        fun hasStoredValues(prefs: SharedPreferences): Boolean = ALL_KEYS.any { prefs.contains(it) }

        fun fromPrefs(prefs: SharedPreferences): GhostConfig {
            val domains = if (prefs.contains(Constants.PREF_EXCEPTION_DOMAINS)) {
                prefs.getString(Constants.PREF_EXCEPTION_DOMAINS, "")
            } else {
                prefs.getString(Constants.PREF_USER_EXCEPTIONS, "")
            }
            return GhostConfig(
                redirectUnwrap = prefs.getBoolean(Constants.PREF_REDIRECT_UNWRAP, true),
                perAppEnabled = prefs.getBoolean(Constants.PREF_PER_APP_ENABLED, false),
                perAppPackages = parsePackages(prefs.getString(Constants.PREF_PER_APP_PACKAGES, "")),
                bypassEnabled = prefs.getBoolean(Constants.PREF_BYPASS_ENABLED, false),
                bypassPackages = parsePackages(prefs.getString(Constants.PREF_BYPASS_PACKAGES, "")),
                addedParameters = parseParameters(prefs.getString(Constants.PREF_ADDED_PARAMS, "")),
                removedParameters = parseParameters(prefs.getString(Constants.PREF_REMOVED_PARAMS, "")),
                exceptionDomains = parseDomains(domains),
                siteRules = if (prefs.contains(Constants.PREF_SITE_RULES)) {
                    parseSiteRules(prefs.getString(Constants.PREF_SITE_RULES, ""))
                } else {
                    DefaultSiteRules.ALL
                },
                redirectRules = if (prefs.contains(Constants.PREF_REDIRECT_RULES)) {
                    parseRedirectRules(prefs.getString(Constants.PREF_REDIRECT_RULES, ""))
                } else {
                    DefaultRedirectRules.SET
                }
            )
        }

        fun fromPrefsXml(xml: String): GhostConfig? {
            val booleans = BOOLEAN_XML.findAll(xml).associate { it.groupValues[1] to (it.groupValues[2] == "true") }
            val strings = STRING_XML.findAll(xml).associate { it.groupValues[1] to it.groupValues[2] }
            if (ALL_KEYS.none { it in booleans || it in strings }) return null
            val domains = if (Constants.PREF_EXCEPTION_DOMAINS in strings) {
                strings[Constants.PREF_EXCEPTION_DOMAINS]
            } else {
                strings[Constants.PREF_USER_EXCEPTIONS]
            }
            return GhostConfig(
                redirectUnwrap = booleans[Constants.PREF_REDIRECT_UNWRAP] ?: true,
                perAppEnabled = booleans[Constants.PREF_PER_APP_ENABLED] ?: false,
                perAppPackages = parsePackages(strings[Constants.PREF_PER_APP_PACKAGES]),
                bypassEnabled = booleans[Constants.PREF_BYPASS_ENABLED] ?: false,
                bypassPackages = parsePackages(strings[Constants.PREF_BYPASS_PACKAGES]),
                addedParameters = parseParameters(strings[Constants.PREF_ADDED_PARAMS]),
                removedParameters = parseParameters(strings[Constants.PREF_REMOVED_PARAMS]),
                exceptionDomains = parseDomains(domains),
                siteRules = if (Constants.PREF_SITE_RULES in strings) {
                    parseSiteRules(strings[Constants.PREF_SITE_RULES])
                } else {
                    DefaultSiteRules.ALL
                },
                redirectRules = if (Constants.PREF_REDIRECT_RULES in strings) {
                    parseRedirectRules(strings[Constants.PREF_REDIRECT_RULES])
                } else {
                    DefaultRedirectRules.SET
                }
            )
        }
    }
}
