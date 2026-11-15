package com.sopifo.printagent.ui.shell

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A customer's message on its way to Add order (2.1.19). Three ways in:
 * - Share → "Sopifo order", or "Sopifo order" in the text selection menu ([share]);
 * - copied in Messenger / WhatsApp, then back to Sopifo: a bar offers it ([offer], read once per
 *   new copy so Android's "pasted from your clipboard" notice shows only then);
 * - 📋 Paste on Add order reads the clipboard through the bridge ([readClipboard]).
 * The website takes the text with SopifoApp.takeSharedText() and fills the customer fields itself.
 */
object CopiedOrder {
    /** What the bar shows: the message, its phone number and a short first line. */
    data class Offer(val text: String, val phone: String, val preview: String)

    private data class Pending(val text: String, val at: Long)

    /** The bar after coming back with a customer's message copied (null = no bar). */
    val offer = MutableStateFlow<Offer?>(null)

    /** Bumped when Add order should open and take the text. */
    val openTick = MutableStateFlow(0)

    @Volatile private var pending: Pending? = null
    @Volatile private var openedTick = 0

    /** True once per [share]: the shell opens Add order for it (not again after a re-login). */
    fun claimOpen(tick: Int): Boolean {
        if (tick <= openedTick) return false
        openedTick = tick
        return true
    }

    /** The clip already looked at (offered, dismissed or copied before leaving the app). */
    @Volatile private var seenStamp = 0L
    @Volatile private var seenHash = 0

    private const val MAX_CHARS = 4000
    private const val PENDING_MS = 2 * 60_000L
    private const val FRESH_MS = 15 * 60_000L

    /** Shared text, or "Make order" on the bar: Add order opens and takes it. */
    fun share(text: String) {
        val clean = text.trim().take(MAX_CHARS)
        if (clean.isEmpty()) return
        pending = Pending(clean, SystemClock.elapsedRealtime())
        offer.value = null
        openTick.value += 1
    }

    /** The website's Add order takes the waiting text once ("" when none or too old). */
    fun take(): String {
        val p = pending ?: return ""
        pending = null
        return if (SystemClock.elapsedRealtime() - p.at <= PENDING_MS) p.text else ""
    }

    fun dismiss() {
        offer.value = null
    }

    /** Leaving the app: whatever is on the clipboard now was not copied for an order. */
    fun markClipboardSeen(context: Context) {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return
        val desc = runCatching { cm.primaryClipDescription }.getOrNull() ?: return
        seenStamp = maxOf(seenStamp, desc.timestamp)
    }

    /**
     * Back in the app (window focused): a fresh copy with a Bangladesh mobile number in it becomes
     * the bar. The description is checked first (no notice); the text is read only for a new copy.
     */
    fun checkClipboard(context: Context) {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return
        val desc = runCatching { cm.primaryClipDescription }.getOrNull() ?: return
        if (!desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) && !desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)) return
        val stamp = desc.timestamp
        if (stamp != 0L) {
            if (stamp <= seenStamp) return
            seenStamp = stamp
            if (!isFresh(stamp)) return
        }
        val text = runCatching { cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() }.getOrNull() ?: return
        if (stamp == 0L) {
            if (text.hashCode() == seenHash) return
            seenHash = text.hashCode()
        }
        offerFor(text)?.let { offer.value = it }
    }

    /** 📋 Paste on Add order (web views can't read the clipboard themselves). */
    fun readClipboard(context: Context): String {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return ""
        val text = runCatching { cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() }.getOrNull().orEmpty()
        // Pasted here: no bar for it later.
        runCatching { cm.primaryClipDescription }.getOrNull()?.let { seenStamp = maxOf(seenStamp, it.timestamp) }
        offer.value = null
        return text.take(MAX_CHARS)
    }

    /**
     * Copied in the last 15 minutes. ClipDescription.timestamp is wall-clock time on current
     * Android; a value far below that is time since boot, so both are accepted.
     */
    internal fun isFresh(stamp: Long, wallNow: Long = System.currentTimeMillis(), bootNow: Long = SystemClock.elapsedRealtime()): Boolean {
        val age = if (stamp > WALL_CLOCK_FLOOR) wallNow - stamp else bootNow - stamp
        return age in 0..FRESH_MS
    }

    /** 2001-09-09: a timestamp above this is wall-clock time, not time since boot. */
    private const val WALL_CLOCK_FLOOR = 1_000_000_000_000L

    /** The bar for this text, or null when it doesn't look like a customer's message. */
    fun offerFor(text: String): Offer? {
        if (text.length > MAX_CHARS) return null
        val phone = phoneIn(text) ?: return null
        return Offer(text, phone, previewOf(text))
    }

    private val PHONE = Regex("""(?:(?:\+|00)\s?88[\s-]?|88)?0?[\s-]?1[3-9](?:[\s.-]?\d){8}""")

    /** The first Bangladesh mobile number in [text] as 01XXXXXXXXX (Bangla digits too), else null. */
    fun phoneIn(text: String): String? {
        val ascii = toAsciiDigits(text)
        for (m in PHONE.findAll(ascii)) {
            val before = ascii.getOrNull(m.range.first - 1)
            val after = ascii.getOrNull(m.range.last + 1)
            if (before?.isDigit() == true || after?.isDigit() == true) continue
            val digits = m.value.filter { it.isDigit() }
            val local = when {
                digits.length == 13 && digits.startsWith("880") -> "0" + digits.drop(3)
                digits.length == 12 && digits.startsWith("88") -> digits.drop(2)
                digits.length == 11 && digits.startsWith("01") -> digits
                digits.length == 10 && digits.startsWith("1") -> "0$digits"
                else -> null
            }
            if (local != null && local.length == 11 && local[2] in '3'..'9') return local
        }
        return null
    }

    /** A short line for the bar: the first line that isn't just the number. */
    fun previewOf(text: String): String {
        val line = text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && phoneIn(it) == null }
            ?: text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        return if (line.length > 40) line.take(39).trimEnd() + "…" else line
    }

    private fun toAsciiDigits(s: String): String = buildString(s.length) {
        for (ch in s) append(if (ch in '০'..'৯') '0' + (ch - '০') else ch)
    }
}
