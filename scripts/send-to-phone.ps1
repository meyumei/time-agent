param([string]$Apk = 'outputs/time-agent-0.4.0-debug.apk')
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$source = (Resolve-Path -LiteralPath (Join-Path $taskRoot $Apk)).Path
$name = [System.IO.Path]::GetFileName($source)
if ($name -notmatch '^time-agent-[0-9.]+-debug\.apk$') { throw 'Only a named Time Agent APK can be sent.' }
$identity = Join-Path $taskRoot '.tools/phone-transfer/id_ed25519'
if (-not (Test-Path -LiteralPath $identity)) { throw 'The authorized local delivery identity is missing.' }
$sshOptions = @('-i', $identity, '-o', 'IdentitiesOnly=yes', '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=yes', '-o', 'ConnectTimeout=15', '-o', 'ServerAliveInterval=10', '-o', 'ServerAliveCountMax=3')
$target = 'u0_a451@100.85.154.39'
$remotePath = "/sdcard/Download/$name"
$hash = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()
$batchPath = Join-Path $taskRoot '.tools/phone-transfer/upload.sftp'
[System.IO.File]::WriteAllText($batchPath, ('reput "' + $source.Replace('\','/') + '" "' + $remotePath + '"' + "`n"), [System.Text.UTF8Encoding]::new($false))
& ssh @sshOptions -p 8022 $target 'termux-wake-lock'
if ($LASTEXITCODE) { throw 'Phone connection is unavailable.' }
try {
    # Resume only this named delivery file; compression saves bandwidth for stored native libraries.
    & sftp @sshOptions -C -b $batchPath -P 8022 $target
    if ($LASTEXITCODE) { throw 'Phone transfer paused or failed; its partial file can be resumed.' }
    $remoteOutput = & ssh @sshOptions -p 8022 $target "sha256sum '$remotePath'"
    if ($LASTEXITCODE) { throw 'Phone hash verification failed.' }
} finally {
    & ssh @sshOptions -p 8022 $target 'termux-wake-unlock'
}
$remoteHash = ([string]$remoteOutput -split '\s+')[0].ToLowerInvariant()
if ($hash -ne $remoteHash) { throw 'Phone APK hash differs from the verified local file.' }
$result = [ordered]@{status='delivered_and_hash_verified'; device='Redmi K70 Ultra'; method='Tailscale + Termux SSH'; phonePath=$remotePath; bytes=(Get-Item -LiteralPath $source).Length; sha256=$hash; remoteSha256=$remoteHash; deliveredAt=(Get-Date).ToString('o'); installed=$false}
$reportDirectory = Join-Path $taskRoot 'outputs/v0.4.0'
New-Item -ItemType Directory -Path $reportDirectory -Force | Out-Null
$result | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $reportDirectory 'phone-delivery.json') -Encoding utf8
$result | ConvertTo-Json
