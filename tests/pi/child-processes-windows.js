// child-processes.js for Windows: an extension that starts programs as Pi and its extensions do there (cmd.exe, by its path,
// as the shell tools find a shell; bare names from PATH), and checks what they get and what Pi learns of them.
import { execFileSync, spawn, spawnSync } from "node:child_process";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const cmd = join(process.env.SystemRoot ?? "C:\\Windows", "System32", "cmd.exe");
const failures = [];
const passed = [];
function check(name, ok, detail) {
	if (ok) passed.push(name);
	else failures.push(`${name} (${detail})`);
}
function exited(child) {
	return new Promise((resolve) => child.on("close", (code, signal) => resolve({ code, signal })));
}
function alive(pid) {
	try {
		process.kill(pid, 0);
		return true;
	} catch {
		return false;
	}
}

async function run() {
	check("exit code", spawnSync(cmd, ["/d", "/c", "exit 7"]).status === 7, "");
	const missing = spawnSync("C:\\nonexistent\\program.exe");
	check("ENOENT", missing.error?.code === "ENOENT", missing.error?.code);
	const asyncMissing = await new Promise((resolve) => spawn("C:\\nonexistent\\program.exe").on("error", (e) => resolve(e.code)));
	check("ENOENT (spawn)", asyncMissing === "ENOENT", asyncMissing);
	const bare = spawnSync("whoami", { encoding: "utf8" });
	check("a bare name, from PATH", bare.status === 0 && bare.stdout.trim().length > 0, `${bare.status} ${bare.error?.code}`);

	const sort = spawnSync(join(process.env.SystemRoot ?? "C:\\Windows", "System32", "sort.exe"), { input: "b\r\na\r\n" });
	check("stdin and stdout", sort.stdout?.toString().replace(/\r/g, "") === "a\nb\n", JSON.stringify(sort.stdout?.toString()));
	const stderr = spawnSync(cmd, ["/d", "/c", "echo to stderr 1>&2"]);
	check("stderr", stderr.stderr?.toString().trim() === "to stderr", stderr.stderr?.toString());
	const dir = mkdtempSync(join(tmpdir(), "pibolt-spawn-"));
	const cd = spawnSync(cmd, ["/d", "/c", "cd"], { cwd: dir }).stdout?.toString().trim();
	check("cwd", cd?.toLowerCase() === dir.toLowerCase(), cd);
	const env = spawnSync(cmd, ["/d", "/c", "echo %PIBOLT_TEST%"], { env: { ...process.env, PIBOLT_TEST: "value" } }).stdout?.toString().trim();
	check("environment", env === "value", env);
	const spaced = join(dir, "a folder with spaces");
	writeFileSync(join(dir, "args.cmd"), "@echo [%~1] [%~2]\r\n");
	const args = spawnSync(cmd, ["/d", "/c", join(dir, "args.cmd"), "one two", spaced], { encoding: "utf8" }).stdout?.trim();
	check("arguments with spaces", args === `[one two] [${spaced}]`, args);

	const self = spawn(cmd, ["/d", "/c", "exit 0"]);
	check("pid", Number.isInteger(self.pid) && self.pid > 0, String(self.pid));
	await exited(self);
	const sleeper = spawn(join(process.env.SystemRoot ?? "C:\\Windows", "System32", "ping.exe"), ["-n", "30", "127.0.0.1"], { stdio: "ignore" });
	setTimeout(() => sleeper.kill(), 200);
	const killed = await exited(sleeper);
	check("kill()", killed.signal === "SIGTERM" || killed.code !== 0, JSON.stringify(killed));
	const timedOut = spawnSync(join(process.env.SystemRoot ?? "C:\\Windows", "System32", "ping.exe"), ["-n", "30", "127.0.0.1"], { timeout: 300 });
	check("timeout", timedOut.signal === "SIGTERM" || timedOut.error?.code === "ETIMEDOUT", `${timedOut.status} ${timedOut.signal} ${timedOut.error?.code}`);

	// As Pi's bash tool on Windows: a tree of processes, ended with taskkill /T. The grandchild (the ping cmd.exe starts) is
	// found with tasklist, by the ping.exe processes that were not there before: WMI (Get-CimInstance) was slow on GitHub's
	// Windows runner and at times did not list it at all.
	const system32 = join(process.env.SystemRoot ?? "C:\\Windows", "System32");
	const pings = () =>
		[...execFileSync(join(system32, "tasklist.exe"), ["/FO", "CSV", "/NH", "/FI", "IMAGENAME eq PING.EXE"], { encoding: "utf8" }).matchAll(
			/"PING\.EXE","(\d+)"/gi,
		)].map((match) => Number(match[1]));
	const before = new Set(pings());
	const tree = spawn(cmd, ["/d", "/c", "ping -n 30 127.0.0.1 >nul"], { stdio: "ignore", detached: true, windowsHide: true });
	let grandchildren = [];
	for (let tries = 0; grandchildren.length === 0 && tries < 100; tries++) {
		await new Promise((resolve) => setTimeout(resolve, 100));
		grandchildren = pings().filter((pid) => !before.has(pid));
	}
	spawnSync(join(system32, "taskkill.exe"), ["/pid", String(tree.pid), "/T", "/F"]);
	await exited(tree);
	let anyAlive = true;
	for (let tries = 0; anyAlive && tries < 100; tries++) {
		anyAlive = grandchildren.some(alive);
		if (anyAlive) await new Promise((resolve) => setTimeout(resolve, 20));
	}
	check("a process tree, ended as one", grandchildren.length > 0 && !anyAlive, `${grandchildren.join(",")} alive=${anyAlive}`);
}

// Pi waits for an extension's factory: the checks are done before Pi goes on with its own work (it would otherwise answer the
// prompt and exit first when the checks take long).
export default async function () {
	try {
		await run();
		if (failures.length) console.log(`child-processes-windows failed: ${failures.join("; ")}`);
		else console.log(`child-processes-windows: ${passed.length} checks`);
	} catch (error) {
		console.log(`child-processes-windows failed: ${error.stack}`);
	}
	process.exit(0);
}
