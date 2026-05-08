$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$pidFile = Join-Path $projectRoot "run\\bug-manager-bot.pid"

if (Test-Path $pidFile) {
    $botPid = (Get-Content $pidFile -ErrorAction SilentlyContinue | Select-Object -First 1).Trim()
    if ($botPid) {
        $proc = Get-Process -Id $botPid -ErrorAction SilentlyContinue
        if ($proc) {
            Stop-Process -Id $proc.Id -Force
            Write-Output ("Stopped PID=" + $proc.Id)
            Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
            exit 0
        }
    }
}

Write-Output "No running BugManagerBot process found."
