package com.sopifo.printagent.ui.shell

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

/**
 * The Share → auto-print strip at the top: "🖨 Printing 3 · XP-420B [Cancel]", then "✓ 3 sent"
 * and back to the app the file came from. Not signed in, once a day: the tip with "Try →".
 */
@Composable
fun AutoPrintStrip(modifier: Modifier = Modifier) {
    val strip by SharedAutoPrint.strip.collectAsStateWithLifecycle()
    val s = strip ?: return
    val t = strings()
    val activity = LocalContext.current as? Activity
    LaunchedEffect(s.phase) {
        if (s.phase == SharedAutoPrint.Phase.SENT) {
            // Long enough to read the tip; then back to Steadfast / Pathao / Files.
            delay(if (s.tip) 3_500 else 1_200)
            if (SharedAutoPrint.strip.value?.phase == SharedAutoPrint.Phase.SENT) {
                SharedAutoPrint.close()
                activity?.moveTaskToBack(true)
            }
        }
    }
    Box(modifier.fillMaxSize().statusBarsPadding()) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (s.phase == SharedAutoPrint.Phase.FAILED) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.inverseSurface,
            shadowElevation = 6.dp,
            modifier = Modifier.align(Alignment.TopCenter).padding(10.dp).fillMaxWidth().testTag("auto_print_strip"),
        ) {
            val onColor = if (s.phase == SharedAutoPrint.Phase.FAILED) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.inverseOnSurface
            Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    when (s.phase) {
                        SharedAutoPrint.Phase.WAIT -> {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = onColor)
                            Text("🖨 " + t.stripPrinting.format(maxOf(s.pages, 1), s.printer), color = onColor, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            FilledTonalButton(onClick = SharedAutoPrint::cancel, modifier = Modifier.testTag("auto_print_cancel")) { Text(t.cancel) }
                        }
                        SharedAutoPrint.Phase.SENT -> Text(t.stripSent.format(s.pages), color = onColor, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
                        SharedAutoPrint.Phase.FAILED -> {
                            Text("✗ ${s.error.orEmpty()}", color = onColor, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            TextButton(onClick = { SharedAutoPrint.openInQuickPrint() }) { Text(t.openQuick) }
                        }
                    }
                }
                if (s.tip) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t.stripTip, color = onColor, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            SharedAutoPrint.close()
                            SharedAutoPrint.signUpRequested.value = true
                        }, modifier = Modifier.testTag("auto_print_try")) { Text(t.tryIt, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    }
}
