# Windows' tests/runtime/run.sh: tests of the Pi-Bolt runtime itself (the patched Bun), for what Pi and its extensions rely on:
#   stack       Error.captureStackTrace() and the default Error.prepareStackTrace on objects that are not Errors
#   keepalive   a connection waits in fetch's keep-alive pool for 4 seconds, or for as long as the server's Keep-Alive header
#               says less 2 seconds, and one that has waited longer is not used again: a connection that went dead while it
#               waited (no FIN, no RST) is not what the next request is written to
#   workdir     a compiled executable's embedded code resolves nothing in the directory it is started in, where a repository
#               could supply a package or a native module for it to run; absolute paths and built-in modules still resolve
# Usage: tests\runtime\run.ps1        Environment: PIBOLT_BUN (the runtime; default .work\runtime\bun.exe). Needs Python 3 (`py`).
$ErrorActionPreference = 'Continue'
$here = $PSScriptRoot
$root = (Resolve-Path (Join-Path $here '..\..')).Path
$bun = if ($env:PIBOLT_BUN) { $env:PIBOLT_BUN } else { Join-Path $root '.work\runtime\bun.exe' }
if (-not (Test-Path -LiteralPath $bun)) { Write-Host "no runtime at $bun (scripts\build-runtime.ps1)"; exit 1 }
Push-Location $here
$script:status = 0
function Test-Same([string]$name, [string]$expected, [string]$actual) {
	if ($expected -eq $actual) { Write-Host "PASS $name" } else { Write-Host "FAIL ${name}: expected '$expected', got '$actual'"; $script:status = 1 }
}
$servers = @()
try {
	$expected = ((Get-Content -Raw (Join-Path $here 'stack.expected')) -replace "`r", '').TrimEnd("`n")
	$actual = ((& $bun stack.mjs 2>&1 | Out-String) -replace "`r", '').TrimEnd("`n")
	Test-Same 'stack' $expected $actual

	$workdir = Join-Path ([IO.Path]::GetTempPath()) "pibolt-workdir-$([Guid]::NewGuid().ToString('N').Substring(0, 8))"
	$repo = Join-Path $workdir 'repo'
	New-Item -ItemType Directory -Force -Path (Join-Path $repo 'node_modules\planted'), (Join-Path $repo 'node_modules\planted-pkg\lib') | Out-Null
	# Built as Pi is (scripts\build-pi.ps1): package.json files are read, so a planted package with a "main" would resolve too.
	& $bun build --compile --compile-autoload-package-json workdir\app.mjs --outfile (Join-Path $workdir 'app.exe') *> $null
	[IO.File]::WriteAllText((Join-Path $repo 'node_modules\planted\index.js'), 'module.exports = "PLANTED";')
	[IO.File]::WriteAllText((Join-Path $repo 'node_modules\planted-pkg\package.json'), '{"name": "planted-pkg", "main": "lib/main.mjs"}')
	[IO.File]::WriteAllText((Join-Path $repo 'node_modules\planted-pkg\lib\main.mjs'), 'export default "PLANTED";')
	[IO.File]::WriteAllText((Join-Path $repo 'planted-file.mjs'), 'export default "absolute";')
	Push-Location $repo
	$actual = ((& (Join-Path $workdir 'app.exe') 2>&1 | Out-String) -replace "`r", '').Trim()
	Pop-Location
	Test-Same 'workdir: embedded code resolves nothing in the working directory' 'embedded=ok bare=not-found package=not-found relative=not-found require=not-found resolve=not-found paths=found builtin=function node-builtin=function absolute=absolute' $actual
	Remove-Item -LiteralPath $workdir -Recurse -Force -ErrorAction SilentlyContinue

	function Get-FreePort { $l = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0); $l.Start(); $p = $l.LocalEndpoint.Port; $l.Stop(); $p }
	function Start-Server([int]$port, [string[]]$more = @()) {
		$script:servers += Start-Process -FilePath py -ArgumentList (@('-3', 'keepalive-server.py', $port) + $more) -WorkingDirectory $here -PassThru -WindowStyle Hidden
		for ($i = 0; $i -lt 50; $i++) {
			try { $c = [Net.Sockets.TcpClient]::new('127.0.0.1', $port); $c.Close(); return } catch { Start-Sleep -Milliseconds 100 }
		}
	}
	$plain = Get-FreePort; Start-Server $plain
	# Used again within 4 seconds; a new connection after 7.
	Test-Same 'keepalive: reuse, then a new connection' '1 1 1 2 2' ((& $bun keepalive.mjs "http://127.0.0.1:$plain/" 300 1500 7000 300 | Out-String).Trim())
	# With the default raised, the same idle time keeps the connection.
	$env:BUN_CONFIG_HTTP_KEEPALIVE_TIMEOUT = '30'
	Test-Same 'keepalive: BUN_CONFIG_HTTP_KEEPALIVE_TIMEOUT' '1 1' ((& $bun keepalive.mjs "http://127.0.0.1:$plain/" 7000 | Out-String).Trim())
	$env:BUN_CONFIG_HTTP_KEEPALIVE_TIMEOUT = $null
	$hinted = Get-FreePort; Start-Server $hinted @('--hint', '12')
	# The server keeps it for 12 seconds: used again after 7, not after 11.
	Test-Same "keepalive: the server's Keep-Alive timeout" '1 1 2' ((& $bun keepalive.mjs "http://127.0.0.1:$hinted/" 7000 11000 | Out-String).Trim())
	$silent = Get-FreePort; Start-Server $silent @('--silent-after', '5')
	# The connection is dead after 5 idle seconds and nothing says so: the request after 7 must not wait on it.
	Test-Same 'keepalive: a connection that went dead while idle' '1 2' ((& $bun keepalive.mjs "http://127.0.0.1:$silent/" 7000 | Out-String).Trim())

} finally {
	foreach ($s in $servers) { Stop-Process -Id $s.Id -Force -ErrorAction SilentlyContinue }
	Pop-Location
}
exit $script:status
