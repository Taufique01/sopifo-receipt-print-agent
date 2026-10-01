package com.sopifo.printagent.data.api

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ApiClientTest {
    private val server = MockWebServer()
    private var reachable: Boolean? = null
    private var unauthorizedCalls = 0
    private lateinit var client: ApiClient
    private lateinit var baseUrl: String

    @Before fun setUp() {
        server.start()
        baseUrl = "http://localhost:${server.port}"
        client = ApiClient(
            baseUrlProvider = { baseUrl },
            tokenProvider = { "device-jwt" },
            urlPolicy = UrlPolicy(allowLocalhostHttp = true),
            serverClock = ServerClock(),
            listener = object : ApiClient.Listener {
                override fun onReachability(reachable: Boolean) { this@ApiClientTest.reachable = reachable }
                override fun onUnauthorized() { unauthorizedCalls++ }
            },
        )
    }

    @After fun tearDown() = server.shutdown()

    @Test fun registerSendsTokenWithoutAuthAndParsesResponse() = runTest {
        server.enqueue(MockResponse().setBody("""{"device_id":"dev_1","device_jwt":"jwt_abc","store_name":"Corner Shop","extra":1}"""))
        val res = client.register(baseUrl, RegisterRequest(token = "tok", deviceName = "Phone", appVersion = "1.0"))
        assertEquals("dev_1", res.deviceId)
        assertEquals("jwt_abc", res.deviceJwt)
        assertEquals("Corner Shop", res.storeName)
        val req = server.takeRequest()
        assertEquals("/api/devices/register", req.path)
        assertNull(req.getHeader("Authorization"))
        assertTrue(req.body.readUtf8().contains("\"token\":\"tok\""))
    }

    @Test fun registerAcceptsDataEnvelopeAndAlternateNames() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":{"id":"dev_2","token":"jwt_2","store_name":"S"}}"""))
        val res = client.register(baseUrl, RegisterRequest(token = "tok", deviceName = "P", appVersion = "1"))
        assertEquals("dev_2", res.deviceId)
        assertEquals("jwt_2", res.deviceJwt)
    }

    @Test fun registerRefusesPlainHttpToRemoteHosts() = runTest {
        try {
            client.register("http://evil.example.com", RegisterRequest(token = "tok", deviceName = "P", appVersion = "1"))
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun authenticatedCallsCarryBearerToken() = runTest {
        server.enqueue(MockResponse().setBody("{}"))
        client.heartbeat(HeartbeatRequest(55, PrinterStatusPayload("connected", "disconnected"), "1.0", "2026-09-30T00:00:00Z"))
        val req = server.takeRequest()
        assertEquals("/api/devices/heartbeat", req.path)
        assertEquals("Bearer device-jwt", req.getHeader("Authorization"))
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"battery_percent\":55"))
        assertTrue(body.contains("\"printer_status\":{\"receipt\":\"connected\",\"label\":\"disconnected\"}"))
        assertTrue(body.contains("\"app_version\":\"1.0\""))
        assertTrue(body.contains("\"last_seen\""))
        assertEquals(true, reachable)
    }

    @Test fun pendingJobsToleratesEnvelopesAndDropsMalformedEntries() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"jobs":[
                    {"id":"job_1","type":"receipt","status":"pending","image_url":"https://x/1.png","created_at":"2026-09-30T00:00:00Z"},
                    {"type":"label"},
                    "garbage",
                    {"id":"job_2","type":"qr_label","image_url":"https://x/2.png","created_at":"2026-09-30T00:00:00Z","unknown":true}
                ]}""",
            ),
        )
        val result = client.getPendingJobs()
        assertEquals(listOf("job_1", "job_2"), result.jobs.map { it.id })
        assertEquals(2, result.invalidEntries)
        assertEquals("/api/print-jobs/pending", server.takeRequest().path)
    }

    @Test fun pendingJobsAcceptsBareArray() = runTest {
        server.enqueue(MockResponse().setBody("""[{"id":"job_9","type":"label"}]"""))
        assertEquals("job_9", client.getPendingJobs().jobs.single().id)
    }

    @Test fun getJobUnwrapsDataEnvelope() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":{"id":"job_5","type":"barcode_label","status":"pending","image_url":"https://x/5.png","created_at":"2026-09-30T00:00:00Z","copies":2}}"""))
        val job = client.getJob("job_5")
        assertEquals("barcode_label", job.type)
        assertEquals(2, job.copies)
        assertEquals("/api/print-jobs/job_5", server.takeRequest().path)
    }

    @Test fun getJobDoesNotMistakeNestedDeviceForEnvelope() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"job_6","type":"receipt","status":"pending","image_url":"https://x/6.png","created_at":"2026-10-01T00:00:00Z","payload":{},"device":{"id":"dev_1","name":"HONOR X6c"}}"""))
        val job = client.getJob("job_6")
        assertEquals("job_6", job.id)
        assertEquals("receipt", job.type)
    }

    @Test fun rejectsPathTraversalJobIdsWithoutRequest() = runTest {
        try {
            client.getJob("../devices/register")
            fail("expected rejection")
        } catch (e: IllegalArgumentException) {
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun jobActionsHitTheContractEndpoints() = runTest {
        repeat(3) { server.enqueue(MockResponse().setBody("{}")) }
        client.completeJob("job_1", "2026-09-30T00:00:00Z")
        client.failJob("job_2", "printer offline")
        client.cancelJob("job_3")
        assertEquals("/api/print-jobs/job_1/complete", server.takeRequest().path)
        val fail = server.takeRequest()
        assertEquals("/api/print-jobs/job_2/fail", fail.path)
        assertTrue(fail.body.readUtf8().contains("printer offline"))
        val cancel = server.takeRequest()
        assertEquals("POST", cancel.method)
        assertEquals("/api/print-jobs/job_3/cancel", cancel.path)
    }

    @Test fun unauthorizedIsReportedAndNotRetryable() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        try {
            client.getPendingJobs()
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertTrue(e.unauthorized)
            assertFalse(e.retryable)
            assertEquals(1, unauthorizedCalls)
        }
    }

    @Test fun serverErrorsAreRetryable() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        try {
            client.getPendingJobs()
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertTrue(e.retryable)
        }
    }

    @Test fun malformedJsonBecomesApiException() = runTest {
        server.enqueue(MockResponse().setBody("<html>oops</html>"))
        try {
            client.getJob("job_1")
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals(200, e.httpCode)
            assertTrue(e.malformed)
        }
    }

    @Test fun contractViolationsBecomeMalformedApiException() = runTest {
        server.enqueue(MockResponse().setBody("""{"type":"receipt"}""")) // no id
        try {
            client.getJob("job_1")
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertTrue(e.malformed)
            assertFalse(e.retryable)
        }
    }

    @Test fun networkFailureMarksCloudUnreachable() = runTest {
        server.shutdown()
        try {
            client.getPendingJobs()
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertNull(e.httpCode)
            assertTrue(e.retryable)
            assertEquals(false, reachable)
        }
    }

    @Test fun imageDownloadSendsJwtOnlyToApiHost() = runTest {
        server.enqueue(MockResponse().setBody(Buffer().write(byteArrayOf(1, 2, 3))))
        server.enqueue(MockResponse().setBody(Buffer().write(byteArrayOf(4, 5))))
        client.downloadImage("$baseUrl/images/1.png", 1024)
        // Same server, different host name: treated as a third-party host.
        client.downloadImage("http://127.0.0.1:${server.port}/images/2.png", 1024)
        assertEquals("Bearer device-jwt", server.takeRequest().getHeader("Authorization"))
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test fun imageDownloadEnforcesSizeCap() = runTest {
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(2048))))
        try {
            client.downloadImage("$baseUrl/images/big.png", 1024)
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertTrue(e.message!!.contains("large") || e.message!!.contains("exceeds"))
        }
    }

    @Test fun serverDateHeaderUpdatesClock() = runTest {
        val clock = ServerClock(deviceNow = { 0L })
        val c = ApiClient({ baseUrl }, { "t" }, UrlPolicy(true), clock)
        server.enqueue(MockResponse().setBody("[]").setHeader("Date", "Wed, 30 Sep 2026 12:00:00 GMT"))
        c.getPendingJobs()
        assertTrue(clock.hasSample)
        assertEquals("2026-09-30T12:00:00Z", clock.now().toString())
    }
}
