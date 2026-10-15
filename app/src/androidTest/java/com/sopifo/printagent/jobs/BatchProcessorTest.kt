package com.sopifo.printagent.jobs

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sopifo.printagent.data.api.ApiClient
import com.sopifo.printagent.data.api.ServerClock
import com.sopifo.printagent.data.api.UrlPolicy
import com.sopifo.printagent.data.db.AppDatabase
import com.sopifo.printagent.data.db.PrinterConfigEntity
import com.sopifo.printagent.data.db.PrinterProtocol
import com.sopifo.printagent.data.db.PrinterRole
import com.sopifo.printagent.print.PrintDataBuilder
import com.sopifo.printagent.print.PrinterManager
import com.sopifo.printagent.print.testPng
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.Collections

/**
 * Backend for one bulk print batch, behaving like the real one: each fetch reveals (renders)
 * jobs until [ahead] are waiting, completed jobs drop out, and the batch is done once every job
 * is revealed and none are waiting.
 */
class FakeBatchBackend(private val jobIds: List<String>, private val ahead: Int = 2) {
    val server = MockWebServer()
    val png = testPng(400, 240)
    val completed = Collections.synchronizedList(mutableListOf<String>())
    val failed = Collections.synchronizedList(mutableListOf<String>())
    val fetches = Collections.synchronizedList(mutableListOf<Int>())
    @Volatile var status = "active"
    @Volatile var expiresAt: Instant = Instant.now().plusSeconds(120)
    /** Called after each completion, e.g. to cancel the batch midway. */
    @Volatile var onComplete: (Int) -> Unit = {}
    private var revealed = 0

    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path == "/api/print-batches/b1" -> batch()
                    path.endsWith("/complete") -> {
                        completed += path.removePrefix("/api/print-jobs/").removeSuffix("/complete")
                        onComplete(completed.size)
                        MockResponse().setBody("{}")
                    }
                    path.endsWith("/fail") -> {
                        failed += path.removePrefix("/api/print-jobs/").removeSuffix("/fail")
                        MockResponse().setBody("{}")
                    }
                    path.startsWith("/images/") -> MockResponse().setBody(Buffer().write(png))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @Synchronized private fun batch(): MockResponse {
        val waiting = { jobIds.take(revealed).filter { it !in completed && it !in failed } }
        if (status == "active") while (revealed < jobIds.size && waiting().size < ahead) revealed++
        fetches += revealed
        val jobs = if (status == "active") waiting() else emptyList()
        val json = jobs.joinToString(",") { id ->
            """{"id":"$id","type":"label","status":"pending","image_url":"$baseUrl/images/$id.png",
               "created_at":"${Instant.now()}","expires_at":"$expiresAt","batch_index":${jobIds.indexOf(id)}}"""
        }
        return MockResponse().setBody(
            """{"id":"b1","status":"$status","expires_at":"$expiresAt","total":${jobIds.size},
               "rendered_all":${revealed == jobIds.size},"jobs":[$json]}""",
        )
    }

    val baseUrl get() = "http://localhost:${server.port}"
}

@RunWith(AndroidJUnit4::class)
class BatchProcessorTest {
    private lateinit var db: AppDatabase
    private lateinit var backend: FakeBatchBackend
    private val transport = FakeTransport()
    private val reporter = RecordingReporter()
    private lateinit var processor: JobProcessor
    private lateinit var jobs: JobRepository

    private val ids = (1..6).map { "job_$it" }

    @Before fun setUp() = runBlocking {
        backend = FakeBatchBackend(ids).also { it.start() }
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        db.printerConfigDao().upsert(PrinterConfigEntity(PrinterRole.LABEL, "Label", "00:11:22:33:44:66", PrinterProtocol.TSPL, 400, 50, 30))
        val printers = PrinterManager(db.printerConfigDao(), transport, PrintDataBuilder(), object : PrinterManager.Environment {
            override fun isBluetoothOn() = true
            override fun hasPermission() = true
        })
        jobs = JobRepository(db.printedJobDao(), db.recentJobDao())
        val clock = ServerClock()
        val api = ApiClient({ backend.baseUrl }, { "jwt" }, UrlPolicy(allowLocalhostHttp = true), clock)
        processor = JobProcessor(api, jobs, printers, reporter, clock)
    }

    @After fun tearDown() {
        db.close()
        backend.server.shutdown()
    }

    @Test fun printsEveryJobInOrderOverOneConnection() = runBlocking {
        assertEquals(BatchOutcome.DONE, processor.processBatch("b1", "fcm"))
        assertEquals(6, transport.count(PrinterRole.LABEL))
        assertEquals(ids, backend.completed.toList())
        // Jobs kept arriving while printing, so the printer link stayed open.
        assertEquals(1, transport.opens)
        // The backend rendered ahead on fetches made while printing.
        assertTrue(backend.fetches.size >= 3)
    }

    @Test fun runningAgainNeverReprints() = runBlocking {
        processor.processBatch("b1", "fcm")
        backend.completed.clear() // backend lost the reports: jobs look pending again
        assertEquals(BatchOutcome.DONE, processor.processBatch("b1", "pending_sync"))
        assertEquals(6, transport.count(PrinterRole.LABEL))
    }

    @Test fun stopsWhenTheSellerCancels() = runBlocking {
        backend.onComplete = { done -> if (done == 2) backend.status = "cancelled" }
        assertEquals(BatchOutcome.CANCELLED, processor.processBatch("b1", "fcm"))
        // Already-downloaded jobs may finish, but the batch never runs to the end.
        assertTrue(transport.count(PrinterRole.LABEL) in 2..4)
    }

    @Test fun expiredBatchPrintsNothing() = runBlocking {
        backend.expiresAt = Instant.now().minusSeconds(5)
        assertEquals(BatchOutcome.EXPIRED, processor.processBatch("b1", "fcm"))
        assertEquals(0, transport.count(PrinterRole.LABEL))
    }

    @Test fun writeFailureStopsTheBatchAndReportsTheJob() = runBlocking {
        transport.failWrites = true
        assertEquals(BatchOutcome.PRINTER_FAILED, processor.processBatch("b1", "fcm"))
        assertEquals("job_1", reporter.failed.single().first)
        // Bytes may have reached the printer: the job is never retried automatically.
        assertTrue(jobs.isHandled("job_1"))
    }

    @Test fun unreachablePrinterStopsWithoutPrinting() = runBlocking {
        transport.openFailuresRemaining = 10
        assertEquals(BatchOutcome.PRINTER_FAILED, processor.processBatch("b1", "fcm"))
        assertEquals(0, transport.count(PrinterRole.LABEL))
        assertTrue(reporter.failed.single().second.contains("printer not reachable"))
    }

    @Test fun pendingSyncHandsLiveBatchesToTheScheduler() = runBlocking {
        val server = MockWebServer().apply {
            enqueue(MockResponse().setBody("""{"jobs":[],"batches":["b1"]}"""))
            start()
        }
        val found = mutableListOf<String>()
        val clock = ServerClock()
        val api = ApiClient({ "http://localhost:${server.port}" }, { "jwt" }, UrlPolicy(allowLocalhostHttp = true), clock)
        val printers = PrinterManager(db.printerConfigDao(), transport, PrintDataBuilder(), object : PrinterManager.Environment {
            override fun isBluetoothOn() = true
            override fun hasPermission() = true
        })
        JobProcessor(api, jobs, printers, reporter, clock, onBatchesPending = { found += it }).syncPending("boot")
        assertEquals(listOf("b1"), found)
        server.shutdown()
    }
}
