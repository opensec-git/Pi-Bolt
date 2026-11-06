package opensec.pibolt.app.engine

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.ui.session.SessionScreen
import opensec.pibolt.app.ui.theme.PiBoltTheme
import opensec.pibolt.protocol.rpc.StreamingBehavior
import opensec.pibolt.protocol.session.AssistantTextItem
import java.io.File

/**
 * Debug builds only: opens a local session from adb and shows it in the existing session screen, until the project
 * screens exist. Examples:
 *
 * ```
 * adb shell am start -n in.opensec.pibolt/opensec.pibolt.app.engine.EngineDebugActivity --es project demo
 * adb shell am start -n ... --es project demo --ez fresh true
 * adb shell am start -n ... --es prompt "hello"
 * adb shell am start -n ... --es bash "git status"
 * adb shell am start -n ... --ez close true       (end the live session)
 * adb shell am start -n ... --ez trim true        (stop the spare engine)
 * adb shell am start -n ... --ez status true      (logcat -s PiBoltEngine)
 * ```
 */
class EngineDebugActivity : ComponentActivity() {
    private val app get() = application as PiBoltApp

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handle(intent)
        setContent {
            val settings by app.settings.settings.collectAsStateWithLifecycle()
            val live by app.session.collectAsStateWithLifecycle()
            PiBoltTheme(settings) {
                // Stays open without a session, so measurements do not send the app to the background.
                if (live == null) Box(Modifier.fillMaxSize()) else SessionScreen(app, onBack = {}, onForgotten = {})
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        val engine = app.engine
        if (!intent.getBooleanExtra("noprepare", false)) engine.prepare()
        intent.getStringExtra("project")?.let { name ->
            engine.open(File(engine.projectsDir, name), intent.getStringExtra("session"), intent.getBooleanExtra("fresh", false))
        }
        intent.getStringExtra("prompt")?.let { text ->
            val controller = app.session.value ?: return@let
            val started = SystemClock.elapsedRealtime()
            controller.send(text, StreamingBehavior.FollowUp)
            // Time to first token as the screen sees it: the first published state with streamed assistant text.
            app.appScope.launch(Dispatchers.Default) {
                val running = withTimeoutOrNull(120_000) { controller.state.first { it.isRunning } }?.let { SystemClock.elapsedRealtime() - started }
                withTimeoutOrNull(120_000) { controller.state.first { s -> s.streaming.any { it is AssistantTextItem && it.text.isNotEmpty() } } }
                    ?.let { Log.i(LocalEngine.TAG, "debug prompt: running after $running ms, first token on screen after ${SystemClock.elapsedRealtime() - started} ms") }
                withTimeoutOrNull(300_000) { controller.state.first { s -> !s.isRunning } }
                    ?.let { Log.i(LocalEngine.TAG, "debug prompt: settled after ${SystemClock.elapsedRealtime() - started} ms") }
            }
        }
        intent.getStringExtra("bash")?.let { command ->
            app.appScope.launch {
                val started = SystemClock.elapsedRealtime()
                val response = app.session.value?.runBash(command)
                Log.i(LocalEngine.TAG, "debug bash (${SystemClock.elapsedRealtime() - started} ms): ${response?.success} ${response?.dataObject ?: response?.error}")
            }
        }
        if (intent.getBooleanExtra("close", false)) app.closeSession()
        if (intent.getBooleanExtra("trim", false)) (engine as? LocalEngine)?.trimMemory("debug")
        if (intent.getBooleanExtra("status", false)) {
            app.appScope.launch(Dispatchers.IO) {
                val live = app.session.value
                Log.i(LocalEngine.TAG, "debug status: ${engine.status.value}; last open ${(engine as? LocalEngine)?.lastOpen}")
                Log.i(LocalEngine.TAG, "debug live: ${live?.project} file ${live?.sessionFile} link ${live?.link?.value}")
                live?.project?.let { p -> engine.sessions(p).forEach { Log.i(LocalEngine.TAG, "debug session: $it") } }
                Log.i(LocalEngine.TAG, "debug auth: ${engine.auth.configured.value}")
            }
        }
    }
}
