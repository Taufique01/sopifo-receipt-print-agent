package com.sopifo.printagent.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sopifo.printagent.data.api.DeviceParcelDto
import com.sopifo.printagent.ui.theme.StatusNeutral
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val bookedFormat = DateTimeFormatter.ofPattern("d MMM, HH:mm").withZone(ZoneId.systemDefault())

private fun humanize(s: String) = s.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }

/** Shipping: Pathao and Steadfast parcels on their way, as on the dashboard's Couriers page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShippingScreen(vm: ShippingViewModel, snackbar: SnackbarHostState) {
    val state by vm.state.collectAsStateWithLifecycle()
    val courier by vm.courier.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.consumeMessage() }
    }
    val page = state.page
    val couriers = listOf("steadfast" to "Steadfast", "pathao" to "Pathao")

    Column(Modifier.fillMaxSize()) {
        SecondaryTabRow(selectedTabIndex = couriers.indexOfFirst { it.first == courier }) {
            couriers.forEach { (id, name) ->
                Tab(selected = courier == id, onClick = { vm.setCourier(id) }, text = { Text(name) }, modifier = Modifier.testTag("courier_$id"))
            }
        }
        Column(Modifier.padding(horizontal = 16.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = filter == "awaiting", onClick = { vm.setFilter("awaiting") }, label = { Text("Awaiting pickup (${page?.counts?.awaiting ?: 0})") })
                FilterChip(selected = filter == "all", onClick = { vm.setFilter("all") }, label = { Text("All active (${page?.counts?.all ?: 0})") })
                Row(Modifier.weight(1f)) {}
                IconButton(onClick = { vm.reload() }) { Icon(Icons.Filled.Refresh, contentDescription = "Reload") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = vm::sync, enabled = !syncing, modifier = Modifier.testTag("sync_statuses")) {
                    if (syncing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(if (syncing) " Updating…" else "Sync statuses")
                }
                val newLabels = page?.newLabels?.size ?: 0
                PrimarySmallButton(
                    if (newLabels > 0) "Print new labels ($newLabels)" else "No new labels",
                    { if (newLabels > 0) vm.printNewLabels() },
                    Modifier.testTag("print_new_labels"),
                )
            }
            BulkBar(state.selected.size, onClear = vm::clearSelection) {
                PrimarySmallButton("Print labels", vm::printSelected, Modifier.testTag("bulk_parcel_labels"))
            }
            PullToRefreshBox(isRefreshing = state.loading, onRefresh = { vm.reload() }, modifier = Modifier.weight(1f)) {
                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.error?.let { err -> item { ErrorCard(err) { vm.reload() } } }
                    if (page != null && page.parcels.isNotEmpty()) {
                        item {
                            val all = page.parcels.all { it.id in state.selected }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = all, onCheckedChange = {
                                    if (all) page.parcels.forEach { p -> if (p.id in state.selected) vm.toggle(p.id, p.title) }
                                    else vm.selectAll(page.parcels.associate { it.id to it.title })
                                })
                                Text("Select all on this page", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        items(page.parcels, key = { it.id }) { parcel ->
                            ParcelCard(parcel, parcel.id in state.selected, { vm.toggle(parcel.id, parcel.title) }, { vm.printOne(parcel) })
                        }
                    } else if (page != null && state.error == null) {
                        item {
                            EmptyNote(
                                if (filter == "awaiting") "No parcels waiting for pickup" else "No parcels on their way",
                            )
                        }
                    }
                    if (page != null) item { PagerRow(page.page, page.pageCount, vm::goTo) }
                }
            }
        }
    }
}

@Composable
private fun ParcelCard(parcel: DeviceParcelDto, selected: Boolean, onToggle: () -> Unit, onLabel: () -> Unit) {
    Card(Modifier.fillMaxWidth().testTag("parcel_${parcel.title}")) {
        Row(Modifier.padding(end = 12.dp, top = 8.dp, bottom = 10.dp)) {
            Checkbox(checked = selected, onCheckedChange = { onToggle() })
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(parcel.title, fontWeight = FontWeight.SemiBold)
                    Tag(humanize(parcel.providerStatus ?: parcel.status), StatusNeutral)
                }
                if (parcel.fromCourierPanel) Tag("Booked in the courier's panel", StatusNeutral)
                parcel.trackingCode?.let { Text("Tracking $it", style = MaterialTheme.typography.bodySmall) }
                parcel.consignmentId?.let { Text("Consignment $it", style = MaterialTheme.typography.bodySmall) }
                parcel.codAmount?.let {
                    Text("COD Tk ${NumberFormat.getIntegerInstance().format(it)}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                }
                val who = listOfNotNull(parcel.customer, parcel.phone).joinToString(" · ")
                if (who.isNotEmpty()) Text(who, style = MaterialTheme.typography.bodySmall)
                parcel.address?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = StatusNeutral) }
                parcel.trackingMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = StatusNeutral) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrintedMark("Label", parcel.labelPrinted)
                    parcel.bookedAt?.let { at ->
                        runCatching { bookedFormat.format(Instant.parse(at)) }.getOrNull()?.let {
                            Text("Booked $it", style = MaterialTheme.typography.labelMedium, color = StatusNeutral)
                        }
                    }
                }
            }
            SmallButton("Label", onLabel, Modifier.testTag("print_parcel_${parcel.title}"))
        }
    }
}
