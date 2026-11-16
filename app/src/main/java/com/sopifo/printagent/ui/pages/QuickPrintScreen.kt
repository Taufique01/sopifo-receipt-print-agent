package com.sopifo.printagent.ui.pages

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.sopifo.printagent.ui.shell.strings
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sopifo.printagent.data.db.PrinterRole
import com.sopifo.printagent.quick.ImageFit
import com.sopifo.printagent.quick.PdfDarkness
import com.sopifo.printagent.quick.PdfStretch
import com.sopifo.printagent.quick.ImagePlacement
import com.sopifo.printagent.quick.QuickLabelSize
import com.sopifo.printagent.ui.screens.ScreenTitle
import com.sopifo.printagent.ui.screens.SectionCard
import kotlin.math.roundToInt

/** Quick print: formatted text, an image or a PDF on either printer, with a preview of what prints. */
@Composable
fun QuickPrintScreen(
    vm: QuickPrintViewModel,
    snackbar: SnackbarHostState,
    /** False when the screen around it already shows "Quick print" (beside its close button). */
    showTitle: Boolean = true,
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.consumeMessage() }
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { vm.pickImage(it) }
    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { vm.pickPdf(it) }

    Column(Modifier.fillMaxSize()) {
    Column(
        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (showTitle) ScreenTitle("Quick print")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            QuickMode.entries.forEachIndexed { i, mode ->
                SegmentedButton(
                    selected = s.mode == mode,
                    onClick = { vm.update { it.copy(mode = mode) } },
                    shape = SegmentedButtonDefaults.itemShape(i, QuickMode.entries.size),
                    modifier = Modifier.testTag("quick_${mode.name.lowercase()}"),
                ) { Text(mode.title) }
            }
        }

        SectionCard(title = "Printer") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = s.role == PrinterRole.RECEIPT, onClick = { vm.update { it.copy(role = PrinterRole.RECEIPT) } }, label = { Text("Receipt printer") })
                FilterChip(selected = s.role == PrinterRole.LABEL, onClick = { vm.update { it.copy(role = PrinterRole.LABEL) } }, label = { Text("Label printer") })
            }
            if (s.printerMissing) {
                Text("No ${if (s.role == PrinterRole.RECEIPT) "receipt" else "label"} printer is set up yet: choose one under More → Printers.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (s.printerIsLabel) {
                Text("Label size", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    QuickLabelSize.entries.forEach { size ->
                        FilterChip(selected = s.labelSize == size, onClick = { vm.update { it.copy(labelSize = size) } }, label = { Text(size.title) })
                    }
                }
            }
        }

        SectionCard(title = s.mode.title) {
            when (s.mode) {
                QuickMode.TEXT -> {
                    RichTextEditor(html = s.html, onChange = { html -> vm.update { it.copy(html = html) } })
                    Text("Formatting prints as shown in the preview below.", style = MaterialTheme.typography.bodySmall)
                }
                QuickMode.IMAGE -> {
                    OutlinedButton(onClick = { pickImage.launch("image/*") }) { Text(if (s.imageUri == null) "Choose an image" else "Choose another image") }
                    if (s.imageUri != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            ImageFit.entries.forEach { fit ->
                                FilterChip(
                                    selected = s.imageFit == fit,
                                    onClick = { vm.update { it.copy(imageFit = fit) } },
                                    label = { Text(fit.title) },
                                    modifier = Modifier.testTag("image_fit_${fit.name.lowercase()}"),
                                )
                            }
                            TextButton(onClick = { vm.update { it.copy(imageFit = ImageFit.FIT, imageZoom = 1f) } }, enabled = s.imageZoom != 1f || s.imageFit != ImageFit.FIT) { Text("Reset") }
                        }
                        Text("Zoom ${(s.imageZoom * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
                        Slider(
                            value = s.imageZoom,
                            onValueChange = { z -> vm.update { it.copy(imageZoom = (z * 20).roundToInt() / 20f) } },
                            valueRange = ImagePlacement.MIN_ZOOM..ImagePlacement.MAX_ZOOM,
                            modifier = Modifier.testTag("image_zoom"),
                        )
                    }
                    Text(
                        if (s.printerIsLabel) "Fit shows the whole photo on the label; Fill covers the label and trims the edges. Zoom in to print a part bigger."
                        else "Photos print in black and white, the width of the paper. Zoom out for a smaller print, in to crop the sides.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                QuickMode.PDF -> {
                    OutlinedButton(onClick = { pickPdf.launch("application/pdf") }) { Text(if (s.pdfUri == null) "Choose a PDF" else "Choose another PDF") }
                    if (s.pdfPages > 0) {
                        Text("${s.pdfPages} page${if (s.pdfPages == 1) "" else "s"} · up to ${QuickPrintViewModel.MAX_PDF_PAGES} at a time", style = MaterialTheme.typography.bodySmall)
                        Stepper("From page", s.pageFrom, 1, s.pageTo) { v -> vm.update { it.copy(pageFrom = v) } }
                        Stepper("To page", s.pageTo, s.pageFrom, minOf(s.pdfPages, s.pageFrom + QuickPrintViewModel.MAX_PDF_PAGES - 1)) { v -> vm.update { it.copy(pageTo = v) } }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Trim white margins", Modifier.weight(1f))
                            Switch(
                                checked = s.pdfTrim,
                                onCheckedChange = { on -> vm.update { it.copy(pdfTrim = on) } },
                                modifier = Modifier.testTag("pdf_trim"),
                            )
                        }
                        if (s.printerIsLabel) {
                            // Smart shrink: courier label barcodes redrawn sharp on the small label.
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("✨ Smart shrink (sharp barcode)", Modifier.weight(1f))
                                Switch(
                                    checked = s.smart,
                                    onCheckedChange = { on -> vm.update { it.copy(smart = on) } },
                                    modifier = Modifier.testTag("pdf_smart"),
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                ImageFit.entries.forEach { fit ->
                                    FilterChip(
                                        selected = s.pdfFit == fit,
                                        onClick = { vm.update { it.copy(pdfFit = fit) } },
                                        label = { Text(fit.title) },
                                        modifier = Modifier.testTag("pdf_fit_${fit.name.lowercase()}"),
                                    )
                                }
                            }
                        }
                        Text(
                            when {
                                s.printerIsLabel -> "Trim cuts the page's empty border. Fit shows the whole page on the label; Fill covers the label edge to edge and cuts what's left over."
                                else -> "Trim cuts the page's empty border so the content fills the paper's width."
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (!s.printerIsLabel) {
                            Text("Stretch", style = MaterialTheme.typography.labelLarge)
                            ChoiceChips(PdfStretch.entries, s.pdfStretch, { it.title }, "pdf_stretch") { v -> vm.update { it.copy(pdfStretch = v) } }
                            Text("Prints the page taller (letters taller, not wider), so small text is easier to read.", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("Darkness", style = MaterialTheme.typography.labelLarge)
                        ChoiceChips(PdfDarkness.entries, s.pdfDarkness, { it.title }, "pdf_darkness") { v -> vm.update { it.copy(pdfDarkness = v) } }
                        Text("Darker and Darkest keep faint, thin and grey text; use them when parts are missing.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Stepper("Copies", s.copies, 1, 10) { v -> vm.update { it.copy(copies = v) } }
        }

        SectionCard(title = "Preview") {
            val preview = s.preview
            when {
                s.error != null -> Text(s.error!!, color = MaterialTheme.colorScheme.error)
                preview == null -> Text("Nothing to print yet", style = MaterialTheme.typography.bodySmall)
                else -> Surface(border = BorderStroke(1.dp, Color.LightGray), color = Color.White) {
                    Image(
                        preview.asImageBitmap(),
                        contentDescription = "Print preview",
                        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
        }

    }
        // 2.1.21: Print never scrolls away (a shared PDF opens at the top), with the printer and
        // label size right above it, so the size is seen — and changed — just before printing.
        PrintBar(s, onRole = { r -> vm.update { it.copy(role = r) } }, onSize = { z -> vm.update { it.copy(labelSize = z) } }, onPrint = vm::print)
    }
}

@Composable
private fun PrintBar(s: QuickPrintState, onRole: (PrinterRole) -> Unit, onSize: (QuickLabelSize) -> Unit, onPrint: () -> Unit) {
    val t = strings()
    var menu by remember { mutableStateOf(false) }
    Surface(shadowElevation = 8.dp, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box {
                Row(
                    Modifier.fillMaxWidth().clickable { menu = true }.testTag("quick_print_target"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(if (s.printerIsLabel || s.role == PrinterRole.LABEL) "🏷" else "🧾")
                    Text(
                        when {
                            s.printerMissing -> "${if (s.role == PrinterRole.LABEL) t.labelPrinter else t.receiptPrinter} · ${t.addPrinter}"
                            s.printerIsLabel -> "${t.labelPrinter} · ${s.labelSize.title}"
                            else -> t.receiptPrinter
                        },
                        fontWeight = FontWeight.Bold,
                        color = if (s.printerMissing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).testTag("quick_print_target_text"),
                    )
                    Text(t.change, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    listOf(PrinterRole.LABEL to t.labelPrinter, PrinterRole.RECEIPT to t.receiptPrinter).forEach { (role, name) ->
                        DropdownMenuItem(
                            text = { Text((if (s.role == role) "✓ " else "") + name, fontWeight = FontWeight.Bold) },
                            onClick = { onRole(role); if (role == PrinterRole.RECEIPT) menu = false },
                        )
                    }
                    if (s.printerIsLabel) {
                        HorizontalDivider()
                        QuickLabelSize.entries.forEach { size ->
                            DropdownMenuItem(
                                text = { Text((if (s.labelSize == size) "✓ " else "") + size.title) },
                                onClick = { onSize(size); menu = false },
                                modifier = Modifier.testTag("quick_size_${size.name.lowercase()}"),
                            )
                        }
                    }
                }
            }
            Button(onClick = onPrint, enabled = !s.busy, modifier = Modifier.fillMaxWidth().height(52.dp).testTag("quick_print_button")) {
                if (s.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                Text(if (s.busy) "  Preparing…" else "🖨  ${t.printNow}", fontWeight = FontWeight.Black)
            }
        }
    }
}

@Composable
private fun Stepper(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        OutlinedButton(onClick = { onChange((value - 1).coerceAtLeast(min)) }, enabled = value > min) { Text("−") }
        Text("  $value  ", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = { onChange((value + 1).coerceAtMost(max)) }, enabled = value < max) { Text("+") }
    }
}

/** One choice out of [options], as a row of chips. */
@Composable
private fun <T : Enum<T>> ChoiceChips(options: List<T>, selected: T, title: (T) -> String, tag: String, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o ->
            FilterChip(
                selected = selected == o,
                onClick = { onSelect(o) },
                label = { Text(title(o)) },
                modifier = Modifier.testTag("${tag}_${o.name.lowercase()}"),
            )
        }
    }
}
