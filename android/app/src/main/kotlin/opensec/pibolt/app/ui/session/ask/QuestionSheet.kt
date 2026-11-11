package opensec.pibolt.app.ui.session.ask

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import opensec.pibolt.app.ui.components.PiSheet
import opensec.pibolt.app.ui.components.PiTextField
import opensec.pibolt.app.ui.components.PrimaryButton
import opensec.pibolt.app.ui.components.QuadMode
import opensec.pibolt.app.ui.components.QuadSpinner
import opensec.pibolt.app.ui.components.SOFT_PRESS
import opensec.pibolt.app.ui.components.SecondaryButton
import opensec.pibolt.app.ui.components.Tag
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.components.rememberSheetCloser
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.PiMotion
import opensec.pibolt.app.ui.theme.Radius
import opensec.pibolt.app.ui.theme.Space
import opensec.pibolt.app.ui.theme.Tnum

/**
 * `ask_user_question` as one card ([AskQuestions]): "1 of 3" and a segment bar, the question's header chip, the question
 * in rowTitle, its options as rows with descriptions (radio for one choice, checkboxes for several), a "Type something"
 * field and Back / Next / Submit. Answers stay in the card until Submit ([onSubmit]); dismissing the sheet cancels the
 * questionnaire, as the extension's own dismiss does. [first] is the first question still open (normally 0).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuestionSheet(toolCallId: String, questions: List<AskQuestion>, first: Int, onSubmit: (AskSubmission) -> Unit) {
    val colors = Pi.colors
    val type = Pi.type
    val haptics = Pi.haptics
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = rememberSheetCloser(state)
    val answers = remember(toolCallId) { mutableStateListOf(*Array(questions.size) { AskAnswer() }) }
    var index by remember(toolCallId) { mutableIntStateOf(first.coerceIn(0, questions.lastIndex)) }
    LaunchedEffect(toolCallId) { haptics.longPress() }
    fun cancel() {
        haptics.reject()
        onSubmit(AskSubmission(toolCallId, questions, answers.toList(), cancel = true))
    }
    PiSheet(onDismiss = ::cancel, state = state) {
        Column(Modifier.padding(horizontal = Space.page)) {
            Row(Modifier.fillMaxWidth().padding(top = Space.xs, bottom = Space.md), verticalAlignment = Alignment.CenterVertically) {
                QuadSpinner(QuadMode.Wait, size = 16.dp, color = colors.warning)
                Spacer(Modifier.width(10.dp))
                Text("Pi is asking", style = type.metaStrong, color = colors.warning, modifier = Modifier.weight(1f))
                if (questions.size > 1) {
                    Text("${index + 1} of ${questions.size}", style = type.meta.copy(fontFeatureSettings = Tnum), color = colors.ink3)
                }
            }
            if (questions.size > 1) {
                Row(Modifier.fillMaxWidth().padding(bottom = Space.lg), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    questions.indices.forEach { i ->
                        val fill by animateColorAsState(if (i <= index) colors.accent else colors.surface3, PiMotion.effects(), label = "step")
                        Box(Modifier.weight(1f).height(3.dp).background(fill, RoundedCornerShape(2.dp)))
                    }
                }
            }
            // The page takes what is left above the action row, so with the keyboard up (the sheet stops above it) the
            // options shrink and scroll, and Back / Next stay in view.
            AnimatedContent(
                targetState = index,
                modifier = Modifier.weight(1f, fill = false),
                transitionSpec = { fadeIn(PiMotion.enter(PiMotion.QUICK)) togetherWith fadeOut(PiMotion.leave(PiMotion.QUICK)) },
                label = "question",
            ) { shown ->
                QuestionPage(
                    questions[shown],
                    answers[shown],
                    onAnswer = { answers[shown] = it },
                )
            }
            Spacer(Modifier.height(Space.lg))
            val last = index == questions.lastIndex
            val answered = questions[index].multiSelect || !answers[index].isEmpty
            Row(Modifier.fillMaxWidth().padding(bottom = Space.sm), horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                if (index > first) {
                    SecondaryButton("Back", onClick = {
                        haptics.tick()
                        index--
                    }, modifier = Modifier.weight(1f))
                } else {
                    SecondaryButton("Cancel", onClick = { close(::cancel) }, modifier = Modifier.weight(1f))
                }
                PrimaryButton(if (last) "Submit" else "Next", onClick = {
                    if (last) {
                        haptics.confirm()
                        val submission = AskSubmission(toolCallId, questions, answers.toList())
                        close { onSubmit(submission) }
                    } else {
                        haptics.tick()
                        index++
                    }
                }, enabled = answered && (!last || AskQuestions.complete(questions, answers, first)), modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun QuestionPage(question: AskQuestion, answer: AskAnswer, onAnswer: (AskAnswer) -> Unit) {
    val focus = LocalFocusManager.current
    val colors = Pi.colors
    val type = Pi.type
    val haptics = Pi.haptics
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (question.header.isNotEmpty()) Tag(question.header, color = colors.ink1)
            if (question.multiSelect) {
                if (question.header.isNotEmpty()) Spacer(Modifier.width(Space.sm))
                Text("Choose any", style = type.meta, color = colors.ink3)
            }
        }
        Spacer(Modifier.height(Space.sm))
        Text(question.question, style = type.rowTitle, color = colors.ink1, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(Space.md))
        Column(
            Modifier.weight(1f, fill = false).heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            question.options.forEachIndexed { i, option ->
                val on = i in answer.picked
                AskOptionRow(option, on, question.multiSelect) {
                    haptics.tick()
                    onAnswer(
                        when {
                            question.multiSelect -> answer.copy(picked = if (on) answer.picked - i else answer.picked + i)
                            // One choice: an option replaces the typed answer.
                            else -> AskAnswer(picked = setOf(i))
                        },
                    )
                }
                if (on && !question.multiSelect) option.preview?.let { PreviewBlock(it) }
            }
        }
        // Always in view, under the options (which scroll when long), never scrolled out of sight.
        Spacer(Modifier.height(Space.sm))
        PiTextField(
            value = answer.text,
            onValueChange = { text ->
                // One choice: typing is the answer instead of an option.
                onAnswer(if (question.multiSelect || text.isBlank()) answer.copy(text = text) else AskAnswer(text = text))
            },
            placeholder = "Type something",
            maxLines = 4,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** An option: a radio or checkbox glyph, the label (rowTitle) and its description (rowMeta ink3); selected rows lift. */
@Composable
private fun AskOptionRow(option: AskOption, selected: Boolean, multi: Boolean, onClick: () -> Unit) {
    val colors = Pi.colors
    val shape = RoundedCornerShape(Radius.tile)
    val border by animateColorAsState(if (selected) colors.accent else colors.line, PiMotion.effects(), label = "option")
    val glyph = when {
        multi && selected -> Icons.Rounded.CheckBox
        multi -> Icons.Rounded.CheckBoxOutlineBlank
        selected -> Icons.Rounded.RadioButtonChecked
        else -> Icons.Rounded.RadioButtonUnchecked
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Pi.dims.optionRow)
            .pressable(onClick, shape = shape, pressedScale = SOFT_PRESS, role = if (multi) Role.Checkbox else Role.RadioButton)
            .semantics {
                this.selected = selected
                stateDescription = if (selected) "Selected" else "Not selected"
            }
            .background(if (selected) colors.selected else colors.surface1, shape)
            .border(if (selected) 1.5.dp else 1.dp, border, shape)
            .padding(horizontal = Space.lg, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(glyph, contentDescription = null, tint = if (selected) colors.accent else colors.ink3, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text(option.label, style = Pi.type.rowTitle, color = colors.ink1, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (option.description.isNotBlank()) Text(option.description, style = Pi.type.rowMeta, color = colors.ink3, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** An option's preview (a mockup, a snippet): mono on surface2, scrolls sideways, at most a dozen lines shown. */
@Composable
private fun PreviewBlock(text: String) {
    val colors = Pi.colors
    Box(
        Modifier
            .fillMaxWidth()
            .background(colors.surface2, RoundedCornerShape(Radius.md))
            .border(1.dp, colors.line, RoundedCornerShape(Radius.md))
            .horizontalScroll(rememberScrollState())
            .padding(Space.md),
    ) {
        Text(text.lineSequence().take(12).joinToString("\n"), style = Pi.type.monoSmall, color = colors.ink2, softWrap = false)
    }
}
