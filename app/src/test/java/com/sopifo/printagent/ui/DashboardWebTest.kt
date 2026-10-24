package com.sopifo.printagent.ui

import com.sopifo.printagent.ui.screens.DashboardWeb
import com.sopifo.printagent.ui.screens.DashboardWeb.Route
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the in-app dashboard keeps inside and what it hands to Chrome or other apps. */
class DashboardWebTest {
    private val app = "app.sopifo.com"

    @Test fun ourPagesStayInside() {
        assertEquals(Route.INSIDE, DashboardWeb.route("https://app.sopifo.com/dashboard/orders", app))
        assertEquals(Route.INSIDE, DashboardWeb.route("https://sopifo.com/help/printer-setup", app))
        assertEquals(Route.INSIDE, DashboardWeb.route("http://192.168.1.5:3000/dashboard", "192.168.1.5"))
    }

    @Test fun signInPaymentsAndOtherSitesGoToChrome() {
        // Facebook connect starts on our server, then facebook.com; sign-in providers and EPS can't run in a WebView.
        assertEquals(Route.CHROME, DashboardWeb.route("https://app.sopifo.com/api/facebook/connect", app))
        assertEquals(Route.CHROME, DashboardWeb.route("https://www.facebook.com/v19.0/dialog/oauth?x=1", app))
        assertEquals(Route.CHROME, DashboardWeb.route("https://abc.supabase.co/auth/v1/authorize?provider=facebook", app))
        assertEquals(Route.CHROME, DashboardWeb.route("https://pg.eps.com.bd/PG?data=123", app))
        assertEquals(Route.CHROME, DashboardWeb.route("https://evil.example/app.sopifo.com", app))
        assertEquals(Route.CHROME, DashboardWeb.route("https://notsopifo.com/", app))
    }

    @Test fun phoneLinksGoToTheirApps() {
        assertEquals(Route.SYSTEM, DashboardWeb.route("tel:+8801712345678", app))
        assertEquals(Route.SYSTEM, DashboardWeb.route("mailto:help@sopifo.com", app))
        assertEquals(Route.SYSTEM, DashboardWeb.route("intent://pay#Intent;scheme=bkash;end", app))
        assertEquals(Route.SYSTEM, DashboardWeb.route("whatsapp://send?phone=880", app))
    }
}
