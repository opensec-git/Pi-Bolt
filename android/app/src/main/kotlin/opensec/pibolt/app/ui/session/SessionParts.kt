package opensec.pibolt.app.ui.session

import androidx.compose.runtime.Immutable

/**
 * The project-only parts of the session screen, per kind of session: a project's session on this phone has them all;
 * a session with a computer has the folder; a plain chat (engine.ChatSpace: no project, no tools) has none. Turn
 * checkpoints, undo and the changes pill need [opensec.pibolt.app.session.SessionController.checkpoints], which a chat
 * has none of; the workspace tabs (Files, Changes, Terminal) belong to the project screen, which a chat never opens.
 */
@Immutable
data class SessionParts(
    /** `@` file mentions and the attach sheet's "Mention a file". */
    val files: Boolean,
    /** `!` shell commands. */
    val shell: Boolean,
    /** The dev-server preview chips over the composer. */
    val preview: Boolean,
    /** The empty session's suggestions from the project's files. */
    val projectStarters: Boolean,
    /** The working folder: the top bar's second line for a computer, the session sheet's folder. */
    val folder: Boolean,
    /** The top bar's second line (the session name, or a computer's folder). A chat's title already is its name. */
    val subtitle: Boolean,
) {
    companion object {
        val PROJECT = SessionParts(files = true, shell = true, preview = true, projectStarters = true, folder = true, subtitle = true)
        val REMOTE = SessionParts(files = false, shell = false, preview = false, projectStarters = false, folder = true, subtitle = true)
        val CHAT = SessionParts(files = false, shell = false, preview = false, projectStarters = false, folder = false, subtitle = false)

        fun of(local: Boolean, chat: Boolean): SessionParts = when {
            chat -> CHAT
            local -> PROJECT
            else -> REMOTE
        }
    }
}
