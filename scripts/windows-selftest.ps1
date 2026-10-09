# A self-test of a Pi-Bolt executable on this Windows machine (Windows 10 1809 or later, or Windows 11; x64), for machines the
# release was not built or tested on. Self-contained: no Python, no repository, no network (a small fake model on 127.0.0.1
# answers Pi; no provider is called). Run it with Windows PowerShell:
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File windows-selftest.ps1 [-Pi C:\path\to\pi-bolt.exe]
#
# Default: the pi-bolt on PATH. It prints PASS/FAIL for each check and writes the same, with what the machine is, to
# pibolt-selftest.txt in the current folder: send that file back. It changes nothing on the machine; its temporary files are in
# %TEMP% and removed at the end.
param([string]$Pi = '')
$ErrorActionPreference = 'Continue'
if (-not $Pi) { $Pi = (Get-Command pi-bolt -ErrorAction SilentlyContinue).Source }
if (-not $Pi -or -not (Test-Path -LiteralPath $Pi)) { Write-Host 'error: no pi-bolt.exe (pass -Pi C:\path\to\pi-bolt.exe)'; exit 1 }
$Pi = (Resolve-Path -LiteralPath $Pi).Path
$report = Join-Path (Get-Location) 'pibolt-selftest.txt'
$lines = New-Object System.Collections.Generic.List[string]
$failures = 0
function Say([string]$text) { Write-Host $text; $lines.Add($text) }
function Result([string]$name, [bool]$ok, [string]$detail = '') {
	if ($ok) { Say "PASS $name" } else { Say "FAIL $name$(if ($detail) { ": $detail" })"; $script:failures++ }
}

# --- What this machine is ---------------------------------------------------------------------------------------------
$os = Get-CimInstance Win32_OperatingSystem
$cpu = (Get-CimInstance Win32_Processor | Select-Object -First 1).Name
Add-Type -Namespace PiBoltSelfTest -Name Cpu -MemberDefinition '[DllImport("kernel32.dll")] public static extern bool IsProcessorFeaturePresent(uint feature);'
Say "machine: $($os.Caption) $($os.Version) (build $($os.BuildNumber)), $env:PROCESSOR_ARCHITECTURE"
Say "cpu: $cpu; AVX2 reported: $([PiBoltSelfTest.Cpu]::IsProcessorFeaturePresent(40))"
Say "powershell: $($PSVersionTable.PSVersion); .NET: $([Environment]::Version)"
Say "pi-bolt: $Pi"

$tmp = Join-Path ([IO.Path]::GetTempPath()) "pibolt-selftest-$([Guid]::NewGuid().ToString('N').Substring(0, 8))"
$agent = Join-Path $tmp 'agent'; $work = Join-Path $tmp 'work'
New-Item -ItemType Directory -Force -Path $agent, $work | Out-Null

# --- A fake model: an OpenAI-style chat completions endpoint on 127.0.0.1 ---------------------------------------------
# Answers a prompt with text; asked to, first calls Pi's read tool on a file (a second request then has the tool's result).
$listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
$listener.Start()
$port = $listener.LocalEndpoint.Port
$server = [PowerShell]::Create()
$null = $server.AddScript({
	param($listener)
	function Read-Request($stream) {
		$head = New-Object System.Collections.Generic.List[byte]
		while ($true) {
			$b = $stream.ReadByte(); if ($b -lt 0) { return $null }
			$head.Add([byte]$b)
			$n = $head.Count
			if ($n -ge 4 -and $head[$n - 4] -eq 13 -and $head[$n - 3] -eq 10 -and $head[$n - 2] -eq 13 -and $head[$n - 1] -eq 10) { break }
		}
		$text = [Text.Encoding]::ASCII.GetString($head.ToArray())
		$length = 0; if ($text -match '(?im)^content-length:\s*(\d+)') { $length = [int]$Matches[1] }
		$body = New-Object byte[] $length; $read = 0
		while ($read -lt $length) { $r = $stream.Read($body, $read, $length - $read); if ($r -le 0) { break }; $read += $r }
		@{ Head = $text; Body = [Text.Encoding]::UTF8.GetString($body) }
	}
	function Chunk($delta, $finish) {
		$o = @{ id = 'chatcmpl-selftest'; object = 'chat.completion.chunk'; created = 0; model = 'fake-model'; choices = @(@{ index = 0; delta = $delta; finish_reason = $finish }) }
		"data: $(ConvertTo-Json $o -Depth 8 -Compress)`n`n"
	}
	while ($true) {
		try { $client = $listener.AcceptTcpClient() } catch { break }
		try {
			$stream = $client.GetStream()
			$request = Read-Request $stream
			if (-not $request) { continue }
			if ($request.Head -like 'GET*') {
				$body = '{"object":"list","data":[{"id":"fake-model","object":"model"}]}'
				$type = 'application/json'
			} else {
				$tools = ([regex]::Matches($request.Body, '"role"\s*:\s*"tool"')).Count
				$wantsTool = $request.Body -match 'SELFTEST-READ'
				$sse = Chunk @{ role = 'assistant'; content = '' } $null
				if ($wantsTool -and $tools -eq 0) {
					$sse += Chunk @{ tool_calls = @(@{ index = 0; id = 'call_0'; type = 'function'; function = @{ name = 'read'; arguments = '{"path":"note.txt"}' } }) } $null
					$sse += Chunk @{} 'tool_calls'
				} else {
					$answer = if ($tools -gt 0) { 'SELFTEST-OK after the tool' } else { 'SELFTEST-OK' }
					$sse += Chunk @{ content = $answer } $null
					$sse += Chunk @{} 'stop'
				}
				$body = $sse + "data: [DONE]`n`n"
				$type = 'text/event-stream'
			}
			$bytes = [Text.Encoding]::UTF8.GetBytes($body)
			$headers = [Text.Encoding]::ASCII.GetBytes("HTTP/1.1 200 OK`r`nContent-Type: $type`r`nContent-Length: $($bytes.Length)`r`nConnection: close`r`n`r`n")
			$stream.Write($headers, 0, $headers.Length); $stream.Write($bytes, 0, $bytes.Length); $stream.Flush()
		} catch { } finally { $client.Close() }
	}
}).AddArgument($listener)
$handle = $server.BeginInvoke()

$models = @{ providers = @{ fake = @{ baseUrl = "http://127.0.0.1:$port/v1"; api = 'openai-completions'; apiKey = 'fake'
	models = @(@{ id = 'fake-model'; contextWindow = 200000; maxTokens = 8192; input = @('text', 'image') }) } } }
[IO.File]::WriteAllText((Join-Path $agent 'models.json'), (ConvertTo-Json $models -Depth 8))
[IO.File]::WriteAllText((Join-Path $agent 'settings.json'), '{"lastChangelogVersion":"9999.0.0"}')
[IO.File]::WriteAllText((Join-Path $agent 'auth.json'), '{}')
[IO.File]::WriteAllText((Join-Path $work 'note.txt'), "a file for the read tool`n")

function Invoke-Pi([string[]]$arguments, [int]$timeoutSeconds = 120) {
	$info = [Diagnostics.ProcessStartInfo]::new($Pi)
	$info.Arguments = ($arguments | ForEach-Object { if ($_ -match '[\s"]') { '"' + ($_ -replace '"', '\"') + '"' } else { $_ } }) -join ' '
	$info.UseShellExecute = $false; $info.RedirectStandardOutput = $true; $info.RedirectStandardError = $true; $info.RedirectStandardInput = $true
	$info.WorkingDirectory = $work
	foreach ($k in @($info.EnvironmentVariables.Keys)) { if ($k -like 'BUN_*' -or $k -like 'PI_*' -or $k -like 'NODE_*') { $info.EnvironmentVariables.Remove($k) } }
	$info.EnvironmentVariables['PI_CODING_AGENT_DIR'] = $agent
	$info.EnvironmentVariables['PI_OFFLINE'] = '1'; $info.EnvironmentVariables['PI_SKIP_VERSION_CHECK'] = '1'; $info.EnvironmentVariables['PI_TELEMETRY'] = '0'
	$info.EnvironmentVariables['BUN_STATIC_HEAP_VERBOSE'] = '1'
	$watch = [Diagnostics.Stopwatch]::StartNew()
	$p = [Diagnostics.Process]::Start($info)
	$p.StandardInput.Close()
	$err = $p.StandardError.ReadToEndAsync(); $out = $p.StandardOutput.ReadToEndAsync()
	if (-not $p.WaitForExit($timeoutSeconds * 1000)) { try { $p.Kill() } catch { }; return @{ Code = 'timeout'; Out = ''; Err = ''; Ms = $watch.ElapsedMilliseconds } }
	@{ Code = $p.ExitCode; Out = $out.Result; Err = $err.Result; Ms = $watch.ElapsedMilliseconds }
}
function Hex($code) { if ($code -is [int]) { '0x{0:x8}' -f $code } else { $code } }

try {
	# --- The checks -------------------------------------------------------------------------------------------------------
	$v = Invoke-Pi @('--version')
	Result 'starts and says its version' ($v.Code -eq 0 -and $v.Out -match 'Pi-Bolt') "exit $(Hex $v.Code) $($v.Out.Trim()) $($v.Err.Trim())"
	Say "  version: $($v.Out.Trim())"
	$aot = if ($v.Err -match 'image of \d+ bytes mapped') { 'yes' } elseif ($v.Err -match 'not used \(([^)]*)\)') { "no ($($Matches[1]))" } else { 'not said' }
	Say "  compiled code used: $aot"
	$times = @(); for ($i = 0; $i -lt 5; $i++) { $times += (Invoke-Pi @('--version')).Ms }
	Say "  --version: $(($times | Sort-Object)[2]) ms (median of 5, with this script's overhead)"

	$r = Invoke-Pi @('--no-session', '--model', 'fake/fake-model', '-p', 'hello')
	Result 'answers a prompt (pi-bolt -p, with the local fake model)' ($r.Code -eq 0 -and $r.Out -match 'SELFTEST-OK') "exit $(Hex $r.Code) $($r.Err.Trim() | Select-Object -First 1)"
	$r = Invoke-Pi @('--no-session', '--model', 'fake/fake-model', '-p', 'SELFTEST-READ the note')
	Result 'runs a tool (read) and answers after it' ($r.Code -eq 0 -and $r.Out -match 'SELFTEST-OK after the tool') "exit $(Hex $r.Code) $($r.Err.Trim())"

	# An image it is given: resized in a worker (a crash there ended Pi-Bolt 0.7.0 on Windows).
	Add-Type -AssemblyName System.Drawing
	$bitmap = New-Object System.Drawing.Bitmap 2400, 1600
	$g = [System.Drawing.Graphics]::FromImage($bitmap); $g.Clear([System.Drawing.Color]::SteelBlue); $g.Dispose()
	$bitmap.Save((Join-Path $work 'big.png'), [System.Drawing.Imaging.ImageFormat]::Png); $bitmap.Dispose()
	$r = Invoke-Pi @('--no-session', '--model', 'fake/fake-model', '-p', '@big.png', 'What is in this image?')
	Result 'an image it is given (resized in a worker)' ($r.Code -eq 0 -and $r.Out -match 'SELFTEST-OK') "exit $(Hex $r.Code) $($r.Err.Trim())"

	# An extension that uses Node's modules, starts programs and workers.
	$ext = Join-Path $work 'selftest-extension.js'
	[IO.File]::WriteAllText($ext, @'
import { spawnSync } from "node:child_process";
import { createRequire } from "node:module";
const require = createRequire(import.meta.url);
const results = [];
for (const name of ["node:tls", "node:https", "node:http", "node:net", "node:zlib", "node:querystring"]) results.push(Object.keys(require(name)).length > 2);
results.push(spawnSync(process.env.ComSpec || "cmd.exe", ["/d", "/c", "exit 7"]).status === 7);
const worker = () => new Promise((resolve) => {
	const w = new Worker(URL.createObjectURL(new Blob(["self.onmessage = (e) => postMessage(e.data * 2);"])));
	w.onmessage = (e) => { w.terminate(); resolve(e.data === 42); };
	w.onerror = () => resolve(false);
	w.postMessage(21);
});
export default function () {
	Promise.all([worker(), worker(), worker()]).then((ok) => {
		results.push(...ok);
		for (let i = 0; i < 3; i++) Bun.gc(true);
		console.log(`selftest-extension: ${results.every(Boolean) ? "ok" : "failed " + JSON.stringify(results)}`);
		process.exit(0);
	});
}
'@)
	$r = Invoke-Pi @('-ne', '-e', $ext, '--no-session', '-p', 'hi')
	Result "an extension (Node's modules, a program, workers, collections)" ($r.Out -match 'selftest-extension: ok') "exit $(Hex $r.Code) $($r.Out.Trim()) $($r.Err.Trim())"

	# The interactive interface starts in this console (its first frame), and ends on Ctrl+C... cannot be driven from here
	# without a terminal of its own: `--help` stands for it.
	$r = Invoke-Pi @('--help')
	Result 'prints its help' ($r.Code -eq 0 -and $r.Out.Length -gt 200) "exit $(Hex $r.Code)"
} finally {
	$listener.Stop()
	try { $server.Stop() } catch { }
	$server.Dispose()
	Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
	Say "$(if ($failures) { "$failures check(s) failed" } else { 'all checks passed' })"
	[IO.File]::WriteAllLines($report, $lines)
	Write-Host "`nWritten to $report"
}
exit $(if ($failures) { 1 } else { 0 })
