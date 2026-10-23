package com.sopifo.printagent.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sopifo.printagent.ui.theme.StatusBad
import com.sopifo.printagent.ui.theme.StatusGood
import com.sopifo.printagent.ui.theme.StatusNeutral

/** A load error the seller can act on, with "Try again". */
@Composable
fun ErrorCard(message: String, onRetry: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().testTag("list_error"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
            OutlinedButton(onClick = onRetry) { Text("Try again") }
        }
    }
}

@Composable
fun EmptyNote(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = StatusNeutral)
    }
}

/** ‹ Page 2 of 5 › */
@Composable
fun PagerRow(page: Int, pageCount: Int, onGo: (Int) -> Unit) {
    if (pageCount <= 1) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onGo(page - 1) }, enabled = page > 1, modifier = Modifier.testTag("page_prev")) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous page")
        }
        Text("Page $page of $pageCount", style = MaterialTheme.typography.bodyMedium)
        IconButton(onClick = { onGo(page + 1) }, enabled = page < pageCount, modifier = Modifier.testTag("page_next")) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next page")
        }
    }
}

/** Shown while something is selected: "3 selected" and the bulk print buttons. */
@Composable
fun BulkBar(count: Int, onClear: () -> Unit, actions: @Composable () -> Unit) {
    if (count == 0) return
    Surface(tonalElevation = 3.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().testTag("bulk_bar")) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("$count selected", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            androidx.compose.material3.TextButton(onClick = onClear) { Text("Clear") }
            actions()
        }
    }
}

/** "Invoice ✓" / "Label ✗": whether it has been printed. */
@Composable
fun PrintedMark(label: String, printed: Boolean) {
    Text(
        "$label ${if (printed) "✓" else "✗"}",
        style = MaterialTheme.typography.labelMedium,
        color = if (printed) StatusGood else StatusNeutral,
    )
}

/** A small coloured tag, e.g. "Booked · Steadfast". */
@Composable
fun Tag(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.12f), shape = MaterialTheme.shapes.small) {
        Text(text, Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
fun SmallButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(onClick = onClick, modifier = modifier, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun PrimarySmallButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(onClick = onClick, modifier = modifier, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

val Booked = StatusGood
val NotBooked = StatusBad
