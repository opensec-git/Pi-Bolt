package opensec.pibolt.app.ui.session.ask

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import opensec.pibolt.app.engine.ChatSpace
import opensec.pibolt.protocol.ProtocolJson
import opensec.pibolt.protocol.bool
import opensec.pibolt.protocol.str
import opensec.pibolt.protocol.session.ChatItem
import opensec.pibolt.protocol.session.DialogKind
import opensec.pibolt.protocol.session.DialogRequest
import opensec.pibolt.protocol.session.SessionState
import opensec.pibolt.protocol.session.ToolItem
import opensec.pibolt.protocol.session.UserItem

@Immutable
data class AskOption(val label: String, val description: String, val preview: String? = null)

/** One question of an `ask_user_question` call: [header] is its chip ("Database"), [options] 2 to 4. */
@Immutable
data class AskQuestion(val question: String, val header: String, val options: List<AskOption>, val multiSelect: Boolean)

/**
 * The card's answer to one question: the options picked ([picked], indexes; one at most for a single-select question)
 * and the typed text ([text], "Type something"). A single-select answer is an option or text, never both.
 */
@Immutable
data class AskAnswer(val picked: Set<Int> = emptySet(), val text: String = "") {
    val typed: String get() = text.trim()
    val isEmpty: Boolean get() = picked.isEmpty() && typed.isEmpty()
}

/** What the app sends back for one of the walker's dialogs. */
sealed interface AskReply {
    data class Value(val value: String) : AskReply

    data object Cancel : AskReply
}

/** The card's answers, handed to [AskResponder] on Submit; [cancel] when the user dismissed the card. */
data class AskSubmission(val toolCallId: String, val questions: List<AskQuestion>, val answers: List<AskAnswer>, val cancel: Boolean = false)

/**
 * `ask_user_question` (rpiv-ask-user-question 2.15.0) as one native question card.
 *
 * In RPC mode the extension walks its questions with the host's dialogs (rpc-fallback.ts, `runRpcQuestionnaire`):
 * - single-select: `select(title, options)` with title `[<header>] <question>` (previews appended) and options
 *   `N. <label> — <description>` plus a last `N+1. Type something.` row. The reply is the chosen line; the last row
 *   asks `input(<question, the option list>, "")` for the typed answer.
 * - multi-select: `input(title, "1,3; custom answer")`: the numbers of the picked options, comma-separated, and
 *   optionally `; <text>` (only text: `; <text>`, so text that looks like numbers stays text; nothing: an empty answer).
 * - dismissing a question's dialog cancels the whole questionnaire ("User declined to answer questions"); dismissing
 *   the typed-answer input goes back to that question's options.
 *
 * The card shows every question from the call's arguments (the tool call in flight); the dialogs are answered from the
 * card's answers only on Submit ([AskResponder]), so Back works across questions. Dialogs of other extensions, or of a
 * call whose arguments the app cannot read, keep the generic dialog sheet.
 */
object AskQuestions {
    const val TOOL = ChatSpace.ASK_TOOL

    /** The questions of a call's arguments (also double-encoded ones, as the extension accepts); null when unreadable. */
    fun parse(args: JsonObject?): List<AskQuestion>? {
        val list = decode(args?.get("questions")) as? JsonArray ?: return null
        val questions = list.map { element ->
            val q = decode(element) as? JsonObject ?: return null
            val options = (decode(q["options"]) as? JsonArray)?.map { o ->
                val option = decode(o) as? JsonObject ?: return null
                AskOption(norm(option.str("label") ?: return null), norm(option.str("description").orEmpty()), option.str("preview")?.let(::norm)?.takeIf { it.isNotEmpty() })
            } ?: return null
            AskQuestion(norm(q.str("question") ?: return null), norm(q.str("header").orEmpty()), options, q.bool("multiSelect") ?: false)
        }
        return questions.takeIf { it.isNotEmpty() && it.all { q -> q.options.isNotEmpty() } }
    }

    private fun decode(element: JsonElement?): JsonElement? = when {
        element is JsonPrimitive && element.isString -> runCatching { ProtocolJson.parseToJsonElement(element.content) }.getOrNull()
        else -> element
    }

    /** The extension normalises line endings before it builds its dialogs (#192); the titles are matched the same way. */
    private fun norm(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')

    /** The `ask_user_question` call of the live turn that is still running, newest first. */
    fun activeCall(items: List<ChatItem>): ToolItem? {
        for (i in items.indices.reversed()) {
            val item = items[i]
            if (item is UserItem) return null
            if (item is ToolItem && item.name == TOOL && item.isActive) return item
        }
        return null
    }

    /** The running call that [dialog] belongs to, with its questions; null for another extension's dialog. */
    fun owner(state: SessionState, dialog: DialogRequest): Pair<ToolItem, List<AskQuestion>>? {
        val call = activeCall(state.streaming) ?: activeCall(state.items) ?: return null
        val questions = parse(call.args) ?: return null
        return if (stepOf(dialog, questions) != null) call to questions else null
    }

    /** Which prompt of the walker a dialog is. */
    sealed interface Step {
        val question: Int

        /** A single-select question's options. */
        data class Options(override val question: Int) : Step

        /** The typed answer of a single-select question (after its "Type something." row). */
        data class Typed(override val question: Int) : Step

        /** A multi-select question's numbers-and-text input. */
        data class Multi(override val question: Int) : Step
    }

    /** The walker's prefix of every dialog title for question [q]: `[header] question`. */
    fun titleOf(q: AskQuestion): String = (if (q.header.isNotEmpty()) "[${q.header}] " else "") + q.question

    /** The step [dialog] is, or null when it is no dialog of these questions. */
    fun stepOf(dialog: DialogRequest, questions: List<AskQuestion>): Step? {
        val index = questionOf(dialog.title, questions) ?: return null
        val q = questions[index]
        return when (dialog.kind) {
            DialogKind.Select -> if (q.multiSelect || dialog.options.size < 2) null else Step.Options(index)
            DialogKind.Input -> if (q.multiSelect) Step.Multi(index) else Step.Typed(index)
            else -> null
        }
    }

    private fun squash(text: String) = text.replace(Regex("\\s+"), " ").trim()

    /**
     * The question a walker dialog [title] asks. Its title is `[header] question`, alone (a select) or followed by a
     * blank line and more (the option list of an input, a select's previews), so a question matches only up to that
     * boundary: `[Scope] Deploy?` is not the question of `[Scope] Deploy? Which region?`. Of the questions that match
     * (one whose text goes on with a blank line, e.g. `Deploy?` and `Deploy?\n\nWhich region?`), the longest wins.
     * Failing that, a whole title equal with whitespace squashed (a host that reflowed it); never a bare prefix.
     */
    fun questionOf(title: String, questions: List<AskQuestion>): Int? {
        fun pick(t: String, titleOf: (AskQuestion) -> String, boundary: (String) -> Boolean): Int? =
            questions.indices
                .filter { i -> titleOf(questions[i]).let { q -> t.startsWith(q) && boundary(t.substring(q.length)) } }
                .maxByOrNull { titleOf(questions[it]).length }
        return pick(title, ::titleOf) { rest -> rest.isEmpty() || rest.startsWith("\n\n") }
            ?: pick(squash(title), { squash(titleOf(it)) }) { rest -> rest.isEmpty() }
    }

    /**
     * The reply to [dialog] (a step of [submission]'s questions) from the card's answers; null when the dialog is not
     * one of them.
     */
    fun reply(submission: AskSubmission, dialog: DialogRequest): AskReply? {
        val step = stepOf(dialog, submission.questions) ?: return null
        if (submission.cancel) return AskReply.Cancel
        val q = submission.questions[step.question]
        val answer = submission.answers.getOrNull(step.question) ?: AskAnswer()
        return when (step) {
            is Step.Options -> {
                val picked = answer.picked.firstOrNull()
                when {
                    answer.typed.isNotEmpty() -> AskReply.Value(dialog.options.last())
                    picked != null && picked in q.options.indices -> AskReply.Value(
                        // The offered line; by number when the dialog's rows are not the call's options plus one.
                        if (dialog.options.size == q.options.size + 1) dialog.options[picked] else "${picked + 1}",
                    )
                    // Not answered (Submit is disabled then): the walker could only take it as a dismissal.
                    else -> AskReply.Cancel
                }
            }
            is Step.Typed -> if (answer.typed.isNotEmpty()) AskReply.Value(answer.typed) else AskReply.Cancel
            is Step.Multi -> AskReply.Value(multiValue(answer, q.options.size))
        }
    }

    /** A multi-select answer as the walker parses it: `1,3`, `1,3; text`, `; text`, or empty. */
    fun multiValue(answer: AskAnswer, options: Int): String {
        val numbers = answer.picked.filter { it in 0 until options }.sorted().joinToString(",") { "${it + 1}" }
        return when {
            answer.typed.isEmpty() -> numbers
            numbers.isEmpty() -> "; ${answer.typed}"
            else -> "$numbers; ${answer.typed}"
        }
    }

    /** Whether the card may submit: every question from [from] on answered (a multi-select one may stay empty). */
    fun complete(questions: List<AskQuestion>, answers: List<AskAnswer>, from: Int = 0): Boolean =
        questions.indices.drop(from).all { i -> questions[i].multiSelect || !(answers.getOrNull(i) ?: AskAnswer()).isEmpty }
}

/**
 * Answers the walker's dialogs from a submitted card, one at a time as they arrive (SessionController calls [next] on
 * every state change), until the call ends. A dialog is answered once.
 */
class AskResponder {
    @Volatile
    var submission: AskSubmission? = null
        private set
    private val answered = HashSet<String>()

    fun submit(submission: AskSubmission) {
        this.submission = submission
    }

    /** True while [toolCallId]'s answers are being sent: the card stays closed. */
    fun submitting(toolCallId: String): Boolean = submission?.toolCallId == toolCallId

    /** The next dialog to answer and how; null when there is none. Forgets the submission once its call ended. */
    @Synchronized
    fun next(state: SessionState): Pair<String, AskReply>? {
        val s = submission ?: return null
        val call = (state.streaming + state.items).lastOrNull { it is ToolItem && it.toolCallId == s.toolCallId } as ToolItem?
        if (call == null || !call.isActive) {
            submission = null
            answered.clear()
            return null
        }
        for (dialog in state.dialogs) {
            if (dialog.id in answered) continue
            val reply = AskQuestions.reply(s, dialog) ?: continue
            answered += dialog.id
            return dialog.id to reply
        }
        return null
    }
}
