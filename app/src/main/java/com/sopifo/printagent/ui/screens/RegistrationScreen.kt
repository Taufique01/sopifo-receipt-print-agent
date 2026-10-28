package com.sopifo.printagent.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.sopifo.printagent.R
import com.sopifo.printagent.ui.AgentViewModel
import com.sopifo.printagent.ui.pages.WebLinks
import com.sopifo.printagent.ui.theme.AppIcons
import com.sopifo.printagent.ui.pages.rememberOpenWeb

/**
 * Not paired yet: the Connect screen. "Connect to my shop" opens the website, where the seller
 * signs in and approves this phone's code; the screen checks for approval by itself while it is
 * in front. The older pairing code (QR or typed) stays in a collapsed section below.
 */
@Composable
fun RegistrationScreen(vm: AgentViewModel, onHelp: () -> Unit) {
    val context = LocalContext.current
    val registering by vm.registering.collectAsStateWithLifecycle()
    val error by vm.registrationError.collectAsStateWithLifecycle()
    val connect by vm.connect.collectAsStateWithLifecycle()
    var token by rememberSaveable { mutableStateOf("") }
    var scanError by rememberSaveable { mutableStateOf<String?>(null) }
    var showCodeEntry by rememberSaveable { mutableStateOf(false) }
    // Not paired yet: links go to sopifo.com.
    val openWeb = rememberOpenWeb(baseUrl = null)
    val brand = MaterialTheme.colorScheme.primary.toArgb()

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) { vm.runConnect() }
    }

    // Centred when it fits; scrolls when it doesn't (small screens, keyboard open, error shown).
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SopifoLogo()
            connect.notice?.let {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                    Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp).testTag("connect_notice"))
                }
            }
            Text(
                "Connect this phone to your shop: sign in to Sopifo, then tap Yes, connect.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Button(
                onClick = { connect.code?.let { WebLinks.open(context, it.connectUrl, brand) } },
                enabled = connect.code != null && !registering,
                modifier = Modifier.fillMaxWidth().height(56.dp).testTag("connect_button"),
            ) { Text("Connect to my shop", style = MaterialTheme.typography.titleMedium) }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Phone code: ${connect.code?.code ?: "…"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("connect_code"),
                )
                TextButton(onClick = vm::newConnectCode, enabled = !connect.loading, modifier = Modifier.testTag("new_code")) { Text("New code") }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    connect.error != null -> Text(connect.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("connect_error"))
                    connect.code != null -> {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Text("Waiting for approval…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("connect_waiting"))
                    }
                    else -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            // The older way, for codes from the dashboard's Pairing tab.
            TextButton(onClick = { showCodeEntry = !showCodeEntry }, modifier = Modifier.testTag("show_code_entry")) {
                Text("Have a pairing code?")
                Icon(if (showCodeEntry) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null)
            }
            if (showCodeEntry) {
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it.trim() },
                    label = { Text("Pairing code") },
                    singleLine = true,
                    enabled = !registering,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth().testTag("token_input"),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = {
                            scanError = null
                            // Google code scanner: no camera permission needed, runs in Play services.
                            val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
                            try {
                                GmsBarcodeScanning.getClient(context, options).startScan()
                                    .addOnSuccessListener { code -> code.rawValue?.let(vm::register) }
                                    .addOnFailureListener { scanError = "Scanner unavailable: ${it.message}. Enter the code instead." }
                            } catch (e: Exception) {
                                scanError = "Scanner unavailable. Enter the code instead."
                            }
                        },
                        enabled = !registering,
                        modifier = Modifier.weight(1f).testTag("scan_qr"),
                    ) { Text("Scan QR") }
                    Button(
                        onClick = { vm.register(token) },
                        enabled = token.isNotBlank() && !registering,
                        modifier = Modifier.weight(1f).testTag("register_button"),
                    ) { Text("Pair") }
                }
                if (registering) CircularProgressIndicator(Modifier.size(32.dp))
                (error ?: scanError)?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("registration_error"))
                }
                LinkButton("Open Cloud Print page", AppIcons.OpenInNew, Modifier.testTag("open_cloud_print")) { openWeb(WebLinks.CLOUD_PRINT) }
            }
            Spacer(Modifier.height(8.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                LinkButton("Printer setup help", Icons.Filled.Info, Modifier.testTag("printer_help"), onHelp)
                LinkButton("Delete account & data", Icons.Filled.Delete, Modifier.testTag("delete_account")) { openWeb(WebLinks.DATA_DELETION) }
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

/** A link that looks like one: brand colour, underlined, with an icon, and a full-size tap target. */
@Composable
private fun LinkButton(text: String, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = modifier, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            textDecoration = TextDecoration.Underline,
        )
    }
}
