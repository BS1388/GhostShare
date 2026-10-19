package ir.ghostshare

import android.content.SharedPreferences

data class GhostConfig(
    val redirectUnwrap: Boolean = true,
    val builtinExceptions: Boolean = true,
    val perAppEnabled: Boolean = false,
    val perAppPackages: Set<String> = emptySet(),
    val bypassEnabled: Boolean = false,
    val bypassPackages: Set<String> = emptySet(),
    val userExceptions: Set<String> = emptySet()
) {

    fun shouldClean(packageName: String?): Boolean {
        val pkg = packageName.orEmpty()
        if (bypassEnabled && pkg in bypassPackages) return false
        if (perAppEnabled && pkg !in perAppPackages) return false
        return true
    }

    fun applyToCleaner() {
        UrlCleaner.redirectUnwrapEnabled = redirectUnwrap
        UrlCleaner.builtinExceptionsEnabled = builtinExceptions
        UrlCleaner.userExceptionDomains = userExceptions
    }

    fun encode(): String = listOf(
        "v=1",
        "r=${flag(redirectUnwrap)}",
        "e=${flag(builtinExceptions)}",
        "pa=${flag(perAppEnabled)}",
        "pl=${joinList(perAppPackages)}",
        "bp=${flag(bypassEnabled)}",
        "bl=${joinList(bypassPackages)}",
        "ex=${joinList(userExceptions)}"
    ).joinToString(";")

    companion object {
        private const val MAX_ITEMS = 2000
        private val PACKAGE_REGEX = Regex("[A-Za-z0-9._]{1,255}")
        private val DOMAIN_REGEX = Regex("[a-z0-9.-]{1,253}")
        private val STRICT_DOMAIN_REGEX = Regex("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+")
        private val BOOLEAN_XML = Regex("""<boolean name="([^"]+)" value="(true|false)"\s*/>""")
        private val STRING_XML = Regex("""<string name="([^"]+)">([^<]*)</string>""")

        private val ALL_KEYS = setOf(
            Constants.PREF_REDIRECT_UNWRAP,
            Constants.PREF_BUILTIN_EXCEPTIONS,
            Constants.PREF_PER_APP_ENABLED,
            Constants.PREF_PER_APP_PACKAGES,
            Constants.PREF_BYPASS_ENABLED,
            Constants.PREF_BYPASS_PACKAGES,
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

        fun parsePackages(raw: String?): Set<String> = parseList(raw, PACKAGE_REGEX)

        fun parseDomains(raw: String?): Set<String> = parseList(raw?.lowercase(), DOMAIN_REGEX)

        private fun parseList(raw: String?, valid: Regex): Set<String> {
            if (raw.isNullOrBlank()) return emptySet()
            val result = LinkedHashSet<String>()
            for (part in raw.split(',')) {
                val item = part.trim()
                if (item.isNotEmpty() && valid.matches(item)) {
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
            if (map["v"] != "1") return null
            return GhostConfig(
                redirectUnwrap = map["r"] != "0",
                builtinExceptions = map["e"] != "0",
                perAppEnabled = map["pa"] == "1",
                perAppPackages = parsePackages(map["pl"]),
                bypassEnabled = map["bp"] == "1",
                bypassPackages = parsePackages(map["bl"]),
                userExceptions = parseDomains(map["ex"])
            )
        }

        fun hasStoredValues(prefs: SharedPreferences): Boolean = ALL_KEYS.any { prefs.contains(it) }

        fun fromPrefs(prefs: SharedPreferences): GhostConfig = GhostConfig(
            redirectUnwrap = prefs.getBoolean(Constants.PREF_REDIRECT_UNWRAP, true),
            builtinExceptions = prefs.getBoolean(Constants.PREF_BUILTIN_EXCEPTIONS, true),
            perAppEnabled = prefs.getBoolean(Constants.PREF_PER_APP_ENABLED, false),
            perAppPackages = parsePackages(prefs.getString(Constants.PREF_PER_APP_PACKAGES, "")),
            bypassEnabled = prefs.getBoolean(Constants.PREF_BYPASS_ENABLED, false),
            bypassPackages = parsePackages(prefs.getString(Constants.PREF_BYPASS_PACKAGES, "")),
            userExceptions = parseDomains(prefs.getString(Constants.PREF_USER_EXCEPTIONS, ""))
        )

        fun fromPrefsXml(xml: String): GhostConfig? {
            val booleans = BOOLEAN_XML.findAll(xml).associate { it.groupValues[1] to (it.groupValues[2] == "true") }
            val strings = STRING_XML.findAll(xml).associate { it.groupValues[1] to it.groupValues[2] }
            if (ALL_KEYS.none { it in booleans || it in strings }) return null
            return GhostConfig(
                redirectUnwrap = booleans[Constants.PREF_REDIRECT_UNWRAP] ?: true,
                builtinExceptions = booleans[Constants.PREF_BUILTIN_EXCEPTIONS] ?: true,
                perAppEnabled = booleans[Constants.PREF_PER_APP_ENABLED] ?: false,
                perAppPackages = parsePackages(strings[Constants.PREF_PER_APP_PACKAGES]),
                bypassEnabled = booleans[Constants.PREF_BYPASS_ENABLED] ?: false,
                bypassPackages = parsePackages(strings[Constants.PREF_BYPASS_PACKAGES]),
                userExceptions = parseDomains(strings[Constants.PREF_USER_EXCEPTIONS])
            )
        }
    }
}
