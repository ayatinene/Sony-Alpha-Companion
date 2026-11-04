package io.github.ayatinene.sonycompanion

import io.github.ayatinene.sonycompanion.network.CameraHttp
import io.github.ayatinene.sonycompanion.protocol.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList

/** JVM tests for the independent avContent protocol layer, exercised against MockWebServer. */
class AvContentTransferTest {

    private class FakeCamera(private val handler: (JSONObject) -> String?) : Dispatcher() {
        val requests = CopyOnWriteArrayList<JSONObject>()
        override fun dispatch(request: RecordedRequest): MockResponse {
            val body = JSONObject(request.body.readUtf8())
            requests += body
            val response = handler(body) ?: return MockResponse().setResponseCode(500)
            return MockResponse().setBody(response)
        }
    }

    private fun ok(result: String, id: Int) = """{"result":$result,"id":$id}"""
    private fun err(code: Int, message: String, id: Int) = """{"error":[$code,"$message"],"id":$id}"""
    private fun rowsJson(rows: List<Pair<String, String>>) =
        "[" + rows.joinToString(",") { (n, v) -> """["$n","object",[],"$v"]""" } + "]"

    private val standardRows = mapOf(
        "1.3" to listOf("getContentList" to "1.3"),
        "1.2" to listOf("getContentCount" to "1.2")
    )
    private val noCountRows = mapOf(
        "1.3" to listOf("getContentList" to "1.3"),
        "1.2" to emptyList<Pair<String, String>>()
    )

    private fun base(
        rows: Map<String, List<Pair<String, String>>>,
        versions: String = """[["1.0","1.1","1.2","1.3"]]"""
    ): (JSONObject) -> String? = { req ->
        when (req.getString("method")) {
            "getVersions" -> ok(versions, req.optInt("id"))
            "getMethodTypes" -> """{"results":${rowsJson(rows[req.getJSONArray("params").getString(0)].orEmpty())},"id":${req.optInt("id")}}"""
            else -> null
        }
    }

    /** A real-shaped content entry. */
    private fun item(uri: String, jpg: String, arw: String? = null, kind: String = "still"): String {
        val originals = buildString {
            append("""{"url":"http://cam/$jpg","fileName":"$jpg","fileSize":4259840}""")
            if (arw != null) append(""",{"url":"http://cam/$arw","fileName":"$arw","fileSize":36700160}""")
        }
        return """{"uri":"$uri","contentKind":"$kind","createdTime":"2024-01-02T03:04:05Z",""" +
            """"content":{"original":[$originals],""" +
            """"smallUrl":"http://cam/small_$jpg","largeUrl":"http://cam/large_$jpg","thumbnailUrl":"http://cam/thumb_$jpg"}}"""
    }

    /** getContentList returns result[0] as the item array, so the page body is double-nested. */
    private fun page(vararg items: String) = "[[" + items.joinToString(",") + "]]"

    private fun transfer(server: MockWebServer, http: CameraHttp) =
        AvContentTransfer(server.url("/sony/avContent").toString(), http)

    private fun methods(camera: FakeCamera) = camera.requests.map { it.getString("method") }

    // --- negotiation ---------------------------------------------------------------------------

    @Test fun negotiateResolvesCount12AndList13WithRealEnvelopeVersions() = runBlocking {
        val camera = FakeCamera(base(standardRows))
        MockWebServer().use { server ->
            server.dispatcher = camera
            CameraHttp().use { http ->
                val av = transfer(server, http)
                av.negotiate()
                assertEquals("1.2", av.countVersion)
                assertEquals("1.3", av.listVersion)
                assertTrue(av.countAvailable)

                val versions = camera.requests.single { it.getString("method") == "getVersions" }
                assertEquals("1.0", versions.getString("version"))
                assertEquals(0, versions.getJSONArray("params").length())

                val listTypes = camera.requests.first {
                    it.getString("method") == "getMethodTypes" && it.getJSONArray("params").getString(0) == "1.3"
                }
                assertEquals("1.0", listTypes.getString("version"))
                assertEquals("1.3", listTypes.getJSONArray("params").getString(0))
            }
        }
    }

    @Test fun listIsUnsupportedThrowsInsteadOfSendingBlindly() = runBlocking {
        val camera = FakeCamera(base(mapOf("1.0" to emptyList<Pair<String, String>>()), """[["1.0"]]"""))
        MockWebServer().use { server ->
            server.dispatcher = camera
            CameraHttp().use { http ->
                val av = transfer(server, http)
                try {
                    av.negotiate()
                    fail("getContentList unsupported must throw")
                } catch (e: AvContentUnsupported) {
                    assertTrue(e.message!!.contains("getContentList"))
                }
                // No getContentList request was ever sent.
                assertFalse(camera.requests.any { it.getString("method") == "getContentList" })
            }
        }
    }

    @Test fun countIsOptionalAndUnknownWhenMethodMissing() = runBlocking {
        val camera = FakeCamera(base(noCountRows))
        MockWebServer().use { server ->
            server.dispatcher = camera
            CameraHttp().use { http ->
                val av = transfer(server, http)
                av.negotiate()
                assertFalse(av.countAvailable)
                assertEquals(-1, av.count("storage"))
                assertFalse(camera.requests.any { it.getString("method") == "getContentCount" })
            }
        }
    }

    // --- count / schemes / sources -------------------------------------------------------------

    @Test fun getContentCountUsesObjectParamsAndCountObject() = runBlocking {
        val camera = FakeCamera { req ->
            base(standardRows)(req) ?: when (req.getString("method")) {
                "getContentCount" -> ok("""[{"count":1843}]""", req.optInt("id"))
                else -> null
            }
        }
        MockWebServer().use { server ->
            server.dispatcher = camera
            CameraHttp().use { http ->
                val av = transfer(server, http)
                av.negotiate()
                assertEquals(1843, av.count("storage"))
                val count = camera.requests.single { it.getString("method") == "getContentCount" }
                assertEquals("1.2", count.getString("version"))
                val params = count.getJSONArray("params")
                assertEquals(1, params.length())
                assertEquals("storage", params.getJSONObject(0).getString("uri"))
                assertEquals("flat", params.getJSONObject(0).getString("view"))
            }
        }
    }

    @Test fun schemeAndSourceListsParseObjects() = runBlocking {
        val camera = FakeCamera { req ->
            base(standardRows)(req) ?: when (req.getString("method")) {
                "getSchemeList" -> ok("""[[{"scheme":"storage"}]]""", req.optInt("id"))
                "getSourceList" -> ok("""[[{"source":"storage:memoryCard1"}]]""", req.optInt("id"))
                else -> null
            }
        }
        MockWebServer().use { server ->
            server.dispatcher = camera
            CameraHttp().use { http ->
                val av = transfer(server, http)
                assertEquals(listOf("storage"), av.schemes())
                assertEquals(listOf("storage:memoryCard1"), av.sources("storage"))
                val source = camera.requests.single { it.getString("method") == "getSourceList" }
                assertEquals("storage", source.getJSONArray("params").getJSONObject(0).getString("scheme"))
            }
        }
    }

    // --- getContentList shape / parsing --------------------------------------------------------

    @Test fun getContentListUsesRealObjectParamsAndParsesTopLevelAndInner() = runBlocking {
        val camera = FakeCamera { req ->
            base(standardRows)(req) ?: when (req.getString("method")) {
                "getContentCount" -> ok("""[{"count":1}]""", req.optInt("id"))
                "getContentList" -> ok(page(item("image:memorycard1:DSC00001.JPG", "DSC00001.JPG", "DSC00001.ARW")), req.optInt("id"))
                else -> null
            }
        }
        MockWebServer().use { server ->
            server.dispatcher = camera
            CameraHttp().use { http ->
                val items = transfer(server, http).list("storage")
                assertEquals(1, items.size)
                val entry = items.single()
                assertEquals("storage", entry.source)
                assertEquals("image:memorycard1:DSC00001.JPG", entry.contentId)
                assertEquals("DSC00001.JPG", entry.name)
                assertEquals("2024-01-02T03:04:05Z", entry.date)
                assertEquals(2, entry.original.size)
                assertEquals(AV_CONTENT_FORMAT_JPEG, entry.jpegOriginals.single().format)
                assertEquals(AV_CONTENT_FORMAT_RAW, entry.rawOriginals.single().format)
                assertEquals(4259840L, entry.jpegOriginals.single().size)
                assertEquals(36700160L, entry.rawOriginals.single().size)
                assertEquals("http://cam/large_DSC00001.JPG", entry.previewUrl)
                assertEquals("http://cam/thumb_DSC00001.JPG", entry.thumbnailUrl)

                val list = camera.requests.single { it.getString("method") == "getContentList" }
                assertEquals("1.3", list.getString("version"))
                val params = list.getJSONArray("params")
                assertEquals(1, params.length())
                val p = params.getJSONObject(0)
                assertEquals("storage", p.getString("uri"))
                assertEquals(0, p.getInt("stIdx"))
                assertEquals(20, p.getInt("cnt"))
                assertEquals("flat", p.getString("view"))
                assertEquals("descending", p.getString("sort"))
            }
        }
    }

    @Test fun dateViewUsesCameraProvidedFolderUriAndParsesBrowsableDates() = runBlocking {
        val dateUri = "storage:memoryCard1?path=2024-09-25"
        val camera = FakeCamera { req ->
            base(standardRows)(req) ?: when(req.getString("method")) {
                "getContentCount" -> ok("""[{"count":1}]""",req.optInt("id"))
                "getContentList" -> {
                    val entry = if(req.getJSONArray("params").getJSONObject(0).getString("uri")=="storage:memoryCard1")
                        """{"uri":"$dateUri","title":"2024-09-25","contentKind":"date","isBrowsable":"true"}"""
                    else item("image:one","DSC00001.JPG","DSC00001.ARW")
                    ok(page(entry),req.optInt("id"))
                }
                else -> null
            }
        }
        MockWebServer().use { server ->
            server.dispatcher=camera
            CameraHttp().use { http ->
                val av=transfer(server,http)
                assertEquals(1,av.count("storage:memoryCard1",AvContentTransfer.VIEW_DATE))
                val root=av.getContentPage("storage:memoryCard1",0,20,view=AvContentTransfer.VIEW_DATE)
                val folder=root.items.single()
                assertTrue(folder.isDateFolder)
                assertEquals("2024-09-25",folder.name)
                assertEquals(dateUri,folder.contentId)
                assertTrue(folder.original.isEmpty())
                val inner=av.getContentPage(folder.contentId,0,20,view=AvContentTransfer.VIEW_DATE)
                assertFalse(inner.items.single().isDateFolder)
                assertEquals(2,inner.items.single().original.size)
                val reads=camera.requests.filter { it.getString("method") in setOf("getContentCount","getContentList") }
                assertTrue(reads.all { it.getJSONArray("params").getJSONObject(0).getString("view")=="date" })
                assertEquals(dateUri,reads.last().getJSONArray("params").getJSONObject(0).getString("uri"))
            }
        }
    }

    @Test fun nameFallsBackToFirstOriginalWhenTopLevelNameAbsent() {
        val entry = parseAvContentItem(
            JSONObject("""{"uri":"u","contentKind":"still","content":{"original":[{"url":"http://cam/a.JPG","fileName":"a.JPG"}]}}"""),
            "storage"
        )!!
        assertEquals("a.JPG", entry.name)
        assertNull(entry.previewUrl)
        assertNull(entry.thumbnailUrl)
    }

    @Test fun jpegOnlyItemKeepsSingleJpegOriginal() {
        val entry = parseAvContentItem(JSONObject(item("u:1", "DSC1.JPG")), "storage")!!
        assertEquals(1, entry.original.size)
        assertEquals(AV_CONTENT_FORMAT_JPEG, entry.original.single().format)
        assertTrue(entry.rawOriginals.isEmpty())
    }

    @Test fun itemWithoutUsableOriginalIsSkipped() {
        assertNull(parseAvContentItem(JSONObject("""{"uri":"u","contentKind":"still","content":{"original":[]}}"""), "storage"))
        assertNull(parseAvContentItem(JSONObject("""{"uri":"u","contentKind":"still"}"""), "storage"))
        assertNull(parseAvContentItem(JSONObject("""{"uri":"u","contentKind":"still","content":{"original":[{"url":"http://cam/a.tiff","fileName":"a.tiff"}]}}"""), "storage"))
    }

    @Test fun unknownContentKindIsSkipped() {
        assertNull(parseAvContentItem(JSONObject(item("u:1", "MOV1.MP4", kind = "movie")), "storage"))
    }

    @Test fun jsonNullFieldsNeverBecomeLiteralNamesOrIdentities() {
        val entry = parseAvContentItem(
            JSONObject("""{"uri":"u:1","contentKind":null,"createdTime":null,"content":{"original":[{"url":"http://cam/a.JPG","fileName":null,"stillObject":null}],"largeUrl":null,"thumbnailUrl":null}}"""),
            "storage"
        )!!
        assertEquals("u:1", entry.contentId)
        assertEquals("", entry.date)
        assertEquals("a.JPG", entry.name)
        assertNull(entry.previewUrl)
        assertNull(entry.thumbnailUrl)
        assertEquals("", entry.folderNo)
    }

    @Test fun multipleSameFormatOriginalsAreAllKept() {
        val entry = parseAvContentItem(
            JSONObject("""{"uri":"u:1","contentKind":"still","content":{"original":[{"url":"http://cam/a1.JPG","fileName":"a1.JPG"},{"url":"http://cam/a2.JPG","fileName":"a2.JPG"},{"url":"http://cam/a.ARW","fileName":"a.ARW"}]}}"""),
            "storage"
        )!!
        assertEquals(3, entry.original.size)
        assertEquals(2, entry.jpegOriginals.size)
        assertEquals(1, entry.rawOriginals.size)
    }

    @Test fun folderNoIsParsedFromTopLevelForSameNameDisambiguation() {
        val entry = parseAvContentItem(
            JSONObject("""{"uri":"u:1","contentKind":"still","folderNo":"100MSDCF","content":{"original":[{"url":"http://cam/a.JPG","fileName":"a.JPG"}]}}"""),
            "storage"
        )!!
        assertEquals("100MSDCF", entry.folderNo)
    }

    // --- pagination ----------------------------------------------------------------------------

    @Test fun filteredPageWithReturnedItemsStillAdvances() = runBlocking {
        val pages = ArrayDeque(listOf(page(item("movie:1", "MOV0001.MP4", kind = "movie")), page(item("still:1", "DSC1.JPG")), "[[]]"))
        val camera = FakeCamera { req ->
            base(noCountRows)(req) ?: when (req.getString("method")) {
                "getContentList" -> ok(pages.removeFirstOrNull() ?: "[[]]", req.optInt("id"))
                else -> null
            }
        }
        MockWebServer().use { server ->
            server.dispatcher = camera
            CameraHttp().use { http ->
                val items = transfer(server, http).list("storage", pageSize = 1)
                assertEquals(1, items.size)
                assertEquals("still:1", items.single().contentId)
                assertEquals(3, methods(camera).count { it == "getContentList" })
            }
        }
    }

    @Test fun duplicateRawUriPageStopsPagination() = runBlocking {
        val repeated = page(item("still:1", "DSC1.JPG"))
        val pages = ArrayDeque(listOf(repeated, repeated, repeated))
        val camera = FakeCamera { req ->
            base(noCountRows)(req) ?: when (req.getString("method")) {
                "getContentList" -> ok(pages.removeFirstOrNull() ?: "[[]]", req.optInt("id"))
                else -> null
            }
        }
        MockWebServer().use { server ->
            server.dispatcher = camera
            CameraHttp().use { http ->
                val items = transfer(server, http).list("storage", pageSize = 1)
                assertEquals(1, items.size)
                assertEquals(2, methods(camera).count { it == "getContentList" })
            }
        }
    }

    @Test fun emptyPageStopsPagination() = runBlocking {
        val camera = FakeCamera { req ->
            base(noCountRows)(req) ?: when (req.getString("method")) {
                "getContentList" -> ok("[[]]", req.optInt("id"))
                else -> null
            }
        }
        MockWebServer().use { server ->
            server.dispatcher = camera
            CameraHttp().use { http ->
                assertTrue(transfer(server, http).list("storage").isEmpty())
                assertEquals(1, methods(camera).count { it == "getContentList" })
            }
        }
    }

    @Test fun shortPageStopsPagination() = runBlocking {
        val camera = FakeCamera { req ->
            base(standardRows)(req) ?: when (req.getString("method")) {
                "getContentCount" -> ok("""[{"count":1843}]""", req.optInt("id"))
                "getContentList" -> ok(page(item("still:1", "DSC1.JPG"), item("still:2", "DSC2.JPG")), req.optInt("id"))
                else -> null
            }
        }
        MockWebServer().use { server ->
            server.dispatcher = camera
            CameraHttp().use { http ->
                assertEquals(2, transfer(server, http).list("storage").size)
                assertEquals(1, methods(camera).count { it == "getContentList" })
            }
        }
    }

    @Test fun totalStopUsesRawContentProgressNotParsedCount() = runBlocking {
        // count declares 3 raw entries; the page returns 3 (1 filtered movie + 2 still). Parsed size
        // is 2, but raw progress is 3, so pagination must stop after the first page.
        val camera = FakeCamera { req ->
            base(standardRows)(req) ?: when (req.getString("method")) {
                "getContentCount" -> ok("""[{"count":3}]""", req.optInt("id"))
                "getContentList" -> ok(page(item("movie:1", "MOV.MP4", kind = "movie"), item("still:1", "DSC1.JPG"), item("still:2", "DSC2.JPG")), req.optInt("id"))
                else -> null
            }
        }
        MockWebServer().use { server ->
            server.dispatcher = camera
            CameraHttp().use { http ->
                assertEquals(2, transfer(server, http).list("storage", pageSize = 3).size)
                assertEquals(1, methods(camera).count { it == "getContentList" })
            }
        }
    }

    @Test fun overlappingPageDeduplicatesItemsByContentId() = runBlocking {
        // Page 2 overlaps page 1 on B; B must not be emitted twice while raw progress still advances.
        val pages = ArrayDeque(
            listOf(
                page(item("A", "A.JPG"), item("B", "B.JPG")),
                page(item("B", "B.JPG"), item("C", "C.JPG")),
                "[[]]"
            )
        )
        val camera = FakeCamera { req ->
            base(noCountRows)(req) ?: when (req.getString("method")) {
                "getContentList" -> ok(pages.removeFirstOrNull() ?: "[[]]", req.optInt("id"))
                else -> null
            }
        }
        MockWebServer().use { server ->
            server.dispatcher = camera
            CameraHttp().use { http ->
                val items = transfer(server, http).list("storage", pageSize = 2)
                assertEquals(listOf("A", "B", "C"), items.map { it.contentId })
                assertEquals(3, methods(camera).count { it == "getContentList" })
            }
        }
    }

    // --- errors --------------------------------------------------------------------------------

    @Test fun malformedContentListResultThrowsProtocolError() = runBlocking {
        for (malformed in listOf("""[]""", """[{"uri":"x"}]""")) {
            val camera = FakeCamera { req ->
                base(standardRows)(req) ?: when (req.getString("method")) {
                    "getContentCount" -> ok("""[{"count":1}]""", req.optInt("id"))
                    "getContentList" -> ok(malformed, req.optInt("id"))
                    else -> null
                }
            }
            MockWebServer().use { server ->
                server.dispatcher = camera
                CameraHttp().use { http ->
                    try {
                        transfer(server, http).list("storage")
                        fail("malformed result[0] must not read as an empty gallery")
                    } catch (_: AvContentProtocolError) {
                    }
                }
            }
        }
    }

    @Test fun malformedSchemeResultThrowsProtocolError() = runBlocking {
        val camera = FakeCamera { req ->
            base(standardRows)(req) ?: when (req.getString("method")) {
                "getSchemeList" -> ok("""[]""", req.optInt("id"))
                else -> null
            }
        }
        MockWebServer().use { server ->
            server.dispatcher = camera
            CameraHttp().use { http ->
                try {
                    transfer(server, http).schemes()
                    fail("malformed scheme result must throw")
                } catch (_: AvContentProtocolError) {
                }
            }
        }
    }

    @Test fun errorSevenOnContentListPropagates() = runBlocking {
        val camera = FakeCamera { req ->
            base(standardRows)(req) ?: when (req.getString("method")) {
                "getContentCount" -> ok("""[{"count":1843}]""", req.optInt("id"))
                "getContentList" -> err(7, "NotAvailableNow", req.optInt("id"))
                else -> null
            }
        }
        MockWebServer().use { server ->
            server.dispatcher = camera
            CameraHttp().use { http ->
                try {
                    transfer(server, http).list("storage")
                    fail("error 7 must propagate, not be treated as an empty listing")
                } catch (e: CameraApiError) {
                    assertEquals(7, e.code)
                }
            }
        }
    }

    @Test fun errorSevenOnSchemeListPropagates() = runBlocking {
        val camera = FakeCamera { req ->
            base(standardRows)(req) ?: when (req.getString("method")) {
                "getSchemeList" -> err(7, "NotAvailableNow", req.optInt("id"))
                else -> null
            }
        }
        MockWebServer().use { server ->
            server.dispatcher = camera
            CameraHttp().use { http ->
                try {
                    transfer(server, http).schemes()
                    fail("error 7 must propagate")
                } catch (e: CameraApiError) {
                    assertEquals(7, e.code)
                }
            }
        }
    }

    @Test fun errorSevenOnCountPropagates() = runBlocking {
        val camera = FakeCamera { req ->
            base(standardRows)(req) ?: when (req.getString("method")) {
                "getContentCount" -> err(7, "NotAvailableNow", req.optInt("id"))
                else -> null
            }
        }
        MockWebServer().use { server ->
            server.dispatcher = camera
            CameraHttp().use { http ->
                try {
                    transfer(server, http).count("storage")
                    fail("error 7 on count must propagate, not become -1")
                } catch (e: CameraApiError) {
                    assertEquals(7, e.code)
                }
            }
        }
    }

    @Test fun httpErrorPropagates() = runBlocking {
        val camera = FakeCamera { req -> if (req.getString("method") == "getVersions") null else base(standardRows)(req) }
        MockWebServer().use { server ->
            server.dispatcher = camera
            CameraHttp().use { http ->
                try {
                    transfer(server, http).negotiate()
                    fail("an HTTP failure must propagate")
                } catch (e: IOException) {
                    // CameraHttpError in production extends IOException.
                    assertNotNull(e)
                }
            }
        }
    }

    // --- pure helpers --------------------------------------------------------------------------

    @Test fun strictUrlValidationRejectsNonHttpOriginalsAndPreview() {
        assertNull(strictContentUrl("javascript:alert(1)"))
        assertNull(strictContentUrl("file:///etc/passwd"))
        assertNull(strictContentUrl("/relative/path.jpg"))
        assertNull(strictContentUrl("http://"))
        assertNull(strictContentUrl("  "))
        assertNull(strictContentUrl("http://user:pass@cam/a.jpg"))   // userinfo
        assertNull(strictContentUrl("http://cam/a.jpg#frag"))        // fragment
        assertNull(strictContentUrl("http://cam:99999/a.jpg"))       // port out of range
        assertNull(strictContentUrl("http://cam:0/a.jpg"))           // port zero
        assertEquals("https://cam/a.jpg", strictContentUrl("https://cam/a.jpg"))
        assertEquals("http://cam/a.jpg", strictContentUrl(" http://cam/a.jpg "))
    }

    @Test fun missingOrNegativeFileSizeIsZeroNotDropped() {
        val originals = parseAvOriginals(
            org.json.JSONArray("""[{"url":"http://cam/a.JPG","fileName":"a.JPG"},{"url":"http://cam/b.JPG","fileName":"b.JPG","fileSize":-5}]""")
        )
        assertEquals(listOf(0L, 0L), originals.map { it.size })
        assertEquals(AV_CONTENT_FORMAT_JPEG, originals.first().format)
        assertEquals("a.JPG", originals.first().name)
    }

    @Test fun formatDetectionPrefersFileNameAndNeverMistakesQueryOrHostForExtension() {
        // A concrete file name is authoritative even when stillObject is unknown or contradicts it.
        assertEquals(AV_CONTENT_FORMAT_JPEG, avOriginalFormat("a.JPG", "http://cam/a", "unknown"))
        assertEquals(AV_CONTENT_FORMAT_RAW, avOriginalFormat("a.ARW", "http://cam/a.JPG", "jpeg"))
        // No file name: stillObject, then only the URL path is consulted.
        assertEquals(AV_CONTENT_FORMAT_JPEG, avOriginalFormat(null, "http://cam/x", "jpeg"))
        assertEquals(AV_CONTENT_FORMAT_RAW, avOriginalFormat(null, "http://cam/x.ARW", null))
        assertNull(avOriginalFormat(null, "http://cam/x?token=.arw", null))  // query is not an extension
        assertNull(avOriginalFormat(null, "http://cdn.arw/photo", null))     // host TLD is not an extension
        assertNull(avOriginalFormat(null, "http://cam/photo", null))
    }

    @Test fun missingFileNameGetsCanonicalSuffixAndQueryIsNeverAnExtension() {
        val jpeg = parseAvOriginals(org.json.JSONArray("""[{"url":"http://cam/photo","stillObject":"jpeg"}]""")).single()
        assertEquals("photo.JPG", jpeg.name)
        val raw = parseAvOriginals(org.json.JSONArray("""[{"url":"http://cam/photo","stillObject":"raw"}]""")).single()
        assertEquals("photo.ARW", raw.name)
        assertEquals("DSC1.ARW", parseAvOriginals(org.json.JSONArray("""[{"url":"http://cam/DSC1.ARW"}]""")).single().name)
        // A URL query/hash is never an extension and, with no other hint, the resource is skipped.
        assertTrue(parseAvOriginals(org.json.JSONArray("""[{"url":"http://cam/photo?token=.arw"}]""")).isEmpty())
    }

    @Test fun itemWithoutRealUriIsSkippedSoNameNeverSubstitutesIdentity() {
        assertNull(
            parseAvContentItem(
                JSONObject("""{"contentKind":"still","content":{"original":[{"url":"http://cam/a.JPG","fileName":"a.JPG"}]}}"""),
                "storage"
            )
        )
    }

    @Test fun versionComparisonIsNumericNotLexicographic() {
        assertTrue(compareVersions("1.10", "1.3") > 0)
        assertTrue(compareVersions("1.3", "1.10") < 0)
        assertEquals(0, compareVersions("1.2", "1.2"))
    }

    @Test fun methodRowCarryingMinimumVersionStillResolves() = runBlocking {
        // getMethodTypes("1.3") reports getContentList with the minimum supported version "1.2".
        val rows = mapOf(
            "1.3" to listOf("getContentList" to "1.2"),
            "1.2" to listOf("getContentCount" to "1.2")
        )
        val camera = FakeCamera(base(rows))
        MockWebServer().use { server ->
            server.dispatcher = camera
            CameraHttp().use { http ->
                val av = transfer(server, http)
                av.negotiate()
                assertEquals("1.3", av.listVersion)
                assertEquals("1.2", av.countVersion)
            }
        }
    }

    @Test fun timeoutIsRetriedOnceThenSucceeds() = runBlocking {
        var calls = 0
        val value = retryOnTimeout(2, 1L) {
            calls++
            if (calls == 1) throw SocketTimeoutException("timeout")
            "ok"
        }
        assertEquals("ok", value)
        assertEquals(2, calls)
    }

    @Test fun persistentTimeoutIsSurfacedAfterBoundedAttempts() = runBlocking {
        var calls = 0
        try {
            retryOnTimeout(2, 1L) { calls++; throw SocketTimeoutException("timeout") }
            fail("a persistent timeout must not be swallowed")
        } catch (_: SocketTimeoutException) {
            assertEquals(2, calls)
        }
    }

    @Test fun nonTimeoutFailureIsNotRetried() = runBlocking {
        var calls = 0
        try {
            retryOnTimeout(2, 1L) { calls++; throw IllegalStateException("boom") }
            fail()
        } catch (_: IllegalStateException) {
            assertEquals(1, calls)
        }
    }

    // --- endpoint discovery --------------------------------------------------------------------

    @Test fun endpointIsResolvedFromAdvertisedAvContentService() {
        val descriptor = """<root xmlns:av="urn:schemas-sony-com:av"><device><friendlyName>α7R II</friendlyName>""" +
            """<av:X_ScalarWebAPI_ServiceList><av:X_ScalarWebAPI_Service>""" +
            """<av:X_ScalarWebAPI_ServiceType>camera</av:X_ScalarWebAPI_ServiceType>""" +
            """<av:X_ScalarWebAPI_ActionList_URL>/sony</av:X_ScalarWebAPI_ActionList_URL>""" +
            """</av:X_ScalarWebAPI_Service><av:X_ScalarWebAPI_Service>""" +
            """<av:X_ScalarWebAPI_ServiceType>avContent</av:X_ScalarWebAPI_ServiceType>""" +
            """<av:X_ScalarWebAPI_ActionList_URL>/sony</av:X_ScalarWebAPI_ActionList_URL>""" +
            """</av:X_ScalarWebAPI_Service></av:X_ScalarWebAPI_ServiceList></device></root>"""
        assertEquals(
            "http://192.168.122.1:64321/sony/avContent",
            AvContentTransfer.endpointFromDescriptor(descriptor, URI("http://192.168.122.1:64321/DmsDescPush.xml"))
        )
    }

    @Test fun endpointRespectsAdvertisedPathAlreadyEndingWithService() {
        val descriptor = """<root xmlns:av="urn:schemas-sony-com:av"><device>""" +
            """<av:X_ScalarWebAPI_ServiceList><av:X_ScalarWebAPI_Service>""" +
            """<av:X_ScalarWebAPI_ServiceType>avContent</av:X_ScalarWebAPI_ServiceType>""" +
            """<av:X_ScalarWebAPI_ActionList_URL>http://10.0.0.9:8080/api/avContent</av:X_ScalarWebAPI_ActionList_URL>""" +
            """</av:X_ScalarWebAPI_Service></av:X_ScalarWebAPI_ServiceList></device></root>"""
        assertEquals(
            "http://10.0.0.9:8080/api/avContent",
            AvContentTransfer.endpointFromDescriptor(descriptor, URI("http://10.0.0.9:8080/dd.xml"))
        )
    }

    @Test fun endpointIsNullWhenDescriptorHasNoAvContentService() {
        val descriptor = """<root xmlns:av="urn:schemas-sony-com:av"><device>""" +
            """<av:X_ScalarWebAPI_ServiceList><av:X_ScalarWebAPI_Service>""" +
            """<av:X_ScalarWebAPI_ServiceType>camera</av:X_ScalarWebAPI_ServiceType>""" +
            """<av:X_ScalarWebAPI_ActionList_URL>/sony</av:X_ScalarWebAPI_ActionList_URL>""" +
            """</av:X_ScalarWebAPI_Service></av:X_ScalarWebAPI_ServiceList></device></root>"""
        assertNull(AvContentTransfer.endpointFromDescriptor(descriptor, URI("http://cam/dd.xml")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun constructorRejectsNonHttpEndpoint() {
        AvContentTransfer("ftp://cam/avContent", CameraHttp())
    }
}
