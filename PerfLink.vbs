' PerfLink launcher (used by the Start menu / desktop shortcuts).
' Starts PerfLink in the background with its tray icon, without a console window:
'   1. through the "PerfLink" scheduled task (runs as administrator, no UAC prompt), or
'   2. if that task doesn't exist, directly with pythonw.exe (no admin: FPS may be N/A).
Option Explicit
Dim sh, fso, dir
Set sh = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")
dir = fso.GetParentFolderName(WScript.ScriptFullName)
If sh.Run("schtasks.exe /run /tn PerfLink", 0, True) <> 0 Then
    sh.Run "pythonw.exe """ & dir & "\agent\perflink_agent.py"" --tray", 0, False
End If
