package com.sopifo.printagent.ui

import android.net.Uri
import com.sopifo.printagent.ui.shell.AuthReturn
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
                com.sopifo.printagent.ui.shell.UpdateGate {
                    AppRoot(
                        viewModel,
                        onRequestPermissions = ::requestRuntimePermissions,
                        openRequest = openRequest.value,
                        onOpenHandled = { openRequest.value = null },
                    )
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

    /** sopifo://auth?code=… (Facebook sign-in finished in Chrome) and Share → Sopifo (PDF / image). */
    private fun handleIncoming(intent: Intent?) {
        intent ?: return
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
            SharedFile.item.value = SharedFile.Item(shared, isPdf = type.contains("pdf") || shared.toString().endsWith(".pdf", ignoreCase = true))
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshDeviceFacts()
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
