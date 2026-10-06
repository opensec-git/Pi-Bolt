# Runs the full benchmark suite on Windows into one results folder, then report.py into summary.md: run-suite.sh's counterpart.
#
# Usage (from anywhere, in Windows PowerShell 5.1 or PowerShell 7):
#   powershell -ExecutionPolicy Bypass -File bench\run-suite.ps1 -Out bench\results\<date>-windows [-PiBolt ...] [-Bun ...]
#
# The four builds compared, by default:
#   pi-bolt  out\pi-bolt-aot-lto\pi.exe     Pi-Bolt, compiled ahead of time, LTO + Control Flow Guard (scripts\build-pi.ps1)
#   bun      out\pi-stable-upstream\pi.exe  Pi as released on stock Bun 1.4.2 (scripts\build-pi.ps1 -Stable -Pi <Pi checkout>)
#   node     C:\pb\tools\node22\node.exe    Node 22, running Pi's npm bundle (-NodeCli)
#   node24   node (on PATH)                 Node 24, the same bundle
# What runs, in this order (every tool starts its own fake model on 127.0.0.1; no model provider is called):
#   benchmark.py      startup, headless, interactive: -Runs runs after -Warmup warm-up rounds, interleaved; with the process
#                     floor in every round (--floor): bench\floor\floor.c, built with clang-cl into %TEMP%\pibolt-bench-floor
#                     (-Floor: an executable built already; -NoFloor: none)
#   long_session.py   -LongSessions sessions of -LongPrompts prompts per build
#   conpty_check.py   tmux_check.py's streaming check in a ConPTY (there is no tmux on Windows): -Rounds rounds
#   pauses.py         frame times and stalls (-PauseSteps), then garbage collection pauses (--gc, -GcSteps)
#   long_answer.py, large_write.py   -LongAnswerRuns runs each (0 skips them)
#   plugin_bench.py   the example plugin loaded at run time, and compiled in, as run-suite.sh has them: each of these builds that
#                     is there (or that its parameter names) is measured:
#                       -PiBoltPlugins     out\pi-bolt-plugins\pi.exe       --compiled pi-bolt
#                       -PiBoltPluginsJit  out\pi-bolt-plugins-jit\pi.exe   --compiled pi-bolt-jit
#                       -PiBoltJit         out\pi-bolt-aot-lto-jit\pi.exe   --runtime pi-bolt-jit
#                     (scripts\build-pi.ps1 -Plugins examples\plugins\plugins.ts [-Jit on] -Out ...; -Jit on for the last)
#   report.py         summary.md, and the charts in -Images (default: <Out>\images; docs\images is left alone)
# Keep the machine otherwise idle, on AC power, for the whole run: Windows cannot pin a process to cores the way taskset does.
[CmdletBinding()]
param(
	[Parameter(Mandatory = $true)][string]$Out,
	[string]$PiBolt = 'out\pi-bolt-aot-lto\pi.exe',
	[string]$Bun = 'out\pi-stable-upstream\pi.exe',
	[string]$Node22 = 'C:\pb\tools\node22\node.exe',
	[string]$Node24 = 'node',
	[string]$NodeCli = 'C:\pb\Pi-Bolt\.work\node-pi\node_modules\@earendil-works\pi-coding-agent\dist\bundle\cli.js',
	[string]$PiBoltPlugins = '',
	[string]$PiBoltPluginsJit = '',
	[string]$PiBoltJit = '',
	[string]$PiBoltLabel = 'Pi-Bolt (LTO+CFG AOT)',
	[int]$Runs = 11,
	[int]$Warmup = 2,
	[int]$LongSessions = 4,
	[int]$LongPrompts = 75,
	[int]$LongEvery = 25,
	[int]$Rounds = 5,
	[int]$StreamPrompts = 4,
	[string]$PauseSteps = 'md:20000,write:50,write:200',
	[string]$GcSteps = 'md:20000,write:50',
	[int]$LongAnswerRuns = 1,
	[string]$LongAnswerSizes = '5000,20000,60000',
	[string]$LargeWriteSizes = '50,200',
	[int]$PluginRuns = 5,
	[int]$PluginCommands = 5,
	[string]$Images = '',
	[string]$Floor = '',
	[switch]$NoFloor
)
$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not (Get-Command python -ErrorAction SilentlyContinue) -and (Test-Path 'C:\pb\env.ps1')) { . 'C:\pb\env.ps1' }
function Die($message) { Write-Host "error: $message" -ForegroundColor Red; exit 1 }
function Log($message) { Write-Host "==> $message" -ForegroundColor Cyan }

# Absolute paths: the tools change directory for every run. A relative path is taken from where this was started, else from the
# repository (the defaults). A command line is split on spaces (harness.parse_builds): a path with a space cannot be in a build.
function Resolve-File($path, $what) {
	foreach ($candidate in @($path, (Join-Path $Root $path))) {
		if ([System.IO.Path]::IsPathRooted($path) -and $candidate -ne $path) { continue }
		if (Test-Path -LiteralPath $candidate -PathType Leaf) { $full = (Resolve-Path -LiteralPath $candidate).Path; break }
	}
	if (-not $full) {
		$cmd = Get-Command $path -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
		if (-not $cmd) { Die "$what not found: $path" }
		$full = $cmd.Source
	}
	if ($full -match ' ') { Die "$what is in a folder with a space in its path, which a --build command line cannot hold: $full" }
	return $full
}
$PiBolt = Resolve-File $PiBolt 'Pi-Bolt (-PiBolt)'
$Bun = Resolve-File $Bun 'the stock-Bun build (-Bun)'
$Node22 = Resolve-File $Node22 'Node 22 (-Node22)'
$Node24 = Resolve-File $Node24 'Node 24 (-Node24)'
$NodeCli = Resolve-File $NodeCli "Pi's npm bundle (-NodeCli)"
# The plugin_bench.py builds: one named is required; by default, each is measured if it is there.
function Resolve-Optional($path, $default, $what) {
	if ($path) { return Resolve-File $path $what }
	if (Test-Path -LiteralPath (Join-Path $Root $default) -PathType Leaf) { return Resolve-File (Join-Path $Root $default) $what }
	return ''
}
$PiBoltPlugins = Resolve-Optional $PiBoltPlugins 'out\pi-bolt-plugins\pi.exe' 'the plugin build (-PiBoltPlugins)'
$PiBoltPluginsJit = Resolve-Optional $PiBoltPluginsJit 'out\pi-bolt-plugins-jit\pi.exe' 'the JIT-on plugin build (-PiBoltPluginsJit)'
$PiBoltJit = Resolve-Optional $PiBoltJit 'out\pi-bolt-aot-lto-jit\pi.exe' 'the JIT-on build (-PiBoltJit)'
# The process floor: a minimal native program, linked as the real executable is (static CRT, ASLR with high entropy, DEP, Control
# Flow Guard), built here from bench\floor\floor.c into a scratch folder (no binary in the repository).
$floorBuilt = ''
if ($NoFloor) { $Floor = '' }
elseif ($Floor) { $Floor = Resolve-File $Floor 'the process floor (-Floor)' }
else {
	if (-not (Get-Command clang-cl -ErrorAction SilentlyContinue) -and (Test-Path 'C:\pb\env.ps1')) { . 'C:\pb\env.ps1' }
	if (-not (Get-Command clang-cl -ErrorAction SilentlyContinue)) { Die 'clang-cl not found, to build the process floor (pass -Floor with one built already, or -NoFloor)' }
	$floorDir = Join-Path $env:TEMP 'pibolt-bench-floor'
	New-Item -ItemType Directory -Force $floorDir | Out-Null
	$Floor = Join-Path $floorDir 'floor.exe'
	$ErrorActionPreference = 'Continue'
	& clang-cl /nologo /O2 /MT /guard:cf "/Fo$floorDir\" (Join-Path $PSScriptRoot 'floor\floor.c') "/Fe$Floor" /link /DYNAMICBASE /HIGHENTROPYVA /NXCOMPAT /guard:cf | Out-Host
	$ErrorActionPreference = 'Stop'
	if ($LASTEXITCODE -ne 0) { Die "the process floor did not build (clang-cl exit $LASTEXITCODE)" }
	$clangVersion = if (((& clang-cl --version) | Select-Object -First 1) -match 'version (\S+)') { $Matches[1] } else { '?' }
	$floorBuilt = " built with clang-cl $clangVersion /O2 /MT /guard:cf, linked /DYNAMICBASE /HIGHENTROPYVA /NXCOMPAT /guard:cf"
}
if (-not [System.IO.Path]::IsPathRooted($Out)) { $Out = Join-Path (Get-Location).Path $Out }
New-Item -ItemType Directory -Force $Out | Out-Null
$Out = (Resolve-Path $Out).Path
if ($Images -and -not [System.IO.Path]::IsPathRooted($Images)) { $Images = Join-Path (Get-Location).Path $Images }
Set-Location $Root
if (-not $Images) { $Images = Join-Path $Out 'images' }
# UTF-8 between Python and PowerShell (report.py writes "x" signs and dashes), and for every file a tool reads.
$env:PYTHONUTF8 = '1'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
function Save-Lines($path, $lines) { [System.IO.File]::WriteAllLines($path, [string[]]@($lines)) }
# Python's progress goes to stderr, which Windows PowerShell turns into errors when it is redirected: it is left on the console.
function Run-Python {
	$ErrorActionPreference = 'Continue'
	& python @args
	if ($LASTEXITCODE -ne 0) { Die "python $($args[0]) failed (exit $LASTEXITCODE)" }
}

# --- What the results were taken with.
Log "environment -> $Out\environment.txt"
$cpu = Get-CimInstance Win32_Processor | Select-Object -First 1
$ram = [math]::Round((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1GB, 1)
$os = Get-CimInstance Win32_OperatingSystem
$nt = Get-ItemProperty 'HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion'
$defender = try {
	$mp = Get-MpComputerStatus
	"real-time protection $(if ($mp.RealTimeProtectionEnabled) { 'on' } else { 'off' }), antivirus $(if ($mp.AntivirusEnabled) { 'on' } else { 'off' }), tamper protection $(if ($mp.IsTamperProtected) { 'on' } else { 'off' }), mode $($mp.AMRunningMode), engine $($mp.AMEngineVersion)"
} catch { "unknown ($($_.Exception.Message))" }
Add-Type -AssemblyName System.Windows.Forms
$line = [System.Windows.Forms.SystemInformation]::PowerStatus.PowerLineStatus
$source = if ($line -eq 'Online') { 'AC' } elseif ($line -eq 'Offline') { 'battery' } else { "$line" }
$scheme = ((powercfg /getactivescheme) -join ' ') -replace '^.*?:\s*', '' -replace '\s+', ' '
$overlays = @{ 'ded574b5-45a0-4f42-8737-46345c09c238' = 'best performance'; '961cc777-2547-4f9d-8174-7d86181b8a7a' = 'best power efficiency'; '00000000-0000-0000-0000-000000000000' = 'balanced' }
$mode = try {
	$p = Get-ItemProperty 'HKLM:\SYSTEM\CurrentControlSet\Control\Power\User\PowerSchemes'
	$guid = if ($source -eq 'battery') { $p.ActiveOverlayDcPowerScheme } else { $p.ActiveOverlayAcPowerScheme }
	if ($overlays.ContainsKey("$guid")) { $overlays["$guid"] } else { "$guid" }
} catch { 'unknown' }
if ($source -ne 'AC') { Write-Host "warning: running on $source power: the figures will not compare with a run on AC" -ForegroundColor Yellow }
$piBoltVersion = (& $PiBolt --version | Select-Object -First 1)
$env:BUN_BE_BUN = '1' # (a Bun executable says which Bun it is built on)
$piBoltBun = (& $PiBolt --version | Select-Object -First 1)
$stockBun = (& $Bun --version | Select-Object -First 1)
Remove-Item Env:BUN_BE_BUN
$stockPi = (& $Bun --version | Select-Object -First 1)
$node22Version = (& $Node22 --version)
$node24Version = (& $Node24 --version)
$cliVersion = (Get-Content (Join-Path (Split-Path (Split-Path (Split-Path $NodeCli))) 'package.json') -Raw | ConvertFrom-Json).version
$commit = (& git -C $Root rev-parse --short HEAD 2>$null)
$pythonVersion = (& python --version)
Save-Lines (Join-Path $Out 'environment.txt') @(
	"date: $((Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mmZ'))",
	"cpu: $($cpu.Name.Trim()), $($cpu.NumberOfCores) cores, $($cpu.NumberOfLogicalProcessors) threads, not pinned; RAM $ram GB",
	"system: $($os.Caption) $($nt.DisplayVersion), build $($nt.CurrentBuild).$($nt.UBR)",
	"defender: $defender",
	"power: $source, scheme $scheme, power mode $mode",
	"pi-bolt: $piBoltVersion, Pi-Bolt $((Get-Content (Join-Path $Root 'VERSION') -Raw).Trim()) from $commit, runtime Bun $piBoltBun (Pi-Bolt's), $PiBolt",
	"bun: Pi $stockPi on stock Bun $stockBun, $Bun",
	"node: $node22Version, Pi $cliVersion's npm bundle, $Node22",
	"node24: $node24Version, Pi $cliVersion's npm bundle, $Node24",
	"python: $pythonVersion",
	"model: bench/fake_model*.py on 127.0.0.1 (no provider is called)"
)
if ($Floor) { [System.IO.File]::AppendAllLines((Join-Path $Out 'environment.txt'), [string[]]@("floor: bench/floor/floor.c$floorBuilt, $Floor")) }
foreach ($extra in @(@('pi-bolt-plugins', $PiBoltPlugins), @('pi-bolt-plugins-jit', $PiBoltPluginsJit), @('pi-bolt-jit', $PiBoltJit))) {
	if ($extra[1]) { [System.IO.File]::AppendAllLines((Join-Path $Out 'environment.txt'), [string[]]@("$($extra[0]): $(& $extra[1] --version | Select-Object -First 1), $($extra[1])")) }
}
Get-Content (Join-Path $Out 'environment.txt')

$builds = @('--build', "pi-bolt=$PiBolt", '--build', "bun=$Bun", '--build', "node=$Node22 $NodeCli", '--build', "node24=$Node24 $NodeCli")

$floorArgs = if ($Floor) { @('--floor', $Floor) } else { @() }
Log "benchmark.py: startup, headless, interactive$(if ($Floor) { ', and the process floor' })"
Run-Python bench\benchmark.py --runs $Runs --warmup $Warmup --out "$Out\benchmark.jsonl" @builds @floorArgs --baseline bun |
	Tee-Object -Variable lines | Out-Host
Save-Lines "$Out\benchmark.txt" $lines

for ($i = 1; $i -le $LongSessions; $i++) {
	Log "long_session.py: session $i of $LongSessions"
	Run-Python bench\long_session.py --prompts $LongPrompts --every $LongEvery --out "$Out\long.jsonl" @builds | Out-Host
}

Log 'conpty_check.py: replies streaming at human pace, in a ConPTY'
Run-Python bench\conpty_check.py --prompts $StreamPrompts --rounds $Rounds --out "$Out\conpty.jsonl" @builds |
	Tee-Object -Variable lines | Out-Host
Save-Lines "$Out\conpty.txt" $lines

Log 'pauses.py: frame times and stalls'
Run-Python bench\pauses.py --steps $PauseSteps --out "$Out\pauses.jsonl" @builds | Tee-Object -Variable lines | Out-Host
Save-Lines "$Out\pauses-1.txt" $lines
Log 'pauses.py --gc: garbage collection pauses'
Run-Python bench\pauses.py --gc --steps $GcSteps --out "$Out\pauses.jsonl" @builds | Tee-Object -Variable lines | Out-Host
Save-Lines "$Out\pauses-gc-1.txt" $lines

for ($i = 1; $i -le $LongAnswerRuns; $i++) {
	Log "long_answer.py and large_write.py: run $i of $LongAnswerRuns"
	Run-Python bench\long_answer.py --sizes $LongAnswerSizes @builds | Tee-Object -Variable lines | Out-Host
	Save-Lines "$Out\long_answer-$i.txt" $lines
	Run-Python bench\large_write.py --sizes $LargeWriteSizes @builds | Tee-Object -Variable lines | Out-Host
	Save-Lines "$Out\large_write-$i.txt" $lines
}

Log 'plugin_bench.py'
# (The order of run-suite.sh's, which is the order of the table and chart.)
$plugins = @()
if ($PiBoltPlugins) { $plugins += @('--compiled', "pi-bolt=$PiBoltPlugins") }
else { Write-Host 'note: no plugin build (out\pi-bolt-plugins\pi.exe, or -PiBoltPlugins): not measured compiled in' -ForegroundColor Yellow }
if ($PiBoltPluginsJit) { $plugins += @('--compiled', "pi-bolt-jit=$PiBoltPluginsJit") }
else { Write-Host 'note: no JIT-on plugin build (out\pi-bolt-plugins-jit\pi.exe, or -PiBoltPluginsJit): not measured compiled in with the JIT on' -ForegroundColor Yellow }
$plugins += @('--runtime', "pi-bolt=$PiBolt")
if ($PiBoltJit) { $plugins += @('--runtime', "pi-bolt-jit=$PiBoltJit") }
else { Write-Host 'note: no JIT-on build (out\pi-bolt-aot-lto-jit\pi.exe, or -PiBoltJit): not measured loaded at run time with the JIT on' -ForegroundColor Yellow }
$plugins += @('--runtime', "bun=$Bun", '--none', "pi-bolt=$PiBolt", '--none', "bun=$Bun")
Run-Python bench\plugin_bench.py --runs $PluginRuns --commands $PluginCommands --out "$Out\plugins.jsonl" @plugins | Out-Host

Log "report.py -> $Out\summary.md"
$bunLabel = "Pi $stockPi on stock Bun $stockBun"
$node22Label = "Node $(($node22Version -replace '^v', '').Split('.')[0])"
$node24Label = "Node $(($node24Version -replace '^v', '').Split('.')[0])"
Run-Python bench\report.py $Out --images $Images --builds 'pi-bolt,bun,node,node24' --label "pi-bolt=$PiBoltLabel" `
	--label "bun=$bunLabel" --label "node=$node22Label" --label "node24=$node24Label" | Tee-Object -Variable lines | Out-Host
Save-Lines "$Out\summary.md" $lines
Log "done: $Out\summary.md"
