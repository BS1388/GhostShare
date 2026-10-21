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
        val exceptionParameters: Set<String>?,
        val skipped: Int
    ) {
        val total: Int
            get() = (parameters?.size ?: 0) + (exceptionDomains?.size ?: 0) + (exceptionParameters?.size ?: 0)
    }

    sealed class ParseResult {
        class Success(val data: ImportData) : ParseResult()
        class Error(val reason: Failure) : ParseResult()
    }

    fun export(config: GhostConfig): String {
        val root = JSONObject()
        root.put("version", VERSION)
        root.put("parameters", JSONArray(config.effectiveParameters().sorted()))
        root.put(
            "exceptions",
            JSONArray(config.exceptionDomains.sorted() + config.exceptionParameters.sorted())
        )
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
        if (!hasParameters && !hasExceptions) return ParseResult.Error(Failure.WRONG_STRUCTURE)

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
        var exceptionParameters: Set<String>? = null
        if (hasExceptions) {
            val array = root.optJSONArray("exceptions") ?: return ParseResult.Error(Failure.WRONG_STRUCTURE)
            val domainSet = LinkedHashSet<String>()
            val parameterSet = LinkedHashSet<String>()
            val limit = minOf(array.length(), MAX_ENTRIES)
            skipped += array.length() - limit
            for (i in 0 until limit) {
                val entry = array.opt(i) as? String
                if (entry == null) {
                    skipped++
                    continue
                }
                val domain = if (GhostConfig.looksLikeDomain(entry)) GhostConfig.normalizeDomain(entry) else null
                if (domain != null) {
                    domainSet.add(domain)
                } else {
                    val parameter = GhostConfig.normalizeParameter(entry)
                    if (parameter != null) parameterSet.add(parameter) else skipped++
                }
            }
            domains = domainSet
            exceptionParameters = parameterSet
        }

        return ParseResult.Success(ImportData(parameters, domains, exceptionParameters, skipped))
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
        if (data.exceptionParameters != null) result = result.copy(exceptionParameters = data.exceptionParameters)
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
        if (data.exceptionParameters != null) {
            result = result.copy(exceptionParameters = result.exceptionParameters + data.exceptionParameters)
        }
        return result
    }
}
