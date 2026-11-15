Unicode true
!include "MUI2.nsh"
!include "LogicLib.nsh"
!include "Sections.nsh"
!include "x64.nsh"
!include "WinVer.nsh"
!include "FileFunc.nsh"

!define APP_ID "Lixing.TaskbarLabelPicker"
!define APP_NAME "任务栏标签"
!define LAUNCHER "TaskbarLabelPickerLauncher.exe"
!define INSTALL_KEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\${APP_ID}"
!define RUN_KEY "Software\Microsoft\Windows\CurrentVersion\Run"
!define START_LINK "$SMPROGRAMS\${APP_NAME}.lnk"

Name "${APP_NAME}"
OutFile "${OUTPUT}"
InstallDir "$LOCALAPPDATA\Programs\TaskbarLabelPicker"
RequestExecutionLevel user
SetCompressor /SOLID lzma
ShowInstDetails show
ShowUninstDetails show
VIProductVersion "${VERSION}.0"
VIAddVersionKey /LANG=2052 "ProductName" "${APP_NAME}"
VIAddVersionKey /LANG=2052 "FileDescription" "${APP_NAME}安装程序"
VIAddVersionKey /LANG=2052 "FileVersion" "${VERSION}"
VIAddVersionKey /LANG=2052 "CompanyName" "砺行"
VIAddVersionKey /LANG=2052 "LegalCopyright" "砺行"

!define MUI_ICON "${PAYLOAD}/app/app/resources/taskbar-label-picker.ico"
!define MUI_UNICON "${PAYLOAD}/app/app/resources/taskbar-label-picker.ico"
!define MUI_FINISHPAGE_RUN "$INSTDIR\${LAUNCHER}"
!define MUI_FINISHPAGE_RUN_TEXT "打开任务栏标签"
!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_LICENSE "${PAYLOAD}/app/Notices/TaskbarLabelPicker-LICENSE.txt"
!insertmacro MUI_PAGE_COMPONENTS
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "SimpChinese"

Section "任务栏标签（必需）" Main
  SectionIn RO
  SetShellVarContext current
  ; Extract beside the live files so an interrupted extraction leaves the current version intact.
  RMDir /r "$INSTDIR\.incoming"
  SetOutPath "$INSTDIR\.incoming"
  ClearErrors
  File /r "${PAYLOAD}/*"
  IfErrors installFailed
  SetOutPath "$INSTDIR"
  IfFileExists "$INSTDIR\${LAUNCHER}" 0 switchFiles
    ExecWait '"$INSTDIR\${LAUNCHER}" --stop' $0
    StrCmp $0 0 switchFiles
    DetailPrint "无法关闭正在运行的任务栏标签。"
    Goto installFailed

switchFiles:
  ; Recover retired files left by a previously interrupted replacement.
  IfFileExists "$INSTDIR\app\TaskbarLabelPicker.exe" +2
    Rename "$INSTDIR\.previous\app" "$INSTDIR\app"
  IfFileExists "$INSTDIR\runtime\Windhawk\windhawk.exe" +2
    Rename "$INSTDIR\.previous\runtime" "$INSTDIR\runtime"
  IfFileExists "$INSTDIR\${LAUNCHER}" +2
    Rename "$INSTDIR\.previous\${LAUNCHER}" "$INSTDIR\${LAUNCHER}"
  RMDir /r "$INSTDIR\.previous"
  CreateDirectory "$INSTDIR\.previous"
  IfFileExists "$INSTDIR\app\TaskbarLabelPicker.exe" 0 saveRuntime
    ClearErrors
    Rename "$INSTDIR\app" "$INSTDIR\.previous\app"
    IfErrors restoreFiles
saveRuntime:
  IfFileExists "$INSTDIR\runtime\Windhawk\windhawk.exe" 0 saveLauncher
    ClearErrors
    Rename "$INSTDIR\runtime" "$INSTDIR\.previous\runtime"
    IfErrors restoreFiles
saveLauncher:
  IfFileExists "$INSTDIR\${LAUNCHER}" 0 moveApp
    ClearErrors
    Rename "$INSTDIR\${LAUNCHER}" "$INSTDIR\.previous\${LAUNCHER}"
    IfErrors restoreFiles
moveApp:
  ClearErrors
  Rename "$INSTDIR\.incoming\app" "$INSTDIR\app"
  IfErrors restoreFiles
  Rename "$INSTDIR\.incoming\runtime" "$INSTDIR\runtime"
  IfErrors restoreApp
  Rename "$INSTDIR\.incoming\${LAUNCHER}" "$INSTDIR\${LAUNCHER}"
  IfErrors restoreRuntime
  RMDir /r "$INSTDIR\.incoming"
  RMDir /r "$INSTDIR\.previous"
  WriteUninstaller "$INSTDIR\Uninstall.exe"
  CreateShortcut "${START_LINK}" "$INSTDIR\${LAUNCHER}" "" "$INSTDIR\app\TaskbarLabelPicker.exe"
  WriteRegStr HKCU "${INSTALL_KEY}" "DisplayName" "${APP_NAME}"
  WriteRegStr HKCU "${INSTALL_KEY}" "DisplayVersion" "${VERSION}"
  WriteRegStr HKCU "${INSTALL_KEY}" "Publisher" "砺行"
  WriteRegStr HKCU "${INSTALL_KEY}" "InstallLocation" "$INSTDIR"
  WriteRegStr HKCU "${INSTALL_KEY}" "DisplayIcon" "$INSTDIR\app\TaskbarLabelPicker.exe"
  WriteRegStr HKCU "${INSTALL_KEY}" "UninstallString" '"$INSTDIR\Uninstall.exe"'
  WriteRegDWORD HKCU "${INSTALL_KEY}" "NoModify" 1
  WriteRegDWORD HKCU "${INSTALL_KEY}" "NoRepair" 1
  Goto installDone

restoreRuntime:
  Rename "$INSTDIR\runtime" "$INSTDIR\.incoming\runtime"
restoreApp:
  Rename "$INSTDIR\app" "$INSTDIR\.incoming\app"
restoreFiles:
  Rename "$INSTDIR\.previous\app" "$INSTDIR\app"
  Rename "$INSTDIR\.previous\runtime" "$INSTDIR\runtime"
  Rename "$INSTDIR\.previous\${LAUNCHER}" "$INSTDIR\${LAUNCHER}"
installFailed:
  SetErrorLevel 1
  MessageBox MB_ICONSTOP "安装未完成。请关闭任务栏标签后重试。" /SD IDOK
  Abort
installDone:
SectionEnd

Section /o "登录时托盘启动" Autostart
  ExecWait '"$INSTDIR\${LAUNCHER}" --autostart-enable' $0
  StrCmp $0 0 +2
    MessageBox MB_ICONEXCLAMATION "开机启动未设置。请在应用内检查当前启动项。" /SD IDOK
SectionEnd

Function .onInit
  System::Call 'kernel32::CreateMutexW(p 0, i 0, w "Local\Lixing.TaskbarLabelPicker.Setup") p .r9 ?e'
  Pop $0
  ${If} $9 == 0
  ${OrIf} $0 == 183
    SetErrorLevel 1
    Abort
  ${EndIf}
  ${IfNot} ${IsNativeAMD64}
  ${OrIfNot} ${AtLeastWin11}
    MessageBox MB_ICONSTOP "任务栏标签需要 64 位 Windows 11。" /SD IDOK
    Abort
  ${EndIf}
  ReadRegStr $0 HKCU "${RUN_KEY}" "${APP_ID}"
  StrCmp $0 '"$INSTDIR\${LAUNCHER}" --tray' 0 initialAutostartDone
    !insertmacro SelectSection ${Autostart}
initialAutostartDone:
FunctionEnd

Function .onInstSuccess
  ${IfNot} ${SectionIsSelected} ${Autostart}
    ExecWait '"$INSTDIR\${LAUNCHER}" --autostart-disable' $0
    StrCmp $0 1 0 +2
      MessageBox MB_ICONEXCLAMATION "未能保存开机启动设置，请在应用内确认。" /SD IDOK
  ${EndIf}
  ${GetParameters} $0
  ClearErrors
  ${GetOptions} $0 "/UPDATE" $1
  ${IfNot} ${Errors}
    Exec '"$INSTDIR\${LAUNCHER}" --tray'
  ${EndIf}
FunctionEnd

Section "Uninstall"
  SetShellVarContext current
  IfFileExists "$INSTDIR\${LAUNCHER}" 0 remove
    ExecWait '"$INSTDIR\${LAUNCHER}" --stop' $0
    StrCmp $0 0 +4
      SetErrorLevel 1
      MessageBox MB_ICONSTOP "请先关闭任务栏标签，然后重试卸载。" /SD IDOK
      Abort
remove:
  ReadRegStr $0 HKCU "${RUN_KEY}" "${APP_ID}"
  StrCmp $0 '"$INSTDIR\${LAUNCHER}" --tray' 0 +2
    DeleteRegValue HKCU "${RUN_KEY}" "${APP_ID}"
  Delete "${START_LINK}"
  DeleteRegKey HKCU "${INSTALL_KEY}"
  RMDir /r "$INSTDIR\app"
  RMDir /r "$INSTDIR\runtime"
  RMDir /r "$INSTDIR\.incoming"
  RMDir /r "$INSTDIR\.previous"
  Delete "$INSTDIR\${LAUNCHER}"
  Delete "$INSTDIR\Uninstall.exe"
  RMDir "$INSTDIR"
SectionEnd
