package com.sopifo.printagent.ui.pages

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sopifo.printagent.SopifoApp
import com.sopifo.printagent.data.db.PrintRunEntity
import com.sopifo.printagent.data.db.PrintRunItemEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Activity: the prints started in the app, each item's outcome, and Retry / Stop / Continue. */
@OptIn(ExperimentalCoroutinesApi::class)
class ActivityViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as SopifoApp).container
    private val dao = c.db.printRunDao()
    private val started = SharingStarted.WhileSubscribed(5_000)

    val runs: StateFlow<List<PrintRunEntity>> = dao.observeRuns().stateIn(viewModelScope, started, emptyList())
    val running: StateFlow<String?> = c.runner.running

    /** The run whose items are shown. */
    private val _open = MutableStateFlow<String?>(null)
    val open: StateFlow<String?> = _open

    val openItems: StateFlow<List<PrintRunItemEntity>> =
        _open.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else dao.observeItems(id) }
            .stateIn(viewModelScope, started, emptyList())

    /** The run printing now, with its items (for the progress strip). */
    val current: StateFlow<PrintRunEntity?> =
        combine(running, runs) { id, list -> list.firstOrNull { it.id == id } }.stateIn(viewModelScope, started, null)

    fun toggle(runId: String) {
        _open.value = if (_open.value == runId) null else runId
    }

    fun retry(runId: String) = viewModelScope.launch { c.runner.retry(runId) }
    fun resume(runId: String) = viewModelScope.launch { c.runner.resume(runId) }
    fun stop(runId: String) = c.runner.stop(runId)
}
