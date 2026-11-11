package opensec.pibolt.app

import opensec.pibolt.app.ui.session.ErrorAction
import opensec.pibolt.app.ui.session.ErrorClass
import opensec.pibolt.app.ui.session.ErrorNotices
import opensec.pibolt.app.ui.session.ErrorText
import opensec.pibolt.protocol.session.AssistantTextItem
import opensec.pibolt.protocol.session.NoticeItem
import opensec.pibolt.protocol.session.NoticeKind
import opensec.pibolt.protocol.session.RetryInfo
import opensec.pibolt.protocol.session.UserItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ErrorTextTest {
    @Test
    fun readsStatusAndMessageFromTheProviderBody() {
        val e = ErrorText.parse("""503: {"message":"The server is overloaded. Please try again later.","type":"server_error"}""")
        assertEquals(ErrorClass.Overloaded, e.kind)
        assertEquals(503, e.status)
        assertEquals("The server is overloaded. Please try again later.", e.message)
        assertEquals("503 · The server is overloaded. Please try again later.", ErrorText.detail(e))
        assertEquals("Fake is overloaded", ErrorText.title(e, "Fake Sonnet 4.5", "Fake"))
    }

    @Test
    fun nestedErrorObjectsAndPlainStatusPrefixes() {
        val anthropic = ErrorText.parse("""529 {"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""")
        assertEquals(ErrorClass.Overloaded, anthropic.kind)
        assertEquals(529, anthropic.status)
        assertEquals("Overloaded", anthropic.message)
        val plain = ErrorText.parse("500 Internal server error")
        assertEquals(500, plain.status)
        assertEquals("Internal server error", plain.message)
        assertEquals("The provider had a server error", ErrorText.title(plain, null, null))
    }

    @Test
    fun contextOverflow() {
        val e = ErrorText.parse("""400: {"message":"This model's maximum context length is 128000 tokens. However, your messages resulted in 131072 tokens.","type":"invalid_request_error","code":"context_length_exceeded"}""")
        assertEquals(ErrorClass.ContextOverflow, e.kind)
        assertEquals(400, e.status)
        assertEquals("Too long for Fake Sonnet 4.5", ErrorText.title(e, "Fake Sonnet 4.5", null))
        assertEquals(listOf(ErrorAction.Compact, ErrorAction.SwitchModel, ErrorAction.NewSession), ErrorText.actions(e, local = true, running = false, retrying = false))
        assertEquals(ErrorClass.ContextOverflow, ErrorText.parse("prompt is too long: 210000 tokens > 200000 maximum").kind)
        assertEquals(ErrorClass.ContextOverflow, ErrorText.parse("413 Request Entity Too Large").kind)
        // A rate limit that mentions tokens is not an overflow.
        assertEquals(ErrorClass.RateLimit, ErrorText.parse("429: Rate limit reached: too many tokens per minute").kind)
    }

    @Test
    fun keyErrors() {
        val e = ErrorText.parse("""401: {"message":"Incorrect API key provided: sk-***.","type":"invalid_request_error","code":"invalid_api_key"}""")
        assertEquals(ErrorClass.Auth, e.kind)
        assertEquals("OpenAI rejected the key", ErrorText.title(e, null, "OpenAI"))
        assertEquals(listOf(ErrorAction.UpdateKey, ErrorAction.SwitchModel), ErrorText.actions(e, local = true, running = false, retrying = false))
        // A remote computer keeps its keys: no Update key on the phone.
        assertEquals(listOf(ErrorAction.SwitchModel), ErrorText.actions(e, local = false, running = false, retrying = false))
        assertEquals(ErrorClass.Auth, ErrorText.parse("403 Forbidden").kind)
        assertEquals(ErrorClass.Auth, ErrorText.parse("""{"error":{"type":"authentication_error","message":"invalid x-api-key"}}""").kind)
    }

    @Test
    fun billingBeforeRateLimit() {
        assertEquals(ErrorClass.Billing, ErrorText.parse("""402: {"message":"Insufficient credits.","code":402}""").kind)
        // OpenAI sends quota exhaustion as a 429.
        assertEquals(ErrorClass.Billing, ErrorText.parse("""429: {"message":"You exceeded your current quota.","type":"insufficient_quota"}""").kind)
        val rate = ErrorText.parse("""429: {"message":"Rate limit reached for requests per minute.","code":"rate_limit_exceeded"}""")
        assertEquals(ErrorClass.RateLimit, rate.kind)
        assertEquals("Rate limited", ErrorText.title(rate, null, null))
        assertEquals(listOf(ErrorAction.Retry, ErrorAction.SwitchModel), ErrorText.actions(rate, local = true, running = false, retrying = false))
    }

    @Test
    fun networkAndUnknown() {
        val net = ErrorText.parse("fetch failed")
        assertEquals(ErrorClass.Network, net.kind)
        assertNull(net.status)
        assertEquals("Can't reach Groq", ErrorText.title(net, null, "Groq"))
        assertEquals(listOf(ErrorAction.Retry), ErrorText.actions(net, local = true, running = false, retrying = false))
        val other = ErrorText.parse("Something odd happened")
        assertEquals(ErrorClass.Other, other.kind)
        assertEquals("The model returned an error", ErrorText.title(other, null, null))
        assertEquals("Something odd happened", ErrorText.detail(other))
    }

    @Test
    fun actionsWhileRunningOrRetrying() {
        val e = ErrorText.parse("503 Service Unavailable")
        assertEquals(listOf(ErrorAction.SwitchModel), ErrorText.actions(e, local = true, running = true, retrying = false))
        assertEquals(listOf(ErrorAction.Stop), ErrorText.actions(e, local = true, running = true, retrying = true))
    }

    private fun err(key: String, title: String = "Error", text: String = "503: overloaded") = NoticeItem(key, NoticeKind.Error, title, text)

    @Test
    fun retriesCollapseIntoOneNotice() {
        val items = listOf(
            UserItem("u1", "audit-retry"),
            err("m1.error"),
            err("m2.error"),
            err("m3.error"),
            err("m9", "Retry failed", "503: still overloaded"),
        )
        val (out, groups) = ErrorNotices.collapse(items, retry = null)
        assertEquals(2, out.size)
        val notice = out[1] as NoticeItem
        // The first key stays, so the row does not move as attempts are added; the text is the last one.
        assertEquals("m1.error", notice.key)
        assertEquals("503: still overloaded", notice.text)
        assertEquals(3, groups.getValue("m1.error").attempts)
        assertNull(groups.getValue("m1.error").retry)
    }

    @Test
    fun liveRetryBelongsToTheLastGroupOnly() {
        val retry = RetryInfo(attempt = 2, maxAttempts = 3, delayMs = 2000, error = "503")
        val items = listOf(UserItem("u1", "a"), err("m1.error"), AssistantTextItem("m2", "Done.", false), UserItem("u2", "b"), err("m3.error"))
        val (out, groups) = ErrorNotices.collapse(items, retry)
        assertEquals(5, out.size)
        assertNull(groups.getValue("m1.error").retry)
        assertEquals(retry, groups.getValue("m3.error").retry)
        // Once the next attempt streams, the notice is no longer last: no live retry on it.
        val (_, later) = ErrorNotices.collapse(items + AssistantTextItem("m4", "Hel", true), retry)
        assertNull(later.getValue("m3.error").retry)
    }

    @Test
    fun overflowThenCompactionFailedIsOneNoticeExplainedAsOverflow() {
        val items = listOf(
            err("m1.error", text = "400: {\"message\":\"maximum context length is 128000 tokens\",\"code\":\"context_length_exceeded\"}"),
            err("m2", "Compaction failed", "Summarization failed"),
        )
        val (out, groups) = ErrorNotices.collapse(items, null)
        assertEquals(1, out.size)
        assertEquals(ErrorClass.ContextOverflow, groups.getValue("m1.error").error().kind)
        assertEquals(1, groups.getValue("m1.error").attempts)
    }

    @Test
    fun otherNoticesAreLeftAlone() {
        val items = listOf(err("m1", "Extension error", "boom"), NoticeItem("m2", NoticeKind.Aborted, "Aborted"))
        val (out, groups) = ErrorNotices.collapse(items, null)
        assertEquals(items, out)
        assertTrue(groups.isEmpty())
        assertFalse(ErrorNotices.isProviderError(items[0]))
    }
}
