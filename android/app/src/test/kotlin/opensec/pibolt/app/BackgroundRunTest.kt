package opensec.pibolt.app

import opensec.pibolt.app.background.OemSetup
import opensec.pibolt.app.background.OemSetup.Vendor
import android.app.PendingIntent
import opensec.pibolt.app.background.SessionActions
import opensec.pibolt.app.background.SessionActions.Request
import opensec.pibolt.app.background.SessionActions.Route
import opensec.pibolt.app.background.WakePolicy
import opensec.pibolt.app.background.WakePolicy.Decision
import opensec.pibolt.app.background.WakePolicy.Work
import opensec.pibolt.app.session.RunningSummary
import opensec.pibolt.app.session.KeepAlive
import opensec.pibolt.app.engine.BackgroundJobs
import opensec.pibolt.protocol.session.SessionState
import opensec.pibolt.protocol.session.DialogKind
import opensec.pibolt.protocol.session.DialogRequest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The agent running with the app away: the wake lock policy, the OEM screens, and the notification actions. */
class BackgroundRunTest {
    // Wake lock policy

    @Test
    fun onlyLocalWorkHoldsTheCpu() {
        assertTrue(WakePolicy.working(listOf(Work(local = true, running = true, waiting = false))), "a streaming turn")
        assertFalse(WakePolicy.working(listOf(Work(local = true, running = true, waiting = true))), "waiting for the user")
        assertFalse(WakePolicy.working(listOf(Work(local = false, running = true, waiting = false))), "remote: the computer works")
        assertFalse(WakePolicy.working(listOf(Work(local = true, running = false, waiting = false))), "idle")
        assertTrue(WakePolicy.working(listOf(Work(local = true, running = true, waiting = true, subagents = true))), "background agents run while it waits")
        assertTrue(WakePolicy.working(listOf(Work(local = true, running = false, waiting = false, compacting = true))))
        assertTrue(WakePolicy.working(listOf(Work(local = true, running = false, waiting = false, shell = true))), "a ! command")
        assertTrue(WakePolicy.working(listOf(Work(local = false, running = true, waiting = false), Work(local = true, running = true, waiting = false))))
        assertFalse(WakePolicy.working(emptyList()))
    }

    @Test
    fun theLockIsTakenAtOnceAndRenewedOnlyAfterProgress() {
        val t0 = 1_000L
        assertEquals(Decision.Acquire, WakePolicy.decide(true, t0, acquiredAt = null, held = false, progressAt = 0, idleSince = null))
        // Progress but not yet time to renew.
        assertEquals(Decision.Keep, WakePolicy.decide(true, t0 + 60_000, acquiredAt = t0, held = true, progressAt = t0 + 50_000, idleSince = null))
        // Time to renew, with progress since: renewed.
        val later = t0 + WakePolicy.REFRESH_MS
        assertEquals(Decision.Acquire, WakePolicy.decide(true, later, acquiredAt = t0, held = true, progressAt = later - 1, idleSince = null))
        // Time to renew, nothing happened since: kept as is, so its timeout runs out.
        assertEquals(Decision.Keep, WakePolicy.decide(true, later, acquiredAt = t0, held = true, progressAt = t0 - 1, idleSince = null))
    }

    @Test
    fun aStuckSessionLosesTheLockWithinTheCap() {
        // Simulate: progress until 30 minutes, then nothing. Ticks every REFRESH_MS as SessionService schedules them.
        var acquiredAt: Long? = null
        var expiresAt = 0L
        val lastProgress = 30 * 60_000L
        var t = 0L
        var lastHeld = 0L
        while (t <= 12 * 60 * 60_000L) {
            val held = acquiredAt != null && t < expiresAt
            if (held) lastHeld = t
            val progressAt = minOf(t, lastProgress)
            if (WakePolicy.decide(true, t, acquiredAt, held, progressAt, null) == Decision.Acquire) {
                acquiredAt = t
                expiresAt = t + WakePolicy.CAP_MS
            }
            t += 60_000L
        }
        assertTrue(lastHeld <= lastProgress + WakePolicy.CAP_MS + WakePolicy.REFRESH_MS, "held until $lastHeld")
        assertTrue(lastHeld >= lastProgress + WakePolicy.CAP_MS - WakePolicy.REFRESH_MS, "a live run keeps it ($lastHeld)")
    }

    @Test
    fun anExpiredLockComesBackWithNewProgress() {
        val t0 = 0L
        val t = WakePolicy.CAP_MS + 1
        assertEquals(Decision.Keep, WakePolicy.decide(true, t, acquiredAt = t0, held = false, progressAt = t0, idleSince = null), "still stuck")
        assertEquals(Decision.Acquire, WakePolicy.decide(true, t, acquiredAt = t0, held = false, progressAt = t, idleSince = null), "it moved again")
    }

    @Test
    fun theLockGoesShortlyAfterTheWorkEnds() {
        val end = 10_000L
        assertEquals(Decision.ReleaseIn(WakePolicy.LINGER_MS), WakePolicy.decide(false, end, acquiredAt = 0, held = true, progressAt = end, idleSince = end))
        assertEquals(Decision.ReleaseIn(2_000), WakePolicy.decide(false, end + WakePolicy.LINGER_MS - 2_000, acquiredAt = 0, held = true, progressAt = end, idleSince = end))
        assertEquals(Decision.Release, WakePolicy.decide(false, end + WakePolicy.LINGER_MS, acquiredAt = 0, held = true, progressAt = end, idleSince = end))
        assertEquals(Decision.Keep, WakePolicy.decide(false, end, acquiredAt = null, held = false, progressAt = 0, idleSince = end), "never taken: nothing to do")
    }

    @Test
    fun backgroundWorkKeepsTheServiceUp() {
        fun entry(running: Boolean = false, subagents: Boolean = false, shell: Boolean = false, compacting: Boolean = false) =
            RunningSummary.Entry("api", local = true, running = running, waiting = false, compacting = compacting, connected = true, subagents = subagents, shell = shell)
        assertNull(RunningSummary.of(listOf(entry()), "Pi-Bolt"))
        assertEquals("Background agents working", RunningSummary.of(listOf(entry(subagents = true)), "Pi-Bolt")!!.text)
        assertEquals("Running a command", RunningSummary.of(listOf(entry(shell = true)), "Pi-Bolt")!!.text)
        assertEquals("Compacting context", RunningSummary.of(listOf(entry(compacting = true)), "Pi-Bolt")!!.text)
        assertEquals("Working", RunningSummary.of(listOf(entry(running = true, subagents = true)), "Pi-Bolt")!!.text)
    }

    @Test
    fun staleAnswerNotificationsAreRecognised() {
        val answer = RunningSummary.notificationId("local:/p/a", RunningSummary.ANSWER)
        val settled = RunningSummary.notificationId("local:/p/a", RunningSummary.SETTLED)
        assertTrue(RunningSummary.isKind(answer, RunningSummary.ANSWER))
        assertFalse(RunningSummary.isKind(settled, RunningSummary.ANSWER))
        assertTrue(RunningSummary.isKind(settled, RunningSummary.SETTLED))
        // The app's fixed ids (session 1, agent 4, updates 10 and 11, terminals 41) are never taken for one.
        for (fixed in listOf(1, 4, 10, 11, 41)) assertFalse(RunningSummary.isKind(fixed, RunningSummary.ANSWER), "id $fixed")
    }

    // OEM screens

    @Test
    fun vendorsFromManufacturerOrBrand() {
        assertEquals(Vendor.Xiaomi, OemSetup.vendor("Xiaomi", "Redmi"))
        assertEquals(Vendor.Xiaomi, OemSetup.vendor("Xiaomi", "POCO"))
        assertEquals(Vendor.Huawei, OemSetup.vendor("HUAWEI", "HUAWEI"))
        assertEquals(Vendor.Honor, OemSetup.vendor("HONOR", "HONOR"))
        assertEquals(Vendor.Oppo, OemSetup.vendor("OPPO", "OPPO"))
        assertEquals(Vendor.Oppo, OemSetup.vendor("realme", "realme"))
        assertEquals(Vendor.OnePlus, OemSetup.vendor("OnePlus", "OnePlus"))
        assertEquals(Vendor.Vivo, OemSetup.vendor("vivo", "iQOO"))
        assertEquals(Vendor.Samsung, OemSetup.vendor("samsung", "samsung"))
        assertNull(OemSetup.vendor("motorola", "motorola"))
        assertNull(OemSetup.vendor("Google", "google"))
        assertTrue(OemSetup.steps(null, "in.opensec.pibolt", "Pi-Bolt").isEmpty(), "stock Android: battery optimization only")
    }

    @Test
    fun xiaomiAsksForAutostartAndNoRestrictions() {
        val steps = OemSetup.steps(Vendor.Xiaomi, "in.opensec.pibolt", "Pi-Bolt")
        assertEquals(listOf("autostart", "battery-saver"), steps.map { it.id })
        assertEquals("com.miui.permcenter.autostart.AutoStartManagementActivity", steps[0].screens.single().cls)
        val saver = steps[1].screens.single()
        assertEquals("com.miui.powerkeeper", saver.pkg)
        assertEquals(mapOf("package_name" to "in.opensec.pibolt", "package_label" to "Pi-Bolt"), saver.extras)
    }

    @Test
    fun miuiBatteryRequestGoesToItsOwnScreenNotAChooser() {
        val steps = OemSetup.steps(Vendor.Xiaomi, "in.opensec.pibolt", "Pi-Bolt")
        // What MIUI 13 (Redmi Note 10 Pro, Android 12) answers ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS with.
        val miui = listOf(
            "com.miui.powerkeeper" to "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
            "com.miui.securitycenter" to "com.miui.powercenter.legacypowerrank.PowerDetailActivity",
        )
        assertEquals("battery-saver", OemSetup.batteryStep(steps, miui)?.id)
        assertNull(OemSetup.batteryStep(steps, miui.take(1)), "one handler: Android shows no chooser")
        assertNull(OemSetup.batteryStep(steps, listOf("a" to "A", "b" to "B")), "none of the vendor's screens")
        assertNull(OemSetup.batteryStep(emptyList(), miui), "stock Android")
    }

    @Test
    fun theFirstScreenThatExistsElseAppDetails() {
        val step = OemSetup.steps(Vendor.Vivo, "p", "Pi-Bolt").single()
        val second = step.screens[1]
        assertEquals(OemSetup.Target.Open(step.screens[0]), OemSetup.resolve(step) { true })
        assertEquals(OemSetup.Target.Open(second), OemSetup.resolve(step) { it == second })
        assertEquals(OemSetup.Target.AppDetails, OemSetup.resolve(step) { false })
    }

    @Test
    fun onePlusFallsBackToColorOsAndHonorToHuawei() {
        val onePlus = OemSetup.steps(Vendor.OnePlus, "p", "a").single().screens.map { it.pkg }
        assertEquals("com.oneplus.security", onePlus.first())
        assertTrue("com.coloros.safecenter" in onePlus)
        val honor = OemSetup.steps(Vendor.Honor, "p", "a").single().screens.map { it.pkg }
        assertEquals(listOf("com.hihonor.systemmanager", "com.huawei.systemmanager"), honor.distinct())
        assertFalse("com.hihonor.systemmanager" in OemSetup.steps(Vendor.Huawei, "p", "a").single().screens.map { it.pkg })
    }

    @Test
    fun everyOemPackageIsVisibleToTheApp() {
        // Android 11+ resolves another app's activity only for packages in <queries>; a missing one always falls back.
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val missing = OemSetup.packages().filter { "<package android:name=\"$it\" />" !in manifest }
        assertTrue(missing.isEmpty(), "add to <queries>: $missing")
    }

    // Notification actions

    private val confirm = DialogRequest("d1", DialogKind.Confirm, "Run rm -rf build?")
    private val pick = DialogRequest("d2", DialogKind.Select, "Which model?", options = listOf("Opus", "Sonnet", "Haiku"))
    private val many = DialogRequest("d3", DialogKind.Select, "Which file?", options = listOf("a.kt", "b.kt", "c.kt", "d.kt", "e.kt", "f.kt"))
    private val input = DialogRequest("d4", DialogKind.Input, "Branch name", placeholder = "feature/...")

    private fun live(dialogs: List<DialogRequest> = emptyList(), running: Boolean = true, key: String = "local:/p/a", local: Boolean = true) =
        SessionActions.Live(key, local, running, dialogs)

    @Test
    fun actionsSurviveTheIntent() {
        val requests = listOf(
            Request.StopAll,
            Request.Answer("local:/p/a", "d2", "Sonnet"),
            Request.Answer("local:/p/a", "d4"),
            Request.Confirm("local:/p/a", "d1", yes = true),
            Request.Confirm("local:/p/a", "d1", yes = false),
            Request.Reply("remote-host"),
            Request.Reply("local:/p/a", sessionId = "s1"),
        )
        for (r in requests) {
            val (action, extras) = SessionActions.encode(r)
            assertEquals(r, SessionActions.decode(action) { extras[it] })
        }
        assertNull(SessionActions.decode("something.else") { "x" })
        assertNull(SessionActions.decode(SessionActions.ANSWER) { null }, "no session key")
        assertNull(SessionActions.decode(SessionActions.CONFIRM) { if (it == SessionActions.EXTRA_KEY) "k" else null }, "no dialog id")
    }

    @Test
    fun everyActionHasItsOwnRequestCode() {
        val ids = listOf(RunningSummary.notificationId("a", 0), RunningSummary.notificationId("a", 1), RunningSummary.notificationId("b", 1), 1)
        val codes = ids.flatMap { id -> (0 until SessionActions.SLOTS).map { SessionActions.requestCode(id, it) } }
        assertEquals(codes.size, codes.toSet().size)
        // The largest per-session id still fits.
        assertTrue(SessionActions.requestCode(100 + 999_999 * 2 + 1, SessionActions.SLOTS - 1) > 0)
    }

    @Test
    fun stopAbortsOnlyRunsOnThisPhone() {
        val route = SessionActions.route(
            Request.StopAll,
            null,
            listOf(live(key = "a"), live(key = "b", running = false), live(key = "remote", local = false)),
        )
        assertEquals(Route.Abort(listOf("a")), route)
    }

    @Test
    fun answersReachTheirDialog() {
        val k = "local:/p/a"
        assertEquals(Route.Confirm(k, "d1", true), SessionActions.route(Request.Confirm(k, "d1", true), null, listOf(live(listOf(confirm)))))
        assertEquals(Route.Value(k, "d2", "Sonnet"), SessionActions.route(Request.Answer(k, "d2", "Sonnet"), null, listOf(live(listOf(pick)))))
        assertEquals(Route.Value(k, "d4", "fix/login"), SessionActions.route(Request.Answer(k, "d4"), "  fix/login ", listOf(live(listOf(input)))))
        // A typed option: by name in any case, or by its number.
        assertEquals(Route.Value(k, "d3", "c.kt"), SessionActions.route(Request.Answer(k, "d3"), "C.KT", listOf(live(listOf(many)))))
        assertEquals(Route.Value(k, "d3", "e.kt"), SessionActions.route(Request.Answer(k, "d3"), "5", listOf(live(listOf(many)))))
        assertIs<Route.Again>(SessionActions.route(Request.Answer(k, "d3"), "z.kt", listOf(live(listOf(many)))))
        assertIs<Route.Again>(SessionActions.route(Request.Answer(k, "d3"), "9", listOf(live(listOf(many)))))
        assertIs<Route.Again>(SessionActions.route(Request.Answer(k, "d4"), "   ", listOf(live(listOf(input)))))
        // A button whose option is no longer offered is not sent.
        assertIs<Route.Again>(SessionActions.route(Request.Answer(k, "d2", "GPT"), null, listOf(live(listOf(pick)))))
    }

    @Test
    fun aTypedConfirmTakesYesOrNo() {
        val k = "local:/p/a"
        assertEquals(Route.Confirm(k, "d1", true), SessionActions.route(Request.Answer(k, "d1"), "Yes", listOf(live(listOf(confirm)))))
        assertEquals(Route.Confirm(k, "d1", false), SessionActions.route(Request.Answer(k, "d1"), "n", listOf(live(listOf(confirm)))))
        assertIs<Route.Again>(SessionActions.route(Request.Answer(k, "d1"), "maybe", listOf(live(listOf(confirm)))))
    }

    @Test
    fun aGoneSessionOrDialogIsNotAnswered() {
        val k = "local:/p/a"
        assertEquals(Route.Gone(k), SessionActions.route(Request.Confirm(k, "d1", true), null, emptyList()), "the process restarted")
        assertEquals(Route.Gone(k), SessionActions.route(Request.Confirm(k, "d1", true), null, listOf(live())), "answered in the app")
        assertEquals(Route.Gone(k), SessionActions.route(Request.Answer(k, "d4"), "x", listOf(live(listOf(pick)))))
        assertEquals(Route.Gone(k), SessionActions.route(Request.Reply(k), "hi", listOf(live(key = "other"))))
    }

    @Test
    fun aReplySteersARunOrStartsOne() {
        val k = "local:/p/a"
        assertEquals(Route.Prompt(k, "use the v2 API", steer = true), SessionActions.route(Request.Reply(k), " use the v2 API ", listOf(live(running = true))))
        assertEquals(Route.Prompt(k, "now the tests", steer = false), SessionActions.route(Request.Reply(k), "now the tests", listOf(live(running = false))))
        assertEquals(Route.Again(k, null, "Type a message"), SessionActions.route(Request.Reply(k), "", listOf(live())))
    }

    @Test
    fun aTappedSuggestionOfALongOptionIsThatOption() {
        // ask_user_question's options run long ("PostgreSQL: a relational database with..."): the inline reply offers them
        // cut to 40 characters, and tapping one sent the cut text, which matched no option ("Not an option").
        val k = "local:/p/a"
        val pg = "PostgreSQL: a relational database with strong consistency"
        val sqlite = "SQLite: a single file, no server to run on the phone"
        val db = DialogRequest("d5", DialogKind.Select, "Which database?", options = listOf(pg, sqlite))
        val typed = assertIs<SessionActions.Answering.Typed>(SessionActions.answering(db))
        assertTrue(typed.choices.all { it.length <= SessionActions.CHOICE_CHARS })
        for ((option, offered) in db.options.zip(typed.choices)) {
            assertEquals(Route.Value(k, "d5", option), SessionActions.route(Request.Answer(k, "d5"), offered, listOf(live(listOf(db)))))
        }
        // Two options with the same first 40 characters: a cut one is ambiguous, so it is asked again.
        val twins = DialogRequest("d6", DialogKind.Select, "Which?", options = listOf("x".repeat(40) + " one", "x".repeat(40) + " two"))
        assertIs<Route.Again>(SessionActions.route(Request.Answer(k, "d6"), "x".repeat(40), listOf(live(listOf(twins)))))
    }

    @Test
    fun aReplyReachesOnlyTheConversationItWasAbout() {
        // A "Pi finished" notification stays while the user opens another chat (or session) in the same live engine;
        // its Reply must not land in the conversation that is open now.
        val k = "local:/chats"
        val now = SessionActions.Live(k, local = true, running = false, dialogs = emptyList(), sessionId = "chat-b")
        assertEquals(Route.Gone(k), SessionActions.route(Request.Reply(k, sessionId = "chat-a"), "and in French?", listOf(now)))
        assertEquals(Route.Prompt(k, "and in French?", steer = false), SessionActions.route(Request.Reply(k, sessionId = "chat-b"), "and in French?", listOf(now)))
        // Not known on either side (a notification from an older build, a session not yet reported): as before.
        assertIs<Route.Prompt>(SessionActions.route(Request.Reply(k), "hi", listOf(now)))
        assertIs<Route.Prompt>(SessionActions.route(Request.Reply(k, sessionId = "chat-a"), "hi", listOf(now.copy(sessionId = null))))
    }

    @Test
    fun aReplyThatReachedNoSessionKeepsItsText() {
        assertEquals("Not sent: now add the tests", SessionActions.closedText("  now add the tests "))
        assertEquals("Open it to continue", SessionActions.closedText(null))
        assertEquals("Open it to continue", SessionActions.closedText("   "))
        assertEquals(200, SessionActions.closedText("x".repeat(500)).removePrefix("Not sent: ").length)
    }

    @Test
    fun aChatsNotificationNamesNoProject() {
        // The chats' directory is HOME/.pibolt/chats: a "needs an answer" re-posted from a notification named the project
        // "chats", so its tap opened nothing, or a project called "chats".
        val chats = File("/data/data/in.opensec.pibolt/files/home/.pibolt/chats")
        assertNull(opensec.pibolt.app.session.Notifications.projectOf(chats, chat = true))
        assertEquals("app", opensec.pibolt.app.session.Notifications.projectOf(File("/p/app"), chat = false))
        assertNull(opensec.pibolt.app.session.Notifications.projectOf(null, chat = false))
    }

    @Test
    fun eachDialogKindGetsItsControls() {
        assertEquals(SessionActions.Answering.YesNo, SessionActions.answering(confirm))
        assertEquals(SessionActions.Answering.Options(listOf("Opus", "Sonnet", "Haiku")), SessionActions.answering(pick))
        val typed = assertIs<SessionActions.Answering.Typed>(SessionActions.answering(many))
        assertEquals(5, typed.choices.size)
        assertEquals("feature/...", assertIs<SessionActions.Answering.Typed>(SessionActions.answering(input)).label)
        val long = pick.copy(options = listOf("Keep the current implementation", "Rewrite"))
        assertIs<SessionActions.Answering.Typed>(SessionActions.answering(long), "a long option does not fit a button")
    }

    // The foreground service that keeps the network with the app away (KeepAlive)

    @Test
    fun everyKindOfWorkStartsTheService() {
        val idle = SessionState(hello = true)
        assertFalse(KeepAlive.busy(idle))
        // Only turns and dialogs started it: a manual compaction lost its model request once the user left (Android 15+
        // cuts an app's network about 5 seconds after it leaves without a foreground service).
        assertTrue(KeepAlive.starts(idle, idle.copy(isCompacting = true, compactionReason = "manual")), "a manual /compact")
        assertTrue(KeepAlive.starts(idle, idle.copy(isRunning = true)), "a turn")
        assertTrue(KeepAlive.starts(idle, idle.copy(dialogs = listOf(confirm))), "a dialog")
        assertTrue(KeepAlive.starts(idle, idle.copy(statuses = mapOf(BackgroundJobs.STATUS_KEY to "1 job"))), "background jobs")
        assertTrue(KeepAlive.starts(idle, idle.copy(statuses = mapOf(KeepAlive.SUBAGENTS_STATUS to "1 agent"))), "background agents")
        // Already busy: no second start; a status that is not work starts nothing.
        assertFalse(KeepAlive.starts(idle.copy(isRunning = true), idle.copy(isRunning = true, isCompacting = true)))
        assertFalse(KeepAlive.starts(idle, idle.copy(statuses = mapOf("todo" to "2/5"))))
    }

    @Test
    fun theServiceCountsWhatItsNotificationShowsAsBusy() {
        // KeepAlive.busy starts the service and RunningSummary.busy keeps it: work one counts and the other not would
        // start the service only for it to stop itself at once, or never start it for work it would keep.
        val idle = SessionState(hello = true)
        val states = listOf(
            idle,
            idle.copy(isRunning = true),
            idle.copy(isCompacting = true),
            idle.copy(dialogs = listOf(confirm)),
            idle.copy(statuses = mapOf(BackgroundJobs.STATUS_KEY to "1 job")),
            idle.copy(statuses = mapOf(KeepAlive.SUBAGENTS_STATUS to "1 agent")),
        )
        for (s in states) {
            val entry = RunningSummary.Entry(
                name = "p", local = true, running = s.isRunning, waiting = s.dialogs.isNotEmpty(), compacting = s.isCompacting, connected = true,
                subagents = KeepAlive.subagents(s), jobs = BackgroundJobs.STATUS_KEY in s.statuses,
            )
            assertEquals(entry.busy, KeepAlive.busy(s), "$s")
        }
    }

    @Test
    fun aSentPromptHoldsTheServiceUntilItsTurnShows() {
        // Pi answers `prompt` once it accepted it; the turn's agent_start may still be behind that answer.
        assertTrue(KeepAlive.waitsForTurn("started", running = false))
        assertFalse(KeepAlive.waitsForTurn("started", running = true))
        // Queued behind a running turn, or handled by an extension command: no turn to wait for.
        assertFalse(KeepAlive.waitsForTurn("queued", running = false))
        assertFalse(KeepAlive.waitsForTurn("handled", running = false))
        assertFalse(KeepAlive.waitsForTurn(null, running = false))
    }

    @Test
    fun typedActionsStayMutableOnEveryApi() {
        // Codex on #263: FLAG_IMMUTABLE on API 26-30 dropped the inline reply's text.
        assertEquals(0, SessionActions.mutability(typed = true, sdk = 26))
        assertEquals(0, SessionActions.mutability(typed = true, sdk = 30))
        assertEquals(PendingIntent.FLAG_MUTABLE, SessionActions.mutability(typed = true, sdk = 31))
        assertEquals(PendingIntent.FLAG_IMMUTABLE, SessionActions.mutability(typed = false, sdk = 26))
        assertEquals(PendingIntent.FLAG_IMMUTABLE, SessionActions.mutability(typed = false, sdk = 36))
    }
}
