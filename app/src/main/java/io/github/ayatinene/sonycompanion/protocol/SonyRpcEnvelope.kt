package io.github.ayatinene.sonycompanion.protocol

import org.json.JSONArray
import org.json.JSONObject

/**
 * Sony's getMethodTypes response is not shaped like the ordinary ScalarWebAPI result:
 * real ILCE-7RM2 firmware returns {"results":[[method,params,returns,version],...]}.
 * Accept the legacy singular envelope for compatibility, but never treat an arbitrary
 * method's missing result as a successful empty response.
 */
internal fun sonyRpcResult(json: JSONObject, method: String): JSONArray? =
    if (method == "getMethodTypes") json.optJSONArray("results") ?: json.optJSONArray("result")
    else json.optJSONArray("result")
