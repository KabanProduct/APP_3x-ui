param([string[]]$Tasks = @(':app:testDebugUnitTest', ':app:assembleDebug', ':app:lintDebug'))
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
# JDK 17's Windows argument-file decoding breaks Gradle test classpaths containing
# Cyrillic. Map this exact workspace to an unused drive for the build only.
$originalRoot = $projectRoot
$mappedDrive = $null
if ($projectRoot -match '[^\x00-\x7F]') {
    $mappedDrive = @('X:', 'Y:', 'Z:', 'W:') | Where-Object { -not (Test-Path ($_ + '\')) } | Select-Object -First 1
    if (-not $mappedDrive) { throw 'No unused build drive is available.' }
    & subst.exe $mappedDrive $projectRoot
    if ($LASTEXITCODE -ne 0) { throw 'Unable to map the workspace build drive.' }
    $projectRoot = $mappedDrive + '\'
}
try {
    Set-Location -LiteralPath $projectRoot
    $jdkFolder = (Get-ChildItem "$projectRoot/.tools/jdk" -Directory | Select-Object -First 1).Name
    $env:JAVA_HOME = "$projectRoot/.tools/jdk/$jdkFolder"
    $env:ANDROID_HOME = "$projectRoot/.tools/android-sdk"
    $env:ANDROID_USER_HOME = "$projectRoot/.tools/android-user"
    $env:GRADLE_USER_HOME = "$projectRoot/.tools/gradle-user"
    $gradle = "$projectRoot/.tools/gradle/gradle-8.11.1/bin/gradle.bat"
    & $gradle --no-daemon --console=plain @Tasks
    $buildExit = $LASTEXITCODE
} finally {
    Set-Location -LiteralPath $originalRoot
    if ($mappedDrive) { & subst.exe $mappedDrive /D }
}
exit $buildExit
