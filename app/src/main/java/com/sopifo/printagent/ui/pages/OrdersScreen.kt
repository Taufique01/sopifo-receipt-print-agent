package com.sopifo.printagent.ui.pages

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sopifo.printagent.data.api.DeviceOrderDto
import com.sopifo.printagent.ui.screens.ScreenTitle

private fun statusTitle(s: String) = s.lowercase().replaceFirstChar { it.uppercase() }

private fun courierTitle(p: String) = if (p == "PATHAO") "Pathao" else "Steadfast"

/** Orders: open orders to print, one by one or in bulk. Just what printing needs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrdersScreen(vm: OrdersViewModel, snackbar: SnackbarHostState) {
    val state by vm.state.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val pageSize by vm.pageSize.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.consumeMessage() }
    }
    val page = state.page

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(top = 12.dp)) { ScreenTitle("Orders") }
            for (size in listOf(30, 50)) {
                FilterChip(selected = pageSize == size, onClick = { vm.setPageSize(size) }, label = { Text("$size") }, modifier = Modifier.padding(start = 4.dp))
            }
            IconButton(onClick = { vm.reload() }, modifier = Modifier.testTag("orders_reload")) {
                Icon(Icons.Filled.Refresh, contentDescription = "Reload")
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val counts = page?.counts.orEmpty()
            FilterChip(selected = status == null, onClick = { vm.setStatus(null) }, label = { Text("All open (${counts.values.sum()})") })
            for (s in OrdersViewModel.STATUSES) {
                FilterChip(selected = status == s, onClick = { vm.setStatus(s) }, label = { Text("${statusTitle(s)} (${counts[s] ?: 0})") })
            }
        }
        BulkBar(state.selected.size, onClear = vm::clearSelection) {
            SmallButton("Invoices", { vm.printSelected(OrdersViewModel.DOC_INVOICE) }, Modifier.testTag("bulk_invoices"))
            PrimarySmallButton("Labels", { vm.printSelected(OrdersViewModel.DOC_LABEL) }, Modifier.testTag("bulk_labels"))
        }

        PullToRefreshBox(isRefreshing = state.loading, onRefresh = { vm.reload() }, modifier = Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.error?.let { err -> item { ErrorCard(err) { vm.reload() } } }
                if (page != null && page.orders.isNotEmpty()) {
                    item {
                        val all = page.orders.all { it.id in state.selected }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = all,
                                onCheckedChange = {
                                    if (all) page.orders.forEach { o -> if (o.id in state.selected) vm.toggle(o.id, o.number) }
                                    else vm.selectAll(page.orders.associate { it.id to it.number })
                                },
                                modifier = Modifier.testTag("select_all"),
                            )
                            Text("Select all on this page", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    items(page.orders, key = { it.id }) { order ->
                        OrderRow(
                            order = order,
                            selected = order.id in state.selected,
                            onToggle = { vm.toggle(order.id, order.number) },
                            onInvoice = { vm.printOne(order, OrdersViewModel.DOC_INVOICE) },
                            onLabel = { vm.printOne(order, OrdersViewModel.DOC_LABEL) },
                        )
                    }
                } else if (page != null && state.error == null) {
                    item { EmptyNote(if (status == null) "No open orders" else "No ${statusTitle(status!!).lowercase()} orders") }
                }
                if (page != null) item { PagerRow(page.page, page.pageCount, vm::goTo) }
            }
        }
    }
}

@Composable
private fun OrderRow(
    order: DeviceOrderDto,
    selected: Boolean,
    onToggle: () -> Unit,
    onInvoice: () -> Unit,
    onLabel: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().testTag("order_${order.number}")) {
        Row(Modifier.padding(end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = selected, onCheckedChange = { onToggle() })
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(order.number, fontWeight = FontWeight.SemiBold)
                Text(
                    listOfNotNull(order.customer, order.phone).joinToString(" · ").ifEmpty { "—" },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    PrintedMark("Invoice", order.invoicePrinted)
                    PrintedMark("Label", order.labelPrinted)
                    order.courier?.let { Tag("Booked · ${courierTitle(it.provider)}", Booked) } ?: Tag("Not booked", NotBooked)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SmallButton("Invoice", onInvoice, Modifier.testTag("print_invoice_${order.number}"))
                SmallButton("Label", onLabel, Modifier.testTag("print_label_${order.number}"))
            }
        }
    }
}
