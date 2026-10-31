param(
    [string[]]$Tasks = @(':core:test', ':agent-runtime:testDebugUnitTest', ':app:testDebugUnitTest', ':app:assembleDebug', ':app:lintDebug'),
    [string]$Label = 'verification',
    [string]$VerificationRoot = ''
)
$ErrorActionPreference = 'Stop'
$sourceRoot = Split-Path -Parent $PSScriptRoot
if (-not $VerificationRoot) {
    $VerificationRoot = Join-Path $sourceRoot '.tmp/butler-verification'
}
$evidenceRoot = Join-Path $sourceRoot 'evidence/butler-upgrade-20261026'
New-Item -ItemType Directory -Force -Path $evidenceRoot | Out-Null
$VerificationRoot = [IO.Path]::GetFullPath($VerificationRoot)
if ($VerificationRoot -eq [IO.Path]::GetFullPath($sourceRoot)) { throw '验证副本不能指向源码目录' }
New-Item -ItemType Directory -Force -Path (Join-Path $VerificationRoot '.tools') | Out-Null
$buildLock = [IO.File]::Open((Join-Path $VerificationRoot '.tools/butler-build.lock'), [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$log = Join-Path $evidenceRoot "$Label-$stamp.log"
$startedAt = [DateTimeOffset]::Now.ToString('o')
$code = 1
try {
    foreach ($folder in @('app/src', 'app/libs', 'core/src', 'app/schemas', 'scripts', 'agent-runtime/src', 'agent-plugins', 'gradle')) {
        if (-not (Test-Path -LiteralPath (Join-Path $sourceRoot $folder))) { continue }
        & robocopy (Join-Path $sourceRoot $folder) (Join-Path $VerificationRoot $folder) /E /XJ /NFL /NDL /NJH /NJS /NP | Out-Null
        if ($LASTEXITCODE -gt 7) { throw "验证副本同步失败：$folder" }
    }
    # Generated runtime assets must match exactly; /E alone retains retired names.
    $runtimeAssetsSource = Join-Path $sourceRoot 'agent-runtime/src/main/assets/fish'
    if (Test-Path -LiteralPath $runtimeAssetsSource) {
        $runtimeAssetsTarget = [IO.Path]::GetFullPath((Join-Path $VerificationRoot 'agent-runtime/src/main/assets/fish'))
        $verificationPrefix = $VerificationRoot.TrimEnd([char[]]"\/") + [IO.Path]::DirectorySeparatorChar
        if (-not $runtimeAssetsTarget.StartsWith($verificationPrefix, [StringComparison]::OrdinalIgnoreCase)) { throw '运行资源同步目标不在验证副本中' }
        & robocopy $runtimeAssetsSource $runtimeAssetsTarget /MIR /XJ /NFL /NDL /NJH /NJS /NP | Out-Null
        if ($LASTEXITCODE -gt 7) { throw '运行资源精确同步失败' }
    }
    foreach ($file in @('app/build.gradle.kts', 'core/build.gradle.kts', 'agent-runtime/build.gradle.kts', 'build.gradle.kts', 'gradle.properties', 'settings.gradle.kts', 'gradlew.bat')) {
        if (-not (Test-Path -LiteralPath (Join-Path $sourceRoot $file))) { continue }
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent (Join-Path $VerificationRoot $file)) | Out-Null
        Copy-Item -LiteralPath (Join-Path $sourceRoot $file) -Destination (Join-Path $VerificationRoot $file) -Force
    }
    $flags = @('-Pkotlin.compiler.execution.strategy=in-process', '-Dorg.gradle.jvmargs=-Xmx3g -Dfile.encoding=GBK', '--no-parallel', '--max-workers=2')
    & (Join-Path $VerificationRoot 'scripts/build.ps1') -Tasks @($Tasks + $flags) *> $log
    $code = 0
} catch {
    $_ | Out-String | Add-Content -LiteralPath $log
} finally {
    $buildLock.Dispose()
    @{ startedAt = $startedAt; finishedAt = [DateTimeOffset]::Now.ToString('o'); tasks = $Tasks; exitCode = $code; log = $log; source = $sourceRoot; verificationRoot = $VerificationRoot } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $evidenceRoot "$Label-$stamp.json") -Encoding utf8
}
Write-Output "BUILD_EXIT=$code LOG=$log"
Get-Content -LiteralPath $log -Tail 24
exit $code
