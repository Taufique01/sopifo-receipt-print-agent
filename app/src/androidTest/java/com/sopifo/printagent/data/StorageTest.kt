package com.sopifo.printagent.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sopifo.printagent.data.db.AppDatabase
import com.sopifo.printagent.data.db.DeviceConfigEntity
import com.sopifo.printagent.data.db.PrinterConfigEntity
import com.sopifo.printagent.data.db.PrinterProtocol
import com.sopifo.printagent.data.db.PrinterRole
import com.sopifo.printagent.data.secure.KeystoreTokenStore
import com.sopifo.printagent.jobs.JobRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseTest {
    private lateinit var db: AppDatabase
    private var now = 1_000_000L

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
    }

    @After fun tearDown() = db.close()

    @Test fun recentJobsKeepOnlyTheLast20() = runTest {
        val repo = JobRepository(db.printedJobDao(), db.recentJobDao()) { now }
        repeat(35) { i ->
            now += 1000
            repo.recordRecent("job_$i", "receipt", "printed")
        }
        val all = db.recentJobDao().getAll()
        assertEquals(20, all.size)
        assertEquals("job_34", all.first().jobId)
        assertEquals("job_15", all.last().jobId)
        assertEquals(20, repo.observeRecent().first().size)
    }

    @Test fun updatingARecentJobDoesNotDuplicateIt() = runTest {
        val repo = JobRepository(db.printedJobDao(), db.recentJobDao()) { now }
        repo.recordRecent("job_1", "label", "printing")
        now += 1
        repo.recordRecent("job_1", "label", "printed")
        val all = db.recentJobDao().getAll()
        assertEquals(1, all.size)
        assertEquals("printed", all.single().status)
    }

    @Test fun claimIsAtomicAndIdempotent() = runTest {
        val repo = JobRepository(db.printedJobDao(), db.recentJobDao()) { now }
        assertFalse(repo.isHandled("job_1"))
        assertTrue(repo.claim("job_1"))
        assertFalse(repo.claim("job_1"))
        assertTrue(repo.isHandled("job_1"))
        repo.release("job_1")
        assertFalse(repo.isHandled("job_1"))
    }

    @Test fun interruptedClaimsAndPruning() = runTest {
        val repo = JobRepository(db.printedJobDao(), db.recentJobDao()) { now }
        repo.claim("stuck")
        repo.claim("done"); repo.markPrinted("done")
        now += 120_000
        assertEquals(listOf("stuck"), repo.interruptedClaims(olderThanMs = 60_000))
        now += JobRepository.RETENTION_MS + 1
        repo.prune()
        assertEquals(0, db.printedJobDao().count())
    }

    @Test fun printerAndDeviceConfigRoundTrip() = runTest {
        val printer = PrinterConfigEntity(PrinterRole.LABEL, "XP-P323B", "AA:BB:CC:DD:EE:FF", PrinterProtocol.TSPL, 400, 50, 30)
        db.printerConfigDao().upsert(printer)
        assertEquals(printer, db.printerConfigDao().get(PrinterRole.LABEL))
        assertNull(db.printerConfigDao().get(PrinterRole.RECEIPT))

        db.deviceConfigDao().upsert(DeviceConfigEntity(deviceId = "d1", storeName = "S", deviceName = "P", apiBaseUrl = "https://x", registeredAt = 1))
        db.deviceConfigDao().setLastSync(42)
        db.deviceConfigDao().setSessionValid(false)
        val cfg = db.deviceConfigDao().get()!!
        assertEquals(42L, cfg.lastSyncAt)
        assertFalse(cfg.sessionValid)
    }
}

@RunWith(AndroidJUnit4::class)
class TokenStoreTest {
    private val store = KeystoreTokenStore(ApplicationProvider.getApplicationContext())

    @After fun tearDown() = store.clear()

    @Test fun jwtRoundTripsThroughKeystoreAndIsNotStoredInPlaintext() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZpY2UtMSJ9.sig"
        assertTrue(store.save(jwt))
        // A fresh instance (no in-memory cache) must decrypt it.
        assertEquals(jwt, KeystoreTokenStore(ApplicationProvider.getApplicationContext()).load())
        val prefs = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSharedPreferences("secure_session", android.content.Context.MODE_PRIVATE)
        val raw = prefs.all.values.joinToString()
        assertFalse(raw.contains("eyJ"))
    }

    @Test fun clearRemovesSession() {
        store.save("token-1")
        store.clear()
        assertNull(KeystoreTokenStore(ApplicationProvider.getApplicationContext()).load())
    }
}
