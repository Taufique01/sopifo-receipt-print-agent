package com.sopifo.printagent.data.api

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** The app's own pages: orders / parcels lists, images drawn on request, and print results. */
class PrintPagesApiTest {
    private val server = MockWebServer()
    private lateinit var client: ApiClient

    @Before fun setUp() {
        server.start()
        client = ApiClient(
            baseUrlProvider = { "http://localhost:${server.port}" },
            tokenProvider = { "device-jwt" },
            urlPolicy = UrlPolicy(allowLocalhostHttp = true),
            serverClock = ServerClock(),
        )
    }

    @After fun tearDown() = server.shutdown()

    @Test fun listOrdersAsksForAPageAndReadsTheLeanRows() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"orders":[{"id":"o1","number":"GB-1042","status":"PENDING","customer":"Rahim","phone":"01711000000",
                "invoice_printed":true,"label_printed":false,"courier":{"provider":"STEADFAST","consignment_id":"123"}},
                {"id":"o2","number":"GB-1043","status":"PACKED","courier":null}],
                "counts":{"PENDING":1,"PACKED":1},"total":2,"page":1,"page_count":1}""",
            ),
        )
        val page = client.listOrders("PENDING", 2, 30)
        val req = server.takeRequest()
        assertTrue(req.path!!.startsWith("/api/device/orders?"))
        assertEquals("2", req.requestUrl!!.queryParameter("page"))
        assertEquals("30", req.requestUrl!!.queryParameter("page_size"))
        assertEquals("PENDING", req.requestUrl!!.queryParameter("status"))
        assertEquals("Bearer device-jwt", req.getHeader("Authorization"))
        assertEquals(2, page.orders.size)
        assertEquals("STEADFAST", page.orders[0].courier!!.provider)
        assertTrue(page.orders[0].invoicePrinted)
        assertEquals(null, page.orders[1].courier)
        assertEquals(1, page.counts["PACKED"])
    }

    @Test fun listParcelsReadsCardsCountsAndNewLabels() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"parcels":[{"id":"p1","provider":"PATHAO","status":"BOOKED","consignment_id":"DL1","invoice":"INV-9",
                "cod_amount":1250.5,"from_courier_panel":true,"label_printed":false}],
                "counts":{"awaiting":1,"all":3},"new_labels":[{"id":"p1","number":"INV-9"}],"page":1,"page_count":1}""",
            ),
        )
        val page = client.listParcels("pathao", "all", 1, 50)
        assertEquals("pathao", server.takeRequest().requestUrl!!.queryParameter("courier"))
        assertEquals("INV-9", page.parcels[0].title)
        assertEquals(3, page.counts.all)
        assertEquals("p1", page.newLabels.single().id)
    }

    @Test fun renderReturnsTheImageAndWhichPrinterItIsFor() = runTest {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3)
        server.enqueue(MockResponse().setBody(Buffer().write(png)).setHeader("Content-Type", "image/png").setHeader("X-Print-Printer", "receipt"))
        val image = client.renderOrder("o1", "invoice")
        val req = server.takeRequest()
        assertEquals("/api/device/orders/o1/render?doc=invoice", req.path)
        assertEquals("Bearer device-jwt", req.getHeader("Authorization"))
        assertArrayEquals(png, image.png)
        assertEquals("receipt", image.printer)
    }

    @Test fun theServersOwnErrorMessageReachesTheSeller() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"Order not found"}"""))
        try {
            client.renderOrder("gone", "label")
            fail("expected an error")
        } catch (e: ApiException) {
            assertEquals("Order not found", e.message)
            assertTrue(e.notFound)
        }
        server.enqueue(MockResponse().setResponseCode(502).setBody("""{"error":"Steadfast is not answering"}"""))
        try {
            client.syncParcels("steadfast")
            fail("expected an error")
        } catch (e: ApiException) {
            assertEquals("Steadfast is not answering", e.message)
        }
    }

    @Test fun printResultsAreSentAsOneReport() = runTest {
        server.enqueue(MockResponse().setBody("""{"recorded":2,"ignored":0}"""))
        client.reportPrintResults(
            PrintResultsRequest(
                "run-1",
                listOf(
                    PrintResultItemDto("order", "o1", "label", "printed"),
                    PrintResultItemDto("parcel", "p1", "label", "failed", "Printer not reachable", attempt = 2),
                ),
            ),
        )
        val req = server.takeRequest()
        assertEquals("/api/device/print-results", req.path)
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"run\":\"run-1\""))
        assertTrue(body.contains("\"reason\":\"Printer not reachable\""))
        assertTrue(body.contains("\"attempt\":2"))
    }

    @Test fun lockedPrintingIsA402WithTheMessageAndUnlockLinkNotASignOut() = runTest {
        var signedOut = false
        val locked = ApiClient(
            baseUrlProvider = { "http://localhost:${server.port}" },
            tokenProvider = { "device-jwt" },
            urlPolicy = UrlPolicy(allowLocalhostHttp = true),
            serverClock = ServerClock(),
            listener = object : ApiClient.Listener { override fun onUnauthorized(code: String?) { signedOut = true } },
        )
        val body = """{"error":"Courier and bulk printing is locked. Unlock it once for ৳49.","code":"PRINT_LOCKED",
            "price":49,"unlock_url":"http://localhost:${server.port}/dashboard/print?unlock=1"}"""
        server.enqueue(MockResponse().setResponseCode(402).setBody(body))
        server.enqueue(MockResponse().setResponseCode(402).setBody(body))

        for (call in listOf<suspend () -> Unit>({ locked.listParcels("steadfast", "awaiting", 1, 50) }, { locked.renderParcel("p1") })) {
            try {
                call()
                fail("expected PRINT_LOCKED")
            } catch (e: ApiException) {
                assertTrue(e.printLocked)
                assertTrue(e.message!!.startsWith("Courier and bulk printing is locked"))
                assertEquals("http://localhost:${server.port}/dashboard/print?unlock=1", e.unlockUrl)
                assertEquals(49, e.price)
                assertTrue(!e.unauthorized)
            }
        }
        assertTrue("402 must not sign the device out", !signedOut)
    }
}
