package opensec.pibolt.app.terminal

import opensec.pibolt.app.secrets.SecretEnv

/**
 * A terminal shell's environment: the engine's ([engine]: PATH, HOME, the CA bundle, git's paths, TMPDIR...), a real
 * terminal type instead of the engine's `dumb`, a short prompt, then the project's [secrets].
 */
fun shellEnvironment(engine: Map<String, String>, secrets: Map<String, String>): Map<String, String> {
    val env = engine.toMutableMap()
    env["TERM"] = "xterm-256color"
    env["COLORTERM"] = "truecolor"
    env.putIfAbsent("PS1", "\\w \\$ ")
    return SecretEnv.inject(env, secrets)
}
