package com.sopifo.printagent.data

import com.sopifo.printagent.data.api.UrlPolicy
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlPolicyTest {
    private val release = UrlPolicy(allowLocalhostHttp = false)
    private val debug = UrlPolicy(allowLocalhostHttp = true)

    @Test fun releaseIsHttpsOnly() {
        assertTrue(release.isAllowed("https://app.sopifo.com".toHttpUrl()))
        assertFalse(release.isAllowed("http://app.sopifo.com".toHttpUrl()))
        assertFalse(release.isAllowed("http://localhost:8080".toHttpUrl()))
        assertNull(release.parseAllowed("ftp://x"))
        assertNull(release.parseAllowed("not a url"))
        assertNull(release.parseAllowed(null))
    }

    @Test fun debugAllowsOnlyLocalhostOverHttp() {
        assertTrue(debug.isAllowed("http://localhost:18080".toHttpUrl()))
        assertTrue(debug.isAllowed("http://127.0.0.1:18080".toHttpUrl()))
        assertFalse(debug.isAllowed("http://192.168.1.10".toHttpUrl()))
        assertFalse(debug.isAllowed("http://localhost.evil.com".toHttpUrl()))
    }

    @Test fun jobIdsAreRestrictedToSafePathCharacters() {
        assertTrue(UrlPolicy.isValidJobId("job_123"))
        assertTrue(UrlPolicy.isValidJobId("9f0c2b1e-7c55-4a8b-bb38-0d6a0c0f5f0e"))
        assertFalse(UrlPolicy.isValidJobId(null))
        assertFalse(UrlPolicy.isValidJobId(""))
        assertFalse(UrlPolicy.isValidJobId("../devices"))
        assertFalse(UrlPolicy.isValidJobId(".."))
        assertFalse(UrlPolicy.isValidJobId("a/b"))
        assertFalse(UrlPolicy.isValidJobId("a b"))
        assertFalse(UrlPolicy.isValidJobId("x".repeat(129)))
    }
}

class RegistrationCodeTest {
    @Test fun bareToken() {
        assertEquals(RegistrationCode("abc123XYZ", null), RegistrationCode.parse("  abc123XYZ \n"))
    }

    @Test fun customSchemeUri() {
        assertEquals(
            RegistrationCode("tok_1", "https://staging.sopifo.com"),
            RegistrationCode.parse("sopifo://register?token=tok_1&api=https%3A%2F%2Fstaging.sopifo.com"),
        )
    }

    @Test fun httpsLink() {
        assertEquals(RegistrationCode("tok-2", null), RegistrationCode.parse("https://app.sopifo.com/pair?token=tok-2"))
    }

    @Test fun jsonPayload() {
        val parsed = RegistrationCode.parse("""{"token":"tok3","api_url":"https://x.sopifo.com"}""")
        assertNotNull(parsed)
        assertEquals("tok3", parsed!!.token)
        assertEquals("https://x.sopifo.com", parsed.apiUrl)
    }

    @Test fun rejectsInvalidInput() {
        assertNull(RegistrationCode.parse(null))
        assertNull(RegistrationCode.parse(""))
        assertNull(RegistrationCode.parse("has spaces in it"))
        assertNull(RegistrationCode.parse("abc")) // too short
        assertNull(RegistrationCode.parse("sopifo://register?foo=bar"))
        assertNull(RegistrationCode.parse("{not json"))
        assertNull(RegistrationCode.parse("""{"token":"bad token"}"""))
    }
}
