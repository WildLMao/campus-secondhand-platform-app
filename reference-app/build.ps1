$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
if (Test-Path -LiteralPath (Join-Path $PSScriptRoot '.runtime\server.pid')) { & (Join-Path $PSScriptRoot 'stop.ps1') }
$javaCandidates = @($env:JAVA_HOME)
$jdk = $javaCandidates | Where-Object { $_ -and (Test-Path -LiteralPath (Join-Path $_ 'bin\javac.exe')) } | Select-Object -First 1
if ($jdk) { $env:JAVA_HOME = $jdk }
$nodeCandidates = @((Get-Command node -ErrorAction SilentlyContinue).Source)
$nodeExe = $nodeCandidates | Where-Object { $_ -and (Test-Path -LiteralPath $_) } | Select-Object -First 1
if (!$nodeExe) { throw 'Node.js 20.19+ is required.' }
$env:PATH = (Split-Path $nodeExe) + ';' + $env:PATH
Set-Location -LiteralPath (Join-Path $PSScriptRoot 'frontend')
if (!(Test-Path -LiteralPath 'node_modules\vite\bin\vite.js')) {
  & npm.cmd install --cache (Join-Path $PSScriptRoot '.npm-cache') --no-audit --no-fund
  if ($LASTEXITCODE -ne 0) { throw 'npm install failed.' }
}
& $nodeExe 'node_modules\vite\bin\vite.js' build
if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
$static = Join-Path $PSScriptRoot 'backend\src\main\resources\static'
New-Item -ItemType Directory -Path $static -Force | Out-Null
Copy-Item -Path '.\dist\*' -Destination $static -Recurse -Force
Set-Location -LiteralPath (Join-Path $PSScriptRoot 'backend')
$maven = (Get-Command mvn.cmd -ErrorAction SilentlyContinue).Source
if (!$maven -and $env:MAVEN_HOME) { $maven = Join-Path $env:MAVEN_HOME 'bin\mvn.cmd' }
if (!$maven) { throw 'Maven 3.6+ is required.' }
& $maven -q package
if ($LASTEXITCODE -ne 0) { throw 'Backend build or tests failed.' }
Write-Host 'Build and tests passed. Run start.ps1.'
