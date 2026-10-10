# Installs each of the real extensions measured by bench\plugins_real\measure.py into a Pi agent directory of its own, under
# .work\plugins-real\<name>\agent, from registry.npmjs.org, with the packages' install scripts off. Pi's own `install` does it
# (as a user would), with Pi-Bolt's executable.
#   bench\plugins_real\install.ps1 [-Pi out\pi-bolt\pi.exe]
param([string]$Pi = '')
$ErrorActionPreference = 'Continue'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if (-not $Pi) { $Pi = Join-Path $root 'out\pi-bolt\pi.exe' }
$work = Join-Path $root '.work\plugins-real'
$extensions = [ordered]@{
	'opensec-pi-subagents' = 'opensec-pi-subagents@0.20.0'
	'opensec-pi-todo' = 'opensec-pi-todo@2.13.0'
	'pi-mcp-adapter' = 'pi-mcp-adapter@5.2.0'
	'pi-subagents' = 'pi-subagents@0.76.1'
	'pi-powerline-footer' = 'pi-powerline-footer@0.19.1'
	'pi-permission-system' = '@gotgenes/pi-permission-system@40.1.2'
	'pi-lens' = 'pi-lens@4.4.1'
	'statusline' = '@reedchan/statusline@1.11.3'
	'pi-docparser' = 'pi-docparser@4.0.0'
}
$env:NPM_CONFIG_REGISTRY = 'https://registry.npmjs.org'
$env:NPM_CONFIG_IGNORE_SCRIPTS = 'true'
$env:PI_OFFLINE = $null
foreach ($name in $extensions.Keys) {
	$agent = Join-Path $work "$name\agent"
	New-Item -ItemType Directory -Force -Path $agent | Out-Null
	$env:PI_CODING_AGENT_DIR = $agent
	$watch = [Diagnostics.Stopwatch]::StartNew()
	$out = & $Pi install "npm:$($extensions[$name])" 2>&1 | Out-String
	$code = $LASTEXITCODE
	$size = (Get-ChildItem -LiteralPath $agent -Recurse -File -ErrorAction SilentlyContinue | Measure-Object Length -Sum).Sum / 1MB
	Write-Host ("{0,-24} exit {1}  {2,6:N1} MB  {3:N0} s" -f $name, $code, $size, $watch.Elapsed.TotalSeconds)
	if ($code -ne 0) { ($out.Trim() -split "`n" | Select-Object -Last 4) | ForEach-Object { Write-Host "   $_" } }
}
$env:PI_CODING_AGENT_DIR = $null
