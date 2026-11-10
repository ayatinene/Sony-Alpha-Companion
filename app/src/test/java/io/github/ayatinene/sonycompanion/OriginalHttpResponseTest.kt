package io.github.ayatinene.sonycompanion

import io.github.ayatinene.sonycompanion.data.StreamCopy
import io.github.ayatinene.sonycompanion.data.requireFullOriginalResponse
import io.github.ayatinene.sonycompanion.network.CameraHttp
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Exercises the real HTTP-original policy and StreamCopy over MockWebServer, not synthetic Responses. */
class OriginalHttpResponseTest {
    private suspend fun readOriginal(server: MockWebServer, http: CameraHttp): ByteArray =
        http.stream(server.url("/contentstransfer/orgraw/DSC00001.ARW").toString()) { response ->
            val expected = requireFullOriginalResponse(response)
            ByteArrayOutputStream().also { target ->
                requireNotNull(response.body).byteStream().use { input ->
                    StreamCopy.copy(input, target, expected)
                }
            }.toByteArray()
        }

    @Test fun completeHttp200OriginalIsCopiedByteForByte() = runBlocking {
        val original = ByteArray(131_087) { (it % 251).toByte() }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(original)))
            CameraHttp().use { http ->
                assertArrayEquals(original, readOriginal(server, http))
            }
        }
    }

    @Test fun http206IsRejectedBeforeItsPartialBodyIsSaved() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-3/8").setBody("abcd"))
            CameraHttp().use { http ->
                try {
                    readOriginal(server, http)
                    fail("HTTP 206 must not be accepted as a complete original")
                } catch (e: IOException) {
                    assertTrue(e.message.orEmpty().contains("HTTP 206"))
                }
            }
        }
    }

    @Test fun http200WithContentRangeIsAlsoRejected() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200)
                .setHeader("Content-Range", "bytes 0-3/8").setBody("abcd"))
            CameraHttp().use { http ->
                try {
                    readOriginal(server, http)
                    fail("a Content-Range header means the original is not trustworthy")
                } catch (e: IOException) {
                    assertTrue(e.message.orEmpty().contains("HTTP 200"))
                }
            }
        }
    }

    @Test fun aDisconnectedBodyWithAdvertisedContentLengthIsNotAccepted() = runBlocking {
        val original = ByteArray(262_144) { (it % 197).toByte() }
        MockWebServer().use { server ->
            // Advertise the full length, then close the socket midway through the response body.
            server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(original))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            CameraHttp().use { http ->
                try {
                    readOriginal(server, http)
                    fail("a truncated HTTP 200 response must fail")
                } catch (_: IOException) {
                    // Either OkHttp detects the incomplete body or StreamCopy detects a short read.
                }
            }
        }
    }
}
