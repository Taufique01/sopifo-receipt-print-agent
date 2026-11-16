package com.sopifo.printagent.ui.shell

import com.sopifo.printagent.ui.print.SetupFlags
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App 2.0 choices kept on the phone: language (Bangla by default; the website's Settings →
 * Language changes it too, through the bridge), print-station mode, and the shop facts the
 * dashboard last reported (top bar name, (+) button).
 */
class AppSettings private constructor(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("app_shell", Context.MODE_PRIVATE)

    private val _lang = MutableStateFlow(prefs.getString(KEY_LANG, "bn") ?: "bn")
    val lang: StateFlow<String> = _lang.asStateFlow()

    private val _station = MutableStateFlow(prefs.getBoolean(KEY_STATION, false))
    val station: StateFlow<Boolean> = _station.asStateFlow()

    private val _shop = MutableStateFlow(
        ShopFacts(
            name = prefs.getString(KEY_SHOP_NAME, null),
            slug = prefs.getString(KEY_SHOP_SLUG, null),
            pos = prefs.getBoolean(KEY_POS, false),
            courierCount = prefs.getInt(KEY_COURIERS, 0),
        ),
    )
    val shop: StateFlow<ShopFacts> = _shop.asStateFlow()

    /** The seller finished the "Did it print?" step once (hides the setup card's printer steps). */
    private val _testPrinted = MutableStateFlow(prefs.getBoolean(KEY_TEST_OK, false))
    val testPrinted: StateFlow<Boolean> = _testPrinted.asStateFlow()

    /** The label printer's paper (smart shrink, Print → Sopifo, Quick print start here). */
    private val _labelSize = MutableStateFlow(
        runCatching { com.sopifo.printagent.quick.QuickLabelSize.valueOf(prefs.getString(KEY_LABEL_SIZE, "S2X3")!!) }
            .getOrDefault(com.sopifo.printagent.quick.QuickLabelSize.S2X3),
    )
    val labelSize: StateFlow<com.sopifo.printagent.quick.QuickLabelSize> = _labelSize.asStateFlow()

    fun setLabelSize(size: com.sopifo.printagent.quick.QuickLabelSize, fromWebsite: Boolean = false) {
        prefs.edit {
            putString(KEY_LABEL_SIZE, size.name)
            // Chosen here: the website (Cloud Print label size) gets it at the next chance.
            if (!fromWebsite) putBoolean(KEY_SIZE_PENDING, true)
        }
        _labelSize.value = size
    }

    /** The label size still has to be sent to the website (chosen before login, or offline). */
    val sizePendingSync: Boolean get() = prefs.getBoolean(KEY_SIZE_PENDING, false)
    fun sizeSynced() = prefs.edit { putBoolean(KEY_SIZE_PENDING, false) }

    private fun readSetup() = SetupFlags(
        sizeConfirmed = prefs.getBoolean(KEY_SIZE_OK, false),
        testPrinted = prefs.getBoolean(KEY_TEST_OK, false),
        testFailed = prefs.getBoolean(KEY_TEST_FAILED, false),
        receiptSkipped = prefs.getBoolean(KEY_RECEIPT_SKIPPED, false),
        labelSkipped = prefs.getBoolean(KEY_LABEL_SKIPPED, false),
        printServiceUsed = prefs.getBoolean(KEY_SERVICE_USED, false),
        firstSetupDone = prefs.getBoolean(KEY_FIRST_DONE, false),
        freeServicePrints = prefs.getInt(KEY_FREE_PRINTS, 0),
    )

    private val _setup = MutableStateFlow(readSetup())
    /** Printer setup progress (kept on the phone, carried over at login). */
    val setup: StateFlow<SetupFlags> = _setup.asStateFlow()

    fun updateSetup(change: (SetupFlags) -> SetupFlags) {
        val next = change(_setup.value)
        prefs.edit {
            putBoolean(KEY_SIZE_OK, next.sizeConfirmed)
            putBoolean(KEY_TEST_OK, next.testPrinted)
            putBoolean(KEY_TEST_FAILED, next.testFailed)
            putBoolean(KEY_RECEIPT_SKIPPED, next.receiptSkipped)
            putBoolean(KEY_LABEL_SKIPPED, next.labelSkipped)
            putBoolean(KEY_SERVICE_USED, next.printServiceUsed)
            putBoolean(KEY_FIRST_DONE, next.firstSetupDone)
            putInt(KEY_FREE_PRINTS, next.freeServicePrints)
        }
        _setup.value = next
        _testPrinted.value = next.testPrinted
    }

    /**
     * Logged in on the website inside the app. Kept here too because the print service runs on
     * its own (often in a fresh process) and can't rely on the web view's cookies being on disk.
     */
    val loggedIn: Boolean get() = prefs.getBoolean(KEY_LOGGED_IN, false)
    fun setLoggedIn(value: Boolean) = prefs.edit(commit = true) { putBoolean(KEY_LOGGED_IN, value) }

    /** "Disconnect phone" in the Store menu: no automatic reconnect at login until "Connect phone". */
    private val _userDisconnected = MutableStateFlow(prefs.getBoolean(KEY_USER_DISCONNECTED, false))
    val userDisconnected: StateFlow<Boolean> = _userDisconnected.asStateFlow()
    fun setUserDisconnected(value: Boolean) {
        prefs.edit { putBoolean(KEY_USER_DISCONNECTED, value) }
        _userDisconnected.value = value
    }

    fun setLang(value: String) {
        val v = if (value == "en") "en" else "bn"
        prefs.edit { putString(KEY_LANG, v) }
        _lang.value = v
    }

    fun setStation(on: Boolean) {
        prefs.edit { putBoolean(KEY_STATION, on) }
        _station.value = on
    }

    fun setShop(facts: ShopFacts) {
        prefs.edit {
            putString(KEY_SHOP_NAME, facts.name)
            putString(KEY_SHOP_SLUG, facts.slug)
            putBoolean(KEY_POS, facts.pos)
            putInt(KEY_COURIERS, facts.courierCount)
        }
        _shop.value = facts
    }

    fun setTestPrinted() = updateSetup { it.copy(testPrinted = true, testFailed = false) }

    /**
     * Share → Sopifo prints at once with the last Quick print choices (2.1.20). Offered once,
     * after the first shared file printed from Quick print; switched on Printers.
     */
    private val _autoPrintShared = MutableStateFlow(prefs.getBoolean(KEY_AUTO_SHARED, false))
    val autoPrintShared: StateFlow<Boolean> = _autoPrintShared.asStateFlow()
    fun setAutoPrintShared(on: Boolean) {
        prefs.edit {
            putBoolean(KEY_AUTO_SHARED, on)
            putBoolean(KEY_AUTO_ASKED, true)
        }
        _autoPrintShared.value = on
    }
    /** The "print shared files right away?" question was answered (either way). */
    val autoPrintAsked: Boolean get() = prefs.getBoolean(KEY_AUTO_ASKED, false)

    /** Prints made on this phone (Quick print, Share, Print → Sopifo): this week's count and the nudges. */
    private val _prints = MutableStateFlow(readPrints())
    val prints: StateFlow<PrintCount> = _prints.asStateFlow()
    private fun readPrints() = PrintCount(
        total = prefs.getInt(KEY_PRINTS_TOTAL, 0),
        week = prefs.getInt(KEY_PRINTS_WEEK_NO, 0),
        weekCount = prefs.getInt(KEY_PRINTS_WEEK, 0),
    )
    fun countPrints(n: Int, nowMs: Long = System.currentTimeMillis()) {
        if (n <= 0) return
        val next = GuestNudges.add(_prints.value, n, nowMs)
        prefs.edit {
            putInt(KEY_PRINTS_TOTAL, next.total)
            putInt(KEY_PRINTS_WEEK_NO, next.week)
            putInt(KEY_PRINTS_WEEK, next.weekCount)
        }
        _prints.value = next
    }

    /** The guest home's "you print courier labels" card: ✕ hides it for a week. */
    private val _cardHiddenUntil = MutableStateFlow(prefs.getLong(KEY_CARD_HIDDEN, 0L))
    val cardHiddenUntil: StateFlow<Long> = _cardHiddenUntil.asStateFlow()
    fun hideShopCard(nowMs: Long = System.currentTimeMillis()) {
        val until = nowMs + GuestNudges.CARD_HIDE_MS
        prefs.edit { putLong(KEY_CARD_HIDDEN, until) }
        _cardHiddenUntil.value = until
    }

    /** The day (epoch day) the auto-print strip last showed its sign-up tip: once a day. */
    var stripTipDay: Long
        get() = prefs.getLong(KEY_STRIP_TIP_DAY, -1L)
        set(value) = prefs.edit { putLong(KEY_STRIP_TIP_DAY, value) }

    /** Signing out of the dashboard forgets the shop facts (not the printers or language). */
    fun clearShop() = setShop(ShopFacts())

    companion object {
        private const val KEY_LANG = "lang"
        private const val KEY_STATION = "station"
        private const val KEY_SHOP_NAME = "shop_name"
        private const val KEY_SHOP_SLUG = "shop_slug"
        private const val KEY_POS = "selling_pos"
        private const val KEY_COURIERS = "courier_count"
        private const val KEY_TEST_OK = "test_printed"
        private const val KEY_LABEL_SIZE = "label_size"
        private const val KEY_LOGGED_IN = "logged_in"
        private const val KEY_USER_DISCONNECTED = "user_disconnected"
        private const val KEY_SIZE_PENDING = "label_size_pending"
        private const val KEY_SIZE_OK = "setup_size_ok"
        private const val KEY_TEST_FAILED = "setup_test_failed"
        private const val KEY_RECEIPT_SKIPPED = "setup_receipt_skipped"
        private const val KEY_LABEL_SKIPPED = "setup_label_skipped"
        private const val KEY_SERVICE_USED = "setup_service_used"
        private const val KEY_FIRST_DONE = "setup_first_done"
        private const val KEY_FREE_PRINTS = "service_free_prints"
        private const val KEY_AUTO_SHARED = "auto_print_shared"
        private const val KEY_AUTO_ASKED = "auto_print_asked"
        private const val KEY_PRINTS_TOTAL = "prints_total"
        private const val KEY_PRINTS_WEEK_NO = "prints_week_no"
        private const val KEY_PRINTS_WEEK = "prints_week"
        private const val KEY_CARD_HIDDEN = "shop_card_hidden_until"
        private const val KEY_STRIP_TIP_DAY = "strip_tip_day"

        @Volatile private var instance: AppSettings? = null

        fun get(context: Context): AppSettings =
            instance ?: synchronized(this) { instance ?: AppSettings(context.applicationContext).also { instance = it } }
    }
}

data class ShopFacts(
    val name: String? = null,
    val slug: String? = null,
    /** Selling mode POS: the (+) button opens POS instead of Add order. */
    val pos: Boolean = false,
    val courierCount: Int = 0,
)
