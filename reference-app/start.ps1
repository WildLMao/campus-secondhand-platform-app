param([switch]$NoBrowser)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$jar = Join-Path $PSScriptRoot 'backend\target\campus-market-1.0.0.jar'
if (!(Test-Path -LiteralPath $jar)) { & (Join-Path $PSScriptRoot 'build.ps1') }
$javaCandidates = @($(if($env:JAVA_HOME){Join-Path $env:JAVA_HOME 'bin\java.exe'}), (Get-Command java -ErrorAction SilentlyContinue).Source)
$javaExe = $javaCandidates | Where-Object { $_ -and (Test-Path -LiteralPath $_) } | Select-Object -First 1
if (!$javaExe) { throw 'Java 17+ is required.' }
$port = if($env:PORT){$env:PORT}else{'18080'}
$url = "http://127.0.0.1:$port"
$runtime = Join-Path $PSScriptRoot '.runtime'
New-Item -ItemType Directory -Path $runtime -Force | Out-Null
$existing = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
if ($existing) {
  $pidFile = Join-Path $runtime 'server.pid'
  if ((Test-Path -LiteralPath $pidFile) -and ((Get-Content -LiteralPath $pidFile -Raw).Trim() -eq [string]$existing[0].OwningProcess)) { if(!$NoBrowser){Start-Process $url}; exit 0 }
  throw "Port $port is occupied by another process. Set PORT or stop the other process."
}
$process = Start-Process -FilePath $javaExe -ArgumentList @('-Dfile.encoding=UTF-8','-jar','target/campus-market-1.0.0.jar') -WorkingDirectory (Join-Path $PSScriptRoot 'backend') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtime 'server.log') -RedirectStandardError (Join-Path $runtime 'server-error.log')
[string]$process.Id | Set-Content -LiteralPath (Join-Path $runtime 'server.pid')
for($attempt=0;$attempt -lt 45;$attempt++) {
  Start-Sleep -Seconds 1
  if($process.HasExited) { throw 'Server failed. See .runtime/server.log and server-error.log.' }
  try { $response = Invoke-WebRequest -Uri "$url/api/me" -UseBasicParsing -TimeoutSec 2; if($response.StatusCode -eq 200){if(!$NoBrowser){Start-Process $url};Write-Host "Campus Market: $url";exit 0} } catch {}
}
throw 'Startup timed out. See .runtime/server.log.'
