[CmdletBinding()]
param(
    [string]$SdkPath,
    [string]$AndroidJavaHome,
    [string]$Architectures = 'arm64-v8a,x86_64'
)

$ErrorActionPreference = 'Stop'

if (-not $IsWindows -and $PSVersionTable.PSEdition -eq 'Core') {
    throw 'This wrapper is for local Windows Android builds.'
}
if (-not $SdkPath -or -not $AndroidJavaHome) {
    throw 'Pass explicit -SdkPath and -AndroidJavaHome paths.'
}

$repo = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$project = Join-Path $repo 'apps\mobile\android'
$wrapper = Join-Path $project 'gradlew.bat'
$initScript = Join-Path $PSScriptRoot 'android-native-paths.gradle'
if (-not (Test-Path -LiteralPath $wrapper -PathType Leaf)) {
    throw 'Generated Android project is missing. Run Expo prebuild first.'
}
if (-not (Test-Path -LiteralPath $initScript -PathType Leaf)) {
    throw 'Android native path init script is missing.'
}

$sdk = (Resolve-Path -LiteralPath $SdkPath -ErrorAction Stop).ProviderPath
$java = (Resolve-Path -LiteralPath $AndroidJavaHome -ErrorAction Stop).ProviderPath
$javaRelease = Join-Path $java 'release'
if (-not (Test-Path -LiteralPath (Join-Path $java 'bin\javac.exe') -PathType Leaf) -or
    -not (Test-Path -LiteralPath $javaRelease -PathType Leaf) -or
    -not (Select-String -LiteralPath $javaRelease -Pattern '^JAVA_VERSION="17(?:\.|"|\+)' -Quiet)) {
    throw 'AndroidJavaHome must point to JDK 17.'
}
if (-not (Test-Path -LiteralPath (Join-Path $sdk 'platforms\android-36\android.jar') -PathType Leaf) -or
    -not (Test-Path -LiteralPath (Join-Path $sdk 'build-tools\36.0.0\aapt2.exe') -PathType Leaf)) {
    throw 'SdkPath must contain Android API 36 and Build Tools 36.0.0.'
}

$validArchitectures = @('armeabi-v7a', 'arm64-v8a', 'x86', 'x86_64')
$selected = @($Architectures.Split(',') | ForEach-Object { $_.Trim() })
if ($selected.Count -eq 0 -or $selected.Count -ne @($selected | Select-Object -Unique).Count -or
    @($selected | Where-Object { $_ -notin $validArchitectures }).Count -gt 0) {
    throw 'Architectures must be a unique comma-separated subset of armeabi-v7a,arm64-v8a,x86,x86_64.'
}

$localRoot = [System.IO.Path]::GetFullPath((Join-Path $repo '.local-gradle-android'))
$expectedPrefix = $repo.TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar
if (-not $localRoot.StartsWith($expectedPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Local Gradle output must stay inside the repository.'
}
$gradleHome = $localRoot
$nativeRoot = Join-Path $localRoot 'native'
foreach ($directory in @($localRoot, $gradleHome, $nativeRoot)) {
    if (Test-Path -LiteralPath $directory) {
        $item = Get-Item -LiteralPath $directory -Force
        if (-not $item.PSIsContainer -or
            ($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint)) {
            throw 'Local Gradle output paths must be ordinary directories inside the repository.'
        }
    } else {
        New-Item -ItemType Directory -Path $directory | Out-Null
    }
}

$names = @('JAVA_HOME', 'ANDROID_HOME', 'ANDROID_SDK_ROOT', 'GRADLE_USER_HOME')
$previous = @{}
foreach ($name in $names) {
    $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
try {
    [Environment]::SetEnvironmentVariable('JAVA_HOME', $java, 'Process')
    [Environment]::SetEnvironmentVariable('ANDROID_HOME', $sdk, 'Process')
    [Environment]::SetEnvironmentVariable('ANDROID_SDK_ROOT', $sdk, 'Process')
    [Environment]::SetEnvironmentVariable('GRADLE_USER_HOME', $gradleHome, 'Process')
    Push-Location -LiteralPath $project
    try {
        $gradleArguments = @(
            ':app:assembleDebug', '--init-script', $initScript,
            "-PreactNativeArchitectures=$($selected -join ',')",
            "-Droutiqo.native.stagingRoot=$nativeRoot", '--max-workers=2', '--console=plain'
        )
        & $wrapper @gradleArguments
        if ($LASTEXITCODE -ne 0) { throw "Android Gradle build failed with exit code $LASTEXITCODE." }
    } finally {
        Pop-Location
    }
} finally {
    foreach ($name in $names) {
        [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process')
    }
}
