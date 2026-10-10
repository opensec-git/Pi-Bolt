// An extension that starts programs as Pi and its extensions do, and checks what they get and what Pi learns of them. On macOS
// they are started through pi-spawn (scripts/lib/darwin-spawn.h): each start must load them at a new address, unlike Pi's
// own executable, and otherwise as if Pi had started them itself. PIBOLT_TEST_PROBE: a program that prints where its code and stack are.
import { execFileSync, spawn, spawnSync } from "node:child_process";
import { chmodSync, mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const failures = [];
const passed = [];
function check(name, ok, detail) {
	if (ok) passed.push(name);
	else failures.push(`${name} (${detail})`);
}
function exited(child) {
	return new Promise((resolve) => child.on("close", (code, signal) => resolve({ code, signal })));
}
function output(child) {
	let text = "";
	child.stdout.on("data", (chunk) => (text += chunk));
	return exited(child).then((result) => ({ ...result, text }));
}

async function run() {
	const probe = process.env.PIBOLT_TEST_PROBE;
	if (probe) {
		// Each start of a program must put it somewhere else, not always at the same place.
		const places = new Set();
		for (let i = 0; i < 4; i++) places.add(execFileSync(probe, { encoding: "utf8" }).trim());
		check("new address each start", places.size === 4, [...places].join(" "));
		const nested = execFileSync("/bin/sh", ["-c", `"${probe}"; "${probe}"`], { encoding: "utf8" }).trim().split("\n");
		check("new address in a shell's programs", nested.length === 2 && nested[0] !== nested[1] && !places.has(nested[0]), nested.join(" "));
		const async_ = await output(spawn(probe));
		check("new address (spawn)", async_.code === 0 && !places.has(async_.text.trim()), async_.text);
	}

	check("exit code", spawnSync("/bin/sh", ["-c", "exit 7"]).status === 7, "");
	const signalled = spawnSync("/bin/sh", ["-c", "kill -TERM $$"]);
	check("killed by a signal", signalled.signal === "SIGTERM" && signalled.status === null, `${signalled.status} ${signalled.signal}`);
	const missing = spawnSync("/nonexistent/program");
	check("ENOENT", missing.error?.code === "ENOENT", missing.error?.code);
	const dir = mkdtempSync(join(tmpdir(), "pibolt-spawn-"));
	const notExecutable = join(dir, "script");
	writeFileSync(notExecutable, "#!/bin/sh\necho hi\n");
	chmodSync(notExecutable, 0o644);
	const denied = spawnSync(notExecutable);
	check("EACCES", denied.error?.code === "EACCES", denied.error?.code);
	const asyncMissing = await new Promise((resolve) => spawn("/nonexistent/program").on("error", (e) => resolve(e.code)));
	check("ENOENT (spawn)", asyncMissing === "ENOENT", asyncMissing);

	const cat = spawnSync("/bin/cat", { input: "through stdin" });
	check("stdin and stdout", cat.stdout?.toString() === "through stdin", cat.stdout?.toString());
	const stderr = spawnSync("/bin/sh", ["-c", "echo to stderr >&2"]);
	check("stderr", stderr.stderr?.toString() === "to stderr\n", stderr.stderr?.toString());
	const pwd = spawnSync("/bin/pwd", { cwd: dir }).stdout?.toString().trim();
	check("cwd", pwd?.endsWith(dir.split("/").pop()), pwd);
	const env = spawnSync("/usr/bin/env", { env: { PIBOLT_TEST: "value" } }).stdout?.toString();
	check("environment", env === "PIBOLT_TEST=value\n", env);
	const fd3 = spawn("/bin/sh", ["-c", "echo on fd 3 >&3"], { stdio: ["ignore", "ignore", "ignore", "pipe"] });
	let fd3Text = "";
	fd3.stdio[3].on("data", (chunk) => (fd3Text += chunk));
	// (The end of the pipe, not the program's "exit": what a program wrote can arrive after it has exited.)
	await Promise.all([exited(fd3), new Promise((resolve) => fd3.stdio[3].on("end", resolve).on("close", resolve))]);
	check("a fourth descriptor", fd3Text === "on fd 3\n", fd3Text);
	const ownPgid = execFileSync("/bin/ps", ["-o", "pgid=", "-p", String(process.pid)], { encoding: "utf8" }).trim();
	const childPgid = execFileSync("/bin/sh", ["-c", "/bin/ps -o pgid= -p $$"], { encoding: "utf8" }).trim();
	check("process group", ownPgid === childPgid, `${ownPgid} ${childPgid}`);

	const self = spawn("/bin/sh", ["-c", "echo $$"]);
	const selfOut = await output(self);
	check("pid", selfOut.text.trim() === String(self.pid), `${selfOut.text.trim()} ${self.pid}`);
	const sleeper = spawn("/bin/sleep", ["30"]);
	setTimeout(() => sleeper.kill("SIGTERM"), 100);
	const killed = await exited(sleeper);
	check("kill()", killed.signal === "SIGTERM", JSON.stringify(killed));
	const sleepy = spawn("/bin/sh", ["-c", "trap 'exit 3' USR1; sleep 30 & wait"]);
	setTimeout(() => sleepy.kill("SIGUSR1"), 200);
	const trapped = await exited(sleepy);
	check("a signal the program handles", trapped.code === 3, JSON.stringify(trapped));
	const timedOut = spawnSync("/bin/sleep", ["30"], { timeout: 200 });
	check("timeout", timedOut.signal === "SIGTERM", `${timedOut.status} ${timedOut.signal}`);

	// As Pi's bash tool: a session of its own, killed as a process group.
	const group = spawn("/bin/sh", ["-c", "/bin/sleep 30 & echo $!; wait"], { detached: true, stdio: ["ignore", "pipe", "ignore"] });
	let grandchild = "";
	group.stdout.on("data", (chunk) => {
		grandchild += chunk;
		if (grandchild.endsWith("\n")) process.kill(-group.pid, "SIGKILL");
	});
	const groupKilled = await exited(group);
	// The grandchild, killed with the group, is a zombie until whoever adopted it reaps it, and kill(pid, 0) finds a zombie:
	// it has a second to go.
	let alive = true;
	for (let tries = 0; alive && tries < 100; tries++) {
		try {
			process.kill(Number(grandchild), 0);
			await new Promise((resolve) => setTimeout(resolve, 10));
		} catch {
			alive = false;
		}
	}
	check("detached, killed as a group", groupKilled.signal === "SIGKILL" && !alive, `${JSON.stringify(groupKilled)} ${grandchild.trim()} alive=${alive}`);
}

export default function () {
	run().then(
		() => {
			if (failures.length) console.log(`child-processes failed: ${failures.join("; ")}`);
			else console.log(`child-processes: ${passed.length} checks`);
			process.exit(0);
		},
		(error) => {
			console.log(`child-processes failed: ${error.stack}`);
			process.exit(0);
		},
	);
}
