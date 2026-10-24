# install.ps1's questions and what was typed before them (#103), with its functions only (PIBOLT_INSTALLER_NO_MAIN):
#   - on a console of its own, "y" and Enter typed together at the action menu: the menu takes the "y", and the Enter left in the
#     console's input does not answer the next question, which takes the "n" typed once it is shown;
#   - with input redirected (no console to read), dropping type-ahead does not fail, and the question takes the line given.
# The console case starts a PowerShell in a console of its own and types into that console from another process (AttachConsole
# and WriteConsoleInput), which reads the console's screen to see the question, as a person would.
#
# Usage: tests\installer\typeahead.ps1
param([int]$Drive = 0, [string]$Work = '')
$ErrorActionPreference = 'Continue'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path

if ($Drive) {
	# The typist: attaches to the console of process $Drive, types "y" and Enter, says so ($Work\typed), waits for the question on
	# the screen, then types "n" and Enter. Writes what it did to $Work\driver.log.
	Add-Type -IgnoreWarnings -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
using System.Text;

public static class PiBoltTestConsole {
	[StructLayout(LayoutKind.Explicit)]
	struct KeyRecord {
		[FieldOffset(0)] public ushort EventType;
		[FieldOffset(4)] public int KeyDown;
		[FieldOffset(8)] public ushort RepeatCount;
		[FieldOffset(10)] public ushort VirtualKeyCode;
		[FieldOffset(12)] public ushort VirtualScanCode;
		[FieldOffset(14)] public char UnicodeChar;
		[FieldOffset(16)] public uint ControlKeyState;
	}
	[StructLayout(LayoutKind.Sequential)]
	struct Coord { public short X; public short Y; }
	[StructLayout(LayoutKind.Sequential)]
	struct BufferInfo {
		public Coord Size; public Coord Cursor; public ushort Attributes;
		public short Left; public short Top; public short Right; public short Bottom; public Coord MaxWindow;
	}
	[DllImport("kernel32.dll")] static extern bool FreeConsole();
	[DllImport("kernel32.dll", SetLastError = true)] static extern bool AttachConsole(int pid);
	[DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
	static extern IntPtr CreateFileW(string name, uint access, uint share, IntPtr security, uint disposition, uint flags, IntPtr template);
	[DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr handle);
	[DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
	static extern bool WriteConsoleInputW(IntPtr input, KeyRecord[] records, uint count, out uint written);
	[DllImport("kernel32.dll", SetLastError = true)] static extern bool GetConsoleScreenBufferInfo(IntPtr output, out BufferInfo info);
	[DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
	static extern bool ReadConsoleOutputCharacterW(IntPtr output, StringBuilder text, uint length, Coord at, out uint read);
	[DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern short VkKeyScanW(char c);
	[DllImport("user32.dll")] static extern uint MapVirtualKeyW(uint code, uint type);

	static IntPtr Open(string name) {
		IntPtr handle = CreateFileW(name, 0xC0000000, 3, IntPtr.Zero, 3, 0, IntPtr.Zero);
		if (handle == new IntPtr(-1)) throw new InvalidOperationException("cannot open " + name + ": " + Marshal.GetLastWin32Error());
		return handle;
	}

	public static void Attach(int pid) {
		FreeConsole();
		if (!AttachConsole(pid)) throw new InvalidOperationException("AttachConsole: " + Marshal.GetLastWin32Error());
	}

	public static void Type(string text) {
		KeyRecord[] records = new KeyRecord[text.Length * 2];
		for (int i = 0; i < text.Length; i++) {
			char c = text[i];
			ushort vk = c == '\r' ? (ushort)0x0D : (ushort)(VkKeyScanW(c) & 0xFF);
			for (int up = 0; up < 2; up++) {
				KeyRecord r = new KeyRecord();
				r.EventType = 1;
				r.KeyDown = up == 0 ? 1 : 0;
				r.RepeatCount = 1;
				r.VirtualKeyCode = vk;
				r.VirtualScanCode = (ushort)MapVirtualKeyW(vk, 0);
				r.UnicodeChar = c;
				records[i * 2 + up] = r;
			}
		}
		IntPtr input = Open("CONIN$");
		try {
			uint written;
			if (!WriteConsoleInputW(input, records, (uint)records.Length, out written) || written != records.Length)
				throw new InvalidOperationException("WriteConsoleInput: " + Marshal.GetLastWin32Error());
		} finally { CloseHandle(input); }
	}

	public static string Screen() {
		IntPtr output = Open("CONOUT$");
		try {
			BufferInfo info;
			if (!GetConsoleScreenBufferInfo(output, out info)) throw new InvalidOperationException("GetConsoleScreenBufferInfo: " + Marshal.GetLastWin32Error());
			uint length = (uint)(info.Size.X * (info.Cursor.Y + 1));
			StringBuilder text = new StringBuilder((int)length);
			uint read;
			ReadConsoleOutputCharacterW(output, text, length, new Coord(), out read);
			return text.ToString();
		} finally { CloseHandle(output); }
	}
}
'@
	$log = Join-Path $Work 'driver.log'
	try {
		for ($i = 0; -not (Test-Path -LiteralPath (Join-Path $Work 'ready')); $i++) {
			if ($i -ge 600) { throw 'the installer did not start' }
			Start-Sleep -Milliseconds 100
		}
		[PiBoltTestConsole]::Attach($Drive)
		[PiBoltTestConsole]::Type("y`r")
		Set-Content -LiteralPath (Join-Path $Work 'typed') -Value ''
		for ($i = 0; -not ([PiBoltTestConsole]::Screen()).Contains('Install them? [Y/n]'); $i++) {
			if ($i -ge 300) { throw "the question did not appear; the screen:`n$([PiBoltTestConsole]::Screen())" }
			Start-Sleep -Milliseconds 100
		}
		Start-Sleep -Milliseconds 300
		[PiBoltTestConsole]::Type("n`r")
		Add-Content -LiteralPath $log -Value 'typed y, Enter, then n, Enter at the question'
		exit 0
	} catch {
		Add-Content -LiteralPath $log -Value "driver: $($_.Exception.Message)"
		exit 1
	}
}

$work = Join-Path ([IO.Path]::GetTempPath()) "pibolt-typeahead-$([Guid]::NewGuid().ToString('N').Substring(0, 8))"
New-Item -ItemType Directory -Force -Path $work | Out-Null
$script:status = 0
function Write-Pass([string]$what) { Write-Host "PASS $what" }
function Write-Fail([string]$what, [string]$out = '') { Write-Host "FAIL $what"; if ($out) { Write-Host $out }; $script:status = 1 }
$installer = (Join-Path $root 'install.ps1') -replace "'", "''"

# Runs the console case with $ask as the question (PowerShell, in the installer's scope); returns "<the menu's key>|<the answer>".
function Invoke-ConsoleCase([string]$case, [string]$ask) {
	$dir = Join-Path $work $case
	New-Item -ItemType Directory -Force -Path $dir | Out-Null
	$at = $dir -replace "'", "''"
	# The installer's side: the menu's key, a second (the download, in a real run), then the question.
	$user = Join-Path $dir 'user.ps1'
	Set-Content -LiteralPath $user -Encoding utf8 -Value @"
`$env:PIBOLT_INSTALLER_NO_MAIN = '1'
. '$installer'
Set-Content -LiteralPath '$at\ready' -Value ''
while (-not (Test-Path -LiteralPath '$at\typed')) { Start-Sleep -Milliseconds 100 }
`$key = Read-PiBoltKey
Start-Sleep -Seconds 1
`$answer = & { $ask }
Set-Content -LiteralPath '$at\result' -Value "`$key|`$answer"
"@
	# (Start-Process without redirection or -NoNewWindow gives each its own console.)
	$child = Start-Process -FilePath powershell -ArgumentList '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$user`"" -WindowStyle Hidden -PassThru
	$driver = Start-Process -FilePath powershell -WindowStyle Hidden -PassThru -ArgumentList '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
		"`"$PSCommandPath`"", '-Drive', $child.Id, '-Work', "`"$dir`""
	$null = $driver.WaitForExit(90000)
	$null = $child.WaitForExit(15000)
	foreach ($p in $child, $driver) { if (-not $p.HasExited) { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue } }
	$log = if (Test-Path -LiteralPath "$dir\driver.log") { (Get-Content -LiteralPath "$dir\driver.log" -Raw).Trim() } else { '(no driver log)' }
	if (Test-Path -LiteralPath "$dir\result") { (Get-Content -LiteralPath "$dir\result" -Raw).Trim() } else { "(no answer) $log" }
}

try {
	# The check itself: Read-Host alone takes the Enter left by the menu (#103 as it was), so the typing does reach the console.
	$result = Invoke-ConsoleCase control 'Write-Host -NoNewline "`nInstall them? [Y/n] "; Read-Host'
	if ($result -eq 'y|') { Write-Pass 'without dropping it, the Enter typed after the menu''s key answers the question (the check works)' }
	else { Write-Fail 'without dropping it, the Enter typed after the menu''s key answers the question (the check works)' "key|answer: $result" }

	$result = Invoke-ConsoleCase fixed 'Read-PiBoltAnswer "`nInstall them? [Y/n] "'
	if ($result -eq 'y|n') { Write-Pass 'the Enter typed after the menu''s key does not answer the next question' }
	else { Write-Fail 'the Enter typed after the menu''s key does not answer the next question' "key|answer: $result" }

	# No console to read: input from a file.
	$redirected = Join-Path $work 'redirected.ps1'
	Set-Content -LiteralPath $redirected -Encoding utf8 -Value @"
`$env:PIBOLT_INSTALLER_NO_MAIN = '1'
. '$installer'
Clear-PiBoltTypeahead
`$answer = Read-PiBoltAnswer 'Install them? [Y/n] '
Write-Host "answer=[`$answer]"
"@
	$in = Join-Path $work 'in.txt'
	[IO.File]::WriteAllText($in, "n`r`n")
	$out = Join-Path $work 'out.txt'
	$err = Join-Path $work 'err.txt'
	$p = Start-Process -FilePath powershell -ArgumentList '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$redirected`"" -NoNewWindow -PassThru `
		-RedirectStandardInput $in -RedirectStandardOutput $out -RedirectStandardError $err
	$null = $p.Handle # (Windows PowerShell has no exit code for a process started so without it.)
	if (-not $p.WaitForExit(60000)) { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue }
	$printed = "$(Get-Content -LiteralPath $out -Raw)$(Get-Content -LiteralPath $err -Raw)"
	if ($p.HasExited -and $p.ExitCode -eq 0 -and $printed -match 'answer=\[n\]' -and $printed -notmatch 'Exception|error') {
		Write-Pass 'without a console, the question takes the line given and nothing fails'
	} else { Write-Fail 'without a console, the question takes the line given and nothing fails' $printed }
} finally {
	Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
}
exit $script:status
