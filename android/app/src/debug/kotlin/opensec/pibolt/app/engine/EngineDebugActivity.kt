package opensec.pibolt.app.engine

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.ui.session.SessionScreen
import opensec.pibolt.app.ui.theme.PiBoltTheme
import opensec.pibolt.protocol.rpc.StreamingBehavior
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
            PiBoltTheme(settings) {
                SessionScreen(app, onBack = { finish() }, onForgotten = { finish() })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        val engine = app.engine
        engine.prepare()
        intent.getStringExtra("project")?.let { name ->
            engine.open(File(engine.projectsDir, name), intent.getStringExtra("session"), intent.getBooleanExtra("fresh", false))
        }
        intent.getStringExtra("prompt")?.let { app.session.value?.send(it, StreamingBehavior.FollowUp) }
        intent.getStringExtra("bash")?.let { command ->
            app.appScope.launch {
                val response = app.session.value?.runBash(command)
                Log.i(LocalEngine.TAG, "debug bash: ${response?.success} ${response?.dataObject ?: response?.error}")
            }
        }
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
