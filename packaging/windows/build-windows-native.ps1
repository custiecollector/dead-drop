param(
    [string]$JdkHome = $env:JAVA_HOME,
    [string]$ZxingCoreJar = $env:ZXING_CORE_JAR,
    [string]$InnoDir = "$env:LOCALAPPDATA\Programs\Inno Setup 6",
    [string]$InnoSetupUrl = 'https://github.com/jrsoftware/issrc/releases/download/is-6_7_3/innosetup-6.7.3.exe',
    [switch]$InstallInno
)

$ErrorActionPreference = 'Stop'

function Resolve-RepoRoot {
    $scriptDir = Split-Path -Parent $PSCommandPath
    return (Resolve-Path (Join-Path $scriptDir '..\..')).Path
}

function Resolve-JavaTool {
    param([string]$Name)
    $candidates = @()
    if ($JdkHome) { $candidates += (Join-Path $JdkHome "bin\$Name") }
    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($command) { $candidates += $command.Source }
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path $candidate)) { return (Resolve-Path $candidate).Path }
    }
    throw "Could not find $Name. Set JAVA_HOME to a JDK 17+ directory."
}

function Resolve-ZxingJar {
    param([string]$RepoRoot)
    $candidates = @()
    if ($ZxingCoreJar) { $candidates += $ZxingCoreJar }
    $userProfile = $env:USERPROFILE
    if ($userProfile) {
        $candidates += Get-ChildItem -Path (Join-Path $userProfile '.gradle\caches\modules-2\files-2.1\com.google.zxing\core') -Filter 'core-*.jar' -Recurse -ErrorAction SilentlyContinue | Sort-Object FullName | Select-Object -ExpandProperty FullName
    }
    $candidates += Join-Path $RepoRoot 'tools\zxing-core-3.5.3.jar'
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path $candidate)) { return (Resolve-Path $candidate).Path }
    }
    throw 'ZXing core jar not found. Run the Android Gradle build once, download core-3.5.3.jar, or pass -ZxingCoreJar.'
}

function Resolve-InnoCompiler {
    param([string]$RepoRoot)
    $command = Get-Command iscc.exe -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }

    $candidates = @(
        (Join-Path $InnoDir 'ISCC.exe'),
        "$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe",
        "$env:ProgramFiles\Inno Setup 6\ISCC.exe",
        "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe"
    )
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path $candidate)) { return (Resolve-Path $candidate).Path }
    }

    if (-not $InstallInno) { return $null }

    New-Item -ItemType Directory -Force -Path $InnoDir | Out-Null

    $winget = Get-Command winget.exe -ErrorAction SilentlyContinue
    if ($winget) {
        Write-Host "Installing Inno Setup with winget into $InnoDir"
        & $winget.Source install --id JRSoftware.InnoSetup -e --accept-package-agreements --accept-source-agreements --scope user --location $InnoDir --silent
        if ($LASTEXITCODE -ne 0) { throw "winget failed installing Inno Setup with exit code $LASTEXITCODE" }
    } else {
        $downloadDir = Join-Path (Join-Path $RepoRoot 'build') 'windows-native\downloads'
        New-Item -ItemType Directory -Force -Path $downloadDir | Out-Null
        $installer = Join-Path $downloadDir 'innosetup.exe'
        Write-Host "Downloading Inno Setup to $installer"
        & curl.exe -L --fail --retry 3 -sS --output $installer $InnoSetupUrl
        if ($LASTEXITCODE -ne 0) { throw "curl failed downloading Inno Setup with exit code $LASTEXITCODE" }

        Write-Host "Installing Inno Setup to $InnoDir"
        $installProcess = Start-Process -FilePath $installer -ArgumentList @('/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART','/CURRENTUSER','/NOICONS',"/DIR=$InnoDir") -PassThru -Wait
        if ($installProcess.ExitCode -ne 0) { throw "Inno Setup installer failed with exit code $($installProcess.ExitCode)" }
    }

    $iscc = Join-Path $InnoDir 'ISCC.exe'
    if (Test-Path $iscc) { return (Resolve-Path $iscc).Path }
    throw "Inno Setup install completed but ISCC.exe was not found at $iscc"
}

function Get-VersionName {
    param([string]$RepoRoot)
    $gradle = Get-Content (Join-Path $RepoRoot 'app\build.gradle') -Raw
    if ($gradle -notmatch "versionName\s+'([^']+)'") { throw 'versionName not found in app/build.gradle' }
    return $Matches[1]
}

function Get-NumericVersion {
    param([string]$VersionName)
    if ($VersionName -match '(\d+\.\d+\.\d+)') { return $Matches[1] }
    return '0.0.0'
}

$repoRoot = Resolve-RepoRoot
$versionName = Get-VersionName $repoRoot
$numericVersion = Get-NumericVersion $versionName
$innoVersion = "$numericVersion.0"
$buildRoot = Join-Path $repoRoot 'build'
$classes = Join-Path $buildRoot 'desktop\classes'
$jarPath = Join-Path $buildRoot 'deaddrop-desktop.jar'
$legacyJarPath = Join-Path $buildRoot 'deaddrop-linux-gui.jar'
$nativeRoot = Join-Path $buildRoot 'windows-native'
$inputDir = Join-Path $nativeRoot 'input'
$appImageRoot = Join-Path $nativeRoot 'app-image'
$appDir = Join-Path $appImageRoot 'DeadDrop Desktop'
$installerDir = Join-Path $nativeRoot 'installer'
$iconPath = Join-Path $repoRoot 'packaging\windows\deaddrop.ico'
$issPath = Join-Path $repoRoot 'packaging\windows\DeadDrop.iss'

$javac = Resolve-JavaTool 'javac.exe'
$jarTool = Resolve-JavaTool 'jar.exe'
$jpackage = Resolve-JavaTool 'jpackage.exe'
$zxing = Resolve-ZxingJar $repoRoot

Write-Host "DeadDrop Windows native build"
Write-Host "repo=$repoRoot"
Write-Host "version=$versionName"
Write-Host "javac=$javac"
Write-Host "jpackage=$jpackage"
Write-Host "zxing=$zxing"

Remove-Item -Recurse -Force $classes, $inputDir, $appImageRoot, $installerDir -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $classes, $inputDir, $installerDir | Out-Null

$fieldPacketCoreSources = Get-ChildItem -Path (Join-Path $repoRoot 'desktop\src\org\fieldpacket\core') -Filter '*.java' | Sort-Object FullName | Select-Object -ExpandProperty FullName
$sources = @(
    'app\src\main\java\org\deaddrop\app\DeadDropCrypto.java',
    'app\src\main\java\org\deaddrop\app\AudioDiagnostics.java',
    'app\src\main\java\org\deaddrop\app\AudioModem.java',
    'desktop\src\org\deaddrop\desktop\FieldPacketToolsPanel.java',
    'desktop\src\org\deaddrop\desktop\DeadDropDesktopGui.java'
) | ForEach-Object { Join-Path $repoRoot $_ }
$sources = @($sources) + @($fieldPacketCoreSources)

& $javac -encoding UTF-8 -cp $zxing -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE" }

Push-Location $classes
try {
    & $jarTool --extract --file $zxing
    if ($LASTEXITCODE -ne 0) { throw "jar extract failed with exit code $LASTEXITCODE" }
} finally {
    Pop-Location
}

New-Item -ItemType Directory -Force -Path $buildRoot | Out-Null
& $jarTool --create --file $jarPath --main-class org.deaddrop.desktop.DeadDropDesktopGui -C $classes .
if ($LASTEXITCODE -ne 0) { throw "jar create failed with exit code $LASTEXITCODE" }
Copy-Item -Force $jarPath $legacyJarPath
Copy-Item -Force $jarPath (Join-Path $inputDir 'deaddrop-desktop.jar')
Copy-Item -Force (Join-Path $repoRoot 'packaging\windows\deaddrop-wasapi-loopback.ps1') (Join-Path $inputDir 'deaddrop-wasapi-loopback.ps1')


$jpackageArgs = @(
    '--type', 'app-image',
    '--name', 'DeadDrop Desktop',
    '--vendor', 'DeadDrop',
    '--app-version', $numericVersion,
    '--input', $inputDir,
    '--main-jar', 'deaddrop-desktop.jar',
    '--main-class', 'org.deaddrop.desktop.DeadDropDesktopGui',
    '--dest', $appImageRoot,
    '--java-options', '-Dfile.encoding=UTF-8',
    '--add-modules', 'java.desktop,java.logging,java.prefs,jdk.crypto.ec'
)
if (Test-Path $iconPath) { $jpackageArgs += @('--icon', $iconPath) }

Write-Host 'Creating native Windows app image'
& $jpackage @jpackageArgs
if ($LASTEXITCODE -ne 0) { throw "jpackage app-image failed with exit code $LASTEXITCODE" }

Copy-Item -Force (Join-Path $repoRoot 'LICENSE') $appDir
Copy-Item -Force (Join-Path $repoRoot 'packaging\windows\README-WINDOWS.txt') $appDir

$exe = Join-Path $appDir 'DeadDrop Desktop.exe'
if (-not (Test-Path $exe)) { throw "Native launcher was not created: $exe" }

$iscc = Resolve-InnoCompiler $repoRoot
$setupExe = $null
if ($iscc) {
    Write-Host "Building Inno Setup installer with $iscc"
    & $iscc "/DSourceDir=$appDir" "/DOutputDir=$installerDir" "/DMyAppVersion=$versionName" "/DMyAppVersionNumeric=$innoVersion" $issPath
    if ($LASTEXITCODE -ne 0) { throw "Inno Setup compiler failed with exit code $LASTEXITCODE" }
    $setupExe = Join-Path $installerDir "DeadDrop-Desktop-$versionName-Setup.exe"
    if (-not (Test-Path $setupExe)) { throw "Expected installer not found: $setupExe" }
} else {
    Write-Warning 'Inno Setup compiler not found. App image was built, but installer .exe was not. Re-run with -InstallInno or install Inno Setup 6.'
}

$zipPath = Join-Path $buildRoot "deaddrop-$versionName-desktop-windows-native.zip"
Remove-Item -Force $zipPath -ErrorAction SilentlyContinue
Compress-Archive -Path $appDir -DestinationPath $zipPath -Force

$sumPath = Join-Path $buildRoot "SHA256SUMS-$versionName-windows-native.txt"
$hashLines = @()
$hashLines += "$((Get-FileHash $jarPath -Algorithm SHA256).Hash.ToLower())  deaddrop-desktop.jar"
$hashLines += "$((Get-FileHash $exe -Algorithm SHA256).Hash.ToLower())  DeadDrop Desktop.exe"
$hashLines += "$((Get-FileHash $zipPath -Algorithm SHA256).Hash.ToLower())  $(Split-Path $zipPath -Leaf)"
if ($setupExe) { $hashLines += "$((Get-FileHash $setupExe -Algorithm SHA256).Hash.ToLower())  $(Split-Path $setupExe -Leaf)" }
Set-Content -Path $sumPath -Value $hashLines -Encoding ascii

Write-Host 'Created:'
Write-Host "  $jarPath"
Write-Host "  $exe"
Write-Host "  $zipPath"
if ($setupExe) { Write-Host "  $setupExe" }
Write-Host "  $sumPath"
