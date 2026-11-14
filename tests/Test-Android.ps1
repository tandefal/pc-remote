param(
    [string]$SdkPath = 'C:\Users\timti\AppData\Local\Android\Sdk',
    [string]$Device = 'emulator-5554',
    [string]$HostAddress = '10.0.2.2',
    [string]$JdkPath = 'C:\Program Files\Java\jdk-17'
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$dotnetExe = Join-Path $env:ProgramFiles 'dotnet\dotnet.exe'
$adbExe = Join-Path $SdkPath 'platform-tools\adb.exe'
$env:JAVA_HOME = $JdkPath
$env:ANDROID_HOME = $SdkPath
Push-Location $projectRoot
try {
    & $dotnetExe build tests\PCRemote.Checks -v:quiet
    if ($LASTEXITCODE -ne 0) { throw 'Windows checks build failed.' }
    Push-Location android
    try {
        & .\gradlew.bat --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest
        if ($LASTEXITCODE -ne 0) { throw 'Android test build failed.' }
    } finally { Pop-Location }
    & $adbExe -s $Device install -r android\app\build\outputs\apk\debug\app-debug.apk
    if ($LASTEXITCODE -ne 0) { throw 'App installation failed.' }
    & $adbExe -s $Device install -r android\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
    if ($LASTEXITCODE -ne 0) { throw 'Test installation failed.' }
    $artifactsPath = Join-Path $projectRoot 'artifacts'
    New-Item -ItemType Directory -Force -Path $artifactsPath | Out-Null
    $startedAt = [DateTime]::UtcNow
    $testProcess = Start-Process -FilePath $dotnetExe -ArgumentList 'tests\PCRemote.Checks\bin\Debug\net8.0-windows\PCRemote.Checks.dll','--android' -WindowStyle Hidden -WorkingDirectory $projectRoot -RedirectStandardOutput (Join-Path $artifactsPath 'integration.log') -RedirectStandardError (Join-Path $artifactsPath 'integration-error.log') -PassThru
    try {
        $sessionPath = Join-Path $artifactsPath 'checks-session.json'
        $ready = $false
        for ($attempt = 0; $attempt -lt 240; $attempt++) {
            if ($testProcess.HasExited) { throw 'Integration server failed; see artifacts/integration-error.log.' }
            if ((Test-Path -LiteralPath $sessionPath) -and (Get-Item -LiteralPath $sessionPath).LastWriteTimeUtc -ge $startedAt) { $ready = $true; break }
            Start-Sleep -Milliseconds 500
        }
        if (!$ready) { throw 'Integration server did not become ready.' }
        $session = Get-Content -Raw -LiteralPath $sessionPath | ConvertFrom-Json
        $output = & $adbExe -s $Device shell am instrument -w -r -e id $session.id -e host $HostAddress -e port $session.port -e cert $session.cert -e secret $session.secret dev.pcremote.app.test/androidx.test.runner.AndroidJUnitRunner
        $output
        if (($output -join "`n") -notmatch 'OK \(1 test\)') { throw 'Android integration test failed.' }
    } finally {
        # This object is the exact test process created above, never an application found by name.
        if (!$testProcess.HasExited) { $testProcess.Kill(); $testProcess.WaitForExit() }
        $testProcess.Dispose()
        if ($ready) { Remove-Item -LiteralPath $sessionPath }
    }
} finally { Pop-Location }
