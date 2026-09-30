package com.sopifo.printagent.jobs

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sopifo.printagent.data.api.ApiClient
import com.sopifo.printagent.data.api.ServerClock
import com.sopifo.printagent.data.api.UrlPolicy
import com.sopifo.printagent.data.db.AppDatabase
import com.sopifo.printagent.data.db.PrintedState
import com.sopifo.printagent.data.db.PrinterConfigEntity
import com.sopifo.printagent.data.db.PrinterProtocol
import com.sopifo.printagent.data.db.PrinterRole
import com.sopifo.printagent.data.db.RecentJobStatus
import com.sopifo.printagent.print.PrintDataBuilder
import com.sopifo.printagent.print.PrintException
import com.sopifo.printagent.print.PrintFailure
import com.sopifo.printagent.print.PrinterConnection
import com.sopifo.printagent.print.PrinterManager
import com.sopifo.printagent.print.PrinterState
import com.sopifo.printagent.print.PrinterTransport
import com.sopifo.printagent.print.testPng
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
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
import java.util.concurrent.ConcurrentHashMap

/** Fake Bluetooth: records what each printer received and can simulate failures. */
class FakeTransport : PrinterTransport {
    val received = Collections.synchronizedList(mutableListOf<Pair<PrinterRole, ByteArray>>())
    @Volatile var openFailuresRemaining = 0
    @Volatile var failOpenPermanently: PrintFailure? = null
    @Volatile var failWrites = false
    @Volatile var writeDelayMs = 0L
    @Volatile var opens = 0

    override suspend fun open(printer: PrinterConfigEntity): PrinterConnection {
        opens++
        failOpenPermanently?.let { throw PrintException(it, "simulated $it") }
        if (openFailuresRemaining > 0) {
            openFailuresRemaining--
            throw PrintException(PrintFailure.CONNECT_FAILED, "simulated connect failure")
        }
        return object : PrinterConnection {
            override suspend fun write(data: ByteArray) {
                if (writeDelayMs > 0) delay(writeDelayMs)
                if (failWrites) throw PrintException(PrintFailure.WRITE_FAILED, "simulated broken pipe")
                received += printer.role to data
            }

            override fun close() {}
        }
    }

    fun count(role: PrinterRole) = received.count { it.first == role }
}

class RecordingReporter : JobReporter {
    val completed = Collections.synchronizedList(mutableListOf<String>())
    val failed = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
    override fun reportCompleted(jobId: String, printedAtIso: String) { completed += jobId }
    override fun reportFailed(jobId: String, reason: String) { failed += jobId to reason }
}

/** Minimal Sopifo backend: jobs by id, a pending list, and PNG images. */
class FakeBackend {
    val server = MockWebServer()
    val jobs = ConcurrentHashMap<String, String>()
    val pending = Collections.synchronizedList(mutableListOf<String>())
    val brokenImages = ConcurrentHashMap.newKeySet<String>()
    val png = testPng(576, 120)
    @Volatile var dateHeader: String? = null
    val requests = Collections.synchronizedList(mutableListOf<String>())

    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                requests += "${request.method} $path"
                val resp = when {
                    path == "/api/print-jobs/pending" -> MockResponse().setBody("[" + pending.mapNotNull { jobs[it] }.joinToString(",") + "]")
                    path.startsWith("/api/print-jobs/") -> jobs[path.removePrefix("/api/print-jobs/")]?.let { MockResponse().setBody(it) } ?: MockResponse().setResponseCode(404)
                    path.startsWith("/images/") -> {
                        val id = path.removePrefix("/images/").removeSuffix(".png")
                        if (id in brokenImages) MockResponse().setResponseCode(500) else MockResponse().setBody(Buffer().write(png))
                    }
                    else -> MockResponse().setResponseCode(404)
                }
                dateHeader?.let { resp.setHeader("Date", it) }
                return resp
            }
        }
        server.start()
    }

    val baseUrl get() = "http://localhost:${server.port}"

    fun addJob(id: String, type: String, ageSeconds: Long = 5, status: String = "pending", inPending: Boolean = false, now: Instant = Instant.now()) {
        jobs[id] = """{"id":"$id","type":"$type","status":"$status","image_url":"$baseUrl/images/$id.png","created_at":"${now.minusSeconds(ageSeconds)}"}"""
        if (inPending) pending += id
    }
}

@RunWith(AndroidJUnit4::class)
class JobProcessorTest {
    private lateinit var db: AppDatabase
    private val backend = FakeBackend()
    private val transport = FakeTransport()
    private val reporter = RecordingReporter()
    private lateinit var printers: PrinterManager
    private lateinit var jobs: JobRepository
    private lateinit var processor: JobProcessor
    private var syncs = 0

    private fun processorWith(clock: ServerClock = ServerClock()): JobProcessor {
        val api = ApiClient({ backend.baseUrl }, { "jwt" }, UrlPolicy(allowLocalhostHttp = true), clock)
        return JobProcessor(api, jobs, printers, reporter, clock) { syncs++ }
    }

    @Before fun setUp() = runBlocking {
        backend.start()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        db.printerConfigDao().upsert(PrinterConfigEntity(PrinterRole.RECEIPT, "Receipt", "00:11:22:33:44:55", PrinterProtocol.ESC_POS, 576))
        db.printerConfigDao().upsert(PrinterConfigEntity(PrinterRole.LABEL, "Label", "00:11:22:33:44:66", PrinterProtocol.TSPL, 400, 50, 30))
        printers = PrinterManager(db.printerConfigDao(), transport, PrintDataBuilder(), object : PrinterManager.Environment {
            override fun isBluetoothOn() = true
            override fun hasPermission() = true
        })
        jobs = JobRepository(db.printedJobDao(), db.recentJobDao())
        processor = processorWith()
    }

    @After fun tearDown() {
        db.close()
        backend.server.shutdown()
    }

    private suspend fun recentStatus(id: String) = db.recentJobDao().getAll().firstOrNull { it.jobId == id }?.status

    @Test fun fcmWakePrintsJobOnceAndReportsCompletion() = runBlocking {
        backend.addJob("job_1", "receipt")
        assertEquals(JobOutcome.PRINTED, processor.processJobById("job_1", "fcm"))
        assertEquals(1, transport.count(PrinterRole.RECEIPT))
        assertEquals(listOf("job_1"), reporter.completed)
        assertEquals(RecentJobStatus.PRINTED, recentStatus("job_1"))
        assertEquals(PrinterState.CONNECTED, printers.status.value[PrinterRole.RECEIPT])
        assertTrue(syncs > 0)
        // The ESC/POS payload starts with ESC @.
        assertEquals(0x1B, transport.received.single().second[0].toInt())
    }

    @Test fun duplicateWakeUpsNeverPrintTwice() = runBlocking {
        backend.addJob("job_dup", "receipt", inPending = true)
        assertEquals(JobOutcome.PRINTED, processor.processJobById("job_dup", "fcm"))
        assertEquals(JobOutcome.DUPLICATE, processor.processJobById("job_dup", "fcm"))
        processor.syncPending("network_reconnect")
        assertEquals(1, transport.received.size)
        assertEquals(1, reporter.completed.size)
        // Duplicate FCM is resolved locally without hitting the backend again.
        assertEquals(1, backend.requests.count { it == "GET /api/print-jobs/job_dup" })
    }

    @Test fun concurrentFcmAndPendingSyncPrintOnce() = runBlocking {
        backend.addJob("job_race", "receipt", inPending = true)
        transport.writeDelayMs = 300
        val outcomes = listOf(
            async { processor.processJobById("job_race", "fcm") },
            async { processor.syncPending("app_start") },
            async { processor.processJobById("job_race", "fcm") },
        ).awaitAll()
        assertEquals(1, transport.received.size)
        assertEquals(1, reporter.completed.size)
        // Exactly one path printed it; the others saw it in progress or already printed.
        val printedBy = outcomes.count { it == JobOutcome.PRINTED || (it as? Map<*, *>)?.get(JobOutcome.PRINTED) == 1 }
        assertEquals(outcomes.toString(), 1, printedBy)
    }

    @Test fun jobsOlderThanTwoMinutesAreNeverPrinted() = runBlocking {
        backend.addJob("job_old", "receipt", ageSeconds = 180)
        backend.addJob("job_old2", "label", ageSeconds = 121, inPending = true)
        assertEquals(JobOutcome.EXPIRED, processor.processJobById("job_old", "fcm"))
        assertEquals(mapOf(JobOutcome.EXPIRED to 1), processor.syncPending("app_start"))
        assertEquals(0, transport.received.size)
        assertEquals(RecentJobStatus.SKIPPED_EXPIRED, recentStatus("job_old"))
    }

    @Test fun freshnessUsesServerClockNotDeviceClock() = runBlocking {
        val realNow = Instant.now()
        // Device clock 1 hour fast; backend Date header tells the truth.
        val skewed = processorWith(ServerClock(deviceNow = { System.currentTimeMillis() + 3_600_000 }))
        backend.dateHeader = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(realNow.atZone(java.time.ZoneOffset.UTC))
        backend.addJob("job_skew", "receipt", ageSeconds = 10, now = realNow)
        assertEquals(JobOutcome.PRINTED, skewed.processJobById("job_skew", "fcm"))
    }

    @Test fun pendingRecoveryRoutesEachTypeToTheRightPrinter() = runBlocking {
        backend.addJob("r1", "receipt", ageSeconds = 30, inPending = true)
        backend.addJob("s1", "scratchpad", ageSeconds = 25, inPending = true)
        backend.addJob("l1", "label", ageSeconds = 20, inPending = true)
        backend.addJob("b1", "barcode_label", ageSeconds = 15, inPending = true)
        backend.addJob("q1", "qr_label", ageSeconds = 10, inPending = true)
        val outcomes = processor.syncPending("boot")
        assertEquals(mapOf(JobOutcome.PRINTED to 5), outcomes)
        assertEquals(2, transport.count(PrinterRole.RECEIPT))
        assertEquals(3, transport.count(PrinterRole.LABEL))
        // Printed oldest first.
        assertEquals(listOf("r1", "s1", "l1", "b1", "q1"), reporter.completed)
        // Label jobs went out as TSPL.
        assertTrue(String(transport.received[2].second, Charsets.ISO_8859_1).startsWith("SIZE 50 mm,30 mm"))
    }

    @Test fun jobsThatAreNoLongerPendingAreSkipped() = runBlocking {
        backend.addJob("job_cancelled", "receipt", status = "cancelled")
        assertEquals(JobOutcome.NOT_PENDING, processor.processJobById("job_cancelled", "fcm"))
        assertEquals(0, transport.received.size)
    }

    @Test fun malformedJobsAreReportedNotCrashing() = runBlocking {
        backend.jobs["bad_type"] = """{"id":"bad_type","type":"invoice","status":"pending","image_url":"${backend.baseUrl}/images/x.png","created_at":"${Instant.now()}"}"""
        backend.jobs["no_image"] = """{"id":"no_image","type":"receipt","status":"pending","created_at":"${Instant.now()}"}"""
        backend.jobs["no_time"] = """{"id":"no_time","type":"receipt","status":"pending","image_url":"${backend.baseUrl}/images/x.png"}"""
        backend.jobs["garbage"] = """<html>502 Bad Gateway</html>"""
        assertEquals(JobOutcome.INVALID, processor.processJobById("bad_type", "fcm"))
        assertEquals(JobOutcome.INVALID, processor.processJobById("no_image", "fcm"))
        assertEquals(JobOutcome.INVALID, processor.processJobById("no_time", "fcm"))
        assertEquals(JobOutcome.INVALID, processor.processJobById("garbage", "fcm"))
        assertEquals(JobOutcome.INVALID, processor.processJobById("../../etc", "fcm"))
        assertEquals(JobOutcome.NOT_FOUND, processor.processJobById("missing", "fcm"))
        assertEquals(0, transport.received.size)
        assertEquals(setOf("bad_type", "no_image", "no_time", "garbage"), reporter.failed.map { it.first }.toSet())
    }

    @Test fun imageDownloadFailureIsReportedAndLeavesJobRetryable() = runBlocking {
        backend.addJob("job_img", "receipt")
        backend.brokenImages += "job_img"
        assertEquals(JobOutcome.FAILED, processor.processJobById("job_img", "fcm"))
        assertEquals(0, transport.received.size)
        assertEquals("job_img", reporter.failed.single().first)
        assertTrue(reporter.failed.single().second.contains("image download failed"))
        // Nothing was sent, so the job was never claimed.
        assertEquals(false, jobs.isHandled("job_img"))
    }

    @Test fun printerReconnectsAfterTransientFailures() = runBlocking {
        backend.addJob("job_retry", "receipt")
        transport.openFailuresRemaining = 2
        assertEquals(JobOutcome.PRINTED, processor.processJobById("job_retry", "fcm"))
        assertEquals(3, transport.opens)
        assertEquals(1, transport.received.size)
    }

    @Test fun unreachablePrinterFailsGracefullyAndStaysRetryable() = runBlocking {
        backend.addJob("job_off", "label")
        transport.openFailuresRemaining = 99
        assertEquals(JobOutcome.FAILED, processor.processJobById("job_off", "fcm"))
        assertEquals(PrinterState.DISCONNECTED, printers.status.value[PrinterRole.LABEL])
        assertEquals(false, jobs.isHandled("job_off"))
        assertEquals("job_off", reporter.failed.single().first)
    }

    @Test fun bluetoothOffFailsFastWithoutRetries() = runBlocking {
        backend.addJob("job_bt", "receipt")
        transport.failOpenPermanently = PrintFailure.BLUETOOTH_OFF
        assertEquals(JobOutcome.FAILED, processor.processJobById("job_bt", "fcm"))
        assertEquals(1, transport.opens)
        assertEquals(PrinterState.BLUETOOTH_OFF, printers.status.value[PrinterRole.RECEIPT])
    }

    @Test fun failedWriteIsNeverRetriedToAvoidDuplicates() = runBlocking {
        backend.addJob("job_write", "receipt", inPending = true)
        transport.failWrites = true
        assertEquals(JobOutcome.FAILED, processor.processJobById("job_write", "fcm"))
        transport.failWrites = false
        // A later recovery must not print it again: partial output may already be on paper.
        processor.syncPending("network_reconnect")
        assertEquals(0, transport.received.size)
        assertEquals(true, jobs.isHandled("job_write"))
    }

    @Test fun missingPrinterConfigIsReported() = runBlocking {
        db.printerConfigDao().delete(PrinterRole.LABEL)
        backend.addJob("job_nolabel", "qr_label")
        assertEquals(JobOutcome.FAILED, processor.processJobById("job_nolabel", "fcm"))
        assertTrue(reporter.failed.single().second.contains("no label printer"))
    }

    @Test fun interruptedPrintsAreReportedButNeverReprinted() = runBlocking {
        val oldClock = { System.currentTimeMillis() - 120_000 }
        JobRepository(db.printedJobDao(), db.recentJobDao(), oldClock).claim("job_crash")
        backend.addJob("job_crash", "receipt", inPending = true)
        processor.recoverInterrupted()
        assertEquals(PrintedState.PRINTED, db.printedJobDao().get("job_crash")!!.state)
        assertEquals("job_crash", reporter.failed.single().first)
        processor.syncPending("app_start")
        assertEquals(0, transport.received.size)
    }

    @Test fun pendingSyncSurvivesAMixOfGoodAndBadJobs() = runBlocking {
        backend.addJob("ok1", "receipt", inPending = true)
        backend.jobs["bad"] = """{"id":"bad","type":"nope","status":"pending","image_url":"x","created_at":"${Instant.now()}"}"""
        backend.pending += "bad"
        backend.addJob("ok2", "label", inPending = true)
        val outcomes = processor.syncPending("app_start")
        assertEquals(2, outcomes[JobOutcome.PRINTED])
        assertEquals(1, outcomes[JobOutcome.INVALID])
    }
}
