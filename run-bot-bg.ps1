$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$logDir = Join-Path $projectRoot "logs"
$startScript = Join-Path $projectRoot "start-bot.ps1"
$runDir = Join-Path $projectRoot "run"

New-Item -ItemType Directory -Force -Path $logDir | Out-Null
New-Item -ItemType Directory -Force -Path $runDir | Out-Null

$stdoutLog = Join-Path $logDir "bot.out.log"
$stderrLog = Join-Path $logDir "bot.err.log"
$pidFile = Join-Path $runDir "bug-manager-bot.pid"

$process = Start-Process -FilePath "powershell.exe" `
    -ArgumentList @(
        "-NoProfile",
        "-ExecutionPolicy", "Bypass",
        "-File", $startScript
    ) `
    -WorkingDirectory $projectRoot `
    -RedirectStandardOutput $stdoutLog `
    -RedirectStandardError $stderrLog `
    -WindowStyle Hidden `
    -PassThru

$process.Id | Set-Content -Path $pidFile -Encoding ascii

Write-Output ("PID=" + $process.Id)
Write-Output ("STDOUT=" + $stdoutLog)
Write-Output ("STDERR=" + $stderrLog)
Write-Output ("PIDFILE=" + $pidFile)
