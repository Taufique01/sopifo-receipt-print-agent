package com.sopifo.printagent.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sopifo.printagent.print.PrinterState
import com.sopifo.printagent.ui.theme.StatusBad
import com.sopifo.printagent.ui.theme.StatusGood
import com.sopifo.printagent.ui.theme.StatusNeutral
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun SectionCard(modifier: Modifier = Modifier, title: String? = null, content: @Composable () -> Unit) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/** A label/value row with an optional coloured status dot (true = good, false = bad, null = neutral). */
@Composable
fun StatusRow(label: String, value: String, good: Boolean? = null, tag: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (good != null || tag != null) {
            Surface(Modifier.size(10.dp), shape = CircleShape, color = when (good) { true -> StatusGood; false -> StatusBad; null -> StatusNeutral }) {}
            Spacer(Modifier.width(8.dp))
        }
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = if (tag != null) Modifier.testTag(tag) else Modifier,
        )
    }
}

fun printerStateText(state: PrinterState?): String = when (state) {
    PrinterState.CONNECTED -> "Connected"
    PrinterState.NOT_CONFIGURED, null -> "Not configured"
    PrinterState.BLUETOOTH_OFF -> "Disconnected (Bluetooth off)"
    PrinterState.NO_PERMISSION -> "Disconnected (no permission)"
    PrinterState.DISCONNECTED -> "Disconnected"
}

fun printerStateGood(state: PrinterState?): Boolean? = when (state) {
    PrinterState.CONNECTED -> true
    PrinterState.NOT_CONFIGURED, null -> null
    else -> false
}

private val timeFormat = DateTimeFormatter.ofPattern("MMM d, HH:mm:ss").withZone(ZoneId.systemDefault())

fun formatTime(epochMs: Long?): String = epochMs?.let { timeFormat.format(Instant.ofEpochMilli(it)) } ?: "Never"

fun formatAgo(epochMs: Long?, now: Long = System.currentTimeMillis()): String {
    if (epochMs == null) return "Never"
    val s = (now - epochMs) / 1000
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60} min ago"
        s < 86400 -> "${s / 3600} h ago"
        else -> formatTime(epochMs)
    }
}

@Composable
fun ScreenTitle(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 4.dp))
}
