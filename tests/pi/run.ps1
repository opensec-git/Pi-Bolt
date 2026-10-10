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
function Invoke-Pi([string[]]$arguments, [hashtable]$vars = @{}, [string]$exe = $Pi) {
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
	$info.WorkingDirectory = $here
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
