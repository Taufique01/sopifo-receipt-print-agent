package com.sopifo.printagent.ui

import android.net.Uri
import com.sopifo.printagent.ui.shell.AuthReturn
import com.sopifo.printagent.ui.shell.CopiedOrder
import com.sopifo.printagent.ui.shell.SharedFile

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.mutableStateOf
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.sopifo.printagent.BuildConfig
import com.sopifo.printagent.ui.theme.SopifoTheme

class MainActivity : FragmentActivity() {
    private val viewModel: AgentViewModel by viewModels()

    /** A page to open on (a print notification opens Activity); consumed by the UI. */
    private val openRequest = mutableStateOf<String?>(null)

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.onPermissionsChanged()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Debug builds only: lets on-device UI tests run on a phone with a secure lock screen.
        if (BuildConfig.DEBUG && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 &&
            intent?.getBooleanExtra(EXTRA_TEST_SHOW_WHEN_LOCKED, false) == true
        ) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        enableEdgeToEdge()
        openRequest.value = intent?.getStringExtra(EXTRA_OPEN)
        handleIncoming(intent)
        setContent {
            SopifoTheme {
                // Below the required version (Admin → App versions): only "Update Sopifo to continue".
                androidx.compose.foundation.layout.Box {
                    com.sopifo.printagent.ui.shell.UpdateGate {
                        AppRoot(
                            viewModel,
                            onRequestPermissions = ::requestRuntimePermissions,
                            openRequest = openRequest.value,
                            onOpenHandled = { openRequest.value = null },
                        )
                    }
                    // Share → auto-print: "Printing 3 … Cancel" over whatever is on screen.
                    com.sopifo.printagent.ui.shell.AutoPrintStrip()
                }
            }
        }
        requestRuntimePermissions()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_OPEN)?.let { openRequest.value = it }
        handleIncoming(intent)
    }

    /**
     * sopifo://auth?code=… (Facebook sign-in finished in Chrome), Share → Sopifo (PDF / image), and
     * a customer's message shared as text or picked in the text selection menu (→ Add order).
     */
    private fun handleIncoming(intent: Intent?) {
        intent ?: return
        val text = when (intent.action) {
            Intent.ACTION_SEND -> intent.takeIf { it.type?.startsWith("text/") == true }?.getCharSequenceExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            else -> null
        }
        if (!text.isNullOrBlank()) {
            CopiedOrder.share(text.toString())
            return
        }
        val data = intent.data
        if (intent.action == Intent.ACTION_VIEW && data?.scheme == "sopifo" && data.host == "auth") {
            data.getQueryParameter("code")?.takeIf { it.length in 20..8000 }?.let { AuthReturn.code.value = it }
            return
        }
        val shared: Uri? = when (intent.action) {
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            Intent.ACTION_VIEW -> data?.takeIf { it.scheme == "content" || it.scheme == "file" }
            else -> null
        }
        if (shared != null) {
            val type = intent.type ?: contentResolver.getType(shared).orEmpty()
            val item = SharedFile.Item(shared, isPdf = type.contains("pdf") || shared.toString().endsWith(".pdf", ignoreCase = true))
            // 2.1.20: print at once (the seller said Yes once); else Quick print opens with it.
            val c = (application as com.sopifo.printagent.SopifoApp).container
            val auto = com.sopifo.printagent.ui.shell.AppSettings.get(this).autoPrintShared.value &&
                com.sopifo.printagent.ui.shell.SharedAutoPrint.begin(this, c, item, signedIn = com.sopifo.printagent.ui.shell.WebSession.isLoggedIn(c))
            if (!auto) SharedFile.item.value = item
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshDeviceFacts()
    }

    /** Set when the seller left the app: coming back may bring a customer's message on the clipboard. */
    private var wasAway = false

    override fun onStop() {
        super.onStop()
        wasAway = true
        CopiedOrder.markClipboardSeen(this)
    }

    // Android lets the app read the clipboard only once its window has focus (not yet in onResume).
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && wasAway) {
            wasAway = false
            CopiedOrder.checkClipboard(this)
        }
    }

    fun requestRuntimePermissions() {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
            // Language starts in Bangla; the dashboard cookie follows the app's choice.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    companion object {
        const val EXTRA_TEST_SHOW_WHEN_LOCKED = "test_show_when_locked"
        /** Which page to open (from a notification). */
        const val EXTRA_OPEN = "open"
        const val OPEN_ACTIVITY = "activity"
        /** Print → Sopifo refused a print: open Printers / the login screen. */
        const val OPEN_PRINTERS = "printers"
        const val OPEN_LOGIN = "login"
    }
}
