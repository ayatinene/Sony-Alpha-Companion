package io.github.ayatinene.sonycompanion

import io.github.ayatinene.sonycompanion.network.CameraHttp
import io.github.ayatinene.sonycompanion.protocol.CameraApiError
import io.github.ayatinene.sonycompanion.protocol.RemoteCamera
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Real JSON-RPC negotiation against old Sony getEvent versions; no camera is needed. */
class RemoteEventCompatibilityTest {
    private class LegacyCamera(
        private val versions: List<String>,
        private val advertisedEventVersion: String?,
        private val rejectHigherEvent: Int? = null
    ) : Dispatcher() {
        val requests = CopyOnWriteArrayList<Pair<String, String>>()
        val eventCalls = AtomicInteger(0)

        override fun dispatch(request: RecordedRequest): MockResponse {
            val json=JSONObject(request.body.readUtf8())
            val method=json.getString("method")
            val version=json.getString("version")
            requests += method to version
            val id=json.getInt("id")
            val body=when(method) {
                "getAvailableApiList" -> JSONObject().put("result",
                    JSONArray().put(JSONArray().put("getEvent").put("getVersions"))).put("id",id)
                "getVersions" -> JSONObject().put("result",JSONArray().put(JSONArray(versions))).put("id",id)
                "getMethodTypes" -> {
                    val queried=json.getJSONArray("params").getString(0)
                    val rows=JSONArray()
                    if(queried==advertisedEventVersion)
                        rows.put(JSONArray().put("getEvent").put("object").put(JSONArray()).put(queried))
                    // Sony getMethodTypes responds with 'results' (plural), not ordinary 'result'.
                    JSONObject().put("results",rows).put("id",id)
                }
                "getEvent" -> {
                    eventCalls.incrementAndGet()
                    if(rejectHigherEvent!=null && version!="1.0")
                        JSONObject().put("error",JSONArray().put(rejectHigherEvent).put("not supported")).put("id",id)
                    else JSONObject().put("result",JSONArray().put(JSONObject()
                        .put("type","cameraStatus").put("cameraStatus","IDLE"))).put("id",id)
                }
                else -> JSONObject().put("result",JSONArray().put(0)).put("id",id)
            }
            return MockResponse().setResponseCode(200).setBody(body.toString())
        }

        fun eventVersions(): List<String> =
            requests.filter { it.first=="getEvent" }.map { it.second }
    }

    @Test fun version10OnlyUsesOriginalGetEventWithoutMethodTypesProbe() = runBlocking {
        val fake=LegacyCamera(listOf("1.0"),null)
        MockWebServer().use { server ->
            server.dispatcher=fake
            CameraHttp().use { http ->
                val remote=RemoteCamera(server.url("/sony/camera").toString(),http)
                assertEquals("1.0",remote.initialize().eventVersion)
                assertEquals("IDLE",remote.events().first.status)
                assertEquals(listOf("1.0"),fake.eventVersions())
                assertFalse(fake.requests.any { it.first=="getMethodTypes" })
            }
        }
    }

    @Test fun legacy11IsNegotiatedEvenWhenNewerVersionsExist() = runBlocking {
        val fake=LegacyCamera(listOf("1.0","1.1","1.2","1.3"),"1.1")
        MockWebServer().use { server ->
            server.dispatcher=fake
            CameraHttp().use { http ->
                val remote=RemoteCamera(server.url("/sony/camera").toString(),http)
                assertEquals("1.1",remote.initialize().eventVersion)
                assertEquals("IDLE",remote.events().first.status)
                assertEquals(listOf("1.1"),fake.eventVersions())
                assertTrue(fake.requests.any { it.first=="getMethodTypes" })
            }
        }
    }

    @Test fun unadvertisedEventVersionConservativelyUses10() = runBlocking {
        val fake=LegacyCamera(listOf("1.0","1.1","1.2"),null)
        MockWebServer().use { server ->
            server.dispatcher=fake
            CameraHttp().use { http ->
                val remote=RemoteCamera(server.url("/sony/camera").toString(),http)
                assertEquals("1.0",remote.initialize().eventVersion)
                assertEquals("IDLE",remote.events().first.status)
                assertEquals(listOf("1.0"),fake.eventVersions())
            }
        }
    }

    @Test fun rejectedNewerEventFallsBackTo10AndKeepsUsingIt() = runBlocking {
        for(code in listOf(14,15)) {
            val fake=LegacyCamera(listOf("1.0","1.1"),"1.1",code)
            MockWebServer().use { server ->
                server.dispatcher=fake
                CameraHttp().use { http ->
                    val remote=RemoteCamera(server.url("/sony/camera").toString(),http)
                    assertEquals("1.1",remote.initialize().eventVersion)
                    assertEquals("IDLE",remote.events().first.status)
                    assertEquals("1.0",remote.state.eventVersion)
                    assertEquals("IDLE",remote.events().first.status)
                    assertEquals(listOf("1.1","1.0","1.0"),fake.eventVersions())
                }
            }
        }
    }
}
