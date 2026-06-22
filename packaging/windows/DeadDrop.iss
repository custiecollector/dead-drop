; Native Windows installer definition for DeadDrop Desktop.
;
; Build from a Windows PowerShell session with:
;
;   powershell -NoProfile -ExecutionPolicy Bypass -File packaging\windows\build-windows-native.ps1 -InstallInno
;
; The build script creates a jpackage app image with a bundled Java runtime,
; then invokes Inno Setup Compiler to produce a per-user installer .exe.

#ifndef MyAppName
#define MyAppName "DeadDrop Desktop"
#endif
#ifndef MyAppVersion
#define MyAppVersion "0.1.12"
#endif
#ifndef MyAppVersionNumeric
#define MyAppVersionNumeric "0.1.12.0"
#endif
#ifndef MyAppPublisher
#define MyAppPublisher "DeadDrop"
#endif
#ifndef MyAppExeName
#define MyAppExeName "DeadDrop Desktop.exe"
#endif
#ifndef SourceDir
#define SourceDir "..\..\build\windows-native\app-image\DeadDrop Desktop"
#endif
#ifndef OutputDir
#define OutputDir "..\..\build\windows-native\installer"
#endif

[Setup]
AppId={{6B0E79DF-0984-4F6E-B74B-4ED5A0A1C8C9}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName={localappdata}\Programs\DeadDrop Desktop
DefaultGroupName=DeadDrop Desktop
DisableProgramGroupPage=yes
OutputDir={#OutputDir}
OutputBaseFilename=DeadDrop-Desktop-{#MyAppVersion}-Setup
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
UninstallDisplayName={#MyAppName}
SetupIconFile=deaddrop.ico
UninstallDisplayIcon={app}\{#MyAppExeName}
VersionInfoVersion={#MyAppVersionNumeric}
VersionInfoCompany={#MyAppPublisher}
VersionInfoDescription={#MyAppName}
VersionInfoProductName={#MyAppName}
VersionInfoProductVersion={#MyAppVersionNumeric}

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\DeadDrop Desktop"; Filename: "{app}\{#MyAppExeName}"; WorkingDir: "{app}"
Name: "{autodesktop}\DeadDrop Desktop"; Filename: "{app}\{#MyAppExeName}"; WorkingDir: "{app}"; Tasks: desktopicon

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Additional shortcuts:"; Flags: unchecked

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "Launch DeadDrop Desktop"; Flags: nowait postinstall skipifsilent
