package com.sopifo.printagent.ui.shell

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

    fun setTestPrinted() {
        prefs.edit { putBoolean(KEY_TEST_OK, true) }
        _testPrinted.value = true
    }

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
