package ir.ghostshare

import android.content.SharedPreferences

data class GhostConfig(
    val redirectUnwrap: Boolean = true,
    val perAppEnabled: Boolean = false,
    val perAppPackages: Set<String> = emptySet(),
    val bypassEnabled: Boolean = false,
    val bypassPackages: Set<String> = emptySet(),
    val addedParameters: Set<String> = emptySet(),
    val removedParameters: Set<String> = emptySet(),
    val exceptionDomains: Set<String> = emptySet(),
    val appLayerDisabled: Boolean = false
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
            copy(addedParameters = addedParameters + name)
        }

    fun removeParameter(name: String): GhostConfig =
        if (name in DefaultParameters.SET) {
            copy(removedParameters = removedParameters + name, addedParameters = addedParameters - name)
        } else {
            copy(addedParameters = addedParameters - name)
        }

    fun restoreDefaultParameters(): GhostConfig =
        copy(addedParameters = emptySet(), removedParameters = emptySet())

    fun applyToCleaner() {
        UrlCleaner.redirectUnwrapEnabled = redirectUnwrap
        UrlCleaner.exceptionDomains = exceptionDomains
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
        "ed=${joinList(exceptionDomains)}"
    ).joinToString(";")

    fun encodeForMirror(): String {
        val full = encode()
        return if (full.length <= MIRROR_LIMIT) full else "v=2;big=1"
    }

    companion object {
        const val MIRROR_LIMIT = 8000
        private const val MAX_ITEMS = 5000

        private val PACKAGE_REGEX = Regex("[A-Za-z0-9._]{1,255}")
        private val PARAMETER_REGEX = Regex("""[a-z0-9_.\-\[\]*$]{1,100}""")
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
            Constants.PREF_USER_EXCEPTIONS
        )

        private fun flag(value: Boolean) = if (value) "1" else "0"

        fun joinList(items: Set<String>): String = items.sorted().joinToString(",")

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
            if (map["big"] == "1") return GhostConfig(appLayerDisabled = true)
            return GhostConfig(
                redirectUnwrap = map["r"] != "0",
                perAppEnabled = map["pa"] == "1",
                perAppPackages = parsePackages(map["pl"]),
                bypassEnabled = map["bp"] == "1",
                bypassPackages = parsePackages(map["bl"]),
                addedParameters = parseParameters(map["ap"]),
                removedParameters = parseParameters(map["rp"]),
                exceptionDomains = parseDomains(map["ed"])
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
                exceptionDomains = parseDomains(domains)
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
                exceptionDomains = parseDomains(domains)
            )
        }
    }
}
