# Windows' tests/pi/run.sh: tests that need a built Pi (Pi's own ahead-of-time compiled code under an extension), not just the
# engine: crashes, and extensions that failed to load; and what Windows needs of it besides (programs started as Pi starts
# them, workers, the DLLs it loads).
#
# Usage: tests\pi\run.ps1 [PI]     (default: out\pi-bolt\pi.exe)
# Each test runs several times: the crashes they guard against depended on when the collector ran. Needs Python 3 (`py`) for the
# image check, which uses bench's local fake model.
param([string]$Pi = '')
$ErrorActionPreference = 'Continue'
$here = $PSScriptRoot
$root = (Resolve-Path (Join-Path $here '..\..')).Path
if (-not $Pi) { $Pi = Join-Path $root 'out\pi-bolt\pi.exe' }
$Pi = (Resolve-Path -LiteralPath $Pi -ErrorAction SilentlyContinue).Path
if (-not $Pi) { Write-Host 'no Pi executable (scripts\build-pi.ps1)'; exit 1 }
$home_ = Join-Path ([IO.Path]::GetTempPath()) "pibolt-tests-pi-$([Guid]::NewGuid().ToString('N').Substring(0, 8))"
New-Item -ItemType Directory -Force -Path $home_ | Out-Null
# The extensions run from a folder of their own, as a user's do (~\.pi\agent\extensions): from this repository, a package with
# "type": "module" and node_modules, jiti would import them as they are rather than transform them.
$extensions = Join-Path $home_ 'extensions'
New-Item -ItemType Directory -Force -Path $extensions | Out-Null
Copy-Item -Path (Join-Path $here '*.js') -Destination $extensions
$script:status = 0

# Runs Pi with only what it needs in its environment (as run.sh's `env -i`), plus `$vars`.
function Invoke-Pi([string[]]$arguments, [hashtable]$vars = @{}, [string]$exe = $Pi, [string]$cwd = $here) {
	$keep = @{
		SystemRoot = $env:SystemRoot; windir = $env:windir; PATH = "$env:SystemRoot\System32;$env:SystemRoot"; PATHEXT = $env:PATHEXT
		TEMP = $env:TEMP; TMP = $env:TMP; USERPROFILE = $home_; HOME = $home_; APPDATA = (Join-Path $home_ 'AppData\Roaming')
		LOCALAPPDATA = (Join-Path $home_ 'AppData\Local'); PI_CODING_AGENT_DIR = (Join-Path $home_ 'agent'); DO_NOT_TRACK = '1'
		BUN_ENABLE_CRASH_REPORTING = '0'; ComSpec = $env:ComSpec
	}
	foreach ($k in $vars.Keys) { $keep[$k] = $vars[$k] }
	$info = [Diagnostics.ProcessStartInfo]::new($exe)
	$info.Arguments = ($arguments | ForEach-Object { if ($_ -match '[\s"]') { '"' + ($_ -replace '"', '\"') + '"' } else { $_ } }) -join ' '
	$info.UseShellExecute = $false; $info.RedirectStandardOutput = $true; $info.RedirectStandardError = $true; $info.RedirectStandardInput = $true
	$info.WorkingDirectory = $cwd
	$info.EnvironmentVariables.Clear()
	foreach ($k in $keep.Keys) { if ($null -ne $keep[$k]) { $info.EnvironmentVariables[$k] = [string]$keep[$k] } }
	$p = [Diagnostics.Process]::Start($info)
	$p.StandardInput.Close()
	$err = $p.StandardError.ReadToEndAsync()
	$out = $p.StandardOutput.ReadToEnd()
	if (-not $p.WaitForExit(300000)) { $p.Kill(); return @{ Code = -1; Text = "$out`n(timed out)" } }
	@{ Code = $p.ExitCode; Text = $out + $err.Result }
}

function Test-Extension([string]$name, [int]$runs, [hashtable]$vars = @{}) {
	$failed = 0; $last = ''
	for ($i = 0; $i -lt $runs; $i++) {
		$r = Invoke-Pi @('-ne', '-e', (Join-Path $extensions "$name.js"), '--offline', '--no-session', '-p', 'hi') $vars
		if ($r.Code -ne 0 -or $r.Text -notmatch "(?m)^${name}: " -or $r.Text -match 'Failed to load extension') { $failed++; $last = $r }
	}
	$with = if ($vars.Count) { ', ' + (($vars.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }) -join ' ') } else { '' }
	if (-not $failed) { Write-Host "PASS $name ($runs runs$with)" }
	else {
		Write-Host "FAIL ${name}: $failed of $runs runs$with (exit $('{0:x}' -f $last.Code))"
		($last.Text -split "`n" | Where-Object { $_ -match 'segmentation|panic|error|failed' } | Select-Object -First 3) | ForEach-Object { Write-Host "   $_" }
		$script:status = 1
	}
}

Test-Extension 'gc-end-stacks' 5
Test-Extension 'gc-end-stacks' 5 @{ BUN_JSC_collectContinuously = '1' }
# (The first run transforms the extension; the others take it from the cache, which is the agent's own.)
Test-Extension 'capture-stack' 3
if (Get-ChildItem (Join-Path $home_ 'agent\cache\jiti') -Filter '*capture-stack*' -ErrorAction SilentlyContinue) { Write-Host 'PASS the transformed extension is kept in the agent directory' }
else { Write-Host "FAIL the transformed extension is not in $home_\agent\cache\jiti"; $script:status = 1 }
Test-Extension 'builtin-modules' 2

# An extension package's own dependencies resolve as on Node, through their package.json ("exports" here, as sharp finds its
# native module), with import and with require.
$pkg = Join-Path $extensions 'package-deps'
New-Item -ItemType Directory -Force -Path (Join-Path $pkg 'node_modules\dep\lib') | Out-Null
[IO.File]::WriteAllText((Join-Path $pkg 'node_modules\dep\package.json'), '{"name": "dep", "exports": {".": {"require": "./lib/main.cjs"}}}')
[IO.File]::WriteAllText((Join-Path $pkg 'node_modules\dep\lib\main.cjs'), 'module.exports = { answer: 42 };')
[IO.File]::WriteAllText((Join-Path $pkg 'package-deps.ts'), @'
import { createRequire } from "node:module";
import { answer } from "dep";
const require = createRequire(import.meta.url);
export default function () {
	console.log(`package-deps: import=${answer} require=${require("dep").answer}`);
	process.exit(0);
}
'@)
$r = Invoke-Pi @('-ne', '-e', (Join-Path $pkg 'package-deps.ts'), '--offline', '--no-session', '-p', 'hi')
if ($r.Text -match '(?m)^package-deps: import=42 require=42') { Write-Host "PASS an extension's own packages resolve through their package.json" }
else {
	Write-Host "FAIL an extension's own packages resolve through their package.json"
	($r.Text -split "`n" | Where-Object { $_ -match '(?i)error|cannot' } | Select-Object -First 3) | ForEach-Object { Write-Host "   $_" }
	$script:status = 1
}
# Without the JIT, a package that works only through bun:ffi does not load, so that its extension takes its Node.js alternative
# (pi-fff: @ff-labs/fff-bun, then @ff-labs/fff-node); with the JIT it loads.
$ffi = Join-Path $extensions 'jit-only'
foreach ($b in 'bun', 'node') {
	$dir = Join-Path $ffi "node_modules\@ff-labs\fff-$b"
	New-Item -ItemType Directory -Force -Path $dir | Out-Null
	[IO.File]::WriteAllText((Join-Path $dir 'package.json'), "{`"name`": `"@ff-labs/fff-$b`", `"main`": `"index.js`"}")
	[IO.File]::WriteAllText((Join-Path $dir 'index.js'), "module.exports = { backend: `"$b`" };")
}
[IO.File]::WriteAllText((Join-Path $ffi 'jit-only.ts'), @'
async function backend(): Promise<string> {
	try {
		return (await import("@ff-labs/fff-bun")).backend;
	} catch {
		return (await import("@ff-labs/fff-node")).backend;
	}
}
export default async function () {
	console.log(`jit-only: ${await backend()}`);
	process.exit(0);
}
'@)
$want = if ((Invoke-Pi @('--version')).Text -match 'JIT on') { 'bun' } else { 'node' }
$r = Invoke-Pi @('-ne', '-e', (Join-Path $ffi 'jit-only.ts'), '--offline', '--no-session', '-p', 'hi')
if ($r.Text -match "(?m)^jit-only: $want") { Write-Host "PASS a package that needs bun:ffi: the $want backend" }
else { Write-Host "FAIL a package that needs bun:ffi: expected the $want backend"; $script:status = 1 }
Test-Extension 'child-processes-windows' 3
Test-Extension 'workers' 3

# The DLLs Pi delay-loads are Windows's (USERENV for the home folder, ...): from System32, not from the executable's folder. A copy
# of Pi next to a USERENV.dll that is no DLL at all must start and run as the original does.
$planted = Join-Path $home_ 'planted'
New-Item -ItemType Directory -Force -Path $planted | Out-Null
Copy-Item -LiteralPath $Pi -Destination (Join-Path $planted 'pi.exe')
foreach ($dll in 'USERENV.dll', 'dbghelp.dll', 'IPHLPAPI.dll', 'CRYPT32.dll', 'WSOCK32.dll', 'windowscodecs.dll') {
	[IO.File]::WriteAllText((Join-Path $planted $dll), 'not a DLL')
}
$r = Invoke-Pi @('-ne', '-e', (Join-Path $extensions 'builtin-modules.js'), '--offline', '--no-session', '-p', 'hi') @{} (Join-Path $planted 'pi.exe')
if ($r.Code -eq 0 -and $r.Text -match '(?m)^builtin-modules: ') { Write-Host 'PASS DLLs next to the executable are not loaded in the place of the system''s' }
else { Write-Host "FAIL with DLLs planted next to it, Pi did not run (exit $('{0:x}' -f $r.Code))"; $script:status = 1 }

# A program named without a path comes from PATH, never from the working directory (a repository's own git.cmd): Pi starts in
# a folder with a pibolt-planted.cmd that would write a marker. (This environment has no NoDefaultCurrentDirectoryInExePath,
# as a user's has none: Pi sets it itself.)
$project = Join-Path $home_ 'project'
New-Item -ItemType Directory -Force -Path $project | Out-Null
$marker = Join-Path $project 'PLANTED-RAN.txt'
[IO.File]::WriteAllText((Join-Path $project 'pibolt-planted.cmd'), "@echo ran> `"$marker`"`r`n")
$r = Invoke-Pi @('-ne', '-e', (Join-Path $extensions 'workdir-programs.js'), '--offline', '--no-session', '-p', 'hi') @{} $Pi $project
if ($r.Code -eq 0 -and $r.Text -match '(?m)^workdir-programs: planted=ENOENT whoami=ok' -and -not (Test-Path -LiteralPath $marker)) {
	Write-Host 'PASS a program named without a path is not taken from the working directory'
} else {
	Write-Host "FAIL a program in the working directory ran, or one on PATH did not (exit $('{0:x}' -f $r.Code), marker $(Test-Path -LiteralPath $marker))"
	($r.Text -split "`n" | Where-Object { $_ -match 'workdir-programs' } | Select-Object -First 2) | ForEach-Object { Write-Host "   $_" }
	$script:status = 1
}

# A repository's .pi/settings.json is not used before the project is trusted: its sessionDir (a \\host\share, say, which Windows
# would connect to at once) is neither created nor written to.
$repo = Join-Path $home_ 'untrusted-repo'
$elsewhere = Join-Path $home_ 'sessions-elsewhere'
New-Item -ItemType Directory -Force -Path (Join-Path $repo '.pi') | Out-Null
[IO.File]::WriteAllText((Join-Path $repo '.pi\settings.json'), (@{ sessionDir = $elsewhere } | ConvertTo-Json))
$r = Invoke-Pi @('--model', 'none/none', '-p', 'hi') @{} $Pi $repo
if (-not (Test-Path -LiteralPath $elsewhere)) { Write-Host 'PASS an untrusted project''s sessionDir is not used' }
else { Write-Host "FAIL an untrusted project's sessionDir was created ($elsewhere)"; $script:status = 1 }
# ...and is, when the user trusts every project (defaultProjectTrust "always"), as the runtime then does.
$alwaysAgent = Join-Path $home_ 'agent-always'
New-Item -ItemType Directory -Force -Path $alwaysAgent | Out-Null
[IO.File]::WriteAllText((Join-Path $alwaysAgent 'settings.json'), (@{ defaultProjectTrust = 'always' } | ConvertTo-Json))
$r = Invoke-Pi @('--model', 'none/none', '-p', 'hi') @{ PI_CODING_AGENT_DIR = $alwaysAgent } $Pi $repo
if (Test-Path -LiteralPath $elsewhere) { Write-Host 'PASS a project''s sessionDir is used when every project is trusted' }
else { Write-Host "FAIL with defaultProjectTrust always, the project's sessionDir was not used ($elsewhere)"; $script:status = 1 }

# Bedrock, the proxy agents and the OAuth flows are loaded when they are first used, with the builtin modules they import. A request
# that can reach nothing has to fail as a connection that is refused, not as code that is missing or broken.
function Test-Bedrock([string]$name, [hashtable]$vars) {
	$all = @{ PI_OFFLINE = '1'; AWS_ACCESS_KEY_ID = 'test'; AWS_SECRET_ACCESS_KEY = 'test'; AWS_REGION = 'us-east-1' }
	foreach ($k in $vars.Keys) { $all[$k] = $vars[$k] }
	$r = Invoke-Pi @('-ne', '--no-session', '--provider', 'amazon-bedrock', '--model', 'amazon.nova-micro-v1:0', '-p', 'hi') $all
	if ($r.Text -match 'ECONNREFUSED|ConnectionRefused|onnection refused' -and $r.Text -notmatch '(?i)is not defined|cannot find module|is not a function|is not a constructor|panic') { Write-Host "PASS $name" }
	else { Write-Host "FAIL $name"; ($r.Text.Trim() -split "`n" | Select-Object -Last 3) | ForEach-Object { Write-Host "   $($_.Substring(0, [Math]::Min(200, $_.Length)))" }; $script:status = 1 }
}
Test-Bedrock 'Bedrock loads (the request is refused)' @{ AWS_ENDPOINT_URL_BEDROCK_RUNTIME = 'http://127.0.0.1:1' }
Test-Bedrock 'Bedrock loads behind a proxy (the proxy refuses)' @{ AWS_ENDPOINT_URL_BEDROCK_RUNTIME = 'https://127.0.0.1:1'; HTTPS_PROXY = 'http://127.0.0.1:1' }

# Pi's own worker: an image it is given.
& py -3 (Join-Path $here 'image.py') $Pi
if ($LASTEXITCODE -ne 0) { $script:status = 1 }

# OpenSec's extensions, if this executable has them compiled in (its pi-bolt.txt says so): there, working, switchable, replaceable.
$about = Join-Path (Split-Path $Pi) 'pi-bolt.txt'
if ((Test-Path -LiteralPath $about) -and (Select-String -LiteralPath $about -Pattern '^plugins: .*opensec-pi-todo' -Quiet)) {
	& py -3 (Join-Path $here 'compiled-plugins.py') $Pi
	if ($LASTEXITCODE -ne 0) { $script:status = 1 }
}

Remove-Item -LiteralPath $home_ -Recurse -Force -ErrorAction SilentlyContinue
exit $script:status
