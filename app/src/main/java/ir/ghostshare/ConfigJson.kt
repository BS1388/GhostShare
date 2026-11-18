package ir.ghostshare

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

object ConfigJson {

    private const val VERSION = 1
    private const val MAX_ENTRIES = 5000

    enum class Failure { NOT_JSON, WRONG_STRUCTURE, UNSUPPORTED_VERSION }

    class ImportData(
        val parameters: Set<String>?,
        val exceptionDomains: Set<String>?,
        val siteRules: Map<String, SiteRule>?,
        val redirectRules: Set<String>?,
        val skipped: Int
    ) {
        val total: Int
            get() = (parameters?.size ?: 0) + (exceptionDomains?.size ?: 0) + (siteRules?.size ?: 0) + (redirectRules?.size ?: 0)
    }

    sealed class ParseResult {
        class Success(val data: ImportData) : ParseResult()
        class Error(val reason: Failure) : ParseResult()
    }

    fun export(config: GhostConfig): String {
        val root = JSONObject()
        root.put("version", VERSION)
        root.put("parameters", JSONArray(config.effectiveParameters().sorted()))
        root.put("exceptions", JSONArray(config.exceptionDomains.sorted()))
        val rules = JSONArray()
        for ((domain, rule) in config.siteRules.entries.sortedBy { it.key }) {
            rules.put(
                JSONObject()
                    .put("domain", domain)
                    .put("keep", JSONArray(rule.keep.sorted()))
                    .put("strip", JSONArray(rule.strip.sorted()))
            )
        }
        root.put("site_rules", rules)
        root.put("redirect_rules", JSONArray(config.redirectRules.toList()))
        return root.toString(2)
    }

    fun parse(text: String): ParseResult {
        val root = try {
            JSONObject(text.trim().removePrefix("\uFEFF"))
        } catch (_: JSONException) {
            return ParseResult.Error(Failure.NOT_JSON)
        }

        if (!root.has("version")) return ParseResult.Error(Failure.WRONG_STRUCTURE)
        val version = root.opt("version")
        if (version !is Int && version !is Long) return ParseResult.Error(Failure.WRONG_STRUCTURE)
        if ((version as Number).toInt() != VERSION) return ParseResult.Error(Failure.UNSUPPORTED_VERSION)

        val hasParameters = root.has("parameters")
        val hasExceptions = root.has("exceptions")
        val hasSiteRules = root.has("site_rules")
        val hasRedirectRules = root.has("redirect_rules")
        if (!hasParameters && !hasExceptions && !hasSiteRules && !hasRedirectRules) return ParseResult.Error(Failure.WRONG_STRUCTURE)

        var skipped = 0

        var parameters: Set<String>? = null
        if (hasParameters) {
            val array = root.optJSONArray("parameters") ?: return ParseResult.Error(Failure.WRONG_STRUCTURE)
            val result = LinkedHashSet<String>()
            val limit = minOf(array.length(), MAX_ENTRIES)
            skipped += array.length() - limit
            for (i in 0 until limit) {
                val name = (array.opt(i) as? String)?.let { GhostConfig.normalizeParameter(it) }
                if (name != null) result.add(name) else skipped++
            }
            parameters = result
        }

        var domains: Set<String>? = null
        if (hasExceptions) {
            val array = root.optJSONArray("exceptions") ?: return ParseResult.Error(Failure.WRONG_STRUCTURE)
            val domainSet = LinkedHashSet<String>()
            val limit = minOf(array.length(), MAX_ENTRIES)
            skipped += array.length() - limit
            for (i in 0 until limit) {
                val domain = (array.opt(i) as? String)?.let { GhostConfig.normalizeDomain(it) }
                if (domain != null) domainSet.add(domain) else skipped++
            }
            domains = domainSet
        }

        var siteRules: Map<String, SiteRule>? = null
        if (hasSiteRules) {
            val array = root.optJSONArray("site_rules") ?: return ParseResult.Error(Failure.WRONG_STRUCTURE)
            val ruleMap = LinkedHashMap<String, SiteRule>()
            val limit = minOf(array.length(), MAX_ENTRIES)
            skipped += array.length() - limit
            for (i in 0 until limit) {
                val item = array.optJSONObject(i)
                val domain = item?.optString("domain")?.let { GhostConfig.normalizeDomain(it) }
                if (item == null || domain == null) {
                    skipped++
                    continue
                }
                val keep = readNames(item.optJSONArray("keep"))
                val strip = readNames(item.optJSONArray("strip"))
                val rule = SiteRule(keep, strip)
                if (rule.isEmpty) {
                    skipped++
                } else {
                    val existing = ruleMap[domain]
                    ruleMap[domain] = if (existing == null) rule else SiteRule(existing.keep + keep, existing.strip + strip)
                }
            }
            siteRules = ruleMap
        }

        var redirectRules: Set<String>? = null
        if (hasRedirectRules) {
            val array = root.optJSONArray("redirect_rules") ?: return ParseResult.Error(Failure.WRONG_STRUCTURE)
            val ruleSet = LinkedHashSet<String>()
            val limit = minOf(array.length(), RedirectRules.MAX_RULES)
            skipped += array.length() - limit
            for (i in 0 until limit) {
                val rule = (array.opt(i) as? String)?.let { RedirectRules.normalize(it) }
                if (rule != null) ruleSet.add(rule) else skipped++
            }
            redirectRules = ruleSet
        }

        return ParseResult.Success(ImportData(parameters, domains, siteRules, redirectRules, skipped))
    }

    private fun readNames(array: JSONArray?): Set<String> {
        if (array == null) return emptySet()
        val result = LinkedHashSet<String>()
        for (i in 0 until minOf(array.length(), MAX_ENTRIES)) {
            val name = (array.opt(i) as? String)?.let { GhostConfig.normalizeParameter(it) }
            if (name != null) result.add(name)
        }
        return result
    }

    fun overwrite(config: GhostConfig, data: ImportData): GhostConfig {
        var result = config
        val incoming = data.parameters
        if (incoming != null) {
            result = result.copy(
                addedParameters = incoming - DefaultParameters.SET,
                removedParameters = DefaultParameters.SET - incoming
            )
        }
        if (data.exceptionDomains != null) result = result.copy(exceptionDomains = data.exceptionDomains)
        if (data.siteRules != null) result = result.copy(siteRules = data.siteRules)
        if (data.redirectRules != null) result = result.copy(redirectRules = data.redirectRules)
        return result
    }

    fun merge(config: GhostConfig, data: ImportData): GhostConfig {
        var result = config
        val incoming = data.parameters
        if (incoming != null) {
            result = result.copy(
                addedParameters = result.addedParameters + (incoming - DefaultParameters.SET),
                removedParameters = result.removedParameters - incoming
            )
        }
        if (data.exceptionDomains != null) {
            result = result.copy(exceptionDomains = result.exceptionDomains + data.exceptionDomains)
        }
        if (data.siteRules != null) {
            val merged = LinkedHashMap(result.siteRules)
            for ((domain, rule) in data.siteRules) {
                val existing = merged[domain]
                merged[domain] = if (existing == null) rule else SiteRule(existing.keep + rule.keep, existing.strip + rule.strip)
            }
            result = result.copy(siteRules = merged)
        }
        if (data.redirectRules != null) {
            result = result.copy(redirectRules = result.redirectRules + data.redirectRules)
        }
        return result
    }
}
