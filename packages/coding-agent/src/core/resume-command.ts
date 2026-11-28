/**
 * The command that brings a session back in a new process, shared by the command printed on exit, the OSC 7577 resume
 * command a terminal runs, and the resume command reported to Herdr, so all of them keep the agent directory and the
 * restriction flags the same way.
 */

import { COMMAND_NAME, getAgentDir, usesDefaultAgentDir } from "../config.ts";
import { pathsEqual, resolvePath } from "../utils/paths.ts";

let programResumeFlags: readonly string[] = [];

/** Records the restriction and resource flags this process was started with (`Args.resumeFlags`, main.ts). */
export function setProgramResumeFlags(flags: readonly string[]): void {
	programResumeFlags = flags;
}

/** The restriction and resource flags this process was started with, as typed. */
export function getProgramResumeFlags(): readonly string[] {
	return programResumeFlags;
}

/**
 * `pi-bolt`, then `--agent-dir` when the agent directory is not the default, then `sessionArgs` (what names the session),
 * then `resumeFlags`. Undefined when flags were typed in another directory than the session's (`sessionCwd`): a relative
 * path in them would point elsewhere, and a session without its restrictions must not come back.
 */
export function buildResumeArgv(
	sessionArgs: readonly string[],
	sessionCwd: string,
	resumeFlags: readonly string[],
): string[] | undefined {
	// pi never changes its working directory, so it is still the one the flags were typed in.
	if (resumeFlags.length > 0 && !pathsEqual(sessionCwd, process.cwd())) return undefined;
	const argv = [COMMAND_NAME];
	if (!usesDefaultAgentDir()) argv.push("--agent-dir", resolvePath(getAgentDir()));
	argv.push(...sessionArgs, ...resumeFlags);
	return argv;
}
