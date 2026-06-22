param(
    [switch]$NoDesktopShortcut
)

$ErrorActionPreference = 'Stop'

$SourceDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$JarPath = Join-Path $SourceDir 'deaddrop-desktop.jar'
$LauncherPath = Join-Path $SourceDir 'deaddrop-windows.cmd'
$ReadmePath = Join-Path $SourceDir 'README-WINDOWS.txt'
$LicensePath = Join-Path $SourceDir 'LICENSE'

if (!(Test-Path $JarPath)) {
    throw "Missing deaddrop-desktop.jar next to installer. Run this from the unpacked DeadDrop Windows package."
}
if (!(Test-Path $LauncherPath)) {
    throw "Missing deaddrop-windows.cmd next to installer. Run this from the unpacked DeadDrop Windows package."
}

$JavaCommand = Get-Command java -ErrorAction SilentlyContinue
if ($null -eq $JavaCommand) {
    throw "Java 17 or newer is required. Install Eclipse Temurin/OpenJDK 17+ and run the installer again."
}

$JavaOutput = & java -version 2>&1 | Out-String
$JavaVersion = $null
if ($JavaOutput -match 'version\s+"([^"]+)"') {
    $JavaVersion = $Matches[1]
}
if ($null -ne $JavaVersion) {
    if ($JavaVersion.StartsWith('1.')) {
        $Major = [int]$JavaVersion.Split('.')[1]
    } else {
        $MajorText = ($JavaVersion.Split('.')[0] -replace '[^0-9].*$', '')
        $Major = [int]$MajorText
    }
    if ($Major -lt 17) {
        throw "Java 17 or newer is required. Found Java $JavaVersion."
    }
} else {
    Write-Warning "Could not parse Java version; continuing because java is present. Output: $JavaOutput"
}

$InstallRoot = Join-Path $env:LOCALAPPDATA 'DeadDrop'
$StartMenuDir = Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs\DeadDrop'
$DesktopDir = [Environment]::GetFolderPath('DesktopDirectory')

New-Item -ItemType Directory -Force -Path $InstallRoot | Out-Null
New-Item -ItemType Directory -Force -Path $StartMenuDir | Out-Null

Copy-Item -Force $JarPath (Join-Path $InstallRoot 'deaddrop-desktop.jar')
Copy-Item -Force $LauncherPath (Join-Path $InstallRoot 'deaddrop-windows.cmd')
Copy-Item -Force (Join-Path $SourceDir 'uninstall-deaddrop-windows.cmd') (Join-Path $InstallRoot 'uninstall-deaddrop-windows.cmd')
if (Test-Path $ReadmePath) { Copy-Item -Force $ReadmePath (Join-Path $InstallRoot 'README-WINDOWS.txt') }
if (Test-Path $LicensePath) { Copy-Item -Force $LicensePath (Join-Path $InstallRoot 'LICENSE') }

$WshShell = New-Object -ComObject WScript.Shell
$AppShortcut = $WshShell.CreateShortcut((Join-Path $StartMenuDir 'DeadDrop Desktop.lnk'))
$AppShortcut.TargetPath = Join-Path $InstallRoot 'deaddrop-windows.cmd'
$AppShortcut.WorkingDirectory = $InstallRoot
$AppShortcut.IconLocation = "$env:SystemRoot\System32\shell32.dll,44"
$AppShortcut.Save()

$UninstallShortcut = $WshShell.CreateShortcut((Join-Path $StartMenuDir 'Uninstall DeadDrop Desktop.lnk'))
$UninstallShortcut.TargetPath = Join-Path $InstallRoot 'uninstall-deaddrop-windows.cmd'
$UninstallShortcut.WorkingDirectory = $InstallRoot
$UninstallShortcut.IconLocation = "$env:SystemRoot\System32\shell32.dll,131"
$UninstallShortcut.Save()

if (-not $NoDesktopShortcut) {
    $DesktopShortcut = $WshShell.CreateShortcut((Join-Path $DesktopDir 'DeadDrop Desktop.lnk'))
    $DesktopShortcut.TargetPath = Join-Path $InstallRoot 'deaddrop-windows.cmd'
    $DesktopShortcut.WorkingDirectory = $InstallRoot
    $DesktopShortcut.IconLocation = "$env:SystemRoot\System32\shell32.dll,44"
    $DesktopShortcut.Save()
}

Write-Host "DeadDrop Desktop installed to: $InstallRoot"
Write-Host "Start menu shortcut: $StartMenuDir\DeadDrop Desktop.lnk"
if (-not $NoDesktopShortcut) { Write-Host "Desktop shortcut: $DesktopDir\DeadDrop Desktop.lnk" }
Write-Host "Vaults are stored separately under %USERPROFILE%\.config\deaddrop-desktop and are not removed by uninstall."
