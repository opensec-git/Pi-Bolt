// pibolt-github v2: written by the Rapid Pi Android app; do not edit (it is rewritten).
// GitHub REST API tools for the agent: create repos, read/write files, issues, comments, pull requests, Actions.
// Needs GITHUB_TOKEN in the environment (set by the app when signed in to GitHub via Settings).
// Reads are available to any session; writes change GitHub data — the model should confirm with the user first.

const GITHUB_API = "https://api.github.com";
const API_VERSION = "2022-11-28";

function authToken() {
	return process.env.GITHUB_TOKEN || "";
}

function apiHeaders() {
	const t = authToken();
	const h = {
		Accept: "application/vnd.github+json",
		"X-GitHub-Api-Version": API_VERSION,
		"User-Agent": "Rapid Pi Android",
	};
	if (t) h.Authorization = `Bearer ${t}`;
	return h;
}

/** One GitHub REST call. Throws with a clear message on non-2xx. */
async function gh(method, path, body) {
	if (!authToken()) throw new Error("Not signed in to GitHub. Use Settings > Agent > GitHub to sign in first.");
	const res = await fetch(`${GITHUB_API}${path}`, {
		method,
		headers: apiHeaders(),
		...(body ? { body: JSON.stringify(body) } : {}),
	});
	let text;
	try {
		text = await res.text();
	} catch {
		throw new Error(`GitHub ${method} ${path}: no answer (network?)`);
	}
	if (!res.ok) {
		let detail = text;
		try {
			const j = JSON.parse(text);
			detail = j.message || text;
			if (j.errors) detail += " (" + j.errors.map((e) => e.message || e.code).join(", ") + ")";
		} catch {}
		const status = res.status;
		let hint = "";
		if (status === 401) hint = " Token invalid or revoked — sign in again in Settings > Agent > GitHub.";
		else if (status === 403) hint = " Token lacks permission — it needs the repo scope.";
		else if (status === 404) hint = " Repository not found, or the token can't see it. Check owner/repo and that you have access.";
		throw new Error(`GitHub ${status}: ${detail}${hint}`);
	}
	if (!text) return null;
	return JSON.parse(text);
}

function text(t, details) {
	return { content: [{ type: "text", text: t }], details };
}

/** Parse owner/repo, returning [owner, name] or null when malformed. */
function splitRepo(repo) {
	const parts = String(repo).split("/");
	if (parts.length !== 2 || !parts[0] || !parts[1]) return null;
	return parts;
}

/** Build a /repos/owner/repo<path> URL with owner and name encoded separately (slash preserved). */
function repoUrl(repo, path) {
	const p = splitRepo(repo);
	if (!p) throw new Error(`Invalid repository '${repo}': use owner/repo, e.g. 'yl-stack/yl-agent-runtime-test'`);
	const owner = encodeURIComponent(p[0]);
	const name = encodeURIComponent(p[1]);
	const cleanPath = path.startsWith("/") ? path.slice(1) : path;
	return `/repos/${owner}/${name}/${cleanPath}`;
}

function repoContentsUrl(repo, path) {
	const p = splitRepo(repo);
	if (!p) throw new Error(`Invalid repository '${repo}': use owner/repo`);
	const segments = path.split("/").map(segment => encodeURIComponent(segment));
	return `/repos/${encodeURIComponent(p[0])}/${encodeURIComponent(p[1])}/contents/${segments.join("/")}`;
}

export default function (pi) {
	/** Read a file from a GitHub repo (base64 decoded). */
	pi.registerTool({
		name: "github_read_file",
		label: "Read a GitHub file",
		description: "Read a file's contents from a GitHub repository. Give owner/repo and the file path.",
		parameters: {
			type: "object",
			properties: {
				repo: { type: "string", description: "owner/repo, e.g. 'yl-stack/yl-agent-runtime-test'" },
				path: { type: "string", description: "File path in the repo, e.g. 'README.md'" },
				ref: { type: "string", description: "Branch, tag or commit SHA (default: the repo's default branch)" },
			},
			required: ["repo", "path"],
		},
		async execute(_toolCallId, params, _signal, _onUpdate, _ctx) {
			const url = repoContentsUrl(params.repo, params.path) + (params.ref ? `?ref=${encodeURIComponent(params.ref)}` : "");
			const data = await gh("GET", url);
			if (!data) throw new Error("Empty answer from GitHub");
			let content = data.content || "";
			if (data.encoding === "base64") {
				content = Buffer.from(content, "base64").toString("utf8");
			}
			return text(
				content,
				{ path: data.path, sha: data.sha, size: data.size, downloadUrl: data.download_url },
			);
		},
	});

	/** Create or update a file in a GitHub repo. Pass currentSha to update an existing file. */
	pi.registerTool({
		name: "github_write_file",
		label: "Write a GitHub file",
		description: "Create or update a file in a GitHub repo. For an update, pass currentSha (from github_read_file).",
		parameters: {
			type: "object",
			properties: {
				repo: { type: "string", description: "owner/repo, e.g. 'yl-stack/yl-agent-runtime-test'" },
				path: { type: "string", description: "File path in the repo, e.g. 'src/main.ts'" },
				message: { type: "string", description: "Commit message" },
				content: { type: "string", description: "File contents" },
				branch: { type: "string", description: "Branch to commit to (default: default branch)" },
				currentSha: { type: "string", description: "Current file SHA, required to update an existing file" },
			},
			required: ["repo", "path", "message", "content"],
		},
		async execute(_toolCallId, params, _signal, _onUpdate, _ctx) {
			const payload = {
				message: params.message,
				content: Buffer.from(params.content, "utf8").toString("base64"),
			};
			if (params.branch) payload.branch = params.branch;
			if (params.currentSha) payload.sha = params.currentSha;
			const data = await gh("PUT", repoContentsUrl(params.repo, params.path), payload);
			const c = data.content;
			return text(
				`Wrote ${params.path} in ${params.repo} (commit ${c.sha})`,
				{ path: c.path, sha: c.sha, size: c.size, commit: c.sha },
			);
		},
	});

	/** Create a new GitHub repository. */
	pi.registerTool({
		name: "github_create_repo",
		label: "Create a GitHub repo",
		description: "Create a new repository on GitHub under your account.",
		parameters: {
			type: "object",
			properties: {
				name: { type: "string", description: "Repository name, e.g. 'my-app'" },
				description: { type: "string", description: "Repository description" },
				"private": { type: "boolean", description: "Private repo (default: true)" },
				autoInit: { type: "boolean", description: "Create an initial commit with a README (default: false)" },
			},
			required: ["name"],
		},
		async execute(_toolCallId, params, _signal, _onUpdate, _ctx) {
			const payload = { name: params.name };
			if (params.description !== undefined) payload.description = params.description;
			payload.private = params.private !== false;
			if (params.autoInit === true) payload.auto_init = true;
			const data = await gh("POST", "/user/repos", payload);
			return text(
				`Created ${data.full_name} (${data.defaultBranch})`,
				{ fullName: data.full_name, cloneUrl: data.clone_url, htmlUrl: data.html_url, defaultBranch: data.default_branch },
			);
		},
	});

	/** Create a GitHub issue. */
	pi.registerTool({
		name: "github_create_issue",
		label: "Create a GitHub issue",
		description: "Create an issue in a GitHub repository.",
		parameters: {
			type: "object",
			properties: {
				repo: { type: "string", description: "owner/repo, e.g. 'yl-stack/yl-agent-runtime-test'" },
				title: { type: "string", description: "Issue title" },
				body: { type: "string", description: "Issue body" },
				labels: { type: "array", items: { type: "string" }, description: "Label names to apply" },
			},
			required: ["repo", "title"],
		},
		async execute(_toolCallId, params, _signal, _onUpdate, _ctx) {
			const payload = { title: params.title };
			if (params.body) payload.body = params.body;
			if (params.labels) payload.labels = params.labels;
			const data = await gh("POST", repoUrl(params.repo, "issues"), payload);
			return text(
				`Created issue #${data.number} in ${params.repo}`,
				{ number: data.number, title: data.title, htmlUrl: data.html_url, state: data.state },
			);
		},
	});

	/** List issues in a GitHub repo. Returns empty list when there are none (not an error). */
	pi.registerTool({
		name: "github_list_issues",
		label: "List GitHub issues",
		description: "List issues in a GitHub repository (open by default; pass state 'closed' or 'all').",
		parameters: {
			type: "object",
			properties: {
				repo: { type: "string", description: "owner/repo, e.g. 'yl-stack/yl-agent-runtime-test'" },
				state: { type: "string", description: "open, closed or all (default: open)" },
			},
			required: ["repo"],
		},
		async execute(_toolCallId, params, _signal, _onUpdate, _ctx) {
			const state = params.state || "open";
			const data = await gh("GET", repoUrl(params.repo, `issues?state=${encodeURIComponent(state)}&per_page=50`));
			const list = data || [];
			const lines = list.map((i) => `#${i.number} ${i.title} — ${i.state}`).join("\n") || "No issues.";
			return text(lines, { count: list.length, state, repo: params.repo });
		},
	});

	/** Comment on a GitHub issue or pull request. */
	pi.registerTool({
		name: "github_create_comment",
		label: "Comment on a GitHub issue/PR",
		description: "Post a comment on a GitHub issue or pull request.",
		parameters: {
			type: "object",
			properties: {
				repo: { type: "string", description: "owner/repo, e.g. 'yl-stack/yl-agent-runtime-test'" },
				issueNumber: { type: "number", description: "Issue or pull request number" },
				body: { type: "string", description: "Comment body" },
			},
			required: ["repo", "issueNumber", "body"],
		},
		async execute(_toolCallId, params, _signal, _onUpdate, _ctx) {
			const data = await gh("POST", repoUrl(params.repo, `issues/${params.issueNumber}/comments`), { body: params.body });
			return text(
				`Commented on #${params.issueNumber} in ${params.repo}`,
				{ id: data.id, htmlUrl: data.html_url },
			);
		},
	});

	/** List pull requests in a GitHub repo. Returns empty list when there are none (not an error). */
	pi.registerTool({
		name: "github_list_prs",
		label: "List GitHub pull requests",
		description: "List pull requests in a GitHub repository (open by default).",
		parameters: {
			type: "object",
			properties: {
				repo: { type: "string", description: "owner/repo, e.g. 'yl-stack/yl-agent-runtime-test'" },
				state: { type: "string", description: "open, closed or all (default: open)" },
			},
			required: ["repo"],
		},
		async execute(_toolCallId, params, _signal, _onUpdate, _ctx) {
			const state = params.state || "open";
			const data = await gh("GET", repoUrl(params.repo, `pulls?state=${encodeURIComponent(state)}&per_page=50`));
			const list = data || [];
			const lines = list.map((p) => `!${p.number} ${p.title} — ${p.state}`).join("\n") || "No pull requests.";
			return text(lines, { count: list.length, state, repo: params.repo });
		},
	});

	/** Create a pull request. */
	pi.registerTool({
		name: "github_create_pr",
		label: "Create a GitHub pull request",
		description: "Open a pull request in a GitHub repository.",
		parameters: {
			type: "object",
			properties: {
				repo: { type: "string", description: "owner/repo, e.g. 'yl-stack/yl-agent-runtime-test'" },
				title: { type: "string", description: "Pull request title" },
				body: { type: "string", description: "Pull request body" },
				head: { type: "string", description: "Head branch, e.g. 'feature-branch'" },
				base: { type: "string", description: "Base branch, e.g. 'main'" },
			},
			required: ["repo", "title", "head", "base"],
		},
		async execute(_toolCallId, params, _signal, _onUpdate, _ctx) {
			const data = await gh("POST", repoUrl(params.repo, "pulls"), {
				title: params.title,
				body: params.body || "",
				head: params.head,
				base: params.base,
			});
			return text(
				`Created PR !${data.number} in ${params.repo}`,
				{ number: data.number, title: data.title, htmlUrl: data.html_url },
			);
		},
	});

	/** Trigger a GitHub Actions workflow_dispatch. */
	pi.registerTool({
		name: "github_run_workflow",
		label: "Run a GitHub Actions workflow",
		description: "Trigger a workflow_dispatch on a GitHub Actions workflow.",
		parameters: {
			type: "object",
			properties: {
				repo: { type: "string", description: "owner/repo, e.g. 'yl-stack/yl-agent-runtime-test'" },
				workflow: { type: "string", description: "Workflow file name or id, e.g. 'ci.yml'" },
				ref: { type: "string", description: "Branch or tag to run on (default: 'main')" },
				inputs: { type: "object", description: "Workflow input key/value pairs" },
			},
			required: ["repo", "workflow"],
		},
		async execute(_toolCallId, params, _signal, _onUpdate, _ctx) {
			const payload = { ref: params.ref || "main" };
			if (params.inputs) payload.inputs = params.inputs;
			await gh("POST", repoUrl(params.repo, `actions/workflows/${encodeURIComponent(params.workflow)}/dispatches`), payload);
			return text(`Triggered workflow ${params.workflow} in ${params.repo}`, { workflow: params.workflow, ref: payload.ref });
		},
	});
}
