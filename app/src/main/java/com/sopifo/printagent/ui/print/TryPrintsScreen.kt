package com.sopifo.printagent.ui.print

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sopifo.printagent.SopifoApp
import com.sopifo.printagent.data.db.PrinterProtocol
import com.sopifo.printagent.data.db.PrinterRole
import com.sopifo.printagent.fast.SampleLabels
import com.sopifo.printagent.print.PrintDataBuilder
import com.sopifo.printagent.quick.QuickPrintRenderer
import com.sopifo.printagent.quick.QuickTarget
import com.sopifo.printagent.ui.shell.AppSettings
import com.sopifo.printagent.ui.shell.HelpIcon
import com.sopifo.printagent.ui.shell.Tile
import com.sopifo.printagent.ui.shell.strings
import com.sopifo.printagent.ui.theme.AppIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Try prints (no account needed): test label / receipt, sample Pathao and Steadfast labels
 * printed short with smart shrink, and a try pad. Everything else needs a login.
 */
@Composable
fun TryPrintsScreen(onBack: () -> Unit, onPrinters: () -> Unit, onLogin: () -> Unit, onSignup: () -> Unit) {
    val t = strings()
    val context = LocalContext.current
    val c = (context.applicationContext as SopifoApp).container
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var pad by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun run(role: PrinterRole, draw: suspend (com.sopifo.printagent.data.db.PrinterConfigEntity) -> Bitmap?) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val printer = c.printers.config(role) ?: c.printers.config(if (role == PrinterRole.LABEL) PrinterRole.RECEIPT else PrinterRole.LABEL)
                if (printer == null) {
                    snackbar.showSnackbar(t.needPrinterFirst)
                    onPrinters()
                    return@launch
                }
                val bytes = withContext(Dispatchers.Default) {
                    val bmp = draw(printer) ?: return@withContext null
                    PrintDataBuilder().fromDrawn(bmp, printer).bytes.also { bmp.recycle() }
                }
                if (bytes == null) c.printers.testPrint(printer.role) else c.printers.printFast(printer, bytes)
                snackbar.showSnackbar("✓")
            } catch (e: Exception) {
                snackbar.showSnackbar("✗ ${e.message}")
            } finally {
                busy = false
            }
        }
    }

    /** The sample shrunk the way smart shrink does it, to this printer's label or roll. */
    suspend fun shrunk(sample: Bitmap, printer: com.sopifo.printagent.data.db.PrinterConfigEntity): Bitmap {
        val size = AppSettings.get(context).labelSize.value
        val target = if (printer.protocol == PrinterProtocol.TSPL) QuickTarget(size.w, size.h)
        else QuickTarget(PrintDataBuilder.paperWidthDots(printer.paperWidthMm) ?: PrintDataBuilder.PAPER_58_MM_DOTS)
        return QuickPrintRenderer(context).smartFromBitmap(sample, target)
    }

    Box(Modifier.fillMaxSize().testTag("try_prints")) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("try_back")) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = t.back) }
                Text("🧪 ${t.tryPrints}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                HelpIcon(page = "Try prints")
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Tile(AppIcons.Tag, t.testLabel, Modifier.weight(1f).testTag("try_test_label")) { run(PrinterRole.LABEL) { null } }
                    Tile(AppIcons.Receipt, t.testReceipt, Modifier.weight(1f).testTag("try_test_receipt")) { run(PrinterRole.RECEIPT) { null } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Tile(AppIcons.Truck, t.samplePathao, Modifier.weight(1f).testTag("try_pathao"), sub = t.shortLabel) { run(PrinterRole.LABEL) { shrunk(SampleLabels.pathao(), it) } }
                    Tile(AppIcons.Truck, t.sampleSteadfast, Modifier.weight(1f).testTag("try_steadfast"), sub = t.shortLabel) { run(PrinterRole.LABEL) { shrunk(SampleLabels.steadfast(), it) } }
                }
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("✏️ ${t.tryPad}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        OutlinedTextField(value = pad, onValueChange = { if (it.length <= 300) pad = it }, placeholder = { Text(t.typeHere) }, modifier = Modifier.fillMaxWidth().testTag("try_pad_text"), minLines = 2)
                        Button(onClick = {
                            val text = pad.trim()
                            if (text.isNotEmpty()) run(PrinterRole.LABEL) { printer ->
                                val size = AppSettings.get(context).labelSize.value
                                if (printer.protocol == PrinterProtocol.TSPL) SampleLabels.textBlock(text, size.w, size.h)
                                else SampleLabels.textBlock(text, PrintDataBuilder.paperWidthDots(printer.paperWidthMm) ?: PrintDataBuilder.PAPER_58_MM_DOTS, null)
                            }
                        }, enabled = pad.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth().testTag("try_pad_print")) {
                            Icon(AppIcons.Print, contentDescription = null)
                            Spacer(Modifier.size(6.dp))
                            Text(t.printNow, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                OutlinedButton(onClick = onPrinters, modifier = Modifier.fillMaxWidth()) { Text("🖨 ${t.printers}") }
                HorizontalDivider()
                FullFeatures(onLogin, onSignup)
                Spacer(Modifier.height(16.dp))
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

/** "🔓 Log in for full features" + Log in / Create shop (with what a shop gives). */
@Composable
fun FullFeatures(onLogin: () -> Unit, onSignup: () -> Unit) {
    val t = strings()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("🔓 ${t.fullFeatures}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Button(onClick = onLogin, modifier = Modifier.fillMaxWidth().height(54.dp).testTag("login")) { Text(t.logIn, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        OutlinedButton(onClick = onSignup, modifier = Modifier.fillMaxWidth().height(54.dp).testTag("signup")) {
            Icon(AppIcons.Store, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text(t.createShop, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        Text(t.createShopSub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}
