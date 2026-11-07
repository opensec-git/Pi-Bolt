package opensec.pibolt.app.ui.session.voice

import opensec.pibolt.app.ui.session.ask.AskQuestion
import java.util.Locale

/** What a spoken answer to a question card means ([SpokenAnswers.match]). */
sealed interface SpokenAnswer {
    /** Options by index; empty only for "none" on a multi-select question. */
    data class Picked(val options: Set<Int>) : SpokenAnswer

    /** A free answer ("other: use SQLite instead"), the card's "Type something". */
    data class Typed(val text: String) : SpokenAnswer

    /** "Cancel", "never mind": the questionnaire is declined, as dismissing the card does. */
    data object Cancel : SpokenAnswer

    /** Nothing that maps to an option. */
    data object Unclear : SpokenAnswer
}

/**
 * Answering `ask_user_question` by voice (pure, unit-tested): how a question is read out ([prompt]) and what the reply
 * means ([match]). An option is picked by its number ("option two", "2", "the second one", "number 3") or its label
 * ("Postgres"); several for a multi-select question ("one and three", "Postgres and Redis", "all of them", "none").
 * A free answer needs a lead word ("other …", "my answer is …"), so a misheard number never becomes a typed answer.
 */
object SpokenAnswers {
    private val cardinals = mapOf(
        "one" to 1, "won" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
    )
    private val ordinals = mapOf(
        "first" to 1, "1st" to 1, "second" to 2, "2nd" to 2, "third" to 3, "3rd" to 3, "fourth" to 4, "4th" to 4, "fifth" to 5, "5th" to 5,
        "sixth" to 6, "6th" to 6, "seventh" to 7, "7th" to 7, "eighth" to 8, "8th" to 8, "ninth" to 9, "9th" to 9, "tenth" to 10, "10th" to 10,
    )

    /** Words that only lead a number ("option two"); after them "to", "too" and "for" are numbers too. */
    private val leads = setOf("option", "options", "number", "numbers", "choice", "choices", "answer", "item")
    private val homophones = mapOf("to" to 2, "too" to 2, "for" to 4)

    /** Words around a pick that carry no meaning ("I'll take the second one please"). */
    private val fillers = setOf(
        "i", "ill", "i'll", "id", "i'd", "want", "take", "pick", "choose", "select", "go", "with", "use", "the", "a", "an", "one", "ones", "please",
        "and", "or", "plus", "also", "both", "let's", "lets", "it", "is", "that", "this", "uh", "um", "okay", "ok", "yes", "yeah", "like", "would", "say",
    )
    private val cancels = setOf("cancel", "never mind", "nevermind", "skip", "skip it", "dismiss", "no answer", "forget it", "cancel that", "stop")
    private val typedLeads = listOf("something else", "my answer is", "the answer is", "other", "type", "custom", "answer")
    private val alls = setOf("all", "all of them", "every one", "everything", "all options", "all of the above")
    private val nones = setOf("none", "none of them", "nothing", "no options", "neither")

    private fun norm(text: String): String = text.lowercase(Locale.ROOT)
        .replace(Regex("""\([^)]*\)"""), " ")
        .replace(Regex("""[^\p{L}\p{N}'\s]"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

    /** What [heard] means for a question with [options] (their labels); [multi] allows several. */
    fun match(heard: String, options: List<String>, multi: Boolean): SpokenAnswer {
        val said = norm(heard)
        if (said.isEmpty()) return SpokenAnswer.Unclear
        val labels = options.map(::norm)
        // A label said as it is wins over everything (an option may be called "Skip" or "Other").
        labels.indexOf(said).takeIf { it >= 0 }?.let { return SpokenAnswer.Picked(setOf(it)) }
        if (said in cancels) return SpokenAnswer.Cancel
        typed(heard, said)?.let { return it }
        if (multi && said in alls) return SpokenAnswer.Picked(options.indices.toSet())
        if (multi && said in nones) return SpokenAnswer.Picked(emptySet())
        val byNumber = numbers(said, options.size).filter { it in 1..options.size }.map { it - 1 }.toSet()
        val byLabel = labelsIn(said, labels)
        val picked = byNumber + byLabel
        if (picked.isEmpty()) return SpokenAnswer.Unclear
        if (!multi && picked.size > 1) return SpokenAnswer.Unclear
        return SpokenAnswer.Picked(picked)
    }

    /** "other: …", "my answer is …": the words after the lead, as said. */
    private fun typed(heard: String, said: String): SpokenAnswer? {
        for (lead in typedLeads) {
            if (said == lead || !said.startsWith("$lead ")) continue
            // Cut the same number of words from the original, so its case and punctuation stay.
            val words = lead.split(' ').size
            val rest = heard.trim().split(Regex("""\s+""")).drop(words).joinToString(" ").trimStart(':', ',', '-', ' ').trim()
            if (rest.isNotEmpty()) return SpokenAnswer.Typed(rest)
        }
        return null
    }

    /**
     * The option numbers in [said]: digits, number words, ordinals, "last", and "to"/"for" right after a lead word. Only a
     * reply of nothing else counts: a number inside a sentence about something else ("use two threads") is no pick.
     */
    private fun numbers(said: String, count: Int): List<Int> {
        val words = said.split(' ')
        val found = ArrayList<Int>()
        var meaningful = 0
        for ((i, w) in words.withIndex()) {
            val afterLead = i > 0 && words[i - 1] in leads
            val n = w.toIntOrNull()
                ?: ordinals[w]
                ?: cardinals[w].takeUnless { w == "one" && i > 0 && (words[i - 1] in ordinals || words[i - 1] == "last") }
                ?: homophones[w].takeIf { afterLead }
                ?: count.takeIf { w == "last" }
            when {
                n != null -> found += n
                w in leads || w in fillers -> Unit
                else -> meaningful++
            }
        }
        return if (meaningful == 0) found else emptyList()
    }

    /** Options whose label (all its words, in order) is in [said]. */
    private fun labelsIn(said: String, labels: List<String>): Set<Int> {
        val padded = " $said "
        val hits = labels.indices.filter { i -> labels[i].isNotEmpty() && padded.contains(" ${labels[i]} ") }
        // A label inside a longer matching label ("SQL" in "SQL Server") is not picked as well.
        return hits.filterNot { i -> hits.any { j -> j != i && labels[j].length > labels[i].length && " ${labels[j]} ".contains(" ${labels[i]} ") } }.toSet()
    }

    /**
     * The question read out: "Question 2 of 3." when there are several, the question, then each option by number
     * ("Option 1, Postgres."), and "You can pick more than one." for a multi-select question.
     */
    fun prompt(q: AskQuestion, index: Int, total: Int): String = buildString {
        if (total > 1) append("Question ${index + 1} of $total. ")
        append(sentence(SpokenSummary.plain(q.question).replace("\n\n", " ")))
        q.options.forEachIndexed { i, o -> append(" Option ${i + 1}, ${sentence(SpokenSummary.plain(o.label).replace("\n\n", " "))}") }
        if (q.multiSelect) append(" You can pick more than one.")
    }

    /** After an answer that did not match. */
    fun retry(q: AskQuestion): String =
        "Sorry, I didn't catch that. Say an option number" + (if (q.options.size > 1) " from 1 to ${q.options.size}" else "") + ", or say other and your answer."

    /** When voice gives up on a question: the card takes over. */
    const val ON_CARD = "I'll leave the question on screen."

    private fun sentence(text: String): String = text.trim().let { if (it.isEmpty() || it.last() in ".!?…") it else "$it." }
}
