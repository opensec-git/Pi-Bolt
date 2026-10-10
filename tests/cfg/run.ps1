# Checks that WTF::callSysV() (wtf/SysVCall.h), through which the runtime makes its indirect calls to SysV-convention functions on
# Windows x64, still has Control Flow Guard check the target: built with /guard:cf against a runtime build's headers, a call to a
# function through it runs, and a call to an address that is not a function's start ends the process (a fail-fast) before
# anything is called. (docs\WINDOWS.md, "The other mitigations".)
# Usage: tests\cfg\run.ps1 [-BuildDir DIR]   DIR: the runtime's build, in .work\bun (default build/pibolt-release)
param([string]$BuildDir = 'build/pibolt-release')
$ErrorActionPreference = 'Continue'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$Work = if ($env:PIBOLT_WORK) { $env:PIBOLT_WORK } else { Join-Path $Root '.work' }
$WebKitBuild = Join-Path $Work "bun\$BuildDir\deps\WebKit"
if (-not (Test-Path (Join-Path $WebKitBuild 'WTF\Headers\wtf\SysVCall.h'))) { Write-Host "error: no WebKit build with wtf/SysVCall.h at $WebKitBuild" -ForegroundColor Red; exit 1 }
$Out = Join-Path $Work 'tests\cfg'
New-Item -ItemType Directory -Force $Out | Out-Null
$Exe = Join-Path $Out 'callsysv.exe'
& clang-cl /nologo /O2 /std:c++latest /EHsc /guard:cf /DBUILDING_WITH_CMAKE=1 /DHAVE_CONFIG_H=1 /DSTATICALLY_LINKED_WITH_WTF /DSTATICALLY_LINKED_WITH_bmalloc /DNOMINMAX /DWIN32_LEAN_AND_MEAN `
	"/I$WebKitBuild" "/I$WebKitBuild\WTF\Headers" "/I$WebKitBuild\bmalloc\Headers" "/I$(Join-Path $Work 'webkit\Source\WTF')" `
	"/Fo$Out\" (Join-Path $PSScriptRoot 'callsysv.cpp') "/Fe$Exe" /link /guard:cf | Out-Host
if ($LASTEXITCODE -ne 0) { Write-Host 'error: the test did not build' -ForegroundColor Red; exit 1 }
$failed = 0
$valid = & $Exe valid
if ($LASTEXITCODE -eq 0 -and "$valid".Trim() -eq '7') { Write-Host 'PASS a call to a function runs' } else { Write-Host "FAIL a call to a function: exit $LASTEXITCODE, printed '$valid'"; $failed++ }
$invalid = & $Exe invalid
if ($LASTEXITCODE -eq -1073740791 -and -not "$invalid".Trim()) { Write-Host 'PASS a call to an address that is not a function ends the process (0xC0000409)' } else { Write-Host ("FAIL a call to an address that is not a function: exit 0x{0:x}, printed '{1}'" -f $LASTEXITCODE, $invalid); $failed++ }
exit $(if ($failed) { 1 } else { 0 })
