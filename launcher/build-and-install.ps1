# Windows version of build-and-install.sh: build the launcher APK and install it on the TV as the
# default home screen. Uses Android Studio's bundled JDK and the default SDK location unless
# JAVA_HOME / ANDROID_HOME are set.
#
# Some TVs have several apps that claim Home and the stock one outranks ours, so after installing this lists
# every package that claims Home, asks before disabling them (undo with tools\restore.ps1) and repeats until
# Home resolves to this launcher.
#
# Usage: launcher\build-and-install.ps1 -TvHost 192.168.1.50 [-Yes] [-StockLauncher <package>]
#   -Yes            don't ask before disabling the packages that claim Home
#   -StockLauncher  disable only this package in the first round (default: every other Home claimant)
param(
    [string]$TvHost = $env:TV_HOST,
    [string]$StockLauncher = '',
    [switch]$Yes
)
$ErrorActionPreference = 'Stop'
# Pipe plain UTF-8 to native commands; the default here prepends a BOM that corrupts the first command.
$OutputEncoding = New-Object System.Text.UTF8Encoding $false
if (-not $TvHost) { throw 'pass -TvHost <tv ip> or set $env:TV_HOST' }
$env:TV_HOST = $TvHost
Set-Location $PSScriptRoot

if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    $jbr = Get-ChildItem 'C:\Program Files\Android' -Directory -ErrorAction SilentlyContinue |
        ForEach-Object { Join-Path $_.FullName 'jbr' } | Where-Object { Test-Path "$_\bin\java.exe" } | Select-Object -First 1
    if (-not $jbr) { throw 'Set JAVA_HOME to a JDK 17+ (Android Studio ships one in its jbr folder)' }
    $env:JAVA_HOME = $jbr
}
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
if (-not (Test-Path local.properties)) {
    "sdk.dir=$($sdk -replace '\\', '\\')" | Out-File -Encoding ascii local.properties
}

.\gradlew.bat assembleDebug -q --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed' }

$py = '..\tools\venv\Scripts\python.exe'
$apk = 'app\build\outputs\apk\debug\app-debug.apk'
& $py -I ..\tools\b64push.py $apk /data/local/tmp/tvhome.apk
if ($LASTEXITCODE -ne 0) { throw 'APK upload failed' }
$tv = Join-Path $PSScriptRoot '..\tools\tv.py'
$me = 'com.gathin.tvhome'
$log = Join-Path $PSScriptRoot 'home-claimants-disabled.txt'   # read by tools\restore.ps1
function Tv([string]$cmd) { (& $py -I $tv $cmd) -join "`n" }
# The system fallback home and Settings must never be disabled: the TV needs them.
function Get-HomeClaimants {
    $out = Tv 'cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME'
    $out -split "`n" | ForEach-Object { if ($_ -match '^\s+([A-Za-z0-9_.]+)/') { $Matches[1] } } |
        Where-Object { $_ -notin @($me, 'android', 'com.android.tv.settings', 'com.android.settings') }
}
function Get-CurrentHome {
    ((Tv 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME') -split "`n" |
        Where-Object { $_.Trim() } | Select-Object -Last 1).Trim()
}

$out = Tv 'pm install -r /data/local/tmp/tvhome.apk; rm -f /data/local/tmp/tvhome.apk'
Write-Host $out
if ($out -notmatch 'Success') { throw 'pm install failed; nothing else was touched' }
Tv "pm grant $me android.permission.ACCESS_FINE_LOCATION 2>/dev/null; echo location-granted" | Write-Host
function Set-Home { Tv "cmd package set-home-activity --user 0 $me/.MainActivity" | Write-Host }
Set-Home

for ($round = 1; $round -le 5; $round++) {
    # Round 1 always runs: even when we already win, the stock launcher should not come back after an update.
    if ($round -gt 1 -and (Get-CurrentHome).StartsWith("$me/")) { break }
    $list = if ($round -eq 1 -and $StockLauncher) { @($StockLauncher) } else { @(Get-HomeClaimants) }
    if (-not $list) {
        if ((Get-CurrentHome).StartsWith("$me/")) { break }
        throw "No other app claims Home, yet Home resolves to $(Get-CurrentHome)."
    }
    Write-Host 'These packages claim Home and outrank, or could replace, the new launcher:'
    $list | ForEach-Object { Write-Host "  $_" }
    if (-not $Yes) {
        if ([Environment]::UserInteractive -and -not [Console]::IsInputRedirected) {
            $ans = Read-Host 'Disable them? Undo with tools\restore.ps1 [y/N]'
            if ($ans -notmatch '^[yY]') { throw "Skipped. The launcher is installed but Home still opens $(Get-CurrentHome)." }
        } else { throw 'Not interactive: re-run with -Yes to disable them.' }
    }
    foreach ($p in $list) {
        Tv "pm disable-user --user 0 $p" | Write-Host
        if (-not (Test-Path $log) -or -not (Select-String -Path $log -SimpleMatch -Pattern $p -Quiet)) {
            Add-Content -Path $log -Value $p -Encoding ascii
        }
    }
    Set-Home
}
$now = Get-CurrentHome
if (-not $now.StartsWith("$me/")) { throw "Home still resolves to $now. Re-enable with tools\restore.ps1." }
Write-Host "Installed. Home now opens $now. Press Home on the remote to see it."
