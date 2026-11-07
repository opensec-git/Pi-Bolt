package opensec.pibolt.app

import opensec.pibolt.app.ui.session.web.FaviconRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which favicon a source gets and its cache key (ui/session/web/FaviconRules.kt). */
class FaviconRulesTest {
    @Test
    fun iconsComeFromTheSiteItselfOverHttps() {
        assertEquals("pi.dev", FaviconRules.iconHost("https://pi.dev/docs/latest"))
        assertEquals("https://pi.dev/favicon.ico", FaviconRules.iconUrl("pi.dev"))
        assertEquals("https://pi.dev/", FaviconRules.homeUrl("pi.dev"))
        // An http page still gets its icon over https; the host is lowercased and the root dot dropped.
        assertEquals("www.example.org", FaviconRules.iconHost("http://WWW.Example.org./a?b#c"))
        // Never a favicon service: nothing in the rules names one.
        for (host in listOf("pi.dev", "kotlinlang.org")) {
            val url = FaviconRules.iconUrl(host)
            assertFalse("google" in url || "duckduckgo" in url || "gstatic" in url, url)
            assertTrue(url.startsWith("https://$host/"))
        }
    }

    @Test
    fun localAndNonWebAddressesGetNoFetch() {
        for (url in listOf(
            "https://192.168.1.10/x", "https://[::1]/", "https://10.0.0.1", "http://localhost:8080/", "https://printer.local/",
            "https://router.lan/", "https://a.internal/", "https://intranet/", "ftp://pi.dev/x", "file:///etc/hosts", "javascript:alert(1)",
            "data:text/html,hi", "not a url", "https://xyz.onion/", "https://foo.localhost/",
        )) {
            assertNull(FaviconRules.iconHost(url), url)
        }
        // International names are fetched by their ASCII form.
        assertEquals("xn--bcher-kva.example.de", FaviconRules.iconHost("https://bücher.example.de/"))
    }

    @Test
    fun theCacheKeyIsTheHostsHashAndStable() {
        val key = FaviconRules.cacheKey("pi.dev")
        assertEquals(32, key.length)
        assertTrue(key.all { it in "0123456789abcdef" })
        assertEquals(key, FaviconRules.cacheKey("PI.DEV"))
        // sha256("pi.dev"), first 32 hex digits.
        assertEquals(java.security.MessageDigest.getInstance("SHA-256").digest("pi.dev".toByteArray()).joinToString("") { "%02x".format(it) }.take(32), key)
        assertFalse(key == FaviconRules.cacheKey("www.pi.dev"))
    }

    @Test
    fun aHomePagesIconLinksAreReadBestFirstHttpsOnly() {
        val html = """
            <html><head>
            <link rel="stylesheet" href="/a.css">
            <link rel="apple-touch-icon" href="/apple.png">
            <LINK REL='shortcut icon' HREF='/static/fav.ico?v=2'>
            <link rel="icon" type="image/svg+xml" href="/fav.svg">
            <link rel="icon" href="http://cdn.example.org/plain.png">
            <link rel="icon" href="data:image/png;base64,AAAA">
            <link href="//cdn.example.org/i.png" rel="icon" sizes="32x32">
            </head></html>
        """.trimIndent()
        assertEquals(
            listOf("https://example.org/static/fav.ico?v=2", "https://cdn.example.org/i.png", "https://example.org/apple.png"),
            FaviconRules.linkIcons(html, "https://example.org/"),
        )
        assertEquals(emptyList(), FaviconRules.linkIcons("<p>no head</p>", "https://example.org/"))
    }

    @Test
    fun onlyImageBytesAreDecodedAndRedirectsStayOnHttps() {
        assertTrue(FaviconRules.looksLikeImage(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A)))
        assertTrue(FaviconRules.looksLikeImage(byteArrayOf(0, 0, 1, 0, 1, 0)))
        assertTrue(FaviconRules.looksLikeImage(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
        assertFalse(FaviconRules.looksLikeImage("<!doctype html><html>".toByteArray()))
        assertFalse(FaviconRules.looksLikeImage(ByteArray(0)))
        assertEquals("https://www.pi.dev/favicon.ico", FaviconRules.redirectTarget("https://pi.dev/favicon.ico", "https://www.pi.dev/favicon.ico"))
        assertEquals("https://pi.dev/static/f.ico", FaviconRules.redirectTarget("https://pi.dev/favicon.ico", "/static/f.ico"))
        assertNull(FaviconRules.redirectTarget("https://pi.dev/favicon.ico", "http://pi.dev/favicon.ico"))
        assertNull(FaviconRules.redirectTarget("https://pi.dev/favicon.ico", "https://192.168.0.1/x"))
        assertNull(FaviconRules.redirectTarget("https://pi.dev/favicon.ico", null))
    }
}
