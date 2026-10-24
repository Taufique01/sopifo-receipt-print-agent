package com.sopifo.printagent.ui.pages

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sopifo.printagent.SopifoApp
import com.sopifo.printagent.data.api.ApiException
import com.sopifo.printagent.data.api.DeviceOrderDto
import com.sopifo.printagent.data.api.DeviceParcelDto
import com.sopifo.printagent.data.api.OrdersPageDto
import com.sopifo.printagent.data.api.ParcelsPageDto
import com.sopifo.printagent.data.db.PrinterRole
import com.sopifo.printagent.runs.ApiRunContent
import com.sopifo.printagent.runs.NewRunItem
import com.sopifo.printagent.runs.PrintRunner
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the Orders and Shipping pages share: loading a page, selection, printing, feedback. */
abstract class PrintListViewModel<P>(app: Application) : AndroidViewModel(app) {
    protected val c = (app as SopifoApp).container

    data class State<P>(
        val page: P? = null,
        val pageNumber: Int = 1,
        val loading: Boolean = false,
        val error: String? = null,
        /** Courier & bulk printing isn't unlocked for this shop (402 PRINT_LOCKED): the page shows how to unlock. */
        val locked: PrintLock? = null,
        /** Selected ids → what the seller sees them as (kept across pages). */
        val selected: Map<String, String> = emptyMap(),
    )

    protected val _state = MutableStateFlow(State<P>())
    val state: StateFlow<State<P>> = _state.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    /** One-shot feedback (snackbar). */
    val message: StateFlow<String?> = _message.asStateFlow()
    fun consumeMessage() { _message.value = null }
    protected fun say(text: String) { _message.value = text }

    private var loadJob: Job? = null

    init {
        // A print that finished changed the printed marks: show them.
        viewModelScope.launch {
            c.runner.running.drop(1).filter { it == null }.collect {
                delay(RELOAD_AFTER_RUN_MS)
                reload(quiet = true)
            }
        }
    }

    protected abstract suspend fun fetch(page: Int): P

    fun reload(quiet: Boolean = false) = load(_state.value.pageNumber, quiet)

    fun goTo(page: Int) = load(page.coerceAtLeast(1), quiet = false)

    private fun load(page: Int, quiet: Boolean) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (!quiet) _state.update { it.copy(loading = true, error = null) }
            try {
                val data = fetch(page)
                _state.update { it.copy(page = data, pageNumber = page, loading = false, error = null, locked = null) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                val lock = lockOf(e)
                _state.update {
                    if (lock != null) it.copy(loading = false, error = null, locked = lock, page = null, selected = emptyMap())
                    else it.copy(loading = false, error = PrintRunner.friendly(e))
                }
            }
        }
    }

    fun toggle(id: String, title: String) = _state.update {
        it.copy(selected = if (id in it.selected) it.selected - id else it.selected + (id to title))
    }

    fun selectAll(items: Map<String, String>) = _state.update { it.copy(selected = it.selected + items) }

    fun clearSelection() = _state.update { it.copy(selected = emptyMap()) }

    /** Starts a run; feedback comes from the progress strip, notifications and Activity. */
    protected fun print(title: String, source: String, role: PrinterRole, items: List<NewRunItem>) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            try {
                c.runner.start(title, source, role, items)
                say(if (items.size == 1) "Printing ${items[0].title}…" else "Printing ${items.size}… you can lock the phone")
                clearSelection()
            } catch (e: Exception) {
                say("Couldn't start printing: ${e.message}")
            }
        }
    }

    /** The locked state for a PRINT_LOCKED answer, else null. */
    protected fun lockOf(e: Exception): PrintLock? =
        (e as? ApiException)?.takeIf { it.printLocked }?.let { PrintLock(it.message ?: "Printing is locked.", it.unlockUrl) }

    protected companion object {
        const val RELOAD_AFTER_RUN_MS = 1_500L
    }
}

/** Orders tab: open orders, printable one by one or in bulk. */
class OrdersViewModel(app: Application) : PrintListViewModel<OrdersPageDto>(app) {
    private val _status = MutableStateFlow<String?>(null)
    /** null = every open status. */
    val status: StateFlow<String?> = _status.asStateFlow()
    private val _pageSize = MutableStateFlow(50)
    val pageSize: StateFlow<Int> = _pageSize.asStateFlow()

    init { reload() }

    override suspend fun fetch(page: Int) = c.api.listOrders(_status.value, page, _pageSize.value)

    fun setStatus(status: String?) {
        _status.value = status
        clearSelection()
        goTo(1)
    }

    fun setPageSize(size: Int) {
        _pageSize.value = size
        goTo(1)
    }

    fun printOne(order: DeviceOrderDto, doc: String) = print(
        title = "${if (doc == DOC_INVOICE) "Invoice" else "Label"} ${order.number}",
        source = "orders",
        role = roleFor(doc),
        items = listOf(NewRunItem(ApiRunContent.KIND_ORDER, order.id, doc, order.number)),
    )

    fun printSelected(doc: String) = print(
        title = if (doc == DOC_INVOICE) "Order invoices" else "Order labels",
        source = "orders",
        role = roleFor(doc),
        items = _state.value.selected.map { (id, number) -> NewRunItem(ApiRunContent.KIND_ORDER, id, doc, number) },
    )

    private fun roleFor(doc: String) = if (doc == DOC_INVOICE) PrinterRole.RECEIPT else PrinterRole.LABEL

    companion object {
        const val DOC_INVOICE = "invoice"
        const val DOC_LABEL = "label"
        val STATUSES = listOf("PENDING", "CONFIRMED", "PROCESSING", "PACKED", "SHIPPED")
    }
}

/** Shipping tab: Pathao and Steadfast parcels on their way, like the dashboard's Couriers page. */
class ShippingViewModel(app: Application) : PrintListViewModel<ParcelsPageDto>(app) {
    private val _courier = MutableStateFlow("steadfast")
    val courier: StateFlow<String> = _courier.asStateFlow()
    private val _filter = MutableStateFlow("awaiting")
    val filter: StateFlow<String> = _filter.asStateFlow()
    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    init { reload() }

    override suspend fun fetch(page: Int) = c.api.listParcels(_courier.value, _filter.value, page, 50)

    fun setCourier(courier: String) {
        _courier.value = courier
        clearSelection()
        goTo(1)
    }

    fun setFilter(filter: String) {
        _filter.value = filter
        goTo(1)
    }

    fun sync() {
        if (_syncing.value) return
        viewModelScope.launch {
            _syncing.value = true
            try {
                say(c.api.syncParcels(_courier.value).message.ifBlank { "Statuses updated" })
                reload(quiet = true)
            } catch (e: Exception) {
                val lock = lockOf(e)
                if (lock != null) _state.update { it.copy(locked = lock, page = null) }
                else say("Couldn't update statuses: ${PrintRunner.friendly(e)}")
            } finally {
                _syncing.value = false
            }
        }
    }

    private val courierName get() = if (_courier.value == "pathao") "Pathao" else "Steadfast"

    fun printOne(parcel: DeviceParcelDto) = print(
        title = "Label ${parcel.title}",
        source = "shipping",
        role = PrinterRole.LABEL,
        items = listOf(NewRunItem(ApiRunContent.KIND_PARCEL, parcel.id, "label", parcel.title)),
    )

    fun printSelected() = print(
        title = "$courierName labels",
        source = "shipping",
        role = PrinterRole.LABEL,
        items = _state.value.selected.map { (id, title) -> NewRunItem(ApiRunContent.KIND_PARCEL, id, "label", title) },
    )

    /** Every parcel awaiting pickup whose label isn't printed yet, across all pages. */
    fun printNewLabels() = print(
        title = "New $courierName labels",
        source = "shipping",
        role = PrinterRole.LABEL,
        items = _state.value.page?.newLabels.orEmpty().map { NewRunItem(ApiRunContent.KIND_PARCEL, it.id, "label", it.number) },
    )
}
