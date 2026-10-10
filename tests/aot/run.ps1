# Correctness tests for the ahead-of-time engine: Windows' tests/aot/run.sh. Each program is compiled ahead of time three times
# (JIT on, JIT off: the two kinds of Pi build; and JIT off with every operation compiled the compact way, as outside loops and in
# the generic copies of loops) and must print exactly what a stock Bun prints running its source.
#
# Usage: tests\aot\run.ps1 [test.mjs...]      (default: every test)
# Environment: PIBOLT_BUN (the Pi-Bolt runtime; default .work\runtime\bun.exe), PIBOLT_STABLE_BUN (the reference; default `bun`),
#              AOT_BUILD_ENV (extra variables for the compile step, e.g. "BUN_JSC_useAOTLoopSplitting=0")
param([Parameter(ValueFromRemainingArguments)][string[]]$Tests)
$ErrorActionPreference = 'Continue'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$Work = if ($env:PIBOLT_WORK) { $env:PIBOLT_WORK } else { Join-Path $Root '.work' }
$Bun = if ($env:PIBOLT_BUN) { $env:PIBOLT_BUN } else { Join-Path $Work 'runtime\bun.exe' }
$Stable = if ($env:PIBOLT_STABLE_BUN) { $env:PIBOLT_STABLE_BUN } else { 'bun' }
if (-not (Test-Path $Bun)) { Write-Host "error: no Pi-Bolt runtime at $Bun" -ForegroundColor Red; exit 1 }
$Out = Join-Path $Work 'tests\aot'
New-Item -ItemType Directory -Force $Out | Out-Null
Set-Location $PSScriptRoot
if (-not $Tests) {
	$Tests = 'liveness.mjs', 'mapset.mjs', 'realms.mjs', 'workers.mjs', 'spread-loops.mjs', 'number-encoding.mjs', 'helper-calls.mjs', 'callbacks.mjs', 'methods.mjs', 'dictionaries.mjs', 'variables.mjs', 'polymorphic.mjs', 'strings.mjs', 'unicode-regexps.mjs', 'builtins.mjs', 'declined.mjs', 'deferred-builtins.mjs', 'wide-constants.mjs', 'intl-locales.mjs'
}
$Target = '--target=bun-windows-x64'

# Runs a command with these variables set (and the BUN_*/AOT ones of the caller's environment cleared), returning its exit code;
# its output goes to the files given.
function Invoke-With([hashtable]$vars, [string]$exe, [string[]]$arguments, [string]$stdout, [string]$stderr) {
	$saved = @{}
	$all = @{}
	foreach ($pair in ($env:AOT_BUILD_ENV -split '\s+' | Where-Object { $_ })) { $k, $v = $pair -split '=', 2; $all[$k] = $v }
	foreach ($k in $vars.Keys) { $all[$k] = $vars[$k] }
	foreach ($k in $all.Keys) { $saved[$k] = [Environment]::GetEnvironmentVariable($k); [Environment]::SetEnvironmentVariable($k, $all[$k]) }
	try {
		$startArgs = @{ FilePath = $exe; NoNewWindow = $true; Wait = $true; PassThru = $true; RedirectStandardOutput = $stdout; RedirectStandardError = $stderr }
		if ($arguments) { $startArgs.ArgumentList = $arguments }
		# (An executable that was just written may not start at once: the antivirus is still reading it. Once more, a moment later.)
		try { $p = Start-Process @startArgs } catch { Start-Sleep -Seconds 2; $p = Start-Process @startArgs }
		return $p.ExitCode
	} finally {
		foreach ($k in $saved.Keys) { [Environment]::SetEnvironmentVariable($k, $saved[$k]) }
	}
}
# Windows keeps an executable's image section after its process exits, and charges the system's commit for its writable and
# uninitialized pages while it does (docs/WINDOWS.md): opening the file for writing lets go of it (nothing is written). So that
# a run of every test does not hold on to one per executable.
function Clear-CachedImage([string]$path) { try { [IO.File]::Open($path, 'Open', 'ReadWrite', 'ReadWrite').Close() } catch { } }
function Read-Text([string]$path) { if (Test-Path -LiteralPath $path) { ([IO.File]::ReadAllText($path) -replace "`r`n", "`n").TrimEnd("`n") } else { '' } }

$common = @{ BUN_JSC_useAOTLoopSplitting = '1'; BUN_JSC_aotLoopSplittingPolicy = '5'; BUN_JSC_useImmutableIntrinsics = '1'; BUN_JSC_useJIT = '0'; BUN_STATIC_HEAP = '1'; BUN_AOT = '1'; BUN_JSC_omitBytecodeFromStaticHeap = '1' }
$status = 0
foreach ($t in $Tests) {
	$name = [IO.Path]::GetFileNameWithoutExtension($t)
	$extra = @(); $own = @{}
	if ($name -eq 'realms') { $extra = @('realms-mod.mjs') }
	& $Stable $t *> "$Out\$name.expected"
	# A plain bytecode build, run once, records the order functions are first called in: the AOT build lays code out by it.
	Invoke-With @{} $Bun (@('build', '--compile', '--bytecode', '--format=esm', $Target, $t) + $extra + @('--outfile', "$Out\$name-bytecode.exe")) "$Out\$name.build.out" "$Out\$name.build.err" | Out-Null
	Remove-Item "$Out\$name.order" -ErrorAction SilentlyContinue
	Invoke-With @{ BUN_BYTECODE_ORDER_OUT = "$Out\$name.order" } "$Out\$name-bytecode.exe" @() "$Out\$name.order.out" "$Out\$name.order.err" | Out-Null
	Clear-CachedImage "$Out\$name-bytecode.exe"
	if ($name -eq 'declined') {
		# Every function named `declined` is declined, as the compiler declines one it cannot compile. The build stops and says which
		# and why; told to go on, it builds a program whose compiled code calls them like any value.
		$own = @{ BUN_JSC_aotDeclineFunctionsNamed = 'declined'; BUN_JSC_allowAOTDeclinedFunctions = '1' }
		$vars = $common.Clone(); $vars.BUN_JSC_aotDeclineFunctionsNamed = 'declined'; $vars.BUN_AOT_JIT = '0'; $vars.BUN_ENABLE_CRASH_REPORTING = '0'
		$code = Invoke-With $vars $Bun @('build', '--compile', '--bytecode', '--format=esm', $Target, "--bytecode-order=$Out\$name.order", $t, '--outfile', "$Out\$name-stopped.exe") "$Out\$name.stopped.out" "$Out\$name.stopped.err"
		$stopped = (Read-Text "$Out\$name.stopped.out") + "`n" + (Read-Text "$Out\$name.stopped.err")
		if ($code -eq 1 -and $stopped -match 'the function .declined. .*cannot be compiled ahead of time' -and $stopped -notmatch '(?i)panic|crashed') {
			Write-Host "PASS $name (the build stops)"
		} else {
			Write-Host "FAIL $name (the build stops): exit $code"
			($stopped -split "`n" | Select-Object -Last 3) | ForEach-Object { $_.Substring(0, [Math]::Min(200, $_.Length)) }
			$status = 1
		}
	}
	foreach ($mode in 'jit-on', 'jit-off', 'compact') {
		$vars = $common.Clone()
		foreach ($k in $own.Keys) { $vars[$k] = $own[$k] }
		if ($mode -ne 'jit-on') { $vars.BUN_AOT_JIT = '0' }
		if ($mode -eq 'compact') { $vars.BUN_JSC_useAOTInlineFastPathsInLoops = '0' }
		Remove-Item "$Out\$name-$mode.exe", "$Out\$name.$mode", "$Out\$name.$mode.err" -ErrorAction SilentlyContinue
		Invoke-With $vars $Bun (@('build', '--compile', '--bytecode', '--format=esm', $Target, "--bytecode-order=$Out\$name.order", $t) + $extra + @('--outfile', "$Out\$name-$mode.exe")) "$Out\$name-$mode.build.out" "$Out\$name-$mode.build.err" | Out-Null
		if (-not (Test-Path "$Out\$name-$mode.exe")) {
			Write-Host "FAIL $name ($mode): the build made no executable"
			(Read-Text "$Out\$name-$mode.build.err") -split "`n" | Select-Object -Last 3 | ForEach-Object { "  $_" }
			$status = 1
			continue
		}
		$code = Invoke-With @{ BUN_STATIC_HEAP_VERBOSE = '1' } "$Out\$name-$mode.exe" @() "$Out\$name.$mode" "$Out\$name.$mode.err"
		Clear-CachedImage "$Out\$name-$mode.exe"
		$used = @(Select-String -Path "$Out\$name.$mode.err" -Pattern 'image registered: true' -SimpleMatch -ErrorAction SilentlyContinue).Count
		$expected = Read-Text "$Out\$name.expected"
		$actual = Read-Text "$Out\$name.$mode"
		# The realms test also prints memory figures, which differ by design: compare the first column only.
		if ($name -eq 'realms') {
			$expected = ($expected -split "`n" | ForEach-Object { ($_ -split ' ')[0] }) -join "`n"
			$actual = ($actual -split "`n" | ForEach-Object { ($_ -split ' ')[0] }) -join "`n"
		}
		if ($used -eq 1 -and $expected -ceq $actual) {
			Write-Host "PASS $name ($mode)"
		} else {
			Write-Host ("FAIL $name ($mode): exit 0x{0:X8}, compiled code used: {1}" -f ($code -band 0xffffffffL), $(if ($used -eq 1) { 'yes' } else { 'no' }))
			Compare-Object ($expected -split "`n") ($actual -split "`n") | Select-Object -First 5 | ForEach-Object { "  $($_.SideIndicator) $($_.InputObject)" }
			$status = 1
		}
	}
}
exit $status
