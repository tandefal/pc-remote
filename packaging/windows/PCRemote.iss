#ifndef AppVersion
  #define AppVersion "1.0.2"
#endif
#ifndef PublishDir
  #define PublishDir "..\..\artifacts\windows"
#endif

[Setup]
AppId={{8C4E3A26-80A4-4E18-9726-B79D9C798584}
AppName=PC Remote
AppVersion={#AppVersion}
AppPublisher=tandefal
AppPublisherURL=https://github.com/tandefal/pc-remote
DefaultDirName={localappdata}\Programs\PC Remote
DefaultGroupName=PC Remote
DisableDirPage=no
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0
OutputDir=..\..\artifacts\release
OutputBaseFilename=PCRemote-Windows-{#AppVersion}-Setup
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
UninstallDisplayIcon={app}\PCRemote.exe
AppMutex=Local\PCRemote-8C4E3A26
CloseApplications=no
RestartApplications=no
SetupLogging=no

[Languages]
Name: "russian"; MessagesFile: "compiler:Languages\Russian.isl"

[Tasks]
Name: "desktopicon"; Description: "Создать ярлык на рабочем столе"; Flags: unchecked

[Files]
Source: "{#PublishDir}\*"; DestDir: "{app}"; Excludes: "*.pdb"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\PC Remote"; Filename: "{app}\PCRemote.exe"
Name: "{autodesktop}\PC Remote"; Filename: "{app}\PCRemote.exe"; Tasks: desktopicon

[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueName: "PC Remote"; Flags: uninsdeletevalue

[Run]
Filename: "{app}\PCRemote.exe"; Description: "Запустить PC Remote"; Flags: nowait postinstall skipifsilent

[Code]
procedure CurStepChanged(CurStep: TSetupStep);
var
  Existing: String;
begin
  if CurStep = ssPostInstall then
    if RegQueryStringValue(HKCU, 'Software\Microsoft\Windows\CurrentVersion\Run', 'PC Remote', Existing) then
      RegWriteStringValue(HKCU, 'Software\Microsoft\Windows\CurrentVersion\Run', 'PC Remote', '"' + ExpandConstant('{app}\PCRemote.exe') + '" --background');
end;
