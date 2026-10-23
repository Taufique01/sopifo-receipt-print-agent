package com.sopifo.printagent.ui

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
        setContent {
            SopifoTheme {
                AppRoot(
                    viewModel,
                    onRequestPermissions = ::requestRuntimePermissions,
                    openRequest = openRequest.value,
                    onOpenHandled = { openRequest.value = null },
                )
            }
        }
        requestRuntimePermissions()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_OPEN)?.let { openRequest.value = it }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshDeviceFacts()
    }

    fun requestRuntimePermissions() {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    companion object {
        const val EXTRA_TEST_SHOW_WHEN_LOCKED = "test_show_when_locked"
        /** Which page to open (from a notification). */
        const val EXTRA_OPEN = "open"
        const val OPEN_ACTIVITY = "activity"
    }
}
