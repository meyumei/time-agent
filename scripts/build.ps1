param(
    [string[]]$Tasks = @(':core:test', ':app:testDebugUnitTest', ':app:assembleDebug', ':app:lintDebug'),
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$AndroidSdk = $env:ANDROID_HOME
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$localConfig = Join-Path $projectRoot '.tools/environment.json'
if (Test-Path -LiteralPath $localConfig) {
    $config = Get-Content -LiteralPath $localConfig -Raw | ConvertFrom-Json
    if (-not $JavaHome) { $JavaHome = $config.javaHome }
    if (-not $AndroidSdk) { $AndroidSdk = $config.androidSdk }
}
if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe'))) {
    throw '请设置 JAVA_HOME 或使用 -JavaHome 指定 JDK 21。'
}
if (-not $AndroidSdk -or -not (Test-Path -LiteralPath $AndroidSdk)) {
    throw '请设置 ANDROID_HOME 或使用 -AndroidSdk 指定 Android SDK（API 35 / Build Tools 35.0.0）。'
}
$env:JAVA_HOME = $JavaHome
$env:ANDROID_HOME = $AndroidSdk
# Java does not automatically inherit HTTP(S)_PROXY. Apply unauthenticated local proxy only to this build.
if ($env:HTTPS_PROXY) {
    $buildProxy = [Uri]$env:HTTPS_PROXY
    if (-not $buildProxy.UserInfo) {
        $env:JAVA_TOOL_OPTIONS = ($env:JAVA_TOOL_OPTIONS + " -Dhttps.proxyHost=$($buildProxy.Host) -Dhttps.proxyPort=$($buildProxy.Port) -Dhttp.proxyHost=$($buildProxy.Host) -Dhttp.proxyPort=$($buildProxy.Port)").Trim()
    }
}
Push-Location -LiteralPath $projectRoot
try {
    & (Join-Path $projectRoot 'gradlew.bat') @Tasks --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle 失败，退出码 $LASTEXITCODE" }
} finally { Pop-Location }
