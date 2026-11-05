param(
    [Parameter(Mandatory = $true)][string]$ApkPath,
    [Parameter(Mandatory = $true)][string]$ApkUrl,
    [string]$Notes = "MusicBox 更新"
)

$apk = (Resolve-Path -LiteralPath $ApkPath).Path
$address = [uri]$ApkUrl
if ($address.Scheme -ne 'https') { throw 'APK 下载地址必须使用 HTTPS。' }
$gradle = Get-Content -LiteralPath (Join-Path $PSScriptRoot '../app/build.gradle') -Raw
$code = [regex]::Match($gradle, 'versionCode\s+(\d+)')
$name = [regex]::Match($gradle, "versionName\s+'([^']+)'")
if (-not $code.Success -or -not $name.Success) { throw '无法读取应用版本号。' }
$manifest = [ordered]@{
    versionCode = [int]$code.Groups[1].Value
    versionName = $name.Groups[1].Value
    apkUrl = $address.AbsoluteUri
    sha256 = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
    sizeBytes = (Get-Item -LiteralPath $apk).Length
    notes = $Notes
}
$target = Join-Path $PSScriptRoot '../update.json'
[System.IO.File]::WriteAllText($target, (($manifest | ConvertTo-Json -Depth 3) + "`n"), [System.Text.UTF8Encoding]::new($false))
Write-Output "已生成 $target（$($manifest.versionName)）"
