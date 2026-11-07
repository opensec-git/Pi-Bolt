package opensec.pibolt.app

import android.media.AudioManager
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import opensec.pibolt.app.data.AppSettings
import opensec.pibolt.app.data.SettingsCodec
import opensec.pibolt.app.ui.session.ask.AskAnswer
import opensec.pibolt.app.ui.session.ask.AskOption
import opensec.pibolt.app.ui.session.ask.AskQuestion
import opensec.pibolt.app.ui.session.voice.BargeIn
import opensec.pibolt.app.ui.session.voice.FocusRules
import opensec.pibolt.app.ui.session.voice.SpeechText
import opensec.pibolt.app.ui.session.voice.SpokenAnswer
import opensec.pibolt.app.ui.session.voice.SpokenAnswers
import opensec.pibolt.app.ui.session.voice.SpokenSummary
import opensec.pibolt.app.ui.session.voice.TtsVoice
import opensec.pibolt.app.ui.session.voice.TtsVoices
import opensec.pibolt.app.ui.session.voice.VoiceAction
import opensec.pibolt.app.ui.session.voice.VoiceEvent
import opensec.pibolt.app.ui.session.voice.VoiceModeMachine
import opensec.pibolt.app.ui.session.voice.VoiceModeState
import opensec.pibolt.app.ui.session.voice.VoicePhase
import opensec.pibolt.app.ui.session.voice.VoiceStep
import opensec.pibolt.app.ui.settings.rateLabel
import opensec.pibolt.protocol.session.AssistantTextItem
import opensec.pibolt.protocol.session.ChatItem
import opensec.pibolt.protocol.session.NoticeItem
import opensec.pibolt.protocol.session.NoticeKind
import opensec.pibolt.protocol.session.ToolItem
import opensec.pibolt.protocol.session.ToolStatus
import opensec.pibolt.protocol.session.UserItem
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Voice mode (060 feature 9): the spoken summary, spoken answers to question cards, and the state machine. */
class VoiceModeTest {
    // ---- Spoken summary: markdown stripped, first paragraph, length

    @Test
    fun plainStripsCodeTablesLinksAndMarks() {
        val md = """
            ## Summary

            I fixed the **failing** `checkout` tests in [the shop app](https://example.com/shop). See https://ci.example.com/run/1 for the run.

            ```kotlin
            fun main() = println("hi")
            ```

            | File | Change |
            |------|--------|
            | Cart.kt | fixed |

            > A _quoted_ ~~old~~ line
            ![diagram](img.png)
            <details>hidden</details>
        """.trimIndent()
        val plain = SpokenSummary.plain(md)
        assertEquals(
            "Summary\n\nI fixed the failing checkout tests in the shop app. See for the run.\n\nA quoted old line\n\nhidden",
            plain,
        )
        listOf("```", "|", "http", "**", "`", "#", "println", "Cart.kt", "img.png", "<").forEach { assertFalse(it in plain, "kept $it") }
    }

    @Test
    fun plainKeepsSnakeCaseAndTurnsListsIntoSentences() {
        assertEquals("Run make_release now.", SpokenSummary.plain("Run make_release now."))
        assertEquals("Changes: Added a test. Fixed the parser.", SpokenSummary.plain("Changes:\n- Added a test\n- Fixed the parser.").replace("\n\n", " "))
        assertEquals("", SpokenSummary.plain("```\nonly code\n```"))
        assertEquals("unclosed", SpokenSummary.plain("unclosed\n\n```js\nconst x = 1"))
    }

    @Test
    fun excerptIsTheFirstParagraphWithinThreeSentences() {
        val reply = "All tests pass now. I fixed the date parser. The flaky test is gone. I also cleaned up imports.\n\nSecond paragraph that is not said."
        assertEquals("All tests pass now. I fixed the date parser. The flaky test is gone.", SpokenSummary.excerpt(reply))
        // A heading is skipped; a short or introducing first paragraph takes the next.
        assertEquals("Done. The build is green.", SpokenSummary.excerpt("# Result\n\nDone.\n\nThe build is green.\n\nMore."))
        assertEquals("Here is what changed: Added a cache. Removed a lock.", SpokenSummary.excerpt("Here is what changed:\n\n- Added a cache\n- Removed a lock"))
        // "e.g." and version numbers do not end a sentence.
        assertEquals("Use v1.2 e.g. for the build. It works.", SpokenSummary.excerpt("Use v1.2 e.g. for the build. It works."))
    }

    @Test
    fun excerptStaysShort() {
        val long = (1..40).joinToString(" ") { "word$it" } + ", and then a lot more words that go on and on without a stop " + "x ".repeat(100)
        val e = SpokenSummary.excerpt(long)
        assertTrue(e.length <= SpokenSummary.MAX_CHARS + 1, "length ${e.length}")
        assertTrue(e.endsWith("…"))
        val sentences = (1..10).joinToString(" ") { "Sentence number $it is here." }
        val s = SpokenSummary.excerpt(sentences)
        assertEquals(3, s.split(". ").size)
        assertTrue(s.length <= SpokenSummary.MAX_CHARS)
        assertEquals("", SpokenSummary.excerpt(""))
    }

    private fun tool(name: String, path: String, status: ToolStatus = ToolStatus.Done) =
        ToolItem("t-$name-$path-$status", "c-$path", name, buildJsonObject { put("path", path) }, status = status)

    @Test
    fun forTurnSaysTheFinalReplyAndTheFilesChanged() {
        val items: List<ChatItem> = listOf(
            UserItem("u0", "old"),
            AssistantTextItem("a0", "Old reply.", false),
            UserItem("u1", "fix the tests"),
            AssistantTextItem("a1", "Let me look at the tests first.", false),
            tool("read", "src/Cart.kt"),
            tool("edit", "src/Cart.kt"),
            tool("edit", "src/Cart.kt"),
            tool("write", "src/CartTest.kt"),
            tool("edit", "src/Broken.kt", ToolStatus.Error),
            AssistantTextItem("a2", "The checkout tests pass now.\n\n```diff\n- a\n+ b\n```", false),
        )
        assertEquals("The checkout tests pass now. I changed 2 files.", SpokenSummary.forTurn(items))
        assertEquals(2, SpokenSummary.changedFiles(items.filterIsInstance<ToolItem>()))
        assertEquals("I changed one file.", SpokenSummary.changedLine(1))
        assertNull(SpokenSummary.changedLine(0))
    }

    @Test
    fun forTurnHandlesCodeOnlyAbortedAndFailedTurns() {
        assertEquals(SpokenSummary.ON_SCREEN, SpokenSummary.forTurn(listOf(UserItem("u", "x"), AssistantTextItem("a", "```\ncode\n```", false))))
        assertEquals(
            "Stopped. I changed one file.",
            SpokenSummary.forTurn(listOf(UserItem("u", "x"), tool("write", "a.txt"), NoticeItem("n", NoticeKind.Aborted, "Aborted"))),
        )
        assertEquals(
            "That didn't work: extension error.",
            SpokenSummary.forTurn(listOf(UserItem("u", "x"), NoticeItem("n", NoticeKind.Error, "Extension error", "boom"))),
        )
        assertEquals("Done.", SpokenSummary.forTurn(listOf(UserItem("u", "x"), tool("read", "a.txt"))))
        assertEquals("", SpokenSummary.forTurn(listOf(UserItem("u", "x"))))
        // The last text when no text follows the last tool.
        assertEquals("Checking.", SpokenSummary.forTurn(listOf(UserItem("u", "x"), AssistantTextItem("a", "Checking", false), tool("read", "a.txt"))))
    }

    // ---- Spoken answers to question cards

    private val db = listOf("Postgres", "SQLite (recommended)", "SQL Server")

    private fun pick(said: String, options: List<String> = db, multi: Boolean = false) = SpokenAnswers.match(said, options, multi)

    @Test
    fun anOptionByNumber() {
        for (said in listOf("option two", "Option 2.", "2", "two", "number two", "the second one", "second", "I'll take the second one please", "option to")) {
            assertEquals(SpokenAnswer.Picked(setOf(1)), pick(said), said)
        }
        assertEquals(SpokenAnswer.Picked(setOf(0)), pick("first"))
        assertEquals(SpokenAnswer.Picked(setOf(0)), pick("one"))
        assertEquals(SpokenAnswer.Picked(setOf(2)), pick("the last one"))
        assertEquals(SpokenAnswer.Unclear, pick("option four"), "no such option")
        assertEquals(SpokenAnswer.Unclear, pick("to"), "'to' is a number only after 'option'")
        assertEquals(SpokenAnswer.Unclear, pick("use two threads"), "a number inside another sentence")
    }

    @Test
    fun anOptionByLabel() {
        assertEquals(SpokenAnswer.Picked(setOf(0)), pick("Postgres"))
        assertEquals(SpokenAnswer.Picked(setOf(0)), pick("let's go with postgres"))
        assertEquals(SpokenAnswer.Picked(setOf(1)), pick("sqlite"), "the parenthesis is not needed")
        assertEquals(SpokenAnswer.Picked(setOf(2)), pick("SQL server please"))
        assertEquals(SpokenAnswer.Unclear, pick("Postgres or SQLite"), "one answer only")
        assertEquals(SpokenAnswer.Unclear, pick("Mongo"))
        // An option literally called "Skip" is that option, not a cancel.
        assertEquals(SpokenAnswer.Picked(setOf(1)), pick("skip", listOf("Run", "Skip")))
    }

    @Test
    fun severalForMultiSelectAndTheOtherReplies() {
        assertEquals(SpokenAnswer.Picked(setOf(0, 2)), pick("one and three", multi = true))
        assertEquals(SpokenAnswer.Picked(setOf(0, 1)), pick("Postgres and SQLite", multi = true))
        assertEquals(SpokenAnswer.Picked(setOf(0, 1, 2)), pick("all of them", multi = true))
        assertEquals(SpokenAnswer.Picked(emptySet()), pick("none", multi = true))
        assertEquals(SpokenAnswer.Cancel, pick("never mind"))
        assertEquals(SpokenAnswer.Cancel, pick("Cancel."))
        assertEquals(SpokenAnswer.Typed("use MySQL instead"), pick("Other: use MySQL instead"))
        assertEquals(SpokenAnswer.Typed("Redis"), pick("my answer is Redis"))
        assertEquals(SpokenAnswer.Unclear, pick(""))
    }

    @Test
    fun aQuestionIsReadWithItsOptions() {
        val q = AskQuestion("Which **database**?", "DB", listOf(AskOption("Postgres", "d"), AskOption("SQLite", "d")), multiSelect = true)
        assertEquals(
            "Question 1 of 2. Which database? Option 1, Postgres. Option 2, SQLite. You can pick more than one.",
            SpokenAnswers.prompt(q, 0, 2),
        )
        assertEquals("Which database? Option 1, Postgres. Option 2, SQLite.", SpokenAnswers.prompt(q.copy(multiSelect = false), 0, 1))
        assertTrue("1 to 2" in SpokenAnswers.retry(q))
    }

    // ---- The state machine

    private fun run(start: VoiceModeState, vararg events: VoiceEvent): VoiceStep {
        var step = VoiceStep(start)
        for (e in events) step = VoiceModeMachine.next(step.state, e)
        return step
    }

    private fun started(running: Boolean = false, muted: Boolean = false, bargeIn: Boolean = false) =
        VoiceModeMachine.next(VoiceModeState(), VoiceEvent.Start(running, bargeIn, muted))

    @Test
    fun theLoopListensSendsWorksSpeaksAndListensAgain() {
        val start = started()
        assertEquals(VoicePhase.Listening, start.state.phase)
        assertEquals(listOf<VoiceAction>(VoiceAction.Listen), start.actions)

        val heard = VoiceModeMachine.next(VoiceModeMachine.next(start.state, VoiceEvent.Partial("fix the")).state, VoiceEvent.Heard("fix the tests"))
        assertEquals(VoicePhase.Sending, heard.state.phase)
        assertEquals(listOf<VoiceAction>(VoiceAction.Send("fix the tests")), heard.actions)
        assertEquals("fix the tests", heard.state.heard)

        val working = VoiceModeMachine.next(heard.state, VoiceEvent.RunStarted)
        assertEquals(VoicePhase.Working, working.state.phase)

        val ended = VoiceModeMachine.next(working.state, VoiceEvent.RunEnded("All green."))
        assertEquals(VoicePhase.Speaking, ended.state.phase)
        assertTrue(VoiceAction.Speak("All green.") in ended.actions)
        assertEquals("All green.", ended.state.spoken)

        val again = VoiceModeMachine.next(ended.state, VoiceEvent.SpeechDone)
        assertEquals(VoicePhase.Listening, again.state.phase)
        assertEquals(listOf<VoiceAction>(VoiceAction.Listen), again.actions)
    }

    @Test
    fun interruptsAndStops() {
        val speaking = run(started().state, VoiceEvent.Heard("hi"), VoiceEvent.RunStarted, VoiceEvent.RunEnded("A long answer.")).state
        // The mic interrupts the speech and listens.
        val tapped = VoiceModeMachine.next(speaking, VoiceEvent.TapMic)
        assertEquals(VoicePhase.Listening, tapped.state.phase)
        assertEquals(listOf(VoiceAction.StopSpeaking, VoiceAction.Listen), tapped.actions)
        // A tap while listening finishes the words; nothing heard then waits for a tap.
        val stopping = VoiceModeMachine.next(tapped.state, VoiceEvent.TapMic)
        assertEquals(listOf<VoiceAction>(VoiceAction.StopListening), stopping.actions)
        assertTrue(stopping.state.stopping)
        assertEquals(VoicePhase.Idle, VoiceModeMachine.next(stopping.state, VoiceEvent.NoSpeech).state.phase)
        // Mute while speaking stops the speech and listens.
        val muted = VoiceModeMachine.next(speaking, VoiceEvent.ToggleMute)
        assertTrue(muted.state.muted)
        assertEquals(VoicePhase.Listening, muted.state.phase)
        assertTrue(VoiceAction.StopSpeaking in muted.actions)
        // End stops everything; later events do nothing.
        val end = VoiceModeMachine.next(speaking, VoiceEvent.End)
        assertEquals(VoicePhase.Off, end.state.phase)
        assertEquals(listOf(VoiceAction.CancelListening, VoiceAction.StopSpeaking, VoiceAction.Release), end.actions)
        assertEquals(end.state, VoiceModeMachine.next(end.state, VoiceEvent.RunEnded("x")).state)
        assertTrue(VoiceModeMachine.next(end.state, VoiceEvent.TapMic).actions.isEmpty())
    }

    @Test
    fun silenceWaitsForATapAndMutedRepliesAreOnlyShown() {
        val s = started().state
        val once = VoiceModeMachine.next(s, VoiceEvent.NoSpeech)
        assertEquals(listOf<VoiceAction>(VoiceAction.Listen), once.actions)
        val twice = VoiceModeMachine.next(once.state, VoiceEvent.NoSpeech)
        assertEquals(VoicePhase.Idle, twice.state.phase)
        assertTrue(twice.actions.isEmpty())
        assertEquals(VoicePhase.Listening, VoiceModeMachine.next(twice.state, VoiceEvent.TapMic).state.phase)

        val muted = run(started(muted = true).state, VoiceEvent.Heard("hi"), VoiceEvent.RunStarted, VoiceEvent.RunEnded("Hello."))
        assertEquals(VoicePhase.Listening, muted.state.phase)
        assertEquals("Hello.", muted.state.spoken)
        assertTrue(muted.actions.none { it is VoiceAction.Speak })
    }

    @Test
    fun aRecognizerErrorAndAFailedSendWaitForATap() {
        val failed = VoiceModeMachine.next(started().state, VoiceEvent.Failed("No connection for speech recognition"))
        assertEquals(VoicePhase.Idle, failed.state.phase)
        assertEquals("No connection for speech recognition", failed.state.notice)
        val notSent = run(started().state, VoiceEvent.Heard("hi"), VoiceEvent.SendFailed)
        assertEquals(VoicePhase.Idle, notSent.state.phase)
        assertEquals(VoiceModeMachine.NOT_SENT, notSent.state.notice)
    }

    @Test
    fun talkingWhilePiWorksSteersAndTheReplyWaits() {
        val working = started(running = true)
        assertEquals(VoicePhase.Working, working.state.phase)
        assertTrue(working.actions.isEmpty())
        val steer = run(working.state, VoiceEvent.TapMic, VoiceEvent.Heard("use the other branch"))
        assertEquals(VoicePhase.Working, steer.state.phase)
        assertEquals(listOf<VoiceAction>(VoiceAction.Send("use the other branch")), steer.actions)
        // The turn ends while the user talks: the reply is said once they are done (nothing heard).
        val talking = run(working.state, VoiceEvent.TapMic, VoiceEvent.RunEnded("Done."))
        assertEquals(VoicePhase.Listening, talking.state.phase)
        assertEquals("Done.", talking.state.pendingReply)
        val said = VoiceModeMachine.next(talking.state, VoiceEvent.NoSpeech)
        assertEquals(VoicePhase.Speaking, said.state.phase)
        assertTrue(VoiceAction.Speak("Done.") in said.actions)
        // "Stop" only stops the speech: it is never sent.
        val hush = run(started().state, VoiceEvent.Heard("Stop."))
        assertTrue(hush.actions.none { it is VoiceAction.Send })
        assertEquals(VoicePhase.Listening, hush.state.phase)
        assertTrue(VoiceModeMachine.isHush("Listening"))
    }

    @Test
    fun bargeInStopsTheSpeechButNotItsOwnEcho() {
        val speaking = run(started(bargeIn = true).state, VoiceEvent.Heard("hi"), VoiceEvent.RunStarted, VoiceEvent.RunEnded("The build is green now."))
        // With barge-in it listens while it speaks.
        assertEquals(listOf(VoiceAction.Speak("The build is green now."), VoiceAction.Listen), speaking.actions)
        val echo = VoiceModeMachine.next(speaking.state, VoiceEvent.Partial("the build is green"))
        assertEquals(VoicePhase.Speaking, echo.state.phase)
        assertTrue(echo.actions.isEmpty())
        val echoFinal = VoiceModeMachine.next(speaking.state, VoiceEvent.Heard("build is green now"))
        assertEquals(listOf<VoiceAction>(VoiceAction.Listen), echoFinal.actions, "its own words are not sent")
        val user = VoiceModeMachine.next(speaking.state, VoiceEvent.Partial("wait run the tests"))
        assertEquals(VoicePhase.Listening, user.state.phase)
        assertEquals(listOf<VoiceAction>(VoiceAction.StopSpeaking), user.actions)
        val userFinal = VoiceModeMachine.next(speaking.state, VoiceEvent.Heard("now deploy it please"))
        assertEquals(listOf(VoiceAction.StopSpeaking, VoiceAction.Send("now deploy it please")), userFinal.actions)
        // Without barge-in the phone does not listen while it speaks.
        val plain = run(started().state, VoiceEvent.Heard("hi"), VoiceEvent.RunStarted, VoiceEvent.RunEnded("Ok."))
        assertEquals(listOf(VoiceAction.CancelListening, VoiceAction.Speak("Ok.")), plain.actions)
        assertTrue(BargeIn.isUser("stop", "The build is green."))
        assertFalse(BargeIn.isUser("green", "The build is green."))
        assertFalse(BargeIn.isUser("", "x"))
    }

    @Test
    fun callsAndOtherAudioPauseAndResume() {
        val speaking = run(started().state, VoiceEvent.Heard("hi"), VoiceEvent.RunStarted, VoiceEvent.RunEnded("All done.")).state
        val paused = VoiceModeMachine.next(speaking, VoiceEvent.Pause(FocusRules.CALL))
        assertTrue(paused.state.paused)
        assertEquals(VoicePhase.Idle, paused.state.phase)
        assertEquals("All done.", paused.state.pendingReply)
        assertTrue(VoiceAction.StopSpeaking in paused.actions)
        // Nothing is said or heard while paused.
        assertTrue(VoiceModeMachine.next(paused.state, VoiceEvent.RunEnded("Next.")).actions.isEmpty())
        val resumed = VoiceModeMachine.next(paused.state, VoiceEvent.Resume)
        assertFalse(resumed.state.paused)
        assertEquals(listOf(VoiceAction.CancelListening, VoiceAction.Speak("All done.")), resumed.actions)
        // A tap resumes too, and listens.
        val tap = VoiceModeMachine.next(paused.state.copy(pendingReply = null), VoiceEvent.TapMic)
        assertFalse(tap.state.paused)
        assertEquals(VoicePhase.Listening, tap.state.phase)

        assertEquals(FocusRules.Effect.Pause, FocusRules.effect(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, 10_000))
        assertEquals(FocusRules.Effect.Ignore, FocusRules.effect(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, 300), "the recogniser's own focus")
        assertEquals(FocusRules.Effect.Ignore, FocusRules.effect(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK, 10_000))
        assertEquals(FocusRules.Effect.Resume, FocusRules.effect(AudioManager.AUDIOFOCUS_GAIN, 10_000))
        assertTrue(FocusRules.inCall(AudioManager.MODE_IN_CALL))
        assertFalse(FocusRules.inCall(AudioManager.MODE_NORMAL))
    }

    private val questions = listOf(
        AskQuestion("Which database?", "DB", listOf(AskOption("Postgres", ""), AskOption("SQLite", "")), multiSelect = false),
        AskQuestion("Which extras?", "Extras", listOf(AskOption("Auth", ""), AskOption("Cache", ""), AskOption("Queue", "")), multiSelect = true),
    )

    @Test
    fun questionsAreReadAndAnsweredByVoice() {
        val working = started(running = true).state
        val asked = VoiceModeMachine.next(working, VoiceEvent.Ask("call-1", questions))
        assertEquals(VoicePhase.Asking, asked.state.phase)
        assertEquals(VoiceAction.Speak(SpokenAnswers.prompt(questions[0], 0, 2)), asked.actions.last())
        // The same call is asked once.
        assertEquals(asked.state, VoiceModeMachine.next(asked.state, VoiceEvent.Ask("call-1", questions)).state)

        val listening = VoiceModeMachine.next(asked.state, VoiceEvent.SpeechDone)
        assertEquals(VoicePhase.Listening, listening.state.phase)
        val first = VoiceModeMachine.next(listening.state, VoiceEvent.Heard("option two"))
        assertEquals(1, first.state.question?.index, "on to the second question")
        assertEquals(VoiceAction.Speak(SpokenAnswers.prompt(questions[1], 1, 2)), first.actions.last())

        val done = run(first.state, VoiceEvent.SpeechDone, VoiceEvent.Heard("auth and queue"))
        assertEquals(VoicePhase.Working, done.state.phase)
        assertNull(done.state.question)
        assertTrue("call-1" in done.state.handled)
        val submit = done.actions.single() as VoiceAction.Submit
        assertEquals("call-1", submit.submission.toolCallId)
        assertEquals(listOf(AskAnswer(picked = setOf(1)), AskAnswer(picked = setOf(0, 2))), submit.submission.answers)
        assertFalse(submit.submission.cancel)
    }

    @Test
    fun unclearAnswersGoToTheCardAndCancelDeclines() {
        val listening = run(started(running = true).state, VoiceEvent.Ask("call-2", questions), VoiceEvent.SpeechDone).state
        val miss = VoiceModeMachine.next(listening, VoiceEvent.Heard("purple elephants"))
        assertEquals(VoicePhase.Asking, miss.state.phase)
        assertEquals(VoiceAction.Speak(SpokenAnswers.retry(questions[0])), miss.actions.last())
        val card = run(miss.state, VoiceEvent.SpeechDone, VoiceEvent.NoSpeech)
        assertNull(card.state.question)
        assertTrue("call-2" in card.state.handled, "the card shows again")
        assertEquals(VoiceAction.Speak(SpokenAnswers.ON_CARD), card.actions.last())
        assertEquals(VoicePhase.Working, VoiceModeMachine.next(card.state, VoiceEvent.SpeechDone).state.phase)

        val cancel = VoiceModeMachine.next(listening, VoiceEvent.Heard("never mind"))
        assertTrue((cancel.actions.single() as VoiceAction.Submit).submission.cancel)

        val shown = VoiceModeMachine.next(listening, VoiceEvent.ShowCard)
        assertNull(shown.state.question)
        assertEquals(VoicePhase.Working, shown.state.phase)
        assertTrue(VoiceAction.CancelListening in shown.actions)

        val gone = VoiceModeMachine.next(listening, VoiceEvent.DialogGone)
        assertNull(gone.state.question)

        // Muted, the question is shown and listened for, not said.
        val muted = VoiceModeMachine.next(started(running = true, muted = true).state, VoiceEvent.Ask("call-3", questions))
        assertEquals(VoicePhase.Listening, muted.state.phase)
        assertTrue(muted.actions.none { it is VoiceAction.Speak })
    }

    @Test
    fun anotherDialogIsAnnouncedAndAnsweredOnScreen() {
        val said = VoiceModeMachine.next(started(running = true).state, VoiceEvent.OtherDialog("Allow rm -rf build?"))
        assertEquals(VoicePhase.Speaking, said.state.phase)
        assertEquals("Pi needs your answer on screen: Allow rm -rf build?", said.state.spoken)
        assertEquals(VoicePhase.Working, VoiceModeMachine.next(said.state, VoiceEvent.SpeechDone).state.phase)
    }

    // ---- Small pieces

    @Test
    fun voicesSpeechTextAndSettings() {
        val voices = listOf(
            TtsVoice("en-us-x-net", "en", "en-US", "English (United States)", 400, network = true, installed = true),
            TtsVoice("en-us-x-local", "en", "en-US", "English (United States)", 300, network = false, installed = true),
            TtsVoice("en-gb-x-local", "en", "en-GB", "English (United Kingdom)", 400, network = false, installed = true),
            TtsVoice("de-de-x-local", "de", "de-DE", "German (Germany)", 400, network = false, installed = true),
            TtsVoice("en-in-x-missing", "en", "en-IN", "English (India)", 400, network = false, installed = false),
        )
        val picked = TtsVoices.pick(voices, Locale.US)
        assertEquals(listOf("en-gb-x-local", "en-us-x-local", "en-us-x-net"), picked.map { it.name })
        assertEquals(
            mapOf("en-gb-x-local" to "English (United Kingdom)", "en-us-x-local" to "English (United States) 1", "en-us-x-net" to "English (United States) 2"),
            TtsVoices.labels(picked),
        )
        assertEquals(listOf("de-de-x-local"), TtsVoices.pick(voices, Locale.GERMANY).map { it.name })

        assertEquals("Wait. Then go.", SpeechText.forTts("Wait… Then go..."))

        assertEquals(listOf("0.75x", "Normal", "1.25x", "1.5x", "2x"), AppSettings.VOICE_RATE_CHOICES.map(::rateLabel))
        val s = AppSettings(voiceSpeakReplies = false, voiceRate = 150, voiceName = "en-us-x-local", voiceLabel = "English (United States) 1")
        assertEquals(s, SettingsCodec.decode(SettingsCodec.encode(s)))
        assertEquals(100, SettingsCodec.decode("""{"voiceRate":130}""").voiceRate, "an unknown rate falls back")
        assertTrue(SettingsCodec.decode("{}").voiceSpeakReplies)
        assertNull(SettingsCodec.decode("""{"voiceLabel":"x"}""").voiceLabel, "no label without a voice")
    }
}
