package opensec.pibolt.app.ui.session

import androidx.compose.ui.graphics.vector.ImageVector
import java.io.File

/**
 * Up / Down through earlier prompts, as in the terminal editor. [entries] are most recent first. The text being typed
 * is kept as the draft: Up from it shows the newest prompt, Down past the newest gives the draft back. Any edit to a
 * recalled prompt makes it the new draft ([edited]).
 */
class PromptHistory(private var entries: List<String>) {
    /** -1 is the draft; 0 is the newest prompt. */
    var index: Int = -1
        private set
    private var draft: String = ""

    fun update(entries: List<String>) {
        if (entries != this.entries) {
            this.entries = entries
            index = -1
        }
    }

    /** The prompt before the one shown, or null when there is none (nothing changes). */
    fun older(current: String): String? {
        if (index + 1 >= entries.size) return null
        if (index == -1) draft = current
        index++
        return entries[index]
    }

    /** The prompt after the one shown, the draft after the newest, or null when already at the draft. */
    fun newer(): String? {
        if (index < 0) return null
        index--
        return if (index == -1) draft else entries[index]
    }

    /** The user changed the text: history browsing ends there. */
    fun edited(text: String) {
        if (index >= 0 && text != entries.getOrNull(index)) index = -1
    }

    companion object {
        /** Up recalls history only with the cursor on the first line, so multi-line prompts can still be edited. */
        fun onFirstLine(text: String, cursor: Int): Boolean = text.lastIndexOf('\n', (cursor - 1).coerceAtLeast(0)).let { it < 0 || it >= cursor }

        /** Down moves on only with the cursor on the last line. */
        fun onLastLine(text: String, cursor: Int): Boolean = text.indexOf('\n', cursor.coerceIn(0, text.length)) < 0
    }
}

/**
 * A suggestion on the empty session: [label] on the row, [prompt] put in the composer (never sent by itself), and an
 * optional [icon] before the label (a chat's topics).
 */
data class StarterPrompt(val label: String, val prompt: String, val icon: ImageVector? = null)

/**
 * The empty session's order of entrance (assistant-ui's Welcome): the title is step 0, its line step 1, each
 * suggestion one step, then the recent prompts as one step and the commands line as one; the composer comes last.
 */
object Welcome {
    /** Recent prompts shown on the empty session; the rest stay in the composer's Prompt history. */
    const val RECENT = 3

    /** The step of the first suggestion. */
    const val FIRST_SUGGESTION = 2

    /** The composer's step: after every element the empty session shows. */
    fun composerStep(suggestions: Int, recent: Boolean, commands: Boolean): Int =
        FIRST_SUGGESTION + suggestions + (if (recent) 1 else 0) + (if (commands) 1 else 0)
}

/** What the project folder says about itself, read once on IO for the empty session's suggestions. */
data class ProjectTraits(val empty: Boolean, val git: Boolean, val tests: Boolean)

object StarterPrompts {
    /** Files that mean "this project has a test runner". */
    val TEST_MARKERS = listOf("package.json", "Cargo.toml", "pyproject.toml", "go.mod", "pom.xml", "build.gradle", "build.gradle.kts", "Gemfile", "pytest.ini", "setup.py", "mix.exs", "Package.swift", "deno.json", "composer.json", "Makefile")

    val INSPECT = StarterPrompt("Inspect the project", "Look around this project and tell me what it is, how it is built and run, and anything that looks off.")
    val TESTS = StarterPrompt("Run the tests", "Find how this project runs its tests, run them, and summarise the results.")
    val STRUCTURE = StarterPrompt("Explain the structure", "Explain how this project is structured: the main folders and files, and how they fit together.")
    val GIT = StarterPrompt("Set up git", "Set up git for this project: initialise a repository, add a sensible .gitignore and make a first commit.")
    val COMMITS = StarterPrompt("Summarise recent commits", "Summarise the recent commits in this repository and what they changed.")
    val NEW = StarterPrompt("Start a new project", "This folder is empty. Ask me what I want to build, then set up the project.")

    /** Up to four suggestions for a project with [traits]; null traits (a remote computer) get the general ones. */
    fun pick(traits: ProjectTraits?): List<StarterPrompt> = when {
        traits == null -> listOf(INSPECT, TESTS, STRUCTURE, COMMITS)
        traits.empty -> listOfNotNull(NEW, GIT.takeIf { !traits.git })
        else -> listOfNotNull(INSPECT, TESTS.takeIf { traits.tests }, STRUCTURE, if (traits.git) COMMITS else GIT)
    }

    /** Reads [dir]'s top level (call on IO): hidden files other than `.git` do not make a folder non-empty. */
    fun traits(dir: File): ProjectTraits {
        val names = dir.list()?.toSet().orEmpty()
        return ProjectTraits(
            empty = names.none { !it.startsWith(".") },
            git = ".git" in names,
            tests = TEST_MARKERS.any { it in names } || "tests" in names || "test" in names,
        )
    }
}
