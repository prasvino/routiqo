param([Parameter(ValueFromRemainingArguments=$true)][string[]]$GradleTasks = @('check'))
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$jdkCandidates = @($env:JAVA_HOME) + @(Get-ChildItem 'C:\Program Files\Java','C:\Program Files\Eclipse Adoptium' -Directory -ErrorAction SilentlyContinue | Where-Object { $_.Name -match '25' } | Select-Object -ExpandProperty FullName)
$selectedJdk = $null
foreach ($candidate in $jdkCandidates) {
  if ($candidate -and (Test-Path (Join-Path $candidate 'bin/javac.exe'))) {
    $compilerVersion = & (Join-Path $candidate 'bin/javac.exe') -version 2>&1
    if ("$compilerVersion" -match 'javac 25') { $selectedJdk = $candidate; break }
  }
}
if (-not $selectedJdk) { throw 'JDK 25 is required. Set JAVA_HOME to a JDK 25 installation.' }
$previousJavaHome = $env:JAVA_HOME
try {
  $env:JAVA_HOME = $selectedJdk
  Push-Location (Join-Path $projectRoot 'backend')
  & .\gradlew.bat @GradleTasks
  $buildExit = $LASTEXITCODE
} finally {
  Pop-Location
  $env:JAVA_HOME = $previousJavaHome
}
exit $buildExit

