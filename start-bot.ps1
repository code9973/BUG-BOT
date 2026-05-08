$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$targetDir = Join-Path $projectRoot "target"
$jarPath = Join-Path $targetDir "bug-manager-bot-2.0.0.jar"
$libDir = Join-Path $targetDir "runtime-lib"
$mainClass = "com.bugbot.BugManagerBot"

if (-not (Test-Path $jarPath)) {
    throw "Jar not found: $jarPath"
}

if (-not (Test-Path $libDir)) {
    throw "Runtime lib dir not found: $libDir"
}

$classpath = "$jarPath;$libDir\*"
Set-Location $projectRoot
& java "-cp" $classpath $mainClass
