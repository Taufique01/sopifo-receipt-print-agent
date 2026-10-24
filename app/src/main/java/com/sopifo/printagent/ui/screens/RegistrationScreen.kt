package com.sopifo.printagent.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.sopifo.printagent.R
import com.sopifo.printagent.ui.AgentViewModel
import com.sopifo.printagent.ui.pages.WebLinks
import com.sopifo.printagent.ui.pages.rememberOpenWeb

@Composable
fun RegistrationScreen(vm: AgentViewModel, onHelp: () -> Unit) {
    val context = LocalContext.current
    val registering by vm.registering.collectAsStateWithLifecycle()
    val error by vm.registrationError.collectAsStateWithLifecycle()
    var token by rememberSaveable { mutableStateOf("") }
    var scanError by rememberSaveable { mutableStateOf<String?>(null) }
    // Not paired yet: links go to sopifo.com.
    val openWeb = rememberOpenWeb(baseUrl = null)

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SopifoLogo()
            Text(
                "Connect this phone to your store. Scan the device QR code from Cloud Print on the Sopifo dashboard, or enter the registration token.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
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
            Button(
                onClick = { vm.register(token) },
                enabled = token.isNotBlank() && !registering,
                modifier = Modifier.fillMaxWidth().testTag("register_button"),
            ) { Text("Register device") }

            if (registering) CircularProgressIndicator(Modifier.size(32.dp))
            (error ?: scanError)?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("registration_error"))
            }
            Spacer(Modifier.height(8.dp))
            // Quiet links: where the QR code / token is (log in on this phone if there's no computer), and help.
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                QuietLink("Open Cloud Print page", Modifier.testTag("open_cloud_print")) { openWeb(WebLinks.CLOUD_PRINT) }
                Text("·", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                QuietLink("Printer setup help", Modifier.testTag("printer_help"), onHelp)
            }
        }
    }
}

/** The Sopifo logo with a small "Print" caption; on a white panel in dark mode (the lettering is dark). */
@Composable
private fun SopifoLogo() {
    val dark = isSystemInDarkTheme()
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(
            color = if (dark) Color.White else Color.Transparent,
            shape = MaterialTheme.shapes.large,
        ) {
            Image(
                painter = painterResource(R.drawable.sopifo_logo),
                contentDescription = "Sopifo",
                modifier = Modifier.padding(if (dark) 12.dp else 0.dp).width(200.dp).testTag("sopifo_logo"),
            )
        }
        Text("Print", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun QuietLink(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = modifier, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
