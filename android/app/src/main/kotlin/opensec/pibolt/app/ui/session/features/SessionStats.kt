package opensec.pibolt.app.ui.session.features

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.JsonObject
import opensec.pibolt.protocol.double
import opensec.pibolt.protocol.long
import opensec.pibolt.protocol.obj
import opensec.pibolt.protocol.str
import opensec.pibolt.protocol.session.SessionReducer
import java.util.Locale

/**
 * `get_session_stats`: the whole session's tokens and cost (assistant turns, tools and summaries) and the context in
 * use, as the terminal's footer shows them.
 */
@Immutable
data class SessionStats(
    val userMessages: Long = 0,
    val assistantMessages: Long = 0,
    val toolCalls: Long = 0,
    val totalMessages: Long = 0,
    val input: Long = 0,
    val output: Long = 0,
    val cacheRead: Long = 0,
    val cacheWrite: Long = 0,
    val total: Long = 0,
    val cost: Double = 0.0,
    /** Tokens in the context now; null right after a compaction until the next reply. */
    val contextTokens: Long? = null,
    val contextWindow: Long? = null,
    val contextPercent: Double? = null,
    val sessionFile: String? = null,
    val sessionId: String? = null,
) {
    /** Share of prompt tokens served from the cache, 0–100; null before any input. */
    val cacheHitPercent: Int?
        get() {
            val prompt = input + cacheRead
            return if (prompt <= 0) null else ((cacheRead * 100.0) / prompt).toInt().coerceIn(0, 100)
        }

    /** Context use 0–1 for the bar: the host's percent, else tokens over the window. */
    val contextFraction: Float?
        get() = contextPercent?.let { (it / 100.0).toFloat().coerceIn(0f, 1f) }
            ?: contextTokens?.let { t -> contextWindow?.takeIf { it > 0 }?.let { (t.toDouble() / it).toFloat().coerceIn(0f, 1f) } }

    /** The terminal footer's line: `↑50k ↓10k R40k W5.0k $0.450`. */
    val footer: String
        get() = buildList {
            add("↑${SessionReducer.formatTokens(input)}")
            add("↓${SessionReducer.formatTokens(output)}")
            if (cacheRead > 0) add("R${SessionReducer.formatTokens(cacheRead)}")
            if (cacheWrite > 0) add("W${SessionReducer.formatTokens(cacheWrite)}")
            if (cost > 0) add(formatCost(cost))
        }.joinToString(" ")

    /** `30.0% of 200k`, `60k of 200k` without a percent, or `? of 200k` after a compaction. */
    val contextLabel: String?
        get() {
            val window = contextWindow?.takeIf { it > 0 } ?: return contextTokens?.let { "${SessionReducer.formatTokens(it)} tokens" }
            val used = contextFraction?.let { String.format(Locale.ROOT, "%.1f%%", it * 100) } ?: "?"
            return "$used of ${SessionReducer.formatTokens(window)}"
        }

    companion object {
        fun from(data: JsonObject?): SessionStats? {
            data ?: return null
            val tokens = data.obj("tokens")
            val context = data.obj("contextUsage")
            return SessionStats(
                userMessages = data.long("userMessages") ?: 0,
                assistantMessages = data.long("assistantMessages") ?: 0,
                toolCalls = data.long("toolCalls") ?: 0,
                totalMessages = data.long("totalMessages") ?: 0,
                input = tokens?.long("input") ?: 0,
                output = tokens?.long("output") ?: 0,
                cacheRead = tokens?.long("cacheRead") ?: 0,
                cacheWrite = tokens?.long("cacheWrite") ?: 0,
                total = tokens?.long("total") ?: 0,
                cost = data.double("cost") ?: 0.0,
                contextTokens = context?.long("tokens"),
                contextWindow = context?.long("contextWindow"),
                contextPercent = context?.double("percent"),
                sessionFile = data.str("sessionFile"),
                sessionId = data.str("sessionId"),
            )
        }

        /** `$0.452`, `$12.30`, `<$0.001`, or `$0` for free models. */
        fun formatCost(cost: Double): String = when {
            cost <= 0.0 -> "$0"
            cost < 0.001 -> "<$0.001"
            cost < 10 -> String.format(Locale.ROOT, "$%.3f", cost)
            else -> String.format(Locale.ROOT, "$%.2f", cost)
        }
    }
}
