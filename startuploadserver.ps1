[CmdletBinding()]
param(
    [string]$SaveDir,
    [switch]$InstallStartupTask,
    [switch]$UninstallStartupTask,
    [switch]$ServerOnly
)

$ErrorActionPreference = 'Stop'
$Root = $PSScriptRoot
$TaskName = 'PhotoUploadServer'
$Port = 8080
$PythonPath = Join-Path $Root '.winvenv\Scripts\python.exe'
$MisspelledPythonPath = Join-Path $Root '.winvevn\Scripts\python.exe'

function ConvertTo-QuotedArgument {
    param([string]$Value)
    $Escaped = [regex]::Replace($Value, '(\\*)"', '$1$1\"')
    $Escaped = [regex]::Replace($Escaped, '(\\+)$', '$1$1')
    return ('"{0}"' -f $Escaped)
}

if ($InstallStartupTask -and $UninstallStartupTask) {
    throw 'Use only one of -InstallStartupTask and -UninstallStartupTask.'
}

if ($UninstallStartupTask) {
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction SilentlyContinue
    Write-Host "Removed logon startup task: $TaskName"
    exit 0
}

if ([string]::IsNullOrWhiteSpace($SaveDir)) {
    $SaveDir = Join-Path $Root 'uploads'
}
$SaveDir = [System.IO.Path]::GetFullPath($SaveDir)

if ($InstallStartupTask) {
    $PowerShellExe = (Get-Process -Id $PID).Path
    $ScriptPathArg = ConvertTo-QuotedArgument (Join-Path $Root 'startuploadserver.ps1')
    $SaveDirArg = ConvertTo-QuotedArgument $SaveDir
    $Arguments = "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File $ScriptPathArg -ServerOnly -SaveDir $SaveDirArg"
    $Action = New-ScheduledTaskAction -Execute $PowerShellExe -Argument $Arguments
    $Trigger = New-ScheduledTaskTrigger -AtLogOn -User ([System.Security.Principal.WindowsIdentity]::GetCurrent().Name)
    $Principal = New-ScheduledTaskPrincipal -UserId ([System.Security.Principal.WindowsIdentity]::GetCurrent().Name) -LogonType Interactive -RunLevel Limited
    $Settings = New-ScheduledTaskSettingsSet -StartWhenAvailable
    Register-ScheduledTask -TaskName $TaskName -Action $Action -Trigger $Trigger -Principal $Principal -Settings $Settings -Force | Out-Null
    Write-Host "Startup task registered for the current user. Save directory: $SaveDir"
    exit 0
}

New-Item -ItemType Directory -Force -Path $SaveDir | Out-Null
$ServerScript = Join-Path $Root 'server\upload_server.py'
$LogPath = Join-Path $Root 'server\server.log'
$ErrorLogPath = Join-Path $Root 'server\server-error.log'
New-Item -ItemType Directory -Force -Path (Split-Path $LogPath) | Out-Null

$Listener = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue | Select-Object -First 1
if ($Listener) {
    Write-Host "Port $Port is already listening; reusing the existing service."
} else {
    if (-not (Test-Path -LiteralPath $PythonPath) -and (Test-Path -LiteralPath $MisspelledPythonPath)) {
        $PythonPath = $MisspelledPythonPath
        Write-Warning "Using existing environment at '.winvevn'. Rename it to '.winvenv' to match the documented name."
    }
    if (-not (Test-Path -LiteralPath $PythonPath)) {
        throw "Virtual environment interpreter was not found: $PythonPath. Create the .winvenv environment in the project directory first."
    }
    $Arguments = '-u {0} --host 0.0.0.0 --port {1} --dir {2}' -f `
        (ConvertTo-QuotedArgument $ServerScript), $Port, (ConvertTo-QuotedArgument $SaveDir)
    $Process = Start-Process -FilePath $PythonPath -ArgumentList $Arguments `
        -WorkingDirectory $Root -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $LogPath -RedirectStandardError $ErrorLogPath

    $Ready = $false
    for ($Attempt = 0; $Attempt -lt 20; $Attempt++) {
        Start-Sleep -Milliseconds 250
        if ($Process.HasExited) {
            throw "Upload server failed to start. See log: $ErrorLogPath"
        }
        try {
            $Health = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/health" -TimeoutSec 1
            if ($Health.ok) {
                $Ready = $true
                break
            }
        } catch {
            # The server may need a moment to bind the port.
        }
    }
    if (-not $Ready) {
        throw "Upload server did not become ready on port $Port. See log: $ErrorLogPath"
    }
}

$UdpClient = [System.Net.Sockets.UdpClient]::new()
try {
    $UdpClient.Connect('1.1.1.1', 80)
    $LanIp = ([System.Net.IPEndPoint]$UdpClient.Client.LocalEndPoint).Address.IPAddressToString
} finally {
    $UdpClient.Dispose()
}
if ([string]::IsNullOrWhiteSpace($LanIp) -or $LanIp.StartsWith('127.')) {
    throw 'Could not determine the LAN IPv4 address. Check the network connection.'
}
$ServerUrl = "http://${LanIp}:$Port/upload"
Write-Host "LAN upload URL: $ServerUrl"
Write-Host "Photo save directory: $SaveDir"

if ($ServerOnly) {
    exit 0
}

$Adb = Get-Command adb.exe -ErrorAction SilentlyContinue
if (-not $Adb) {
    Write-Host 'ADB not found. The upload server is running; enter the URL above in the Android app.'
    exit 0
}

$Device = (& $Adb.Source devices | Select-String '^\S+\s+device$' | Select-Object -First 1)
if (-not $Device) {
    Write-Host 'No Android device connected. The upload server is running; enter the URL above in the Android app.'
    exit 0
}
$DeviceId = ($Device.Line -split '\s+')[0]
$Package = 'com.local.photoupload'
$Installed = (& $Adb.Source -s $DeviceId shell pm path $Package 2>$null) -match '^package:'
if (-not $Installed) {
    $Apk = Join-Path $Root 'android\app\build\outputs\apk\debug\app-debug.apk'
    if (-not (Test-Path $Apk)) {
        $GradleWrapper = Join-Path $Root 'android\gradlew.bat'
        $Gradle = Get-Command gradle.bat -ErrorAction SilentlyContinue
        if (Test-Path $GradleWrapper) {
            & $GradleWrapper -p (Join-Path $Root 'android') assembleDebug --no-daemon
        } elseif ($Gradle) {
            & $Gradle.Source -p (Join-Path $Root 'android') assembleDebug --no-daemon
        } else {
            Write-Warning 'The app is not installed, and neither gradlew.bat nor system Gradle was found. Build the APK in Android Studio first.'
            exit 0
        }
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle build failed with exit code $LASTEXITCODE."
        }
    }
    if (-not (Test-Path $Apk)) {
        throw "APK not found: $Apk"
    }
    & $Adb.Source -s $DeviceId install -r $Apk
    if ($LASTEXITCODE -ne 0) {
        throw "ADB install failed with exit code $LASTEXITCODE."
    }
}

& $Adb.Source -s $DeviceId shell pm grant $Package android.permission.CAMERA 2>$null
& $Adb.Source -s $DeviceId shell input keyevent KEYCODE_WAKEUP 2>$null
& $Adb.Source -s $DeviceId shell am start -n "$Package/.MainActivity" --es server_url $ServerUrl
if ($LASTEXITCODE -ne 0) {
    throw "Could not launch the Android app. ADB exit code: $LASTEXITCODE"
}
Write-Host 'Opened the photo upload app and supplied the LAN upload URL.'
