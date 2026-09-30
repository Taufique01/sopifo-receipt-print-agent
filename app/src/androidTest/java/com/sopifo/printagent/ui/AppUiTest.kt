package com.sopifo.printagent.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.sopifo.printagent.SopifoApp
import com.sopifo.printagent.data.RegistrationCode
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.Collections

@RunWith(AndroidJUnit4::class)
class AppUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @get:Rule val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        *buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray(),
    )

    private val container = (ApplicationProvider.getApplicationContext<SopifoApp>()).container
    private val server = MockWebServer()
    private val requests = Collections.synchronizedList(mutableListOf<String>())

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += "${request.method} ${request.path}"
                return when (request.path) {
                    "/api/devices/register" -> MockResponse().setBody("""{"device_id":"dev_ui","device_jwt":"jwt_ui","store_name":"UI Test Store"}""")
                    // Old enough that the agent itself will not print it, but the backend still lists it.
                    "/api/print-jobs/pending" -> MockResponse().setBody(
                        """[{"id":"job_ui_1","type":"qr_label","status":"pending","image_url":"http://localhost/x.png","created_at":"${Instant.now().minusSeconds(600)}"}]""",
                    )
                    else -> MockResponse().setBody("{}")
                }
            }
        }
        server.start()
        runBlocking { container.unregister() }
    }

    /** The test phone may be locked; debug builds can show the activity above the keyguard. */
    private fun launch(): ActivityScenario<MainActivity> = ActivityScenario.launch(
        Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_TEST_SHOW_WHEN_LOCKED, true),
    )

    /** Waits for a condition; on timeout fails with the UI tree and backend requests to make field debugging possible. */
    private fun waitOrDump(condition: () -> Boolean) {
        try {
            compose.waitUntil(10_000, condition)
        } catch (e: Throwable) {
            val tree = runCatching { compose.onRoot(useUnmergedTree = true).printToString() }.getOrDefault("<no tree>")
            throw AssertionError("Timed out.\nRequests: $requests\nUI:\n$tree", e)
        }
    }

    @After fun tearDown() {
        runBlocking { container.unregister() }
        server.shutdown()
    }

    @Test fun registrationScreenRejectsInvalidCodes() {
        launch().use {
            compose.onNodeWithTag("scan_qr").assertIsDisplayed()
            compose.onNodeWithTag("token_input").performTextInput("not/a valid;code")
            compose.onNodeWithTag("register_button").performClick()
            compose.onNodeWithTag("registration_error").assertIsDisplayed()
        }
    }

    @Test fun registeredDeviceShowsStatusAndCancelsSinglePendingJob() {
        runBlocking { container.device.register(RegistrationCode("ui-test-token", "http://localhost:${server.port}")) }
        launch().use {
            compose.waitUntil(10_000) { compose.onAllNodes(hasText("UI Test Store")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("cloud_status").assertIsDisplayed()
            compose.onNodeWithTag("receipt_status").assertIsDisplayed()
            compose.onNodeWithTag("label_status").assertIsDisplayed()
            compose.onNodeWithTag("battery").assertIsDisplayed()
            compose.onNodeWithTag("test_receipt").assertIsDisplayed()
            compose.onNodeWithTag("test_label").assertIsDisplayed()

            compose.onNodeWithTag("tab_pending").performClick()
            waitOrDump { compose.onAllNodes(hasTestTag("cancel_job_ui_1")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("job_ui_1").assertIsDisplayed()
            compose.onNodeWithTag("cancel_job_ui_1").performClick()
            compose.onNodeWithText("Cancel job").performClick()
            waitOrDump { requests.contains("POST /api/print-jobs/job_ui_1/cancel") }

            compose.onNodeWithTag("tab_diagnostics").performClick()
            compose.onNodeWithTag("diag_version").assertIsDisplayed()
            compose.onNodeWithTag("diag_fcm").assertIsDisplayed()
            compose.onNodeWithTag("diag_service").assertIsDisplayed()
        }
        assertTrue(requests.none { it.contains("clear") || it.contains("cancel-all") })
    }
}
