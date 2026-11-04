/* Independent Sony avContent ("Contents Transfer") JSON-RPC layer.
 *
 * Read-only RPCs only; never deletes/moves/mutates camera content and never switches camera mode
 * (startRecMode / setCameraFunction are the caller's job). The avContent action URL comes from the
 * ScalarWebAPI service list, never hard-coded. Version negotiation targets getContentCount 1.2 and
 * getContentList 1.3 (the JSON-RPC envelope `version`). Error 7 (NotAvailableNow) and a malformed
 * result[0] are surfaced, never treated as an empty gallery; only a real empty result[0] array ends
 * a listing.
 */
package io.github.ayatinene.sonycompanion.protocol

import io.github.ayatinene.sonycompanion.network.CameraHttp
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

/** Normalised original-resource format. Unknown kinds are dropped rather than guessed. */
const val AV_CONTENT_FORMAT_JPEG = "jpeg"
const val AV_CONTENT_FORMAT_RAW = "arw"

/** Thrown when the camera does not declare getContentList for any advertised API version. */
class AvContentUnsupported(message: String) : Exception(message)

/** Thrown when an avContent response is structurally malformed (must not be read as "empty"). */
class AvContentProtocolError(message: String) : Exception(message)

/**
 * One downloadable original resource of a content item. [size] is 0 when the camera omitted
 * `fileSize`; callers must treat 0 as "length unknown", never as an empty file.
 */
data class AvContentOriginal(
    val url: String,
    val format: String,
    val name: String,
    val size: Long
)

/**
 * Content identity surfaced to the controller. [contentId] is the top-level `uri` (stable identity);
 * [name] comes from the first original file; [original] holds every JPEG/ARW original from the inner
 * `content` object; [previewUrl] is the best preview (largeUrl -> smallUrl -> thumbnailUrl) and
 * [thumbnailUrl] the dedicated thumbnail. [folderNo] distinguishes same-named items in different
 * parent folders. All URLs are strictly validated and may be null.
 */
data class AvContentItem(
    val source: String,
    val contentId: String,
    val name: String,
    val date: String,
    val original: List<AvContentOriginal>,
    val previewUrl: String?,
    val thumbnailUrl: String? = null,
    val folderNo: String = ""
) {
    val jpegOriginals: List<AvContentOriginal> get() = original.filter { it.format == AV_CONTENT_FORMAT_JPEG }
    val rawOriginals: List<AvContentOriginal> get() = original.filter { it.format == AV_CONTENT_FORMAT_RAW }
}

/**
 * One page of `getContentList`. [returned] is the raw array length the camera returned; [rawUris]
 * holds the top-level `uri` of every returned entry (including entries filtered out as unknown
 * kinds) so pagination can detect duplicate / zero-progress pages.
 */
data class AvContentPage(
    val items: List<AvContentItem>,
    val returned: Int,
    val rawUris: List<String> = emptyList()
)

/** A method row from `getMethodTypes`: the method name and the API version that declares it. */
data class AvContentMethodType(val name: String, val version: String)

/**
 * Read-only client for the camera's avContent JSON-RPC service.
 *
 * Call contract: resolve [endpoint] from the camera descriptor, `negotiate()` (or let the first read
 * negotiate lazily), then `schemes()` -> `sources(scheme)` -> `count(source)` -> `list(source)`.
 * getContentList uses the flat view with descending sort, 20 items per page by default.
 */
class AvContentTransfer(
    val endpoint: String,
    private val http: CameraHttp,
    private val trace: (String) -> Unit = {}
) {
    private val nextId = AtomicInteger(1)
    private var negotiated = false

    /** API version used for getContentCount (negotiated, default 1.2). */
    var countVersion: String = DEFAULT_COUNT_VERSION
        private set

    /** API version used for getContentList (negotiated, default 1.3). */
    var listVersion: String = DEFAULT_LIST_VERSION
        private set

    /** False when the camera does not advertise getContentCount; [count] then returns -1. */
    var countAvailable: Boolean = false
        private set

    init {
        require(strictContentUrl(endpoint) != null) { "avContent 端点必须是有效的 http(s) URL" }
    }

    // --- version negotiation -------------------------------------------------------------------

    /** Returns the API versions the camera advertises for avContent (getVersions result[0]). */
    suspend fun versions(): List<String> {
        val result = call("getVersions")
        val array = result.optJSONArray(0)
            ?: throw AvContentProtocolError("avContent getVersions 返回的 result[0] 不是数组")
        return (0 until array.length()).mapNotNull { array.opt(it) as? String }
            .map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** Returns the method rows advertised for [version]. */
    suspend fun methodTypes(version: String): List<AvContentMethodType> {
        val result = call("getMethodTypes", JSONArray().put(version))
        val nested = result.optJSONArray(0)
        val rows = if (nested != null && nested.opt(0) is JSONArray) nested else result
        return (0 until rows.length()).mapNotNull { i ->
            val row = rows.optJSONArray(i) ?: return@mapNotNull null
            val name = row.optString(0).trim()
            if (name.isEmpty()) null else AvContentMethodType(name, row.optString(3).trim())
        }
    }

    /**
     * Resolves getContentList at 1.3 (or the highest advertised version that declares it) and
     * getContentCount at 1.2 when available. Throws [AvContentUnsupported] when getContentList is
     * not declared for any advertised version instead of optimistically sending a request.
     * Idempotent; called lazily by the read methods.
     */
    suspend fun negotiate() {
        val available = versions()
        check(available.isNotEmpty()) { "相机未返回 avContent 版本" }
        val ordered = available.sortedWith { a, b -> compareVersions(b, a) }
        listVersion = resolveMethodVersion("getContentList", DEFAULT_LIST_VERSION, ordered)
            ?: throw AvContentUnsupported("相机未声明 avContent getContentList，无法列举内容")
        val count = resolveMethodVersion("getContentCount", DEFAULT_COUNT_VERSION, ordered)
        countAvailable = count != null
        countVersion = count ?: DEFAULT_COUNT_VERSION
        negotiated = true
        trace("avContent 协商：count=$countVersion($countAvailable) list=$listVersion")
    }

    /**
     * Finds the API version to use for [method]. getMethodTypes rows may carry the minimum supported
     * version rather than the queried version, so a method listed at [version] counts as supported at
     * that version too. The preferred default wins when it is among the declared versions, otherwise
     * the highest known declared version is used. Returns null (never a blind guess) when no probed
     * version declares the method.
     */
    private suspend fun resolveMethodVersion(method: String, preferred: String, ordered: List<String>): String? {
        val probes = (listOf(preferred) + ordered).distinct()
        for (version in probes) {
            if (version !in ordered) continue
            val declared = LinkedHashSet<String>()
            methodTypes(version).filter { it.name == method }.forEach { declared += it.version }
            if (declared.isEmpty()) continue
            declared += version
            if (preferred in declared && preferred in ordered) return preferred
            return declared.filter { it in ordered }.maxWithOrNull(::compareVersions) ?: version
        }
        return null
    }

    private suspend fun ensureNegotiated() {
        if (!negotiated) negotiate()
    }

    // --- listing -------------------------------------------------------------------------------

    /** Returns the scheme names (getSchemeList result[0]). */
    suspend fun schemes(): List<String> {
        ensureNegotiated()
        return namedValues(call("getSchemeList"), "scheme", "getSchemeList")
    }

    /** Returns the source ids for [scheme] (getSourceList). */
    suspend fun sources(scheme: String): List<String> {
        ensureNegotiated()
        return namedValues(call("getSourceList", JSONArray().put(JSONObject().put("scheme", scheme))), "source", "getSourceList")
    }

    /**
     * Returns the declared content count from `result[0] {"count": n}`, or -1 when the camera does
     * not advertise getContentCount. Error 7 and malformed results are not swallowed.
     */
    suspend fun count(source: String): Int {
        ensureNegotiated()
        if (!countAvailable) return -1
        val result = call("getContentCount", countParams(source), countVersion)
        if (result.length() == 0) throw AvContentProtocolError("avContent getContentCount 返回空 result")
        val first = result.opt(0)
        return when (first) {
            is JSONObject -> {
                if (!first.has("count") || first.isNull("count"))
                    throw AvContentProtocolError("avContent getContentCount 缺少 count")
                jsonInt(first.opt("count"))
            }
            is Number, is String -> jsonInt(first)
            else -> throw AvContentProtocolError("avContent getContentCount 返回未知结构")
        }
    }

    /**
     * Fetches one flat page in the requested sort order. Error 7 and any other API error propagate
     * unchanged; a malformed result[0] throws [AvContentProtocolError]; only a real empty
     * `result[0]` array yields an empty page.
     */
    suspend fun getContentPage(
        source: String,
        startIndex: Int,
        pageSize: Int = DEFAULT_PAGE_SIZE,
        sort: String = SORT_DESCENDING
    ): AvContentPage {
        require(startIndex >= 0) { "起始位置不能为负" }
        require(pageSize > 0) { "分页大小必须为正" }
        ensureNegotiated()
        val result = call("getContentList", listParams(source, startIndex, pageSize, sort), listVersion)
        val array = result.optJSONArray(0)
            ?: throw AvContentProtocolError("avContent getContentList 返回的 result[0] 不是数组")
        val items = ArrayList<AvContentItem>(array.length())
        val rawUris = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            val raw = array.opt(i)
            rawUris += (raw as? JSONObject)?.stringOrNull("uri").orEmpty()
            parseAvContentItem(raw, source)?.let(items::add)
        }
        return AvContentPage(items, array.length(), rawUris)
    }

    /**
     * Walks every flat page until the declared raw count is reached or a page makes no progress.
     * Progress is measured by the raw `uri` page fingerprint (so a page whose entries are all
     * filtered out as unknown kinds still advances), while parsed items are de-duplicated by
     * [AvContentItem.contentId]. Empty and short pages terminate cleanly, with a hard page cap.
     */
    suspend fun list(
        source: String,
        pageSize: Int = DEFAULT_PAGE_SIZE,
        sort: String = SORT_DESCENDING,
        onPage: suspend (AvContentPage) -> Unit = {}
    ): List<AvContentItem> {
        require(pageSize > 0) { "分页大小必须为正" }
        ensureNegotiated()
        val total = count(source)
        val seenUris = LinkedHashSet<String>()
        val seenIds = LinkedHashSet<String>()
        val out = ArrayList<AvContentItem>()
        var start = 0
        var pages = 0
        while (pages < MAX_PAGES) {
            val page = getContentPage(source, start, pageSize, sort)
            onPage(page)
            pages++
            if (page.returned == 0) break                                     // real empty page
            val freshUris = page.rawUris.filter { it.isNotEmpty() }.filter { seenUris.add(it) }
            if (page.rawUris.any { it.isNotEmpty() } && freshUris.isEmpty()) break // duplicate page
            for (item in page.items) if (seenIds.add(item.contentId)) out += item
            start += page.returned                                            // raw content progress
            if (total >= 0 && start >= total) break
            if (page.returned < pageSize) break
        }
        check(pages < MAX_PAGES) { "avContent 分页超出上限，请导出诊断信息" }
        return out
    }

    // --- RPC -----------------------------------------------------------------------------------

    private fun countParams(source: String) = JSONArray().put(JSONObject().put("uri", source).put("view", VIEW_FLAT))

    private fun listParams(source: String, startIndex: Int, pageSize: Int, sort: String) = JSONArray().put(
        JSONObject().put("uri", source).put("stIdx", startIndex).put("cnt", pageSize)
            .put("view", VIEW_FLAT).put("sort", sort)
    )

    private suspend fun call(method: String, params: JSONArray = JSONArray(), version: String = "1.0"): JSONArray =
        retryOnTimeout(TIMEOUT_ATTEMPTS) {
            val request = JSONObject().put("method", method).put("params", params)
                .put("id", nextId.getAndIncrement()).put("version", version)
            trace("avContent RPC → $method v$version")
            val json = JSONObject(http.post(endpoint, request.toString()))
            val error = json.optJSONArray("error")
            if (error != null) throw CameraApiError(error.optInt(0, -1), error.optString(1))
            json.optJSONArray("result") ?: error("avContent 返回缺少 result：$method")
        }

    private fun namedValues(result: JSONArray, key: String, method: String): List<String> {
        val array = result.optJSONArray(0)
            ?: throw AvContentProtocolError("avContent $method 返回的 result[0] 不是数组")
        val out = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            when (val value = array.opt(i)) {
                is String -> value.trim().takeIf { it.isNotEmpty() }?.let(out::add)
                is JSONObject -> value.stringOrNull(key)?.let(out::add)
            }
        }
        return out
    }

    companion object {
        const val SERVICE_TYPE = "avContent"
        const val DEFAULT_COUNT_VERSION = "1.2"
        const val DEFAULT_LIST_VERSION = "1.3"
        const val DEFAULT_PAGE_SIZE = 20
        const val SORT_DESCENDING = "descending"
        const val VIEW_FLAT = "flat"

        /** Sony error code for "NotAvailableNow"; surfaced to the caller, never treated as empty. */
        const val ERROR_NOT_AVAILABLE_NOW = 7

        /** Hard cap on pages walked in [list]; far above a full card at 20 items per page. */
        const val MAX_PAGES = 10_000

        /** Read-only calls are retried once on a socket timeout, then surfaced. */
        const val TIMEOUT_ATTEMPTS = 2
        const val TIMEOUT_RETRY_DELAY_MS = 200L

        /**
         * Resolves the avContent JSON-RPC action URL from a ScalarWebAPI descriptor. Returns null
         * when the descriptor advertises no avContent service. No IP or port is hard-coded; the
         * advertised URL is resolved against the descriptor location / URLBase.
         */
        fun endpointFromDescriptor(descriptorXml: String, location: URI): String? {
            val root = CameraXml.document(descriptorXml)
            val urlBase = with(CameraXml) { root.text("URLBase") }.takeIf { it.isNotBlank() }
                ?.let { runCatching { location.resolve(it) }.getOrNull() } ?: location
            val advertised = with(CameraXml) {
                root.all("X_ScalarWebAPI_Service").firstOrNull {
                    it.text("X_ScalarWebAPI_ServiceType").trim() == SERVICE_TYPE
                }?.text("X_ScalarWebAPI_ActionList_URL")
            }?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
            val base = runCatching { urlBase.resolve(advertised).toString() }.getOrNull()
                ?.trimEnd('/') ?: return null
            val resolved = if (base.endsWith("/$SERVICE_TYPE")) base else "$base/$SERVICE_TYPE"
            return strictContentUrl(resolved)
        }
    }
}

/** Accepts only absolute http/https URLs with a host, no userinfo, no fragment and a valid port. */
internal fun strictContentUrl(raw: String?): String? {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty()) return null
    val uri = runCatching { URI(value) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme != "http" && scheme != "https") return null
    if (uri.host.isNullOrBlank()) return null
    if (uri.rawUserInfo != null) return null
    if (uri.rawFragment != null) return null
    if (uri.port != -1 && uri.port !in 1..65535) return null
    return value
}

/**
 * Parses one getContentList entry. Top-level fields are `uri` / `contentKind` / `createdTime`; the
 * `content` object carries `original` and the preview URLs. Unknown content kinds, unknown resource
 * formats and entries without a real `uri` are dropped (a name never substitutes for identity).
 */
internal fun parseAvContentItem(raw: Any?, source: String): AvContentItem? {
    val obj = raw as? JSONObject ?: return null
    val kind = obj.stringOrNull("contentKind")?.lowercase().orEmpty()
    if (kind.isNotEmpty() && kind != "still") return null
    val contentId = obj.stringOrNull("uri") ?: return null
    val date = obj.stringOrNull("createdTime").orEmpty()
    val inner = obj.optJSONObject("content") ?: obj
    val original = parseAvOriginals(inner.optJSONArray("original") ?: obj.optJSONArray("original"))
    if (original.isEmpty()) return null
    val name = obj.stringOrNull("contentName") ?: obj.stringOrNull("name") ?: original.first().name
    val large = strictContentUrl(inner.stringOrNull("largeUrl"))
    val small = strictContentUrl(inner.stringOrNull("smallUrl"))
    val thumbnail = strictContentUrl(inner.stringOrNull("thumbnailUrl"))
    val folderNo = obj.stringOrNull("folderNo") ?: inner.stringOrNull("folderNo").orEmpty()
    return AvContentItem(source, contentId, name, date, original, large ?: small ?: thumbnail, thumbnail, folderNo)
}

/** Parses the `original` array, keeping only strictly valid JPEG/ARW resources. */
internal fun parseAvOriginals(array: JSONArray?): List<AvContentOriginal> {
    if (array == null) return emptyList()
    val out = ArrayList<AvContentOriginal>(array.length())
    for (i in 0 until array.length()) {
        val o = array.optJSONObject(i) ?: continue
        val url = strictContentUrl(o.stringOrNull("url")) ?: continue
        val fileName = o.stringOrNull("fileName")
        val still = o.stringOrNull("stillObject")
        val format = avOriginalFormat(fileName, url, still) ?: continue
        val size = jsonLong(o.opt("fileSize")).coerceAtLeast(0L)
        out += AvContentOriginal(url, format, resourceName(fileName, url, format), size)
    }
    return out
}

/**
 * Normalises a resource to "jpeg" / "arw". A concrete file name is authoritative (stillObject may be
 * unknown or generic); stillObject is only consulted when the name has no recognised extension, and
 * the URL path is the last resort. Returns null for an unknown format.
 */
internal fun avOriginalFormat(fileName: String?, url: String, stillObject: String?): String? {
    extensionOf(fileName)?.let { return it }
    when (stillObject?.trim()?.lowercase()) {
        "jpeg", "jpg" -> return AV_CONTENT_FORMAT_JPEG
        "raw", "arw" -> return AV_CONTENT_FORMAT_RAW
    }
    return urlExtension(url)
}

/**
 * Builds a usable resource name. When the camera omits `fileName` the URL path is used, and if it
 * has no recognised extension a canonical suffix is appended, so a RAW resource never ends up with
 * an extensionless name (or a query/hash mistaken for one).
 */
private fun resourceName(fileName: String?, url: String, format: String): String {
    val base = fileName?.takeIf { it.isNotBlank() }
        ?: url.substringAfterLast('/').substringBefore('?').substringBefore('#').takeIf { it.isNotBlank() }
        ?: "original"
    if (extensionOf(base) != null) return base
    return base + if (format == AV_CONTENT_FORMAT_RAW) ".ARW" else ".JPG"
}

/** Extracts the format from the URL's path only, so a query/hash or a host TLD cannot fake it. */
private fun urlExtension(url: String): String? {
    val path = runCatching { URI(url).path }.getOrNull() ?: return null
    return extensionOf(path)
}

private fun extensionOf(value: String?): String? {
    val v = value?.trim()?.substringBefore('?')?.substringBefore('#') ?: return null
    if (v.isEmpty()) return null
    return when (v.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> AV_CONTENT_FORMAT_JPEG
        "arw", "raw" -> AV_CONTENT_FORMAT_RAW
        else -> null
    }
}

/** Reads a string field, treating a missing key and a JSON null as absent (never the literal "null"). */
private fun JSONObject.stringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key).trim().ifEmpty { null }
}

/** Numeric version comparison so "1.10" sorts above "1.3" (never lexicographic). */
internal fun compareVersions(a: String, b: String): Int {
    val pa = a.split('.')
    val pb = b.split('.')
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val va = pa.getOrNull(i)?.toIntOrNull() ?: 0
        val vb = pb.getOrNull(i)?.toIntOrNull() ?: 0
        if (va != vb) return va - vb
    }
    return 0
}

internal fun jsonLong(value: Any?): Long = when (value) {
    is Number -> value.toLong()
    is String -> value.trim().toLongOrNull() ?: 0L
    else -> 0L
}

internal fun jsonInt(value: Any?): Int = when (value) {
    is Number -> value.toInt()
    is String -> value.trim().toIntOrNull() ?: -1
    is JSONArray -> jsonInt(value.opt(0))
    else -> -1
}

/**
 * Runs [block], retrying up to [attempts] times only on a socket timeout. Any other failure is
 * rethrown immediately, so this never retries a completed JSON-RPC error response.
 */
internal suspend fun <T> retryOnTimeout(
    attempts: Int,
    delayMillis: Long = AvContentTransfer.TIMEOUT_RETRY_DELAY_MS,
    block: suspend () -> T
): T {
    var last: SocketTimeoutException? = null
    for (attempt in 1..attempts) {
        try {
            return block()
        } catch (e: SocketTimeoutException) {
            last = e
            if (attempt < attempts) delay(delayMillis)
        }
    }
    throw last ?: error("不可达")
}
