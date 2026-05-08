$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$logDir = Join-Path $projectRoot "logs"
$runDir = Join-Path $projectRoot "run"
$stdoutLog = Join-Path $logDir "bot.out.log"
$stderrLog = Join-Path $logDir "bot.err.log"
$pidFile = Join-Path $runDir "bug-manager-bot.pid"

$botPid = $null
if (Test-Path $pidFile) {
    $rawPid = Get-Content $pidFile -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($rawPid) {
        $botPid = $rawPid.Trim()
    }
}

$proc = $null
if ($botPid) {
    $proc = Get-Process -Id $botPid -ErrorAction SilentlyContinue
}

if (-not $proc) {
    Write-Output "Status: STOPPED"
} else {
    Write-Output "Status: RUNNING"
    Write-Output ("PID: " + $proc.Id)
    Write-Output ("Name: " + $proc.ProcessName)
    if ($proc.StartTime) {
        Write-Output ("Started: " + $proc.StartTime.ToString("yyyy-MM-dd HH:mm:ss"))
        $uptime = (Get-Date) - $proc.StartTime
        Write-Output ("Uptime: " + [int]$uptime.TotalHours + "h " + $uptime.Minutes + "m " + $uptime.Seconds + "s")
    }
    if (Test-Path $pidFile) {
        Write-Output ("PidFile: " + $pidFile)
    }
    Write-Output ""
}

Write-Output "Last stderr lines:"
if (Test-Path $stderrLog) {
    Get-Content $stderrLog -Tail 20
} else {
    Write-Output "(no stderr log)"
}

Write-Output ""
Write-Output "Last stdout lines:"
if (Test-Path $stdoutLog) {
    Get-Content $stdoutLog -Tail 20
} else {
    Write-Output "(no stdout log)"
}
