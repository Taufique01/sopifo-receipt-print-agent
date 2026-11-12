package com.sopifo.printagent.ui.shell

import java.net.URI

/**
 * ＋ keeps its page between taps. Add order's Order / Close move that page on (to the order or
 * Orders), so ＋ would show Orders next time: load Add order (POS in POS mode) again unless the
 * page is still on it (an order in progress is kept). Tapping ＋ while on it always starts over.
 */
internal object PlusTab {
    fun shouldReload(alreadyOnPlus: Boolean, currentUrl: String?, plusPath: String): Boolean {
        if (alreadyOnPlus) return true
        if (currentUrl.isNullOrBlank()) return false
        val path = runCatching { URI(currentUrl).path }.getOrNull()?.trimEnd('/') ?: return true
        val want = "/" + plusPath.trim('/')
        return path != want && !path.startsWith("$want/")
    }
}
