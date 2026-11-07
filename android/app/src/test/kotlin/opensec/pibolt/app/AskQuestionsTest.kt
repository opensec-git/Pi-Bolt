package opensec.pibolt.app

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import opensec.pibolt.app.ui.session.ask.AskAnswer
import opensec.pibolt.app.ui.session.ask.AskOption
import opensec.pibolt.app.ui.session.ask.AskQuestion
import opensec.pibolt.app.ui.session.ask.AskQuestions
import opensec.pibolt.app.ui.session.ask.AskQuestions.Step
import opensec.pibolt.app.ui.session.ask.AskReply
import opensec.pibolt.app.ui.session.ask.AskResponder
import opensec.pibolt.app.ui.session.ask.AskSubmission
import opensec.pibolt.protocol.ProtocolJson
import opensec.pibolt.protocol.session.DialogKind
import opensec.pibolt.protocol.session.DialogRequest
import opensec.pibolt.protocol.session.SessionReducer
import opensec.pibolt.protocol.session.SessionState
import opensec.pibolt.protocol.session.ToolItem
import opensec.pibolt.protocol.session.ToolStatus
import opensec.pibolt.protocol.session.UserItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The question card's answers as rpiv-ask-user-question 2.15.0's RPC walker expects them (ui/session/ask). The
 * fixtures are real runs of the engine (fixtures/engine): the walker's select/input requests, and the answers that
 * produced the recorded result. Replaying them, the card's state must give exactly those answers.
 */
class AskQuestionsTest {
    private val args = ProtocolJson.parseToJsonElement(
        """{"questions":[
            {"question":"Which database should we use?","header":"Database","options":[
              {"label":"Postgres","description":"Relational, mature"},{"label":"SQLite","description":"Embedded, simple"}]},
            {"question":"Which features do you want?","header":"Features","multiSelect":true,"options":[
              {"label":"Auth","description":"Sign in"},{"label":"Search","description":"Full text"},{"label":"Export","description":"CSV"}]}]}""",
    ) as JsonObject
    private val questions = AskQuestions.parse(args)!!

    /**
     * Replays [fixture] as the app sees it: every event through the reducer; at each dialog, [AskResponder.next] must
     * answer it as recorded. Returns the replies and the call's final result text.
     */
    private fun replay(fixture: String, submission: (String) -> AskSubmission): Pair<List<AskReply>, String?> {
        val recorded = EngineFixture.header(fixture).answers
        val responder = AskResponder()
        var state = SessionState(hello = true, items = listOf(UserItem("u1", "go")))
        val replies = ArrayList<AskReply>()
        for (event in EngineFixture.events(fixture)) {
            state = SessionReducer.reduce(state, event)
            val call = AskQuestions.activeCall(state.allItems)
            if (call != null && responder.submission == null && state.dialogs.isNotEmpty()) {
                // The card opens on the first dialog; its owner is the running call.
                val owner = assertNotNull(AskQuestions.owner(state, state.dialogs.first()))
                assertEquals(call.toolCallId, owner.first.toolCallId)
                responder.submit(submission(call.toolCallId))
            }
            while (true) {
                val (id, reply) = responder.next(state) ?: break
                replies += reply
                state = state.copy(dialogs = state.dialogs.filterNot { it.id == id })
            }
        }
        assertEquals(recorded.map { if (it == null) AskReply.Cancel else AskReply.Value(it) }, replies, "the recorded answers of $fixture")
        val result = state.allItems.filterIsInstance<ToolItem>().last { it.name == AskQuestions.TOOL }
        assertEquals(ToolStatus.Done, result.status)
        assertNull(responder.submission, "forgotten once the call ended")
        return replies to result.result
    }

    @Test
    fun theCallsArgumentsAreItsQuestions() {
        assertEquals(2, questions.size)
        assertEquals(AskQuestion("Which database should we use?", "Database", questions[0].options, multiSelect = false), questions[0])
        assertEquals(listOf("Postgres", "SQLite"), questions[0].options.map { it.label })
        assertEquals("Embedded, simple", questions[0].options[1].description)
        assertTrue(questions[1].multiSelect)
        // Double-encoded arguments, as some models send them (the extension decodes them too).
        val encoded = JsonObject(mapOf("questions" to JsonPrimitive(args["questions"].toString())))
        assertEquals(questions, AskQuestions.parse(encoded))
        assertNull(AskQuestions.parse(null))
        assertNull(AskQuestions.parse(ProtocolJson.parseToJsonElement("""{"questions":[{"question":"q?","header":"h"}]}""") as JsonObject))
        assertNull(AskQuestions.parse(ProtocolJson.parseToJsonElement("""{"questions":[]}""") as JsonObject))
    }

    @Test
    fun theRecordedDialogsAreTheWalkersSteps() {
        val dialogs = EngineFixture.events("ask-back").filter { (it["type"] as JsonPrimitive).content == "extension_ui_request" }.map { SessionReducer.dialogFrom(it)!! }
        assertEquals(listOf(Step.Options(0), Step.Typed(0), Step.Options(0), Step.Multi(1)), dialogs.map { AskQuestions.stepOf(it, questions) })
        assertEquals(listOf("1. Postgres — Relational, mature", "2. SQLite — Embedded, simple", "3. Type something."), dialogs[0].options)
        assertEquals("[Database] Which database should we use?", AskQuestions.titleOf(questions[0]))
        // Another extension's dialog is not the card's.
        assertNull(AskQuestions.stepOf(DialogRequest("x", DialogKind.Select, "Pick a branch", options = listOf("main", "dev")), questions))
        assertNull(AskQuestions.stepOf(DialogRequest("y", DialogKind.Confirm, "[Database] Which database should we use?"), questions))
    }

    @Test
    fun singleChoiceAndSeveralChoicesWithText() {
        // project-web-ask: Postgres; Auth and Export plus "also dark mode".
        val (_, result) = replay("project-web-ask") { id ->
            AskSubmission(id, questions, listOf(AskAnswer(picked = setOf(0)), AskAnswer(picked = setOf(2, 0), text = "  also dark mode ")))
        }
        assertEquals(
            "User has answered your questions: \"Which database should we use?\"=\"Postgres\". \"Which features do you want?\"=\"Auth, Export, also dark mode\". You can now continue with the user's answers in mind.",
            result,
        )
    }

    @Test
    fun typedAnswersForBothKindsOfQuestion() {
        // chat-web-ask: a typed answer to the single choice (its "Type something." row, then the text), text only on
        // the multi-select (written "; text", so it is never read as option numbers).
        val (_, result) = replay("chat-web-ask") { id ->
            AskSubmission(id, questions, listOf(AskAnswer(picked = emptySet(), text = "MySQL please"), AskAnswer(text = "only my own text")))
        }
        assertTrue("\"Which database should we use?\"=\"MySQL please\"" in result!! && "\"Which features do you want?\"=\"only my own text\"" in result, result)
    }

    @Test
    fun dismissingTheCardDeclinesTheQuestionnaire() {
        val (_, result) = replay("ask-cancel") { id -> AskSubmission(id, questions, List(2) { AskAnswer() }, cancel = true) }
        assertEquals("User declined to answer questions", result)
    }

    @Test
    fun aQuestionThatStartsWithAnothersTextIsNotTakenForIt() {
        // PR #266 review: `[Scope] Deploy?` was matched for `[Scope] Deploy? Which region?`.
        fun q(text: String, multi: Boolean = false) = AskQuestion(text, "Scope", listOf(AskOption("A", "a"), AskOption("B", "b")), multi)
        for (order in listOf(listOf(q("Deploy?"), q("Deploy? Which region?", multi = true)), listOf(q("Deploy? Which region?", multi = true), q("Deploy?")))) {
            val short = order.indexOfFirst { it.question == "Deploy?" }
            val long = 1 - short
            val select = DialogRequest("s", DialogKind.Select, "[Scope] Deploy?", options = listOf("1. A — a", "2. B — b", "3. Type something."))
            val multi = DialogRequest("m", DialogKind.Input, "[Scope] Deploy? Which region?\n\n1. A — a\n2. B — b\n\nEnter the numbers", placeholder = "1,3; custom answer")
            val typed = DialogRequest("t", DialogKind.Input, "[Scope] Deploy?\n\n1. A — a\n2. B — b\n\nType your answer:")
            assertEquals(Step.Options(short), AskQuestions.stepOf(select, order))
            assertEquals(Step.Multi(long), AskQuestions.stepOf(multi, order))
            assertEquals(Step.Typed(short), AskQuestions.stepOf(typed, order))
            val answers = MutableList(2) { AskAnswer() }
            answers[short] = AskAnswer(picked = setOf(1))
            answers[long] = AskAnswer(picked = setOf(0, 1))
            val submission = AskSubmission("c", order, answers)
            assertEquals(AskReply.Value("2. B — b"), AskQuestions.reply(submission, select))
            assertEquals(AskReply.Value("1,2"), AskQuestions.reply(submission, multi))
        }
        // A select with previews: the question ends at the blank line before them.
        val withPreview = listOf(q("Layout?"), q("Layout? Mobile too?"))
        assertEquals(0, AskQuestions.questionOf("[Scope] Layout?\n\n--- 1. A preview ---\nx", withPreview))
        assertEquals(1, AskQuestions.questionOf("[Scope] Layout? Mobile too?", withPreview))
        assertNull(AskQuestions.questionOf("[Scope] Layout? Desktop?", withPreview))
    }

    @Test
    fun aDeclinedQuestionnaireReadsDeclined() {
        // Xiaomi round: a dismissed card showed "✓ Asked".
        val events = EngineFixture.events("ask-cancel")
        var state = SessionState(hello = true, items = listOf(UserItem("u1", "go")))
        for (e in events) state = SessionReducer.reduce(state, e)
        val declined = state.allItems.filterIsInstance<ToolItem>().last()
        assertTrue(opensec.pibolt.app.ui.session.isDeclinedAsk(declined))
        assertEquals("Declined", opensec.pibolt.app.ui.session.toolLabel(declined).past)
        var answered = SessionState(hello = true, items = listOf(UserItem("u1", "go")))
        for (e in EngineFixture.events("ask-back")) answered = SessionReducer.reduce(answered, e)
        val ok = answered.allItems.filterIsInstance<ToolItem>().last()
        assertTrue(!opensec.pibolt.app.ui.session.isDeclinedAsk(ok))
        assertEquals("Asked", opensec.pibolt.app.ui.session.toolLabel(ok).past)
    }

    @Test
    fun theWalkersBackToOptionsIsAnsweredFromTheCard() {
        // ask-back: the client cancelled the typed input, which returns to the options (not a decline); then SQLite and
        // option 2. The card never sends a blank typed answer: a typed step without text is answered by going back.
        val back = EngineFixture.events("ask-back").filter { (it["type"] as JsonPrimitive).content == "extension_ui_request" }.map { SessionReducer.dialogFrom(it)!! }
        val typedThenNothing = AskSubmission("c", questions, listOf(AskAnswer(text = " "), AskAnswer()))
        assertEquals(AskReply.Cancel, AskQuestions.reply(typedThenNothing, back[1]))
        val sqlite = AskSubmission("c", questions, listOf(AskAnswer(picked = setOf(1)), AskAnswer(picked = setOf(1))))
        assertEquals(AskReply.Value("2. SQLite — Embedded, simple"), AskQuestions.reply(sqlite, back[2]))
        assertEquals(AskReply.Value("2"), AskQuestions.reply(sqlite, back[3]))
        // A cancelled card cancels whatever step is open, the typed input included (it would only go back otherwise,
        // and the next options dialog is cancelled in turn).
        val cancelled = sqlite.copy(cancel = true)
        assertEquals(listOf(AskReply.Cancel, AskReply.Cancel, AskReply.Cancel, AskReply.Cancel), back.map { AskQuestions.reply(cancelled, it) })
    }

    @Test
    fun aRealModelsFourQuestionsMapToTheWalkersDialogs() {
        // real-chat: a chat with a real model (opencode-go/deepseek-v4.1-flash) asked four questions of its own, the last
        // a multi-select; the recording's client answered "1" to each. The card reads the same call and dialogs.
        val events = EngineFixture.events("real-chat")
        val call = events.first { (it["type"] as JsonPrimitive).content == "tool_execution_start" && (it["toolName"] as JsonPrimitive).content == AskQuestions.TOOL }
        val asked = assertNotNull(AskQuestions.parse(call["args"] as JsonObject))
        assertEquals(listOf("Data shape", "Hosting", "Scale", "Priority"), asked.map { it.header })
        assertEquals(listOf(false, false, false, true), asked.map { it.multiSelect })
        val dialogs = events.filter { (it["type"] as JsonPrimitive).content == "extension_ui_request" }.map { SessionReducer.dialogFrom(it)!! }
        assertEquals(listOf(Step.Options(0), Step.Options(1), Step.Options(2), Step.Multi(3)), dialogs.map { AskQuestions.stepOf(it, asked) })
        val first = AskSubmission("c", asked, listOf(AskAnswer(picked = setOf(0)), AskAnswer(picked = setOf(0)), AskAnswer(picked = setOf(3)), AskAnswer(picked = setOf(0, 1))))
        assertEquals(AskReply.Value(dialogs[0].options[0]), AskQuestions.reply(first, dialogs[0]))
        assertTrue((AskQuestions.reply(first, dialogs[1]) as AskReply.Value).value.startsWith("1. Managed cloud DB (Recommended) — "))
        assertTrue((AskQuestions.reply(first, dialogs[2]) as AskReply.Value).value.startsWith("4. "))
        assertEquals(AskReply.Value("1,2"), AskQuestions.reply(first, dialogs[3]))
        // The recorded answers ("1") were taken as the first options, as the walker reads a bare number.
        val result = events.last { (it["type"] as JsonPrimitive).content == "tool_execution_end" && (it["toolName"] as JsonPrimitive).content == AskQuestions.TOOL }
        assertTrue("\"How do you want to run it?\"=\"Managed cloud DB (Recommended)\"" in result.toString().replace("\\\"", "\""), result.toString())
    }

    @Test
    fun multiSelectValues() {
        assertEquals("", AskQuestions.multiValue(AskAnswer(), 3))
        assertEquals("1,3", AskQuestions.multiValue(AskAnswer(picked = setOf(2, 0)), 3))
        assertEquals("1,3; dark mode", AskQuestions.multiValue(AskAnswer(picked = setOf(0, 2), text = "dark mode"), 3))
        assertEquals("; 2", AskQuestions.multiValue(AskAnswer(text = "2"), 3))
        assertEquals("; a; b", AskQuestions.multiValue(AskAnswer(text = "a; b"), 3))
        // An index outside the options is never sent.
        assertEquals("2", AskQuestions.multiValue(AskAnswer(picked = setOf(1, 7)), 3))
    }

    @Test
    fun singleChoiceByNumberWhenTheRowsDifferAndCompleteness() {
        val odd = DialogRequest("d", DialogKind.Select, "[Database] Which database should we use?", options = listOf("Postgres", "SQLite"))
        assertEquals(AskReply.Value("2"), AskQuestions.reply(AskSubmission("c", questions, listOf(AskAnswer(picked = setOf(1)), AskAnswer())), odd))
        assertEquals(AskReply.Cancel, AskQuestions.reply(AskSubmission("c", questions, listOf(AskAnswer(), AskAnswer())), odd))
        assertTrue(!AskQuestions.complete(questions, listOf(AskAnswer(), AskAnswer())))
        assertTrue(AskQuestions.complete(questions, listOf(AskAnswer(picked = setOf(0)), AskAnswer())))
        assertTrue(AskQuestions.complete(questions, listOf(AskAnswer(), AskAnswer()), from = 1))
    }

    @Test
    fun aResponderAnswersEachDialogOnceAndOnlyForItsCall() {
        val responder = AskResponder()
        val call = ToolItem("tool:c1", "c1", AskQuestions.TOOL, args, status = ToolStatus.Running)
        val select = SessionReducer.dialogFrom(EngineFixture.events("ask-back").first { (it["type"] as JsonPrimitive).content == "extension_ui_request" })!!
        val other = DialogRequest("o", DialogKind.Select, "Pick a branch", options = listOf("main", "dev"))
        var state = SessionState(hello = true, items = listOf(UserItem("u1", "go"), call), dialogs = listOf(other, select))
        assertNull(responder.next(state))
        responder.submit(AskSubmission("c1", questions, listOf(AskAnswer(picked = setOf(0)), AskAnswer())))
        assertTrue(responder.submitting("c1"))
        assertEquals(select.id to AskReply.Value("1. Postgres — Relational, mature"), responder.next(state))
        assertNull(responder.next(state), "not answered twice; the other extension's dialog is left alone")
        state = state.copy(items = listOf(UserItem("u1", "go"), call.copy(status = ToolStatus.Done)))
        assertNull(responder.next(state))
        assertNull(responder.submission)
    }
}
