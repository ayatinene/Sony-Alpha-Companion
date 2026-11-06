package io.github.ayatinene.sonycompanion.data

import okhttp3.Response
import java.io.IOException

/**
 * A camera gallery original must be the complete resource, not an HTTP range response.
 * The response Content-Length is the authoritative expected count when it is positive;
 * StreamCopy verifies that count after reading the body.
 *
 * Unknown-length (chunked) bodies remain subject to non-empty/transport checks, but an
 * apparently clean EOF without a declared length cannot prove completeness.
 */
internal fun requireFullOriginalResponse(response: Response): Long {
    if (response.code != 200 || response.header("Content-Range") != null)
        throw IOException("相机未返回完整原文件（HTTP ${response.code}）")
    return requireNotNull(response.body) { "相机未返回文件内容" }.contentLength()
}
