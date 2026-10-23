package com.sopifo.printagent.ui.pages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sopifo.printagent.data.db.PrintRunEntity
import com.sopifo.printagent.data.db.PrintRunItemEntity
import com.sopifo.printagent.data.db.RunItemStatus
import com.sopifo.printagent.data.db.RunState
import com.sopifo.printagent.ui.AgentViewModel
import com.sopifo.printagent.ui.screens.HistoryScreen
import com.sopifo.printagent.ui.screens.PendingScreen
import com.sopifo.printagent.ui.screens.formatAgo
import com.sopifo.printagent.ui.theme.StatusBad
import com.sopifo.printagent.ui.theme.StatusGood
import com.sopifo.printagent.ui.theme.StatusNeutral

/**
 * Activity: what happened to every print. "Prints" are the ones started in the app (with each
 * item's outcome, Retry and Stop); "Waiting" and "Dashboard jobs" are the prints sent from the
 * dashboard (today's Pending and History).
 */
@Composable
fun ActivityScreen(vm: ActivityViewModel, agent: AgentViewModel, lockedContent: @Composable (@Composable () -> Unit) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf("Prints", "Waiting", "Dashboard jobs")
    Column(Modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            tabs.forEachIndexed { i, t ->
                SegmentedButton(selected = tab == i, onClick = { tab = i }, shape = SegmentedButtonDefaults.itemShape(i, tabs.size)) { Text(t) }
            }
        }
        when (tab) {
            0 -> lockedContent { RunsList(vm) }
            1 -> PendingScreen(agent)
            else -> HistoryScreen(agent)
        }
    }
}

@Composable
private fun RunsList(vm: ActivityViewModel) {
    val runs by vm.runs.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val open by vm.open.collectAsStateWithLifecycle()
    val items by vm.openItems.collectAsStateWithLifecycle()
    if (runs.isEmpty()) {
        Box(Modifier.fillMaxSize()) { EmptyNote("Prints you start from Orders, Shipping or Quick print show here") }
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(runs, key = { it.id }) { run ->
            RunCard(
                run = run,
                isRunning = run.id == running,
                expanded = run.id == open,
                items = if (run.id == open) items else emptyList(),
                onToggle = { vm.toggle(run.id) },
                onRetry = { vm.retry(run.id) },
                onResume = { vm.resume(run.id) },
                onStop = { vm.stop(run.id) },
            )
        }
    }
}

private fun stateText(run: PrintRunEntity, isRunning: Boolean) = when {
    isRunning -> "Printing"
    run.state == RunState.RUNNING -> "Waiting to print"
    run.state == RunState.DONE && run.failed == 0 -> "Done"
    run.state == RunState.DONE -> "Done with problems"
    run.state == RunState.STOPPED -> "Stopped"
    else -> "Interrupted"
}

@Composable
private fun RunCard(
    run: PrintRunEntity,
    isRunning: Boolean,
    expanded: Boolean,
    items: List<PrintRunItemEntity>,
    onToggle: () -> Unit,
    onRetry: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().testTag("run_${run.id}")) {
        Column(Modifier.clickable(onClick = onToggle).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(run.title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Tag(stateText(run, isRunning), if (run.failed > 0 || run.state == RunState.STOPPED) StatusBad else StatusGood)
            }
            Text(
                "${run.printed} printed" + (if (run.failed > 0) " · ${run.failed} not printed" else "") + " of ${run.total} · ${formatAgo(run.createdAt)}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (isRunning) LinearProgressIndicator({ (run.printed + run.failed).toFloat() / run.total.coerceAtLeast(1) }, Modifier.fillMaxWidth())
            run.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isRunning) SmallButton("Stop", onStop, Modifier.testTag("run_stop"))
                if (!isRunning && run.state == RunState.INTERRUPTED) PrimarySmallButton("Continue", onResume, Modifier.testTag("run_continue"))
                if (!isRunning && run.state != RunState.RUNNING && run.failed > 0) PrimarySmallButton("Retry ${run.failed}", onRetry, Modifier.testTag("run_retry"))
                Text(if (expanded) "Hide details" else "Details", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            if (expanded) {
                HorizontalDivider()
                items.forEach { ItemRow(it) }
            }
        }
    }
}

@Composable
private fun ItemRow(item: PrintRunItemEntity) {
    val (text, color) = when (item.status) {
        RunItemStatus.PRINTED -> "Printed" to StatusGood
        RunItemStatus.PENDING -> "Waiting" to StatusNeutral
        RunItemStatus.SENDING -> "Printing…" to StatusNeutral
        RunItemStatus.FAILED -> "Failed" to StatusBad
        RunItemStatus.INCOMPLETE -> "May be incomplete" to StatusBad
        RunItemStatus.NOT_PRINTED -> "Not printed" to StatusBad
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(item.title + if (item.attempt > 1) " (try ${item.attempt})" else "", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(text, style = MaterialTheme.typography.labelMedium, color = color)
        }
        item.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color.Gray) }
    }
}
