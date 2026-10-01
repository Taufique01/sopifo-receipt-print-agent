package com.sopifo.printagent.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sopifo.printagent.data.db.RecentJobStatus
import com.sopifo.printagent.ui.AgentViewModel
import com.sopifo.printagent.ui.theme.StatusBad
import com.sopifo.printagent.ui.theme.StatusGood

/** Live view of the backend's pending jobs for this device. Only single-job cancel is offered. */
@Composable
fun PendingScreen(vm: AgentViewModel) {
    val state by vm.pending.collectAsStateWithLifecycle()
    var confirmCancel by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.loadPending() }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScreenTitle("Pending jobs")
            Row(Modifier.weight(1f)) {}
            OutlinedButton(onClick = { vm.loadPending(printFirst = true) }, enabled = !state.loading) { Text("Refresh & print") }
        }
        when {
            state.loading && state.jobs.isEmpty() -> CircularProgressIndicator()
            state.error != null -> Text(state.error!!, color = MaterialTheme.colorScheme.error)
            state.jobs.isEmpty() -> Text("No pending jobs", modifier = Modifier.testTag("pending_empty"))
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.jobs, key = { it.id }) { job ->
                SectionCard {
                    StatusRow("Job ID", job.id)
                    StatusRow("Type", job.type)
                    StatusRow("Status", job.status)
                    val cancelling = job.id in state.cancelling
                    OutlinedButton(
                        onClick = { confirmCancel = job.id },
                        enabled = !cancelling,
                        modifier = Modifier.fillMaxWidth().testTag("cancel_${job.id}"),
                    ) {
                        if (cancelling) CircularProgressIndicator(Modifier.size(16.dp)) else Text("Cancel")
                    }
                }
            }
        }
    }

    confirmCancel?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmCancel = null },
            title = { Text("Cancel job?") },
            text = { Text("Job $id will be cancelled on the server and will not print.") },
            confirmButton = { TextButton(onClick = { vm.cancelJob(id); confirmCancel = null }) { Text("Cancel job") } },
            dismissButton = { TextButton(onClick = { confirmCancel = null }) { Text("Keep") } },
        )
    }
}

/** Last 20 jobs handled on this device. No pagination by design. */
@Composable
fun HistoryScreen(vm: AgentViewModel) {
    val jobs by vm.recentJobs.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScreenTitle("Print history")
        if (jobs.isEmpty()) Text("No jobs yet", modifier = Modifier.testTag("history_empty"))
        LazyColumn(Modifier.testTag("history_list")) {
            items(jobs, key = { it.jobId }) { job ->
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(job.type, fontWeight = FontWeight.SemiBold)
                        Text(formatTime(job.updatedAt), style = MaterialTheme.typography.bodySmall)
                        job.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                    Text(
                        job.status,
                        color = when (job.status) {
                            RecentJobStatus.PRINTED -> StatusGood
                            RecentJobStatus.FAILED, RecentJobStatus.INTERRUPTED -> StatusBad
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                HorizontalDivider()
            }
        }
    }
}
