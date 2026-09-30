package com.sopifo.printagent.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.sopifo.printagent.BuildConfig
import com.sopifo.printagent.ui.theme.SopifoTheme

class MainActivity : ComponentActivity() {
    private val viewModel: AgentViewModel by viewModels()

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
        setContent {
            SopifoTheme {
                AppRoot(viewModel, onRequestPermissions = ::requestRuntimePermissions)
            }
        }
        requestRuntimePermissions()
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
    }
}
