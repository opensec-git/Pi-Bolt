package opensec.pibolt.app.ui.cost

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.cost.CapCard
import opensec.pibolt.app.cost.CapKind
import opensec.pibolt.app.cost.Cost
import opensec.pibolt.app.cost.Money
import opensec.pibolt.app.cost.SpendLimits
import opensec.pibolt.app.ui.components.FieldHint
import opensec.pibolt.app.ui.components.FieldLabel
import opensec.pibolt.app.ui.components.GroupRow
import opensec.pibolt.app.ui.components.GroupSwitchRow
import opensec.pibolt.app.ui.components.InsetGroup
import opensec.pibolt.app.ui.components.Notice
import opensec.pibolt.app.ui.components.PiSheet
import opensec.pibolt.app.ui.components.PiTextField
import opensec.pibolt.app.ui.components.PrimaryButton
import opensec.pibolt.app.ui.components.ProgressLine
import opensec.pibolt.app.ui.components.SecondaryButton
import opensec.pibolt.app.ui.components.SheetHeader
import opensec.pibolt.app.ui.components.SnackKind
import opensec.pibolt.app.ui.components.TextAction
import opensec.pibolt.app.ui.components.rememberSheetCloser
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.Space
import opensec.pibolt.app.ui.theme.Tnum
import java.time.ZoneId

/** The words around costs (unit-tested). */
object SpendText {
    /** Home's quiet line: "Today $0.42", "Today —" for unpriced use, null before anything today. */
    fun today(cost: Cost): String? = Money.label(cost)?.let { "Today $it" }

    /** A list row's meta with its cost at the end ("2h · 12 messages · $0.42"); unchanged without one. */
    fun withCost(meta: String, cost: Cost, show: Boolean): String {
        val label = Money.label(cost)?.takeIf { show } ?: return meta
        return if (meta.isEmpty()) label else "$meta · $label"
    }

    /** "claude-sonnet-4-5" for "anthropic/claude-sonnet-4-5"; "Other" when the model is unknown. */
    fun model(key: String): String = key.substringAfterLast('/').ifBlank { "Other" }

    /** Settings' value for the Spending row: "Off", or the first limit set ("$5.00 a day"). */
    fun summary(limits: SpendLimits): String = when {
        limits.day != null -> Money.usd(limits.day) + " a day"
        limits.month != null -> Money.usd(limits.month) + " a month"
        limits.session != null -> Money.usd(limits.session) + " a session"
        else -> "Off"
    }

    fun limitLabel(kind: CapKind): String = when (kind) {
        CapKind.Session -> "This session"
        CapKind.Day -> "Today"
        CapKind.Month -> "This month"
    }
}

/**
 * What Pi cost: this session (when [session] is given) and its project's sessions ([project]), today and this month,
 * each against its limit, then this month by model. [onLimits] opens the limits. Figures are monochrome and tabular.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpendSheet(app: PiBoltApp, session: Cost?, onLimits: () -> Unit, onDismiss: () -> Unit, note: String? = null, project: Cost? = null) {
    val colors = Pi.colors
    val type = Pi.type
    val ledger by app.spend.ledger.collectAsStateWithLifecycle()
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    val limits = settings.spend
    val now = System.currentTimeMillis()
    val zone = ZoneId.systemDefault()
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = rememberSheetCloser(state)
    val figures = type.rowMeta.copy(fontFeatureSettings = Tnum)
    PiSheet(onDismiss = onDismiss, state = state) {
        SheetHeader("Spending")
        Column(Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            val rows = buildList<Triple<String, CapKind?, Cost>> {
                if (session != null) add(Triple(SpendText.limitLabel(CapKind.Session), CapKind.Session, session))
                if (project != null) add(Triple("This project", null, project))
                add(Triple(SpendText.limitLabel(CapKind.Day), CapKind.Day, ledger.day(now, zone)))
                add(Triple(SpendText.limitLabel(CapKind.Month), CapKind.Month, ledger.month(now, zone)))
            }
            InsetGroup(footer = note) {
                rows.forEachIndexed { i, (label, kind, cost) ->
                    val limit = kind?.let(limits::of)
                    val value = Money.label(cost) ?: Money.usd(0.0)
                    GroupRow(
                        label,
                        meta = limit?.let { "Limit ${Money.usd(it)}" },
                        // A limit's progress line separates it from the next row.
                        divider = i < rows.lastIndex && limit == null,
                        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "$label, ${Money.spoken(cost) ?: "nothing"}" + (limit?.let { ", limit ${Money.usd(it)}" } ?: "") },
                        trailing = { Text(value, style = figures, color = colors.ink1, maxLines = 1) },
                    )
                    if (limit != null) {
                        ProgressLine((cost.usd / limit).toFloat(), Modifier.fillMaxWidth().padding(start = Space.lg, end = Space.lg, bottom = Space.md))
                    }
                }
            }
            val models = ledger.monthPeriod(now, zone)?.byModel.orEmpty().entries.sortedByDescending { it.value.usd }.take(MAX_MODELS)
            if (models.isNotEmpty()) {
                InsetGroup(header = "This month by model") {
                    models.forEachIndexed { i, (key, cost) ->
                        GroupRow(
                            SpendText.model(key),
                            divider = i < models.lastIndex,
                            trailing = { Text(Money.label(cost) ?: Money.NO_PRICE, style = figures, color = colors.ink1, maxLines = 1) },
                        )
                    }
                }
            }
            SecondaryButton(
                if (limits.any) "Spending limits" else "Set a limit",
                onClick = { close(onLimits) },
                modifier = Modifier.fillMaxWidth().padding(start = Space.page, end = Space.page, top = Space.lg, bottom = Space.lg),
            )
        }
    }
}

/**
 * Settings > Agent > Spending: a limit per session, per day and per month (empty: none), and whether list rows show
 * costs. Save applies them at once to every session.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpendLimitsSheet(app: PiBoltApp, onDismiss: () -> Unit) {
    val current = Pi.settings.spend
    var session by remember { mutableStateOf(Money.field(current.session)) }
    var day by remember { mutableStateOf(Money.field(current.day)) }
    var month by remember { mutableStateOf(Money.field(current.month)) }
    var inLists by remember { mutableStateOf(current.showInLists) }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = rememberSheetCloser(state)
    fun bad(text: String) = text.isNotBlank() && Money.parse(text) == null
    val invalid = bad(session) || bad(day) || bad(month)
    fun save() {
        if (invalid) return
        val next = SpendLimits(Money.parse(session), Money.parse(day), Money.parse(month), inLists)
        app.settings.update { it.copy(spend = next) }
        close(onDismiss)
    }
    PiSheet(onDismiss = onDismiss, state = state) {
        SheetHeader("Spending limits", subtitle = "At a limit, Pi stops after its current step.")
        Column(Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState()).padding(horizontal = Space.lg)) {
            LimitField("Per session", session, { session = it }, bad(session))
            LimitField("Per day", day, { day = it }, bad(day))
            LimitField("Per month", month, { month = it }, bad(month), last = true, onDone = ::save)
        }
        InsetGroup {
            GroupSwitchRow("Show costs in lists", inLists, { inLists = it }, meta = "Sessions and chats", divider = false)
        }
        PrimaryButton(
            "Save",
            onClick = ::save,
            enabled = !invalid,
            modifier = Modifier.fillMaxWidth().padding(start = Space.page, end = Space.page, top = Space.lg, bottom = Space.lg).navigationBarsPadding(),
        )
    }
}

@Composable
private fun LimitField(label: String, value: String, onChange: (String) -> Unit, error: Boolean, last: Boolean = false, onDone: () -> Unit = {}) {
    FieldLabel(label)
    PiTextField(
        value = value,
        onValueChange = { text -> onChange(text.filter { it.isDigit() || it == '.' || it == ',' }.take(9)) },
        placeholder = "No limit",
        singleLine = true,
        isError = error,
        style = Pi.type.body.copy(fontFeatureSettings = Tnum),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = if (last) ImeAction.Done else ImeAction.Next),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        leading = { Text("$", style = Pi.type.body, color = Pi.colors.ink3) },
        modifier = Modifier.fillMaxWidth(),
    )
    if (error) FieldHint("Type an amount in dollars, such as 5 or 2.50", isError = true)
}

private const val MAX_MODELS = 8

/** Today's cost for Home, from the ledger (recomposes only when the day's figure changes). */
@Composable
fun rememberTodayLine(app: PiBoltApp): String? {
    val ledger by app.spend.ledger.collectAsStateWithLifecycle()
    return remember(ledger) { SpendText.today(ledger.day(System.currentTimeMillis(), ZoneId.systemDefault())) }
}

/**
 * A limit held a prompt back or stopped a run: "Daily limit reached ($5.00). Raise it in Settings or continue once.",
 * with Continue once (the next run goes to its end) and Not now.
 */
@Composable
fun CapNotice(card: CapCard, onContinue: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Notice(
        card.text,
        kind = SnackKind.Warning,
        icon = Icons.Rounded.Savings,
        modifier = modifier,
        actions = {
            TextAction("Not now", onClick = onDismiss, color = Pi.colors.ink2)
            TextAction("Continue once", onClick = onContinue)
        },
    )
}
