package com.sopifo.printagent.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.sopifo.printagent.ui.AgentViewModel

@Composable
fun RegistrationScreen(vm: AgentViewModel) {
    val context = LocalContext.current
    val registering by vm.registering.collectAsStateWithLifecycle()
    val error by vm.registrationError.collectAsStateWithLifecycle()
    var token by rememberSaveable { mutableStateOf("") }
    var scanError by rememberSaveable { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Sopifo Print Agent", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Connect this phone to your store. Scan the device QR code from the Sopifo dashboard, or enter the registration token.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(
            onClick = {
                scanError = null
                // Google code scanner: no camera permission needed, runs in Play services.
                val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
                try {
                    GmsBarcodeScanning.getClient(context, options).startScan()
                        .addOnSuccessListener { code -> code.rawValue?.let(vm::register) }
                        .addOnFailureListener { scanError = "Scanner unavailable: ${it.message}. Enter the token instead." }
                } catch (e: Exception) {
                    scanError = "Scanner unavailable. Enter the token instead."
                }
            },
            enabled = !registering,
            modifier = Modifier.fillMaxWidth().testTag("scan_qr"),
        ) { Text("Scan QR code") }

        Text("or", style = MaterialTheme.typography.labelLarge)

        OutlinedTextField(
            value = token,
            onValueChange = { token = it.trim() },
            label = { Text("Registration token") },
            singleLine = true,
            enabled = !registering,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth().testTag("token_input"),
        )
        OutlinedButton(
            onClick = { vm.register(token) },
            enabled = token.isNotBlank() && !registering,
            modifier = Modifier.fillMaxWidth().testTag("register_button"),
        ) { Text("Register device") }

        if (registering) CircularProgressIndicator(Modifier.size(32.dp))
        (error ?: scanError)?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("registration_error"))
        }
        Spacer(Modifier.height(24.dp))
    }
}
