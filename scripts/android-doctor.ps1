[CmdletBinding()]
param(
    [string]$SdkPath,
    [string]$AndroidJavaHome,
    [switch]$RequireReady
)

$ErrorActionPreference = 'Stop'

# Inspect prerequisites without installing software, changing environment variables,
# starting adb/emulators, or contacting a device.
if (-not $SdkPath) {
    if ($env:ANDROID_HOME) { $SdkPath = $env:ANDROID_HOME }
    elseif ($env:ANDROID_SDK_ROOT) { $SdkPath = $env:ANDROID_SDK_ROOT }
    else { $SdkPath = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
}
if (-not $AndroidJavaHome) { $AndroidJavaHome = $env:JAVA_HOME }

$taskChecks = [System.Collections.Generic.List[object]]::new()
function Add-Check([string]$Name, [bool]$Ready, [string]$Action) {
    $taskChecks.Add([PSCustomObject]@{
        Check = $Name
        Status = $(if ($Ready) { 'Ready' } else { 'Missing' })
        Action = $(if ($Ready) { '' } else { $Action })
    })
}

Add-Check 'Android SDK' (Test-Path -LiteralPath $SdkPath -PathType Container) 'Install SDK in Android Studio or pass -SdkPath.'
Add-Check 'Android API 36' (Test-Path -LiteralPath (Join-Path $SdkPath 'platforms\android-36\android.jar') -PathType Leaf) 'Install Android 16 (API 36) SDK Platform.'
Add-Check 'Build Tools 36.0.0' (Test-Path -LiteralPath (Join-Path $SdkPath 'build-tools\36.0.0\aapt2.exe') -PathType Leaf) 'Install Android SDK Build-Tools 36.0.0.'
Add-Check 'Platform Tools' (Test-Path -LiteralPath (Join-Path $SdkPath 'platform-tools\adb.exe') -PathType Leaf) 'Install Android SDK Platform-Tools.'

$taskCommandTools = Join-Path $SdkPath 'cmdline-tools'
$taskSdkManager = @()
if (Test-Path -LiteralPath $taskCommandTools -PathType Container) {
    $taskSdkManager = @(Get-ChildItem -LiteralPath $taskCommandTools -Directory | Where-Object {
        Test-Path -LiteralPath (Join-Path $_.FullName 'bin\sdkmanager.bat') -PathType Leaf
    })
}
Add-Check 'Command-line Tools' ($taskSdkManager.Count -gt 0) 'Install Android SDK Command-line Tools in SDK Manager.'

$taskJava17 = $false
if ($AndroidJavaHome) {
    $taskJavaRelease = Join-Path $AndroidJavaHome 'release'
    if ((Test-Path -LiteralPath $taskJavaRelease -PathType Leaf) -and
        (Test-Path -LiteralPath (Join-Path $AndroidJavaHome 'bin\javac.exe') -PathType Leaf)) {
        $taskJava17 = [bool](Select-String -LiteralPath $taskJavaRelease -Pattern '^JAVA_VERSION="17(?:\.|"|\+)' -Quiet)
    }
}
Add-Check 'Android JDK 17' $taskJava17 'Pass -AndroidJavaHome pointing to JDK 17. Keep Java 25 for the backend.'

Write-Output "Android SDK inspected: $SdkPath"
$taskChecks | Format-Table -AutoSize -Wrap
Write-Output 'This checks installed files only. SDK licenses, Gradle build, device connection, emulator images and app behavior still require verification.'
if ($RequireReady -and ($taskChecks.Status -contains 'Missing')) { exit 1 }
