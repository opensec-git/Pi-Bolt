// Starts a helper in its own session that inherits stdout, then runs until stdin closes. The helper is outside the
// server's process group, so killing the group leaves it holding the pipe (upstream #12238).
import { spawn } from "node:child_process";

const helper = spawn(process.execPath, ["-e", "setInterval(() => {}, 1000)"], {
	detached: true,
	stdio: ["ignore", "inherit", "ignore"],
});
console.error(`helper ${helper.pid}`);
helper.unref();
process.stdin.resume();
process.stdin.on("end", () => process.exit(0));
