package opensec.pibolt.app

import androidx.compose.ui.unit.dp
import opensec.pibolt.app.ui.theme.PiDims
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The component and token rules of DESIGN.md ("Components"), checked on the UI sources so screens built by different
 * hands do not drift: one icon family, the app's own buttons, fields, switches, sheets and progress, type and motion
 * from the tokens. A rule names its fix; [ALLOWED] lists the reviewed exceptions, each with its reason.
 */
class DesignRulesTest {
    private val ui = File("src/main/kotlin/opensec/pibolt/app/ui")

    private data class Rule(val name: String, val pattern: Regex, val fix: String, val except: Set<String> = emptySet())

    private val rules = listOf(
        Rule(
            "one icon family",
            Regex("""Icons\.(Outlined|Filled|Sharp|TwoTone|Default)\b|Icons\.AutoMirrored\.(Outlined|Filled|Sharp|TwoTone)\b"""),
            "use Icons.Rounded or Icons.AutoMirrored.Rounded",
            setOf("projects/ProjectCard.kt"),
        ),
        Rule(
            "the app's buttons",
            Regex("""(?<![\w.])(Button|TextButton|OutlinedButton|FilledTonalButton|ElevatedButton|IconButton|FloatingActionButton)\("""),
            "use PrimaryButton, SecondaryButton, GhostButton, DangerButton, TextAction or IconAction",
        ),
        Rule(
            "sheets, not dialogs",
            Regex("""(?<![\w.])(AlertDialog|BasicAlertDialog)\("""),
            "use PiSheet or ConfirmSheet",
        ),
        Rule(
            "the app's fields",
            Regex("""(?<![\w.])(TextField|OutlinedTextField)\("""),
            "use PiTextField or SecretField",
        ),
        Rule(
            "the app's switch",
            Regex("""(?<![\w.])Switch\("""),
            "use SwitchRow or PiSwitch",
            setOf("components/Lists.kt"),
        ),
        Rule(
            "Quad, not Material progress",
            Regex("""(?<![\w.])(CircularProgressIndicator|LinearProgressIndicator)\("""),
            "use QuadSpinner, LoadingRow or ProgressLine",
        ),
        Rule(
            "the app's menus",
            Regex("""(?<![\w.])DropdownMenu\("""),
            "use PiMenu (surface3, radius tile, a lineStrong ring, no shadow)",
            setOf("components/Menus.kt"),
        ),
        Rule(
            "glass back and close",
            Regex("""IconAction\(Icons\.(AutoMirrored\.Rounded\.ArrowBack|Rounded\.Close), "(Close|Back)""""),
            "a screen's back or close is a GlassIconButton (in a GlassBar)",
            // The image viewer is immersive: white glyphs on black, over the picture.
            setOf("session/features/ImageViewer.kt"),
        ),
        Rule(
            "the app's snackbars",
            Regex("""(?<![\w.])Snackbar\(|Toast\.makeText"""),
            "use PiSnackHost and SnackbarHostState.show",
        ),
        Rule(
            "tabular figures through the token",
            Regex(""""'?tnum'?( 1)?""""),
            "use Tnum",
            setOf("theme/"),
        ),
        Rule(
            "type sizes from the scale",
            Regex("""fontSize = [0-9.]+\.sp"""),
            "use a PiType style (or scale one: style.fontSize * factor)",
        ),
        Rule(
            "radii from the tokens",
            Regex("""RoundedCornerShape\(([3-9]|[1-9][0-9])(\.[0-9]+)?\.dp"""),
            "use Radius.xs / sm / md / lg / xl (0 to 2dp hairlines are fine)",
        ),
        Rule(
            "durations from PiMotion",
            Regex("""tween\(([1-9][0-9]*)[,)]"""),
            "use PiMotion.TAP / QUICK / STANDARD / SHEET / SETTLE / GLYPH / FADE / PULSE / SWEEP",
            setOf("session/subagent/", "markdown/"),
        ),
    )

    private fun sources(): List<Pair<String, String>> {
        assertTrue(ui.isDirectory, "run from the app module (${ui.absolutePath})")
        return ui.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(ui).path.replace(File.separatorChar, '/') to it.readText() }
            .toList()
    }

    @Test
    fun uiSourcesFollowTheComponentAndTokenRules() {
        val files = sources()
        val failures = ArrayList<String>()
        for (rule in rules) {
            for ((path, text) in files) {
                if (rule.except.any { path == it || (it.endsWith("/") && path.startsWith(it)) }) continue
                text.lineSequence().forEachIndexed { i, line ->
                    val code = line.substringBefore("//")
                    if (!code.trimStart().startsWith("import ") && !code.trimStart().startsWith("*") && rule.pattern.containsMatchIn(code)) {
                        failures += "$path:${i + 1} (${rule.name}: ${rule.fix}): ${line.trim()}"
                    }
                }
            }
        }
        assertTrue(failures.isEmpty(), "DESIGN.md component and token rules:\n" + failures.joinToString("\n"))
    }

    @Test
    fun exceptionsStillExist() {
        // An exception for a file that no longer breaks the rule (or no longer exists) is removed, so the list only shrinks.
        val files = sources().toMap()
        val stale = ArrayList<String>()
        for (rule in rules) {
            for (except in rule.except) {
                val hit = files.any { (path, text) -> (path == except || (except.endsWith("/") && path.startsWith(except))) && text.lineSequence().any { rule.pattern.containsMatchIn(it.substringBefore("//")) } }
                if (!hit) stale += "${rule.name}: $except"
            }
        }
        assertTrue(stale.isEmpty(), "stale exceptions:\n" + stale.joinToString("\n"))
    }

    @Test
    fun sharedComponentsUseTheTokens() {
        // ui/components defines the look; a literal there is a token that is missing.
        val failures = ArrayList<String>()
        for ((path, text) in sources().filter { it.first.startsWith("components/") }) {
            text.lineSequence().forEachIndexed { i, line ->
                if (Regex("""Color\(0x""").containsMatchIn(line)) failures += "$path:${i + 1} colour literal: ${line.trim()}"
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun densityTokensKeepTouchTargets() {
        // An option row is one touch target, so Compact density may tighten it only down to 48dp (it was 46dp).
        for (compact in listOf(false, true)) {
            assertTrue(PiDims(compact).optionRow >= 48.dp, "optionRow (compact = $compact) under 48dp")
            assertTrue(PiDims(compact).homeRow >= 48.dp, "homeRow (compact = $compact) under 48dp")
        }
    }
}
