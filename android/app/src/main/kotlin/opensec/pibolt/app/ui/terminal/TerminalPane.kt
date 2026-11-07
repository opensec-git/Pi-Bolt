package opensec.pibolt.app.ui.terminal

import opensec.pibolt.app.ui.projects.EmptyCard
import opensec.pibolt.app.ui.projects.WorkspaceMenu
import opensec.pibolt.app.ui.projects.WorkspaceMenuRow
import opensec.pibolt.app.ui.projects.WorkspaceTab
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.Terminal
import android.annotation.SuppressLint
import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import opensec.pibolt.app.ui.components.GlassIconButton
import opensec.pibolt.app.ui.components.GlassPill
import opensec.pibolt.app.ui.components.SOFT_PRESS
import opensec.pibolt.app.ui.components.glass
import opensec.pibolt.app.ui.theme.IconSize
import opensec.pibolt.app.ui.theme.Space
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloseFullscreen
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.TextDecrease
import androidx.compose.material.icons.rounded.TextIncrease
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.edit
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.launch
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.R
import opensec.pibolt.app.terminal.TerminalTab
import opensec.pibolt.app.terminal.Terminals
import opensec.pibolt.app.ui.components.IconAction
import opensec.pibolt.app.ui.components.PrimaryButton
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.session.Ansi
import opensec.pibolt.app.ui.session.AnsiColor
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.PiColors
import opensec.pibolt.app.ui.theme.PiMotion
import opensec.pibolt.app.ui.theme.Radius
import java.io.File

/**
 * A project's terminals: tabs, the terminal itself (Termux's TerminalView: scrollback by dragging, pinch to zoom,
 * long press to select and copy), an extra-keys row and the dev-server preview chip. Used in the project's Terminal
 * tab and full screen; both show the same shells ([Terminals]).
 */
@Composable
fun TerminalPane(
    app: PiBoltApp,
    project: File,
    fullScreen: Boolean,
    onToggleFullScreen: () -> Unit,
    onPreview: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Pi.colors
    val type = Pi.type
    val scope = rememberCoroutineScope()
    val tabs = Terminals.inProject(project)
    val tab = Terminals.selectedIn(project)
    var starting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val prefs = remember { app.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var fontSp by remember { mutableIntStateOf(prefs.getInt(KEY_FONT, DEFAULT_FONT_SP).coerceIn(MIN_FONT_SP, MAX_FONT_SP)) }
    val ctrl = remember { mutableStateOf(false) }
    val alt = remember { mutableStateOf(false) }
    var view by remember { mutableStateOf<TerminalView?>(null) }
    var sizeMenu by remember { mutableStateOf(false) }

    fun setFont(sp: Int) {
        fontSp = sp.coerceIn(MIN_FONT_SP, MAX_FONT_SP)
        prefs.edit { putInt(KEY_FONT, fontSp) }
    }

    fun start() {
        if (starting) return
        starting = true
        error = null
        scope.launch {
            Terminals.open(app, project).onFailure { error = it.message ?: "Could not start a shell" }
            starting = false
        }
    }

    Terminals.applyColors(terminalPalette(colors))

    Column(modifier.background(colors.canvas)) {
        // Tabs and actions: shells as capsules (the shown one glass), then + and the glass actions at the gutter.
        Row(Modifier.fillMaxWidth().height(56.dp).padding(end = Space.lg), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(start = Space.page - Space.sm), verticalAlignment = Alignment.CenterVertically) {
                for (t in tabs) TabChip(t, selected = t.id == tab?.id, onSelect = { Terminals.selected[t.project] = t.id }, onClose = { Terminals.close(app, t) })
                IconAction(Icons.Rounded.Add, "New terminal", onClick = ::start, enabled = !starting, tint = colors.ink1)
            }
            if (tab != null) {
                GlassIconButton(Icons.Rounded.ContentPaste, "Paste", onClick = { tab.session.onPasteTextFromClipboard() }, size = 40.dp)
                // Text size is also a pinch on the terminal: the two steps live in one menu, not two icons.
                Box {
                    GlassIconButton(Icons.Rounded.FormatSize, "Text size, $fontSp", onClick = { sizeMenu = true }, size = 40.dp)
                    WorkspaceMenu(sizeMenu, { sizeMenu = false }) {
                        WorkspaceMenuRow("Larger text", Icons.Rounded.TextIncrease, if (fontSp < MAX_FONT_SP) colors.ink1 else colors.ink3) { setFont(fontSp + 1) }
                        WorkspaceMenuRow("Smaller text", Icons.Rounded.TextDecrease, if (fontSp > MIN_FONT_SP) colors.ink1 else colors.ink3) { setFont(fontSp - 1) }
                        Text("$fontSp sp · or pinch", style = type.meta, color = colors.ink3, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                }
            }
            GlassIconButton(
                if (fullScreen) Icons.Rounded.CloseFullscreen else Icons.Rounded.OpenInFull,
                if (fullScreen) "Leave full screen" else "Full screen terminal",
                onClick = onToggleFullScreen,
                size = 40.dp,
            )
        }
        PreviewChipRow(onPreview, Modifier.padding(horizontal = Space.page))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (tab == null) {
                Column(Modifier.fillMaxSize().padding(top = Space.xs)) {
                    EmptyCard(Icons.Rounded.Terminal, "A shell in this project", "bash with git, curl and rg") {
                        PrimaryButton(if (starting) "Starting…" else "Start a terminal", onClick = ::start, enabled = !starting, modifier = Modifier.fillMaxWidth())
                        error?.let { Text(it, style = type.meta, color = colors.danger, modifier = Modifier.padding(top = 8.dp)) }
                    }
                }
            } else {
                TerminalSurface(app, tab, fontSp, ctrl, alt, onFont = ::setFont, onView = { view = it })
            }
        }
        if (tab != null) ExtraKeys(view, ctrl, alt)
    }
}

/** The tab's TerminalView. A new view (rotation, a new screen) attaches to the same session; the shell never notices. */
@SuppressLint("ClickableViewAccessibility")
@Composable
private fun TerminalSurface(
    app: PiBoltApp,
    tab: TerminalTab,
    fontSp: Int,
    ctrl: MutableState<Boolean>,
    alt: MutableState<Boolean>,
    onFont: (Int) -> Unit,
    onView: (TerminalView?) -> Unit,
) {
    val density = LocalDensity.current
    val fontPx = with(density) { fontSp.dp.toPx() * density.fontScale }.toInt()
    val currentFont by rememberUpdatedState(fontSp)
    val currentOnFont by rememberUpdatedState(onFont)
    val label = "${tab.label}, ${tab.title}" + if (tab.running) "" else ", ended"
    AndroidView(
        factory = { context ->
            TerminalView(context, null).apply {
                // Drags scroll the terminal's scrollback, never a scrolling screen around it. The listener only
                // claims the gesture and returns false: TerminalView's own onTouchEvent handles taps and clicks.
                setOnTouchListener { v, e ->
                    if (e.actionMasked == MotionEvent.ACTION_DOWN) v.parent?.requestDisallowInterceptTouchEvent(true)
                    false
                }
                isFocusable = true
                isFocusableInTouchMode = true
                // The size first: setTypeface needs the renderer that setTextSize creates.
                tag = fontPx
                setTextSize(fontPx)
                ResourcesCompat.getFont(context, R.font.jetbrains_mono_regular)?.let(::setTypeface)
                setTerminalViewClient(ViewClient(this, app, ctrl, alt, { currentFont }, { currentOnFont(it) }))
                onView(this)
            }
        },
        update = { v ->
            // setTextSize rebuilds the renderer and resizes the pty: only on a real change.
            if (v.tag != fontPx) {
                v.tag = fontPx
                v.setTextSize(fontPx)
            }
            Terminals.attach(v, tab)
        },
        onRelease = { v ->
            Terminals.detach(v)
            onView(null)
        },
        modifier = Modifier.fillMaxSize().semantics { contentDescription = label },
    )
}

private class ViewClient(
    private val view: TerminalView,
    private val app: PiBoltApp,
    private val ctrl: MutableState<Boolean>,
    private val alt: MutableState<Boolean>,
    private val font: () -> Int,
    private val onFont: (Int) -> Unit,
) : TerminalViewClient {
    override fun onScale(scale: Float): Float {
        // Pinch: one step per 10% of scale, as Termux does.
        if (scale < 0.9f || scale > 1.1f) {
            onFont(font() + if (scale > 1f) 1 else -1)
            return 1f
        }
        return scale
    }

    override fun onSingleTapUp(e: MotionEvent?) {
        view.requestFocus()
        view.context.getSystemService(InputMethodManager::class.java)?.showSoftInput(view, 0)
    }

    override fun shouldBackButtonBeMappedToEscape() = false

    // Termux's default: TYPE_NULL input, so keyboards send plain key events (no suggestions or autocorrect).
    override fun shouldEnforceCharBasedInput() = false

    override fun shouldUseCtrlSpaceWorkaround() = false

    override fun isTerminalViewSelected() = true

    override fun copyModeChanged(copyMode: Boolean) = Unit

    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean {
        // Enter on an ended shell closes its tab.
        if (session != null && !session.isRunning && keyCode == KeyEvent.KEYCODE_ENTER) {
            Terminals.tabs.firstOrNull { it.session === session }?.let { Terminals.close(app, it) }
            return true
        }
        return false
    }

    override fun onKeyUp(keyCode: Int, e: KeyEvent?) = false

    override fun onLongPress(event: MotionEvent?) = false

    override fun readControlKey() = ctrl.value

    override fun readAltKey() = alt.value

    override fun readShiftKey() = false

    override fun readFnKey() = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean {
        // Ctrl and Alt on the extra-keys row apply to one key, then let go.
        ctrl.value = false
        alt.value = false
        return false
    }

    override fun onEmulatorSet() {
        view.invalidate()
    }

    override fun logError(tag: String?, message: String?) = Unit

    override fun logWarn(tag: String?, message: String?) = Unit

    override fun logInfo(tag: String?, message: String?) = Unit

    override fun logDebug(tag: String?, message: String?) = Unit

    override fun logVerbose(tag: String?, message: String?) = Unit

    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit

    override fun logStackTrace(tag: String?, e: Exception?) = Unit
}

@Composable
private fun TabChip(tab: TerminalTab, selected: Boolean, onSelect: () -> Unit, onClose: () -> Unit) {
    WorkspaceTab(
        name = "${tab.id} · ${tab.title}",
        selected = selected,
        onSelect = onSelect,
        onClose = onClose,
        description = "${tab.label}, " + if (tab.running) tab.title else "ended",
        closeDescription = "Close ${tab.label}",
        modifier = Modifier.padding(end = Space.xs),
        ended = !tab.running,
    )
}

/**
 * Esc, Tab, Ctrl, Alt, the arrows and the shell characters a phone keyboard hides, as glass keys on the canvas. Keys
 * are at least 48dp wide: where all twelve fit (landscape, tablets) they share the width, so the row has no dead end;
 * on a phone it scrolls.
 */
@Composable
private fun ExtraKeys(view: TerminalView?, ctrl: MutableState<Boolean>, alt: MutableState<Boolean>) {
    val colors = Pi.colors
    fun key(code: Int) {
        val v = view ?: return
        v.onKeyDown(code, KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, 0, 0))
        ctrl.value = false
        alt.value = false
    }

    fun char(c: Char) {
        val v = view ?: return
        v.inputCodePoint(c.code, false, false)
        ctrl.value = false
        alt.value = false
    }
    BoxWithConstraints(Modifier.fillMaxWidth().background(colors.canvas).padding(vertical = Space.xxs)) {
        val fits = maxWidth >= KEY_MIN * EXTRA_KEYS + Space.lg
        Row(
            Modifier.fillMaxWidth().then(if (fits) Modifier else Modifier.horizontalScroll(rememberScrollState())).padding(horizontal = Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val k = if (fits) Modifier.weight(1f) else Modifier
            ExtraKey("Esc", "Escape", k) { key(KeyEvent.KEYCODE_ESCAPE) }
            ExtraKey("Tab", "Tab", k) { key(KeyEvent.KEYCODE_TAB) }
            ExtraKey("Ctrl", "Control", k, toggled = ctrl.value) { ctrl.value = !ctrl.value }
            ExtraKey("Alt", "Alt", k, toggled = alt.value) { alt.value = !alt.value }
            ExtraKey(null, "Left", k, icon = Icons.AutoMirrored.Rounded.KeyboardArrowLeft) { key(KeyEvent.KEYCODE_DPAD_LEFT) }
            ExtraKey(null, "Up", k, icon = Icons.Rounded.KeyboardArrowUp) { key(KeyEvent.KEYCODE_DPAD_UP) }
            ExtraKey(null, "Down", k, icon = Icons.Rounded.KeyboardArrowDown) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
            ExtraKey(null, "Right", k, icon = Icons.AutoMirrored.Rounded.KeyboardArrowRight) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
            ExtraKey("|", "Pipe", k) { char('|') }
            ExtraKey("~", "Tilde", k) { char('~') }
            ExtraKey("/", "Slash", k) { char('/') }
            ExtraKey("-", "Dash", k) { char('-') }
        }
    }
}

private const val EXTRA_KEYS = 12
private val KEY_MIN = 48.dp

/**
 * One glass key: the whole 48dp cell is the target; the 40dp key face inside it is glass (radius md, gradient, ring
 * and top highlight), pressed at [SOFT_PRESS]. A modifier key that is on (Ctrl, Alt) fills with accent, onAccent text.
 */
@Composable
private fun ExtraKey(text: String?, description: String, modifier: Modifier = Modifier, icon: ImageVector? = null, toggled: Boolean? = null, onClick: () -> Unit) {
    val colors = Pi.colors
    val shape = RoundedCornerShape(Radius.md)
    val on by animateFloatAsState(if (toggled == true) 1f else 0f, PiMotion.effects(), label = "key")
    val ink = if (toggled == true) colors.onAccent else colors.ink1
    Box(
        modifier
            .widthIn(min = KEY_MIN)
            .height(KEY_MIN)
            .pressable(onClick, shape = shape, pressedScale = SOFT_PRESS, label = description)
            .semantics {
                contentDescription = description
                role = Role.Button
                if (toggled != null) stateDescription = if (toggled) "On for the next key" else "Off"
            }
            .padding(horizontal = Space.xxs, vertical = Space.xs)
            .glass(shape, colors)
            .drawBehind { if (on > 0f) drawRoundRect(colors.accent.copy(alpha = on), cornerRadius = CornerRadius(Radius.md.toPx())) },
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(IconSize.md))
        else Text(text.orEmpty(), style = Pi.type.monoMeta, color = ink, maxLines = 1)
    }
}

/**
 * "Preview · localhost:5173" for the dev servers listening now (nothing when none): glass pills, each with an 8dp
 * success dot for "it answers", a tap opens the preview. While Pi's preview tools drive the page, "Pi is using the
 * preview" comes first; a tap opens the preview to watch.
 */
@Composable
fun PreviewChipRow(onPreview: (Int) -> Unit, modifier: Modifier = Modifier) {
    val ports by Terminals.ports.collectAsStateWithLifecycle()
    val preview = (LocalContext.current.applicationContext as PiBoltApp).preview
    val driving by preview.driving.collectAsStateWithLifecycle()
    val drivenPort = driving?.port
    if (ports.isEmpty() && drivenPort == null) return
    val colors = Pi.colors
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalAlignment = Alignment.CenterVertically) {
        if (drivenPort != null) PiDrivingPill(onClick = { onPreview(drivenPort) })
        for (port in ports.take(4)) {
            GlassPill(onClick = { onPreview(port) }, label = "Preview localhost:$port") {
                Box(Modifier.size(8.dp).background(colors.success, CircleShape))
                Text("Preview · localhost:$port", style = Pi.type.label, color = colors.ink1, maxLines = 1)
            }
        }
    }
}

/** The theme as a terminal palette: canvas background, ink text, accent cursor, the 16 base colours as tokens. */
private fun terminalPalette(colors: PiColors): TerminalPalette =
    TerminalPalette(colors.canvas.toArgb(), colors.ink1.toArgb(), colors.accent.toArgb(), IntArray(16) { Ansi.color(AnsiColor(it), colors).toArgb() })

class TerminalPalette(val background: Int, val foreground: Int, val cursor: Int, val base16: IntArray)

private fun Terminals.applyColors(p: TerminalPalette) = applyColors(p.background, p.foreground, p.cursor, p.base16)

private const val PREFS = "terminal"
private const val KEY_FONT = "fontSp"
private const val DEFAULT_FONT_SP = 13
private const val MIN_FONT_SP = 8
private const val MAX_FONT_SP = 28
