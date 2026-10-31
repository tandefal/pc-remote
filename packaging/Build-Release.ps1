param(
    [string]$Version = '1.0.3',
    [int]$VersionCode = 4,
    [string]$JdkPath = 'C:\Program Files\Java\jdk-17',
    [string]$InnoCompiler = '',
    [switch]$InitializeSigningKey
)
$ErrorActionPreference = 'Stop'
if ($Version -notmatch '^\d+\.\d+\.\d+$' -or $VersionCode -lt 1) { throw 'Укажите версию X.Y.Z и положительный VersionCode.' }
$projectRoot = Split-Path -Parent $PSScriptRoot
$signingDirectory = Join-Path $projectRoot 'artifacts\signing'
$signingConfig = Join-Path $signingDirectory 'credentials.xml'
$keyPath = Join-Path $signingDirectory 'pcremote-release.jks'
$releaseDirectory = Join-Path $projectRoot 'artifacts\release'
New-Item -ItemType Directory -Force $signingDirectory, $releaseDirectory | Out-Null
if ($InitializeSigningKey) {
    if ((Test-Path $keyPath) -or (Test-Path $signingConfig)) { throw 'Ключ или его настройки уже существуют. Перезапись запрещена.' }
    $password = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
    $env:PCREMOTE_KEY_PASSWORD = $password
    try {
        & "$JdkPath\bin\keytool.exe" -genkeypair -keystore $keyPath -storetype JKS -alias pcremote -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=PC Remote' -storepass:env PCREMOTE_KEY_PASSWORD -keypass:env PCREMOTE_KEY_PASSWORD
        if ($LASTEXITCODE -ne 0) { throw 'Не удалось создать ключ подписи.' }
        [pscredential]::new('pcremote', (ConvertTo-SecureString $password -AsPlainText -Force)) | Export-Clixml $signingConfig
    } finally { Remove-Item Env:PCREMOTE_KEY_PASSWORD -ErrorAction SilentlyContinue; $password = $null }
}
if (!(Test-Path $keyPath) -or !(Test-Path $signingConfig)) { throw 'Нет постоянного ключа. Для первого выпуска добавьте -InitializeSigningKey.' }
if (!$InnoCompiler) {
    $candidates = @("${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe", "$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe", "$projectRoot\artifacts\tools\inno\ISCC.exe")
    $InnoCompiler = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
}
if (!$InnoCompiler -or !(Test-Path $InnoCompiler)) { throw 'Нужен Inno Setup 6. Укажите -InnoCompiler с путём к ISCC.exe.' }
Push-Location $projectRoot
try {
    $publishDirectory = Join-Path $projectRoot ("artifacts\publish-$Version-" + [Guid]::NewGuid().ToString('N'))
    & dotnet publish windows\PCRemote.Windows\PCRemote.Windows.csproj -c Release -r win-x64 --self-contained true -p:Version=$Version -o $publishDirectory
    if ($LASTEXITCODE -ne 0) { throw 'Ошибка сборки Windows.' }
    & $InnoCompiler "/DAppVersion=$Version" "/DPublishDir=$publishDirectory" packaging\windows\PCRemote.iss
    if ($LASTEXITCODE -ne 0) { throw 'Ошибка сборки установщика.' }
    $credential = Import-Clixml $signingConfig
    $env:PCREMOTE_KEYSTORE = $keyPath
    $env:PCREMOTE_STORE_PASSWORD = $credential.GetNetworkCredential().Password
    $env:PCREMOTE_KEY_PASSWORD = $env:PCREMOTE_STORE_PASSWORD
    $env:JAVA_HOME = $JdkPath
    Push-Location android
    try {
        & .\gradlew.bat --no-daemon :app:assembleRelease :app:lintRelease "-PreleaseVersion=$Version" "-PreleaseVersionCode=$VersionCode"
        if ($LASTEXITCODE -ne 0) { throw 'Ошибка сборки или lint Android.' }
    } finally { Pop-Location }
    Copy-Item android\app\build\outputs\apk\release\app-release.apk (Join-Path $releaseDirectory "PCRemote-Android-$Version.apk")
    $assetPaths = @((Join-Path $releaseDirectory "PCRemote-Android-$Version.apk"), (Join-Path $releaseDirectory "PCRemote-Windows-$Version-Setup.exe"))
    $lines = $assetPaths | ForEach-Object { $hash = Get-FileHash -Algorithm SHA256 -LiteralPath $_; "$($hash.Hash.ToLowerInvariant())  $([IO.Path]::GetFileName($_))" }
    $lines | Set-Content -Encoding ascii (Join-Path $releaseDirectory "SHA256SUMS-$Version.txt")
    Write-Host "Готовые файлы: $releaseDirectory"
    Write-Host "Сохраните резервную копию $signingDirectory. credentials.xml читается только этим пользователем Windows."
} finally {
    'PCREMOTE_KEYSTORE','PCREMOTE_STORE_PASSWORD','PCREMOTE_KEY_PASSWORD' | ForEach-Object { Remove-Item "Env:$_" -ErrorAction SilentlyContinue }
    Pop-Location
}
