package com.sopifo.printagent.ui.shell

import kotlinx.coroutines.flow.MutableStateFlow

/** sopifo://auth?code=… from the Facebook Chrome tab, waiting for the web view to consume it. */
object AuthReturn {
    val code = MutableStateFlow<String?>(null)
}

/** A file shared to the app (Share → Sopifo): opens Quick print with it. */
object SharedFile {
    data class Item(val uri: android.net.Uri, val isPdf: Boolean)
    val item = MutableStateFlow<Item?>(null)
}
