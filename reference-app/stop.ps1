$ErrorActionPreference = 'Stop'
$pidFile = Join-Path $PSScriptRoot '.runtime\server.pid'
if (!(Test-Path -LiteralPath $pidFile)) { Write-Host 'No server started by start.ps1.'; exit 0 }
$serverProcessId = [int](Get-Content -LiteralPath $pidFile -Raw).Trim()
$process = Get-CimInstance Win32_Process -Filter "ProcessId=$serverProcessId" -ErrorAction SilentlyContinue
if ($process -and $process.CommandLine -like '*campus-market-1.0.0.jar*') { Stop-Process -Id $serverProcessId }
Remove-Item -LiteralPath $pidFile
Write-Host 'Campus Market stopped.'
